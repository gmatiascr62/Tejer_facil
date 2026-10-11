package com.tejerfacil.app.processing

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.tejerfacil.app.data.FileStore
import com.tejerfacil.app.data.PatternRepository
import com.tejerfacil.app.data.db.AppDatabase
import com.tejerfacil.app.data.db.DiagramEntity
import com.tejerfacil.app.data.db.PatternEntity
import com.tejerfacil.app.pdf.PdfPageRenderer
import com.tejerfacil.app.pdf.PdfTextExtractor
import com.tejerfacil.core.detect.OrientationResult
import com.tejerfacil.core.geometry.NormRect
import com.tejerfacil.core.geometry.Rotation
import com.tejerfacil.core.text.GlyphMath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class ImportState(
    val fileName: String,
    val patternId: Long? = null,
    val stage: String,
    val page: Int = 0,
    val pageCount: Int = 0,
    val diagramsFound: Int = 0,
    val finished: Boolean = false,
    val error: String? = null,
) {
    val running: Boolean get() = !finished
    val fraction: Float get() = if (pageCount == 0) 0f else page.toFloat() / pageCount
}

/**
 * Importación en segundo plano (no depende de la pantalla visible):
 * copia el PDF sin modificarlo, analiza cada página y guarda los diagramas.
 */
class ImportManager(
    private val context: Context,
    private val db: AppDatabase,
    private val files: FileStore,
    private val repo: PatternRepository,
    private val processor: DiagramProcessor,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<ImportState?>(null)
    val state: StateFlow<ImportState?> = _state.asStateFlow()
    private var job: Job? = null

    fun start(uri: Uri) {
        if (_state.value?.running == true) return
        _state.value = ImportState(fileName = queryName(uri), stage = "Preparando…")
        job = scope.launch(Dispatchers.Default) {
            try {
                runImport(uri)
            } catch (e: CancellationException) {
                _state.value = _state.value?.copy(finished = true, error = "Importación cancelada")
                withContext(NonCancellable) { _state.value?.patternId?.let { repo.deletePattern(it) } }
            } catch (t: Throwable) {
                Log.e("TejerFacil", "Error importando", t)
                val id = _state.value?.patternId
                withContext(NonCancellable) {
                    id?.let { pid ->
                        db.patterns().get(pid)?.let { db.patterns().update(it.copy(status = "ERROR", message = t.message)) }
                    }
                }
                _state.value = _state.value?.copy(finished = true, error = "No se pudo procesar el PDF: ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    fun dismiss() {
        if (_state.value?.finished == true) _state.value = null
    }

    private fun queryName(uri: Uri): String {
        var name: String? = null
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) name = c.getString(0)
            }
        }
        return name ?: uri.lastPathSegment ?: "patron.pdf"
    }

    private suspend fun runImport(uri: Uri) {
        val fileName = _state.value!!.fileName
        _state.value = _state.value?.copy(stage = "Copiando el PDF…")
        val tmp = File(files.tempDir(), "importando_${System.currentTimeMillis()}.pdf")
        withContext(Dispatchers.IO) {
            val input = context.contentResolver.openInputStream(uri) ?: error("No se puede leer el archivo")
            input.use { inp -> tmp.outputStream().use { inp.copyTo(it) } }
        }
        val pageCount = PdfPageRenderer(tmp).use { it.pageCount }
        val patternId = db.patterns().insert(
            PatternEntity(
                name = fileName.removeSuffix(".pdf").removeSuffix(".PDF"),
                fileName = fileName, pdfPath = "", pageCount = pageCount,
                importedAt = System.currentTimeMillis(), status = "PROCESSING",
            )
        )
        _state.value = _state.value?.copy(patternId = patternId, pageCount = pageCount)
        val dest = files.pdfFile(patternId)
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true); tmp.delete()
        }
        db.patterns().get(patternId)?.let { db.patterns().update(it.copy(pdfPath = dest.absolutePath)) }

        var found = 0
        PdfPageRenderer(dest).use { renderer ->
            PdfTextExtractor(dest).use { text ->
                for (page in 0 until pageCount) {
                    currentCoroutineContext().ensureActive()
                    _state.value = _state.value?.copy(stage = "Analizando página ${page + 1} de $pageCount", page = page)
                    try {
                        val glyphs = text.glyphs(page)
                        val analysis = processor.analyzePage(renderer, page, glyphs)
                        val words = GlyphMath.groupWords(glyphs)
                        for ((i, d) in analysis.diagrams.withIndex()) {
                            found++
                            _state.value = _state.value?.copy(
                                stage = "Página ${page + 1}: procesando diagrama ${i + 1} de ${analysis.diagrams.size}",
                                diagramsFound = found,
                            )
                            val crop: NormRect = d.cropNorm(analysis.width, analysis.height)
                            val entity = DiagramEntity(
                                patternId = patternId, pageIndex = page, indexInPage = i,
                                name = "Diagrama $found",
                                cropLeft = crop.left, cropTop = crop.top, cropRight = crop.right, cropBottom = crop.bottom,
                                cellWidthNorm = d.cellWidth / analysis.width,
                                cellHeightNorm = d.cellHeight / analysis.height,
                                rotation = d.rotation.degrees,
                                detectionConfidence = d.confidence,
                                notes = d.notes.joinToString("\n"),
                            )
                            val id = db.diagrams().insert(entity)
                            val initial = OrientationResult(d.rotation, d.rotationConfidence, d.notes.lastOrNull() ?: "", d.rotationSource)
                            processor.build(renderer, entity.copy(id = id), words, initial, null)
                            repo.progressFor(id)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        // Una página problemática no detiene la importación del resto.
                        Log.w("TejerFacil", "Error en la página $page", t)
                    }
                }
            }
        }
        db.patterns().get(patternId)?.let { db.patterns().update(it.copy(status = "READY")) }
        _state.value = _state.value?.copy(stage = "Listo", page = pageCount, finished = true, diagramsFound = found)
    }

    /** Re-procesa un diagrama (tras editar el recorte o la orientación). */
    suspend fun rebuild(diagram: DiagramEntity, forced: Rotation?) = withContext(Dispatchers.Default) {
        val pattern = db.patterns().get(diagram.patternId) ?: return@withContext null
        val file = File(pattern.pdfPath)
        PdfPageRenderer(file).use { r ->
            val words = PdfTextExtractor(file).use { GlyphMath.groupWords(it.glyphs(diagram.pageIndex)) }
            processor.build(r, diagram, words, null, forced)
        }
    }

    fun pageRenderer(patternPdf: String): PdfPageRenderer = PdfPageRenderer(File(patternPdf))
}
