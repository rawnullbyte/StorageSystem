//! StorageSystem — High-Density Inventory Tracking Backend
//!
//! Axum-based REST + WebSocket server with SQLite persistence.
//! The React dashboard is embedded in the binary via rust-embed.
//!
//! # Endpoints
//!
//! | Method | Path                         | Description              |
//! |--------|------------------------------|--------------------------|
//! | GET    | /api/layers                  | List storage layers      |
//! | POST   | /api/layers                  | Create a layer           |
//! | GET    | /api/containers              | List containers          |
//! | POST   | /api/containers              | Create a container       |
//! | PATCH  | /api/containers/{id}         | Update container         |
//! | GET    | /api/components              | List all bags            |
//! | POST   | /api/components              | Add a bag (idempotent)   |
//! | POST   | /api/components/quantity     | Update bag quantity      |
//! | POST   | /api/search                  | Search containers/parts  |
//! | GET    | /ws                          | WebSocket upgrade        |
//! | GET    | /* (non-API)                 | Dashboard SPA            |

mod db;
mod handlers;
mod lcsc_client;
mod models;
mod websocket;
mod dashboard;

use std::net::SocketAddr;

use axum::extract::Request;
use axum::routing::{get, patch, post};
use axum::Router;
use sqlx::sqlite::{SqliteConnectOptions, SqlitePoolOptions};
use std::str::FromStr;
use tower_http::cors::CorsLayer;
use tracing::info;

use crate::lcsc_client::LcscClient;
use crate::websocket::{ws_handler, AppState};

/// Catch-all handler that serves the embedded dashboard for non-API paths.
async fn dashboard_handler(req: Request) -> axum::response::Response<axum::body::Body> {
    let path = req.uri().path();
    if path.starts_with("/api/") || path == "/ws" {
        return axum::http::Response::builder()
            .status(axum::http::StatusCode::NOT_FOUND)
            .body(axum::body::Body::from("not found"))
            .unwrap();
    }
    crate::dashboard::serve(path).await
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    dotenvy::dotenv().ok();

    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| tracing_subscriber::EnvFilter::new("info"))
        )
        .init();

    eprintln!("StorageSystem backend starting…");

    let database_url = std::env::var("DATABASE_URL")
        .unwrap_or_else(|_| {
            let home = std::env::var("HOME")
                .unwrap_or_else(|_| "./".into());
            let dir = std::path::PathBuf::from(home)
                .join(".local/share/storagesystem");
            std::fs::create_dir_all(&dir).expect("Failed to create data directory");
            format!("sqlite:{}/inventory.db", dir.display())
        });

    let sqlite_options = SqliteConnectOptions::from_str(&database_url)
        .expect("Invalid DATABASE_URL")
        .create_if_missing(true);

    let pool = SqlitePoolOptions::new()
        .max_connections(5)
        .connect_with(sqlite_options)
        .await
        .expect("Failed to connect to SQLite");

    info!("Connected to database: {database_url}");

    sqlx::raw_sql("PRAGMA journal_mode=WAL").execute(&pool).await?;
    sqlx::raw_sql("PRAGMA busy_timeout=5000").execute(&pool).await?;
    sqlx::raw_sql("PRAGMA foreign_keys=ON").execute(&pool).await?;

    sqlx::raw_sql(include_str!("../migrations/001_initial_schema.sql"))
        .execute(&pool)
        .await?;
    info!("Database schema up to date");

    let lcsc_key = std::env::var("LCSC_API_KEY").ok();
    let lcsc_secret = std::env::var("LCSC_API_SECRET").ok();
    let lcsc_client = LcscClient::new(lcsc_key, lcsc_secret);

    let state = AppState::new(pool, lcsc_client);

    let app = Router::new()
        .route("/api/layers", get(handlers::list_layers).post(handlers::create_layer))
        .route("/api/containers", get(handlers::list_containers).post(handlers::create_container))
        .route("/api/containers/{id}", patch(handlers::update_container))
        .route("/api/components", get(handlers::list_bags).post(handlers::add_bag))
        .route("/api/components/quantity", post(handlers::update_quantity))
        .route("/api/search", post(handlers::search))
        .route("/ws", get(ws_handler))
        .layer(CorsLayer::permissive())
        .with_state(state)
        .fallback(dashboard_handler);

    let host = std::env::var("HOST").unwrap_or_else(|_| "0.0.0.0".into());
    let port = std::env::var("PORT").unwrap_or_else(|_| "8000".into());
    let addr: SocketAddr = format!("{}:{}", host, port).parse()?;

    info!("Server listening on {addr}");
    let listener = tokio::net::TcpListener::bind(addr).await?;
    axum::serve(listener, app).await?;

    Ok(())
}
