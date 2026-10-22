//! JNI bridge between the Android app (Kotlin) and the moviebox-tui core.
//!
//! Every exported function is blocking and returns a JSON string shaped as
//! `{"ok": true, "data": ...}` or `{"ok": false, "error": "..."}`.
//! Kotlin must call them from a background thread (Dispatchers.IO).

use std::collections::{HashMap, HashSet};
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::sync::LazyLock;

use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jint, jstring};
use serde_json::{Value, json};

use moviebox_tui::providers::models::{CatalogItem, ProviderKind};
use moviebox_tui::providers::moviebox::adapt;
use moviebox_tui::service::MovieBoxService;

mod apig;
mod lang;
mod web;

static RUNTIME: LazyLock<tokio::runtime::Runtime> = LazyLock::new(|| {
    tokio::runtime::Builder::new_multi_thread()
        .worker_threads(4)
        .enable_all()
        .build()
        .expect("tokio runtime")
});

static SERVICE: LazyLock<MovieBoxService> = LazyLock::new(MovieBoxService::new);

// ---------------------------------------------------------------------------
// Core logic (plain Rust, testable without JNI)
// ---------------------------------------------------------------------------

fn ok(data: Value) -> String {
    json!({ "ok": true, "data": data }).to_string()
}

fn err(message: impl Into<String>) -> String {
    json!({ "ok": false, "error": message.into() }).to_string()
}

pub fn init(files_dir: &str, cache_dir: &str) -> String {
    let base = std::path::Path::new(files_dir);
    let cache = std::path::Path::new(cache_dir);
    // SAFETY: called once from the UI thread before any other bridge call,
    // so no other thread is reading the environment concurrently.
    unsafe {
        std::env::set_var("HOME", base);
        std::env::set_var("MOVIEBOX_CONFIG_DIR", base.join("config"));
        std::env::set_var("MOVIEBOX_DATA_DIR", base.join("data"));
        std::env::set_var("MOVIEBOX_CACHE_DIR", cache.join("moviebox"));
        std::env::set_var("TMPDIR", cache);
    }
    for dir in [base.join("config"), base.join("data"), cache.join("moviebox")] {
        let _ = std::fs::create_dir_all(dir);
    }
    LazyLock::force(&RUNTIME);
    ok(json!(env!("CARGO_PKG_VERSION")))
}

fn group_title(group: &Value) -> Option<String> {
    for key in ["title", "name", "opName", "tabName", "label"] {
        if let Some(text) = group.get(key).and_then(|v| v.as_str()) {
            let text = text.trim();
            if !text.is_empty() {
                return Some(text.to_string());
            }
        }
    }
    None
}

fn group_subjects(group: &Value) -> Vec<&Value> {
    let mut out = Vec::new();
    if let Some(banners) = group
        .get("banner")
        .and_then(|b| b.get("banners"))
        .and_then(|b| b.as_array())
    {
        out.extend(banners.iter().filter_map(|b| b.get("subject")));
    }
    if let Some(items) = group
        .get("customData")
        .and_then(|c| c.get("items"))
        .and_then(|i| i.as_array())
    {
        out.extend(items.iter().filter_map(|i| i.get("subject")));
    }
    if let Some(subjects) = group.get("subjects").and_then(|s| s.as_array()) {
        out.extend(subjects.iter());
    }
    out
}

/// Turns the raw MovieBox "tab-operating" payload into titled rows.
pub fn homepage_sections(payload: &Value) -> Vec<Value> {
    let groups = payload
        .get("items")
        .and_then(|i| i.as_array())
        .or_else(|| payload.as_array());

    let mut sections = Vec::new();
    let mut seen = HashSet::new();
    if let Some(groups) = groups {
        for (index, group) in groups.iter().enumerate() {
            let is_banner = group.get("banner").is_some();
            let items: Vec<CatalogItem> = lang::tidy_items(
                group_subjects(group)
                    .into_iter()
                    .filter_map(adapt::moviebox_subject_json_to_catalog_item)
                    .filter(|item| seen.insert(item.id.value.clone()))
                    .collect(),
            );
            if items.is_empty() {
                continue;
            }
            let title = group_title(group).unwrap_or_else(|| {
                if is_banner {
                    "Sorotan".to_string()
                } else {
                    format!("Rekomendasi {}", index + 1)
                }
            });
            sections.push(json!({
                "title": title,
                "banner": is_banner,
                "items": items,
            }));
        }
    }
    sections
}

pub fn homepage(tab_id: &str, page: usize) -> String {
    RUNTIME.block_on(async {
        match SERVICE.client.get_homepage(tab_id, page).await {
            Ok(payload) => {
                let mut sections = homepage_sections(&payload);
                if sections.is_empty() {
                    let (items, _) = adapt::moviebox_homepage_json_to_catalog(&payload);
                    let items = lang::tidy_items(items);
                    if !items.is_empty() {
                        sections.push(json!({
                            "title": "Populer",
                            "banner": false,
                            "items": items,
                        }));
                    }
                }
                ok(json!(sections))
            }
            Err(e) => err(e.to_string()),
        }
    })
}

pub fn search(query: &str, page: usize) -> String {
    RUNTIME.block_on(async {
        match SERVICE
            .search_typed(ProviderKind::MovieBox, query, page.max(1))
            .await
        {
            Ok(items) => ok(json!(lang::tidy_items(items))),
            Err(e) => err(e.user_message(ProviderKind::MovieBox)),
        }
    })
}

/// Pencarian baru (api3 + web). Selalu ok; daftar bisa kosong, alasan di "debug".
pub fn web_search(query: &str, page: usize) -> String {
    RUNTIME.block_on(async {
        let (items, lines) = web::ENGINE.search(query, page.max(1)).await;
        json!({ "ok": true, "data": items, "debug": lines.join("\n") }).to_string()
    })
}

pub fn details(subject_id: &str) -> String {
    RUNTIME.block_on(async {
        let raw = match SERVICE.client.get_details(subject_id).await {
            Ok(raw) => raw,
            Err(e) => {
                return err(moviebox_tui::providers::models::ProviderError::from(e)
                    .user_message(ProviderKind::MovieBox));
            }
        };
        match adapt::moviebox_details_json_to_media_details(&raw) {
            Ok(mut details) => {
                let info = lang::original_info(&raw, &details);
                let clean = moviebox_tui::providers::moviebox::clean_moviebox_title(&details.title)
                    .trim()
                    .to_string();
                if !clean.is_empty() {
                    details.title = clean;
                }
                let mut value = json!(details);
                if let Some(obj) = value.as_object_mut() {
                    obj.insert("original_subject_id".into(), json!(info.subject_id));
                    obj.insert("original_language".into(), json!(info.language));
                    obj.insert("country".into(), json!(info.country));
                    let subject = raw
                        .get("data")
                        .and_then(|d| d.get("subject"))
                        .or_else(|| raw.get("subject"))
                        .unwrap_or(&raw);
                    let seconds = ["durationSeconds", "duration"].iter().find_map(|k| {
                        let v = subject.get(*k)?;
                        v.as_u64().or_else(|| v.as_str().and_then(|s| s.trim().parse().ok()))
                    });
                    obj.insert("duration_seconds".into(), json!(seconds));
                    // Data mentah yang mungkin berguna untuk genre/rating usia.
                    for key in ["subjectType", "genre", "countryName", "imdbRatingValue", "ageRating", "contentRating", "certification", "rated"] {
                        if let Some(v) = subject.get(key) {
                            obj.insert(format!("raw_{key}"), v.clone());
                        }
                    }
                }
                ok(value)
            }
            Err(e) => err(e.user_message(ProviderKind::MovieBox)),
        }
    })
}

fn truncate(text: &str, max: usize) -> String {
    if text.len() <= max {
        return text.to_string();
    }
    let mut end = max;
    while !text.is_char_boundary(end) {
        end -= 1;
    }
    format!("{}… (+{} byte)", &text[..end], text.len() - end)
}

/// Ringkasan isi jawaban play-info, untuk diagnosa kalau tidak ada stream.
fn describe_play_info(json: &Value) -> String {
    let data = json.get("data").unwrap_or(json);
    let Some(streams) = data.get("streams").and_then(|s| s.as_array()) else {
        let keys: Vec<&str> = data
            .as_object()
            .map(|o| o.keys().map(String::as_str).collect())
            .unwrap_or_default();
        return format!("tidak ada field 'streams' (field yang ada: {keys:?})");
    };
    if streams.is_empty() {
        return "daftar streams kosong".to_string();
    }
    let parts: Vec<String> = streams
        .iter()
        .map(|s| {
            let url = s.get("url").and_then(|u| u.as_str()).unwrap_or("");
            let host = url
                .split("://")
                .nth(1)
                .and_then(|r| r.split('/').next())
                .unwrap_or("-");
            let notice = adapt::is_deprecation_notice_url(url);
            let cookie = s
                .get("signCookie")
                .and_then(|c| c.as_str())
                .is_some_and(|c| !c.is_empty());
            format!(
                "[host={host}, notice={notice}, cookie={cookie}, format={}]",
                s.get("format").and_then(|f| f.as_str()).unwrap_or("?")
            )
        })
        .collect();
    format!("{} stream: {}", streams.len(), parts.join(" "))
}

fn release_key(rel: &moviebox_tui::providers::models::Release) -> Option<String> {
    rel.direct_url()
        .map(|u| u.split('?').next().unwrap_or(u).to_string())
        .filter(|u| !u.is_empty())
}

/// Hasil stream disimpan sebentar supaya putar ulang / prefetch langsung instan.
static STREAM_CACHE: LazyLock<std::sync::Mutex<HashMap<String, (std::time::Instant, String)>>> =
    LazyLock::new(|| std::sync::Mutex::new(HashMap::new()));
const STREAM_TTL: std::time::Duration = std::time::Duration::from_secs(20 * 60);

pub fn streams(subject_id: &str, season: usize, episode: usize) -> String {
    let key = format!("{subject_id}:{season}:{episode}");
    if let Ok(cache) = STREAM_CACHE.lock() {
        if let Some((at, value)) = cache.get(&key) {
            if at.elapsed() < STREAM_TTL {
                return value.clone();
            }
        }
    }
    let result = RUNTIME.block_on(streams_uncached(subject_id, season, episode, false));
    if result.starts_with("{\"ok\":true") {
        if let Ok(mut cache) = STREAM_CACHE.lock() {
            cache.retain(|_, (at, _)| at.elapsed() < STREAM_TTL);
            cache.insert(key, (std::time::Instant::now(), result.clone()));
        }
    }
    result
}

pub fn warmup() -> String {
    RUNTIME.block_on(async {
        let _ = tokio::join!(web::ENGINE.warmup(), SERVICE.client.ensure_session());
    });
    ok(json!(true))
}

/// Minta ulang dari SEMUA jalur (tanpa cache & tanpa jalan pintas). Dipakai pemutar kalau
/// semua link sebelumnya ditolak server video.
pub fn streams_full(subject_id: &str, season: usize, episode: usize) -> String {
    let key = format!("{subject_id}:{season}:{episode}");
    if let Ok(mut cache) = STREAM_CACHE.lock() {
        cache.remove(&key);
    }
    RUNTIME.block_on(streams_uncached(subject_id, season, episode, true))
}

async fn streams_uncached(subject_id: &str, season: usize, episode: usize, full: bool) -> String {
    let mut diag = vec![format!(
        "subjectId={subject_id} season={season} episode={episode}"
    )];

    // 1) Jalur baru (port dari Moviebox-API): web H5 paralel + play-info identitas baru.
    let fresh = web::ENGINE
        .streams(subject_id, season, episode, &mut diag, full)
        .await;
    if !fresh.is_empty() && !full {
        return ok(json!(sort_streams(fresh)));
    }
    if full {
        // Mode lengkap: link jalur lama (core) ikut ditambahkan sebagai cadangan.
        let mut all = fresh;
        diag.push("--- jalur lama (core, mode lengkap) ---".to_string());
        if let Some(list) = legacy_streams(subject_id, season, episode, &mut diag).await {
            all.extend(list);
        }
        if !all.is_empty() {
            return ok(json!(all));
        }
    }

    // 2) Cadangan: jalur lama core.
    diag.push("--- jalur lama (core) ---".to_string());
    match legacy_streams(subject_id, season, episode, &mut diag).await {
        Some(list) => ok(json!(list)),
        None => json!({
            "ok": false,
            "error": "Server MovieBox tidak memberikan link video untuk judul ini.",
            "debug": diag.join("\n"),
        })
        .to_string(),
    }
}

fn sort_streams(mut list: Vec<Value>) -> Vec<Value> {
    list.sort_by_key(|v| std::cmp::Reverse(v.get("height").and_then(|h| h.as_u64()).unwrap_or(0)));
    list
}

async fn legacy_streams(
    subject_id: &str,
    season: usize,
    episode: usize,
    diag: &mut Vec<String>,
) -> Option<Vec<Value>> {
    let client = &SERVICE.client;
    let page = if episode > 0 { (episode - 1) / 20 + 1 } else { 1 };
    let (play_info, resources) = tokio::join!(
        client.get_play_info(subject_id, season, episode),
        client.get_resources(subject_id, season, episode, page, None, 20),
    );
    let mut releases = Vec::new();
    let mut seen: HashSet<String> = HashSet::new();
        match &play_info {
            Ok(json) => {
                diag.push(format!("play-info: {}", describe_play_info(json)));
                for rel in adapt::moviebox_play_info_json_to_releases(
                    json,
                    season,
                    episode,
                    client.user_agent(),
                ) {
                    if let Some(key) = release_key(&rel) {
                        seen.insert(key);
                    }
                    releases.push(rel);
                }
            }
            Err(e) => diag.push(format!("play-info GAGAL: {e}")),
        }

        match &resources {
            Ok(json) => {
                let all = adapt::moviebox_resource_json_to_releases(json);
                let raw_count = json
                    .get("list")
                    .and_then(|l| l.as_array())
                    .map(|l| l.len())
                    .unwrap_or(0);
                diag.push(format!(
                    "resource: {raw_count} item mentah, {} punya link",
                    all.len()
                ));
                for rel in all {
                    if let Some(key) = release_key(&rel) {
                        if !seen.insert(key) {
                            continue;
                        }
                    }
                    if (season == 0 && episode == 0)
                        || (rel.season == Some(season) && rel.episode == Some(episode))
                        || (rel.season.is_none() && rel.episode.is_none())
                    {
                        releases.push(rel);
                    }
                }
            }
            Err(e) => diag.push(format!("resource GAGAL: {e}")),
        }

        releases.sort_by(|a, b| {
            b.resolution_u64()
                .cmp(&a.resolution_u64())
                .then_with(|| b.size_bytes.cmp(&a.size_bytes))
        });
    if releases.is_empty() {
        let raw = |r: &Result<Value, _>| match r {
            Ok(v) => truncate(&v.to_string(), 2500),
            Err(_) => "-".to_string(),
        };
        diag.push(format!("--- play-info mentah ---\n{}", raw(&play_info)));
        diag.push(format!("--- resource mentah ---\n{}", raw(&resources)));
        return None;
    }
    Some(
        releases
            .iter()
            .filter_map(|rel| {
                let mirror = rel.mirrors.first()?;
                let height = if rel.is_multi_resolution() {
                    0
                } else {
                    rel.resolution_u64()
                };
                Some(json!({
                    "label": mirror.label,
                    "quality": rel.quality,
                    "height": height,
                    "codec": rel.codec,
                    "sizeBytes": rel.size_bytes,
                    "url": mirror.resolver_url,
                    "headers": mirror.headers,
                    "resourceId": rel.resource_id,
                }))
            })
            .collect(),
    )
}

pub fn captions(subject_id: &str, resource_id: &str, season: usize, episode: usize) -> String {
    RUNTIME.block_on(async {
        let mut list: Vec<Value> = SERVICE
            .get_ext_captions(subject_id, resource_id, &[], season, episode)
            .await
            .map(|l| l.into_iter().map(|c| json!(c)).collect())
            .unwrap_or_default();
        if list.is_empty() {
            list = web::ENGINE.captions(subject_id, resource_id, episode).await;
        }
        ok(json!(list))
    })
}

// ---------------------------------------------------------------------------
// JNI glue — class com.moviebox.app.data.Native
// ---------------------------------------------------------------------------

fn read(env: &mut JNIEnv, value: &JString) -> String {
    env.get_string(value).map(Into::into).unwrap_or_default()
}

fn guarded(env: &mut JNIEnv, f: impl FnOnce() -> String) -> jstring {
    let out = catch_unwind(AssertUnwindSafe(f))
        .unwrap_or_else(|_| err("Terjadi kesalahan internal (panic) di core Rust"));
    env.new_string(out)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

fn as_usize(value: jint) -> usize {
    value.max(0) as usize
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_moviebox_app_data_Native_initCore<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    files_dir: JString<'l>,
    cache_dir: JString<'l>,
) -> jstring {
    let files_dir = read(&mut env, &files_dir);
    let cache_dir = read(&mut env, &cache_dir);
    guarded(&mut env, || init(&files_dir, &cache_dir))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_moviebox_app_data_Native_warmup<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
) -> jstring {
    guarded(&mut env, warmup)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_moviebox_app_data_Native_homepage<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    tab_id: JString<'l>,
    page: jint,
) -> jstring {
    let tab_id = read(&mut env, &tab_id);
    guarded(&mut env, || homepage(&tab_id, as_usize(page).max(1)))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_moviebox_app_data_Native_search<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    query: JString<'l>,
    page: jint,
) -> jstring {
    let query = read(&mut env, &query);
    guarded(&mut env, || search(&query, as_usize(page)))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_moviebox_app_data_Native_webSearch<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    query: JString<'l>,
    page: jint,
) -> jstring {
    let query = read(&mut env, &query);
    guarded(&mut env, || web_search(&query, as_usize(page)))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_moviebox_app_data_Native_details<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    subject_id: JString<'l>,
) -> jstring {
    let subject_id = read(&mut env, &subject_id);
    guarded(&mut env, || details(&subject_id))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_moviebox_app_data_Native_streams<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    subject_id: JString<'l>,
    season: jint,
    episode: jint,
) -> jstring {
    let subject_id = read(&mut env, &subject_id);
    guarded(&mut env, || {
        streams(&subject_id, as_usize(season), as_usize(episode))
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_moviebox_app_data_Native_streamsFull<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    subject_id: JString<'l>,
    season: jint,
    episode: jint,
) -> jstring {
    let subject_id = read(&mut env, &subject_id);
    guarded(&mut env, || {
        streams_full(&subject_id, as_usize(season), as_usize(episode))
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_moviebox_app_data_Native_captions<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    subject_id: JString<'l>,
    resource_id: JString<'l>,
    season: jint,
    episode: jint,
) -> jstring {
    let subject_id = read(&mut env, &subject_id);
    let resource_id = read(&mut env, &resource_id);
    guarded(&mut env, || {
        captions(
            &subject_id,
            &resource_id,
            as_usize(season),
            as_usize(episode),
        )
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sections_keep_titles_and_dedupe() {
        let payload = json!({
            "items": [
                { "banner": { "banners": [ { "subject": { "subjectId": "1", "title": "A", "subjectType": 1 } } ] } },
                { "title": "Trending", "subjects": [
                    { "subjectId": "1", "title": "A", "subjectType": 1 },
                    { "subjectId": "2", "title": "B", "subjectType": 2,
                      "cover": { "url": "https://x/y.jpg" }, "releaseDate": "2024-01-01" }
                ] },
                { "title": "Kosong", "subjects": [] }
            ]
        });
        let sections = homepage_sections(&payload);
        assert_eq!(sections.len(), 2);
        assert_eq!(sections[0]["title"], "Sorotan");
        assert_eq!(sections[1]["title"], "Trending");
        assert_eq!(sections[1]["items"].as_array().unwrap().len(), 1);
        assert_eq!(sections[1]["items"][0]["id"]["value"], "2");
        assert_eq!(sections[1]["items"][0]["media_type"], "series");
        assert_eq!(sections[1]["items"][0]["poster_url"], "https://x/y.jpg");
        assert_eq!(sections[1]["items"][0]["year"], "2024");
    }

    #[test]
    fn error_envelope_shape() {
        let v: Value = serde_json::from_str(&err("boom")).unwrap();
        assert_eq!(v["ok"], false);
        assert_eq!(v["error"], "boom");
    }
}
