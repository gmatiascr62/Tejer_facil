package com.tejerfacil.app.data

import com.tejerfacil.app.data.db.AppDatabase
import com.tejerfacil.app.data.db.DiagramEntity
import com.tejerfacil.app.data.db.ProgressEntity
import com.tejerfacil.app.data.db.RowEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

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

    /**
     * Crea una copia editable del patrón: copia el PDF, los diagramas (imágenes),
     * las filas y los colores. El progreso de la copia empieza de cero.
     * Devuelve el id del patrón nuevo.
     */
    suspend fun duplicatePattern(id: Long): Long = withContext(Dispatchers.IO) {
        val p = db.patterns().get(id) ?: error("Patrón inexistente")
        val now = System.currentTimeMillis()
        val newId = db.patterns().insert(
            p.copy(id = 0, name = "${p.name} (copia)", pdfPath = "", importedAt = now, status = "READY", copyOf = p.copyOf ?: p.id)
        )
        val pdf = files.pdfFile(newId)
        File(p.pdfPath).copyTo(pdf, overwrite = true)
        db.patterns().get(newId)?.let { db.patterns().update(it.copy(pdfPath = pdf.absolutePath)) }
        for (d in db.diagrams().listForPattern(id)) {
            val newD = db.diagrams().insert(d.copy(id = 0, patternId = newId, imagePath = "", thumbPath = ""))
            val img = files.diagramImage(newId, newD, now)
            val thumb = files.diagramThumb(newId, newD, now)
            runCatching { File(d.imagePath).copyTo(img, overwrite = true) }
            runCatching { File(d.thumbPath).copyTo(thumb, overwrite = true) }
            db.diagrams().update(d.copy(id = newD, patternId = newId, imagePath = img.absolutePath, thumbPath = thumb.absolutePath))
            db.rows().replace(newD, db.rows().listForDiagram(d.id))
            val old = db.progress().get(d.id)
            db.progress().upsert(
                ProgressEntity(
                    diagramId = newD,
                    highlightColor = old?.highlightColor ?: AppSettings.DEFAULT_COLOR,
                    highlightAlpha = old?.highlightAlpha ?: 0.35f,
                    updatedAt = now,
                )
            )
        }
        newId
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
