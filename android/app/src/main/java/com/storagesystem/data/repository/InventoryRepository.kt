package com.storagesystem.data.repository

import android.util.Log
import com.storagesystem.data.api.ApiClient
import com.storagesystem.data.api.WebSocketClient
import com.storagesystem.data.ServerSettings
import com.storagesystem.data.models.*
import com.storagesystem.data.models.Container as ContainerModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow

/**
 * Repository layer that wraps [ApiClient] and [WebSocketClient].
 * Provides a single entry point for all data operations.
 */
class InventoryRepository {

    companion object {
        private const val TAG = "InventoryRepository"
    }

    private val wsClient = WebSocketClient()

    val wsEvents: SharedFlow<WsEvent> = wsClient.events

    fun connectWebSocket(scope: CoroutineScope) {
        wsClient.connect(ServerSettings.wsUrl, scope)
    }

    fun disconnectWebSocket() {
        wsClient.disconnect()
    }

    // ─── Layers ─────────────────────────────────────────────────────

    suspend fun getLayers(): Result<List<StorageLayer>> = runCatching {
        ApiClient.getLayers()
    }

    // ─── Containers ─────────────────────────────────────────────────

    suspend fun getContainers(layerId: Int? = null): Result<List<ContainerModel>> = runCatching {
        ApiClient.getContainers(layerId)
    }

    /**
     * Register a container (from QR scan) to a layer.
     * If the container already exists on another layer, the backend will
     * return an error status — we propagate that.
     */
    suspend fun registerContainer(
        displayName: String,
        layerId: Int,
        containerId: String
    ): Result<ContainerModel> = runCatching {
        ApiClient.registerContainer(displayName, layerId, containerId)
    }

    // ─── Bags ───────────────────────────────────────────────────────

    /**
     * Assign a scanned bag to a container.
     * The backend is idempotent — if the bag already exists, it returns
     * the existing quantity without creating a duplicate.
     */
    suspend fun assignBag(request: AddBagRequest): Result<AddBagResponse> = runCatching {
        ApiClient.addBag(request)
    }

    // ─── Search ─────────────────────────────────────────────────────

    suspend fun search(term: String): Result<SearchResult> = runCatching {
        ApiClient.search(term)
    }

    /**
     * Parse a raw QR string to determine its type and extract data.
     */
    fun parseQrCode(rawValue: String): QrParseResult {
        val trimmed = rawValue.trim()

        // Try container QR: JSON with "cid" field
        if (trimmed.startsWith("{") && trimmed.contains("\"cid\"")) {
            return try {
                val data = com.google.gson.Gson().fromJson(trimmed, ContainerQrData::class.java)
                if (data.cid != null) {
                    QrParseResult.Container(cid = data.cid, raw = trimmed)
                } else {
                    QrParseResult.Unknown(raw = trimmed)
                }
            } catch (e: Exception) {
                QrParseResult.Unknown(raw = trimmed)
            }
        }

        // Try LCSC bag QR: key:value pairs (starts with { and contains pbn:/pc:/on:
        // LCSC format: {pbn:PICK...,on:GB...,pc:C...,pm:...,qty:...}
        if (trimmed.startsWith("{") && trimmed.contains("pbn:")) {
            return try {
                val cleaned = trimmed.removeSurrounding("{", "}")
                val kvPairs = cleaned.split(",").associate { kv ->
                    val parts = kv.trim().split(":", limit = 2)
                    parts[0].trim() to parts.getOrElse(1) { "" }.trim()
                }
                QrParseResult.LcscBag(
                    lcscPartNumber = kvPairs["pc"] ?: "",
                    mfgPartNumber = kvPairs["pm"] ?: "",
                    quantity = kvPairs["qty"]?.toIntOrNull() ?: 0,
                    orderNumber = kvPairs["on"],
                    packageBillNo = kvPairs["pbn"],
                    raw = trimmed
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse bag QR: ${e.message}")
                QrParseResult.Unknown(raw = trimmed)
            }
        }

        return QrParseResult.Unknown(raw = trimmed)
    }
}

/**
 * Standalone QR parser for use from UI layer (CameraPreview labels).
 * Delegates to the same logic as InventoryRepository.
 */
fun parseQrRaw(rawValue: String): QrParseResult {
    val trimmed = rawValue.trim()
    if (trimmed.startsWith("{") && trimmed.contains("\"cid\"")) {
        try {
            val data = com.google.gson.Gson().fromJson(trimmed, ContainerQrData::class.java)
            if (data.cid != null) return QrParseResult.Container(cid = data.cid, raw = trimmed)
        } catch (_: Exception) {}
    }
    if (trimmed.startsWith("{") && trimmed.contains("pbn:")) {
        try {
            val cleaned = trimmed.removeSurrounding("{", "}")
            val kvPairs = cleaned.split(",").associate { kv ->
                val parts = kv.trim().split(":", limit = 2)
                parts[0].trim() to parts.getOrElse(1) { "" }.trim()
            }
            return QrParseResult.LcscBag(
                lcscPartNumber = kvPairs["pc"] ?: "",
                mfgPartNumber = kvPairs["pm"] ?: "",
                quantity = kvPairs["qty"]?.toIntOrNull() ?: 0,
                orderNumber = kvPairs["on"],
                packageBillNo = kvPairs["pbn"],
                raw = trimmed
            )
        } catch (_: Exception) {}
    }
    return QrParseResult.Unknown(raw = trimmed)
}

/** Result of parsing a QR code string. */
sealed class QrParseResult {
    data class Container(
        val cid: String,
        val raw: String
    ) : QrParseResult()

    data class LcscBag(
        val lcscPartNumber: String,
        val mfgPartNumber: String,
        val quantity: Int,
        val orderNumber: String?,
        val packageBillNo: String?,
        val raw: String
    ) : QrParseResult()

    data class Unknown(val raw: String) : QrParseResult()
}
