package com.tejerfacil.app.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.tejerfacil.core.geometry.NormRect
import java.io.Closeable
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Renderizado de páginas con el PdfRenderer de Android (PDFium). Las
 * coordenadas son las de la página ya girada según /Rotate, en puntos.
 * PdfRenderer no es seguro entre hilos: todos los accesos se sincronizan.
 */
class PdfPageRenderer(file: File) : Closeable {
    private val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(pfd)

    val pageCount: Int get() = renderer.pageCount

    /** Tamaño de la página en puntos (ancho, alto). */
    fun pageSize(index: Int): Pair<Int, Int> = synchronized(this) {
        renderer.openPage(index).use { it.width to it.height }
    }

    /** Página completa con el lado mayor = [longSide] píxeles. */
    fun renderPage(index: Int, longSide: Int): Bitmap = synchronized(this) {
        renderer.openPage(index).use { page ->
            val scale = longSide.toFloat() / max(page.width, page.height)
            val w = max(1, (page.width * scale).roundToInt())
            val h = max(1, (page.height * scale).roundToInt())
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            val m = Matrix().apply { setScale(w.toFloat() / page.width, h.toFloat() / page.height) }
            page.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bmp
        }
    }

    /**
     * Renderiza solo una región (normalizada) de la página a [pxPerPoint]
     * píxeles por punto. Así los diagramas se guardan con buena definición sin
     * renderizar toda la página a alta resolución.
     */
    fun renderRegion(index: Int, region: NormRect, pxPerPoint: Float): Bitmap = synchronized(this) {
        renderer.openPage(index).use { page ->
            val pw = page.width.toFloat(); val ph = page.height.toFloat()
            val w = max(1, (region.width * pw * pxPerPoint).roundToInt())
            val h = max(1, (region.height * ph * pxPerPoint).roundToInt())
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            val m = Matrix().apply {
                setScale(pxPerPoint, pxPerPoint)
                postTranslate((-region.left * pw * pxPerPoint).toFloat(), (-region.top * ph * pxPerPoint).toFloat())
            }
            page.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bmp
        }
    }

    override fun close() {
        runCatching { renderer.close() }
        runCatching { pfd.close() }
    }
}
