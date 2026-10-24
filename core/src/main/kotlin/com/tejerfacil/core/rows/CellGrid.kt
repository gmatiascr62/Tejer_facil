package com.tejerfacil.core.rows

import com.tejerfacil.core.geometry.IntBox
import com.tejerfacil.core.vision.GridLines
import com.tejerfacil.core.vision.Vision
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Celdas de un diagrama: para cada fila, las posiciones x de las líneas
 * verticales que la atraviesan. Permite saber qué celda se tocó para copiar,
 * pegar o borrar un símbolo sin tocar las líneas de la cuadrícula.
 */
class CellGrid(val rows: List<RowCells>, private val fallbackCell: Double) {

    data class RowCells(val top: Double, val bottom: Double, val xs: List<Double>)

    /** Celda (en píxeles de la imagen) que contiene el punto, o null si está fuera del diagrama. */
    fun cellAt(x: Double, y: Double): IntBox? {
        val row = rows.firstOrNull { y >= it.top && y < it.bottom } ?: return null
        val xs = row.xs
        for (i in 0 until xs.size - 1) {
            if (x >= xs[i] && x < xs[i + 1]) {
                return IntBox(xs[i].roundToInt(), row.top.roundToInt(), xs[i + 1].roundToInt(), row.bottom.roundToInt())
            }
        }
        // Sin líneas verticales detectadas: celda aproximada centrada en el toque.
        if (xs.size < 2) {
            val h = row.bottom - row.top
            val w = if (fallbackCell > 0) fallbackCell else h
            return IntBox((x - w / 2).roundToInt(), row.top.roundToInt(), (x + w / 2).roundToInt(), row.bottom.roundToInt())
        }
        return null
    }

    companion object {
        /** Margen interior que conserva las líneas de la cuadrícula al editar una celda. */
        fun interior(cell: IntBox): IntBox {
            val inset = max(2, (0.12 * min(cell.width, cell.height)).roundToInt())
            return IntBox(cell.left + inset, cell.top + inset, cell.right - inset, cell.bottom - inset)
        }

        /**
         * Construye la grilla a partir de la imagen del diagrama y sus filas
         * (en píxeles de la imagen).
         */
        fun build(image: Mat, rows: List<DetectedRow>): CellGrid {
            if (rows.isEmpty()) return CellGrid(emptyList(), 0.0)
            val gray = Vision.toGray(image)
            val ink = Vision.inkMask(gray)
            gray.release()
            val medH = Vision.median(rows.map { it.height })
            val vLen = max(5, (0.8 * medH).roundToInt())
            val gl = GridLines.extract(ink, max(5, (0.6 * medH).roundToInt()), vLen, max(5, (0.42 * medH).roundToInt()))
            ink.release()
            val v = gl.vertical
            val w = v.cols(); val h = v.rows()
            val raw = ArrayList<Pair<DetectedRow, List<Double>>>(rows.size)
            val widths = ArrayList<Double>()
            for (r in rows.sortedBy { it.top }) {
                val margin = max(1, ((r.bottom - r.top) * 0.06).roundToInt())
                val y0 = (r.top.roundToInt() + margin).coerceIn(0, h - 1)
                val y1 = (r.bottom.roundToInt() - margin).coerceIn(y0 + 1, h)
                val band = v.submat(y0, y1, 0, w)
                val sum = Mat()
                Core.reduce(band, sum, 0, Core.REDUCE_SUM, CvType.CV_32S)
                val cols = IntArray(w)
                sum.get(0, 0, cols)
                band.release(); sum.release()
                // Una línea de la cuadrícula atraviesa toda la fila; un trazo de símbolo no.
                val need = 0.85 * (y1 - y0) * 255
                val xs = ArrayList<Double>()
                var start = -1
                for (x in 0..w) {
                    val on = x < w && cols[x] >= need
                    if (on && start < 0) start = x
                    if (!on && start >= 0) { xs.add((start + x - 1) / 2.0); start = -1 }
                }
                val tol = 0.3 * medH
                val inRow = xs.filter { it >= r.left - tol && it <= r.right + tol }.toMutableList()
                // Bordes de la fila (detectados con los cruces) aunque la línea sea gruesa o de color.
                if (inRow.none { kotlin.math.abs(it - r.left) < tol }) inRow.add(r.left)
                if (inRow.none { kotlin.math.abs(it - r.right) < tol }) inRow.add(r.right)
                inRow.sort()
                for (i in 0 until inRow.size - 1) widths.add(inRow[i + 1] - inRow[i])
                raw.add(r to inRow)
            }
            val cell = if (widths.isEmpty()) medH else {
                val m = Vision.median(widths)
                Vision.median(widths.filter { it <= m * 1.3 }.ifEmpty { widths })
            }
            // Huecos de 2 o más celdas (línea no detectada): dividir en partes iguales.
            val out = raw.map { (r, xs) ->
                val filled = ArrayList<Double>()
                for (i in xs.indices) {
                    filled.add(xs[i])
                    if (i + 1 < xs.size) {
                        val d = xs[i + 1] - xs[i]
                        val k = (d / cell).roundToInt()
                        if (k >= 2 && kotlin.math.abs(d - k * cell) <= 0.35 * cell) {
                            for (j in 1 until k) filled.add(xs[i] + d * j / k)
                        }
                    }
                }
                RowCells(r.top, r.bottom, filled)
            }
            gl.release()
            return CellGrid(out, cell)
        }
    }
}
