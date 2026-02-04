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
import kotlinx.coroutines.Dispatchers
import java.io.InputStream
import java.lang.ref.WeakReference
import androidx.exifinterface.media.ExifInterface
import android.graphics.Matrix
import java.io.File
import java.io.FileOutputStream
import android.content.Context


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
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_BASE)

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
                    activity.lifecycleScope.launch(Dispatchers.IO) { 
                        var index = 0
                        for (page in pages) {
                            val originalUri = page.imageUri ?: continue
                            
                            // 1. Carrega o bitmap base
                            var bitmap = loadBitmapFromUri(activity, originalUri) 
                            var barcodeValue: String? = null
                            var finalUriString = originalUri.toString() 

                            if (bitmap != null) {
                                // --- TENTATIVA 1: Original ---
                                var roi = calculateBarcodeRoi(bitmap.width, bitmap.height)
                                barcodeValue = decodeBarcodeWithMLKit(bitmap, roi)

                                // --- TENTATIVA 2: Gira 90 graus (Horário) ---
                                if (barcodeValue == null) {
                                    val rotated90 = rotateBitmap(bitmap, 90f)
                                    val roi90 = calculateBarcodeRoi(rotated90.width, rotated90.height)
                                    val result90 = decodeBarcodeWithMLKit(rotated90, roi90)
                                    
                                    if (result90 != null) {
                                        barcodeValue = result90
                                        bitmap = rotated90 // Atualiza o bitmap oficial
                                    }
                                }

                                // --- TENTATIVA 3: Gira -90 graus (Anti-horário) ---
                                // Exatamente como no iOS: .rotate(radians: -.pi/2)
                                if (barcodeValue == null) {
                                    val rotatedMinus90 = rotateBitmap(bitmap, -90f) 
                                    val roiMinus90 = calculateBarcodeRoi(rotatedMinus90.width, rotatedMinus90.height)
                                    val resultMinus90 = decodeBarcodeWithMLKit(rotatedMinus90, roiMinus90)
                                    
                                    if (resultMinus90 != null) {
                                        barcodeValue = resultMinus90
                                        bitmap = rotatedMinus90 // Atualiza o bitmap oficial
                                    }
                                }
                                
                                // --- TENTATIVA 4: Gira 180 graus (Ponta Cabeça) ---
                                if (barcodeValue == null) {
                                    val rotated180 = rotateBitmap(bitmap, 180f)
                                    val roi180 = calculateBarcodeRoi(rotated180.width, rotated180.height)
                                    val result180 = decodeBarcodeWithMLKit(rotated180, roi180)
                                    
                                    if (result180 != null) {
                                        barcodeValue = result180
                                        bitmap = rotated180 // Atualiza o bitmap oficial
                                    }
                                }
                                
                                // --- CORREÇÃO FINAL DE FORMATO (Retaguarda) ---
                                // Se não achou nada, mas a imagem final ainda está "deitada" (Landscape),
                                // forçamos 90 graus para o Backend receber em pé (padrão A4).
                                if (barcodeValue == null && bitmap.width > bitmap.height) {
                                     bitmap = rotateBitmap(bitmap, 90f)
                                }

                                // 3. SALVAMENTO: Salva o bitmap vencedor
                                val timestamp = System.currentTimeMillis()
                                val newPath = saveImageToCache(activity, bitmap, "scan_${timestamp}_$index")
                                
                                if (newPath != null) {
                                    finalUriString = newPath
                                }
                            }

                            val resultObject = WritableNativeMap()
                            resultObject.putString("uri", finalUriString) 
                            resultObject.putString("barcode", barcodeValue)
                            resultObject.putBoolean("success", barcodeValue != null)
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

    // Método auxiliar (Certifique-se de ter este método na classe)
    private fun rotateBitmap(source: Bitmap, angle: Float): Bitmap {
        val matrix = android.graphics.Matrix()
        matrix.postRotate(angle)
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    // --- MÉTODOS DE PROCESSAMENTO DE IMAGEM (Vindo do seu original) ---

    private fun loadBitmapFromUri(activity: Activity, uri: Uri): Bitmap? {
        return try {
            // 1. Carrega o Bitmap original (pode vir deitado)
            val bitmap = activity.contentResolver.openInputStream(uri)?.use { 
                BitmapFactory.decodeStream(it) 
            } ?: return null

            // 2. Abre um segundo stream EXCLUSIVO para ler os metadados (Exif)
            // Precisamos abrir de novo porque o stream anterior foi consumido pelo decodeStream
            val inputForExif = activity.contentResolver.openInputStream(uri)
            if (inputForExif != null) {
                val exif = ExifInterface(inputForExif)
                val orientation = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
                inputForExif.close()

                // 3. Rotaciona se necessário
                return rotateBitmapIfRequired(bitmap, orientation)
            }

            return bitmap
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // Método para salvar o Bitmap da memória (já rotacionado) para um arquivo físico
    private fun saveImageToCache(context: Context, bitmap: Bitmap, filename: String): String? {
        return try {
            val cachePath = File(context.cacheDir, "scanned_docs")
            if (!cachePath.exists()) cachePath.mkdirs()

            // Cria um arquivo novo (ex: "scan_123123.jpg")
            val file = File(cachePath, "$filename.jpg")
            val stream = FileOutputStream(file)
            
            // Comprime para JPEG (Hard Rotation acontece aqui, pois gravamos os pixels como estão na memória)
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream) // 90% de qualidade
            stream.close()

            // Retorna o caminho no formato URI file://
            Uri.fromFile(file).toString()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun rotateBitmapIfRequired(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            else -> return bitmap // Nenhuma rotação necessária
        }

        // Cria um novo bitmap rotacionado. 
        // O Garbage Collector cuidará do bitmap antigo, ou você pode dar recycle() explicitamente se memória for crítica.
        return Bitmap.createBitmap(
            bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
        )
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