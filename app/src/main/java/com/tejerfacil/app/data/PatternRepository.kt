package com.tejerfacil.app.data

import com.tejerfacil.app.data.db.AppDatabase
import com.tejerfacil.app.data.db.DiagramEntity
import com.tejerfacil.app.data.db.ProgressEntity
import com.tejerfacil.app.data.db.RowEntity
import kotlinx.coroutines.flow.first

/** Operaciones de alto nivel sobre patrones, diagramas, filas y progreso. */
class PatternRepository(
    val db: AppDatabase,
    private val files: FileStore,
    private val settings: SettingsRepository,
) {
    fun observeLibrary() = db.patterns().observeSummaries()
    fun observePattern(id: Long) = db.patterns().observe(id)
    fun observeDiagrams(patternId: Long) = db.diagrams().observeForPattern(patternId)

    suspend fun deletePattern(id: Long) {
        db.patterns().delete(id)
        files.deletePattern(id)
    }

    suspend fun renameDiagram(id: Long, name: String) {
        val d = db.diagrams().get(id) ?: return
        db.diagrams().update(d.copy(name = name))
    }

    suspend fun deleteDiagram(d: DiagramEntity) {
        db.diagrams().delete(d)
        files.deleteQuietly(d.imagePath)
        files.deleteQuietly(d.thumbPath)
    }

    /** Progreso del diagrama; si no existe se crea con los colores por defecto. */
    suspend fun progressFor(diagramId: Long): ProgressEntity {
        db.progress().get(diagramId)?.let { return it }
        val s = settings.settings.first()
        val p = ProgressEntity(
            diagramId = diagramId, highlightColor = s.highlightColor, highlightAlpha = s.highlightAlpha,
            updatedAt = System.currentTimeMillis(),
        )
        db.progress().upsert(p)
        return p
    }

    suspend fun saveProgress(p: ProgressEntity) {
        db.progress().upsert(p.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun saveRows(diagramId: Long, rows: List<RowEntity>, readingOrder: String) {
        db.rows().replace(diagramId, rows)
        val d = db.diagrams().get(diagramId) ?: return
        db.diagrams().update(d.copy(readingOrder = readingOrder, rowMethod = "MANUAL", rowConfidence = 1.0))
        db.progress().get(diagramId)?.let { p ->
            if (p.currentRow >= rows.size) db.progress().upsert(p.copy(currentRow = (rows.size - 1).coerceAtLeast(0)))
        }
    }
}
