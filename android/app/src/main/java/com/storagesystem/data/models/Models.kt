package com.storagesystem.data.models

import com.google.gson.annotations.SerializedName

/**
 * Data classes matching the backend API models and QR code formats.
 */

// ─── QR code formats ────────────────────────────────────────────────

/** Container QR code payload: {"cid": "<uuid>"} */
data class ContainerQrData(
    val cid: String?
)

/** LCSC bag QR code payload (key=value pairs). */
data class LcscBagQrData(
    val pbn: String? = null,  // Package bill number
    val on: String? = null,   // Order number
    val pc: String? = null,   // LCSC part number (product code)
    val pm: String? = null,   // Manufacturer part number
    val qty: String? = null   // Quantity as string
)

// ─── API request / response models ──────────────────────────────────

data class CreateContainerRequest(
    val display_name: String,
    val storage_layer_id: Int,
    val id: String? = null
)

data class AddBagRequest(
    val container_id: String,
    val lcsc_part_number: String,
    val mfg_part_number: String,
    val quantity: Int,
    val order_number: String? = null,
    val package_bill_no: String? = null,
    val manufacturer_code: String? = null,
    val carton_count: String? = null,
    val packing_date: String? = null,
    val warehouse_code: String? = null
)

data class AddBagResponse(
    val created: Boolean,
    val current_quantity: Int,
    val message: String
)

data class SearchResult(
    val matched_containers: List<String>,
    val matched_part_numbers: List<String>
)

// ─── Backend entity models ──────────────────────────────────────────

data class StorageLayer(
    val id: Int,
    val name: String,
    val description: String? = null,
    val created_at: String? = null
)

data class Container(
    val id: String,
    val display_name: String,
    val storage_layer_id: Int,
    val created_at: String? = null,
    val updated_at: String? = null
)

data class ContainerWithLayer(
    val container: Container,
    val layerName: String
)

// ─── WebSocket event models ─────────────────────────────────────────

sealed class WsEvent {
    data class BagAdded(
        val container_id: String,
        val lcsc_part_number: String,
        val quantity: Int
    ) : WsEvent()

    data class QuantityUpdated(
        val container_id: String,
        val lcsc_part_number: String,
        val new_quantity: Int
    ) : WsEvent()

    data class ContainerMoved(
        val container_id: String,
        val new_layer_id: Int
    ) : WsEvent()
}

// ─── App UI state models ────────────────────────────────────────────

enum class ScanMode {
    /** Auto-import containers to selected layer */
    AUTO_IMPORT_CONTAINERS,

    /** Assign scanned bag to a container */
    ASSIGN_BAG,

    /** Search and highlight */
    SEARCH
}

/** Represents a detected QR code in the camera frame. */
data class DetectedQr(
    val rawValue: String,
    val boundingBox: android.graphics.Rect,
    /** Parsed type hint */
    val qrType: QrType = QrType.UNKNOWN
)

enum class QrType {
    CONTAINER,
    LCSC_BAG,
    UNKNOWN
}

/** Overlay visuals for a detected code. */
enum class OverlayColor {
    /** Container not yet assigned */
    YELLOW,
    /** Container assigned OK */
    GREEN,
    /** Bag code detected */
    BLUE,
    /** Search match */
    GREEN_MATCH,
    /** Search non-match */
    RED_NON_MATCH
}
