use axum::extract::{Path, Query, State};
use axum::http::StatusCode;
use axum::Json;
use serde::Deserialize;

use crate::db;
use crate::lcsc_client;
use crate::models::*;
use crate::websocket::AppState;

fn internal_error(e: impl std::fmt::Display) -> (StatusCode, Json<serde_json::Value>) {
    (StatusCode::INTERNAL_SERVER_ERROR, Json(serde_json::json!({ "error": e.to_string() })))
}
fn bad_request(msg: &str) -> (StatusCode, Json<serde_json::Value>) {
    (StatusCode::BAD_REQUEST, Json(serde_json::json!({ "error": msg })))
}

// ═══════════════════════════════════════════════════════════════════
// Layers
// ═══════════════════════════════════════════════════════════════════

pub async fn list_layers(State(state): State<AppState>) -> Result<Json<Vec<StorageLayer>>, (StatusCode, Json<serde_json::Value>)> {
    db::list_layers(&state.db).await.map(Json).map_err(internal_error)
}

pub async fn create_layer(
    State(state): State<AppState>, Json(payload): Json<CreateLayerRequest>,
) -> Result<(StatusCode, Json<StorageLayer>), (StatusCode, Json<serde_json::Value>)> {
    if payload.name.trim().is_empty() { return Err(bad_request("Layer name cannot be empty")); }
    let layer = db::create_layer(&state.db, payload.name.trim(), payload.description.as_deref())
        .await.map_err(internal_error)?;
    state.broadcast(&WsEvent::LayerCreated { layer_id: layer.id, name: layer.name.clone() });
    Ok((StatusCode::CREATED, Json(layer)))
}

// ═══════════════════════════════════════════════════════════════════
// Containers
// ═══════════════════════════════════════════════════════════════════

#[derive(Deserialize)]
pub struct ListContainersQuery { pub layer_id: Option<i32> }

pub async fn list_containers(
    State(state): State<AppState>, Query(query): Query<ListContainersQuery>,
) -> Result<Json<Vec<Container>>, (StatusCode, Json<serde_json::Value>)> {
    db::list_containers(&state.db, query.layer_id).await.map(Json).map_err(internal_error)
}

pub async fn create_container(
    State(state): State<AppState>, Json(payload): Json<CreateContainerRequest>,
) -> Result<(StatusCode, Json<serde_json::Value>), (StatusCode, Json<serde_json::Value>)> {
    if payload.display_name.trim().is_empty() { return Err(bad_request("Display name cannot be empty")); }

    // Use user-provided ID, or fall back to display_name (the Android app sends cid as both)
    let container_id = payload.id.as_deref().unwrap_or(&payload.display_name).trim().to_string();

    // Dedup: if a container with this display_name already exists on this layer, return it
    if let Ok(Some(existing)) = db::get_container_by_name(&state.db, payload.display_name.trim(), payload.storage_layer_id).await {
        return Ok((StatusCode::OK, Json(serde_json::json!({
            "id": existing.id, "display_name": existing.display_name,
            "storage_layer_id": existing.storage_layer_id, "created": false,
            "message": "Container already exists on this layer"
        }))));
    }

    let c = db::create_container(&state.db, &container_id, payload.display_name.trim(), payload.storage_layer_id)
        .await.map_err(|e| {
            if e.to_string().contains("UNIQUE") {
                bad_request("Container with this ID already exists")
            } else { internal_error(e) }
        })?;
    state.broadcast(&WsEvent::ContainerCreated { container_id: c.id.clone(), layer_id: c.storage_layer_id });
    Ok((StatusCode::CREATED, Json(serde_json::json!({ "created": true, "id": c.id, "display_name": c.display_name, "storage_layer_id": c.storage_layer_id, "created_at": c.created_at, "updated_at": c.updated_at }))))
}

pub async fn update_container(
    State(state): State<AppState>, Path(id): Path<String>, Json(payload): Json<UpdateContainerRequest>,
) -> Result<Json<Container>, (StatusCode, Json<serde_json::Value>)> {
    db::update_container(&state.db, &id, payload.display_name.as_deref(), payload.storage_layer_id)
        .await.map(Json).map_err(internal_error)
}

// ═══════════════════════════════════════════════════════════════════
// Tags
// ═══════════════════════════════════════════════════════════════════

pub async fn get_container_tags(
    State(state): State<AppState>, Path(container_id): Path<String>,
) -> Result<Json<Vec<String>>, (StatusCode, Json<serde_json::Value>)> {
    db::list_container_tags(&state.db, &container_id).await.map(Json).map_err(internal_error)
}

pub async fn add_container_tag(
    State(state): State<AppState>, Path(container_id): Path<String>, Json(payload): Json<TagRequest>,
) -> Result<StatusCode, (StatusCode, Json<serde_json::Value>)> {
    if payload.tag.trim().is_empty() { return Err(bad_request("Tag cannot be empty")); }
    db::add_container_tag(&state.db, &container_id, payload.tag.trim()).await.map_err(internal_error)?;
    Ok(StatusCode::CREATED)
}

pub async fn remove_container_tag(
    State(state): State<AppState>, Path((container_id, tag)): Path<(String, String)>,
) -> Result<StatusCode, (StatusCode, Json<serde_json::Value>)> {
    db::remove_container_tag(&state.db, &container_id, &tag).await.map_err(internal_error)?;
    Ok(StatusCode::NO_CONTENT)
}

#[derive(Deserialize)]
pub struct BagIdQuery { pub bag_id: i32 }

pub async fn get_component_tags(
    State(state): State<AppState>, Query(query): Query<BagIdQuery>,
) -> Result<Json<Vec<String>>, (StatusCode, Json<serde_json::Value>)> {
    db::list_component_tags(&state.db, query.bag_id).await.map(Json).map_err(internal_error)
}

pub async fn add_component_tag(
    State(state): State<AppState>, Query(query): Query<BagIdQuery>, Json(payload): Json<TagRequest>,
) -> Result<StatusCode, (StatusCode, Json<serde_json::Value>)> {
    if payload.tag.trim().is_empty() { return Err(bad_request("Tag cannot be empty")); }
    db::add_component_tag(&state.db, query.bag_id, payload.tag.trim()).await.map_err(internal_error)?;
    Ok(StatusCode::CREATED)
}

pub async fn remove_component_tag(
    State(state): State<AppState>, Path((bag_id, tag)): Path<(i32, String)>,
) -> Result<StatusCode, (StatusCode, Json<serde_json::Value>)> {
    db::remove_component_tag(&state.db, bag_id, &tag).await.map_err(internal_error)?;
    Ok(StatusCode::NO_CONTENT)
}

// ═══════════════════════════════════════════════════════════════════
// Bags
// ═══════════════════════════════════════════════════════════════════

pub async fn list_bags(State(state): State<AppState>) -> Result<Json<Vec<BagWithDetails>>, (StatusCode, Json<serde_json::Value>)> {
    db::list_bags(&state.db).await.map(Json).map_err(internal_error)
}

pub async fn add_bag(
    State(state): State<AppState>, Json(payload): Json<AddBagRequest>,
) -> Result<(StatusCode, Json<AddBagResponse>), (StatusCode, Json<serde_json::Value>)> {
    if payload.quantity < 0 { return Err(bad_request("Quantity cannot be negative")); }

    let part_info = lcsc_client::fetch_part_info(state.lcsc_client.as_ref(), &payload.lcsc_part_number).await;
    db::upsert_part(&state.db, &payload.lcsc_part_number, &part_info.mfg_part_number,
        part_info.description.as_deref(), part_info.manufacturer.as_deref(),
        part_info.package_type.as_deref(), part_info.datasheet_url.as_deref())
        .await.map_err(internal_error)?;

    let (created, current_quantity) = db::add_bag(&state.db, &payload.container_id, &payload.lcsc_part_number,
        payload.quantity, payload.order_number.as_deref(), payload.package_bill_no.as_deref())
        .await.map_err(internal_error)?;

    if created {
        state.broadcast(&WsEvent::BagAdded { container_id: payload.container_id.clone(), lcsc_part_number: payload.lcsc_part_number.clone(), quantity: current_quantity });
    }
    Ok((
        if created { StatusCode::CREATED } else { StatusCode::OK },
        Json(AddBagResponse { created, current_quantity, message: if created { "Bag registered".into() } else { "Bag already exists — existing quantity returned".into() } }),
    ))
}

pub async fn update_quantity(
    State(state): State<AppState>, Json(payload): Json<UpdateQuantityRequest>,
) -> Result<Json<serde_json::Value>, (StatusCode, Json<serde_json::Value>)> {
    if payload.quantity < 0 { return Err(bad_request("Quantity cannot be negative")); }
    db::update_quantity(&state.db, &payload.container_id, &payload.lcsc_part_number, payload.quantity)
        .await.map_err(|e| {
            if let Some(sqlx::Error::RowNotFound) = e.downcast_ref::<sqlx::Error>() {
                (StatusCode::NOT_FOUND, Json(serde_json::json!({ "error": "Bag not found" })))
            } else { internal_error(e) }
        })?;
    state.broadcast(&WsEvent::QuantityUpdated { container_id: payload.container_id.clone(), lcsc_part_number: payload.lcsc_part_number.clone(), new_quantity: payload.quantity });
    Ok(Json(serde_json::json!({ "container_id": payload.container_id, "lcsc_part_number": payload.lcsc_part_number, "new_quantity": payload.quantity })))
}

// ═══════════════════════════════════════════════════════════════════
// Search
// ═══════════════════════════════════════════════════════════════════

pub async fn search(
    State(state): State<AppState>, Json(payload): Json<SearchRequest>,
) -> Result<Json<SearchResult>, (StatusCode, Json<serde_json::Value>)> {
    if payload.term.trim().is_empty() { return Ok(Json(SearchResult { matched_containers: vec![], matched_part_numbers: vec![] })); }
    db::search(&state.db, payload.term.trim()).await.map(Json).map_err(internal_error)
}
