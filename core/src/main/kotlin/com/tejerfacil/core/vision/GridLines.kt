package com.tejerfacil.core.vision

import org.opencv.core.Core
import org.opencv.core.Mat
import kotlin.math.max

/** Punto donde se cruzan (o se tocan) una línea horizontal y una vertical. */
data class Junction(val x: Double, val y: Double)

/**
 * Máscaras de líneas horizontales y verticales finas, y sus intersecciones.
 *
 * Las líneas se extraen con aperturas morfológicas con elementos lineales de
 * longitud [hLen]/[vLen]. Se descartan las zonas gruesas (rellenos, fotos,
 * celdas negras) cuyo espesor supera [maxThick].
 */
class GridLines private constructor(
    val horizontal: Mat,
    val vertical: Mat,
    val junctions: List<Junction>,
) {
    fun release() {
        horizontal.release(); vertical.release()
    }

    companion object {
        fun extract(ink: Mat, hLen: Int, vLen: Int, maxThick: Int): GridLines {
            val h = thinLines(ink, hLen, 1, 1, maxThick)
            val v = thinLines(ink, 1, vLen, maxThick, 1)
            val hd = Vision.dilate(h, 3, 3)
            val vd = Vision.dilate(v, 3, 3)
            val j = Mat()
            Core.bitwise_and(hd, vd, j)
            hd.release(); vd.release()
            val maxArea = (maxThick + 4) * (maxThick + 4) * 2
            val junctions = Components.find(j)
                .filter { it.area <= maxArea && it.box.width <= maxThick * 3 + 6 && it.box.height <= maxThick * 3 + 6 }
                .map { Junction(it.cx, it.cy) }
            j.release()
            return GridLines(h, v, junctions)
        }

        /**
         * Apertura con un segmento ([kw] x [kh]) y eliminación de las zonas cuyo
         * espesor perpendicular es >= [maxThick].
         */
        private fun thinLines(ink: Mat, kw: Int, kh: Int, thickW: Int, thickH: Int): Mat {
            val lines = Vision.open(ink, kw, kh)
            // Zonas gruesas: sobreviven a una apertura perpendicular de tamaño maxThick.
            val thick = Vision.open(lines, max(1, thickW), max(1, thickH))
            // Dilatar un poco lo grueso para no dejar bordes sueltos alrededor.
            val thickD = Vision.dilate(thick, 3, 3)
            Core.subtract(lines, thickD, lines)
            thick.release(); thickD.release()
            return lines
        }
    }
}
