package com.storagesystem.ui

import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.storagesystem.data.models.*
import com.storagesystem.data.repository.QrParseResult
import com.storagesystem.ui.components.*

private const val TAG = "ScannerScreen"

/**
 * Main scanner screen composing the camera preview, mode controls,
 * overlays, and bottom sheets for each operational mode.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerScreen(viewModel: MainViewModel) {
    val scanMode by viewModel.scanMode.collectAsState()
    val layers by viewModel.layers.collectAsState()
    val selectedLayerId by viewModel.selectedLayerId.collectAsState()
    val containers by viewModel.containers.collectAsState()
    val detectedQrs by viewModel.detectedQrs.collectAsState()
    val searchResult by viewModel.searchResult.collectAsState()
    val searchTerm by viewModel.searchTerm.collectAsState()

    // ── UI state ────────────────────────────────────────────────────
    var showContainerSheet by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    // The bag data that the user tapped, awaiting container selection
    var pendingBagData by remember { mutableStateOf<QrParseResult.LcscBag?>(null) }
    // Container registration success tracking
    val registeredContainers = remember { mutableSetOf<String>() }

    // ── Toast host ──────────────────────────────────────────────────
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        viewModel.toastMessage.collect { msg ->
            snackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Short)
        }
    }

    // ── Overlay colours for detected QRs ────────────────────────────
    val overlayQrs: List<Pair<DetectedQr, OverlayColor>> = remember(detectedQrs, scanMode, searchResult, searchTerm) {
        when (scanMode) {
            ScanMode.AUTO_IMPORT_CONTAINERS -> {
                detectedQrs.map { qr ->
                    val color = when {
                        qr.qrType != QrType.CONTAINER -> OverlayColor.RED_NON_MATCH
                        registeredContainers.contains(qr.rawValue) -> OverlayColor.GREEN
                        selectedLayerId != null -> OverlayColor.YELLOW
                        else -> OverlayColor.RED_NON_MATCH
                    }
                    qr to color
                }
            }
            ScanMode.ASSIGN_BAG -> {
                detectedQrs.map { qr ->
                    val color = when (qr.qrType) {
                        QrType.LCSC_BAG -> OverlayColor.BLUE
                        else -> OverlayColor.RED_NON_MATCH
                    }
                    qr to color
                }
            }
            ScanMode.SEARCH -> {
                val sr = searchResult
                if (sr != null) {
                    detectedQrs.map { qr ->
                        when (qr.qrType) {
                            QrType.CONTAINER -> {
                                // Extract cid for matching
                                val cid = try {
                                    com.google.gson.Gson().fromJson(
                                        qr.rawValue,
                                        ContainerQrData::class.java
                                    )?.cid
                                } catch (e: Exception) { null }
                                if (cid != null && sr.matched_containers.contains(cid)) {
                                    qr to OverlayColor.GREEN_MATCH
                                } else {
                                    qr to OverlayColor.RED_NON_MATCH
                                }
                            }
                            QrType.LCSC_BAG -> {
                                val pc = try {
                                    val cleaned = qr.rawValue.trim()
                                        .removeSurrounding("{", "}")
                                    val kv = cleaned.split(",").associate { kv ->
                                        val p = kv.trim().split("=", limit = 2)
                                        p[0].trim() to p.getOrElse(1) { "" }.trim()
                                    }
                                    kv["pc"]
                                } catch (e: Exception) { null }
                                if (pc != null && sr.matched_part_numbers.contains(pc)) {
                                    qr to OverlayColor.GREEN_MATCH
                                } else {
                                    qr to OverlayColor.RED_NON_MATCH
                                }
                            }
                            QrType.UNKNOWN -> qr to OverlayColor.RED_NON_MATCH
                        }
                    }
                } else {
                    detectedQrs.map { it to OverlayColor.RED_NON_MATCH }
                }
            }
        }
    }

    // ── WebSocket connection indicator ──────────────────────────────
    val wsConnected = remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        viewModel.wsEvent.collect {
            wsConnected.value = true
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            // Custom top bar with WS status
            TopAppBar(
                title = { Text("StorageSystem") },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        imageVector = if (wsConnected.value) Icons.Default.Cloud else Icons.Default.CloudOff,
                        contentDescription = if (wsConnected.value) "Connected" else "Disconnected",
                        modifier = Modifier.padding(end = 12.dp),
                        tint = if (wsConnected.value)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.error
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Mode selector
            ModeSelector(
                currentMode = scanMode,
                onModeChange = { mode ->
                    viewModel.setScanMode(mode)
                    if (mode != ScanMode.SEARCH) {
                        viewModel.loadContainers(selectedLayerId)
                    }
                },
                layers = layers,
                selectedLayerId = selectedLayerId,
                onLayerSelected = { id ->
                    viewModel.setSelectedLayerId(id)
                    viewModel.loadContainers(id)
                }
            )

            // Search bar (only in SEARCH mode)
            if (scanMode == ScanMode.SEARCH) {
                SearchBar(
                    initialValue = searchTerm,
                    onSearch = { term ->
                        viewModel.updateSearchTerm(term)
                        viewModel.executeSearch(term)
                    },
                    onClear = {
                        viewModel.updateSearchTerm("")
                        viewModel.executeSearch("")
                    }
                )
            }

            // Camera preview
            CameraPreview(
                overlayQrs = overlayQrs,
                onQrsDetected = { qrs ->
                    viewModel.updateDetectedQrs(qrs)
                },
                onTapQr = { qr ->
                    // Parse the tapped QR
                    val parsed = viewModel.parseQrCode(qr.rawValue)
                    Log.d(TAG, "Tapped QR: type=${qr.qrType}, parsed=$parsed")

                    when (scanMode) {
                        ScanMode.AUTO_IMPORT_CONTAINERS -> {
                            if (parsed is QrParseResult.Container) {
                                viewModel.handleQrScan(parsed)
                                registeredContainers.add(qr.rawValue)
                            }
                        }
                        ScanMode.ASSIGN_BAG -> {
                            if (parsed is QrParseResult.LcscBag && parsed.lcscPartNumber.isNotBlank()) {
                                pendingBagData = parsed
                                showContainerSheet = true
                            }
                        }
                        ScanMode.SEARCH -> {
                            // In search mode, tap does nothing special
                            // (highlighting is handled by overlay)
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )

            // Bottom info bar
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = 2.dp
            ) {
                Text(
                    text = when (scanMode) {
                        ScanMode.AUTO_IMPORT_CONTAINERS -> "Tap a yellow box to register container"
                        ScanMode.ASSIGN_BAG -> "Tap a blue box to assign bag to container"
                        ScanMode.SEARCH -> "Results shown in green"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    // ── Settings screen ─────────────────────────────────────────────
    if (showSettings) {
        SettingsScreen(onDismiss = { showSettings = false })
    }

    // ── Bottom sheet for container selection ────────────────────────
    if (showContainerSheet && pendingBagData != null) {
        ContainerSelectionSheet(
            containers = containers,
            onContainerSelected = { container ->
                viewModel.assignBagToContainer(
                    containerId = container.id,
                    bagData = pendingBagData!!
                )
                showContainerSheet = false
                pendingBagData = null
            },
            onDismiss = {
                showContainerSheet = false
                pendingBagData = null
            }
        )
    }
}
