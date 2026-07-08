//! Embedded dashboard assets served from the Rust binary.
//!
//! The React dashboard is built and embedded at compile time via
//! [rust-embed]. Any path under `/` that doesn't match an API route
//! is looked up here; SPA-style routing falls back to `index.html`.

use axum::body::Body;
use axum::http::{header, Response, StatusCode};
use rust_embed::RustEmbed;

#[derive(RustEmbed)]
#[folder = "../dashboard/dist"]
struct DashboardAssets;

fn mime_for(path: &str) -> &str {
    if path.ends_with(".js") { "application/javascript" }
    else if path.ends_with(".css") { "text/css" }
    else if path.ends_with(".html") { "text/html" }
    else if path.ends_with(".svg") { "image/svg+xml" }
    else if path.ends_with(".png") { "image/png" }
    else if path.ends_with(".ico") { "image/x-icon" }
    else if path.ends_with(".json") { "application/json" }
    else if path.ends_with(".woff2") { "font/woff2" }
    else if path.ends_with(".woff") { "font/woff" }
    else { "application/octet-stream" }
}

/// Serve a static file from the embedded dashboard, or index.html for SPA
/// fallback (non-file routes like `/containers`, etc.).
pub async fn serve(path: &str) -> Response<Body> {
    let stripped = path.trim_start_matches('/');
    // If the path has no extension or doesn't exist, fall back to index.html
    let has_ext = stripped.contains('.');
    let asset_key = if has_ext && DashboardAssets::get(stripped).is_some() {
        stripped
    } else {
        "index.html"
    };
    let asset = DashboardAssets::get(asset_key);

    match asset {
        Some(data) => Response::builder()
            .header(header::CONTENT_TYPE, mime_for(asset_key))
            .body(Body::from(data.data.to_vec()))
            .unwrap(),
        None => Response::builder()
            .status(StatusCode::NOT_FOUND)
            .body(Body::from("404"))
            .unwrap(),
    }
}
