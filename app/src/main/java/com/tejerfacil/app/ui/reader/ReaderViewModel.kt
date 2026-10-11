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
import kotlinx.coroutines.launch

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
