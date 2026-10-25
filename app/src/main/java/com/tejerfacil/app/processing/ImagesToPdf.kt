package com.tejerfacil.app.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.media.ExifInterface
import android.net.Uri
import com.tejerfacil.core.vision.Deskew
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Convierte imágenes (capturas, fotos de la galería, PNG/JPG) en un PDF interno
 * de una página por imagen, para procesarlas igual que un PDF. Corrige la
 * orientación guardada por la cámara (EXIF) y endereza inclinaciones leves.
 */
object ImagesToPdf {
    /** Resolución supuesta para dar tamaño a la página (no afecta la calidad). */
    private const val ASSUMED_DPI = 150f
    /** Lado máximo al decodificar (memoria). */
    private const val MAX_SIDE = 3500

    /** Devuelve la cantidad de páginas escritas. */
    fun build(context: Context, uris: List<Uri>, out: File, onProgress: (Int) -> Unit = {}): Int {
        val doc = PdfDocument()
        var pages = 0
        try {
            for ((i, uri) in uris.withIndex()) {
                onProgress(i)
                var bmp = decode(context, uri) ?: continue
                // Enderezar si la imagen está levemente inclinada.
                val mat = bmp.toMat()
                val fixed = runCatching { Deskew.straighten(mat) }.getOrNull()
                mat.release()
                if (fixed != null) {
                    val nb = fixed.toBitmap()
                    fixed.release()
                    bmp.recycle()
                    bmp = nb
                }
                val wPt = max(1, (bmp.width * 72f / ASSUMED_DPI).roundToInt())
                val hPt = max(1, (bmp.height * 72f / ASSUMED_DPI).roundToInt())
                val page = doc.startPage(PdfDocument.PageInfo.Builder(wPt, hPt, pages + 1).create())
                page.canvas.drawColor(Color.WHITE)
                page.canvas.drawBitmap(bmp, null, Rect(0, 0, wPt, hPt), Paint(Paint.FILTER_BITMAP_FLAG))
                doc.finishPage(page)
                bmp.recycle()
                pages++
            }
            out.outputStream().use { doc.writeTo(it) }
        } finally {
            doc.close()
        }
        return pages
    }

    /** Decodifica con submuestreo y aplica la rotación EXIF. */
    fun decode(context: Context, uri: Uri): Bitmap? {
        val cr = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // Con inJustDecodeBounds, decodeStream siempre devuelve null: solo se leen las medidas.
        val stream = cr.openInputStream(uri) ?: return null
        stream.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > MAX_SIDE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val degrees = runCatching {
            cr.openInputStream(uri)?.use { s ->
                when (ExifInterface(s).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            } ?: 0
        }.getOrDefault(0)
        if (degrees == 0) return bmp
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        if (rotated !== bmp) bmp.recycle()
        return rotated
    }
}
