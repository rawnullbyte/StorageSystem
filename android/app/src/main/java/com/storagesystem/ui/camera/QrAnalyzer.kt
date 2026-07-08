package com.storagesystem.ui.camera

import android.graphics.Rect
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.storagesystem.data.models.DetectedQr
import com.storagesystem.data.models.QrType

/**
 * CameraX [ImageAnalysis.Analyzer] that uses ML Kit BarcodeScanner
 * to detect QR codes in each camera frame.
 *
 * Detected barcodes are forwarded to the [onQrsDetected] callback
 * along with their bounding boxes (in image coordinates).
 *
 * To avoid overwhelming the UI, the analyzer uses a frame-throttle:
 * only processes every Nth frame (configurable).
 */
class QrAnalyzer(
    private val onQrsDetected: (List<DetectedQr>) -> Unit
) : ImageAnalysis.Analyzer {

    /** Throttle: process every N frames (15 fps → every 2 = ~7.5 fps). */
    private var frameCounter = 0
    private val throttle = 2

    @androidx.camera.core.ExperimentalGetImage
    override fun analyze(imageProxy: ImageProxy) {
        frameCounter++

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        // Build ML Kit InputImage
        val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

        val scanner = BarcodeScanning.getClient()
        scanner.process(inputImage)
            .addOnSuccessListener { barcodes ->
                val detected = barcodes.mapNotNull { barcode ->
                    val rawValue = barcode.rawValue
                    val boundingBox = barcode.boundingBox
                    if (rawValue != null && boundingBox != null) {
                        DetectedQr(
                            rawValue = rawValue,
                            boundingBox = boundingBox,
                            qrType = classifyQr(rawValue)
                        )
                    } else {
                        null
                    }
                }
                onQrsDetected(detected)
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }

    private fun classifyQr(rawValue: String): QrType {
        val trimmed = rawValue.trim()
        return when {
            trimmed.startsWith("{") && trimmed.contains("\"cid\"") -> QrType.CONTAINER
            trimmed.startsWith("{") && trimmed.contains("=") -> QrType.LCSC_BAG
            else -> QrType.UNKNOWN
        }
    }
}
