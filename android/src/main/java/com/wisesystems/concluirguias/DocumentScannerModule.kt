package com.wisesystems.concluirguias

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.exifinterface.media.ExifInterface
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@ReactModule(name = DocumentScannerModule.NAME)
class DocumentScannerModule(reactContext: ReactApplicationContext) :
    NativeDocumentScannerSpec(reactContext) {

    companion object {
        const val NAME = "DocumentScanner"
        private const val ANDROID_15_API = 35

        // --- CONFIGURAÇÕES FIXAS DO BARCODE ---
        private const val BARCODE_FORMAT = Barcode.FORMAT_ITF
        private const val LARGURA_CORTE_PERCENTUAL = 25
        private const val ALTURA_CORTE_PERCENTUAL = 20
        private const val MARGEM_CANTO_PERCENTUAL = 3
    }

    override fun getName(): String = NAME

    private var launcher: ActivityResultLauncher<IntentSenderRequest>? = null
    private var pendingPromise: Promise? = null
    private var scanner: GmsDocumentScanner? = null
    private var hostActivityRef: WeakReference<ComponentActivity>? = null
    private var previousFitsSystemWindows: Boolean? = null
    private var pendingMaxDocs: Int? = null

    override fun scanDocument(options: ReadableMap, promise: Promise) {
        val activity = getCurrentActivity() as? ComponentActivity
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

        // Fix Android 15
        ensureSystemBarsVisible(activity)

        initLauncher(activity)

        pendingMaxDocs = if (options.hasKey("maxNumDocuments")) options.getInt("maxNumDocuments") else null

        launchScannerIntent(activity)
    }

    private fun launchScannerIntent(activity: ComponentActivity) {
        val builder = GmsDocumentScannerOptions.Builder()
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_BASE)

        pendingMaxDocs?.let { builder.setPageLimit(it) }

        val scannerClient = GmsDocumentScanning.getClient(builder.build())
        this.scanner = scannerClient

        scannerClient.getStartScanIntent(activity)
            .addOnSuccessListener { intentSender ->
                launcher?.launch(IntentSenderRequest.Builder(intentSender).build())
            }
            .addOnFailureListener { e ->
                pendingPromise?.reject("document_scan_error", e.message)
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
                    activity.lifecycleScope.launch(Dispatchers.IO) {
                        var index = 0
                        for (page in pages) {
                            val originalUri = page.imageUri ?: continue

                            var bitmap = loadBitmapFromUri(activity, originalUri)
                            var finalUriString = originalUri.toString()
                            var barcodeValue: String? = null
                            var successInLoop = false

                            if (bitmap != null) {
                                // TENTATIVAS DE ROTAÇÃO (0, 90, -90, 180)
                                val angles = listOf(0f, 90f, -90f, 180f)
                                for (angle in angles) {
                                    val currentBitmap = if (angle == 0f) bitmap!! else rotateBitmap(bitmap!!, angle)
                                    val roi = calculateBarcodeRoi(currentBitmap.width, currentBitmap.height)
                                    barcodeValue = decodeBarcodeWithMLKit(currentBitmap, roi)
                                    if (barcodeValue != null) {
                                        bitmap = currentBitmap
                                        successInLoop = true
                                        break
                                    }
                                }

                                // Correção se nada foi achado mas está Landscape
                                if (!successInLoop && bitmap != null && bitmap!!.width > bitmap!!.height) {
                                    bitmap = rotateBitmap(bitmap!!, 90f)
                                }

                                if (bitmap != null) {
                                    val timestamp = System.currentTimeMillis()
                                    val newPath = saveImageToCache(activity, bitmap!!, "scan_barcode_${timestamp}_$index")
                                    if (newPath != null) finalUriString = newPath
                                }
                            }

                            val resultObject = WritableNativeMap()
                            resultObject.putString("uri", finalUriString)
                            resultObject.putBoolean("success", successInLoop)
                            resultObject.putString("barcode", barcodeValue)
                            docScanResults.pushMap(resultObject)
                            index++
                        }

                        response.putArray("scannedImages", docScanResults)
                        response.putString("status", "success")
                        promise.resolve(response)
                        clearPending()
                    }
                } else {
                    response.putString("status", "success")
                    response.putArray("scannedImages", WritableNativeArray())
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

    // --- BARCODE ---

    private suspend fun decodeBarcodeWithMLKit(bitmap: Bitmap, roi: Rect): String? =
        suspendCoroutine<String?> { continuation ->
            val options = BarcodeScannerOptions.Builder().setBarcodeFormats(BARCODE_FORMAT).build()
            val scanner = BarcodeScanning.getClient(options)
            try {
                val cropped = Bitmap.createBitmap(bitmap, roi.left, roi.top, roi.width(), roi.height())
                val image = InputImage.fromBitmap(cropped, 0)
                scanner.process(image)
                    .addOnSuccessListener { barcodes ->
                        val result = barcodes.firstOrNull { it.format == BARCODE_FORMAT }?.rawValue
                        continuation.resume(result)
                    }
                    .addOnFailureListener { continuation.resume(null) }
            } catch (e: Exception) { continuation.resume(null) }
        }

    private fun calculateBarcodeRoi(width: Int, height: Int): Rect {
        val larguraCorte = (width * LARGURA_CORTE_PERCENTUAL) / 100
        val alturaCorte = (height * ALTURA_CORTE_PERCENTUAL) / 100
        val posicaoX = (width * MARGEM_CANTO_PERCENTUAL) / 100
        return Rect((width - larguraCorte - posicaoX).coerceAtLeast(0), MARGEM_CANTO_PERCENTUAL, (width - posicaoX).coerceAtMost(width), (alturaCorte + MARGEM_CANTO_PERCENTUAL).coerceAtMost(height))
    }

    // --- IMAGEM ---

    private fun loadBitmapFromUri(activity: Activity, uri: Uri): Bitmap? {
        return try {
            val bitmap = activity.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return null
            val inputForExif = activity.contentResolver.openInputStream(uri)
            if (inputForExif != null) {
                val exif = ExifInterface(inputForExif)
                val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                inputForExif.close()
                return rotateBitmapIfRequired(bitmap, orientation)
            }
            bitmap
        } catch (e: Exception) { null }
    }

    private fun saveImageToCache(context: Context, bitmap: Bitmap, filename: String): String? {
        return try {
            val cachePath = File(context.cacheDir, "scanned_docs").apply { if (!exists()) mkdirs() }
            val file = File(cachePath, "$filename.jpg")
            val stream = FileOutputStream(file)
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
            stream.close()
            Uri.fromFile(file).toString()
        } catch (e: Exception) { null }
    }

    private fun rotateBitmapIfRequired(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun rotateBitmap(source: Bitmap, angle: Float): Bitmap {
        val matrix = Matrix()
        matrix.postRotate(angle)
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    // --- SISTEMA ---

    private fun clearPending() { pendingPromise = null; restoreSystemBars() }

    private fun ensureSystemBarsVisible(activity: ComponentActivity) {
        if (Build.VERSION.SDK_INT < ANDROID_15_API || previousFitsSystemWindows != null) return
        previousFitsSystemWindows = activity.window.decorView.fitsSystemWindows
        WindowCompat.setDecorFitsSystemWindows(activity.window, true)
    }

    private fun restoreSystemBars() {
        val prev = previousFitsSystemWindows ?: return
        hostActivityRef?.get()?.let { WindowCompat.setDecorFitsSystemWindows(it.window, prev) }
        previousFitsSystemWindows = null
        hostActivityRef = null
    }
}
