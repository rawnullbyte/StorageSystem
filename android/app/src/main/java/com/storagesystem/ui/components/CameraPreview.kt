package com.storagesystem.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Rect
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
import com.storagesystem.data.models.DetectedQr
import com.storagesystem.data.models.OverlayColor
import com.storagesystem.data.models.QrType
import com.storagesystem.data.repository.parseQrRaw
import com.storagesystem.data.repository.QrParseResult
import kotlin.math.sqrt

private const val MAX_HISTORY = 12
private const val MAX_PREDICTION_MS = 2500L
private const val CONFIDENT_MS = 150L

/**
 * Tracks a QR code's position using velocity from recent detection history.
 * Once the QR is decoded once, we can predict where it is even when blurry
 * by extrapolating its movement. Tracking is lost after 2.5s of no detections.
 */
private class QrPrediction(val rawValue: String, val qrType: QrType) {
    private val history = mutableListOf<Pair<Rect, Long>>()

    fun addPosition(box: Rect, timeMs: Long) {
        history.add(box to timeMs)
        while (history.size > MAX_HISTORY) history.removeAt(0)
    }

    /** Returns predicted position, or null if tracking is lost. */
    fun predict(currentMs: Long): Rect? {
        if (history.isEmpty()) return null
        val latest = history.last()
        val elapsed = currentMs - latest.second

        // Lost tracking if too long since last detection
        if (elapsed > MAX_PREDICTION_MS) return null

        // Fresh detection — use exact position
        if (elapsed < CONFIDENT_MS) return latest.first

        // Need at least 2 points for velocity
        if (history.size < 2) return latest.first

        // Average velocity from last 5 detections
        val recent = history.takeLast(5)
        var dx = 0f; var dy = 0f; var dt = 0f
        for (i in 1 until recent.size) {
            val t = (recent[i].second - recent[i - 1].second).toFloat()
            if (t > 0) {
                dx += recent[i].first.centerX() - recent[i - 1].first.centerX()
                dy += recent[i].first.centerY() - recent[i - 1].first.centerY()
                dt += t
            }
        }
        if (dt < 10f) return latest.first

        val vx = dx / dt; val vy = dy / dt
        val w = latest.first.width(); val h = latest.first.height()

        return Rect(
            (latest.first.left + vx * elapsed).toInt(),
            (latest.first.top + vy * elapsed).toInt(),
            (latest.first.left + vx * elapsed + w).toInt(),
            (latest.first.top + vy * elapsed + h).toInt()
        )
    }
}

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

    // Tracked predictions — persist across frames, survive blurry frames
    val predictions = remember { mutableStateMapOf<String, QrPrediction>() }
    // Full overlay list (real + predicted) for tap detection
    val overlayListState = remember { mutableStateListOf<DetectedQr>() }

    LaunchedEffect(torchOn, controllerRef.value) {
        val ctrl = controllerRef.value ?: return@LaunchedEffect
        try { ctrl.enableTorch(torchOn) } catch (_: Exception) {}
    }

    LaunchedEffect(trackingResetSignal) {
        predictions.clear()
        overlayListState.clear()
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

                val scanner = BarcodeScanning.getClient(
                    com.google.mlkit.vision.barcode.BarcodeScannerOptions.Builder()
                        .setBarcodeFormats(FORMAT_QR_CODE)
                        .build()
                )

                controller.setImageAnalysisAnalyzer(
                    ContextCompat.getMainExecutor(ctx),
                    MlKitAnalyzer(
                        listOf(scanner),
                        CameraController.COORDINATE_SYSTEM_VIEW_REFERENCED,
                        ContextCompat.getMainExecutor(ctx)
                    ) { result: MlKitAnalyzer.Result? ->
                        val now = System.currentTimeMillis()
                        val barcodes: List<Barcode>? = result?.getValue(scanner) as? List<Barcode>

                        // Real detections this frame
                        val newDetections = (barcodes ?: emptyList()).mapNotNull { b ->
                            val raw = b.rawValue ?: return@mapNotNull null
                            DetectedQr(raw, b.boundingBox ?: Rect(0,0,0,0), classifyQr(raw))
                        }

                        val detectedKeys = newDetections.map { it.rawValue }.toSet()

                        // Update prediction history with real detections
                        for (qr in newDetections) {
                            if (qr.boundingBox.isEmpty) continue
                            val pred = predictions.getOrPut(qr.rawValue) {
                                QrPrediction(qr.rawValue, qr.qrType)
                            }
                            pred.addPosition(qr.boundingBox, now)
                        }

                        // Build overlay: real detections + predicted positions
                        val overlay = mutableListOf<DetectedQr>()
                        overlay.addAll(newDetections)

                        val toRemove = mutableListOf<String>()
                        for ((raw, pred) in predictions) {
                            if (raw in detectedKeys) continue
                            val predictedBox = pred.predict(now)
                            if (predictedBox != null) {
                                overlay.add(DetectedQr(raw, predictedBox, pred.qrType))
                            } else {
                                toRemove.add(raw)
                            }
                        }
                        for (key in toRemove) predictions.remove(key)

                        overlayListState.clear()
                        overlayListState.addAll(overlay)
                        onQrsDetected(newDetections)
                    }
                )

                pv.setOnTouchListener { _: View, event: MotionEvent ->
                    if (event.action == MotionEvent.ACTION_UP) {
                        val tx = event.x; val ty = event.y
                        val tapped = overlayListState.minByOrNull { qr ->
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
            val now = System.currentTimeMillis()
            val overlayKeys = overlayQrs.map { it.first.rawValue }.toSet()

            // Start with state-provided overlay QRs
            val combined = overlayQrs.toMutableList()

            // Add predicted (tracked but not decoded this frame) as green
            for ((raw, pred) in predictions) {
                if (raw in overlayKeys) continue
                val predictedBox = pred.predict(now) ?: continue
                combined.add(DetectedQr(raw, predictedBox, pred.qrType) to OverlayColor.GREEN)
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
