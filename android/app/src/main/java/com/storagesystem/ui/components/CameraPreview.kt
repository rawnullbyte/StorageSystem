package com.storagesystem.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Rect
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageAnalysis.COORDINATE_SYSTEM_VIEW_REFERENCED
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.PreviewView
import androidx.camera.mlkit.vision.MlKitAnalyzer.Result
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
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE
import com.storagesystem.data.models.DetectedQr
import com.storagesystem.data.models.OverlayColor
import com.storagesystem.data.models.QrType
import com.storagesystem.data.repository.parseQrRaw
import com.storagesystem.data.repository.QrParseResult
import java.util.concurrent.Executors
import kotlin.math.sqrt

/**
 * Camera preview with AR-style QR overlay.
 *
 * Uses [MlKitAnalyzer] with [MlKitAnalyzer.COORDINATE_SYSTEM_VIEW_REFERENCED],
 * which returns QR bounding boxes directly in PreviewView pixel coordinates.
 * The Canvas overlay draws at those same coordinates — no manual
 * image→view transformation needed.
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
    val hasCameraPermission = remember {
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    Box(modifier = modifier.fillMaxSize()) {
        // CameraX PreviewView + MlKitAnalyzer bound to lifecycle
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val pv = PreviewView(ctx)
                if (!hasCameraPermission) return@AndroidView pv

                val scanner = BarcodeScanning.getClient(
                    com.google.mlkit.vision.barcode.BarcodeScannerOptions.Builder()
                        .setBarcodeFormats(FORMAT_QR_CODE)
                        .build()
                )

                val mlKitAnalyzer = MlKitAnalyzer(
                    listOf(scanner),
                    COORDINATE_SYSTEM_VIEW_REFERENCED,
                    ContextCompat.getMainExecutor(ctx)
                ) { result: Result? ->
                    val barcodes = result?.getValue(scanner)
                        ?: emptyList<Barcode>()
                    val detected = barcodes.mapNotNull { barcode ->
                        val raw = barcode.rawValue ?: return@mapNotNull null
                        DetectedQr(
                            rawValue = raw,
                            boundingBox = barcode.boundingBox ?: Rect(0, 0, 0, 0),
                            qrType = classifyQr(raw)
                        )
                    }
                    latestQrs.clear()
                    latestQrs.addAll(detected)
                    onQrsDetected(detected)
                }

                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    val provider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build()
                        .also { it.surfaceProvider = pv.surfaceProvider }
                    val imageAnalysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { it.setAnalyzer(Executors.newSingleThreadExecutor(), mlKitAnalyzer) }
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageAnalysis
                    )
                }, ContextCompat.getMainExecutor(ctx))

                pv
            }
        )

        // ── AR Overlay Canvas ─────────────────────────────────────────
        Canvas(modifier = Modifier.fillMaxSize()) {
            for ((qr, color) in overlayQrs) {
                val box = qr.boundingBox
                val drawColor = when (color) {
                    OverlayColor.YELLOW -> Color.Yellow
                    OverlayColor.GREEN, OverlayColor.GREEN_MATCH -> Color.Green
                    OverlayColor.BLUE -> Color.Blue
                    OverlayColor.RED_NON_MATCH -> Color.Red
                }
                val strokeW = if (color == OverlayColor.GREEN_MATCH || color == OverlayColor.YELLOW) 6f else 3f

                drawRect(
                    color = drawColor,
                    topLeft = Offset(box.left.toFloat(), box.top.toFloat()),
                    size = ComposeSize(box.width().toFloat(), box.height().toFloat()),
                    style = Stroke(width = strokeW)
                )
                drawRect(
                    color = drawColor.copy(alpha = when (color) {
                        OverlayColor.GREEN_MATCH -> 0.3f
                        OverlayColor.YELLOW -> 0.25f
                        OverlayColor.BLUE -> 0.2f
                        else -> 0.15f
                    }),
                    topLeft = Offset(box.left.toFloat(), box.top.toFloat()),
                    size = ComposeSize(box.width().toFloat(), box.height().toFloat())
                )

                // Floating label above box
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
                    val lx = (box.left + box.right - tw.toInt()) / 2f
                    val ly = (box.top - 12f).coerceAtLeast(4f)

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
                        val tapped = latestQrs.minByOrNull { qr ->
                            val cx = qr.boundingBox.centerX().toFloat()
                            val cy = qr.boundingBox.centerY().toFloat()
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

private fun DrawScope.drawAimReticle(size: ComposeSize) {
    val cx = size.width / 2f
    val cy = size.height / 2f
    val r = 60f; val bl = 24f
    val c = Color.White.copy(alpha = 0.5f)
    fun l(x1: Float, y1: Float, x2: Float, y2: Float) =
        drawLine(c, Offset(x1, y1), Offset(x2, y2), strokeWidth = 3f)
    l(cx - r, cy - r, cx - r + bl, cy - r)
    l(cx - r, cy - r, cx - r, cy - r + bl)
    l(cx + r, cy - r, cx + r - bl, cy - r)
    l(cx + r, cy - r, cx + r, cy - r + bl)
    l(cx - r, cy + r, cx - r + bl, cy + r)
    l(cx - r, cy + r, cx - r, cy + r - bl)
    l(cx + r, cy + r, cx + r - bl, cy + r)
    l(cx + r, cy + r, cx + r, cy + r - bl)
}

private fun classifyQr(rawValue: String): QrType {
    val trimmed = rawValue.trim()
    return when {
        trimmed.startsWith("{") && trimmed.contains("\"cid\"") -> QrType.CONTAINER
        trimmed.startsWith("{") && trimmed.contains("=") -> QrType.LCSC_BAG
        else -> QrType.UNKNOWN
    }
}
