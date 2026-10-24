package com.tejerfacil.app.ui.symbols

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tejerfacil.app.AppContainer
import com.tejerfacil.app.data.db.DiagramEntity
import com.tejerfacil.app.processing.scaledToMax
import com.tejerfacil.app.processing.toMat
import com.tejerfacil.core.geometry.IntBox
import com.tejerfacil.core.geometry.NormRect
import com.tejerfacil.core.rows.CellGrid
import com.tejerfacil.core.rows.DetectedRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class SymbolTool { COPY, PASTE, ERASE }

/**
 * Edición de símbolos celda por celda sobre la imagen de un diagrama de una
 * copia del patrón. Las líneas de la cuadrícula se conservan: solo se modifica
 * el interior de cada celda.
 */
class SymbolEditorViewModel(private val c: AppContainer, val diagramId: Long) : ViewModel() {

    private class Edit(val rect: Rect, val before: Bitmap, val after: Bitmap)

    private var bitmap: Bitmap? = null
    private var grid: CellGrid? = null
    private val undoStack = ArrayDeque<Edit>()
    private val redoStack = ArrayDeque<Edit>()

    private val _diagram = MutableStateFlow<DiagramEntity?>(null)
    val diagram: StateFlow<DiagramEntity?> = _diagram.asStateFlow()
    private val _image = MutableStateFlow<ImageBitmap?>(null)
    val image: StateFlow<ImageBitmap?> = _image.asStateFlow()
    val tool = MutableStateFlow(SymbolTool.COPY)
    private val _clipboard = MutableStateFlow<Bitmap?>(null)
    val clipboard: StateFlow<Bitmap?> = _clipboard.asStateFlow()
    private val _mark = MutableStateFlow<NormRect?>(null)
    val mark: StateFlow<NormRect?> = _mark.asStateFlow()
    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()
    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()
    private val _dirty = MutableStateFlow(false)
    val dirty: StateFlow<Boolean> = _dirty.asStateFlow()
    private val _busy = MutableStateFlow<String?>("Preparando el diagrama…")
    val busy: StateFlow<String?> = _busy.asStateFlow()
    private val _message = MutableStateFlow("Tocá una celda para copiar su símbolo.")
    val message: StateFlow<String> = _message.asStateFlow()
    val palette = c.palette.symbols

    init {
        viewModelScope.launch {
            try {
                c.palette.refresh()
                val d = c.db.diagrams().get(diagramId) ?: error("Diagrama inexistente")
                _diagram.value = d
                val rows = c.db.rows().listForDiagram(diagramId)
                withContext(Dispatchers.Default) {
                    val opts = BitmapFactory.Options().apply { inMutable = true; inPreferredConfig = Bitmap.Config.ARGB_8888 }
                    val bmp = BitmapFactory.decodeFile(d.imagePath, opts) ?: error("No se pudo leer la imagen")
                    val w = bmp.width.toDouble(); val h = bmp.height.toDouble()
                    val mat = bmp.toMat()
                    grid = CellGrid.build(mat, rows.map { DetectedRow(it.top * h, it.bottom * h, it.left * w, it.right * w) })
                    mat.release()
                    bitmap = bmp
                }
                publish(changed = false)
                _busy.value = null
            } catch (t: Throwable) {
                _busy.value = "Error: ${t.message ?: t.javaClass.simpleName}"
            }
        }
    }

    private fun publish(changed: Boolean = true) {
        bitmap?.let { _image.value = it.asImageBitmap() }
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = redoStack.isNotEmpty()
        if (changed) _dirty.value = true
    }

    fun setTool(t: SymbolTool) {
        tool.value = t
        _message.value = when (t) {
            SymbolTool.COPY -> "Tocá una celda para copiar su símbolo."
            SymbolTool.PASTE -> if (_clipboard.value == null) "Primero copiá un símbolo o elegí uno de la paleta." else "Tocá las celdas donde pegar el símbolo."
            SymbolTool.ERASE -> "Tocá las celdas que querés dejar en blanco."
        }
    }

    /** Toque en la imagen (coordenadas normalizadas). */
    fun tap(nx: Double, ny: Double) {
        val bmp = bitmap ?: return
        val g = grid ?: return
        val cell = g.cellAt(nx * bmp.width, ny * bmp.height)
        if (cell == null) {
            _message.value = "Tocá dentro de una celda del gráfico."
            return
        }
        val inner = CellGrid.interior(cell).clip(bmp.width, bmp.height)
        if (inner.width < 2 || inner.height < 2) return
        _mark.value = cell.toNorm(bmp.width, bmp.height)
        val rect = Rect(inner.left, inner.top, inner.right, inner.bottom)
        when (tool.value) {
            SymbolTool.COPY -> {
                _clipboard.value = Bitmap.createBitmap(bmp, rect.left, rect.top, rect.width(), rect.height())
                tool.value = SymbolTool.PASTE
                _message.value = "Símbolo copiado. Tocá las celdas donde pegarlo."
            }
            SymbolTool.PASTE -> {
                val clip = _clipboard.value
                if (clip == null) {
                    _message.value = "Primero copiá un símbolo o elegí uno de la paleta."
                    return
                }
                apply(rect) { canvas ->
                    canvas.drawRect(rect, whitePaint)
                    canvas.drawBitmap(clip, null, rect, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
                }
            }
            SymbolTool.ERASE -> apply(rect) { canvas -> canvas.drawRect(rect, whitePaint) }
        }
    }

    private val whitePaint = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL }

    private fun apply(rect: Rect, draw: (Canvas) -> Unit) {
        val bmp = bitmap ?: return
        val before = Bitmap.createBitmap(bmp, rect.left, rect.top, rect.width(), rect.height())
        draw(Canvas(bmp))
        val after = Bitmap.createBitmap(bmp, rect.left, rect.top, rect.width(), rect.height())
        undoStack.addLast(Edit(rect, before, after))
        if (undoStack.size > 200) undoStack.removeFirst()
        redoStack.clear()
        publish()
    }

    fun undo() {
        val e = undoStack.removeLastOrNull() ?: return
        bitmap?.let { Canvas(it).drawBitmap(e.before, e.rect.left.toFloat(), e.rect.top.toFloat(), null) }
        redoStack.addLast(e)
        _mark.value = null
        publish()
    }

    fun redo() {
        val e = redoStack.removeLastOrNull() ?: return
        bitmap?.let { Canvas(it).drawBitmap(e.after, e.rect.left.toFloat(), e.rect.top.toFloat(), null) }
        undoStack.addLast(e)
        publish()
    }

    fun saveClipboardToPalette() {
        val clip = _clipboard.value ?: return
        viewModelScope.launch {
            c.palette.save(clip)
            _message.value = "Símbolo guardado en la paleta."
        }
    }

    fun useSymbol(f: File) {
        viewModelScope.launch {
            val bmp = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(f.absolutePath) } ?: return@launch
            _clipboard.value = bmp
            setTool(SymbolTool.PASTE)
        }
    }

    fun deleteSymbol(f: File) {
        viewModelScope.launch { c.palette.delete(f) }
    }

    fun clearMessage() { _busy.value = null }

    /** Guarda la imagen editada del diagrama (las filas no cambian). */
    fun save(onDone: () -> Unit) {
        val bmp = bitmap ?: return
        val d = _diagram.value ?: return
        viewModelScope.launch {
            _busy.value = "Guardando…"
            try {
                val updated = withContext(Dispatchers.IO) {
                    val version = System.currentTimeMillis()
                    val img = c.files.diagramImage(d.patternId, d.id, version)
                    val thumb = c.files.diagramThumb(d.patternId, d.id, version)
                    c.files.savePng(bmp, img)
                    val t = bmp.scaledToMax(480)
                    c.files.saveJpeg(t, thumb)
                    if (t !== bmp) t.recycle()
                    c.files.deleteQuietly(d.imagePath)
                    c.files.deleteQuietly(d.thumbPath)
                    d.copy(imagePath = img.absolutePath, thumbPath = thumb.absolutePath, edited = true)
                }
                c.db.diagrams().update(updated)
                _diagram.value = updated
                _dirty.value = false
                _busy.value = null
                onDone()
            } catch (t: Throwable) {
                _busy.value = "Error: ${t.message ?: t.javaClass.simpleName}"
            }
        }
    }

    @Suppress("unused")
    private fun IntBox.asRect() = Rect(left, top, right, bottom)
}
