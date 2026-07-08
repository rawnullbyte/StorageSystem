package com.storagesystem.data.api

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.storagesystem.data.ServerSettings
import com.storagesystem.data.models.*
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*

/**
 * HTTP client for the StorageSystem backend.
 * Uses raw request/response to avoid ContentNegotiation crashes on
 * non-JSON error responses (e.g. 422 text/plain from Axum).
 */
object ApiClient {

    private val gson = Gson()
    private val httpClient = HttpClient(CIO)

    private val baseUrl: String get() = ServerSettings.apiBaseUrl

    /** Read response body as string, then parse JSON manually. */
    private suspend inline fun <reified T> request(path: String, method: HttpMethod = HttpMethod.Get, body: Any? = null): T {
        val response = httpClient.request("$baseUrl$path") {
            this.method = method
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            if (body != null) {
                setBody(gson.toJson(body))
            }
        }

        // Read body as text first — avoids ContentNegotiation crash on non-JSON errors
        val responseBody = response.bodyAsText()

        if (!response.status.isSuccess()) {
            throw Exception("HTTP ${response.status.value}: ${responseBody.take(200)}")
        }

        return gson.fromJson(responseBody, object : TypeToken<T>() {}.type)
    }

    // ─── Layers ─────────────────────────────────────────────────────

    suspend fun getLayers(): List<StorageLayer> = request("/api/layers")

    suspend fun createLayer(name: String, description: String? = null): StorageLayer =
        request("/api/layers", HttpMethod.Post, mapOf("name" to name, "description" to description))

    // ─── Containers ─────────────────────────────────────────────────

    suspend fun getContainers(layerId: Int? = null): List<Container> {
        val qs = if (layerId != null) "?layer_id=$layerId" else ""
        return request("/api/containers$qs")
    }

    suspend fun registerContainer(displayName: String, storageLayerId: Int, containerId: String): Container =
        request("/api/containers", HttpMethod.Post, CreateContainerRequest(displayName, storageLayerId, containerId))

    // ─── Bags ───────────────────────────────────────────────────────

    suspend fun addBag(request: AddBagRequest): AddBagResponse =
        request("/api/components", HttpMethod.Post, request)

    // ─── Search ─────────────────────────────────────────────────────

    suspend fun search(term: String): SearchResult =
        request("/api/search", HttpMethod.Post, mapOf("term" to term))
}
