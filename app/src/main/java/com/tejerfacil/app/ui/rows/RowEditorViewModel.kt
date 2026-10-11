package com.tejerfacil.app.ui.rows

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tejerfacil.app.AppContainer
import com.tejerfacil.app.data.db.DiagramEntity
import com.tejerfacil.app.data.db.RowEntity
import com.tejerfacil.core.geometry.NormRect
import com.tejerfacil.core.geometry.Rotation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

data class EditRow(val key: Long, val rect: NormRect, val label: Int?)

enum class Edge(val title: String) { TOP("Superior"), BOTTOM("Inferior"), LEFT("Izquierdo"), RIGHT("Derecho") }

/**
 * Edición manual de filas. Las filas se manejan ordenadas de arriba hacia
 * abajo; al guardar se asigna el orden de lectura elegido.
 */
class RowEditorViewModel(private val c: AppContainer, val diagramId: Long) : ViewModel() {
    private var nextKey = 1L
    private val _diagram = MutableStateFlow<DiagramEntity?>(null)
    val diagram: StateFlow<DiagramEntity?> = _diagram.asStateFlow()
    private val _rows = MutableStateFlow<List<EditRow>>(emptyList())
    val rows: StateFlow<List<EditRow>> = _rows.asStateFlow()
    private val _selected = MutableStateFlow<Long?>(null)
    val selected: StateFlow<Long?> = _selected.asStateFlow()
    val bottomUp = MutableStateFlow(true)
    val edge = MutableStateFlow(Edge.TOP)
    private val _dirty = MutableStateFlow(false)
    val dirty: StateFlow<Boolean> = _dirty.asStateFlow()
    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy.asStateFlow()

    init { reload() }

    private fun reload() {
        viewModelScope.launch {
            val d = c.db.diagrams().get(diagramId)
            _diagram.value = d
            bottomUp.value = d?.readingOrder != "TOP_DOWN"
            val list = c.db.rows().listForDiagram(diagramId)
            _rows.value = list.map { EditRow(nextKey++, NormRect(it.left, it.top, it.right, it.bottom), it.label) }.sortedBy { it.rect.top }
            // Seleccionar la fila en la que el usuario va tejiendo.
            val current = c.db.progress().get(diagramId)?.currentRow ?: 0
            val inOrder = ordered(_rows.value)
            _selected.value = inOrder.getOrNull(current)?.key ?: _rows.value.firstOrNull()?.key
            _dirty.value = false
        }
    }

    private fun ordered(list: List<EditRow>) = if (bottomUp.value) list.sortedByDescending { it.rect.top } else list.sortedBy { it.rect.top }

    private fun set(list: List<EditRow>) {
        _rows.value = list.sortedBy { it.rect.top }
        _dirty.value = true
    }

    fun selectIndex(i: Int) { _rows.value.getOrNull(i)?.let { _selected.value = it.key } }

    fun selectNeighbor(delta: Int) {
        val i = _rows.value.indexOfFirst { it.key == _selected.value }
        selectIndex((if (i < 0) 0 else i + delta).coerceIn(0, max(0, _rows.value.size - 1)))
    }

    private fun imgStep(): Pair<Double, Double> {
        val d = _diagram.value
        return if (d == null || d.imageWidth == 0) 0.001 to 0.001 else 1.0 / d.imageWidth to 1.0 / d.imageHeight
    }

    /** Mueve el borde elegido [pixels] píxeles de la imagen (negativo = arriba/izquierda). */
    fun nudge(pixels: Int) {
        val key = _selected.value ?: return
        val (sx, sy) = imgStep()
        set(_rows.value.map { r ->
            if (r.key != key) r else {
                val q = r.rect
                val minH = 3 * sy; val minW = 3 * sx
                val nr = when (edge.value) {
                    Edge.TOP -> q.copy(top = (q.top + pixels * sy).coerceIn(0.0, q.bottom - minH))
                    Edge.BOTTOM -> q.copy(bottom = (q.bottom + pixels * sy).coerceIn(q.top + minH, 1.0))
                    Edge.LEFT -> q.copy(left = (q.left + pixels * sx).coerceIn(0.0, q.right - minW))
                    Edge.RIGHT -> q.copy(right = (q.right + pixels * sx).coerceIn(q.left + minW, 1.0))
                }
                r.copy(rect = nr)
            }
        })
    }

    /** Agrega una fila pegada arriba de la seleccionada, con la misma altura y ancho. */
    fun addAbove() {
        val sel = _rows.value.firstOrNull { it.key == _selected.value }
        val new = if (sel != null) {
            val h = sel.rect.height
            val top = max(0.0, sel.rect.top - h)
            EditRow(nextKey++, NormRect(sel.rect.left, top, sel.rect.right, sel.rect.top), null)
        } else {
            EditRow(nextKey++, NormRect(0.05, 0.45, 0.95, 0.5), null)
        }
        set(_rows.value + new)
        _selected.value = new.key
    }

    fun addBelow() {
        val sel = _rows.value.firstOrNull { it.key == _selected.value } ?: return addAbove()
        val h = sel.rect.height
        val bottom = min(1.0, sel.rect.bottom + h)
        val new = EditRow(nextKey++, NormRect(sel.rect.left, sel.rect.bottom, sel.rect.right, bottom), null)
        set(_rows.value + new)
        _selected.value = new.key
    }

    fun deleteSelected() {
        val i = _rows.value.indexOfFirst { it.key == _selected.value }
        if (i < 0) return
        set(_rows.value.filterIndexed { idx, _ -> idx != i })
        selectIndex(min(i, _rows.value.size - 1))
    }

    /** Divide la fila seleccionada en [n] filas de igual altura. */
    fun splitSelected(n: Int) {
        val sel = _rows.value.firstOrNull { it.key == _selected.value } ?: return
        if (n < 2) return
        val h = sel.rect.height / n
        val parts = (0 until n).map { k ->
            EditRow(nextKey++, sel.rect.copy(top = sel.rect.top + k * h, bottom = sel.rect.top + (k + 1) * h), null)
        }
        set(_rows.value.filter { it.key != sel.key } + parts)
        _selected.value = parts.first().key
    }

    /** Une la fila seleccionada con la de abajo. */
    fun mergeWithBelow() {
        val list = _rows.value
        val i = list.indexOfFirst { it.key == _selected.value }
        if (i < 0 || i + 1 >= list.size) return
        val a = list[i]; val b = list[i + 1]
        val merged = EditRow(a.key, a.rect.union(b.rect), a.label ?: b.label)
        set(list.filterIndexed { idx, _ -> idx != i && idx != i + 1 } + merged)
        _selected.value = merged.key
    }

    fun setLabel(value: Int?) {
        val key = _selected.value ?: return
        set(_rows.value.map { if (it.key == key) it.copy(label = value) else it })
    }

    /** Numera todas las filas 1..N según el orden de lectura (a pedido del usuario). */
    fun numberSequentially() {
        val order = ordered(_rows.value)
        val labels = order.mapIndexed { i, r -> r.key to (i + 1) }.toMap()
        set(_rows.value.map { it.copy(label = labels[it.key]) })
    }

    fun setBottomUp(v: Boolean) {
        bottomUp.value = v
        _dirty.value = true
    }

    fun redetect() {
        val d = _diagram.value ?: return
        viewModelScope.launch {
            _busy.value = "Reconociendo filas…"
            try {
                c.importer.rebuild(d, Rotation.fromDegrees(d.rotation))
                reload()
                _busy.value = null
            } catch (t: Throwable) {
                _busy.value = "Error: ${t.message}"
            }
        }
    }

    fun clearMessage() { _busy.value = null }

    fun save(onDone: () -> Unit) {
        viewModelScope.launch {
            val order = ordered(_rows.value)
            val entities = order.mapIndexed { i, r ->
                RowEntity(
                    diagramId = diagramId, orderIndex = i,
                    top = r.rect.top, bottom = r.rect.bottom, left = r.rect.left, right = r.rect.right,
                    label = r.label, manual = true,
                )
            }
            c.repo.saveRows(diagramId, entities, if (bottomUp.value) "BOTTOM_UP" else "TOP_DOWN")
            _dirty.value = false
            onDone()
        }
    }
}
