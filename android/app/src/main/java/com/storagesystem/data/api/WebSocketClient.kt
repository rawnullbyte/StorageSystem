package com.storagesystem.data.api

import android.util.Log
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.storagesystem.data.models.WsEvent
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * WebSocket client that connects to the backend broadcast channel
 * and emits parsed [WsEvent] objects via a Flow.
 */
class WebSocketClient {

    companion object {
        private const val TAG = "WebSocketClient"
        private const val RECONNECT_DELAY_MS = 3000L
    }

    private val client = HttpClient(CIO) {
        install(WebSockets)
    }

    private val _events = MutableSharedFlow<WsEvent>(replay = 0, extraBufferCapacity = 16)
    val events: SharedFlow<WsEvent> = _events.asSharedFlow()

    private var job: Job? = null

    fun connect(url: String, scope: CoroutineScope) {
        job?.cancel()
        job = scope.launch {
            while (isActive) {
                try {
                    client.webSocket(url) {
                        Log.i(TAG, "WebSocket connected to $url")
                        for (frame in incoming) {
                            if (frame is Frame.Text) {
                                val text = frame.readText()
                                handleMessage(text)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "WebSocket disconnected: ${e.message}")
                }
                // Reconnect delay
                delay(RECONNECT_DELAY_MS)
                Log.i(TAG, "Attempting WebSocket reconnect…")
            }
        }
    }

    fun disconnect() {
        job?.cancel()
        job = null
    }

    private fun handleMessage(text: String) {
        try {
            val json = JsonParser.parseString(text).asJsonObject
            val type = json.get("type")?.asString ?: return
            val payload = json.getAsJsonObject("payload") ?: return

            val event: WsEvent? = when (type) {
                "BagAdded" -> WsEvent.BagAdded(
                    container_id = payload.get("container_id").asString,
                    lcsc_part_number = payload.get("lcsc_part_number").asString,
                    quantity = payload.get("quantity").asInt
                )
                "QuantityUpdated" -> WsEvent.QuantityUpdated(
                    container_id = payload.get("container_id").asString,
                    lcsc_part_number = payload.get("lcsc_part_number").asString,
                    new_quantity = payload.get("new_quantity").asInt
                )
                "ContainerMoved" -> WsEvent.ContainerMoved(
                    container_id = payload.get("container_id").asString,
                    new_layer_id = payload.get("new_layer_id").asInt
                )
                else -> null
            }

            if (event != null) {
                _events.tryEmit(event)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse WS message: ${e.message}")
        }
    }
}
