package com.tejerfacil.app.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tejerfacil.app.AppContainer
import com.tejerfacil.app.data.AppSettings
import com.tejerfacil.app.data.db.DiagramEntity
import com.tejerfacil.app.data.db.ProgressEntity
import com.tejerfacil.app.data.db.RowEntity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ReaderViewModel(private val c: AppContainer, val diagramId: Long) : ViewModel() {

    val diagram: StateFlow<DiagramEntity?> =
        c.db.diagrams().observe(diagramId).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val rows: StateFlow<List<RowEntity>> =
        c.db.rows().observeForDiagram(diagramId).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val settings: StateFlow<AppSettings> =
        c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private val _progress = MutableStateFlow<ProgressEntity?>(null)
    val progress: StateFlow<ProgressEntity?> = _progress.asStateFlow()
    private var saveJob: Job? = null

    init {
        viewModelScope.launch { _progress.value = c.repo.progressFor(diagramId) }
    }

    fun goTo(index: Int) {
        val p = _progress.value ?: return
        val n = rows.value.size
        if (n == 0) return
        val i = index.coerceIn(0, n - 1)
        if (i == p.currentRow) return
        val np = p.copy(currentRow = i)
        _progress.value = np
        // La fila se guarda de inmediato.
        viewModelScope.launch { c.repo.saveProgress(np) }
    }

    fun next() = goTo((_progress.value?.currentRow ?: 0) + 1)
    fun previous() = goTo((_progress.value?.currentRow ?: 0) - 1)

    fun onViewChanged(v: SavedView) {
        val p = _progress.value ?: return
        _progress.value = p.copy(zoom = v.zoom, centerX = v.centerX, centerY = v.centerY)
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(700)
            _progress.value?.let { c.repo.saveProgress(it) }
        }
    }

    /** ¿El diagrama pertenece a una copia editable del patrón? */
    val isCopy: StateFlow<Boolean> = diagram
        .flatMapLatest { d -> if (d == null) flowOf(null) else c.db.patterns().observe(d.patternId) }
        .map { it?.copyOf != null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Crea una copia del patrón y devuelve el diagrama equivalente de la copia. */
    fun copyPatternForEditing(onDone: (Long) -> Unit) {
        val d = diagram.value ?: return
        viewModelScope.launch {
            _busy.value = "Creando la copia del patrón…"
            try {
                val newPattern = c.repo.duplicatePattern(d.patternId)
                val target = c.db.diagrams().listForPattern(newPattern)
                    .firstOrNull { it.pageIndex == d.pageIndex && it.indexInPage == d.indexInPage }
                _busy.value = null
                target?.let { onDone(it.id) }
            } catch (t: Throwable) {
                _busy.value = "Error: ${t.message ?: t.javaClass.simpleName}"
            }
        }
    }

    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy.asStateFlow()

    /** Gira el diagrama 90° (+90 horario, -90 antihorario) y vuelve a reconocer las filas. */
    fun rotateDiagram(delta: Int) {
        if (_busy.value != null) return
        viewModelScope.launch {
            _busy.value = "Girando el diagrama y reconociendo las filas…"
            try {
                c.importer.rotateDiagram(diagramId, delta)
                _busy.value = null
            } catch (t: Throwable) {
                _busy.value = "Error: ${t.message ?: t.javaClass.simpleName}"
            }
        }
    }

    fun clearMessage() { _busy.value = null }

    fun setHighlight(color: Int, alpha: Float) {
        val p = _progress.value ?: return
        val np = p.copy(highlightColor = color, highlightAlpha = alpha)
        _progress.value = np
        viewModelScope.launch {
            c.repo.saveProgress(np)
            c.settings.setHighlight(color, alpha) // también como valor por defecto
        }
    }

    override fun onCleared() {
        // Guardado final fuera del scope del ViewModel (que se está cancelando).
        _progress.value?.let { p -> c.scope.launch { c.repo.saveProgress(p) } }
        super.onCleared()
    }
}
