//! Database helper functions wrapping SQLx queries against SQLite.

use anyhow::Result;
use sqlx::SqlitePool;
use uuid::Uuid;

use crate::models::*;

// ---------------------------------------------------------------------------
// Storage Layers
// ---------------------------------------------------------------------------

pub async fn list_layers(pool: &SqlitePool) -> Result<Vec<StorageLayer>> {
    let rows = sqlx::query_as::<_, StorageLayer>(
        "SELECT id, name, description, created_at FROM storage_layers ORDER BY name",
    )
    .fetch_all(pool)
    .await?;
    Ok(rows)
}

pub async fn create_layer(pool: &SqlitePool, name: &str, description: Option<&str>) -> Result<StorageLayer> {
    let row = sqlx::query_as::<_, StorageLayer>(
        "INSERT INTO storage_layers (name, description) VALUES (?, ?)
         RETURNING id, name, description, created_at",
    )
    .bind(name)
    .bind(description)
    .fetch_one(pool)
    .await?;
    Ok(row)
}

// ---------------------------------------------------------------------------
// Containers
// ---------------------------------------------------------------------------

pub async fn list_containers(pool: &SqlitePool, layer_id: Option<i32>) -> Result<Vec<Container>> {
    let rows = if let Some(lid) = layer_id {
        sqlx::query_as::<_, Container>(
            "SELECT id, display_name, storage_layer_id, created_at, updated_at
             FROM containers WHERE storage_layer_id = ? ORDER BY display_name",
        )
        .bind(lid)
        .fetch_all(pool)
        .await?
    } else {
        sqlx::query_as::<_, Container>(
            "SELECT id, display_name, storage_layer_id, created_at, updated_at
             FROM containers ORDER BY display_name",
        )
        .fetch_all(pool)
        .await?
    };
    Ok(rows)
}

/// Create a container. If `id` is None, a v4 UUID is generated in Rust.
pub async fn create_container(
    pool: &SqlitePool,
    display_name: &str,
    storage_layer_id: i32,
    id: Option<Uuid>,
) -> Result<Container> {
    let uid = id.unwrap_or_else(Uuid::new_v4);
    let row = sqlx::query_as::<_, Container>(
        "INSERT INTO containers (id, display_name, storage_layer_id)
         VALUES (?, ?, ?)
         RETURNING id, display_name, storage_layer_id, created_at, updated_at",
    )
    .bind(uid)
    .bind(display_name)
    .bind(storage_layer_id)
    .fetch_one(pool)
    .await?;
    Ok(row)
}

pub async fn update_container(
    pool: &SqlitePool,
    id: Uuid,
    display_name: Option<&str>,
    storage_layer_id: Option<i32>,
) -> Result<Container> {
    let current = sqlx::query_as::<_, Container>(
        "SELECT id, display_name, storage_layer_id, created_at, updated_at
         FROM containers WHERE id = ?",
    )
    .bind(id)
    .fetch_one(pool)
    .await?;

    let new_name = display_name.unwrap_or(&current.display_name);
    let new_layer = storage_layer_id.unwrap_or(current.storage_layer_id);

    let row = sqlx::query_as::<_, Container>(
        "UPDATE containers SET display_name = ?, storage_layer_id = ?, updated_at = (strftime('%Y-%m-%dT%H:%M:%SZ', 'now'))
         WHERE id = ?
         RETURNING id, display_name, storage_layer_id, created_at, updated_at",
    )
    .bind(new_name)
    .bind(new_layer)
    .bind(id)
    .fetch_one(pool)
    .await?;
    Ok(row)
}

pub async fn get_container(pool: &SqlitePool, id: Uuid) -> Result<Option<Container>> {
    let row = sqlx::query_as::<_, Container>(
        "SELECT id, display_name, storage_layer_id, created_at, updated_at
         FROM containers WHERE id = ?",
    )
    .bind(id)
    .fetch_optional(pool)
    .await?;
    Ok(row)
}

// ---------------------------------------------------------------------------
// LCSC Parts
// ---------------------------------------------------------------------------

pub async fn upsert_part(
    pool: &SqlitePool,
    lcsc_part_number: &str,
    mfg_part_number: &str,
    description: Option<&str>,
    manufacturer: Option<&str>,
    package_type: Option<&str>,
    datasheet_url: Option<&str>,
) -> Result<LcscPart> {
    let row = sqlx::query_as::<_, LcscPart>(
        "INSERT INTO lcsc_parts (lcsc_part_number, mfg_part_number, description, manufacturer, package_type, datasheet_url)
         VALUES (?, ?, ?, ?, ?, ?)
         ON CONFLICT (lcsc_part_number) DO UPDATE SET
            mfg_part_number = excluded.mfg_part_number,
            description = COALESCE(excluded.description, lcsc_parts.description),
            manufacturer = COALESCE(excluded.manufacturer, lcsc_parts.manufacturer),
            package_type = COALESCE(excluded.package_type, lcsc_parts.package_type),
            datasheet_url = COALESCE(excluded.datasheet_url, lcsc_parts.datasheet_url)
         RETURNING lcsc_part_number, mfg_part_number, description, manufacturer,
                   package_type, datasheet_url, price_usd_json, created_at",
    )
    .bind(lcsc_part_number)
    .bind(mfg_part_number)
    .bind(description)
    .bind(manufacturer)
    .bind(package_type)
    .bind(datasheet_url)
    .fetch_one(pool)
    .await?;
    Ok(row)
}

// ---------------------------------------------------------------------------
// Component Bags
// ---------------------------------------------------------------------------

/// Insert a bag or — if one already exists for (container_id, lcsc_part_number) —
/// do nothing and return the existing current_quantity.
pub async fn add_bag(
    pool: &SqlitePool,
    container_id: Uuid,
    lcsc_part_number: &str,
    quantity: i32,
    order_number: Option<&str>,
    package_bill_no: Option<&str>,
) -> Result<(bool, i32)> {
    let result = sqlx::query_scalar::<_, Option<i32>>(
        "INSERT INTO component_bags (container_id, lcsc_part_number, initial_quantity, current_quantity, order_number, package_bill_no)
         VALUES (?, ?, ?, ?, ?, ?)
         ON CONFLICT (container_id, lcsc_part_number) DO NOTHING
         RETURNING current_quantity",
    )
    .bind(container_id)
    .bind(lcsc_part_number)
    .bind(quantity)
    .bind(quantity)
    .bind(order_number)
    .bind(package_bill_no)
    .fetch_optional(pool)
    .await?;

    match result {
        Some(Some(qty)) => Ok((true, qty)),
        _ => {
            let existing = sqlx::query_scalar::<_, i32>(
                "SELECT current_quantity FROM component_bags
                 WHERE container_id = ? AND lcsc_part_number = ?",
            )
            .bind(container_id)
            .bind(lcsc_part_number)
            .fetch_one(pool)
            .await?;
            Ok((false, existing))
        }
    }
}

pub async fn update_quantity(
    pool: &SqlitePool,
    container_id: Uuid,
    lcsc_part_number: &str,
    new_quantity: i32,
) -> Result<i32> {
    let qty = sqlx::query_scalar::<_, i32>(
        "UPDATE component_bags SET current_quantity = ?, updated_at = (strftime('%Y-%m-%dT%H:%M:%SZ', 'now'))
         WHERE container_id = ? AND lcsc_part_number = ?
         RETURNING current_quantity",
    )
    .bind(new_quantity)
    .bind(container_id)
    .bind(lcsc_part_number)
    .fetch_one(pool)
    .await?;
    Ok(qty)
}

/// List all bags with joined container/layer/part information.
pub async fn list_bags(pool: &SqlitePool) -> Result<Vec<BagWithDetails>> {
    let rows = sqlx::query_as::<_, BagWithDetails>(
        r#"
        SELECT
            b.id                    AS bag_id,
            b.container_id,
            b.lcsc_part_number,
            p.mfg_part_number,
            b.initial_quantity,
            b.current_quantity,
            b.order_number,
            b.package_bill_no,
            b.scanned_at,
            b.updated_at,
            p.description,
            p.manufacturer,
            p.package_type,
            p.datasheet_url,
            c.display_name          AS container_display_name,
            l.name                  AS layer_name,
            l.id                    AS layer_id
        FROM component_bags b
        JOIN containers c ON c.id = b.container_id
        JOIN storage_layers l ON l.id = c.storage_layer_id
        LEFT JOIN lcsc_parts p ON p.lcsc_part_number = b.lcsc_part_number
        ORDER BY l.name, c.display_name, b.lcsc_part_number
        "#,
    )
    .fetch_all(pool)
    .await?;
    Ok(rows)
}

// ---------------------------------------------------------------------------
// Search
// ---------------------------------------------------------------------------

pub async fn search(pool: &SqlitePool, term: &str) -> Result<SearchResult> {
    let pattern = format!("%{}%", term);

    // Search containers by display_name or UUID (partial string match).
    // SQLite LIKE is case-insensitive for ASCII characters.
    let containers = sqlx::query_scalar::<_, Uuid>(
        "SELECT id FROM containers
         WHERE display_name LIKE ? OR id LIKE ?",
    )
    .bind(&pattern)
    .bind(&pattern)
    .fetch_all(pool)
    .await?;

    // Search LCSC part numbers
    let parts = sqlx::query_scalar::<_, String>(
        "SELECT DISTINCT lcsc_part_number FROM component_bags
         WHERE lcsc_part_number LIKE ?",
    )
    .bind(&pattern)
    .fetch_all(pool)
    .await?;

    Ok(SearchResult {
        matched_containers: containers,
        matched_part_numbers: parts,
    })
}
