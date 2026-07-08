package com.storagesystem.ui

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.storagesystem.data.models.*
import com.storagesystem.data.repository.QrParseResult
import com.storagesystem.ui.components.*
import kotlinx.coroutines.launch

private const val TAG = "ScannerScreen"

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
    var showSettings by remember { mutableStateOf(false) }
    var showContainerSheet by remember { mutableStateOf(false) }
    var pendingBagData by remember { mutableStateOf<QrParseResult.LcscBag?>(null) }
    val registeredContainers = remember { mutableSetOf<String>() }

    // Assign-bag phase: "select_container" or "scan_bag"
    var assignPhase by remember { mutableStateOf("select_container") }
    var selectedContainer by remember { mutableStateOf<Container?>(null) }
    val coroutineScope = rememberCoroutineScope()

    // ── Toast host ──────────────────────────────────────────────────
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        viewModel.toastMessage.collect { msg ->
            snackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Short)
        }
    }

    // ── Overlay colours ─────────────────────────────────────────────
    val overlayQrs: List<Pair<DetectedQr, OverlayColor>> = remember(detectedQrs, scanMode, searchResult, searchTerm, assignPhase, selectedContainer) {
        when (scanMode) {
            ScanMode.AUTO_IMPORT_CONTAINERS -> detectedQrs.map { qr ->
                val color = when {
                    qr.qrType != QrType.CONTAINER -> OverlayColor.RED_NON_MATCH
                    registeredContainers.contains(qr.rawValue) -> OverlayColor.GREEN
                    selectedLayerId != null -> OverlayColor.YELLOW
                    else -> OverlayColor.RED_NON_MATCH
                }
                qr to color
            }
            ScanMode.ASSIGN_BAG -> {
                if (assignPhase == "select_container") {
                    // Phase 1: show only container QRs as selectable
                    detectedQrs.map { qr ->
                        val color = if (qr.qrType == QrType.CONTAINER) OverlayColor.YELLOW
                                     else OverlayColor.RED_NON_MATCH
                        qr to color
                    }
                } else {
                    // Phase 2: container selected — show only LCSC bag QRs
                    detectedQrs.map { qr ->
                        val color = if (qr.qrType == QrType.LCSC_BAG) OverlayColor.BLUE
                                     else OverlayColor.RED_NON_MATCH
                        qr to color
                    }
                }
            }
            ScanMode.SEARCH -> {
                val sr = searchResult
                if (sr != null) {
                    detectedQrs.map { qr ->
                        when (qr.qrType) {
                            QrType.CONTAINER -> {
                                val cid = try {
                                    com.google.gson.Gson().fromJson(qr.rawValue, ContainerQrData::class.java)?.cid
                                } catch (_: Exception) { null }
                                if (cid != null && sr.matched_containers.contains(cid))
                                    qr to OverlayColor.GREEN_MATCH
                                else qr to OverlayColor.RED_NON_MATCH
                            }
                            QrType.LCSC_BAG -> {
                                val pc = try {
                                    val cleaned = qr.rawValue.trim().removeSurrounding("{", "}")
                                    val kv = cleaned.split(",").associate { kv ->
                                        val p = kv.trim().split("=", limit = 2)
                                        p[0].trim() to p.getOrElse(1) { "" }.trim()
                                    }
                                    kv["pc"]
                                } catch (_: Exception) { null }
                                if (pc != null && sr.matched_part_numbers.contains(pc))
                                    qr to OverlayColor.GREEN_MATCH
                                else qr to OverlayColor.RED_NON_MATCH
                            }
                            QrType.UNKNOWN -> qr to OverlayColor.RED_NON_MATCH
                        }
                    }
                } else detectedQrs.map { it to OverlayColor.RED_NON_MATCH }
            }
        }
    }

    // ── WebSocket indicator ─────────────────────────────────────────
    val wsConnected = remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { viewModel.wsEvent.collect { wsConnected.value = true } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("StorageSystem", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, "Settings", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(
                        if (wsConnected.value) Icons.Default.Cloud else Icons.Default.CloudOff,
                        contentDescription = if (wsConnected.value) "Connected" else "Disconnected",
                        modifier = Modifier.padding(end = 12.dp),
                        tint = if (wsConnected.value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Camera fills everything under the controls
            Box(modifier = Modifier.fillMaxSize()) {
                CameraPreview(
                    overlayQrs = overlayQrs,
                    onQrsDetected = { viewModel.updateDetectedQrs(it) },
                    onTapQr = { qr ->
                        val parsed = viewModel.parseQrCode(qr.rawValue)
                        when (scanMode) {
                            ScanMode.AUTO_IMPORT_CONTAINERS -> {
                                if (parsed is QrParseResult.Container) {
                                    viewModel.handleQrScan(parsed)
                                    registeredContainers.add(qr.rawValue)
                                }
                            }
                            ScanMode.ASSIGN_BAG -> {
                                if (assignPhase == "select_container" && parsed is QrParseResult.Container) {
                                    val cid = parsed.cid
                                    val match = containers.find { it.id == cid }
                                    if (match != null) {
                                        selectedContainer = match
                                        assignPhase = "scan_bag"
                                        viewModel.updateDetectedQrs(emptyList()) // clear overlays
                                        coroutineScope.launch { snackbarHostState.showSnackbar("Selected ${match.display_name}") }
                                    } else {
                                        coroutineScope.launch { snackbarHostState.showSnackbar("Container not found on this layer") }
                                    }
                                } else if (assignPhase == "scan_bag" && parsed is QrParseResult.LcscBag) {
                                    if (parsed.lcscPartNumber.isNotBlank()) {
                                        viewModel.assignBagToContainer(selectedContainer!!.id, parsed)
                                    }
                                }
                            }
                            ScanMode.SEARCH -> {} // highlight only
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // ── TOP CONTROLS OVERLAY ─────────────────────────────
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, start = 8.dp, end = 8.dp)
                ) {
                    // Mode chips
                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f), shadowElevation = 4.dp) {
                        Column(modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                listOf(
                                    Triple(ScanMode.AUTO_IMPORT_CONTAINERS, "Import", Icons.Default.CameraAlt),
                                    Triple(ScanMode.ASSIGN_BAG, "Assign", Icons.Default.Sell),
                                    Triple(ScanMode.SEARCH, "Search", Icons.Default.Search),
                                ).forEach { (mode, label, icon) ->
                                    FilterChip(
                                        selected = scanMode == mode,
                                        onClick = {
                                            viewModel.setScanMode(mode)
                                            assignPhase = "select_container"
                                            selectedContainer = null
                                            if (mode != ScanMode.SEARCH) viewModel.loadContainers(selectedLayerId)
                                        },
                                        label = { Text(label, fontSize = 13.sp) },
                                        leadingIcon = { Icon(icon, null, Modifier.size(16.dp)) },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }

                            // Layer selector (for Import and Assign)
                            if (scanMode != ScanMode.SEARCH) {
                                Spacer(Modifier.height(6.dp))
                                if (layers.isEmpty()) {
                                    Text("No layers — add via dashboard", fontSize = 12.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                                } else {
                                    LayerChip(
                                        layers = layers,
                                        selectedLayerId = selectedLayerId,
                                        onLayerSelected = { id ->
                                            viewModel.setSelectedLayerId(id)
                                            viewModel.loadContainers(id)
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // ── Phase indicator for assign mode ──────────────
                    if (scanMode == ScanMode.ASSIGN_BAG) {
                        Spacer(Modifier.height(8.dp))
                        if (assignPhase == "select_container") {
                            Surface(shape = RoundedCornerShape(12.dp), color = Color(0xCCFFB300), shadowElevation = 2.dp) {
                                Text(
                                    "① Scan a container QR to select it",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.Black,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                                )
                            }
                        } else {
                            Surface(shape = RoundedCornerShape(12.dp), color = Color(0xCC1976D2), shadowElevation = 2.dp) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Selected container:", fontSize = 11.sp, color = Color.White.copy(alpha = 0.7f))
                                        Text(selectedContainer?.display_name ?: "", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                    }
                                    TextButton(onClick = {
                                        assignPhase = "select_container"
                                        selectedContainer = null
                                        viewModel.updateDetectedQrs(emptyList())
                                    }) {
                                        Icon(Icons.Default.Close, "Change", tint = Color.White, modifier = Modifier.size(18.dp))
                                        Text("Change", color = Color.White, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }

                    // ── Search bar ──────────────────────────────────
                    if (scanMode == ScanMode.SEARCH) {
                        Spacer(Modifier.height(8.dp))
                        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f), shadowElevation = 4.dp) {
                            SearchBar(
                                initialValue = searchTerm,
                                onSearch = { viewModel.updateSearchTerm(it); viewModel.executeSearch(it) },
                                onClear = { viewModel.updateSearchTerm(""); viewModel.executeSearch("") }
                            )
                        }
                    }
                }
            }

            // ── BOTTOM info bar ─────────────────────────────────────
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f),
                tonalElevation = 0.dp
            ) {
                Text(
                    text = when {
                        scanMode == ScanMode.ASSIGN_BAG && assignPhase == "select_container" -> "Tap a yellow container QR to select it"
                        scanMode == ScanMode.ASSIGN_BAG -> "Tap a blue bag QR to assign to ${selectedContainer?.display_name ?: ""}"
                        scanMode == ScanMode.AUTO_IMPORT_CONTAINERS -> "Tap a yellow box to register container"
                        scanMode == ScanMode.SEARCH -> "Matching codes shown in green"
                        else -> ""
                    },
                    fontSize = 13.sp,
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    // ── Settings screen ─────────────────────────────────────────────
    if (showSettings) SettingsScreen(onDismiss = { showSettings = false })

    // ── Container selection sheet (legacy, for completeness) ────────
    if (showContainerSheet && pendingBagData != null) {
        ContainerSelectionSheet(
            containers = containers,
            onContainerSelected = { container ->
                viewModel.assignBagToContainer(container.id, pendingBagData!!)
                showContainerSheet = false; pendingBagData = null
            },
            onDismiss = { showContainerSheet = false; pendingBagData = null }
        )
    }
}

/** Compact layer dropdown pill. */
@Composable
private fun LayerChip(
    layers: List<StorageLayer>,
    selectedLayerId: Int?,
    onLayerSelected: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = layers.find { it.id == selectedLayerId }
    Row(modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Folder, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(4.dp))
        Box {
            Text(
                selected?.name ?: "Select layer…",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.clickable { expanded = true }
            )
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                layers.forEach { l ->
                    DropdownMenuItem(
                        text = { Text(l.name, fontSize = 14.sp) },
                        onClick = { onLayerSelected(l.id); expanded = false }
                    )
                }
            }
        }
    }
}
