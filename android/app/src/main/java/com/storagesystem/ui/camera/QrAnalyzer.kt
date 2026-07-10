package com.storagesystem.ui.camera

import android.graphics.Rect
import android.util.Size
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

class QrAnalyzer(
    private val onQrsDetected: (List<DetectedQr>) -> Unit
) : ImageAnalysis.Analyzer {

    private val scanner: BarcodeScanner = BarcodeScanning.getClient(
        com.google.mlkit.vision.barcode.BarcodeScannerOptions.Builder()
            .setBarcodeFormats(FORMAT_QR_CODE)
            .build()
    )

    // Process every frame — ML Kit is fast enough with STRATEGY_KEEP_ONLY_LATEST
    override fun analyze(imageProxy: ImageProxy) {
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
                    DetectedQr(rawValue, box, classifyQr(rawValue))
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
        val t = rawValue.trim()
        return when {
            t.startsWith("{") && t.contains("\"cid\"") -> QrType.CONTAINER
            t.startsWith("{") && (t.contains("=") || t.contains("pbn:") || t.contains(",pc:")) -> QrType.LCSC_BAG
            else -> QrType.UNKNOWN
        }
    }

    fun close() { scanner.close() }
}
