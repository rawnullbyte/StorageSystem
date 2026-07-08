//! REST endpoint handlers for the StorageSystem API.
//!
//! Each handler receives `State<AppState>` (or an extractor that provides
//! a `PgPool`) and returns an Axum response.

use axum::extract::{Path, Query, State};
use axum::http::StatusCode;
use axum::Json;
use serde::Deserialize;
use uuid::Uuid;

use crate::db;
use crate::lcsc_client;
use crate::models::*;
use crate::websocket::AppState;

// ---------------------------------------------------------------------------
// Helper: internal error response
// ---------------------------------------------------------------------------

fn internal_error(e: impl std::fmt::Display) -> (StatusCode, Json<serde_json::Value>) {
    (
        StatusCode::INTERNAL_SERVER_ERROR,
        Json(serde_json::json!({ "error": e.to_string() })),
    )
}

fn bad_request(msg: &str) -> (StatusCode, Json<serde_json::Value>) {
    (
        StatusCode::BAD_REQUEST,
        Json(serde_json::json!({ "error": msg })),
    )
}

// ===========================================================================
// Layers
// ===========================================================================

/// `GET /api/layers` — list all storage layers.
pub async fn list_layers(State(state): State<AppState>) -> Result<Json<Vec<StorageLayer>>, (StatusCode, Json<serde_json::Value>)> {
    db::list_layers(&state.db).await.map(Json).map_err(internal_error)
}

/// `POST /api/layers` — create a new storage layer.
pub async fn create_layer(
    State(state): State<AppState>,
    Json(payload): Json<CreateLayerRequest>,
) -> Result<(StatusCode, Json<StorageLayer>), (StatusCode, Json<serde_json::Value>)> {
    if payload.name.trim().is_empty() {
        return Err(bad_request("Layer name cannot be empty"));
    }
    db::create_layer(&state.db, payload.name.trim(), payload.description.as_deref())
        .await
        .map(|layer| (StatusCode::CREATED, Json(layer)))
        .map_err(internal_error)
}

// ===========================================================================
// Containers
// ===========================================================================

/// Query parameters for `GET /api/containers`.
#[derive(Deserialize)]
pub struct ListContainersQuery {
    pub layer_id: Option<i32>,
}

/// `GET /api/containers` — list containers, optionally filtered by layer.
pub async fn list_containers(
    State(state): State<AppState>,
    Query(query): Query<ListContainersQuery>,
) -> Result<Json<Vec<Container>>, (StatusCode, Json<serde_json::Value>)> {
    db::list_containers(&state.db, query.layer_id)
        .await
        .map(Json)
        .map_err(internal_error)
}

/// `POST /api/containers` — create a new container.
pub async fn create_container(
    State(state): State<AppState>,
    Json(payload): Json<CreateContainerRequest>,
) -> Result<(StatusCode, Json<Container>), (StatusCode, Json<serde_json::Value>)> {
    if payload.display_name.trim().is_empty() {
        return Err(bad_request("Display name cannot be empty"));
    }

    // Verify the layer exists
    let layers = db::list_layers(&state.db).await.map_err(internal_error)?;
    if !layers.iter().any(|l| l.id == payload.storage_layer_id) {
        return Err(bad_request("Specified storage layer does not exist"));
    }

    // If a UUID was provided, check it's not already used
    if let Some(cid) = payload.id {
        if db::get_container(&state.db, cid).await.map_err(internal_error)?.is_some() {
            return Err(bad_request("Container with this UUID already exists"));
        }
    }

    db::create_container(
        &state.db,
        payload.display_name.trim(),
        payload.storage_layer_id,
        payload.id,
    )
    .await
    .map(|c| (StatusCode::CREATED, Json(c)))
    .map_err(internal_error)
}

/// `PATCH /api/containers/:id` — update container name and/or layer.
pub async fn update_container(
    State(state): State<AppState>,
    Path(id): Path<Uuid>,
    Json(payload): Json<UpdateContainerRequest>,
) -> Result<Json<Container>, (StatusCode, Json<serde_json::Value>)> {
    db::update_container(&state.db, id, payload.display_name.as_deref(), payload.storage_layer_id)
        .await
        .map(Json)
        .map_err(internal_error)
}

// ===========================================================================
// Bags
// ===========================================================================

/// `GET /api/components` — list all component bags with joined details.
pub async fn list_bags(
    State(state): State<AppState>,
) -> Result<Json<Vec<BagWithDetails>>, (StatusCode, Json<serde_json::Value>)> {
    db::list_bags(&state.db).await.map(Json).map_err(internal_error)
}

/// `POST /api/components` — add a new bag (idempotent).
pub async fn add_bag(
    State(state): State<AppState>,
    Json(payload): Json<AddBagRequest>,
) -> Result<(StatusCode, Json<AddBagResponse>), (StatusCode, Json<serde_json::Value>)> {
    if payload.quantity < 0 {
        return Err(bad_request("Quantity cannot be negative"));
    }

    // 1. Upsert the LCSC part record (fetch metadata if needed).
    let part_info = lcsc_client::fetch_part_info(state.lcsc_client.as_ref(), &payload.lcsc_part_number).await;
    db::upsert_part(
        &state.db,
        &payload.lcsc_part_number,
        &part_info.mfg_part_number,
        part_info.description.as_deref(),
        part_info.manufacturer.as_deref(),
        part_info.package_type.as_deref(),
        part_info.datasheet_url.as_deref(),
    )
    .await
    .map_err(internal_error)?;

    // 2. Insert bag (idempotent).
    let (created, current_quantity) = db::add_bag(
        &state.db,
        payload.container_id,
        &payload.lcsc_part_number,
        payload.quantity,
        payload.order_number.as_deref(),
        payload.package_bill_no.as_deref(),
    )
    .await
    .map_err(internal_error)?;

    // 3. Broadcast if new.
    if created {
        state.broadcast(&WsEvent::BagAdded {
            container_id: payload.container_id,
            lcsc_part_number: payload.lcsc_part_number.clone(),
            quantity: current_quantity,
        });
    }

    let status = if created {
        StatusCode::CREATED
    } else {
        StatusCode::OK
    };

    Ok((
        status,
        Json(AddBagResponse {
            created,
            current_quantity,
            message: if created {
                "Bag registered".into()
            } else {
                "Bag already exists — existing quantity returned".into()
            },
        }),
    ))
}

/// `POST /api/components/quantity` — manually update bag quantity.
pub async fn update_quantity(
    State(state): State<AppState>,
    Json(payload): Json<UpdateQuantityRequest>,
) -> Result<Json<serde_json::Value>, (StatusCode, Json<serde_json::Value>)> {
    if payload.quantity < 0 {
        return Err(bad_request("Quantity cannot be negative"));
    }

    db::update_quantity(&state.db, payload.container_id, &payload.lcsc_part_number, payload.quantity)
        .await
        .map_err(|e| {
            if matches!(e.downcast_ref::<sqlx::Error>(), Some(&sqlx::Error::RowNotFound)) {
                (
                    StatusCode::NOT_FOUND,
                    Json(serde_json::json!({ "error": "Bag not found for this container and part" })),
                )
            } else {
                internal_error(e)
            }
        })?;

    state.broadcast(&WsEvent::QuantityUpdated {
        container_id: payload.container_id,
        lcsc_part_number: payload.lcsc_part_number.clone(),
        new_quantity: payload.quantity,
    });

    Ok(Json(serde_json::json!({
        "container_id": payload.container_id,
        "lcsc_part_number": payload.lcsc_part_number,
        "new_quantity": payload.quantity,
    })))
}

// ===========================================================================
// Search
// ===========================================================================

/// `POST /api/search` — search containers and parts.
pub async fn search(
    State(state): State<AppState>,
    Json(payload): Json<SearchRequest>,
) -> Result<Json<SearchResult>, (StatusCode, Json<serde_json::Value>)> {
    if payload.term.trim().is_empty() {
        return Ok(Json(SearchResult {
            matched_containers: vec![],
            matched_part_numbers: vec![],
        }));
    }

    db::search(&state.db, payload.term.trim())
        .await
        .map(Json)
        .map_err(internal_error)
}
