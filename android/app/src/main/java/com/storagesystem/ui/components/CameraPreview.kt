package com.storagesystem.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Rect
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
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
import androidx.compose.ui.unit.dp
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

private const val TAG = "CameraPreview"

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

    LaunchedEffect(overlayQrs) {
        latestQrs.clear()
        latestQrs.addAll(overlayQrs.map { it.first })
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
                        val barcodes: List<Barcode>? = result?.getValue(scanner) as? List<Barcode>
                        val detected = (barcodes ?: emptyList()).mapNotNull { barcode ->
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
                        if (detected.isNotEmpty()) {
                            Log.d(TAG, "Detected ${detected.size} QR(s): ${detected.first().rawValue.take(40)}")
                        }
                    }
                )

                pv
            }
        )

        // Canvas draws overlays (doesn't consume touches)
        Canvas(modifier = Modifier.fillMaxSize()) {
            for ((qr, color) in overlayQrs) {
                val box = qr.boundingBox
                if (box.isEmpty) continue

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

                val label = buildQuickLabel(qr, color) ?: continue
                val paint = android.graphics.Paint().apply {
                    this.color = drawColor.toArgb(); textSize = 36f; isAntiAlias = true
                    typeface = android.graphics.Typeface.MONOSPACE; isFakeBoldText = true
                }
                val bg = android.graphics.Paint().apply { this.color = android.graphics.Color.argb(200, 0, 0, 0) }
                val tw = paint.measureText(label)
                val lx = (box.left + box.right - tw.toInt()) / 2f
                val ly = (box.top - 12f).coerceAtLeast(4f)
                drawContext.canvas.nativeCanvas.drawRoundRect(lx - 8f, ly - 30f, lx + tw + 8f, ly + 4f, 6f, 6f, bg)
                drawContext.canvas.nativeCanvas.drawText(label, lx, ly, paint)
            }
            drawAimReticle(size)
        }

        // Tap overlay (on top — only covers canvas, doesn't block camera)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(overlayQrs) {
                    detectTapGestures { offset ->
                        Log.d(TAG, "Tap at (${offset.x}, ${offset.y}), ${latestQrs.size} QRs")
                        val tapped = latestQrs.minByOrNull { qr ->
                            val r = qr.boundingBox
                            val cx = (r.left + r.right) / 2f
                            val cy = (r.top + r.bottom) / 2f
                            val dx = offset.x - cx
                            val dy = offset.y - cy
                            sqrt(dx * dx + dy * dy)
                        }
                        if (tapped != null) {
                            Log.d(TAG, "→ matched: ${tapped.rawValue.take(40)}")
                            onTapQr(tapped)
                        } else {
                            Log.d(TAG, "→ no match")
                        }
                    }
                }
        )
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
    val cx = size.width / 2f; val cy = size.height / 2f
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

private fun classifyQr(rawValue: String): QrType {
    val trimmed = rawValue.trim()
    return when {
        trimmed.startsWith("{") && trimmed.contains("\"cid\"") -> QrType.CONTAINER
        trimmed.startsWith("{") && trimmed.contains("=") -> QrType.LCSC_BAG
        else -> QrType.UNKNOWN
    }
}
