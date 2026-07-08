package com.storagesystem.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.storagesystem.data.models.*
import com.storagesystem.data.repository.InventoryRepository
import com.storagesystem.data.repository.QrParseResult
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * ViewModel managing the UI state for the camera + scanning modes.
 *
 * Maintains:
 * - Current [ScanMode]
 * - List of layers & containers (loaded from backend)
 * - Selected layer for auto-import / bag assignment
 * - Detected QR codes from the latest camera frame
 * - Search state
 * - Toast/error messages
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MainViewModel"
    }

    private val repository = InventoryRepository()

    /** Expose the repository's QR parser so UI can access it. */
    fun parseQrCode(rawValue: String): QrParseResult = repository.parseQrCode(rawValue)

    // ─── Mode ───────────────────────────────────────────────────────

    private val _scanMode = MutableStateFlow(ScanMode.AUTO_IMPORT_CONTAINERS)
    val scanMode: StateFlow<ScanMode> = _scanMode.asStateFlow()

    fun setScanMode(mode: ScanMode) {
        _scanMode.value = mode
        if (mode != ScanMode.SEARCH) {
            _searchTerm.value = ""
            _searchResult.value = null
        }
        // Refresh containers for Assign mode so the list is up to date
        if (mode == ScanMode.ASSIGN_BAG) {
            loadContainers(_selectedLayerId.value)
        }
    }

    // ─── Layers ─────────────────────────────────────────────────────

    private val _layers = MutableStateFlow<List<StorageLayer>>(emptyList())
    val layers: StateFlow<List<StorageLayer>> = _layers.asStateFlow()

    private val _selectedLayerId = MutableStateFlow<Int?>(null)
    val selectedLayerId: StateFlow<Int?> = _selectedLayerId.asStateFlow()

    fun setSelectedLayerId(id: Int?) {
        _selectedLayerId.value = id
    }

    // ─── Containers for selected layer ──────────────────────────────

    private val _containers = MutableStateFlow<List<Container>>(emptyList())
    val containers: StateFlow<List<Container>> = _containers.asStateFlow()

    // ─── Detected QR codes (updated per frame) ─────────────────────

    private val _detectedQrs = MutableStateFlow<List<DetectedQr>>(emptyList())
    val detectedQrs: StateFlow<List<DetectedQr>> = _detectedQrs.asStateFlow()

    fun updateDetectedQrs(qrs: List<DetectedQr>) {
        _detectedQrs.value = qrs
    }

    // ─── Toast messages ─────────────────────────────────────────────

    private val _toastMessage = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val toastMessage: SharedFlow<String> = _toastMessage.asSharedFlow()

    // ─── Search state ───────────────────────────────────────────────

    private val _searchTerm = MutableStateFlow("")
    val searchTerm: StateFlow<String> = _searchTerm.asStateFlow()

    private val _searchResult = MutableStateFlow<SearchResult?>(null)
    val searchResult: StateFlow<SearchResult?> = _searchResult.asStateFlow()

    fun updateSearchTerm(term: String) {
        _searchTerm.value = term
    }

    // ─── WebSocket ─────────────────────────────────────────────────

    private val _wsEvent = MutableSharedFlow<WsEvent>(extraBufferCapacity = 8)
    val wsEvent: SharedFlow<WsEvent> = _wsEvent.asSharedFlow()

    // ─── Initialisation ─────────────────────────────────────────────

    init {
        loadLayers()
        repository.connectWebSocket(viewModelScope)

        // Forward WS events
        viewModelScope.launch {
            repository.wsEvents.collect { event ->
                _wsEvent.emit(event)
                when (event) {
                    is WsEvent.BagAdded -> {
                        _toastMessage.emit("Bag ${event.lcsc_part_number} added to container")
                    }
                    is WsEvent.QuantityUpdated -> {
                        _toastMessage.emit("Quantity updated: ${event.lcsc_part_number} → ${event.new_quantity}")
                    }
                    else -> {}
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        repository.disconnectWebSocket()
    }

    fun loadLayers() {
        viewModelScope.launch {
            repository.getLayers().onSuccess { layerList ->
                _layers.value = layerList
                if (_selectedLayerId.value == null && layerList.isNotEmpty()) {
                    _selectedLayerId.value = layerList.first().id
                }
            }.onFailure { e ->
                Log.e(TAG, "Failed to load layers", e)
                _toastMessage.emit("Failed to load layers: ${e.message}")
            }
        }
    }

    fun loadContainers(layerId: Int?) {
        viewModelScope.launch {
            repository.getContainers(layerId).onSuccess { containerList ->
                _containers.value = containerList
            }.onFailure { e ->
                Log.e(TAG, "Failed to load containers", e)
            }
        }
    }

    // ─── Actions triggered by scan results ──────────────────────────

    /**
     * Handle a parsed QR code based on the current [ScanMode].
     */
    fun handleQrScan(parseResult: QrParseResult) {
        val auto = ServerSettings.autoScan()
        when (parseResult) {
            is QrParseResult.Container -> {
                handleContainerScan(parseResult)
                // Auto-scan: also register without requiring a tap
            }
            is QrParseResult.LcscBag -> {
                handleBagScan(parseResult)
                // Auto-assign: if there's exactly one container on the selected layer, auto-assign
                if (auto) {
                    val containers = _containers.value
                    val layerId = _selectedLayerId.value
                    if (containers.size == 1 && layerId != null && parseResult.lcscPartNumber.isNotBlank()) {
                        assignBagToContainer(containers.first().id, parseResult)
                    } else if (containers.size > 1 && layerId != null) {
                        Log.i(TAG, "Auto-scan: $containers containers on layer, need user to pick one")
                    }
                }
            }
            is QrParseResult.Unknown -> {
                viewModelScope.launch { _toastMessage.emit("Unknown QR code format") }
            }
        }
    }

    private fun handleContainerScan(result: QrParseResult.Container) {
        val layerId = _selectedLayerId.value
        if (layerId == null) {
            viewModelScope.launch { _toastMessage.emit("Select a layer first") }
            return
        }

    private fun handleContainerScan(result: QrParseResult.Container) {
        val layerId = _selectedLayerId.value
        if (layerId == null) {
            viewModelScope.launch { _toastMessage.emit("Select a layer first") }
            return
        }

        viewModelScope.launch {
            val containerId = if (result.cid.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")))
                result.cid
            else
                java.util.UUID.randomUUID().toString()

            val displayName = result.cid.take(12)
            repository.registerContainer(
                displayName = displayName,
                layerId = layerId,
                containerId = containerId
            ).onSuccess { registered ->
                Log.i(TAG, "Container $containerId registered to layer $layerId: ${registered.display_name}")

                // Immediately insert into local list so the data is available
                // even before the async loadContainers() call returns
                val current = _containers.value.toMutableList()
                current.add(registered)
                _containers.value = current

                _toastMessage.emit("Container registered: ${registered.display_name}")
                // Refresh from server in background (overwrites local on completion)
                loadContainers(layerId)
            }.onFailure { e ->
                Log.w(TAG, "Container registration failed: ${e.message}")
                _toastMessage.emit("Registration failed: ${e.message}")
            }
        }
    }

    private fun handleBagScan(result: QrParseResult.LcscBag) {
        if (result.lcscPartNumber.isEmpty()) {
            viewModelScope.launch { _toastMessage.emit("Invalid bag QR: no part number") }
            return
        }
        // Assignment is handled via the bottom sheet UI (user selects container).
        // Here we just log and let the UI layer call assignBagToContainer.
        Log.i(TAG, "Bag detected: ${result.lcscPartNumber}, qty=${result.quantity}")
    }

    /**
     * Assign a previously scanned bag to a chosen container.
     * Called from the bottom sheet after user picks a container.
     */
    fun assignBagToContainer(
        containerId: String,
        bagData: QrParseResult.LcscBag
    ) {
        Log.i(TAG, "assignBag: container=$containerId part=${bagData.lcscPartNumber} qty=${bagData.quantity}")
        viewModelScope.launch {
            val request = AddBagRequest(
                container_id = containerId,
                lcsc_part_number = bagData.lcscPartNumber,
                mfg_part_number = bagData.mfgPartNumber,
                quantity = bagData.quantity,
                order_number = bagData.orderNumber,
                package_bill_no = bagData.packageBillNo
            )

            repository.assignBag(request).onSuccess { response ->
                Log.i(TAG, "assignBag result: created=${response.created} qty=${response.current_quantity} msg=${response.message}")
                if (response.created) {
                    _toastMessage.emit("Bag assigned (qty: ${response.current_quantity})")
                } else {
                    _toastMessage.emit(
                        "Already registered — existing qty: ${response.current_quantity}"
                    )
                }
            }.onFailure { e ->
                Log.e(TAG, "assignBag failed: ${e.message}")
                _toastMessage.emit("Failed: ${e.message}")
            }
        }
    }

    /**
     * Perform a search via the backend.
     */
    fun executeSearch(term: String) {
        if (term.isBlank()) return
        viewModelScope.launch {
            repository.search(term).onSuccess { result ->
                _searchResult.value = result
            }.onFailure { e ->
                _toastMessage.emit("Search failed: ${e.message}")
            }
        }
    }
}
