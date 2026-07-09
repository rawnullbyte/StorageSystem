use serde::{Deserialize, Serialize};

// ── Storage Layer ───────────────────────────────────────────────────

#[derive(Debug, Clone, Serialize, Deserialize, sqlx::FromRow)]
pub struct StorageLayer {
    pub id: i32,
    pub name: String,
    pub description: Option<String>,
    pub created_at: Option<String>,
}

// ── Container (id is TEXT in SQLite — accepts any string, not just UUID) ──

#[derive(Debug, Clone, Serialize, Deserialize, sqlx::FromRow)]
pub struct Container {
    pub id: String,
    pub display_name: String,
    pub storage_layer_id: i32,
    pub created_at: Option<String>,
    pub updated_at: Option<String>,
}

// ── Bags with details ───────────────────────────────────────────────

#[derive(Debug, Clone, Serialize, Deserialize, sqlx::FromRow)]
pub struct BagWithDetails {
    pub bag_id: i32,
    pub container_id: String,
    pub lcsc_part_number: String,
    pub mfg_part_number: String,
    pub initial_quantity: i32,
    pub current_quantity: i32,
    pub order_number: Option<String>,
    pub package_bill_no: Option<String>,
    pub manufacturer_code: Option<String>,
    pub carton_count: Option<String>,
    pub packing_date: Option<String>,
    pub warehouse_code: Option<String>,
    pub scanned_at: Option<String>,
    pub updated_at: Option<String>,
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
    pub created_at: Option<String>,
}

// ── Tags ────────────────────────────────────────────────────────────

#[derive(Debug, Clone, Serialize, Deserialize, sqlx::FromRow)]
#[allow(dead_code)]
pub struct ContainerTag {
    pub id: i32,
    pub container_id: String,
    pub tag: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, sqlx::FromRow)]
#[allow(dead_code)]
pub struct ComponentTag {
    pub id: i32,
    pub bag_id: i32,
    pub tag: String,
}

// ── API Requests ────────────────────────────────────────────────────

#[derive(Debug, Deserialize)]
pub struct CreateLayerRequest {
    pub name: String,
    pub description: Option<String>,
}

#[derive(Debug, Deserialize)]
pub struct CreateContainerRequest {
    pub display_name: String,
    pub storage_layer_id: i32,
    pub id: Option<String>,
}

#[derive(Debug, Deserialize)]
pub struct UpdateContainerRequest {
    pub display_name: Option<String>,
    pub storage_layer_id: Option<i32>,
}

#[derive(Debug, Deserialize)]
pub struct AddBagRequest {
    pub container_id: String,
    pub lcsc_part_number: String,
    #[allow(dead_code)]
    pub mfg_part_number: String,
    pub quantity: i32,
    pub order_number: Option<String>,
    pub package_bill_no: Option<String>,
    pub manufacturer_code: Option<String>,
    pub carton_count: Option<String>,
    pub packing_date: Option<String>,
    pub warehouse_code: Option<String>,
}

#[derive(Debug, Deserialize)]
pub struct UpdateQuantityRequest {
    pub container_id: String,
    pub lcsc_part_number: String,
    pub quantity: i32,
}

#[derive(Debug, Deserialize)]
pub struct SearchRequest {
    pub term: String,
}

#[derive(Debug, Deserialize)]
pub struct TagRequest {
    pub tag: String,
}

// ── API Responses ───────────────────────────────────────────────────

#[derive(Debug, Serialize)]
pub struct AddBagResponse {
    pub created: bool,
    pub current_quantity: i32,
    pub message: String,
}

#[derive(Debug, Serialize)]
pub struct SearchResult {
    pub matched_containers: Vec<String>,
    pub matched_part_numbers: Vec<String>,
}

// ── WebSocket Events ───────────────────────────────────────────────

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type", content = "payload")]
pub enum WsEvent {
    #[serde(rename = "BagAdded")]
    BagAdded { container_id: String, lcsc_part_number: String, quantity: i32 },
    #[serde(rename = "QuantityUpdated")]
    QuantityUpdated { container_id: String, lcsc_part_number: String, new_quantity: i32 },
    #[serde(rename = "ContainerCreated")]
    ContainerCreated { container_id: String, layer_id: i32 },
    #[serde(rename = "ContainerMoved")]
    ContainerMoved { container_id: String, new_layer_id: i32 },
    #[serde(rename = "LayerCreated")]
    LayerCreated { layer_id: i32, name: String },
}
