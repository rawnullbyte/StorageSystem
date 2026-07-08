package com.storagesystem.ui.components

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.storagesystem.data.models.DetectedQr
import com.storagesystem.data.models.OverlayColor
import com.storagesystem.ui.camera.QrAnalyzer
import java.util.concurrent.Executors

/**
 * Composable that renders the CameraX [PreviewView] and draws
 * bounding-box overlays for detected QR codes.
 *
 * @param overlayQrs  List of detected QR codes with overlay colour hints.
 * @param onQrsDetected  Callback from the analyzer with raw detections.
 * @param onTapQr  Called when the user taps near a bounding box.
 * @param modifier  Compose modifier.
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

    // Track the latest raw QRs for tap detection (in image coordinates)
    val latestQrs = remember { mutableStateListOf<DetectedQr>() }

    // Image analysis resolution (must match the builder)
    val IMAGE_WIDTH = 1280f
    val IMAGE_HEIGHT = 720f

    // Camera permission
    val hasCameraPermission = remember {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    Box(modifier = modifier.fillMaxSize()) {
        // Camera Preview (AndroidView, fills the whole Box)
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx)

                if (hasCameraPermission) {
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                    cameraProviderFuture.addListener({
                        val cameraProvider = cameraProviderFuture.get()

                        val preview = Preview.Builder()
                            .build()
                            .also { it.setSurfaceProvider(previewView.surfaceProvider) }

                        val analyzer = QrAnalyzer { qrs ->
                            latestQrs.clear()
                            latestQrs.addAll(qrs)
                            onQrsDetected(qrs)
                        }

                        @Suppress("DEPRECATION")
                        val imageAnalysis = ImageAnalysis.Builder()
                            .setTargetResolution(Size(IMAGE_WIDTH.toInt(), IMAGE_HEIGHT.toInt()))
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                            .also { it.setAnalyzer(Executors.newSingleThreadExecutor(), analyzer) }

                        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            cameraSelector,
                            preview,
                            imageAnalysis
                        )
                    }, ContextCompat.getMainExecutor(ctx))
                }

                previewView
            }
        )

        // Overlay Canvas — draws bounding boxes scaled to view coordinates
        Canvas(modifier = Modifier.fillMaxSize()) {
            val scaleX = size.width / IMAGE_WIDTH
            val scaleY = size.height / IMAGE_HEIGHT

            for ((qr, color) in overlayQrs) {
                val box = qr.boundingBox

                val left = box.left * scaleX
                val top = box.top * scaleY
                val rectWidth = box.width() * scaleX
                val rectHeight = box.height() * scaleY

                val drawColor = when (color) {
                    OverlayColor.YELLOW -> Color.Yellow
                    OverlayColor.GREEN, OverlayColor.GREEN_MATCH -> Color.Green
                    OverlayColor.BLUE -> Color.Blue
                    OverlayColor.RED_NON_MATCH -> Color.Red
                }

                // Outline
                drawRect(
                    color = drawColor,
                    topLeft = Offset(left, top),
                    size = ComposeSize(rectWidth, rectHeight),
                    style = Stroke(width = 4f)
                )

                // Translucent fill
                drawRect(
                    color = when (color) {
                        OverlayColor.GREEN_MATCH -> Color.Green.copy(alpha = 0.25f)
                        OverlayColor.RED_NON_MATCH -> Color.Red.copy(alpha = 0.2f)
                        OverlayColor.YELLOW -> Color.Yellow.copy(alpha = 0.2f)
                        OverlayColor.BLUE -> Color.Blue.copy(alpha = 0.15f)
                        OverlayColor.GREEN -> Color.Green.copy(alpha = 0.15f)
                    },
                    topLeft = Offset(left, top),
                    size = ComposeSize(rectWidth, rectHeight)
                )
            }
        }

        // Transparent overlay for tap detection
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { tapOffset ->
                        val tapX = tapOffset.x
                        val tapY = tapOffset.y

                        // Find closest bounding box centroid (using the latest raw QRs)
                        val tappedQr = latestQrs.minByOrNull { qr ->
                            val box = qr.boundingBox
                            val scaleX = size.width / IMAGE_WIDTH
                            val scaleY = size.height / IMAGE_HEIGHT
                            val cx = box.centerX() * scaleX
                            val cy = box.centerY() * scaleY
                            kotlin.math.sqrt(
                                (tapX - cx) * (tapX - cx) + (tapY - cy) * (tapY - cy)
                            )
                        }

                        if (tappedQr != null) {
                            onTapQr(tappedQr)
                        }
                    }
                }
        )
    }
}
