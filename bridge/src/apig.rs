//! Jalur stream dari "moviebox-internal-api" (apig.inmoviebox.com):
//! play-info (film tanpa se/ep) → resourceDetectors di detail → play-info per resourceId.

use std::time::Duration;

use reqwest::header::{HeaderMap, HeaderName, HeaderValue};
use serde_json::{Value, json};

use moviebox_tui::providers::moviebox::crypto;

use crate::web::{WebEngine, as_id, as_u64, best_height, clean_cookie, is_notice, mpd_from_sign_cookie, stream_json};

const APIG: &str = "https://apig.inmoviebox.com";
const HOST: &str = "apig.inmoviebox.com";
const APP_UA: &str = "MovieBox/4.0.02 (Android 14; Pixel 6)";
const PLAYER_UA: &str = "ExoPlayerLib/2.18.7";
const APP_ID: &str = "4U01pxRu278GqCZKY9";

fn put(h: &mut HeaderMap, name: &'static str, value: &str) {
    if let Ok(v) = HeaderValue::from_str(value) {
        h.insert(HeaderName::from_static(name), v);
    }
}

fn client_info() -> String {
    let seed = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_nanos();
    let device = format!("86820305{:07}", seed % 10_000_000);
    json!({
        "package_name": "com.community.oneroom",
        "version_name": "4.0.02",
        "version_code": 50020126,
        "os": "android",
        "os_version": "14",
        "install_ch": "ps",
        "device_id": device,
        "install_store": "ps",
        "gaid": "",
        "brand": "Google",
        "model": "Pixel 6",
        "system_language": "en",
        "net": "wifi",
        "region": "IN",
        "timezone": "Asia/Kolkata",
        "sp_code": "404"
    })
    .to_string()
}

fn headers(url: &str, token: &str) -> HeaderMap {
    let ts = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64;
    let accept = "application/json";
    let content_type = "application/json;charset=UTF-8";
    let mut h = HeaderMap::new();
    put(&mut h, "user-agent", APP_UA);
    put(&mut h, "accept", accept);
    put(&mut h, "content-type", content_type);
    put(&mut h, "x-m-version", "4.0.02");
    put(&mut h, "referer", &format!("{APIG}/"));
    put(&mut h, "x-sign-version", "2.0");
    put(&mut h, "x-client-token", &crypto::generate_x_client_token(ts));
    put(&mut h, "x-client-info", &client_info());
    put(&mut h, "x-client-status", "0");
    put(&mut h, "x-play-mode", "2");
    put(&mut h, "appid", APP_ID);
    put(&mut h, "region", "IN");
    put(&mut h, "lang", "en");
    put(&mut h, "os", "android");
    put(&mut h, "x-timestamp", &ts.to_string());
    put(&mut h, "authorization", &format!("Bearer {token}"));
    put(
        &mut h,
        "x-tr-signature",
        &crypto::generate_x_tr_signature("GET", Some(accept), Some(content_type), url, None, ts),
    );
    h
}

async fn get(engine: &WebEngine, path: &str, params: &[(&str, String)]) -> Result<Value, String> {
    let mut query: Vec<(String, String)> = params.iter().map(|(k, v)| (k.to_string(), v.clone())).collect();
    query.push(("host".to_string(), HOST.to_string()));
    let qs = query
        .iter()
        .map(|(k, v)| format!("{k}={v}"))
        .collect::<Vec<_>>()
        .join("&");
    let url = format!("{APIG}{path}?{qs}");
    for attempt in 0..2 {
        let token = engine.token(attempt > 0).await?;
        let resp = engine
            .http
            .get(&url)
            .headers(headers(&url, &token))
            .timeout(Duration::from_secs(10))
            .send()
            .await
            .map_err(|e| format!("request gagal: {e}"))?;
        let status = resp.status().as_u16();
        if (status == 401 || status == 441) && attempt == 0 {
            continue;
        }
        if status != 200 {
            return Err(format!("HTTP {status}"));
        }
        return resp.json::<Value>().await.map_err(|e| format!("JSON: {e}"));
    }
    Err("token ditolak".to_string())
}

fn stream_headers(cookie: &str) -> Vec<(String, String)> {
    let mut h = vec![("User-Agent".to_string(), PLAYER_UA.to_string())];
    if !cookie.is_empty() {
        h.push(("Cookie".to_string(), clean_cookie(cookie)));
    }
    h
}

/// Ubah satu objek stream (play-info) jadi format aplikasi.
fn from_stream(st: &Value, fallback_cookie: &str, out: &mut Vec<Value>) {
    let raw_url = st.get("url").and_then(|u| u.as_str()).unwrap_or("");
    let cookie = st
        .get("signCookie")
        .and_then(|c| c.as_str())
        .filter(|c| !c.is_empty())
        .unwrap_or(fallback_cookie);
    let duration = as_u64(st.get("duration"));
    let policy = if cookie.is_empty() { None } else { mpd_from_sign_cookie(cookie) };
    if policy.is_none() && (raw_url.is_empty() || is_notice(raw_url, duration)) {
        return;
    }
    let url = policy.unwrap_or_else(|| raw_url.to_string());
    let res = st
        .get("resolutions")
        .or_else(|| st.get("quality"))
        .or_else(|| st.get("resolution"))
        .map(|r| r.as_str().map(str::to_string).unwrap_or_else(|| r.to_string()))
        .unwrap_or_default();
    out.push(stream_json(
        "App2",
        st.get("format").and_then(|f| f.as_str()).unwrap_or("MP4"),
        best_height(&res),
        st.get("codecName").or_else(|| st.get("codec")).and_then(|c| c.as_str()),
        as_u64(st.get("size")),
        &url,
        stream_headers(cookie),
        as_id(st.get("id")).or_else(|| as_id(st.get("resourceId"))),
        duration,
    ));
}

fn play_info_streams(body: &Value, out: &mut Vec<Value>) -> usize {
    let data = body.get("data").unwrap_or(body);
    let cookie = body
        .get("signCookie")
        .or_else(|| data.get("signCookie"))
        .and_then(|c| c.as_str())
        .unwrap_or("");
    let list = data
        .get("streamList")
        .or_else(|| data.get("streams"))
        .and_then(|s| s.as_array())
        .cloned()
        .unwrap_or_default();
    let before = out.len();
    for st in &list {
        from_stream(st, cookie, out);
    }
    out.len() - before
}

pub async fn streams(
    engine: &WebEngine,
    subject_id: &str,
    season: usize,
    episode: usize,
) -> (Vec<Value>, Vec<String>) {
    let is_movie = season == 0 && episode == 0;
    let mut out = Vec::new();
    let mut lines = Vec::new();

    let mut params = vec![("subjectId", subject_id.to_string())];
    if !is_movie {
        params.push(("se", season.to_string()));
        params.push(("ep", episode.to_string()));
    }

    // 1) play-info
    match get(engine, "/wefeed-mobile-bff/subject-api/play-info", &params).await {
        Ok(body) => {
            let n = play_info_streams(&body, &mut out);
            lines.push(format!("apig play-info: {n} dipakai"));
        }
        Err(e) => lines.push(format!("apig play-info GAGAL: {e}")),
    }
    if !out.is_empty() {
        return (out, lines);
    }

    // 2) resourceDetectors dari detail
    let detail = match get(
        engine,
        "/wefeed-mobile-bff/subject-api/get",
        &[("subjectId", subject_id.to_string())],
    )
    .await
    {
        Ok(d) => d,
        Err(e) => {
            lines.push(format!("apig detail GAGAL: {e}"));
            return (out, lines);
        }
    };
    let data = detail.get("data").unwrap_or(&detail);
    let detectors = data
        .get("resourceDetectors")
        .and_then(|d| d.as_array())
        .cloned()
        .unwrap_or_default();
    for det in &detectors {
        let det_cookie = det.get("signCookie").and_then(|c| c.as_str()).unwrap_or("");
        let list = det
            .get("resolutionList")
            .and_then(|r| r.as_array())
            .cloned()
            .unwrap_or_default();
        for item in &list {
            if !is_movie {
                let se = as_u64(item.get("se").or_else(|| item.get("season")));
                let ep = as_u64(item.get("ep").or_else(|| item.get("episode")));
                if let (Some(se), Some(ep)) = (se, ep) {
                    if se as usize != season || ep as usize != episode {
                        continue;
                    }
                }
            }
            let url = item
                .get("resourceLink")
                .or_else(|| item.get("downloadUrl"))
                .and_then(|u| u.as_str())
                .unwrap_or("");
            let cookie = item
                .get("signCookie")
                .and_then(|c| c.as_str())
                .filter(|c| !c.is_empty())
                .unwrap_or(det_cookie);
            let duration = as_u64(item.get("duration").or_else(|| det.get("duration")));
            if url.is_empty() || is_notice(url, duration) {
                continue;
            }
            let height = as_u64(item.get("resolution")).unwrap_or(0);
            out.push(stream_json(
                "App2",
                "MP4",
                height,
                item.get("codecName").or_else(|| det.get("codecName")).and_then(|c| c.as_str()),
                as_u64(item.get("size")),
                url,
                stream_headers(cookie),
                as_id(item.get("resourceId")).or_else(|| as_id(det.get("resourceId"))),
                duration,
            ));
        }
    }
    lines.push(format!("apig resourceDetectors: {} detektor, {} dipakai", detectors.len(), out.len()));
    if !out.is_empty() {
        return (out, lines);
    }

    // 3) play-info per resourceId
    let ids: Vec<String> = detectors
        .iter()
        .filter_map(|d| as_id(d.get("resourceId")))
        .take(3)
        .collect();
    for rid in ids {
        let mut p = params.clone();
        p.push(("resourceId", rid.clone()));
        if let Ok(body) = get(engine, "/wefeed-mobile-bff/subject-api/play-info", &p).await {
            let n = play_info_streams(&body, &mut out);
            lines.push(format!("apig resource {rid}: {n} dipakai"));
            if n > 0 {
                break;
            }
        }
    }
    (out, lines)
}
