//! StorageSystem — High-Density Inventory Tracking Backend
//!
//! Axum-based REST + WebSocket server with SQLite persistence.
//!
//! # Endpoints
//!
//! | Method | Path                  | Description              |
//! |--------|-----------------------|--------------------------|
//! | GET    | /api/layers           | List storage layers      |
//! | POST   | /api/layers           | Create a layer           |
//! | GET    | /api/containers       | List containers          |
//! | POST   | /api/containers       | Create a container       |
//! | PATCH  | /api/containers/:id   | Update container         |
//! | GET    | /api/components      | List all bags            |
//! | POST   | /api/components      | Add a bag (idempotent)   |
//! | POST   | /api/components/quantity | Update bag quantity   |
//! | POST   | /api/search           | Search containers/parts  |
//! | GET    | /ws                   | WebSocket upgrade        |

mod db;
mod handlers;
mod lcsc_client;
mod models;
mod websocket;

use std::net::SocketAddr;

use axum::body::Body;
use axum::http::{Response, StatusCode};
use axum::routing::{get, patch, post};
use axum::Router;
use sqlx::sqlite::{SqliteConnectOptions, SqlitePoolOptions};
use std::str::FromStr;
use std::sync::Arc;
use tower::service_fn;
use tower_http::cors::CorsLayer;
use tower_http::services::ServeDir;
use tracing::info;

use crate::lcsc_client::LcscClient;
use crate::websocket::{ws_handler, AppState};

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    // Load .env if present
    dotenvy::dotenv().ok();

    // Initialise tracing — default to info if RUST_LOG not set
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| tracing_subscriber::EnvFilter::new("info"))
        )
        .init();

    // Ensure we always print to stderr even before tracing is ready
    eprintln!("StorageSystem backend starting…");

    // Database connection pool (SQLite file-based)
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

    // SQLite performance and correctness PRAGMAs
    sqlx::raw_sql("PRAGMA journal_mode=WAL")
        .execute(&pool)
        .await?;
    sqlx::raw_sql("PRAGMA busy_timeout=5000")
        .execute(&pool)
        .await?;
    sqlx::raw_sql("PRAGMA foreign_keys=ON")
        .execute(&pool)
        .await?;

    // Run migrations (apply SQL file)
    sqlx::raw_sql(include_str!("../migrations/001_initial_schema.sql"))
        .execute(&pool)
        .await?;
    info!("Database schema up to date");

    // LCSC client (optional credentials)
    let lcsc_key = std::env::var("LCSC_API_KEY").ok();
    let lcsc_secret = std::env::var("LCSC_API_SECRET").ok();
    let lcsc_client = LcscClient::new(lcsc_key, lcsc_secret);

    // Shared state
    let state = AppState::new(pool, lcsc_client);

    // Determine dashboard dist path (relative to backend binary / CWD)
    let dist_path = std::env::var("DASHBOARD_DIST")
        .unwrap_or_else(|_| "../dashboard/dist".into());

    // Read index.html once at startup for SPA fallback
    let index_path = format!("{}/index.html", dist_path);
    let index_html = std::sync::Arc::new(
        std::fs::read_to_string(&index_path)
            .unwrap_or_else(|_| {
                tracing::warn!("Dashboard dist not found at {index_path} — build it with: cd dashboard && npm run build");
                String::new()
            })
    );

    // Router
    let app = Router::new()
        // API routes
        .route("/api/layers", get(handlers::list_layers).post(handlers::create_layer))
        .route("/api/containers", get(handlers::list_containers).post(handlers::create_container))
        .route("/api/containers/{id}", patch(handlers::update_container))
        .route("/api/components", get(handlers::list_bags).post(handlers::add_bag))
        .route("/api/components/quantity", post(handlers::update_quantity))
        .route("/api/search", post(handlers::search))
        .route("/ws", get(ws_handler))
        // CORS for API
        .layer(CorsLayer::permissive())
        .with_state(state.clone())
        // Dashboard static files
        .fallback_service(
            ServeDir::new(&dist_path)
                .not_found_service(service_fn(move |_req: axum::http::Request<Body>| {
                    let html = index_html.clone();
                    async move {
                        if html.is_empty() {
                            Ok(Response::builder()
                                .status(StatusCode::NOT_FOUND)
                                .body(Body::from("Dashboard not built — run: cd dashboard && npm run build"))
                                .unwrap())
                        } else {
                            Ok(Response::builder()
                                .header("content-type", "text/html")
                                .body(Body::from(html.as_ref().clone()))
                                .unwrap())
                        }
                    }
                }))
        );

    // Bind
    let host = std::env::var("HOST").unwrap_or_else(|_| "0.0.0.0".into());
    let port = std::env::var("PORT").unwrap_or_else(|_| "8000".into());
    let addr: SocketAddr = format!("{}:{}", host, port).parse()?;

    info!("Server listening on {addr}");
    let listener = tokio::net::TcpListener::bind(addr).await?;
    axum::serve(listener, app).await?;

    Ok(())
}
