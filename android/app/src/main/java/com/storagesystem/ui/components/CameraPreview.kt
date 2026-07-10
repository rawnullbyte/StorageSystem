package com.storagesystem.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Rect
import android.util.Log
import android.view.MotionEvent
import android.view.View
import androidx.camera.core.CameraSelector
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as ComposeSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.DetectedObject
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import com.storagesystem.data.models.DetectedQr
import com.storagesystem.data.models.OverlayColor
import com.storagesystem.data.models.QrType
import com.storagesystem.data.repository.parseQrRaw
import com.storagesystem.data.repository.QrParseResult
import kotlin.math.sqrt

private const val TAG = "CameraPreview"

/**
 * Tracks a QR region across frames using ML Kit's built-in trackingId.
 * Once decoded, the object detector visually follows the region even when
 * the QR is blurry — updating the bounding box based on visual features
 * (corners, edges, contrast), not by re-reading the QR code.
 */
private data class TrackedQr(
    var rawValue: String?,
    var boundingBox: Rect,
    var qrType: QrType,
    val trackingId: Int,
    var isFromBarcode: Boolean,
    val scannedData: QrParseResult? = null
)

@Composable
fun CameraPreview(
    overlayQrs: List<Pair<DetectedQr, OverlayColor>>,
    onQrsDetected: (List<DetectedQr>) -> Unit,
    onTapQr: (DetectedQr) -> Unit,
    modifier: Modifier = Modifier,
    torchOn: Boolean = false,
    onTorchChanged: ((Boolean) -> Unit)? = null,
    trackingResetSignal: Int = 0
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val controllerRef = remember { mutableStateOf<LifecycleCameraController?>(null) }

    // trackingId -> TrackedQr — persists across frames
    val tracked = remember { mutableStateMapOf<Int, TrackedQr>() }
    // Full overlay for tap detection
    val overlayState = remember { mutableStateListOf<DetectedQr>() }

    LaunchedEffect(torchOn, controllerRef.value) {
        val ctrl = controllerRef.value ?: return@LaunchedEffect
        try { ctrl.enableTorch(torchOn) } catch (_: Exception) {}
    }

    LaunchedEffect(trackingResetSignal) {
        tracked.clear()
        overlayState.clear()
    }

    val hasCameraPermission = remember {
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val pv = PreviewView(ctx)
                if (!hasCameraPermission) return@AndroidView pv

                val controller = LifecycleCameraController(ctx).apply {
                    cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                    bindToLifecycle(lifecycleOwner)
                }
                controllerRef.value = controller
                pv.controller = controller

                // ── Barcode scanner (decodes QR content) ────────────────────
                val barcodeScanner = BarcodeScanning.getClient(
                    com.google.mlkit.vision.barcode.BarcodeScannerOptions.Builder()
                        .setBarcodeFormats(FORMAT_QR_CODE)
                        .build()
                )

                // ── Object detector (tracks visual region) ──────────────────
                val objectDetector = ObjectDetection.getClient(
                    ObjectDetectorOptions.Builder()
                        .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
                        .enableMultipleObjects()
                        .build()
                )

                controller.setImageAnalysisAnalyzer(
                    ContextCompat.getMainExecutor(ctx),
                    MlKitAnalyzer(
                        listOf(barcodeScanner, objectDetector),
                        CameraController.COORDINATE_SYSTEM_VIEW_REFERENCED,
                        ContextCompat.getMainExecutor(ctx)
                    ) { result: MlKitAnalyzer.Result? ->
                        val barcodes = result?.getValue(barcodeScanner) as? List<Barcode> ?: emptyList()
                        val objects = result?.getValue(objectDetector) as? List<DetectedObject> ?: emptyList()

                        // ── Step 1: Object detector (visual tracking — every frame) ──
                        for (obj in objects) {
                            val tid = obj.trackingId ?: continue
                            val existing = tracked[tid]
                            if (existing != null) {
                                existing.boundingBox = obj.boundingBox
                                existing.isFromBarcode = false
                            } else {
                                tracked[tid] = TrackedQr(
                                    rawValue = null, boundingBox = obj.boundingBox,
                                    qrType = QrType.UNKNOWN, trackingId = tid, isFromBarcode = false
                                )
                            }
                        }

                        // ── Step 2: Barcode decoding ─────────────────────────────
                        for (barcode in barcodes) {
                            val raw = barcode.rawValue ?: continue
                            val box = barcode.boundingBox ?: continue
                            val type = classifyQr(raw)
                            val parsed = parseQrRaw(raw)

                            val matchedTid = objects.minByOrNull { obj ->
                                val dx = (obj.boundingBox.centerX() - box.centerX()).toFloat()
                                val dy = (obj.boundingBox.centerY() - box.centerY()).toFloat()
                                sqrt(dx * dx + dy * dy)
                            }?.trackingId ?: (raw.hashCode() % 100000)

                            tracked[matchedTid] = TrackedQr(
                                rawValue = raw, boundingBox = box, qrType = type,
                                trackingId = matchedTid, isFromBarcode = true, scannedData = parsed
                            )
                        }

                        // ── Step 3: Remove lost tracks ───────────────────────────
                        val activeIds = (objects.mapNotNull { it.trackingId } +
                                barcodes.map { it.rawValue.hashCode() % 100000 }).toSet()
                        tracked.keys.removeIf { it !in activeIds }

                        // ── Step 4: Fresh decodings for ViewModel + full overlay ──
                        val freshDetections = tracked.values
                            .filter { it.isFromBarcode && it.rawValue != null }
                            .map { DetectedQr(it.rawValue!!, it.boundingBox, it.qrType) }

                        overlayState.clear()
                        overlayState.addAll(tracked.values
                            .filter { it.rawValue != null && it.boundingBox.width() > 0 }
                            .map { DetectedQr(it.rawValue!!, it.boundingBox, it.qrType) })
                        onQrsDetected(freshDetections)
                    }
                )

                pv.setOnTouchListener { _: View, event: MotionEvent ->
                    if (event.action == MotionEvent.ACTION_UP) {
                        val tx = event.x; val ty = event.y
                        val tapped = overlayState.minByOrNull { qr ->
                            val r = qr.boundingBox
                            val cx = (r.left + r.right) / 2f; val cy = (r.top + r.bottom) / 2f
                            sqrt((tx - cx) * (tx - cx) + (ty - cy) * (ty - cy))
                        }
                        if (tapped != null) onTapQr(tapped)
                    }
                    false
                }
                pv
            }
        )

        Canvas(modifier = Modifier.fillMaxSize()) {
            val combined = overlayQrs.toMutableList()

            // Add tracked items — color by QR type (respects scanMode from parent)
            for (t in tracked.values) {
                if (t.rawValue == null) continue
                if (overlayQrs.any { it.first.rawValue == t.rawValue }) continue
                val colorForTracked = when (t.qrType) {
                    QrType.CONTAINER -> OverlayColor.YELLOW
                    QrType.LCSC_BAG -> OverlayColor.BLUE
                    QrType.UNKNOWN -> OverlayColor.RED_NON_MATCH
                }
                combined.add(DetectedQr(t.rawValue!!, t.boundingBox, t.qrType) to colorForTracked)
            }

            for ((qr, color) in combined) {
                val box = qr.boundingBox; if (box.isEmpty) continue
                val drawColor = when (color) {
                    OverlayColor.YELLOW -> Color.Yellow
                    OverlayColor.GREEN, OverlayColor.GREEN_MATCH -> Color.Green
                    OverlayColor.BLUE -> Color.Blue
                    OverlayColor.RED_NON_MATCH -> Color.Red
                }
                val sw = if (color == OverlayColor.GREEN_MATCH || color == OverlayColor.YELLOW) 6f else 3f
                drawRect(drawColor, Offset(box.left.toFloat(), box.top.toFloat()),
                    ComposeSize(box.width().toFloat(), box.height().toFloat()), style = Stroke(width = sw))
                drawRect(drawColor.copy(alpha = when (color) {
                    OverlayColor.GREEN_MATCH -> 0.3f; OverlayColor.YELLOW -> 0.25f
                    OverlayColor.BLUE -> 0.2f; else -> 0.15f
                }), Offset(box.left.toFloat(), box.top.toFloat()),
                    ComposeSize(box.width().toFloat(), box.height().toFloat()))

                val label = buildQuickLabel(qr, color) ?: continue
                val paint = android.graphics.Paint().apply {
                    this.color = drawColor.toArgb(); textSize = 36f; isAntiAlias = true
                    typeface = android.graphics.Typeface.MONOSPACE; isFakeBoldText = true
                }
                val bg = android.graphics.Paint().apply {
                    this.color = android.graphics.Color.argb(200, 0, 0, 0)
                }
                val tw = paint.measureText(label)
                val lx = (box.left + box.right - tw.toInt()) / 2f
                val ly = (box.top - 12f).coerceAtLeast(4f)
                drawContext.canvas.nativeCanvas.drawRoundRect(
                    lx - 8f, ly - 30f, lx + tw + 8f, ly + 4f, 6f, 6f, bg
                )
                drawContext.canvas.nativeCanvas.drawText(label, lx, ly, paint)
            }
            drawAimReticle(size)
        }
    }
}

private val qrLabelCache = mutableMapOf<String, String?>()
private fun buildQuickLabel(qr: DetectedQr, color: OverlayColor): String? {
    if (color == OverlayColor.RED_NON_MATCH) return null
    return qrLabelCache.getOrPut(qr.rawValue) {
        when (val p = parseQrRaw(qr.rawValue)) {
            is QrParseResult.Container -> "📦 ${p.cid.take(8)}…"
            is QrParseResult.LcscBag -> "🔩 ${p.lcscPartNumber}"
            is QrParseResult.Unknown -> null
        }
    }
}
private fun DrawScope.drawAimReticle(size: ComposeSize) {
    val cx = size.width / 2f; val cy = size.height / 2f; val r = 60f; val bl = 24f; val c = Color.White.copy(alpha = 0.5f)
    drawLine(c, Offset(cx - r, cy - r), Offset(cx - r + bl, cy - r), strokeWidth = 3f)
    drawLine(c, Offset(cx - r, cy - r), Offset(cx - r, cy - r + bl), strokeWidth = 3f)
    drawLine(c, Offset(cx + r, cy - r), Offset(cx + r - bl, cy - r), strokeWidth = 3f)
    drawLine(c, Offset(cx + r, cy - r), Offset(cx + r, cy - r + bl), strokeWidth = 3f)
    drawLine(c, Offset(cx - r, cy + r), Offset(cx - r + bl, cy + r), strokeWidth = 3f)
    drawLine(c, Offset(cx - r, cy + r), Offset(cx - r, cy + r - bl), strokeWidth = 3f)
    drawLine(c, Offset(cx + r, cy + r), Offset(cx + r - bl, cy + r), strokeWidth = 3f)
    drawLine(c, Offset(cx + r, cy + r), Offset(cx + r, cy + r - bl), strokeWidth = 3f)
}
private fun classifyQr(rawValue: String): QrType {
    val t = rawValue.trim()
    return when {
        t.startsWith("{") && t.contains("\"cid\"") -> QrType.CONTAINER
        t.startsWith("{") && (t.contains("=") || t.contains("pbn:") || t.contains(",pc:")) -> QrType.LCSC_BAG
        else -> QrType.UNKNOWN
    }
}
