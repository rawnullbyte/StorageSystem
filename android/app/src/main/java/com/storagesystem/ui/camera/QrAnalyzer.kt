package com.storagesystem.ui.camera

import android.graphics.Rect
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE
import com.google.mlkit.vision.common.InputImage
import com.storagesystem.data.models.DetectedQr
import com.storagesystem.data.models.QrType
import java.util.concurrent.atomic.AtomicInteger

/**
 * CameraX [ImageAnalysis.Analyzer] that uses ML Kit [BarcodeScanner] to
 * detect QR codes in each camera frame.
 *
 * Bounding boxes are returned in **image coordinates** (the resolution
 * set via [ImageAnalysis.Builder.setTargetResolution]).
 * The caller must transform them to view coordinates via
 * [androidx.camera.view.transform.CoordinateTransform.mapRect].
 *
 * Frame throttle: processes every Nth frame to keep UI responsive.
 */
class QrAnalyzer(
    private val onQrsDetected: (List<DetectedQr>) -> Unit
) : ImageAnalysis.Analyzer {

    private val scanner: BarcodeScanner = BarcodeScanning.getClient(
        com.google.mlkit.vision.barcode.BarcodeScannerOptions.Builder()
            .setBarcodeFormats(FORMAT_QR_CODE)
            .build()
    )

    private val frameCount = AtomicInteger(0)
    private val throttle = 2  // process every 2nd frame

    override fun analyze(imageProxy: ImageProxy) {
        val frame = frameCount.incrementAndGet()
        if (frame % throttle != 0) {
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        val inputImage = InputImage.fromMediaImage(
            mediaImage,
            imageProxy.imageInfo.rotationDegrees
        )

        scanner.process(inputImage)
            .addOnSuccessListener { barcodes ->
                val detected = barcodes.mapNotNull { barcode ->
                    val rawValue = barcode.rawValue ?: return@mapNotNull null
                    val box = barcode.boundingBox ?: return@mapNotNull null
                    DetectedQr(
                        rawValue = rawValue,
                        boundingBox = box,
                        qrType = classifyQr(rawValue)
                    )
                }
                if (detected.isNotEmpty()) {
                    onQrsDetected(detected)
                }
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

    fun close() {
        scanner.close()
    }
}
