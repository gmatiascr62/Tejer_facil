package com.tejerfacil.core.geometry

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Rectángulo en píxeles (coordenadas enteras, [left, right) x [top, bottom)). */
data class IntBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val area: Long get() = width.toLong() * height.toLong()
    val centerX: Double get() = (left + right) / 2.0
    val centerY: Double get() = (top + bottom) / 2.0
    val isEmpty: Boolean get() = width <= 0 || height <= 0

    fun union(o: IntBox): IntBox =
        IntBox(min(left, o.left), min(top, o.top), max(right, o.right), max(bottom, o.bottom))

    fun intersect(o: IntBox): IntBox =
        IntBox(max(left, o.left), max(top, o.top), min(right, o.right), min(bottom, o.bottom))

    fun intersects(o: IntBox): Boolean =
        left < o.right && o.left < right && top < o.bottom && o.top < bottom

    fun contains(x: Double, y: Double): Boolean = x >= left && x < right && y >= top && y < bottom

    fun containsBox(o: IntBox): Boolean =
        o.left >= left && o.top >= top && o.right <= right && o.bottom <= bottom

    fun expand(dx: Int, dy: Int = dx): IntBox = IntBox(left - dx, top - dy, right + dx, bottom + dy)

    fun clip(w: Int, h: Int): IntBox =
        IntBox(left.coerceIn(0, w), top.coerceIn(0, h), right.coerceIn(0, w), bottom.coerceIn(0, h))

    /** Distancia entre bordes (0 si se superponen). */
    fun gapTo(o: IntBox): Double {
        val dx = max(0, max(o.left - right, left - o.right)).toDouble()
        val dy = max(0, max(o.top - bottom, top - o.bottom)).toDouble()
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    /** Fracción del área de [this] cubierta por [o]. */
    fun overlapFraction(o: IntBox): Double {
        val i = intersect(o)
        if (i.isEmpty || isEmpty) return 0.0
        return i.area.toDouble() / area.toDouble()
    }

    fun toNorm(w: Int, h: Int): NormRect =
        NormRect(left / w.toDouble(), top / h.toDouble(), right / w.toDouble(), bottom / h.toDouble())
}

/** Rectángulo normalizado (0..1) respecto de una imagen de referencia. */
data class NormRect(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    val width: Double get() = right - left
    val height: Double get() = bottom - top
    val centerX: Double get() = (left + right) / 2.0
    val centerY: Double get() = (top + bottom) / 2.0

    fun toPixels(w: Int, h: Int): IntBox = IntBox(
        (left * w).roundToInt(), (top * h).roundToInt(),
        (right * w).roundToInt(), (bottom * h).roundToInt()
    ).clip(w, h)

    fun normalized(): NormRect = NormRect(
        min(left, right).coerceIn(0.0, 1.0), min(top, bottom).coerceIn(0.0, 1.0),
        max(left, right).coerceIn(0.0, 1.0), max(top, bottom).coerceIn(0.0, 1.0)
    )

    fun union(o: NormRect): NormRect =
        NormRect(min(left, o.left), min(top, o.top), max(right, o.right), max(bottom, o.bottom))

    companion object {
        val FULL = NormRect(0.0, 0.0, 1.0, 1.0)
    }
}

/**
 * Rotación (en sentido horario) que hay que aplicar al recorte para que el
 * diagrama quede derecho.
 */
enum class Rotation(val degrees: Int) {
    R0(0), R90(90), R180(180), R270(270);

    fun plus(other: Rotation): Rotation = fromDegrees(degrees + other.degrees)
    fun next(): Rotation = plus(R90)
    val swapsAxes: Boolean get() = this == R90 || this == R270

    companion object {
        fun fromDegrees(deg: Int): Rotation {
            val d = ((deg % 360) + 360) % 360
            return entries.firstOrNull { it.degrees == d }
                ?: throw IllegalArgumentException("Rotación no múltiplo de 90: $deg")
        }
    }
}

data class PointD(val x: Double, val y: Double)

/**
 * Transformación entre coordenadas de página (normalizadas) y coordenadas de
 * la imagen del diagrama ya recortada y girada.
 */
class DiagramTransform(
    val crop: NormRect,
    val rotation: Rotation,
    /** Tamaño en píxeles del recorte SIN girar. */
    val cropWidthPx: Int,
    val cropHeightPx: Int,
) {
    val outWidth: Int get() = if (rotation.swapsAxes) cropHeightPx else cropWidthPx
    val outHeight: Int get() = if (rotation.swapsAxes) cropWidthPx else cropHeightPx

    /** Punto de la página (normalizado) -> píxel de la imagen girada. */
    fun pageToDiagram(px: Double, py: Double): PointD {
        val cx = (px - crop.left) / crop.width * cropWidthPx
        val cy = (py - crop.top) / crop.height * cropHeightPx
        return rotatePoint(cx, cy, rotation, cropWidthPx, cropHeightPx)
    }

    companion object {
        /** Gira un punto de una imagen w x h en sentido horario. */
        fun rotatePoint(x: Double, y: Double, r: Rotation, w: Int, h: Int): PointD = when (r) {
            Rotation.R0 -> PointD(x, y)
            Rotation.R90 -> PointD(h - y, x)
            Rotation.R180 -> PointD(w - x, h - y)
            Rotation.R270 -> PointD(y, w - x)
        }
    }
}
