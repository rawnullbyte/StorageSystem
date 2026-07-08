//! Data models matching the database schema and API request/response types.

use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

// ---------------------------------------------------------------------------
// Database row types (mapped by SQLx)
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, Serialize, Deserialize, sqlx::FromRow)]
pub struct StorageLayer {
    pub id: i32,
    pub name: String,
    pub description: Option<String>,
    pub created_at: Option<DateTime<Utc>>,
}

#[derive(Debug, Clone, Serialize, Deserialize, sqlx::FromRow)]
pub struct Container {
    pub id: Uuid,
    pub display_name: String,
    pub storage_layer_id: i32,
    pub created_at: Option<DateTime<Utc>>,
    pub updated_at: Option<DateTime<Utc>>,
}

/// Flattened bag + container + layer for list endpoints
#[derive(Debug, Clone, Serialize, Deserialize, sqlx::FromRow)]
pub struct BagWithDetails {
    pub bag_id: i32,
    pub container_id: Uuid,
    pub lcsc_part_number: String,
    pub mfg_part_number: String,
    pub initial_quantity: i32,
    pub current_quantity: i32,
    pub order_number: Option<String>,
    pub package_bill_no: Option<String>,
    pub scanned_at: Option<DateTime<Utc>>,
    pub updated_at: Option<DateTime<Utc>>,
    pub description: Option<String>,
    pub manufacturer: Option<String>,
    pub package_type: Option<String>,
    pub datasheet_url: Option<String>,
    pub container_display_name: String,
    pub layer_name: String,
    pub layer_id: i32,
}

#[derive(Debug, Clone, Serialize, Deserialize, sqlx::FromRow)]
pub struct LcscPart {
    pub lcsc_part_number: String,
    pub mfg_part_number: String,
    pub description: Option<String>,
    pub manufacturer: Option<String>,
    pub package_type: Option<String>,
    pub datasheet_url: Option<String>,
    pub price_usd_json: Option<String>,
    pub created_at: Option<DateTime<Utc>>,
}

// ---------------------------------------------------------------------------
// API request payloads
// ---------------------------------------------------------------------------

#[derive(Debug, Deserialize)]
pub struct CreateLayerRequest {
    pub name: String,
    pub description: Option<String>,
}

#[derive(Debug, Deserialize)]
pub struct CreateContainerRequest {
    pub display_name: String,
    pub storage_layer_id: i32,
    pub id: Option<Uuid>, // optional; if missing, server generates
}

#[derive(Debug, Deserialize)]
pub struct UpdateContainerRequest {
    pub display_name: Option<String>,
    pub storage_layer_id: Option<i32>,
}

#[derive(Debug, Deserialize)]
pub struct AddBagRequest {
    pub container_id: Uuid,
    pub lcsc_part_number: String,
    pub mfg_part_number: String,
    pub quantity: i32,
    pub order_number: Option<String>,
    pub package_bill_no: Option<String>,
}

#[derive(Debug, Deserialize)]
pub struct UpdateQuantityRequest {
    pub container_id: Uuid,
    pub lcsc_part_number: String,
    pub quantity: i32,
}

#[derive(Debug, Deserialize)]
pub struct SearchRequest {
    pub term: String,
}

// ---------------------------------------------------------------------------
// API response types
// ---------------------------------------------------------------------------

#[derive(Debug, Serialize)]
pub struct AddBagResponse {
    pub created: bool,
    pub current_quantity: i32,
    pub message: String,
}

#[derive(Debug, Serialize)]
pub struct SearchResult {
    pub matched_containers: Vec<Uuid>,
    pub matched_part_numbers: Vec<String>,
}

// ---------------------------------------------------------------------------
// WebSocket event types
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type", content = "payload")]
pub enum WsEvent {
    #[serde(rename = "BagAdded")]
    BagAdded {
        container_id: Uuid,
        lcsc_part_number: String,
        quantity: i32,
    },
    #[serde(rename = "QuantityUpdated")]
    QuantityUpdated {
        container_id: Uuid,
        lcsc_part_number: String,
        new_quantity: i32,
    },
    #[serde(rename = "ContainerMoved")]
    ContainerMoved {
        container_id: Uuid,
        new_layer_id: i32,
    },
}
