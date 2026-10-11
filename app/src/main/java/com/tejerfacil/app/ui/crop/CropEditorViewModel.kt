package com.tejerfacil.app.ui.crop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tejerfacil.app.AppContainer
import com.tejerfacil.app.data.db.DiagramEntity
import com.tejerfacil.app.data.db.PatternEntity
import com.tejerfacil.app.pdf.PdfPageRenderer
import com.tejerfacil.core.geometry.NormRect
import com.tejerfacil.core.geometry.Rotation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min

/** Recorte en edición. [key] es local; [diagramId] = null si es nuevo. */
data class EditCrop(
    val key: Long,
    val diagramId: Long?,
    val name: String,
    val rect: NormRect,
    val rotation: Int,
    val rotationChanged: Boolean = false,
    val rectChanged: Boolean = false,
)

enum class Handle { MOVE, LEFT, TOP, RIGHT, BOTTOM, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

class CropEditorViewModel(private val c: AppContainer, private val patternId: Long, initialPage: Int) : ViewModel() {
    private var renderer: PdfPageRenderer? = null
    private var pattern: PatternEntity? = null
    private var nextKey = 1L
    private val originals = mutableMapOf<Long, DiagramEntity>()
    private val removed = mutableListOf<DiagramEntity>()

    private val _page = MutableStateFlow(initialPage)
    val page: StateFlow<Int> = _page.asStateFlow()
    private val _pageCount = MutableStateFlow(0)
    val pageCount: StateFlow<Int> = _pageCount.asStateFlow()
    private val _image = MutableStateFlow<ImageBitmap?>(null)
    val image: StateFlow<ImageBitmap?> = _image.asStateFlow()
    private val _crops = MutableStateFlow<List<EditCrop>>(emptyList())
    val crops: StateFlow<List<EditCrop>> = _crops.asStateFlow()
    private val _selected = MutableStateFlow<Set<Long>>(emptySet())
    val selected: StateFlow<Set<Long>> = _selected.asStateFlow()
    private val _dirty = MutableStateFlow(false)
    val dirty: StateFlow<Boolean> = _dirty.asStateFlow()
    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy.asStateFlow()
    val multiSelect = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            val p = c.db.patterns().get(patternId) ?: return@launch
            pattern = p
            renderer = withContext(Dispatchers.IO) { PdfPageRenderer(File(p.pdfPath)) }
            _pageCount.value = renderer?.pageCount ?: 0
            loadPage(_page.value.coerceIn(0, max(0, _pageCount.value - 1)))
        }
    }

    fun loadPage(p: Int) {
        if (_dirty.value) return
        val r = renderer ?: return
        if (p !in 0 until r.pageCount) return
        _page.value = p
        _image.value = null
        viewModelScope.launch {
            _image.value = withContext(Dispatchers.IO) { r.renderPage(p, 1800).asImageBitmap() }
            val list = c.db.diagrams().listForPage(patternId, p)
            originals.clear(); removed.clear()
            list.forEach { originals[it.id] = it }
            _crops.value = list.map {
                EditCrop(nextKey++, it.id, it.name, NormRect(it.cropLeft, it.cropTop, it.cropRight, it.cropBottom), it.rotation)
            }
            _selected.value = emptySet()
            _dirty.value = false
        }
    }

    fun tapSelect(key: Long?) {
        _selected.value = when {
            key == null -> emptySet()
            multiSelect.value -> if (key in _selected.value) _selected.value - key else _selected.value + key
            else -> setOf(key)
        }
    }

    private fun edit(key: Long, f: (EditCrop) -> EditCrop) {
        _crops.value = _crops.value.map { if (it.key == key) f(it) else it }
        _dirty.value = true
    }

    fun drag(key: Long, handle: Handle, dx: Double, dy: Double) = edit(key) { cr ->
        var (l, t, r, b) = listOf(cr.rect.left, cr.rect.top, cr.rect.right, cr.rect.bottom)
        when (handle) {
            Handle.MOVE -> {
                val ndx = dx.coerceIn(-l, 1 - r); val ndy = dy.coerceIn(-t, 1 - b)
                l += ndx; r += ndx; t += ndy; b += ndy
            }
            Handle.LEFT -> l += dx
            Handle.RIGHT -> r += dx
            Handle.TOP -> t += dy
            Handle.BOTTOM -> b += dy
            Handle.TOP_LEFT -> { l += dx; t += dy }
            Handle.TOP_RIGHT -> { r += dx; t += dy }
            Handle.BOTTOM_LEFT -> { l += dx; b += dy }
            Handle.BOTTOM_RIGHT -> { r += dx; b += dy }
        }
        val minSize = 0.02
        l = l.coerceIn(0.0, 1.0 - minSize); t = t.coerceIn(0.0, 1.0 - minSize)
        r = r.coerceIn(l + minSize, 1.0); b = b.coerceIn(t + minSize, 1.0)
        cr.copy(rect = NormRect(l, t, r, b), rectChanged = true)
    }

    fun add() {
        val n = _crops.value.size + 1
        val cr = EditCrop(nextKey++, null, "Diagrama manual $n", NormRect(0.2, 0.3, 0.8, 0.7), 0, rectChanged = true)
        _crops.value = _crops.value + cr
        _selected.value = setOf(cr.key)
        _dirty.value = true
    }

    fun deleteSelected() {
        val sel = _selected.value
        _crops.value.filter { it.key in sel }.forEach { cr -> cr.diagramId?.let { id -> originals[id]?.let(removed::add) } }
        _crops.value = _crops.value.filter { it.key !in sel }
        _selected.value = emptySet()
        _dirty.value = true
    }

    fun rotateSelected() {
        for (k in _selected.value) edit(k) { it.copy(rotation = (it.rotation + 90) % 360, rotationChanged = true) }
    }

    /** Divide el recorte seleccionado en dos (arriba/abajo o izquierda/derecha). */
    fun splitSelected(horizontalCut: Boolean) {
        val key = _selected.value.singleOrNull() ?: return
        val cr = _crops.value.firstOrNull { it.key == key } ?: return
        val r = cr.rect
        val (a, b) = if (horizontalCut) {
            val m = (r.top + r.bottom) / 2
            NormRect(r.left, r.top, r.right, m) to NormRect(r.left, m, r.right, r.bottom)
        } else {
            val m = (r.left + r.right) / 2
            NormRect(r.left, r.top, m, r.bottom) to NormRect(m, r.top, r.right, r.bottom)
        }
        val second = EditCrop(nextKey++, null, cr.name + " (b)", b, cr.rotation, rotationChanged = cr.rotationChanged, rectChanged = true)
        _crops.value = _crops.value.map { if (it.key == key) it.copy(rect = a, rectChanged = true) else it } + second
        _selected.value = setOf(key, second.key)
        _dirty.value = true
    }

    /** Une los recortes seleccionados en uno (rectángulo que los contiene). */
    fun mergeSelected() {
        val sel = _crops.value.filter { it.key in _selected.value }
        if (sel.size < 2) return
        val keep = sel.firstOrNull { it.diagramId != null } ?: sel.first()
        val union = sel.map { it.rect }.reduce { x, y -> x.union(y) }
        sel.filter { it !== keep }.forEach { cr -> cr.diagramId?.let { id -> originals[id]?.let(removed::add) } }
        val others = sel.filter { it !== keep }.map { it.key }.toSet()
        _crops.value = _crops.value.filter { it.key !in others }.map {
            if (it.key == keep.key) it.copy(rect = union, rectChanged = true) else it
        }
        _selected.value = setOf(keep.key)
        multiSelect.value = false
        _dirty.value = true
    }

    fun discard() {
        _dirty.value = false
        loadPage(_page.value)
    }

    fun save(onDone: () -> Unit) {
        val r = renderer ?: return
        val p = pattern ?: return
        viewModelScope.launch {
            try {
                for (d in removed) c.repo.deleteDiagram(d)
                removed.clear()
                val page = _page.value
                val total = _crops.value.size
                for ((i, cr) in _crops.value.sortedWith(compareBy({ it.rect.top }, { it.rect.left })).withIndex()) {
                    val orig = cr.diagramId?.let { originals[it] }
                    if (orig != null && !cr.rectChanged && !cr.rotationChanged && orig.indexInPage == i) continue
                    _busy.value = "Procesando ${i + 1} de $total…"
                    val base = (orig ?: DiagramEntity(
                        patternId = p.id, pageIndex = page, indexInPage = i, name = cr.name,
                        cropLeft = 0.0, cropTop = 0.0, cropRight = 1.0, cropBottom = 1.0, manual = true,
                    )).copy(
                        indexInPage = i,
                        cropLeft = cr.rect.left, cropTop = cr.rect.top, cropRight = cr.rect.right, cropBottom = cr.rect.bottom,
                        manual = true,
                    )
                    val entity = if (orig == null) base.copy(id = c.db.diagrams().insert(base)) else base.also { c.db.diagrams().update(it) }
                    if (orig != null && !cr.rectChanged && !cr.rotationChanged) continue
                    // Rotación: la elegida por el usuario, o la que ya estaba fijada; si no, se detecta.
                    val forced = when {
                        cr.rotationChanged -> Rotation.fromDegrees(cr.rotation)
                        orig?.rotationLocked == true -> Rotation.fromDegrees(orig.rotation)
                        else -> null
                    }
                    withContext(Dispatchers.Default) { c.importer.rebuild(entity, forced) }
                    c.repo.progressFor(entity.id)
                }
                _dirty.value = false
                _busy.value = null
                onDone()
            } catch (t: Throwable) {
                _busy.value = "Error: ${t.message}"
            }
        }
    }

    fun clearMessage() { _busy.value = null }

    override fun onCleared() {
        renderer?.close()
        super.onCleared()
    }

    companion object {
        fun clampRect(r: NormRect) = NormRect(max(0.0, r.left), max(0.0, r.top), min(1.0, r.right), min(1.0, r.bottom))
    }
}
