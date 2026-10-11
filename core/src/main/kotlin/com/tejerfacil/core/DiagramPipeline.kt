package com.tejerfacil.core

import com.tejerfacil.core.detect.OrientationResult
import com.tejerfacil.core.detect.OrientationSource
import com.tejerfacil.core.geometry.Rotation
import com.tejerfacil.core.ocr.DigitOcr
import com.tejerfacil.core.rows.NumberLabel
import com.tejerfacil.core.rows.RowDetectionResult
import com.tejerfacil.core.rows.RowDetector
import com.tejerfacil.core.vision.ImageOps
import com.tejerfacil.core.vision.Vision

import org.opencv.core.Mat

/**
 * Procesamiento de un diagrama ya recortado (sin girar):
 * 1. decide la orientación (texto del PDF > OCR de dígitos > disposición),
 * 2. gira la imagen (sin pérdida),
 * 3. reconoce filas y su numeración.
 */
class DiagramPipeline(private val rowDetector: RowDetector = RowDetector()) {

    class Result(
        /** Imagen girada (el llamador debe liberarla). */
        val image: Mat,
        val orientation: OrientationResult,
        val rows: RowDetectionResult,
        val labels: List<NumberLabel>,
    )

    /**
     * @param crop recorte de la página sin girar.
     * @param cellW ancho de celda estimado en píxeles del recorte (0 = desconocido).
     * @param initial orientación estimada en la página.
     * @param forced si el usuario fijó la rotación, se respeta.
     * @param pdfLabels números extraídos del texto del PDF, ya en coordenadas de la imagen girada
     *        (función de la rotación final).
     */
    fun process(
        crop: Mat,
        cellW: Double,
        cellH: Double,
        initial: OrientationResult?,
        forced: Rotation? = null,
        pdfLabels: ((Rotation) -> List<NumberLabel>)? = null,
    ): Result {
        val gray = Vision.toGray(crop)
        val ink = Vision.inkMask(gray)
        gray.release()
        var cw = cellW; var ch = cellH
        if (cw <= 2 || ch <= 2) {
            val est = rowDetector.estimateCell(ink)
            cw = est?.width ?: 0.0; ch = est?.height ?: 0.0
        }

        val orientation: OrientationResult = when {
            forced != null -> OrientationResult(forced, 1.0, "fijada por el usuario", OrientationSource.MANUAL)
            initial != null && initial.source == OrientationSource.PDF_TEXT -> initial
            cw > 2 && ch > 2 -> {
                val comps = DigitOcr.candidates(ink, cw, ch)
                val vote = DigitOcr.voteRotation(ink, comps)
                if (vote != null && vote.confidence >= 0.5) {
                    OrientationResult(vote.rotation, vote.confidence, vote.detail, OrientationSource.OCR)
                } else initial ?: OrientationResult(Rotation.R0, 0.3, "sin datos; se asume derecho", OrientationSource.DEFAULT)
            }
            else -> initial ?: OrientationResult(Rotation.R0, 0.3, "sin datos; se asume derecho", OrientationSource.DEFAULT)
        }
        ink.release()

        val rotated = ImageOps.rotate(crop, orientation.rotation)
        val (rcw, rch) = if (orientation.rotation.swapsAxes) ch to cw else cw to ch
        val labels = pdfLabels?.invoke(orientation.rotation)?.takeIf { it.isNotEmpty() }
            ?: ocrLabels(rotated, rcw, rch)
        val hint = if (rcw > 2 && rch > 2) RowDetector.CellHint(rcw, rch) else null
        val rows = rowDetector.detect(rotated, hint, labels)
        return Result(rotated, orientation, rows, labels)
    }

    private fun ocrLabels(img: Mat, cw: Double, ch: Double): List<NumberLabel> {
        if (cw <= 2 || ch <= 2) return emptyList()
        val gray = Vision.toGray(img)
        val ink = Vision.inkMask(gray)
        gray.release()
        val comps = DigitOcr.candidates(ink, cw, ch)
        val res = DigitOcr.readNumbers(ink, comps)
        ink.release()
        return res
    }

}
