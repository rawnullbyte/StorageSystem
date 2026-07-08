//! WebSocket broadcasting infrastructure.
//!
//! A single `tokio::sync::broadcast` channel is kept as shared application state.
//! Any state-changing handler sends a serialized `WsEvent` to the channel,
//! which fans it out to every connected WebSocket client.

use std::sync::Arc;

use axum::extract::ws::{Message, WebSocket};
use axum::extract::ws::{WebSocketUpgrade};
use axum::extract::State;
use axum::response::IntoResponse;
use futures::{SinkExt, StreamExt};
use serde_json::json;
use tokio::sync::broadcast;
use tracing::info;

use crate::lcsc_client::LcscClient;
use crate::models::WsEvent;

/// Capacity of the broadcast channel (max buffered events per subscriber).
const BROADCAST_CAPACITY: usize = 256;

/// Shared application state (cloned into every handler).
#[derive(Clone)]
pub struct AppState {
    pub db: sqlx::SqlitePool,
    pub tx: broadcast::Sender<String>,
    pub lcsc_client: Arc<LcscClient>,
}

impl AppState {
    pub fn new(db: sqlx::SqlitePool, lcsc_client: LcscClient) -> Self {
        let (tx, _) = broadcast::channel(BROADCAST_CAPACITY);
        Self {
            db,
            tx,
            lcsc_client: Arc::new(lcsc_client),
        }
    }

    /// Serialize and broadcast a `WsEvent` to all connected clients.
    pub fn broadcast(&self, event: &WsEvent) {
        let payload = json!(event).to_string();
        // `send` returns Err only when there are no active receivers, which is
        // normal when no WebSocket client is connected — not worth logging.
        let _ = self.tx.send(payload);
    }
}

/// Handler for `GET /ws` — upgrades to WebSocket.
pub async fn ws_handler(
    ws: WebSocketUpgrade,
    State(state): State<AppState>,
) -> impl IntoResponse {
    ws.on_upgrade(move |socket| handle_socket(socket, state))
}

/// Per-connection task: subscribes to the broadcast channel and forwards
/// messages to the WebSocket client.
async fn handle_socket(socket: WebSocket, state: AppState) {
    let (mut sender, mut receiver) = socket.split();
    let mut rx = state.tx.subscribe();

    // Spawn a task to forward broadcast messages → WebSocket sender.
    let mut send_task = tokio::spawn(async move {
        while let Ok(msg) = rx.recv().await {
            if sender.send(Message::Text(msg)).await.is_err() {
                break; // client disconnected
            }
        }
    });

    // Read incoming messages from the client (for future extensibility).
    let mut recv_task = tokio::spawn(async move {
        while let Some(Ok(_msg)) = receiver.next().await {
            // We don't process client messages yet, but we drain the stream
            // to keep the connection alive. Future use: client commands.
        }
    });

    // If either task finishes, the connection is done — abort the other.
    tokio::select! {
        _ = (&mut send_task) => recv_task.abort(),
        _ = (&mut recv_task) => send_task.abort(),
    }

    info!("WebSocket client disconnected");
}
