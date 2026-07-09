use anyhow::Result;
use serde::Serialize;
use sqlx::SqlitePool;

use crate::models::*;

// ── Layers ──────────────────────────────────────────────────────────

pub async fn list_layers(pool: &SqlitePool) -> Result<Vec<StorageLayer>> {
    Ok(sqlx::query_as::<_, StorageLayer>(
        "SELECT id, name, description, created_at FROM storage_layers ORDER BY name"
    ).fetch_all(pool).await?)
}

pub async fn create_layer(pool: &SqlitePool, name: &str, description: Option<&str>) -> Result<StorageLayer> {
    Ok(sqlx::query_as::<_, StorageLayer>(
        "INSERT INTO storage_layers (name, description) VALUES (?, ?)
         RETURNING id, name, description, created_at"
    ).bind(name).bind(description).fetch_one(pool).await?)
}

// ── Containers ──────────────────────────────────────────────────────

pub async fn list_containers(pool: &SqlitePool, layer_id: Option<i32>) -> Result<Vec<Container>> {
    if let Some(lid) = layer_id {
        Ok(sqlx::query_as::<_, Container>(
            "SELECT id, display_name, storage_layer_id, created_at, updated_at
             FROM containers WHERE storage_layer_id = ? ORDER BY display_name"
        ).bind(lid).fetch_all(pool).await?)
    } else {
        Ok(sqlx::query_as::<_, Container>(
            "SELECT id, display_name, storage_layer_id, created_at, updated_at
             FROM containers ORDER BY display_name"
        ).fetch_all(pool).await?)
    }
}

pub async fn create_container(pool: &SqlitePool, id: &str, display_name: &str, storage_layer_id: i32) -> Result<Container> {
    Ok(sqlx::query_as::<_, Container>(
        "INSERT INTO containers (id, display_name, storage_layer_id)
         VALUES (?, ?, ?)
         RETURNING id, display_name, storage_layer_id, created_at, updated_at"
    ).bind(id).bind(display_name).bind(storage_layer_id).fetch_one(pool).await?)
}

#[allow(dead_code)]
pub async fn get_container(pool: &SqlitePool, id: &str) -> Result<Option<Container>> {
    Ok(sqlx::query_as::<_, Container>(
        "SELECT id, display_name, storage_layer_id, created_at, updated_at FROM containers WHERE id = ?"
    ).bind(id).fetch_optional(pool).await?)
}

/// Check if a container with the same display_name already exists on this layer.
/// Used for dedup: prevents registering the same named container twice.
pub async fn get_container_by_name(pool: &SqlitePool, display_name: &str, layer_id: i32) -> Result<Option<Container>> {
    Ok(sqlx::query_as::<_, Container>(
        "SELECT id, display_name, storage_layer_id, created_at, updated_at
         FROM containers WHERE display_name = ? AND storage_layer_id = ?"
    ).bind(display_name).bind(layer_id).fetch_optional(pool).await?)
}

pub async fn update_container(pool: &SqlitePool, id: &str, display_name: Option<&str>, storage_layer_id: Option<i32>) -> Result<Container> {
    let current = sqlx::query_as::<_, Container>(
        "SELECT id, display_name, storage_layer_id, created_at, updated_at FROM containers WHERE id = ?"
    ).bind(id).fetch_one(pool).await?;
    let new_name = display_name.unwrap_or(&current.display_name);
    let new_layer = storage_layer_id.unwrap_or(current.storage_layer_id);
    Ok(sqlx::query_as::<_, Container>(
        "UPDATE containers SET display_name = ?, storage_layer_id = ?, updated_at = (strftime('%Y-%m-%dT%H:%M:%SZ', 'now'))
         WHERE id = ? RETURNING id, display_name, storage_layer_id, created_at, updated_at"
    ).bind(new_name).bind(new_layer).bind(id).fetch_one(pool).await?)
}

// ── Tags ────────────────────────────────────────────────────────────

pub async fn list_container_tags(pool: &SqlitePool, container_id: &str) -> Result<Vec<String>> {
    Ok(sqlx::query_scalar::<_, String>(
        "SELECT tag FROM container_tags WHERE container_id = ? ORDER BY tag"
    ).bind(container_id).fetch_all(pool).await?)
}

pub async fn add_container_tag(pool: &SqlitePool, container_id: &str, tag: &str) -> Result<()> {
    sqlx::query("INSERT OR IGNORE INTO container_tags (container_id, tag) VALUES (?, ?)")
        .bind(container_id).bind(tag).execute(pool).await?;
    Ok(())
}

pub async fn remove_container_tag(pool: &SqlitePool, container_id: &str, tag: &str) -> Result<()> {
    sqlx::query("DELETE FROM container_tags WHERE container_id = ? AND tag = ?")
        .bind(container_id).bind(tag).execute(pool).await?;
    Ok(())
}

pub async fn list_component_tags(pool: &SqlitePool, bag_id: i32) -> Result<Vec<String>> {
    Ok(sqlx::query_scalar::<_, String>(
        "SELECT tag FROM component_tags WHERE bag_id = ? ORDER BY tag"
    ).bind(bag_id).fetch_all(pool).await?)
}

pub async fn add_component_tag(pool: &SqlitePool, bag_id: i32, tag: &str) -> Result<()> {
    sqlx::query("INSERT OR IGNORE INTO component_tags (bag_id, tag) VALUES (?, ?)")
        .bind(bag_id).bind(tag).execute(pool).await?;
    Ok(())
}

pub async fn remove_component_tag(pool: &SqlitePool, bag_id: i32, tag: &str) -> Result<()> {
    sqlx::query("DELETE FROM component_tags WHERE bag_id = ? AND tag = ?")
        .bind(bag_id).bind(tag).execute(pool).await?;
    Ok(())
}

// ── LCSC Parts ──────────────────────────────────────────────────────

pub async fn upsert_part(
    pool: &SqlitePool, lcsc_part_number: &str, mfg_part_number: &str,
    description: Option<&str>, manufacturer: Option<&str>,
    package_type: Option<&str>, datasheet_url: Option<&str>,
) -> Result<LcscPart> {
    Ok(sqlx::query_as::<_, LcscPart>(
        "INSERT INTO lcsc_parts (lcsc_part_number, mfg_part_number, description, manufacturer, package_type, datasheet_url)
         VALUES (?, ?, ?, ?, ?, ?)
         ON CONFLICT (lcsc_part_number) DO UPDATE SET
            mfg_part_number = excluded.mfg_part_number,
            description = COALESCE(excluded.description, lcsc_parts.description),
            manufacturer = COALESCE(excluded.manufacturer, lcsc_parts.manufacturer),
            package_type = COALESCE(excluded.package_type, lcsc_parts.package_type),
            datasheet_url = COALESCE(excluded.datasheet_url, lcsc_parts.datasheet_url)
         RETURNING lcsc_part_number, mfg_part_number, description, manufacturer, package_type, datasheet_url, price_usd_json, created_at"
    ).bind(lcsc_part_number).bind(mfg_part_number).bind(description).bind(manufacturer).bind(package_type).bind(datasheet_url).fetch_one(pool).await?)
}

// ── Component Bags ──────────────────────────────────────────────────

#[allow(clippy::too_many_arguments)]
pub async fn add_bag(
    pool: &SqlitePool, container_id: &str, lcsc_part_number: &str, quantity: i32,
    order_number: Option<&str>, package_bill_no: Option<&str>,
    manufacturer_code: Option<&str>, carton_count: Option<&str>,
    packing_date: Option<&str>, warehouse_code: Option<&str>,
) -> Result<(bool, i32)> {
    // Dedup by PBN (package_bill_no) — each bag has a unique PICK number
    let result = if let Some(pbn) = package_bill_no {
        sqlx::query_scalar::<_, Option<i32>>(
            "INSERT INTO component_bags (container_id, lcsc_part_number, initial_quantity, current_quantity, order_number, package_bill_no, manufacturer_code, carton_count, packing_date, warehouse_code)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
             ON CONFLICT (package_bill_no) DO NOTHING
             RETURNING current_quantity"
        ).bind(container_id).bind(lcsc_part_number).bind(quantity).bind(quantity)
            .bind(order_number).bind(pbn)
            .bind(manufacturer_code).bind(carton_count).bind(packing_date).bind(warehouse_code)
        .fetch_optional(pool).await?
    } else {
        // No PBN — always insert
        sqlx::query_scalar::<_, Option<i32>>(
            "INSERT INTO component_bags (container_id, lcsc_part_number, initial_quantity, current_quantity, order_number, package_bill_no, manufacturer_code, carton_count, packing_date, warehouse_code)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
             RETURNING current_quantity"
        ).bind(container_id).bind(lcsc_part_number).bind(quantity).bind(quantity)
            .bind(order_number).bind(package_bill_no)
            .bind(manufacturer_code).bind(carton_count).bind(packing_date).bind(warehouse_code)
        .fetch_optional(pool).await?
    };
    match result {
        Some(Some(qty)) => Ok((true, qty)),
        _ => {
            // Conflict — fetch the actual stored quantity from existing bag
            let existing = match package_bill_no {
                Some(pbn) => sqlx::query_scalar::<_, i32>(
                    "SELECT current_quantity FROM component_bags WHERE package_bill_no = ?"
                ).bind(pbn).fetch_optional(pool).await?.unwrap_or(quantity),
                None => quantity,
            };
            Ok((false, existing))
        }
    }
}

pub async fn update_quantity(pool: &SqlitePool, bag_id: i32, new_quantity: i32) -> Result<i32> {
    Ok(sqlx::query_scalar::<_, i32>(
        "UPDATE component_bags SET current_quantity = ?, updated_at = (strftime('%Y-%m-%dT%H:%M:%SZ', 'now'))
         WHERE id = ? RETURNING current_quantity"
    ).bind(new_quantity).bind(bag_id).fetch_one(pool).await?)
}

pub async fn list_bags(pool: &SqlitePool) -> Result<Vec<BagWithDetails>> {
    Ok(sqlx::query_as::<_, BagWithDetails>(
        r#"
        SELECT b.id AS bag_id, b.container_id, b.lcsc_part_number, p.mfg_part_number,
               b.initial_quantity, b.current_quantity, b.order_number, b.package_bill_no,
               b.manufacturer_code, b.carton_count, b.packing_date, b.warehouse_code,
               b.scanned_at, b.updated_at, p.description, p.manufacturer, p.package_type, p.datasheet_url,
               c.display_name AS container_display_name, l.name AS layer_name, l.id AS layer_id
        FROM component_bags b
        JOIN containers c ON c.id = b.container_id
        JOIN storage_layers l ON l.id = c.storage_layer_id
        LEFT JOIN lcsc_parts p ON p.lcsc_part_number = b.lcsc_part_number
        ORDER BY l.name, c.display_name, b.lcsc_part_number
        "#
    ).fetch_all(pool).await?)
}

// ── Search ──────────────────────────────────────────────────────────

pub async fn search(pool: &SqlitePool, term: &str) -> Result<SearchResult> {
    let pattern = format!("%{}%", term);
    let containers = sqlx::query_scalar::<_, String>(
        "SELECT id FROM containers WHERE display_name LIKE ? OR id LIKE ?"
    ).bind(&pattern).bind(&pattern).fetch_all(pool).await?;
    let parts = sqlx::query_scalar::<_, String>(
        "SELECT DISTINCT lcsc_part_number FROM component_bags WHERE lcsc_part_number LIKE ?"
    ).bind(&pattern).fetch_all(pool).await?;
    Ok(SearchResult { matched_containers: containers, matched_part_numbers: parts })
}

// ── Delete ──────────────────────────────────────────────────────────

pub async fn delete_layer(pool: &SqlitePool, id: i32) -> Result<()> {
    sqlx::query("DELETE FROM storage_layers WHERE id = ?").bind(id).execute(pool).await?;
    Ok(())
}

pub async fn delete_container(pool: &SqlitePool, id: &str) -> Result<()> {
    sqlx::query("DELETE FROM containers WHERE id = ?").bind(id).execute(pool).await?;
    Ok(())
}

pub async fn delete_bag(pool: &SqlitePool, bag_id: i32) -> Result<()> {
    sqlx::query("DELETE FROM component_bags WHERE id = ?").bind(bag_id).execute(pool).await?;
    Ok(())
}

// ── Orders ──────────────────────────────────────────────────────────

#[derive(Debug, sqlx::FromRow, Serialize)]
pub struct OrderSummary {
    pub order_number: String,
    pub bag_count: i32,
    pub total_qty: i64,
    pub first_scanned: Option<String>,
}

pub async fn list_orders(pool: &SqlitePool) -> Result<Vec<OrderSummary>> {
    Ok(sqlx::query_as::<_, OrderSummary>(
        "SELECT order_number, COUNT(*) as bag_count, SUM(current_quantity) as total_qty,
                MIN(scanned_at) as first_scanned
         FROM component_bags WHERE order_number IS NOT NULL AND order_number != ''
         GROUP BY order_number ORDER BY first_scanned DESC"
    ).fetch_all(pool).await?)
}
