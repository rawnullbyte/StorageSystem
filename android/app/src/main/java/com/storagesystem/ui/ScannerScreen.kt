package com.storagesystem.ui

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.storagesystem.data.ServerSettings
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
    var assignPhase by remember { mutableStateOf("select_container") }
    var selectedContainer by remember { mutableStateOf<Container?>(null) }
    val coroutineScope = rememberCoroutineScope()
    var torchOn by remember { mutableStateOf(false) }

    // Search: pick-first mode
    var searchMode by remember { mutableStateOf("input") } // "input" | "results" | "camera"
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<SearchResult?>(null) }
    // Picked search target
    var searchPickContainer by remember { mutableStateOf<String?>(null) }
    var searchPickPart by remember { mutableStateOf<String?>(null) }

    // ── Back handler: close settings first, then search input, then exit
    BackHandler(enabled = showSettings) { showSettings = false }
    BackHandler(enabled = searchMode == "results") { searchMode = "input"; searchResults = null }
    BackHandler(enabled = searchMode == "camera") { searchMode = "input"; searchPickContainer = null; searchPickPart = null }

    // ── Toast host ──────────────────────────────────────────────────
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        viewModel.toastMessage.collect { msg ->
            snackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Short)
        }
    }

    // ── Overlay colours ─────────────────────────────────────────────
    val overlayQrs: List<Pair<DetectedQr, OverlayColor>> = remember(detectedQrs, scanMode, searchResult, searchTerm, assignPhase, selectedContainer, searchPickContainer, searchPickPart) {
        when (scanMode) {
            ScanMode.AUTO_IMPORT_CONTAINERS -> detectedQrs.map { qr ->
                qr to when { qr.qrType != QrType.CONTAINER -> OverlayColor.RED_NON_MATCH
                    registeredContainers.contains(qr.rawValue) -> OverlayColor.GREEN
                    selectedLayerId != null -> OverlayColor.YELLOW; else -> OverlayColor.RED_NON_MATCH }
            }
            ScanMode.ASSIGN_BAG -> detectedQrs.map { qr ->
                val phase = assignPhase
                qr to when { phase == "select_container" && qr.qrType == QrType.CONTAINER -> OverlayColor.YELLOW
                    phase == "scan_bag" && qr.qrType == QrType.LCSC_BAG -> OverlayColor.BLUE
                    else -> OverlayColor.RED_NON_MATCH }
            }
            ScanMode.SEARCH -> {
                if (searchPickContainer != null || searchPickPart != null) {
                    detectedQrs.map { qr ->
                        val cid = try { com.google.gson.Gson().fromJson(qr.rawValue, ContainerQrData::class.java)?.cid } catch (_: Exception) { null }
                        val pc = try { val cleaned = qr.rawValue.trim().removeSurrounding("{","}"); val kvs = cleaned.split(",").associate { val p = it.trim().split("=",limit=2); p[0].trim() to p.getOrElse(1){""}.trim() }; kvs["pc"] } catch(_:Exception){null}
                        qr to when {
                            searchPickContainer != null && cid == searchPickContainer -> OverlayColor.GREEN_MATCH
                            searchPickPart != null && pc == searchPickPart -> OverlayColor.GREEN_MATCH
                            searchPickContainer == null && searchPickPart == null && qr.qrType == QrType.CONTAINER -> OverlayColor.YELLOW
                            else -> OverlayColor.RED_NON_MATCH
                        }
                    }
                } else detectedQrs.map { qr ->
                    // No pick yet — highlight any container QR yellow (they'll all show)
                    qr to if (qr.qrType == QrType.CONTAINER) OverlayColor.YELLOW else OverlayColor.RED_NON_MATCH
                }
            }
        }
    }

    // Track auto-scanned QRs to avoid re-processing every frame
    val autoScanned = remember { mutableSetOf<String>() }

    // ── WebSocket indicator ─────────────────────────────────────────
    val wsConnected = remember { mutableStateOf(false) }
    // Show connected on first WS event OR if we got data from the server
    LaunchedEffect(Unit) { viewModel.wsEvent.collect { wsConnected.value = true } }
    LaunchedEffect(layers, containers) {
        if (layers.isNotEmpty() || containers.isNotEmpty()) wsConnected.value = true
    }

    // ── SEARCH INPUT VIEW ──────────────────────────────────────────
    if (scanMode == ScanMode.SEARCH && searchMode == "input") {
        SettingsLikeScaffold(
            title = "Search Components",
            onBack = { viewModel.setScanMode(ScanMode.AUTO_IMPORT_CONTAINERS); searchMode="input"; searchResults=null }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(searchQuery, { searchQuery = it }, label = { Text("Container name / Part number") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Button({ viewModel.executeSearch(searchQuery); searchResults = null }, Modifier.fillMaxWidth(), enabled = searchQuery.isNotBlank()) {
                    Text("Search")
                }

                // Show results if we got them
                val sr = searchResult
                if (sr != null) {
                    searchResults = sr
                    LaunchedEffect(sr) { searchResults = sr }

                    Text("Results", style = MaterialTheme.typography.titleSmall)
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (sr.matched_containers.isEmpty() && sr.matched_part_numbers.isEmpty()) {
                            item { Text("No matches", color = MaterialTheme.colorScheme.error) }
                        }
                        if (sr.matched_containers.isNotEmpty()) {
                            item { Text("Containers", fontWeight = FontWeight.Bold, fontSize = 13.sp) }
                            items(sr.matched_containers) { cid ->
                                val c = containers.find { it.id == cid || it.display_name == cid }
                                Card(Modifier.fillMaxWidth().clickable {
                                    searchPickContainer = cid; searchPickPart = null; searchMode = "camera"
                                }) {
                                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Inventory2, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                                        Spacer(Modifier.width(8.dp))
                                        Column { Text(c?.display_name ?: cid, fontWeight = FontWeight.Medium)
                                            Text("Container", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                    }
                                }
                            }
                        }
                        if (sr.matched_part_numbers.isNotEmpty()) {
                            item { Text("Components", fontWeight = FontWeight.Bold, fontSize = 13.sp) }
                            items(sr.matched_part_numbers) { pc ->
                                Card(Modifier.fillMaxWidth().clickable {
                                    searchPickPart = pc; searchPickContainer = null; searchMode = "camera"
                                }) {
                                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Memory, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.secondary)
                                        Spacer(Modifier.width(8.dp))
                                        Column { Text(pc, fontWeight = FontWeight.Medium, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                                            Text("LCSC Part", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return
    }

    // ── Main camera view ────────────────────────────────────────────
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("StorageSystem", fontWeight = FontWeight.Bold) },
                actions = {
                    if (scanMode == ScanMode.SEARCH && searchMode == "camera") {
                        Text("Searching…", fontSize = 12.sp, modifier = Modifier.padding(end = 4.dp))
                    }
                    IconButton(onClick = { torchOn = !torchOn }) {
                        Icon(if (torchOn) Icons.Default.FlashOn else Icons.Default.FlashOff, "Torch",
                            tint = if (torchOn) Color(0xFFFFD700) else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, "Settings", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(if (wsConnected.value) Icons.Default.Cloud else Icons.Default.CloudOff, null,
                        Modifier.padding(end = 12.dp),
                        tint = if (wsConnected.value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Box(Modifier.fillMaxSize()) {
                CameraPreview(
                    overlayQrs = overlayQrs,
                    torchOn = torchOn,
                    onQrsDetected = { qrs ->
                        viewModel.updateDetectedQrs(qrs)
                        // Auto-scan: auto-register containers without tapping
                        if (ServerSettings.autoScan() && selectedLayerId != null) {
                            for (qr in qrs) {
                                if (qr.rawValue in autoScanned) continue
                                val parsed = viewModel.parseQrCode(qr.rawValue)
                                if (scanMode == ScanMode.AUTO_IMPORT_CONTAINERS && parsed is QrParseResult.Container) {
                                    autoScanned.add(qr.rawValue)
                                    viewModel.handleQrScan(parsed)
                                } else if (scanMode == ScanMode.ASSIGN_BAG && parsed is QrParseResult.LcscBag && parsed.lcscPartNumber.isNotBlank()) {
                                    autoScanned.add(qr.rawValue)
                                    if (containers.size == 1) {
                                        viewModel.assignBagToContainer(containers.first().id, parsed)
                                    }
                                }
                            }
                        }
                    },
                    onTapQr = { qr ->
                        val parsed = viewModel.parseQrCode(qr.rawValue)
                        when (scanMode) {
                            ScanMode.AUTO_IMPORT_CONTAINERS -> {
                                if (parsed is QrParseResult.Container && !registeredContainers.contains(qr.rawValue)) {
                                    viewModel.handleQrScan(parsed); registeredContainers.add(qr.rawValue)
                                }
                            }
                            ScanMode.ASSIGN_BAG -> {
                                if (assignPhase == "select_container" && parsed is QrParseResult.Container) {
                                    val cid = parsed.cid
                                    var match = containers.find { it.id == cid }
                                    if (match == null) match = containers.find { it.display_name == cid }
                                    if (match != null) { selectedContainer = match; assignPhase = "scan_bag"; viewModel.updateDetectedQrs(emptyList())
                                        coroutineScope.launch { snackbarHostState.showSnackbar("Selected ${match.display_name}") } }
                                    else { coroutineScope.launch { snackbarHostState.showSnackbar("Container not found on this layer") } }
                                } else if (assignPhase == "scan_bag" && parsed is QrParseResult.LcscBag && parsed.lcscPartNumber.isNotBlank()) {
                                    viewModel.assignBagToContainer(selectedContainer!!.id, parsed)
                                }
                            }
                            ScanMode.SEARCH -> {}
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // Controls overlay
                Column(Modifier.fillMaxWidth().padding(top = 8.dp, start = 8.dp, end = 8.dp)) {
                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f), shadowElevation = 4.dp) {
                        Column(Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                listOf(Triple(ScanMode.AUTO_IMPORT_CONTAINERS, "Import", Icons.Default.CameraAlt),
                                    Triple(ScanMode.ASSIGN_BAG, "Assign", Icons.Default.Sell),
                                    Triple(ScanMode.SEARCH, "Search", Icons.Default.Search),
                                ).forEach { (mode, label, icon) ->
                                    FilterChip(selected = scanMode == mode,
                                        onClick = { viewModel.setScanMode(mode); searchMode="input"; searchResults=null; assignPhase="select_container"; selectedContainer=null },
                                        label = { Text(label, fontSize = 13.sp) }, leadingIcon = { Icon(icon, null, Modifier.size(16.dp)) },
                                        modifier = Modifier.weight(1f))
                                }
                            }
                            if (scanMode != ScanMode.SEARCH) {
                                Spacer(Modifier.height(6.dp))
                                if (layers.isEmpty()) Text("No layers — add via dashboard", fontSize = 12.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                                else LayerChip(layers, selectedLayerId, { viewModel.setSelectedLayerId(it); viewModel.loadContainers(it) })
                            }
                        }
                    }
                    if (scanMode == ScanMode.ASSIGN_BAG) {
                        Spacer(Modifier.height(8.dp))
                        if (assignPhase == "select_container") {
                            Surface(shape = RoundedCornerShape(12.dp), color = Color(0xCCFFB300)) {
                                Text("① Scan a container QR to select it", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                    color = Color.Black, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
                            }
                        } else {
                            Surface(shape = RoundedCornerShape(12.dp), color = Color(0xCC1976D2)) {
                                Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) { Text("Selected:", fontSize = 11.sp, color = Color.White.copy(alpha = 0.7f))
                                        Text(selectedContainer?.display_name ?: "", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White) }
                                    TextButton(onClick = { assignPhase = "select_container"; selectedContainer = null; viewModel.updateDetectedQrs(emptyList()) }) {
                                        Icon(Icons.Default.Close, "Change", tint = Color.White, modifier = Modifier.size(18.dp))
                                        Text("Change", color = Color.White, fontSize = 12.sp) }
                                }
                            }
                        }
                    }
                }
            }

            Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f)) {
                Text(when { scanMode == ScanMode.SEARCH && searchMode == "camera" -> "Point camera at the highlighted item"
                    scanMode == ScanMode.ASSIGN_BAG && assignPhase == "select_container" -> "Tap a yellow container QR"
                    scanMode == ScanMode.ASSIGN_BAG -> "Tap a blue bag QR to assign"
                    scanMode == ScanMode.AUTO_IMPORT_CONTAINERS -> "Tap a yellow box to register container"
                    else -> "" }, fontSize = 13.sp, modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    if (showSettings) SettingsScreen(onDismiss = { showSettings = false })
    if (showContainerSheet && pendingBagData != null) {
        ContainerSelectionSheet(containers, { viewModel.assignBagToContainer(it.id, pendingBagData!!); showContainerSheet = false; pendingBagData = null },
            { showContainerSheet = false; pendingBagData = null })
    }
}

/** Full-screen view with back arrow in top bar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsLikeScaffold(title: String, onBack: () -> Unit, content: @Composable (PaddingValues) -> Unit) {
    Scaffold(topBar = {
        TopAppBar(title = { Text(title) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } })
    }) { padding -> content(padding) }
}

@Composable
private fun LayerChip(layers: List<StorageLayer>, selectedLayerId: Int?, onLayerSelected: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selected = layers.find { it.id == selectedLayerId }
    Row(Modifier.padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Folder, null, Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Box { Text(selected?.name ?: "Select layer…", fontSize = 13.sp, modifier = Modifier.clickable { expanded = true })
            DropdownMenu(expanded, { expanded = false }) { layers.forEach { l -> DropdownMenuItem({ Text(l.name, fontSize = 14.sp) }, onClick = { onLayerSelected(l.id); expanded = false }) } } }
    }
}
