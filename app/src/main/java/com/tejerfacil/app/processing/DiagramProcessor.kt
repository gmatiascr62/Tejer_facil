package com.tejerfacil.app.processing

import com.tejerfacil.app.data.FileStore
import com.tejerfacil.app.data.db.AppDatabase
import com.tejerfacil.app.data.db.DiagramEntity
import com.tejerfacil.app.data.db.RowEntity
import com.tejerfacil.app.pdf.PdfPageRenderer
import com.tejerfacil.core.DiagramPipeline
import com.tejerfacil.core.detect.DiagramDetector
import com.tejerfacil.core.detect.OrientationResult
import com.tejerfacil.core.detect.PageAnalysis
import com.tejerfacil.core.geometry.DiagramTransform
import com.tejerfacil.core.geometry.NormRect
import com.tejerfacil.core.geometry.Rotation
import com.tejerfacil.core.rows.LabelMapper
import com.tejerfacil.core.text.TextGlyph
import com.tejerfacil.core.text.TextWord
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Une el renderizado de Android con el núcleo de visión:
 * - análisis de página a resolución media (~200 ppp en A4),
 * - recorte re-renderizado a alta resolución para leer bien los símbolos,
 * - orientación, filas y numeración, y guardado del resultado.
 */
class DiagramProcessor(private val db: AppDatabase, private val files: FileStore) {

    companion object {
        const val ANALYSIS_LONG_SIDE = 2339
        /** Altura de celda deseada en la imagen guardada. */
        const val TARGET_CELL_PX = 36.0
        const val MIN_PX_PER_PT = 200f / 72f
        const val MAX_PX_PER_PT = 450f / 72f
        const val MAX_LONG_SIDE = 4096
        const val MAX_PIXELS = 11_000_000.0
    }

    private val detector = DiagramDetector()

    fun analyzePage(renderer: PdfPageRenderer, pageIndex: Int, glyphs: List<TextGlyph>): PageAnalysis {
        val bmp = renderer.renderPage(pageIndex, ANALYSIS_LONG_SIDE)
        val mat = bmp.toMat()
        bmp.recycle()
        try {
            return detector.detect(mat, glyphs)
        } finally {
            mat.release()
        }
    }

    /**
     * Genera (o regenera) la imagen y las filas de un diagrama.
     * @param forced rotación elegida por el usuario (o null para detectarla).
     */
    suspend fun build(
        renderer: PdfPageRenderer,
        entity: DiagramEntity,
        words: List<TextWord>,
        initial: OrientationResult?,
        forced: Rotation?,
    ): DiagramEntity {
        val (pw, ph) = renderer.pageSize(entity.pageIndex)
        val crop = NormRect(entity.cropLeft, entity.cropTop, entity.cropRight, entity.cropBottom).normalized()
        val cellWPt = entity.cellWidthNorm * pw
        val cellHPt = entity.cellHeightNorm * ph
        var scale = if (cellWPt > 0 && cellHPt > 0) (TARGET_CELL_PX / min(cellWPt, cellHPt)).toFloat() else 300f / 72f
        scale = scale.coerceIn(MIN_PX_PER_PT, MAX_PX_PER_PT)
        val cropWPt = max(1.0, crop.width * pw)
        val cropHPt = max(1.0, crop.height * ph)
        scale = min(scale, (MAX_LONG_SIDE / max(cropWPt, cropHPt)).toFloat())
        scale = min(scale, sqrt(MAX_PIXELS / (cropWPt * cropHPt)).toFloat())

        val bmp = renderer.renderRegion(entity.pageIndex, crop, scale)
        val mat = bmp.toMat()
        bmp.recycle()
        val cw = cellWPt * scale
        val ch = cellHPt * scale
        val cropW = mat.cols(); val cropH = mat.rows()
        val pdfLabels: ((Rotation) -> List<com.tejerfacil.core.rows.NumberLabel>)? =
            if (words.isEmpty()) null else { rot -> LabelMapper.labelsForDiagram(words, DiagramTransform(crop, rot, cropW, cropH)) }
        val result = try {
            DiagramPipeline().process(mat, cw, ch, initial, forced, pdfLabels)
        } finally {
            mat.release()
        }

        // Se re-renderiza desde el PDF: las ediciones de símbolos (si había) se descartan.
        return saveResult(entity.copy(edited = false), result, result.orientation.rotation.degrees, forced != null)
    }

    /**
     * Re-procesa un diagrama a partir de su imagen actual (por ejemplo con
     * símbolos editados), girándola [delta] y reconociendo de nuevo las filas.
     */
    suspend fun rebuildFromImage(entity: DiagramEntity, delta: Rotation): DiagramEntity {
        val bmp = android.graphics.BitmapFactory.decodeFile(entity.imagePath) ?: error("No se pudo leer la imagen del diagrama")
        val src = bmp.toMat()
        bmp.recycle()
        val rotated = com.tejerfacil.core.vision.ImageOps.rotate(src, delta)
        src.release()
        val result = try {
            DiagramPipeline().process(rotated, 0.0, 0.0, null, Rotation.R0, null)
        } finally {
            rotated.release()
        }
        val newRotation = (entity.rotation + delta.degrees) % 360
        return saveResult(entity, result, newRotation, true)
    }

    private suspend fun saveResult(
        entity: DiagramEntity,
        result: DiagramPipeline.Result,
        rotationDegrees: Int,
        locked: Boolean,
    ): DiagramEntity {
        val out = result.image.toBitmap()
        result.image.release()
        val w = out.width; val h = out.height
        val version = System.currentTimeMillis()
        val imgFile: File = files.diagramImage(entity.patternId, entity.id, version)
        val thumbFile: File = files.diagramThumb(entity.patternId, entity.id, version)
        files.savePng(out, imgFile)
        val thumb = out.scaledToMax(480)
        files.saveJpeg(thumb, thumbFile)
        if (thumb !== out) thumb.recycle()
        out.recycle()
        files.deleteQuietly(entity.imagePath)
        files.deleteQuietly(entity.thumbPath)

        val rowsRes = result.rows
        val rows = rowsRes.rows.mapIndexed { i, r ->
            RowEntity(
                diagramId = entity.id, orderIndex = i,
                top = r.top / h, bottom = r.bottom / h, left = r.left / w, right = r.right / w,
                label = r.label,
            )
        }
        db.rows().replace(entity.id, rows)
        val updated = entity.copy(
            rotation = rotationDegrees,
            rotationSource = result.orientation.source.name,
            rotationConfidence = result.orientation.confidence,
            rotationLocked = locked,
            imagePath = imgFile.absolutePath,
            thumbPath = thumbFile.absolutePath,
            imageWidth = w,
            imageHeight = h,
            readingOrder = rowsRes.readingOrder.name,
            rowMethod = rowsRes.method.name,
            rowConfidence = rowsRes.confidence,
            notes = (listOf("Orientación: ${result.orientation.reason}") + rowsRes.notes).joinToString("\n"),
        )
        db.diagrams().update(updated)
        db.progress().get(entity.id)?.let { p ->
            if (p.currentRow >= rows.size) db.progress().upsert(p.copy(currentRow = max(0, rows.size - 1)))
        }
        return updated
    }
}
