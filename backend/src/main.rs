//! StorageSystem — High-Density Inventory Tracking Backend
//!
//! Axum-based REST + WebSocket server with PostgreSQL persistence.
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
//! | GET    | /api/bags             | List all bags            |
//! | POST   | /api/bags             | Add a bag (idempotent)   |
//! | POST   | /api/bags/quantity    | Update bag quantity      |
//! | POST   | /api/search           | Search containers/parts  |
//! | GET    | /ws                   | WebSocket upgrade        |

mod db;
mod handlers;
mod lcsc_client;
mod models;
mod websocket;

use std::net::SocketAddr;

use axum::routing::{get, patch, post};
use axum::Router;
use sqlx::postgres::PgPoolOptions;
use tower_http::cors::CorsLayer;
use tracing::info;

use crate::lcsc_client::LcscClient;
use crate::websocket::{ws_handler, AppState};

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    // Load .env if present
    dotenvy::dotenv().ok();

    // Initialise tracing
    tracing_subscriber::fmt()
        .with_env_filter(tracing_subscriber::EnvFilter::from_default_env())
        .init();

    // Database connection pool
    let database_url = std::env::var("DATABASE_URL")
        .expect("DATABASE_URL must be set");

    let pool = PgPoolOptions::new()
        .max_connections(20)
        .connect(&database_url)
        .await
        .expect("Failed to connect to PostgreSQL");

    info!("Connected to database");

    // Run migrations (apply SQL file — raw_sql supports multiple statements)
    sqlx::raw_sql(include_str!("../migrations/001_initial_schema.sql"))
        .execute(&pool)
        .await?;
    info!("Database migrations applied");

    // LCSC client (optional credentials)
    let lcsc_key = std::env::var("LCSC_API_KEY").ok();
    let lcsc_secret = std::env::var("LCSC_API_SECRET").ok();
    let lcsc_client = LcscClient::new(lcsc_key, lcsc_secret);

    // Shared state
    let state = AppState::new(pool, lcsc_client);

    // Router
    let app = Router::new()
        // Layers
        .route("/api/layers", get(handlers::list_layers).post(handlers::create_layer))
        // Containers
        .route("/api/containers", get(handlers::list_containers).post(handlers::create_container))
        .route("/api/containers/{id}", patch(handlers::update_container))
        // Bags
        .route("/api/bags", get(handlers::list_bags).post(handlers::add_bag))
        .route("/api/bags/quantity", post(handlers::update_quantity))
        // Search
        .route("/api/search", post(handlers::search))
        // WebSocket
        .route("/ws", get(ws_handler))
        // CORS — allow all origins for development
        .layer(CorsLayer::permissive())
        .with_state(state);

    // Bind
    let host = std::env::var("HOST").unwrap_or_else(|_| "0.0.0.0".into());
    let port = std::env::var("PORT").unwrap_or_else(|_| "8000".into());
    let addr: SocketAddr = format!("{}:{}", host, port).parse()?;

    info!("Server listening on {addr}");
    let listener = tokio::net::TcpListener::bind(addr).await?;
    axum::serve(listener, app).await?;

    Ok(())
}
