//! Jalur stream "baru", porting dari Moviebox-API (api.py → MovieBoxEngine).
//!
//! Kenapa perlu: identitas klien di core (`com.community.oneroom` 4.0.01) sekarang
//! dianggap usang oleh server, sehingga play-info/resource hanya mengembalikan video
//! "notice" 23 detik. Di sini dipakai identitas `com.community.mbox.in` 4.0.03 dan
//! endpoint web `wefeed-h5api-bff/subject/play`, sama seperti api.py.

use std::collections::{HashMap, HashSet};

use futures::StreamExt;
use futures::stream::FuturesUnordered;
use std::sync::LazyLock;
use std::time::Duration;

use reqwest::header::{HeaderMap, HeaderName, HeaderValue};
use serde_json::{Value, json};
use tokio::sync::Mutex;

use moviebox_tui::providers::moviebox::{adapt, crypto};

const MODERN_USER_AGENT: &str = "com.community.mbox.in/50020130 (Linux; U; Android 14; en_IN; Pixel 8; Build/UD1A.230803.041; Cronet/145.0.7582.0)";
const WEB_USER_AGENT: &str = "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/154.0.0.0 Mobile Safari/537.36";
const NATIVE_API_BASE: &str = "https://api3.aoneroom.com";
const BOOTSTRAP_URL: &str = "https://apig.inmoviebox.com/wefeed-mobile-bff/tab/ranking-list?tabId=0&categoryType=4516404531735022304&page=1&perPage=1";

const WEB_DOMAINS: &[&str] = &[
    "https://123moviesfree.club",
    "https://movie-box.co",
    "https://movieboxonline.net",
    "https://sflix.film",
    "https://h5-api.aoneroom.com",
    "https://netnaija.film",
    "https://movieboxhd.net",
];

pub struct WebEngine {
    pub(crate) http: reqwest::Client,
    client_info: String,
    token: Mutex<Option<String>>,
    detail_paths: Mutex<HashMap<String, String>>,
    good_domain: Mutex<Option<String>>,
    h5_cookies: Mutex<HashMap<String, String>>,
}

/// Host situs web resmi MovieBox (dipakai pustaka moviebox-api).
const H5_HOSTS: &[&str] = &["h5.aoneroom.com", "moviebox.ph", "moviebox.pk", "moviebox.id", "movieboxapp.in"];

pub static ENGINE: LazyLock<WebEngine> = LazyLock::new(WebEngine::new);

fn now_ms() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}

fn put(headers: &mut HeaderMap, name: &'static str, value: &str) {
    if let Ok(v) = HeaderValue::from_str(value) {
        headers.insert(HeaderName::from_static(name), v);
    }
}

pub(crate) fn as_id(v: Option<&Value>) -> Option<String> {
    let v = v?;
    v.as_str()
        .map(str::to_string)
        .or_else(|| v.as_i64().map(|n| n.to_string()))
        .or_else(|| v.as_u64().map(|n| n.to_string()))
        .filter(|s| !s.is_empty())
}

pub(crate) fn as_u64(v: Option<&Value>) -> Option<u64> {
    let v = v?;
    v.as_u64()
        .or_else(|| v.as_i64().map(|n| n.max(0) as u64))
        .or_else(|| v.as_str().and_then(|s| s.trim().parse().ok()))
}

/// Resolusi tertinggi yang disebut di string seperti "1080,720,480".
pub(crate) fn best_height(resolutions: &str) -> u64 {
    resolutions
        .split(|c: char| !c.is_ascii_digit())
        .filter_map(|s| s.parse::<u64>().ok())
        .filter(|h| (144..=4320).contains(h))
        .max()
        .unwrap_or(0)
}

/// Video "notice" (pesan update aplikasi) — bukan film sungguhan.
pub(crate) fn is_notice(url: &str, duration: Option<u64>) -> bool {
    adapt::is_deprecation_notice_url(url) || duration.is_some_and(|d| d > 0 && d < 90)
}

/// Sama dengan extract_policy_resource() di api.py.
pub(crate) fn mpd_from_sign_cookie(cookie: &str) -> Option<String> {
    adapt::resolve_dash_manifest_from_policy(cookie)
}

pub(crate) fn clean_cookie(cookie: &str) -> String {
    cookie
        .trim_end_matches(';')
        .split(';')
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .collect::<Vec<_>>()
        .join("; ")
}

impl WebEngine {
    fn new() -> Self {
        let http = moviebox_tui::net::http_client_builder()
            .timeout(Duration::from_secs(25))
            .build()
            .unwrap_or_default();
        let device_id = moviebox_tui::cache::md5_hex(&format!(
            "{}-{}",
            now_ms(),
            std::process::id()
        ));
        let client_info = json!({
            "package_name": "com.community.mbox.in",
            "version_name": "4.0.03.0920.03",
            "version_code": 50020130,
            "os": "android",
            "os_version": "14",
            "device_id": device_id,
            "install_store": "official",
            "gaid": "1b2212c1-dadf-43c3-a0c8-bd6ce48ae22d",
            "brand": "Google",
            "model": "Pixel 8",
            "system_language": "en",
            "net": "NETWORK_WIFI",
            "region": "IN",
            "timezone": "Asia/Calcutta",
            "sp_code": "",
        })
        .to_string();
        Self {
            http,
            client_info,
            token: Mutex::new(None),
            detail_paths: Mutex::new(HashMap::new()),
            good_domain: Mutex::new(None),
            h5_cookies: Mutex::new(HashMap::new()),
        }
    }

    fn native_headers(
        &self,
        method: &str,
        url: &str,
        body: Option<&str>,
        token: Option<&str>,
    ) -> HeaderMap {
        let ts = now_ms();
        let accept = "application/json";
        let content_type = "application/json";
        let mut h = HeaderMap::new();
        put(&mut h, "user-agent", MODERN_USER_AGENT);
        put(&mut h, "accept", accept);
        put(&mut h, "content-type", content_type);
        put(&mut h, "connection", "keep-alive");
        put(&mut h, "x-client-token", &crypto::generate_x_client_token(ts));
        put(
            &mut h,
            "x-tr-signature",
            &crypto::generate_x_tr_signature(
                method,
                Some(accept),
                Some(content_type),
                url,
                body,
                ts,
            ),
        );
        put(&mut h, "x-client-info", &self.client_info);
        put(&mut h, "x-client-status", "0");
        if let Some(t) = token {
            put(&mut h, "authorization", &format!("Bearer {t}"));
        }
        h
    }

    pub(crate) async fn token(&self, force: bool) -> Result<String, String> {
        let mut guard = self.token.lock().await;
        if !force {
            if let Some(t) = guard.as_ref() {
                return Ok(t.clone());
            }
        }
        let resp = self
            .http
            .get(BOOTSTRAP_URL)
            .headers(self.native_headers("GET", BOOTSTRAP_URL, None, None))
            .timeout(Duration::from_secs(20))
            .send()
            .await
            .map_err(|e| format!("bootstrap gagal: {e}"))?;
        let status = resp.status().as_u16();
        let x_user = resp
            .headers()
            .get("x-user")
            .and_then(|v| v.to_str().ok())
            .ok_or_else(|| format!("bootstrap: header x-user tidak ada (HTTP {status})"))?;
        let parsed: Value =
            serde_json::from_str(x_user).map_err(|e| format!("bootstrap: x-user rusak: {e}"))?;
        let token = parsed
            .get("token")
            .and_then(|t| t.as_str())
            .filter(|t| !t.is_empty())
            .ok_or("bootstrap: token kosong")?
            .to_string();
        *guard = Some(token.clone());
        Ok(token)
    }

    /// GET ke API native (api3.aoneroom.com) dengan identitas baru. Mengembalikan body penuh.
    async fn native_get(&self, path: &str) -> Result<Value, String> {
        let url = format!("{NATIVE_API_BASE}{path}");
        for attempt in 0..2 {
            let token = self.token(attempt > 0).await?;
            let resp = self
                .http
                .get(&url)
                .headers(self.native_headers("GET", &url, None, Some(&token)))
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

    async fn detail_path(&self, subject_id: &str) -> Result<String, String> {
        let body = self
            .native_get(&format!(
                "/wefeed-mobile-bff/subject-api/get?subjectId={subject_id}"
            ))
            .await?;
        let data = body.get("data").unwrap_or(&body);
        let detail_url = data
            .get("detailUrl")
            .or_else(|| data.get("subject").and_then(|s| s.get("detailUrl")))
            .and_then(|v| v.as_str())
            .unwrap_or("");
        let path = detail_url.rsplit('/').next().unwrap_or(detail_url);
        if path.is_empty() {
            let alt = data
                .get("detailPath")
                .and_then(|v| v.as_str())
                .unwrap_or("");
            return Ok(alt.to_string());
        }
        Ok(path.to_string())
    }

    async fn detail_path_cached(&self, subject_id: &str) -> Result<String, String> {
        if let Some(p) = self.detail_paths.lock().await.get(subject_id) {
            if !p.is_empty() {
                return Ok(p.clone());
            }
        }
        let p = self.detail_path(subject_id).await?;
        if !p.is_empty() {
            self.detail_paths
                .lock()
                .await
                .insert(subject_id.to_string(), p.clone());
        }
        Ok(p)
    }

    /// POST ke API native (api3.aoneroom.com) dengan identitas baru.
    async fn native_post(&self, path: &str, body: &Value) -> Result<Value, String> {
        let url = format!("{NATIVE_API_BASE}{path}");
        let body_str = body.to_string();
        for attempt in 0..2 {
            let token = self.token(attempt > 0).await?;
            let resp = self
                .http
                .post(&url)
                .headers(self.native_headers("POST", &url, Some(&body_str), Some(&token)))
                .body(body_str.clone())
                .timeout(Duration::from_secs(10))
                .send()
                .await
                .map_err(|e| format!("request gagal: {}", short_err(&e)))?;
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

    /// POST pencarian ke situs web (sama seperti movieboxhd.net).
    async fn web_search(&self, domain: &str, token: &str, keyword: &str, page: usize) -> Result<Value, String> {
        if host_dead(domain) {
            return Err("dilewati (domain sedang mati)".to_string());
        }
        let url = format!("{domain}/wefeed-h5api-bff/subject/search");
        let mut h = HeaderMap::new();
        put(&mut h, "user-agent", WEB_USER_AGENT);
        put(&mut h, "referer", &format!("{domain}/"));
        put(&mut h, "accept", "application/json");
        put(&mut h, "content-type", "application/json");
        put(&mut h, "x-client-info", r#"{"timezone":"Asia/Jakarta"}"#);
        put(&mut h, "x-request-lang", "en");
        put(&mut h, "x-source", "moviebox");
        put(&mut h, "authorization", &format!("Bearer {token}"));
        let body = json!({"keyword": keyword, "page": page, "perPage": 24, "subjectType": 0});
        let resp = self
            .http
            .post(&url)
            .headers(h)
            .body(body.to_string())
            .timeout(Duration::from_secs(6))
            .send()
            .await
            .map_err(|e| {
                note_error(domain, &e);
                short_err(&e).to_string()
            })?;
        let status = resp.status().as_u16();
        if status != 200 {
            return Err(format!("HTTP {status}"));
        }
        resp.json::<Value>().await.map_err(|e| format!("JSON: {e}"))
    }

    /// Ambil daftar subject dari jawaban pencarian (bentuk native & web sama: data.items[].subject).
    fn search_items(&self, body: &Value, out: &mut Vec<Value>, paths: &mut Vec<(String, String)>) {
        let data = body.get("data").unwrap_or(body);
        let list = data
            .get("items")
            .or_else(|| data.get("subjects"))
            .or_else(|| data.get("list"))
            .and_then(|v| v.as_array())
            .cloned()
            .unwrap_or_default();
        for item in list {
            let sub = item.get("subject").unwrap_or(&item);
            let Some(id) = as_id(sub.get("subjectId")) else { continue };
            if id == "0" {
                continue;
            }
            let title = sub.get("title").and_then(|v| v.as_str()).unwrap_or("").to_string();
            if title.is_empty() {
                continue;
            }
            let kind = as_u64(sub.get("subjectType")).unwrap_or(1);
            let year: String = sub
                .get("releaseDate")
                .and_then(|v| v.as_str())
                .unwrap_or("")
                .chars()
                .take(4)
                .collect();
            let poster = sub
                .get("cover")
                .and_then(|c| c.get("url"))
                .and_then(|v| v.as_str())
                .unwrap_or("");
            let path = sub.get("detailPath").and_then(|v| v.as_str()).unwrap_or("");
            if !path.is_empty() {
                paths.push((id.clone(), path.to_string()));
            }
            out.push(json!({
                "id": id,
                "title": title,
                "media_type": match kind { 1 => "movie", 2 => "series", _ => "other" },
                "subject_type": kind,
                "genre": sub.get("genre").and_then(|v| v.as_str()).unwrap_or(""),
                "country": sub.get("countryName").and_then(|v| v.as_str()).unwrap_or(""),
                "year": if year.is_empty() { Value::Null } else { json!(year) },
                "poster_url": if poster.is_empty() { Value::Null } else { json!(poster) },
                "detail_path": path,
                "episodes": as_u64(sub.get("totalEpisode")).unwrap_or(0),
            }));
        }
    }

    /// Cookie sesi situs web MovieBox (h5): didapat dari get-latest-app-pkgs, seperti moviebox-api.
    async fn h5_cookie(&self, host: &str) -> Result<String, String> {
        if let Some(c) = self.h5_cookies.lock().await.get(host) {
            return Ok(c.clone());
        }
        if host_dead(host) {
            return Err("dilewati".to_string());
        }
        let url = format!("https://{host}/wefeed-h5-bff/app/get-latest-app-pkgs?app_name=moviebox");
        let resp = self
            .http
            .get(&url)
            .header("user-agent", WEB_USER_AGENT)
            .header("accept", "application/json")
            .header("x-client-info", r#"{"timezone":"Asia/Jakarta"}"#)
            .header("referer", format!("https://{host}/"))
            .timeout(Duration::from_secs(5))
            .send()
            .await
            .map_err(|e| {
                note_error(host, &e);
                short_err(&e).to_string()
            })?;
        let cookie = resp
            .headers()
            .get_all("set-cookie")
            .iter()
            .filter_map(|v| v.to_str().ok())
            .filter_map(|v| v.split(';').next())
            .map(|v| v.trim().to_string())
            .filter(|v| !v.is_empty())
            .collect::<Vec<_>>()
            .join("; ");
        self.h5_cookies.lock().await.insert(host.to_string(), cookie.clone());
        Ok(cookie)
    }

    /// Pencarian situs web resmi (wefeed-h5-bff/web/subject/search).
    async fn h5_search(&self, host: &str, keyword: &str, page: usize) -> Result<Value, String> {
        if host_dead(host) {
            return Err("dilewati (domain sedang mati)".to_string());
        }
        let cookie = self.h5_cookie(host).await.unwrap_or_default();
        let url = format!("https://{host}/wefeed-h5-bff/web/subject/search");
        let body = json!({"keyword": keyword, "page": page, "perPage": 24, "subjectType": 0});
        let mut req = self
            .http
            .post(&url)
            .header("user-agent", WEB_USER_AGENT)
            .header("accept", "application/json")
            .header("content-type", "application/json")
            .header("x-client-info", r#"{"timezone":"Asia/Jakarta"}"#)
            .header("accept-language", "id-ID,id;q=0.9,en;q=0.8")
            .header("referer", format!("https://{host}/"))
            .header("origin", format!("https://{host}"))
            .body(body.to_string())
            .timeout(Duration::from_secs(6));
        if !cookie.is_empty() {
            req = req.header("cookie", cookie);
        }
        let resp = req.send().await.map_err(|e| {
            note_error(host, &e);
            short_err(&e).to_string()
        })?;
        let status = resp.status().as_u16();
        if status != 200 {
            return Err(format!("HTTP {status}"));
        }
        resp.json::<Value>().await.map_err(|e| format!("JSON: {e}"))
    }

    /// Pencarian baru: situs web resmi + API aplikasi terbaru + domain web lain, semua bersamaan.
    /// Hasil web memuat entri yang tidak muncul di pencarian lama (mis. versi lengkap serial).
    pub async fn search(&self, keyword: &str, page: usize) -> (Vec<Value>, Vec<String>) {
        type Fut<'a> = std::pin::Pin<Box<dyn std::future::Future<Output = (String, Result<Value, String>)> + Send + 'a>>;
        let mut jobs: FuturesUnordered<Fut> = FuturesUnordered::new();
        for host in H5_HOSTS {
            jobs.push(Box::pin(async move {
                (format!("h5 {host}"), self.h5_search(host, keyword, page).await)
            }));
        }
        jobs.push(Box::pin(async move {
            let body = json!({"keyword": keyword, "page": page, "perPage": 20});
            let r = tokio::time::timeout(
                Duration::from_secs(9),
                self.native_post("/wefeed-mobile-bff/subject-api/search", &body),
            )
            .await
            .unwrap_or_else(|_| Err("timeout".to_string()));
            ("native".to_string(), r)
        }));
        for domain in WEB_DOMAINS {
            jobs.push(Box::pin(async move {
                let r = match tokio::time::timeout(Duration::from_secs(6), self.token(false)).await {
                    Ok(Ok(token)) => self.web_search(domain, &token, keyword, page).await,
                    Ok(Err(e)) => Err(e),
                    Err(_) => Err("token timeout".to_string()),
                };
                (format!("web {}", domain.trim_start_matches("https://")), r)
            }));
        }

        let mut lines = Vec::new();
        let mut out = Vec::new();
        let mut paths = Vec::new();
        let mut h5_ok = false;
        let mut native_done = false;
        // Batas waktu total supaya layar Cari tidak menunggu lama.
        let deadline = tokio::time::sleep(Duration::from_secs(7));
        tokio::pin!(deadline);
        loop {
            tokio::select! {
                next = jobs.next() => {
                    let Some((name, r)) = next else { break };
                    match r {
                        Ok(body) => {
                            let before = out.len();
                            self.search_items(&body, &mut out, &mut paths);
                            let got = out.len() - before;
                            lines.push(format!("{name}: {got} hasil"));
                            if got > 0 && (name.starts_with("h5") || name.starts_with("web")) {
                                h5_ok = true;
                            }
                        }
                        Err(e) => lines.push(format!("{name}: {e}")),
                    }
                    if name == "native" {
                        native_done = true;
                    }
                    // Cukup: satu situs web menjawab + jalur native selesai.
                    if h5_ok && native_done {
                        break;
                    }
                }
                _ = &mut deadline => {
                    lines.push("batas waktu 7 detik".to_string());
                    break;
                }
            }
        }
        if !paths.is_empty() {
            let mut cache = self.detail_paths.lock().await;
            for (id, p) in paths {
                cache.entry(id).or_insert(p);
            }
        }
        let mut seen = HashSet::new();
        out.retain(|v| seen.insert(v.get("id").and_then(|i| i.as_str()).unwrap_or("").to_string()));
        (out, lines)
    }

    /// Siapkan token lebih awal supaya tombol putar pertama terasa cepat.
    pub async fn warmup(&self) {
        let _ = self.token(false).await;
    }

    /// Port dari MovieBoxEngine.get_streams(), tapi paralel:
    /// semua domain web dicoba bersamaan (yang pertama berhasil dipakai),
    /// dan play-info native jalan berbarengan.
    pub async fn streams(
        &self,
        subject_id: &str,
        season: usize,
        episode: usize,
        diag: &mut Vec<String>,
    ) -> Vec<Value> {
        let web = async {
            let mut lines = Vec::new();
            let (detail_path, token) =
                tokio::join!(self.detail_path_cached(subject_id), self.token(false));
            let detail_path = match detail_path {
                Ok(p) => {
                    lines.push(format!("web: detailPath='{p}'"));
                    p
                }
                Err(e) => {
                    lines.push(format!("web: detailPath gagal: {e}"));
                    String::new()
                }
            };
            let token = match token {
                Ok(t) => t,
                Err(e) => {
                    lines.push(format!("web: {e}"));
                    return (Vec::new(), lines);
                }
            };

            // Domain yang terakhir berhasil dicoba paling depan.
            let preferred = self.good_domain.lock().await.clone();
            let mut domains: Vec<&str> = WEB_DOMAINS.to_vec();
            if let Some(p) = preferred.as_deref() {
                domains.sort_by_key(|d| *d != p);
            }

            let mut pending: FuturesUnordered<_> = domains
                .iter()
                .map(|domain| {
                    let token = token.clone();
                    let detail_path = detail_path.clone();
                    async move {
                        let r = self
                            .web_play(domain, &token, subject_id, season, episode, &detail_path)
                            .await;
                        (*domain, r)
                    }
                })
                .collect();

            while let Some((domain, (items, line))) = pending.next().await {
                lines.push(line);
                if !items.is_empty() {
                    *self.good_domain.lock().await = Some(domain.to_string());
                    return (items, lines);
                }
            }
            (Vec::new(), lines)
        };

        let native = self.native_play(subject_id, season, episode);
        let apig = crate::apig::streams(self, subject_id, season, episode);
        tokio::pin!(web, native, apig);

        // Balapan: begitu satu jalur dapat video, jalur lain hanya ditunggu 1,5 detik lagi
        // (domain yang tidak bisa tersambung tidak perlu ditunggu sampai batas waktunya).
        let mut web_r: Option<(Vec<Value>, Vec<String>)> = None;
        let mut native_r: Option<(Vec<Value>, String)> = None;
        let mut apig_r: Option<(Vec<Value>, Vec<String>)> = None;
        let grace = tokio::time::sleep(Duration::from_secs(3600));
        tokio::pin!(grace);
        let mut grace_on = false;
        loop {
            tokio::select! {
                r = &mut web, if web_r.is_none() => web_r = Some(r),
                r = &mut native, if native_r.is_none() => native_r = Some(r),
                r = &mut apig, if apig_r.is_none() => apig_r = Some(r),
                _ = &mut grace, if grace_on => break,
            }
            if web_r.is_some() && native_r.is_some() && apig_r.is_some() {
                break;
            }
            let found = web_r.as_ref().is_some_and(|r| !r.0.is_empty())
                || native_r.as_ref().is_some_and(|r| !r.0.is_empty())
                || apig_r.as_ref().is_some_and(|r| !r.0.is_empty());
            if found && !grace_on {
                grace_on = true;
                grace.as_mut().reset(tokio::time::Instant::now() + Duration::from_millis(1500));
            }
        }
        let skipped = "(dilewati: sumber lain sudah ditemukan)";
        let (web_items, web_lines) = web_r.unwrap_or_else(|| (Vec::new(), vec![format!("web {skipped}")]));
        let (native_items, native_line) = native_r.unwrap_or_else(|| (Vec::new(), format!("native {skipped}")));
        let (apig_items, apig_lines) = apig_r.unwrap_or_else(|| (Vec::new(), vec![format!("apig {skipped}")]));
        diag.extend(web_lines);
        diag.push(native_line);
        diag.extend(apig_lines);

        let mut seen = HashSet::new();
        let mut out = Vec::new();
        for v in web_items.into_iter().chain(native_items).chain(apig_items) {
            let url = v.get("url").and_then(|u| u.as_str()).unwrap_or("").to_string();
            if !url.is_empty() && seen.insert(url) {
                out.push(v);
            }
        }
        out
    }

    async fn native_play(&self, subject_id: &str, season: usize, episode: usize) -> (Vec<Value>, String) {
        let body = match self
            .native_get(&if season == 0 && episode == 0 {
                format!("/wefeed-mobile-bff/subject-api/play-info?subjectId={subject_id}")
            } else {
                format!("/wefeed-mobile-bff/subject-api/play-info?subjectId={subject_id}&se={season}&ep={episode}")
            })
            .await
        {
            Ok(b) => b,
            Err(e) => return (Vec::new(), format!("native baru GAGAL: {e}")),
        };
        let data = body.get("data").unwrap_or(&body);
        let streams = data
            .get("streams")
            .and_then(|s| s.as_array())
            .cloned()
            .unwrap_or_default();
        let mut out = Vec::new();
        for st in &streams {
            let raw_url = st.get("url").and_then(|u| u.as_str()).unwrap_or("");
            let duration = as_u64(st.get("duration"));
            let cookie = st.get("signCookie").and_then(|c| c.as_str()).unwrap_or("");
            let policy = if cookie.is_empty() {
                None
            } else {
                mpd_from_sign_cookie(cookie)
            };
            if policy.is_none() && (raw_url.is_empty() || is_notice(raw_url, duration)) {
                continue;
            }
            let url = policy.unwrap_or_else(|| raw_url.to_string());
            let res = st.get("resolutions").and_then(|r| r.as_str()).unwrap_or("");
            let mut headers = vec![
                ("User-Agent".to_string(), MODERN_USER_AGENT.to_string()),
                ("Referer".to_string(), "https://api3.aoneroom.com/".to_string()),
            ];
            if !cookie.is_empty() {
                headers.push(("Cookie".to_string(), clean_cookie(cookie)));
            }
            out.push(stream_json(
                "App",
                st.get("format").and_then(|f| f.as_str()).unwrap_or("MP4"),
                best_height(res),
                st.get("codecName").and_then(|c| c.as_str()),
                as_u64(st.get("size")),
                &url,
                headers,
                as_id(st.get("id")),
                duration,
            ));
        }
        let line = format!("native baru: {} stream, {} bisa dipakai", streams.len(), out.len());
        (out, line)
    }

    async fn web_play(
        &self,
        domain: &str,
        token: &str,
        subject_id: &str,
        season: usize,
        episode: usize,
        detail_path: &str,
    ) -> (Vec<Value>, String) {
        let play_url = format!(
            "{domain}/wefeed-h5api-bff/subject/play?subjectId={subject_id}&se={season}&ep={episode}&detailPath={detail_path}&streamSignType=1&supportCodecs%5Bhevc%5D=1&supportCodecs%5Bh264%5D=1"
        );
        let referer = format!(
            "{domain}/movies/{detail_path}?id={subject_id}&type=/movie/detail&detailSe=&detailEp=&lang=en"
        );
        let mut h = HeaderMap::new();
        put(&mut h, "user-agent", WEB_USER_AGENT);
        put(&mut h, "referer", &referer);
        put(&mut h, "accept", "application/json");
        put(&mut h, "x-client-info", r#"{"timezone":"Asia/Calcutta"}"#);
        put(&mut h, "x-request-lang", "en");
        put(&mut h, "x-vip-restrict", "0");
        put(&mut h, "x-no-high-risk-restrict", "0");
        put(&mut h, "x-source", "moviebox");
        put(&mut h, "authorization", &format!("Bearer {token}"));

        let host = domain.trim_start_matches("https://");
        if host_dead(domain) {
            return (Vec::new(), format!("web {host}: dilewati (gagal tersambung barusan)"));
        }
        let resp = match self
            .http
            .get(&play_url)
            .headers(h)
            .timeout(Duration::from_secs(7))
            .send()
            .await
        {
            Ok(r) => r,
            Err(e) => {
                note_error(domain, &e);
                return (Vec::new(), format!("web {host}: gagal ({})", short_err(&e)));
            }
        };
        let status = resp.status().as_u16();
        if status != 200 {
            return (Vec::new(), format!("web {host}: HTTP {status}"));
        }
        let body: Value = match resp.json().await {
            Ok(v) => v,
            Err(e) => return (Vec::new(), format!("web {host}: bukan JSON ({e})")),
        };
        let data = body.get("data").cloned().unwrap_or(Value::Null);
        let raw_streams = data
            .get("streams")
            .and_then(|s| s.as_array())
            .cloned()
            .unwrap_or_default();
        let dash = data
            .get("dash")
            .and_then(|s| s.as_array())
            .cloned()
            .unwrap_or_default();
        if raw_streams.is_empty() && dash.is_empty() {
            let msg = body
                .get("message")
                .or_else(|| body.get("msg"))
                .and_then(|m| m.as_str())
                .unwrap_or("");
            return (Vec::new(), format!("web {host}: kosong {msg}"));
        }

        let web_headers = vec![
            ("User-Agent".to_string(), "Mozilla/5.0".to_string()),
            ("Referer".to_string(), format!("{domain}/")),
            ("Origin".to_string(), domain.to_string()),
        ];
        let mut out = Vec::new();
        let mut notices = 0;

        for st in &raw_streams {
            let Some(url) = st.get("url").and_then(|u| u.as_str()).filter(|u| !u.is_empty()) else {
                continue;
            };
            if is_notice(url, as_u64(st.get("duration"))) {
                notices += 1;
                continue;
            }
            let res = st
                .get("resolutions")
                .map(|r| r.as_str().map(str::to_string).unwrap_or_else(|| r.to_string()))
                .unwrap_or_default();
            out.push(stream_json(
                "Web",
                st.get("format").and_then(|f| f.as_str()).unwrap_or("MP4"),
                best_height(&res),
                st.get("codecName").and_then(|c| c.as_str()),
                as_u64(st.get("size")),
                url,
                web_headers.clone(),
                as_id(st.get("id")),
                as_u64(st.get("duration")),
            ));
        }

        for d in &dash {
            let url = d.get("url").and_then(|u| u.as_str()).unwrap_or("");
            let cookie = d.get("signCookie").and_then(|c| c.as_str()).unwrap_or("");
            let final_url = if cookie.is_empty() {
                None
            } else {
                mpd_from_sign_cookie(cookie)
            }
            .unwrap_or_else(|| url.to_string());
            if final_url.is_empty() || is_notice(&final_url, None) {
                continue;
            }
            let mut headers = web_headers.clone();
            if !cookie.is_empty() {
                headers.push(("Cookie".to_string(), clean_cookie(cookie)));
            }
            out.push(stream_json(
                "Web",
                "DASH",
                0,
                d.get("codecName").and_then(|c| c.as_str()),
                None,
                &final_url,
                headers,
                as_id(d.get("id")),
                as_u64(d.get("duration")),
            ));
        }

        let line = format!(
            "web {host}: {} mp4 + {} dash, {} dipakai, {notices} notice",
            raw_streams.len(),
            dash.len(),
            out.len()
        );
        (out, line)
    }

    /// Port dari MovieBoxEngine.get_subtitles().
    pub async fn captions(&self, subject_id: &str, stream_id: &str, episode: usize) -> Vec<Value> {
        let mut out = Vec::new();
        let mut seen = HashSet::new();
        let paths = [
            format!(
                "/wefeed-mobile-bff/subject-api/get-stream-captions?subjectId={subject_id}&streamId={stream_id}"
            ),
            format!(
                "/wefeed-mobile-bff/subject-api/get-ext-captions?subjectId={subject_id}&resourceId={stream_id}&episode={episode}"
            ),
        ];
        for path in paths {
            let Ok(body) = self.native_get(&path).await else {
                continue;
            };
            let data = body.get("data").cloned().unwrap_or(Value::Null);
            let list = if data.is_array() {
                data
            } else {
                data.get("extCaptions")
                    .or_else(|| data.get("captions"))
                    .cloned()
                    .unwrap_or(Value::Null)
            };
            for item in list.as_array().into_iter().flatten() {
                let Some(url) = item.get("url").and_then(|u| u.as_str()) else {
                    continue;
                };
                if !seen.insert(url.to_string()) {
                    continue;
                }
                let name = item
                    .get("lanName")
                    .or_else(|| item.get("language"))
                    .and_then(|n| n.as_str())
                    .unwrap_or("Subtitle");
                let code = item
                    .get("lan")
                    .or_else(|| item.get("lanCode"))
                    .and_then(|c| c.as_str())
                    .unwrap_or("");
                out.push(json!({ "name": name, "url": url, "code": code }));
            }
        }
        out
    }
}

/// Host yang baru saja gagal tersambung (tidak bisa konek / timeout) dilewati sementara,
/// supaya cek sumber & pencarian tidak menunggu domain mati sampai batas waktunya setiap kali.
static DEAD_HOSTS: LazyLock<std::sync::Mutex<HashMap<String, std::time::Instant>>> =
    LazyLock::new(|| std::sync::Mutex::new(HashMap::new()));
const DEAD_FOR: Duration = Duration::from_secs(15 * 60);

fn host_of(url_or_host: &str) -> String {
    url_or_host
        .trim_start_matches("https://")
        .trim_start_matches("http://")
        .split('/')
        .next()
        .unwrap_or("")
        .to_string()
}

pub(crate) fn host_dead(url_or_host: &str) -> bool {
    let h = host_of(url_or_host);
    DEAD_HOSTS
        .lock()
        .map(|m| m.get(&h).is_some_and(|t| t.elapsed() < DEAD_FOR))
        .unwrap_or(false)
}

pub(crate) fn note_error(url_or_host: &str, e: &reqwest::Error) {
    if e.is_connect() || e.is_timeout() {
        if let Ok(mut m) = DEAD_HOSTS.lock() {
            m.insert(host_of(url_or_host), std::time::Instant::now());
        }
    }
}

fn short_err(e: &reqwest::Error) -> &'static str {
    if e.is_timeout() {
        "timeout"
    } else if e.is_connect() {
        "tidak bisa konek"
    } else {
        "error jaringan"
    }
}

#[allow(clippy::too_many_arguments)]
pub(crate) fn stream_json(
    source: &str,
    format: &str,
    height: u64,
    codec: Option<&str>,
    size: Option<u64>,
    url: &str,
    headers: Vec<(String, String)>,
    resource_id: Option<String>,
    duration: Option<u64>,
) -> Value {
    let quality = if height > 0 {
        format!("{height}p")
    } else {
        "Auto".to_string()
    };
    let label = match codec {
        Some(c) if !c.is_empty() => format!("{quality} {format} {c} • {source}"),
        _ => format!("{quality} {format} • {source}"),
    };
    json!({
        "label": label,
        "quality": quality,
        "height": height,
        "codec": codec,
        "sizeBytes": size,
        "url": url,
        "headers": headers,
        "resourceId": resource_id,
        "durationSec": duration,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn height_and_notice_detection() {
        assert_eq!(best_height("1080,720,480"), 1080);
        assert_eq!(best_height("720"), 720);
        assert_eq!(best_height(""), 0);
        assert!(is_notice(
            "https://macdn.aoneroom.com/other/2026/10/10/80da52722ee7de7a4303290af6783424.mp4",
            Some(23)
        ));
        assert!(is_notice("https://cdn.example/x.mp4", Some(23)));
        assert!(!is_notice("https://cdn.example/x.mp4", Some(7128)));
        assert!(!is_notice("https://cdn.example/x.mp4", None));
    }

    #[test]
    fn stream_json_shape() {
        let v = stream_json(
            "Web",
            "MP4",
            720,
            Some("h264"),
            Some(10),
            "https://x/y.mp4",
            vec![("Referer".into(), "https://a/".into())],
            Some("9".into()),
            Some(7128),
        );
        assert_eq!(v["quality"], "720p");
        assert_eq!(v["height"], 720);
        assert_eq!(v["headers"][0][0], "Referer");
        assert_eq!(v["resourceId"], "9");
        assert_eq!(v["durationSec"], 7128);
    }
}
