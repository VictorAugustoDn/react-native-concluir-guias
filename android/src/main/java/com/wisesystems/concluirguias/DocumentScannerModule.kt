package com.wisesystems.concluirguias

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.facebook.react.bridge.*
import com.facebook.react.module.annotations.ReactModule
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.documentscanner.GmsDocumentScanner
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.launch
import kotlin.coroutines.suspendCoroutine
import kotlin.coroutines.resume
import java.io.InputStream
import java.lang.ref.WeakReference


@ReactModule(name = DocumentScannerModule.NAME)
class DocumentScannerModule(reactContext: ReactApplicationContext) :
    NativeDocumentScannerSpec(reactContext) {

    companion object {
        const val NAME = "DocumentScanner"
        private const val ANDROID_15_API = 35
        
        // --- CONFIGURAÇÕES FIXAS DO BARCODE ---
        private const val BARCODE_FORMAT = Barcode.FORMAT_ITF
        private const val LARGURA_CORTE_PERCENTUAL = 20
        private const val ALTURA_CORTE_PERCENTUAL = 16
        private const val MARGEM_CANTO_PERCENTUAL = 2
    }

    override fun getName(): String = NAME

    private var launcher: ActivityResultLauncher<IntentSenderRequest>? = null
    private var pendingPromise: Promise? = null
    private var scanner: GmsDocumentScanner? = null
    private var hostActivityRef: WeakReference<ComponentActivity>? = null
    private var previousFitsSystemWindows: Boolean? = null

    override fun scanDocument(options: ReadableMap, promise: Promise) {
        val activity = currentActivity as? ComponentActivity
        if (activity == null) {
            promise.reject("no_activity", "Activity not available or not a ComponentActivity")
            return
        }

        if (pendingPromise != null) {
            promise.reject("scan_in_progress", "Scan already in progress")
            return
        }

        pendingPromise = promise
        hostActivityRef = WeakReference(activity)
        
        // Garante visibilidade das barras (fix Android 15)
        ensureSystemBarsVisible(activity)

        initLauncher(activity)
        
        val builder = GmsDocumentScannerOptions.Builder()
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)

        if (options.hasKey("maxNumDocuments")) {
            builder.setPageLimit(options.getInt("maxNumDocuments"))
        }
        
        val scannerClient = GmsDocumentScanning.getClient(builder.build())
        
        scannerClient.getStartScanIntent(activity)
            .addOnSuccessListener { intentSender ->
                launcher?.launch(IntentSenderRequest.Builder(intentSender).build())
            }
            .addOnFailureListener { e ->
                promise.reject("document_scan_error", e.message)
                clearPending()
            }
    }

    private fun initLauncher(activity: ComponentActivity) {
        if (launcher != null) return
        launcher = activity.activityResultRegistry.register(
            "document-scanner",
            ActivityResultContracts.StartIntentSenderForResult()
        ) { result ->
            val promise = pendingPromise ?: return@register
            val response = WritableNativeMap()
            val docScanResults = WritableNativeArray()

            if (result.resultCode == Activity.RESULT_OK) {
                val docResult = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
                val pages = docResult?.pages

                if (pages != null && pages.isNotEmpty()) {
                    // Usamos a lifecycleScope da activity para processar o barcode
                    activity.lifecycleScope.launch {
                        for (page in pages) {
                            val uri = page.imageUri ?: continue
                            val bitmap = loadBitmapFromUri(activity, uri)
                            var barcodeValue: String? = null

                            if (bitmap != null) {
                                val roi = calculateBarcodeRoi(bitmap.width, bitmap.height)
                                barcodeValue = decodeBarcodeWithMLKit(bitmap, roi)
                            }

                            val resultObject = WritableNativeMap()
                            resultObject.putString("uri", uri.toString())
                            resultObject.putString("barcode", barcodeValue)
                            resultObject.putBoolean("success", barcodeValue != null)
                            docScanResults.pushMap(resultObject)
                        }

                        response.putArray("scannedImages", docScanResults)
                        response.putString("status", "success")
                        promise.resolve(response)
                        clearPending()
                    }
                } else {
                    response.putString("status", "success")
                    response.putArray("scannedImages", docScanResults)
                    promise.resolve(response)
                    clearPending()
                }
            } else {
                response.putString("status", "cancel")
                promise.resolve(response)
                clearPending()
            }
        }
    }

    // --- MÉTODOS DE PROCESSAMENTO DE IMAGEM (Vindo do seu original) ---

    private fun loadBitmapFromUri(activity: Activity, uri: Uri): Bitmap? {
        return try {
            activity.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            null
        }
    }

    private fun calculateBarcodeRoi(width: Int, height: Int): Rect {
        val larguraCorte = (width * LARGURA_CORTE_PERCENTUAL) / 100
        val alturaCorte = (height * ALTURA_CORTE_PERCENTUAL) / 100
        val posicaoX = (width * MARGEM_CANTO_PERCENTUAL) / 100
        
        return Rect(
            (width - larguraCorte - posicaoX).coerceAtLeast(0),
            MARGEM_CANTO_PERCENTUAL,
            (width - posicaoX).coerceAtMost(width),
            (alturaCorte + MARGEM_CANTO_PERCENTUAL).coerceAtMost(height)
        )
    }

    private suspend fun decodeBarcodeWithMLKit(bitmap: Bitmap, roi: Rect): String? =
        suspendCoroutine { continuation ->
            val options = BarcodeScannerOptions.Builder()
                .setBarcodeFormats(BARCODE_FORMAT)
                .build()
            val scanner = BarcodeScanning.getClient(options)
            
            try {
                val cropped = Bitmap.createBitmap(bitmap, roi.left, roi.top, roi.width(), roi.height())
                val image = InputImage.fromBitmap(cropped, 0)
                scanner.process(image)
                    .addOnSuccessListener { barcodes ->
                        val result = barcodes.firstOrNull { it.format == BARCODE_FORMAT }?.rawValue
                        continuation.resume(result)
                    }
                    .addOnFailureListener {
                        continuation.resume(null)
                    }
            } catch (e: Exception) {
                continuation.resume(null)
            }
        }

    private fun clearPending() {
        pendingPromise = null
        restoreSystemBars()
    }

    private fun ensureSystemBarsVisible(activity: ComponentActivity) {
        if (Build.VERSION.SDK_INT < ANDROID_15_API || previousFitsSystemWindows != null) return
        val decor = activity.window.decorView
        @Suppress("DEPRECATION")
        previousFitsSystemWindows = decor.fitsSystemWindows
        WindowCompat.setDecorFitsSystemWindows(activity.window, true)
    }

    private fun restoreSystemBars() {
        val previous = previousFitsSystemWindows ?: return
        hostActivityRef?.get()?.let { WindowCompat.setDecorFitsSystemWindows(it.window, previous) }
        previousFitsSystemWindows = null
        hostActivityRef = null
    }
}