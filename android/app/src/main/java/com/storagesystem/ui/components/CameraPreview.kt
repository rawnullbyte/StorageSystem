package com.storagesystem.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Rect
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
 * Coordinate mapping: CameraX ImageAnalysis produces a 1280×720 image
 * (landscape sensor orientation). On a portrait phone the image is
 * rotated 90°, so the actual captured orientation is 720×1280.
 * ML Kit returns barcode bounding boxes in the **rotated** image
 * coordinate space (720 wide × 1280 tall on portrait).
 *
 * This composable:
 * - Detects the display rotation and swaps dimensions accordingly
 * - Draws correctly-positioned bounding boxes with AR-style floating labels
 * - Shows a center aiming reticle
 * - Handles tap-to-select via coordinate-mapped hit testing
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

    val latestQrs = remember { mutableStateListOf<DetectedQr>() }

    // Tack the display rotation so we can swap image dimensions
    val displayRotation = remember { mutableIntStateOf(0) }

    val hasCameraPermission = remember {
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val pv = PreviewView(ctx)
                displayRotation.value =
                    ctx.display?.rotation ?: 0

                if (hasCameraPermission) {
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                    cameraProviderFuture.addListener({
                        val cameraProvider = cameraProviderFuture.get()

                        val preview = Preview.Builder().build()
                            .also { it.setSurfaceProvider(pv.surfaceProvider) }

                        val analyzer = QrAnalyzer { qrs ->
                            latestQrs.clear()
                            latestQrs.addAll(qrs)
                            onQrsDetected(qrs)
                        }

                        @Suppress("DEPRECATION")
                        val imageAnalysis = ImageAnalysis.Builder()
                            .setTargetResolution(Size(1280, 720))
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                            .also { it.setAnalyzer(Executors.newSingleThreadExecutor(), analyzer) }

                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            imageAnalysis
                        )
                    }, ContextCompat.getMainExecutor(ctx))
                }
                pv
            }
        )

        // ── AR Overlay Canvas ─────────────────────────────────────────
        Canvas(modifier = Modifier.fillMaxSize()) {
            val rot = displayRotation.value
            // Image sensor produces 1280x720 (landscape).
            // On rotation 90/270 (portrait phone) the actual image is 720x1280.
            val imgW = if (rot == 0 || rot == 180) 1280f else 720f
            val imgH = if (rot == 0 || rot == 180) 720f else 1280f
            val scaleX = size.width / imgW
            val scaleY = size.height / imgH

            for ((qr, color) in overlayQrs) {
                val box = qr.boundingBox
                val mapped = Rect(
                    (box.left * scaleX).toInt(),
                    (box.top * scaleY).toInt(),
                    (box.right * scaleX).toInt(),
                    (box.bottom * scaleY).toInt()
                )

                val drawColor = when (color) {
                    OverlayColor.YELLOW -> Color.Yellow
                    OverlayColor.GREEN, OverlayColor.GREEN_MATCH -> Color.Green
                    OverlayColor.BLUE -> Color.Blue
                    OverlayColor.RED_NON_MATCH -> Color.Red
                }

                val strokeW = if (color == OverlayColor.GREEN_MATCH || color == OverlayColor.YELLOW) 6f else 3f
                drawRect(
                    color = drawColor,
                    topLeft = Offset(mapped.left.toFloat(), mapped.top.toFloat()),
                    size = ComposeSize(mapped.width().toFloat(), mapped.height().toFloat()),
                    style = Stroke(width = strokeW)
                )

                // Translucent fill
                val fillAlpha = when (color) {
                    OverlayColor.GREEN_MATCH -> 0.3f
                    OverlayColor.YELLOW -> 0.25f
                    OverlayColor.BLUE -> 0.2f
                    OverlayColor.RED_NON_MATCH -> 0.15f
                    OverlayColor.GREEN -> 0.2f
                }
                drawRect(
                    color = drawColor.copy(alpha = fillAlpha),
                    topLeft = Offset(mapped.left.toFloat(), mapped.top.toFloat()),
                    size = ComposeSize(mapped.width().toFloat(), mapped.height().toFloat())
                )

                // Floating label
                val label = buildLabel(qr, color)
                if (label.isNotEmpty()) {
                    val paint = android.graphics.Paint().apply {
                        this.color = drawColor.toArgb()
                        textSize = 36f
                        isAntiAlias = true
                        typeface = android.graphics.Typeface.MONOSPACE
                        isFakeBoldText = true
                    }
                    val bg = android.graphics.Paint().apply {
                        this.color = android.graphics.Color.argb(200, 0, 0, 0)
                    }
                    val tw = paint.measureText(label)
                    val lx = (mapped.left + mapped.right - tw.toInt()) / 2f
                    val ly = (mapped.top - 12f).coerceAtLeast(4f)

                    drawContext.canvas.nativeCanvas.drawRoundRect(
                        lx - 8f, ly - 28f, lx + tw + 8f, ly + 6f, 6f, 6f, bg
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
                        val rot = displayRotation.value
                        val imgW = if (rot == 0 || rot == 180) 1280f else 720f
                        val imgH = if (rot == 0 || rot == 180) 720f else 1280f
                        val sx = size.width / imgW
                        val sy = size.height / imgH

                        val tapped = latestQrs.minByOrNull { qr ->
                            val cx = qr.boundingBox.centerX() * sx
                            val cy = qr.boundingBox.centerY() * sy
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

/** Corner-bracket reticle centered on screen. */
private fun DrawScope.drawAimReticle(size: ComposeSize) {
    val cx = size.width / 2f
    val cy = size.height / 2f
    val r = 60f
    val bl = 24f
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
