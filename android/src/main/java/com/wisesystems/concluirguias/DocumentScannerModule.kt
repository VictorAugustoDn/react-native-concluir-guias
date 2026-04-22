package com.wisesystems.concluirguias

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.util.Log
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

// IMPORTS DE TEXTO (ML KIT) - ESSENCIAIS
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

// IMPORTS DE COROUTINES - ESSENCIAIS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.coroutines.suspendCoroutine
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.lang.ref.WeakReference
import kotlin.coroutines.resume

/**
 * Estrutura de dados para organizar o retorno do OCR.
 */
data class ExtractedData(
    val cpf: String? = null,
    val rg: String? = null,
    val dataNascimento: String? = null,
    val dataEmissao: String? = null,
    val nome: String? = null,
    val sexo: String? = null,
    val rawText: String? = null
)

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
    
    // Variável de instância para persistir o modo durante a sessão de scan
    private var currentMode: String = "barcode"

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
        
        // Fix Android 15
        ensureSystemBarsVisible(activity)

        initLauncher(activity)
        
        // Captura o modo (barcode ou ocr)
        currentMode = if (options.hasKey("mode")) options.getString("mode") ?: "barcode" else "barcode"

        val builder = GmsDocumentScannerOptions.Builder()
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_BASE)

        if (options.hasKey("maxNumDocuments")) {
            builder.setPageLimit(options.getInt("maxNumDocuments"))
        }
        
        val scannerClient = GmsDocumentScanning.getClient(builder.build())
        this.scanner = scannerClient
        
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
                            
                            // 1. Carrega o Bitmap (respeitando EXIF)
                            var bitmap = loadBitmapFromUri(activity, originalUri)
                            var finalUriString = originalUri.toString()
                            
                            var barcodeValue: String? = null
                            var ocrResult: ExtractedData? = null
                            var successInLoop = false

                            if (bitmap != null) {
                                // TENTATIVAS DE ROTAÇÃO (0, 90, -90, 180) - A base do Concluir Guias
                                val angles = listOf(0f, 90f, -90f, 180f)
                                
                                for (angle in angles) {
                                    // bitmap!! é seguro aqui pois já checamos nulo
                                    val currentBitmap = if (angle == 0f) bitmap!! else rotateBitmap(bitmap!!, angle)
                                    
                                    if (currentMode == "ocr") {
                                        // --- LÓGICA OCR ---
                                        val data = processOcr(currentBitmap)
                                        if (data.cpf != null || data.nome != null || data.rg != null) {
                                            ocrResult = data
                                            bitmap = currentBitmap
                                            successInLoop = true
                                            break
                                        }
                                        ocrResult = data // Fallback
                                    } else {
                                        // --- LÓGICA BARCODE ---
                                        val roi = calculateBarcodeRoi(currentBitmap.width, currentBitmap.height)
                                        barcodeValue = decodeBarcodeWithMLKit(currentBitmap, roi)
                                        if (barcodeValue != null) {
                                            bitmap = currentBitmap
                                            successInLoop = true
                                            break
                                        }
                                    }
                                }

                                // Correção se nada foi achado mas está Landscape
                                if (!successInLoop && bitmap != null && bitmap!!.width > bitmap!!.height) {
                                    bitmap = rotateBitmap(bitmap!!, 90f)
                                }

                                // Salva imagem final rotacionada
                                if (bitmap != null) {
                                    val timestamp = System.currentTimeMillis()
                                    val newPath = saveImageToCache(activity, bitmap!!, "scan_${currentMode}_${timestamp}_$index")
                                    if (newPath != null) finalUriString = newPath
                                }
                            }

                            val resultObject = WritableNativeMap()
                            resultObject.putString("uri", finalUriString)
                            resultObject.putBoolean("success", successInLoop)
                            
                            if (currentMode == "ocr") {
                                val ocrMap = WritableNativeMap()
                                ocrResult?.let {
                                    ocrMap.putString("rawText", it.rawText ?: "")
                                    ocrMap.putString("cpf", it.cpf)
                                    ocrMap.putString("rg", it.rg)
                                    ocrMap.putString("dataNascimento", it.dataNascimento)
                                    ocrMap.putString("dataEmissao", it.dataEmissao)
                                    ocrMap.putString("nome", it.nome)
                                    ocrMap.putString("sexo", it.sexo)
                                }
                                resultObject.putMap("ocrData", ocrMap)
                            } else {
                                resultObject.putString("barcode", barcodeValue)
                            }
                            
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

    // --- MÉTODOS DE OCR ---

    private suspend fun processOcr(bitmap: Bitmap): ExtractedData =
        suspendCoroutine<ExtractedData> { continuation ->
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val image = InputImage.fromBitmap(bitmap, 0)

            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    continuation.resume(extractStructuredData(visionText))
                }
                .addOnFailureListener {
                    continuation.resume(ExtractedData(rawText = ""))
                }
        }

    private fun extractStructuredData(visionText: Text): ExtractedData {
        val blocks = visionText.textBlocks
        val allLines = blocks.flatMap { it.lines }.map { it.text.trim() }
        val fullText = visionText.text
        
        var cpf: String? = null
        var rg: String? = null
        var nome: String? = null
        var sexo: String? = null
        val allDates = mutableListOf<String>()
        
        // Regex mais rígido para CPF (prioriza o que tem formatação)
        val cpfRegex = Regex("""\d{3}[\.\s]?\d{3}[\.\s]?\d{3}[-\s]?\d{2}""")
        val rgRegex = Regex("""\d{1,2}\.?\d{3}\.?\d{3}-?[0-9X]{1,2}""", RegexOption.IGNORE_CASE)
        val dateRegex = Regex("""\d{2}/\d{2}/\d{4}""")

        // 1. BUSCA POR ÂNCORAS (Labels)
        for (i in allLines.indices) {
            val line = allLines[i].uppercase()

            // --- LÓGICA PARA CPF ---
            // Se achar a palavra "CPF", olha para a linha atual e as próximas 2
            if (line.contains("CPF") && cpf == null) {
                for (offset in 0..2) {
                    if (i + offset < allLines.size) {
                        val candidate = allLines[i + offset]
                        val match = cpfRegex.find(candidate)?.value
                        if (match != null) {
                            // Se o match tem pontos ou traço, é 100% o CPF e não o Registro
                            if (match.contains(".") || match.contains("-")) {
                                cpf = match
                                break
                            }
                            // Se for só número, guardamos mas continuamos tentando achar um formatado
                            if (cpf == null) cpf = match
                        }
                    }
                }
            }

            // --- LÓGICA PARA RG (DOC. IDENTIDADE) ---
            if ((line.contains("IDENTIDADE") || line.contains("RG")) && rg == null) {
                for (offset in 0..2) {
                    if (i + offset < allLines.size) {
                        // Remove espaços para o regex do RG funcionar com "SESP PR" grudado
                        val candidate = allLines[i + offset].replace(" ", "")
                        val match = rgRegex.find(candidate)?.value
                        if (match != null) {
                            rg = match
                            break
                        }
                    }
                }
            }

            // --- LÓGICA PARA NOME ---
            if (line.contains("NOME") && !line.contains("PAI") && !line.contains("MÃE") && nome == null) {
                // Pega tudo que vier depois da palavra "NOME" na mesma linha (Típico de RG)
                val textAfterLabel = line.substringAfter("NOME").replace(":", "").replace(".", "").trim()

                if (textAfterLabel.length > 5) {
                    // É um RG! O nome estava na mesma linha.
                    nome = textAfterLabel
                } else if (i + 1 < allLines.size) {
                    // É uma CNH! O nome está na linha de baixo.
                    val nextLine = allLines[i + 1]
                    val ignoreList = listOf("REPÚBLICA", "FEDERATIVA", "DOC", "IDENTIDADE")
                    if (nextLine.length > 5 && !ignoreList.any { nextLine.uppercase().contains(it) }) {
                        nome = nextLine
                    }
                }
            }

            // --- LÓGICA PARA SEXO ---
            if (sexo == null) {
                if (line.contains("MASCULINO")) sexo = "M"
                else if (line.contains("FEMININO")) sexo = "F"
            }

            // COLETA DE DATAS (Nascimento e Emissão)
            val datesInLine = dateRegex.findAll(allLines[i]).map { it.value }.toList()
            allDates.addAll(datesInLine)
        }

        // 2. FALLBACK GLOBAL (Caso as âncoras falhem)
        if (cpf == null) cpf = cpfRegex.find(fullText)?.value
        if (rg == null) rg = rgRegex.find(fullText.replace(" ", ""))?.value

        // Ordenação de datas: Nascimento (mais antiga) vs Emissão (mais recente)
        var birthDate: String? = null
        var issueDate: String? = null
        if (allDates.isNotEmpty()) {
            val sorted = allDates.distinct().sortedBy { 
                val parts = it.split("/")
                if (parts.size == 3) "${parts[2]}${parts[1]}${parts[0]}" else "99999999" 
            }
            birthDate = sorted.firstOrNull()
            if (sorted.size > 1) issueDate = sorted.lastOrNull()
        }
        
        return ExtractedData(cpf, rg, birthDate, issueDate, nome, sexo, fullText)
    }

    // --- MÉTODOS DE BARCODE ---

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

    // --- MÉTODOS DE IMAGEM ---

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

    private fun calculateBarcodeRoi(width: Int, height: Int): Rect {
        val larguraCorte = (width * LARGURA_CORTE_PERCENTUAL) / 100
        val alturaCorte = (height * ALTURA_CORTE_PERCENTUAL) / 100
        val posicaoX = (width * MARGEM_CANTO_PERCENTUAL) / 100
        return Rect((width - larguraCorte - posicaoX).coerceAtLeast(0), MARGEM_CANTO_PERCENTUAL, (width - posicaoX).coerceAtMost(width), (alturaCorte + MARGEM_CANTO_PERCENTUAL).coerceAtMost(height))
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
