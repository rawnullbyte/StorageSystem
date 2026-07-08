package com.storagesystem.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.graphics.RectF
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.storagesystem.data.models.DetectedQr
import com.storagesystem.data.models.OverlayColor
import com.storagesystem.data.repository.parseQrRaw
import com.storagesystem.data.repository.QrParseResult
import com.storagesystem.ui.camera.QrAnalyzer
import java.util.concurrent.Executors
import kotlin.math.sqrt

/**
 * Camera preview with AR-style QR overlay.
 *
 * Uses [QrAnalyzer] (ML Kit via ImageAnalysis) to detect QR codes in
 * **image coordinates** (1280×720), then maps them to **view coordinates**
 * using [PreviewView.coordinatesTransform] for pixel-perfect overlay
 * placement regardless of display rotation or aspect ratio.
 */
@Composable
fun CameraPreview(
    overlayQrs: List<Pair<DetectedQr, OverlayColor>>,
    onQrsDetected: (List<DetectedQr>) -> Unit,
    onTapQr: (DetectedQr) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Hold the CoordinateTransform (valid after PreviewView is laid out and camera is active)
    val latestQrs = remember { mutableStateListOf<DetectedQr>() }
    val sensorToViewMat = remember { mutableStateOf<Matrix?>(null) }

    val hasCameraPermission = remember {
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val pv = PreviewView(ctx)
                if (!hasCameraPermission) return@AndroidView pv

                val analyzer = QrAnalyzer { qrs ->
                    latestQrs.clear()
                    latestQrs.addAll(qrs)
                    onQrsDetected(qrs)
                }

                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    val provider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build()
                        .also { it.surfaceProvider = pv.surfaceProvider }
                    val imageAnalysis = ImageAnalysis.Builder()
                        .setTargetResolution(Size(1280, 720))
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

                    // Grab the sensor→view matrix once the camera is bound
                    try {
                        sensorToViewMat.value = pv.getSensorToViewTransform()
                    } catch (_: Exception) { }
                }, ContextCompat.getMainExecutor(ctx))

                // Also update on layout changes
                pv.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                    try {
                        sensorToViewMat.value = pv.getSensorToViewTransform()
                    } catch (_: Exception) { }
                }

                pv
            }
        )

        // ── AR Overlay Canvas ─────────────────────────────────────────
        Canvas(modifier = Modifier.fillMaxSize()) {
            val mat = sensorToViewMat.value

            for ((qr, color) in overlayQrs) {
                // Map from sensor coordinates (QR bounding box) → view coordinates
                val mapped = RectF(qr.boundingBox.left.toFloat(), qr.boundingBox.top.toFloat(),
                                   qr.boundingBox.right.toFloat(), qr.boundingBox.bottom.toFloat())
                if (mat != null) {
                    mat.mapRect(mapped)
                }

                val drawColor = when (color) {
                    OverlayColor.YELLOW -> Color.Yellow
                    OverlayColor.GREEN, OverlayColor.GREEN_MATCH -> Color.Green
                    OverlayColor.BLUE -> Color.Blue
                    OverlayColor.RED_NON_MATCH -> Color.Red
                }
                val strokeW = if (color == OverlayColor.GREEN_MATCH || color == OverlayColor.YELLOW) 6f else 3f

                // Bounding box outline
                drawRect(
                    color = drawColor,
                    topLeft = Offset(mapped.left.toFloat(), mapped.top.toFloat()),
                    size = ComposeSize(mapped.width().toFloat(), mapped.height().toFloat()),
                    style = Stroke(width = strokeW)
                )
                // Translucent fill
                drawRect(
                    color = drawColor.copy(alpha = when (color) {
                        OverlayColor.GREEN_MATCH -> 0.3f
                        OverlayColor.YELLOW -> 0.25f
                        OverlayColor.BLUE -> 0.2f
                        else -> 0.15f
                    }),
                    topLeft = Offset(mapped.left.toFloat(), mapped.top.toFloat()),
                    size = ComposeSize(mapped.width().toFloat(), mapped.height().toFloat())
                )

                // Floating label above the box
                val label = buildLabel(qr, color)
                if (label.isNotEmpty()) {
                    val paint = android.graphics.Paint().apply {
                        this.color = drawColor.toArgb()
                        textSize = 36f
                        isAntiAlias = true
                        typeface = android.graphics.Typeface.MONOSPACE
                        isFakeBoldText = true
                    }
                    val bgPaint = android.graphics.Paint().apply {
                        this.color = android.graphics.Color.argb(200, 0, 0, 0)
                    }
                    val tw = paint.measureText(label)
                    val lx = (mapped.left + mapped.right - tw.toInt()) / 2f
                    val ly = (mapped.top - 12f).coerceAtLeast(4f)

                    drawContext.canvas.nativeCanvas.drawRoundRect(
                        lx - 8f, ly - 30f, lx + tw + 8f, ly + 4f, 6f, 6f, bgPaint
                    )
                    drawContext.canvas.nativeCanvas.drawText(label, lx, ly, paint)
                }
            }

            drawAimReticle(size)
        }

        // ── Tap gesture overlay ──────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { tapOffset ->
                        val mat = sensorToViewMat.value
                        val tapped = latestQrs.minByOrNull { qr ->
                            val mapped = RectF(qr.boundingBox.left.toFloat(), qr.boundingBox.top.toFloat(),
                                               qr.boundingBox.right.toFloat(), qr.boundingBox.bottom.toFloat())
                            if (mat != null) mat.mapRect(mapped)
                            val cx = mapped.centerX()
                            val cy = mapped.centerY()
                            sqrt((tapOffset.x - cx) * (tapOffset.x - cx) + (tapOffset.y - cy) * (tapOffset.y - cy))
                        }
                        if (tapped != null) onTapQr(tapped)
                    }
                }
        )
    }
}

private fun buildLabel(qr: DetectedQr, color: OverlayColor): String {
    if (color == OverlayColor.RED_NON_MATCH) return ""
    return when (val parsed = parseQrRaw(qr.rawValue)) {
        is QrParseResult.Container -> "📦 ${parsed.cid.take(8)}…"
        is QrParseResult.LcscBag -> "🔩 ${parsed.lcscPartNumber}"
        is QrParseResult.Unknown -> ""
    }
}

/** Center aiming reticle (4 corner brackets). */
private fun DrawScope.drawAimReticle(size: ComposeSize) {
    val cx = size.width / 2f
    val cy = size.height / 2f
    val r = 60f; val bl = 24f
    val c = Color.White.copy(alpha = 0.5f)
    drawLine(c, Offset(cx - r, cy - r), Offset(cx - r + bl, cy - r), strokeWidth = 3f)
    drawLine(c, Offset(cx - r, cy - r), Offset(cx - r, cy - r + bl), strokeWidth = 3f)
    drawLine(c, Offset(cx + r, cy - r), Offset(cx + r - bl, cy - r), strokeWidth = 3f)
    drawLine(c, Offset(cx + r, cy - r), Offset(cx + r, cy - r + bl), strokeWidth = 3f)
    drawLine(c, Offset(cx - r, cy + r), Offset(cx - r + bl, cy + r), strokeWidth = 3f)
    drawLine(c, Offset(cx - r, cy + r), Offset(cx - r, cy + r - bl), strokeWidth = 3f)
    drawLine(c, Offset(cx + r, cy + r), Offset(cx + r - bl, cy + r), strokeWidth = 3f)
    drawLine(c, Offset(cx + r, cy + r), Offset(cx + r, cy + r - bl), strokeWidth = 3f)
}
