//! JNI bridge between the Android app (Kotlin) and the moviebox-tui core.
//!
//! Every exported function is blocking and returns a JSON string shaped as
//! `{"ok": true, "data": ...}` or `{"ok": false, "error": "..."}`.
//! Kotlin must call them from a background thread (Dispatchers.IO).

use std::collections::HashSet;
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::sync::LazyLock;

use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jint, jstring};
use serde_json::{Value, json};

use moviebox_tui::providers::ReleaseProvider;
use moviebox_tui::providers::models::{CatalogItem, ProviderKind};
use moviebox_tui::providers::moviebox::adapt;
use moviebox_tui::service::MovieBoxService;

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
            let items: Vec<CatalogItem> = group_subjects(group)
                .into_iter()
                .filter_map(adapt::moviebox_subject_json_to_catalog_item)
                .filter(|item| seen.insert(item.id.value.clone()))
                .collect();
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
            Ok(items) => ok(json!(items)),
            Err(e) => err(e.user_message(ProviderKind::MovieBox)),
        }
    })
}

pub fn details(subject_id: &str) -> String {
    RUNTIME.block_on(async {
        match SERVICE
            .details_typed(ProviderKind::MovieBox, subject_id)
            .await
        {
            Ok(details) => ok(json!(details)),
            Err(e) => err(e.user_message(ProviderKind::MovieBox)),
        }
    })
}

pub fn streams(subject_id: &str, season: usize, episode: usize) -> String {
    RUNTIME.block_on(async {
        match SERVICE
            .client
            .episode_streams(subject_id, season, episode)
            .await
        {
            Ok(releases) => {
                let list: Vec<Value> = releases
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
                    .collect();
                ok(json!(list))
            }
            Err(e) => err(e.user_message(ProviderKind::MovieBox)),
        }
    })
}

pub fn captions(subject_id: &str, resource_id: &str, season: usize, episode: usize) -> String {
    RUNTIME.block_on(async {
        match SERVICE
            .get_ext_captions(subject_id, resource_id, &[], season, episode)
            .await
        {
            Ok(list) => ok(json!(list)),
            Err(e) => err(e),
        }
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
