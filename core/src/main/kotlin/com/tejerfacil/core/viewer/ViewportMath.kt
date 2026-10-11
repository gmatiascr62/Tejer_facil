package com.tejerfacil.core.viewer

import com.tejerfacil.core.geometry.NormRect
import kotlin.math.max
import kotlin.math.min

/**
 * Estado de visualización: pantalla = imagen * scale + offset.
 * Las filas se guardan en coordenadas normalizadas de la imagen del diagrama,
 * así el resaltado coincide con el gráfico en cualquier nivel de zoom.
 */
data class Viewport(val scale: Float, val offsetX: Float, val offsetY: Float)

data class ScreenRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val centerY: Float get() = (top + bottom) / 2f
    val centerX: Float get() = (left + right) / 2f
}

object ViewportMath {
    const val MIN_ZOOM_FACTOR = 0.5f // respecto de "ajustar al ancho"
    const val MAX_SCALE = 12f

    fun fitWidthScale(imgW: Int, viewW: Float): Float = viewW / imgW

    fun fitInsideScale(imgW: Int, imgH: Int, viewW: Float, viewH: Float): Float =
        min(viewW / imgW, viewH / imgH)

    fun minScale(imgW: Int, imgH: Int, viewW: Float, viewH: Float): Float =
        min(fitInsideScale(imgW, imgH, viewW, viewH), fitWidthScale(imgW, viewW) * MIN_ZOOM_FACTOR)

    /** Rectángulo de una fila (normalizada) en coordenadas de pantalla. */
    fun rowOnScreen(row: NormRect, imgW: Int, imgH: Int, vp: Viewport): ScreenRect = ScreenRect(
        (row.left * imgW).toFloat() * vp.scale + vp.offsetX,
        (row.top * imgH).toFloat() * vp.scale + vp.offsetY,
        (row.right * imgW).toFloat() * vp.scale + vp.offsetX,
        (row.bottom * imgH).toFloat() * vp.scale + vp.offsetY,
    )

    /** Punto de pantalla -> punto normalizado de la imagen. */
    fun screenToImageNorm(sx: Float, sy: Float, imgW: Int, imgH: Int, vp: Viewport): Pair<Double, Double> =
        ((sx - vp.offsetX) / vp.scale / imgW).toDouble() to ((sy - vp.offsetY) / vp.scale / imgH).toDouble()

    /**
     * Desplazamiento que deja la fila en el centro vertical de la pantalla,
     * sin cambiar el zoom. En horizontal solo se mueve si la fila no queda
     * visible (se muestra su comienzo).
     */
    fun centerOnRow(row: NormRect, imgW: Int, imgH: Int, viewW: Float, viewH: Float, vp: Viewport): Viewport {
        val rowCenterImg = (row.centerY * imgH).toFloat()
        val newOffsetY = viewH / 2f - rowCenterImg * vp.scale
        var newOffsetX = vp.offsetX
        val contentW = imgW * vp.scale
        if (contentW <= viewW) {
            newOffsetX = (viewW - contentW) / 2f
        } else {
            val left = (row.left * imgW).toFloat() * vp.scale + newOffsetX
            val right = (row.right * imgW).toFloat() * vp.scale + newOffsetX
            val rowW = right - left
            if (rowW <= viewW) {
                // Que la fila entera quede visible.
                if (left < 0) newOffsetX -= left - viewW * 0.04f
                else if (right > viewW) newOffsetX -= right - viewW + viewW * 0.04f
            } else if (right < viewW * 0.25f || left > viewW * 0.75f) {
                newOffsetX -= left - viewW * 0.04f
            }
        }
        return clamp(vp.copy(offsetX = newOffsetX, offsetY = newOffsetY), imgW, imgH, viewW, viewH)
    }

    /**
     * Limita el desplazamiento. En vertical se deja medio alto de pantalla de
     * espacio extra arriba y abajo para poder centrar la primera y la última fila.
     */
    fun clamp(vp: Viewport, imgW: Int, imgH: Int, viewW: Float, viewH: Float): Viewport {
        val cw = imgW * vp.scale
        val chh = imgH * vp.scale
        val ox = if (cw <= viewW) {
            (viewW - cw) / 2f
        } else {
            val extra = viewW * 0.25f
            vp.offsetX.coerceIn(viewW - cw - extra, extra)
        }
        val minY = viewH / 2f - chh
        val maxY = viewH / 2f
        val oy = vp.offsetY.coerceIn(min(minY, maxY), max(minY, maxY))
        return Viewport(vp.scale, ox, oy)
    }

    /** Zoom manteniendo fijo el punto (focusX, focusY) de la pantalla. */
    fun zoomAround(vp: Viewport, factor: Float, focusX: Float, focusY: Float, minScale: Float, maxScale: Float = MAX_SCALE): Viewport {
        val newScale = (vp.scale * factor).coerceIn(minScale, maxScale)
        val f = newScale / vp.scale
        return Viewport(
            newScale,
            focusX - (focusX - vp.offsetX) * f,
            focusY - (focusY - vp.offsetY) * f,
        )
    }

    /** Índice de la fila que contiene el punto de pantalla (o la más cercana en vertical dentro de su ancho). */
    fun hitTestRow(sx: Float, sy: Float, rows: List<NormRect>, imgW: Int, imgH: Int, vp: Viewport): Int? {
        val (nx, ny) = screenToImageNorm(sx, sy, imgW, imgH, vp)
        val inside = rows.indexOfFirst { ny >= it.top && ny <= it.bottom && nx >= it.left - 0.02 && nx <= it.right + 0.02 }
        if (inside >= 0) return inside
        val byY = rows.indices.filter { ny >= rows[it].top && ny <= rows[it].bottom }
        return byY.firstOrNull()
    }
}
