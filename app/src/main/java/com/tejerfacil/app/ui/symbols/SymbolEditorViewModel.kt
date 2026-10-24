package com.tejerfacil.app.ui.symbols

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tejerfacil.app.AppContainer
import com.tejerfacil.app.data.db.DiagramEntity
import com.tejerfacil.app.processing.scaledToMax
import com.tejerfacil.app.processing.toMat
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
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

enum class SymbolTool { SELECT, COPY, PASTE, ERASE }

/**
 * Bloque copiado: celdas con su posición relativa al ancla (celda superior
 * izquierda): filas de diferencia y desplazamiento horizontal en anchos de celda.
 */
class SymbolBlock(val cells: List<Cell>) {
    class Cell(val dRow: Int, val dx: Double, val bitmap: Bitmap)
    val size: Int get() = cells.size

    /** Imagen de vista previa del bloque. */
    fun preview(cellPx: Int = 28): Bitmap {
        val minDx = cells.minOf { it.dx }
        val cols = ceil(cells.maxOf { it.dx } - minDx + 1).toInt().coerceAtLeast(1)
        val rows = cells.maxOf { it.dRow } - cells.minOf { it.dRow } + 1
        val minRow = cells.minOf { it.dRow }
        val out = Bitmap.createBitmap(cols * cellPx, rows * cellPx, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.WHITE)
        val p = Paint(Paint.FILTER_BITMAP_FLAG)
        val grid = Paint().apply { color = Color.LTGRAY; style = Paint.Style.STROKE }
        for (cell in cells) {
            val x = ((cell.dx - minDx) * cellPx).toFloat()
            val y = ((cell.dRow - minRow) * cellPx).toFloat()
            val r = RectF(x + 1, y + 1, x + cellPx - 1, y + cellPx - 1)
            c.drawBitmap(cell.bitmap, null, r, p)
            c.drawRect(x, y, x + cellPx, y + cellPx, grid)
        }
        return out
    }
}

/**
 * Edición de símbolos sobre la imagen de un diagrama de una copia del patrón.
 * Solo se modifica el interior de cada celda (las líneas se conservan).
 */
class SymbolEditorViewModel(private val c: AppContainer, val diagramId: Long) : ViewModel() {

    private class Patch(val rect: Rect, val before: Bitmap, val after: Bitmap)
    private class Edit(val patches: List<Patch>)

    private var bitmap: Bitmap? = null
    private var grid: CellGrid? = null
    private val undoStack = ArrayDeque<Edit>()
    private val redoStack = ArrayDeque<Edit>()
    private val whitePaint = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    private val _diagram = MutableStateFlow<DiagramEntity?>(null)
    val diagram: StateFlow<DiagramEntity?> = _diagram.asStateFlow()
    private val _image = MutableStateFlow<ImageBitmap?>(null)
    val image: StateFlow<ImageBitmap?> = _image.asStateFlow()
    private val _tool = MutableStateFlow(SymbolTool.SELECT)
    val tool: StateFlow<SymbolTool> = _tool.asStateFlow()
    private val _clipboard = MutableStateFlow<SymbolBlock?>(null)
    val clipboard: StateFlow<SymbolBlock?> = _clipboard.asStateFlow()
    private val _clipPreview = MutableStateFlow<Bitmap?>(null)
    val clipPreview: StateFlow<Bitmap?> = _clipPreview.asStateFlow()
    /** Celdas seleccionadas (clave -> celda). */
    private val selection = LinkedHashMap<Long, CellGrid.CellRef>()
    private val _selectionMarks = MutableStateFlow<List<NormRect>>(emptyList())
    val selectionMarks: StateFlow<List<NormRect>> = _selectionMarks.asStateFlow()
    private val _selectionCount = MutableStateFlow(0)
    val selectionCount: StateFlow<Int> = _selectionCount.asStateFlow()
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
    private val _message = MutableStateFlow(hint(SymbolTool.SELECT))
    val message: StateFlow<String> = _message.asStateFlow()
    val palette = c.palette.symbols

    private var lastDrag: Pair<Double, Double>? = null

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

    private fun hint(t: SymbolTool) = when (t) {
        SymbolTool.SELECT -> "Arrastrá el dedo sobre las celdas para seleccionarlas (dos dedos: zoom y mover)."
        SymbolTool.COPY -> "Tocá una celda para copiar su símbolo."
        SymbolTool.PASTE -> if (_clipboard.value == null) "Primero copiá un símbolo o elegí uno de la paleta."
        else if ((_clipboard.value?.size ?: 0) > 1) "Tocá la celda donde va la esquina superior izquierda del bloque."
        else "Tocá las celdas donde pegar el símbolo."
        SymbolTool.ERASE -> "Tocá las celdas que querés dejar en blanco."
    }

    private fun publish(changed: Boolean = true) {
        bitmap?.let { _image.value = it.asImageBitmap() }
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = redoStack.isNotEmpty()
        if (changed) _dirty.value = true
    }

    fun setTool(t: SymbolTool) {
        _tool.value = t
        _message.value = hint(t)
    }

    private fun interiorRect(cell: CellGrid.CellRef, bmp: Bitmap): Rect? {
        val inner = CellGrid.interior(cell.box).clip(bmp.width, bmp.height)
        if (inner.width < 2 || inner.height < 2) return null
        return Rect(inner.left, inner.top, inner.right, inner.bottom)
    }

    // ---------- Selección ----------

    private fun publishSelection() {
        val bmp = bitmap ?: return
        _selectionMarks.value = selection.values.map { it.box.toNorm(bmp.width, bmp.height) }
        _selectionCount.value = selection.size
    }

    private fun selectAt(nx: Double, ny: Double, toggle: Boolean) {
        val bmp = bitmap ?: return
        val cell = grid?.locate(nx * bmp.width, ny * bmp.height) ?: return
        if (toggle && selection.containsKey(cell.key)) selection.remove(cell.key) else selection[cell.key] = cell
    }

    /** Arrastre en modo selección: se marcan todas las celdas por donde pasa el dedo. */
    fun dragSelect(nx: Double, ny: Double) {
        if (_tool.value != SymbolTool.SELECT) return
        val bmp = bitmap ?: return
        val g = grid ?: return
        val prev = lastDrag
        if (prev == null) {
            selectAt(nx, ny, toggle = false)
        } else {
            // Interpolar para no saltear celdas en movimientos rápidos.
            val dxPx = (nx - prev.first) * bmp.width
            val dyPx = (ny - prev.second) * bmp.height
            val step = max(2.0, g.cellWidth * 0.3)
            val n = max(1, (hypot(dxPx, dyPx) / step).roundToInt())
            for (i in 1..n) {
                val t = i.toDouble() / n
                selectAt(prev.first + (nx - prev.first) * t, prev.second + (ny - prev.second) * t, toggle = false)
            }
        }
        lastDrag = nx to ny
        publishSelection()
    }

    fun dragEnd() {
        lastDrag = null
        val n = selection.size
        if (n > 0) _message.value = "$n celdas seleccionadas. Podés borrarlas o copiarlas."
    }

    fun clearSelection() {
        selection.clear()
        publishSelection()
        _message.value = hint(_tool.value)
    }

    fun eraseSelection() {
        val bmp = bitmap ?: return
        val rects = selection.values.mapNotNull { interiorRect(it, bmp) }
        if (rects.isEmpty()) return
        apply(rects) { canvas, r, _ -> canvas.drawRect(r, whitePaint) }
        val n = rects.size
        clearSelection()
        _message.value = "$n celdas borradas."
    }

    fun copySelection() {
        val bmp = bitmap ?: return
        val g = grid ?: return
        if (selection.isEmpty()) return
        val cells = selection.values.toList()
        // Ancla: la celda de más arriba y, dentro de esa fila, la de más a la izquierda.
        val anchor = cells.minWith(compareBy({ it.row }, { it.box.left }))
        val block = cells.mapNotNull { cell ->
            val r = interiorRect(cell, bmp) ?: return@mapNotNull null
            SymbolBlock.Cell(
                dRow = cell.row - anchor.row,
                dx = (cell.box.centerX - anchor.box.centerX) / g.cellWidth,
                bitmap = Bitmap.createBitmap(bmp, r.left, r.top, r.width(), r.height()),
            )
        }
        setClipboard(SymbolBlock(block))
        clearSelection()
        setTool(SymbolTool.PASTE)
    }

    private fun setClipboard(block: SymbolBlock?) {
        _clipboard.value = block
        _clipPreview.value = block?.let { if (it.size == 1) it.cells[0].bitmap else it.preview() }
    }

    // ---------- Toques ----------

    /** Toque en la imagen (coordenadas normalizadas). */
    fun tap(nx: Double, ny: Double) {
        val bmp = bitmap ?: return
        val g = grid ?: return
        val cell = g.locate(nx * bmp.width, ny * bmp.height)
        if (cell == null) {
            _message.value = "Tocá dentro de una celda del gráfico."
            return
        }
        when (_tool.value) {
            SymbolTool.SELECT -> {
                selectAt(nx, ny, toggle = true)
                publishSelection()
                _message.value = if (selection.isEmpty()) hint(SymbolTool.SELECT) else "${selection.size} celdas seleccionadas."
            }
            SymbolTool.COPY -> {
                val r = interiorRect(cell, bmp) ?: return
                _mark.value = cell.box.toNorm(bmp.width, bmp.height)
                setClipboard(SymbolBlock(listOf(SymbolBlock.Cell(0, 0.0, Bitmap.createBitmap(bmp, r.left, r.top, r.width(), r.height())))))
                _tool.value = SymbolTool.PASTE
                _message.value = "Símbolo copiado. Tocá las celdas donde pegarlo."
            }
            SymbolTool.PASTE -> {
                val block = _clipboard.value
                if (block == null) {
                    _message.value = hint(SymbolTool.PASTE)
                    return
                }
                paste(block, cell)
            }
            SymbolTool.ERASE -> {
                val r = interiorRect(cell, bmp) ?: return
                _mark.value = cell.box.toNorm(bmp.width, bmp.height)
                apply(listOf(r)) { canvas, rr, _ -> canvas.drawRect(rr, whitePaint) }
            }
        }
    }

    private fun paste(block: SymbolBlock, anchor: CellGrid.CellRef) {
        val bmp = bitmap ?: return
        val g = grid ?: return
        val targets = ArrayList<Pair<Rect, Bitmap>>()
        var outside = 0
        for (cell in block.cells) {
            val target = if (cell.dRow == 0 && cell.dx == 0.0) anchor else g.offset(anchor, cell.dRow, cell.dx)
            val r = target?.let { interiorRect(it, bmp) }
            if (r == null) outside++ else targets.add(r to cell.bitmap)
        }
        if (targets.isEmpty()) {
            _message.value = "El bloque no entra en esa posición."
            return
        }
        _mark.value = anchor.box.toNorm(bmp.width, bmp.height)
        apply(targets.map { it.first }) { canvas, r, i ->
            canvas.drawRect(r, whitePaint)
            canvas.drawBitmap(targets[i].second, null, r, bitmapPaint)
        }
        _message.value = if (outside > 0) "Pegado. $outside celdas quedaron fuera del gráfico." else hint(SymbolTool.PASTE)
    }

    /** Aplica un cambio a varias celdas como una sola operación (un solo "deshacer"). */
    private fun apply(rects: List<Rect>, draw: (Canvas, Rect, Int) -> Unit) {
        val bmp = bitmap ?: return
        val canvas = Canvas(bmp)
        val patches = rects.mapIndexed { i, r ->
            val before = Bitmap.createBitmap(bmp, r.left, r.top, r.width(), r.height())
            draw(canvas, r, i)
            Patch(r, before, Bitmap.createBitmap(bmp, r.left, r.top, r.width(), r.height()))
        }
        undoStack.addLast(Edit(patches))
        if (undoStack.size > 100) undoStack.removeFirst()
        redoStack.clear()
        publish()
    }

    fun undo() {
        val e = undoStack.removeLastOrNull() ?: return
        bitmap?.let { b -> val cv = Canvas(b); e.patches.asReversed().forEach { cv.drawBitmap(it.before, it.rect.left.toFloat(), it.rect.top.toFloat(), null) } }
        redoStack.addLast(e)
        _mark.value = null
        publish()
    }

    fun redo() {
        val e = redoStack.removeLastOrNull() ?: return
        bitmap?.let { b -> val cv = Canvas(b); e.patches.forEach { cv.drawBitmap(it.after, it.rect.left.toFloat(), it.rect.top.toFloat(), null) } }
        undoStack.addLast(e)
        publish()
    }

    // ---------- Paleta ----------

    fun saveClipboardToPalette() {
        val block = _clipboard.value ?: return
        if (block.size != 1) {
            _message.value = "La paleta guarda símbolos de una sola celda."
            return
        }
        viewModelScope.launch {
            c.palette.save(block.cells[0].bitmap)
            _message.value = "Símbolo guardado en la paleta."
        }
    }

    fun useSymbol(f: File) {
        viewModelScope.launch {
            val bmp = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(f.absolutePath) } ?: return@launch
            setClipboard(SymbolBlock(listOf(SymbolBlock.Cell(0, 0.0, bmp))))
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
}
