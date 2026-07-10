package com.storagesystem.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Rect
import android.util.Size
import android.view.MotionEvent
import android.view.View
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
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
import com.storagesystem.data.models.DetectedQr
import com.storagesystem.data.models.OverlayColor
import com.storagesystem.data.models.QrType
import com.storagesystem.data.repository.parseQrRaw
import com.storagesystem.ui.camera.QrAnalyzer
import kotlin.math.sqrt
import java.util.concurrent.Executors

@Composable
fun CameraPreview(
    overlayQrs: List<Pair<DetectedQr, OverlayColor>>,
    onQrsDetected: (List<DetectedQr>) -> Unit,
    onTapQr: (DetectedQr) -> Unit,
    modifier: Modifier = Modifier,
    torchOn: Boolean = false,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestQrs = remember { mutableStateListOf<DetectedQr>() }
    val sensorToView = remember { mutableStateOf<android.graphics.Matrix?>(null) }

    // Map from image coords (640x640) to view coords
    fun mapBox(box: Rect): Rect {
        val mat = sensorToView.value ?: return box
        val src = android.graphics.RectF(box)
        val dst = android.graphics.RectF()
        mat.mapRect(dst, src)
        return Rect(dst.left.toInt(), dst.top.toInt(), dst.right.toInt(), dst.bottom.toInt())
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

                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    val provider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build()
                        .also { it.surfaceProvider = pv.surfaceProvider }

                    // 640x640 is optimal for ML Kit barcode scanning — fast and close-range
                    val analyzer = QrAnalyzer { qrs ->
                        val mapped = qrs.map { qr ->
                            qr.copy(boundingBox = mapBox(qr.boundingBox))
                        }
                        latestQrs.clear()
                        latestQrs.addAll(mapped)
                        onQrsDetected(mapped)
                    }

                    val imageAnalysis = ImageAnalysis.Builder()
                        .setTargetResolution(Size(640, 640))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { it.setAnalyzer(Executors.newSingleThreadExecutor(), analyzer) }

                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageAnalysis
                    )

                    // Get sensor-to-view matrix for coordinate mapping
                    try { sensorToView.value = pv.getSensorToViewTransform() } catch (_: Exception) {}
                }, ContextCompat.getMainExecutor(ctx))

                // Update transform on layout changes
                pv.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                    try { sensorToView.value = pv.getSensorToViewTransform() } catch (_: Exception) {}
                }

                pv.setOnTouchListener { _: View, event: MotionEvent ->
                    if (event.action == MotionEvent.ACTION_UP) {
                        val tx = event.x; val ty = event.y
                        val tapped = latestQrs.minByOrNull { qr ->
                            val cx = (qr.boundingBox.left + qr.boundingBox.right) / 2f
                            val cy = (qr.boundingBox.top + qr.boundingBox.bottom) / 2f
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
            for ((qr, color) in overlayQrs) {
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
                drawRect(drawColor.copy(alpha = when (color) { OverlayColor.GREEN_MATCH -> 0.3f; OverlayColor.YELLOW -> 0.25f; OverlayColor.BLUE -> 0.2f; else -> 0.15f }),
                    Offset(box.left.toFloat(), box.top.toFloat()), ComposeSize(box.width().toFloat(), box.height().toFloat()))

                val label = buildQuickLabel(qr, color) ?: continue
                val paint = android.graphics.Paint().apply { this.color = drawColor.toArgb(); textSize = 36f; isAntiAlias = true; typeface = android.graphics.Typeface.MONOSPACE; isFakeBoldText = true }
                val bg = android.graphics.Paint().apply { this.color = android.graphics.Color.argb(200, 0, 0, 0) }
                val tw = paint.measureText(label)
                val lx = (box.left + box.right - tw.toInt()) / 2f; val ly = (box.top - 12f).coerceAtLeast(4f)
                drawContext.canvas.nativeCanvas.drawRoundRect(lx - 8f, ly - 30f, lx + tw + 8f, ly + 4f, 6f, 6f, bg)
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
            is com.storagesystem.data.repository.QrParseResult.Container -> "📦 ${p.cid.take(8)}…"
            is com.storagesystem.data.repository.QrParseResult.LcscBag -> "🔩 ${p.lcscPartNumber}"
            is com.storagesystem.data.repository.QrParseResult.Unknown -> null
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
