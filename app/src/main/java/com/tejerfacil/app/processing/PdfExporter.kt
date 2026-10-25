package com.tejerfacil.app.processing

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.tejerfacil.app.data.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.min

/**
 * Exporta los diagramas de un patrón (con las ediciones de símbolos y
 * columnas) a un PDF: una página A4 por diagrama, con su nombre arriba.
 * Las imágenes se incrustan a su resolución completa.
 */
class PdfExporter(private val context: Context, private val db: AppDatabase) {

    suspend fun export(patternId: Long): File = withContext(Dispatchers.IO) {
        val pattern = db.patterns().get(patternId) ?: error("Patrón inexistente")
        val diagrams = db.diagrams().listForPattern(patternId)
        if (diagrams.isEmpty()) error("El patrón no tiene diagramas")
        val dir = File(context.cacheDir, "exportaciones").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // solo se conserva la última exportación
        val safe = pattern.name.replace(Regex("[^\\p{L}\\p{N} ()._-]"), "_").trim().ifBlank { "patron" }
        val out = File(dir, "$safe.pdf")
        val doc = PdfDocument()
        val title = Paint().apply { color = Color.BLACK; textSize = 11f; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }
        val sub = Paint().apply { color = Color.DKGRAY; textSize = 8f; isAntiAlias = true }
        val imgPaint = Paint(Paint.FILTER_BITMAP_FLAG)
        try {
            for ((i, d) in diagrams.withIndex()) {
                val bmp = BitmapFactory.decodeFile(d.imagePath) ?: continue
                val landscape = bmp.width > bmp.height
                val pw = if (landscape) 842 else 595
                val ph = if (landscape) 595 else 842
                val page = doc.startPage(PdfDocument.PageInfo.Builder(pw, ph, i + 1).create())
                val c = page.canvas
                c.drawColor(Color.WHITE)
                val margin = 24f
                c.drawText(d.name, margin, margin + 10f, title)
                c.drawText("${pattern.name} · página ${d.pageIndex + 1} del PDF" + if (d.edited) " · editado" else "", margin, margin + 22f, sub)
                val top = margin + 32f
                val availW = pw - 2 * margin
                val availH = ph - top - margin
                val s = min(availW / bmp.width, availH / bmp.height)
                val w = bmp.width * s; val h = bmp.height * s
                val left = (pw - w) / 2f
                c.drawBitmap(bmp, null, RectF(left, top, left + w, top + h), imgPaint)
                doc.finishPage(page)
                bmp.recycle()
            }
            out.outputStream().use { doc.writeTo(it) }
        } finally {
            doc.close()
        }
        out
    }
}
