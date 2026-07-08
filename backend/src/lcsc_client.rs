//! LCSC API client for fetching part metadata.
//!
//! Uses the official `ips.lcsc.com` endpoint with SHA-1 signing.
//! A fallback scraper for `easyeda.com` is provided for cases where
//! no API key/secret are configured.

use anyhow::{Context, Result};
use serde::Deserialize;
use sha1::Digest;
use tracing::{debug, info, warn};

/// Part information fetched from LCSC / fallback.
#[derive(Debug, Clone, Default)]
pub struct PartInfo {
    #[allow(dead_code)]
    pub lcsc_part_number: String,
    pub mfg_part_number: String,
    pub description: Option<String>,
    pub manufacturer: Option<String>,
    pub package_type: Option<String>,
    pub datasheet_url: Option<String>,
}

// ---------------------------------------------------------------------------
// Official LCSC API (authenticated)
// ---------------------------------------------------------------------------

#[derive(Debug, Deserialize)]
struct LcscApiResponse {
    #[serde(default)]
    result: Option<LcscApiResult>,
}

#[derive(Debug, Deserialize)]
struct LcscApiResult {
    #[serde(default)]
    product: Option<LcscProduct>,
}

#[derive(Debug, Deserialize)]
struct LcscProduct {
    #[serde(rename = "productModel")]
    product_model: Option<String>,
    #[serde(rename = "productIntroEn")]
    product_intro_en: Option<String>,
    #[serde(rename = "brandNameEn")]
    brand_name_en: Option<String>,
    #[serde(rename = "encapStandard")]
    encap_standard: Option<String>,
    #[serde(rename = "productUrl")]
    product_url: Option<String>,
}

/// Client for the LCSC Open API.
pub struct LcscClient {
    api_key: Option<String>,
    api_secret: Option<String>,
    http: reqwest::Client,
}

impl LcscClient {
    pub fn new(api_key: Option<String>, api_secret: Option<String>) -> Self {
        Self {
            api_key,
            api_secret,
            http: reqwest::Client::new(),
        }
    }

    /// Fetch part details from LCSC. Returns `Ok(None)` when no credentials
    /// are configured (caller should try fallback).
    pub async fn fetch_part(&self, lcsc_number: &str) -> Result<Option<PartInfo>> {
        let key = match &self.api_key {
            Some(k) => k,
            None => return Ok(None),
        };
        let secret = match &self.api_secret {
            Some(s) => s,
            None => return Ok(None),
        };

        let timestamp = chrono::Utc::now().timestamp();
        let nonce = format!("{:x}", rand::random::<u64>());

        // Sign: SHA1(key + nonce + secret + timestamp)
        let to_sign = format!("{}{}{}{}", key, nonce, secret, timestamp);
        let signature = hex::encode(sha1::Sha1::digest(to_sign.as_bytes()));

        let url = format!(
            "https://ips.lcsc.com/api/product/detail?productCode={}&lang=en",
            lcsc_number
        );

        let resp = self
            .http
            .get(&url)
            .header("appKey", key)
            .header("nonce", &nonce)
            .header("signature", &signature)
            .header("timestamp", timestamp.to_string())
            .send()
            .await
            .context("LCSC API request failed")?;

        if !resp.status().is_success() {
            warn!("LCSC API returned status {}", resp.status());
            return Ok(None);
        }

        let body: LcscApiResponse = resp.json().await.context("LCSC API JSON parse")?;
        let product = match body.result.and_then(|r| r.product) {
            Some(p) => p,
            None => return Ok(None),
        };

        Ok(Some(PartInfo {
            lcsc_part_number: lcsc_number.to_string(),
            mfg_part_number: product.product_model.unwrap_or_default(),
            description: product.product_intro_en,
            manufacturer: product.brand_name_en,
            package_type: product.encap_standard,
            datasheet_url: product.product_url,
        }))
    }
}

// ---------------------------------------------------------------------------
// EasyEDA fallback (unauthenticated)
// ---------------------------------------------------------------------------

/// Fallback: scrape minimal info from EasyEDA's public API.
/// This endpoint does not require authentication.
pub async fn easyeda_fallback(lcsc_number: &str) -> Result<Option<PartInfo>> {
    let url = format!("https://easyeda.com/api/products/{}", lcsc_number);
    let client = reqwest::Client::new();
    let resp = client.get(&url).send().await;

    let body: serde_json::Value = match resp {
        Ok(r) => {
            if !r.status().is_success() {
                return Ok(None);
            }
            r.json().await.unwrap_or_default()
        }
        Err(e) => {
            debug!("EasyEDA fallback failed: {e}");
            return Ok(None);
        }
    };

    let result = body.get("result").and_then(|r| r.as_object());
    let info = result.map(|r| PartInfo {
        lcsc_part_number: lcsc_number.to_string(),
        mfg_part_number: r
            .get("mpn")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_string(),
        description: r.get("description").and_then(|v| v.as_str()).map(String::from),
        manufacturer: r.get("brand").and_then(|v| v.as_str()).map(String::from),
        package_type: r.get("package").and_then(|v| v.as_str()).map(String::from),
        datasheet_url: r
            .get("datasheet")
            .and_then(|v| v.as_str())
            .and_then(|s| if s.is_empty() { None } else { Some(s.to_string()) }),
    });

    Ok(info)
}

/// Fetch part info from LCSC API or EasyEDA fallback.
pub async fn fetch_part_info(
    lcsc_client: &LcscClient,
    lcsc_number: &str,
) -> PartInfo {
    // Try authenticated LCSC API first
    if let Ok(Some(info)) = lcsc_client.fetch_part(lcsc_number).await {
        if !info.mfg_part_number.is_empty() {
            info!("Fetched part {lcsc_number} from LCSC API");
            return info;
        }
    }

    // Fallback to EasyEDA
    if let Ok(Some(info)) = easyeda_fallback(lcsc_number).await {
        info!("Fetched part {lcsc_number} from EasyEDA fallback");
        return info;
    }

    // Last resort: return a minimal record with what we know
    warn!("No metadata found for part {lcsc_number}, storing minimal record");
    PartInfo {
        lcsc_part_number: lcsc_number.to_string(),
        mfg_part_number: String::new(),
        ..Default::default()
    }
}
