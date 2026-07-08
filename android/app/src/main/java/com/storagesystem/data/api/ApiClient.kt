package com.storagesystem.data.api

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.storagesystem.data.ServerSettings
import com.storagesystem.data.models.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.gson.*

/**
 * HTTP + WebSocket client for the StorageSystem backend.
 * Uses Ktor with CIO engine and Gson serialization.
 */
object ApiClient {

    private val gson = Gson()

    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            gson {
                setPrettyPrinting()
            }
        }
    }

    private val baseUrl: String get() = ServerSettings.apiBaseUrl

    // ─── Layers ─────────────────────────────────────────────────────

    suspend fun getLayers(): List<StorageLayer> {
        val response = httpClient.get("$baseUrl/api/layers")
        return response.body()
    }

    suspend fun createLayer(name: String, description: String? = null): StorageLayer {
        val response = httpClient.post("$baseUrl/api/layers") {
            contentType(ContentType.Application.Json)
            setBody(mapOf("name" to name, "description" to description))
        }
        return response.body()
    }

    // ─── Containers ─────────────────────────────────────────────────

    suspend fun getContainers(layerId: Int? = null): List<Container> {
        val url = if (layerId != null) {
            "$baseUrl/api/containers?layer_id=$layerId"
        } else {
            "$baseUrl/api/containers"
        }
        val response = httpClient.get(url)
        return response.body()
    }

    /** Auto-import: register a scanned container to a layer. */
    suspend fun registerContainer(
        displayName: String,
        storageLayerId: Int,
        containerId: String
    ): Container {
        val response = httpClient.post("$baseUrl/api/containers") {
            contentType(ContentType.Application.Json)
            setBody(
                CreateContainerRequest(
                    display_name = displayName,
                    storage_layer_id = storageLayerId,
                    id = containerId
                )
            )
        }
        return response.body()
    }

    // ─── Bags ───────────────────────────────────────────────────────

    suspend fun addBag(request: AddBagRequest): AddBagResponse {
        val response = httpClient.post("$baseUrl/api/components") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        return response.body()
    }

    // ─── Search ─────────────────────────────────────────────────────

    suspend fun search(term: String): SearchResult {
        val response = httpClient.post("$baseUrl/api/search") {
            contentType(ContentType.Application.Json)
            setBody(mapOf("term" to term))
        }
        return response.body()
    }
}
