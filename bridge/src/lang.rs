//! Aturan "versi asli": buang judul dubbing (Hindi, Bengali, dll.) dari daftar,
//! dan cari subject audio asli + bahasa aslinya untuk subtitle/audio otomatis.

use serde_json::Value;

use moviebox_tui::providers::models::{CatalogItem, MediaDetails};
use moviebox_tui::providers::moviebox::clean_moviebox_title;

const INDIAN_LANGS: &[&str] = &[
    "hindi", "tamil", "telugu", "kannada", "malayalam", "bengali", "bangla", "marathi",
    "punjabi", "gujarati", "urdu", "odia", "bhojpuri",
];

fn lower(s: &str) -> String {
    s.to_ascii_lowercase()
}

/// Bagian "tag" pada judul: isi [..], (..) dan akhiran " - xxx".
fn title_tags(title: &str) -> Vec<String> {
    let mut tags = Vec::new();
    let mut rest = title;
    while let Some(open) = rest.find(['[', '(']) {
        let close_char = if rest[open..].starts_with('[') { ']' } else { ')' };
        let Some(close) = rest[open..].find(close_char) else {
            break;
        };
        tags.push(lower(&rest[open + 1..open + close]));
        rest = &rest[open + close + 1..];
    }
    if let Some(pos) = title.rfind(" - ") {
        tags.push(lower(&title[pos + 3..]));
    }
    tags
}

fn mentions_indian_language(text: &str) -> bool {
    let t = lower(text);
    INDIAN_LANGS.iter().any(|l| t.contains(l))
}

/// Judul versi dubbing, mis. "Avatar [Hindi]", "Naruto (Bengali Dub)", "X - Tamil".
pub fn is_dubbed_title(title: &str) -> bool {
    title_tags(title).iter().any(|tag| {
        mentions_indian_language(tag) || tag.contains("dub") || tag.contains("dubbed")
    })
}

/// Rapikan item beranda/pencarian: buang versi dubbing dan bersihkan judul.
pub fn tidy_items(items: Vec<CatalogItem>) -> Vec<CatalogItem> {
    items
        .into_iter()
        .filter(|item| !is_dubbed_title(&item.title))
        .map(|mut item| {
            let clean = clean_moviebox_title(&item.title).trim().to_string();
            if !clean.is_empty() {
                item.title = clean;
            }
            item
        })
        .collect()
}

fn subject_of(payload: &Value) -> &Value {
    payload
        .get("data")
        .and_then(|d| d.get("subject"))
        .or_else(|| payload.get("subject"))
        .unwrap_or(payload)
}

fn str_field<'a>(v: &'a Value, keys: &[&str]) -> Option<&'a str> {
    keys.iter()
        .find_map(|k| v.get(*k).and_then(|x| x.as_str()))
        .map(str::trim)
        .filter(|s| !s.is_empty())
}

/// Informasi versi asli dari detail mentah.
pub struct OriginalInfo {
    pub subject_id: String,
    pub language: Option<String>,
    pub country: Option<String>,
}

pub fn original_info(raw: &Value, details: &MediaDetails) -> OriginalInfo {
    let subject = subject_of(raw);
    let country = str_field(subject, &["countryName", "country"]).map(str::to_string);
    let mut language = str_field(subject, &["originalLanguage", "language", "lanName"])
        .filter(|l| !l.eq_ignore_ascii_case("original"))
        .map(str::to_string);

    let mut subject_id = details.id.value.clone();

    // 1) Dub yang ditandai "Original" oleh MovieBox.
    let original_dub = details.dubs.iter().find(|d| {
        lower(&d.language).contains("original") || lower(&d.label).contains("original")
    });
    if let Some(dub) = original_dub {
        if !dub.subject_id.is_empty() {
            subject_id = dub.subject_id.clone();
        }
        // "Spanish (Original)" → "Spanish"
        let label = dub
            .label
            .replace("(Original)", "")
            .replace("(original)", "")
            .replace("Original", "")
            .trim()
            .trim_matches(|c: char| c == '-' || c == '(' || c == ')')
            .trim()
            .to_string();
        if !label.is_empty() {
            language = Some(label);
        } else if !lower(&dub.language).contains("original") {
            language = Some(dub.language.clone());
        }
    } else if is_dubbed_title(&details.title) || details.dubs.len() > 1 {
        // 2) Tidak ada tanda "Original": hindari versi dubbing India / "dub".
        let current_is_dub = is_dubbed_title(&details.title)
            || details
                .dubs
                .iter()
                .find(|d| d.subject_id == details.id.value)
                .is_some_and(|d| {
                    mentions_indian_language(&d.language)
                        || mentions_indian_language(&d.label)
                        || lower(&d.label).contains("dub")
                });
        if current_is_dub {
            if let Some(dub) = details.dubs.iter().find(|d| {
                !d.subject_id.is_empty()
                    && !mentions_indian_language(&d.language)
                    && !mentions_indian_language(&d.label)
                    && !lower(&d.label).contains("dub")
            }) {
                subject_id = dub.subject_id.clone();
                language = Some(dub.language.clone());
            }
        }
    }

    OriginalInfo {
        subject_id,
        language,
        country,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use moviebox_tui::providers::models::{
        AudioTrackOption, MediaType, ProviderKind, ProviderMediaId,
    };
    use serde_json::json;

    fn details(id: &str, title: &str, dubs: Vec<(&str, &str, &str)>) -> MediaDetails {
        MediaDetails {
            id: ProviderMediaId {
                provider: ProviderKind::MovieBox,
                value: id.into(),
            },
            title: title.into(),
            media_type: MediaType::Movie,
            year: None,
            description: None,
            tagline: None,
            imdb_rating: None,
            director: None,
            stars: None,
            prints: None,
            audios: None,
            poster_url: None,
            duration: None,
            genres: vec![],
            seasons: vec![],
            dubs: dubs
                .into_iter()
                .map(|(sid, lang, label)| AudioTrackOption {
                    subject_id: sid.into(),
                    language: lang.into(),
                    label: label.into(),
                })
                .collect(),
        }
    }

    #[test]
    fn detects_dubbed_titles() {
        assert!(is_dubbed_title("Avatar [Hindi]"));
        assert!(is_dubbed_title("Naruto (Bengali Dub)"));
        assert!(is_dubbed_title("Squid Game - Tamil"));
        assert!(is_dubbed_title("One Piece [English Dub]"));
        assert!(!is_dubbed_title("Agak Laen"));
        assert!(!is_dubbed_title("Dune (2021)"));
        assert!(!is_dubbed_title("Parasite [CAM]"));
    }

    #[test]
    fn picks_original_dub() {
        let d = details(
            "500",
            "La Casa [Hindi]",
            vec![("500", "Hindi", "Hindi"), ("501", "Original", "Spanish (Original)")],
        );
        let info = original_info(&json!({"countryName": "Spain"}), &d);
        assert_eq!(info.subject_id, "501");
        assert_eq!(info.language.as_deref(), Some("Spanish"));
        assert_eq!(info.country.as_deref(), Some("Spain"));
    }

    #[test]
    fn avoids_indian_dub_without_original_flag() {
        let d = details(
            "1",
            "Avatar [Hindi]",
            vec![("1", "Hindi", "Hindi"), ("2", "English", "English")],
        );
        let info = original_info(&json!({}), &d);
        assert_eq!(info.subject_id, "2");
        assert_eq!(info.language.as_deref(), Some("English"));
    }

    #[test]
    fn keeps_plain_title() {
        let d = details("9", "Agak Laen", vec![]);
        let info = original_info(&json!({"countryName": "Indonesia"}), &d);
        assert_eq!(info.subject_id, "9");
        assert_eq!(info.country.as_deref(), Some("Indonesia"));
    }
}
