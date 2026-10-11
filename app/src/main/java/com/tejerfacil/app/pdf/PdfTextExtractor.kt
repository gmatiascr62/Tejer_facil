package com.tejerfacil.app.pdf

import android.util.Log
import com.tejerfacil.core.text.GlyphMath
import com.tejerfacil.core.text.TextGlyph
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.Closeable
import java.io.File

/**
 * Extrae los caracteres con su posición y dirección (PdfBox-Android). Si el
 * PDF no tiene texto (gráficos como imagen) devuelve listas vacías y la
 * orientación y numeración se resuelven con OCR de dígitos.
 */
class PdfTextExtractor(file: File) : Closeable {
    private val doc: PDDocument? = try {
        PDDocument.load(file)
    } catch (t: Throwable) {
        Log.w("TejerFacil", "No se pudo abrir el PDF para extraer texto", t)
        null
    }

    fun glyphs(pageIndex: Int): List<TextGlyph> {
        val d = doc ?: return emptyList()
        return try {
            val out = ArrayList<TextGlyph>()
            val stripper = object : PDFTextStripper() {
                override fun processTextPosition(text: TextPosition) {
                    val u = text.unicode ?: return
                    if (text.pageWidth <= 0f || text.pageHeight <= 0f) return
                    out.add(
                        TextGlyph(
                            u,
                            GlyphMath.glyphBox(
                                text.x.toDouble(), text.y.toDouble(), text.widthDirAdj.toDouble(),
                                text.heightDir.toDouble(), text.dir.toInt(),
                                text.pageWidth.toDouble(), text.pageHeight.toDouble(),
                            ),
                            text.dir.toInt(),
                        )
                    )
                }
            }
            stripper.startPage = pageIndex + 1
            stripper.endPage = pageIndex + 1
            stripper.getText(d)
            out
        } catch (t: Throwable) {
            Log.w("TejerFacil", "Error extrayendo texto de la página $pageIndex", t)
            emptyList()
        }
    }

    override fun close() {
        runCatching { doc?.close() }
    }
}
