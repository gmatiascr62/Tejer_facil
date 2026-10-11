package com.tejerfacil.core.testutil

import com.tejerfacil.core.geometry.NormRect
import com.tejerfacil.core.text.GlyphMath
import com.tejerfacil.core.text.TextGlyph
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import org.apache.pdfbox.util.Matrix
import org.opencv.core.CvType
import org.opencv.core.Mat
import java.awt.image.BufferedImage
import java.io.File
import kotlin.math.PI
import kotlin.random.Random

/** Especificación de un gráfico sintético (unidades: puntos PDF). */
data class ChartSpec(
    val cols: Int,
    val rows: Int,
    val cell: Float = 10f,
    val cellAspect: Float = 1f, // ancho / alto
    /** Alturas individuales por fila (de abajo hacia arriba); null = todas iguales. */
    val rowHeights: List<Float>? = null,
    /** Rango de columnas [desde, hasta) presentes en cada fila (de abajo hacia arriba); null = todas. */
    val rowSpans: List<IntRange>? = null,
    val numbers: Boolean = true,
    val oddRowsRight: Boolean = true,
    val columnNumbers: Boolean = true,
    val boldEvery: Int = 10,
    val lineGray: Float = 0.35f,
    val seed: Int = 1,
    val title: String? = null,
) {
    val cellW: Float get() = cell * cellAspect
    fun heightOf(r: Int): Float = rowHeights?.get(r) ?: cell
    val totalHeight: Float get() = (0 until rows).sumOf { heightOf(it).toDouble() }.toFloat()
    val totalWidth: Float get() = cols * cellW
    fun span(r: Int): IntRange = rowSpans?.get(r) ?: (0 until cols)
}

/** Gráfico colocado en la página. rotationDeg: 0, 90 (antihorario), 180, 270. */
data class Placement(val spec: ChartSpec, val x: Float, val y: Float, val rotationDeg: Int = 0)

/** Resultado esperado de un gráfico colocado: rectángulo de la cuadrícula en la página (normalizado, origen arriba-izq). */
data class ExpectedChart(val grid: NormRect, val rotationDeg: Int, val spec: ChartSpec)

class PdfFixture(val file: File, val expected: List<List<ExpectedChart>>)

class RenderedPage(val mat: Mat, val glyphs: List<TextGlyph>, val image: BufferedImage)

object PdfFixtures {
    private val font = PDType1Font.HELVETICA
    private val bold = PDType1Font.HELVETICA_BOLD

    class PageBuilder {
        val charts = mutableListOf<Placement>()
        val paragraphs = mutableListOf<Triple<Float, Float, Int>>() // x, yTop, lines
        val photos = mutableListOf<FloatArray>() // x, y, w, h
        val tables = mutableListOf<FloatArray>() // x, y, cols, rows
        val legends = mutableListOf<FloatArray>() // x, yTop
        var landscape = false
        fun chart(spec: ChartSpec, x: Float, y: Float, rotationDeg: Int = 0) { charts.add(Placement(spec, x, y, rotationDeg)) }
        fun paragraph(x: Float, yTop: Float, lines: Int) { paragraphs.add(Triple(x, yTop, lines)) }
        fun photo(x: Float, y: Float, w: Float, h: Float) { photos.add(floatArrayOf(x, y, w, h)) }
        fun table(x: Float, y: Float, cols: Int, rows: Int) { tables.add(floatArrayOf(x, y, cols.toFloat(), rows.toFloat())) }
        fun legend(x: Float, yTop: Float) { legends.add(floatArrayOf(x, yTop)) }
    }

    fun build(dir: File, name: String, vararg pages: PageBuilder.() -> Unit): PdfFixture {
        val doc = PDDocument()
        val expected = mutableListOf<List<ExpectedChart>>()
        for (p in pages) {
            val pb = PageBuilder().apply(p)
            val rect = if (pb.landscape) PDRectangle(PDRectangle.A4.height, PDRectangle.A4.width) else PDRectangle.A4
            val page = PDPage(rect)
            doc.addPage(page)
            val cs = PDPageContentStream(doc, page)
            val exp = mutableListOf<ExpectedChart>()
            for (para in pb.paragraphs) drawParagraph(cs, para.first, para.second, para.third)
            for (t in pb.tables) drawTable(cs, t[0], t[1], t[2].toInt(), t[3].toInt())
            for (l in pb.legends) drawLegend(cs, l[0], l[1])
            for (ph in pb.photos) drawPhoto(doc, cs, ph[0], ph[1], ph[2], ph[3])
            for (c in pb.charts) {
                cs.saveGraphicsState()
                cs.transform(Matrix.getTranslateInstance(c.x, c.y))
                if (c.rotationDeg != 0) cs.transform(Matrix.getRotateInstance(c.rotationDeg * PI / 180, 0f, 0f))
                drawChart(cs, c.spec)
                cs.restoreGraphicsState()
                exp.add(ExpectedChart(gridRectOnPage(c, rect), c.rotationDeg, c.spec))
            }
            cs.close()
            expected.add(exp)
        }
        dir.mkdirs()
        val f = File(dir, "$name.pdf")
        doc.save(f)
        doc.close()
        return PdfFixture(f, expected)
    }

    private fun gridRectOnPage(c: Placement, page: PDRectangle): NormRect {
        val w = c.spec.totalWidth; val h = c.spec.totalHeight
        val corners = listOf(0f to 0f, w to 0f, 0f to h, w to h).map { (x, y) ->
            val r = c.rotationDeg * PI / 180
            val rx = x * kotlin.math.cos(r) - y * kotlin.math.sin(r)
            val ry = x * kotlin.math.sin(r) + y * kotlin.math.cos(r)
            (c.x + rx).toFloat() to (c.y + ry).toFloat()
        }
        val minX = corners.minOf { it.first }; val maxX = corners.maxOf { it.first }
        val minY = corners.minOf { it.second }; val maxY = corners.maxOf { it.second }
        return NormRect(
            (minX / page.width).toDouble(), (1 - maxY / page.height).toDouble(),
            (maxX / page.width).toDouble(), (1 - minY / page.height).toDouble(),
        )
    }

    /** Dibuja el gráfico con origen (0,0) en la esquina inferior izquierda de la cuadrícula. */
    fun drawChart(cs: PDPageContentStream, s: ChartSpec) {
        val rnd = Random(s.seed)
        val cw = s.cellW
        val ys = FloatArray(s.rows + 1)
        for (r in 0 until s.rows) ys[r + 1] = ys[r] + s.heightOf(r)
        // Celdas "sin punto" (fuera de la forma) en gris claro.
        // Símbolos.
        for (r in 0 until s.rows) {
            val span = s.span(r)
            for (c in span) {
                val x0 = c * cw; val y0 = ys[r]; val ch = s.heightOf(r)
                val cx = x0 + cw / 2; val cy = y0 + ch / 2
                cs.setStrokingColor(0f)
                cs.setNonStrokingColor(0f)
                cs.setLineWidth(0.7f)
                when (rnd.nextInt(7)) {
                    0 -> { cs.addRect(cx - cw * 0.12f, cy - ch * 0.12f, cw * 0.24f, ch * 0.24f); cs.fill() } // punto
                    1 -> { cs.moveTo(x0 + cw * 0.22f, y0 + ch * 0.22f); cs.lineTo(x0 + cw * 0.78f, y0 + ch * 0.78f); cs.stroke() } // k2tog
                    2 -> { cs.moveTo(x0 + cw * 0.25f, cy); cs.lineTo(x0 + cw * 0.75f, cy); cs.stroke() } // revés
                    3 -> { // lazada (círculo aproximado)
                        val rr = cw * 0.25f
                        cs.moveTo(cx + rr, cy)
                        val k = 0.552f * rr
                        cs.curveTo(cx + rr, cy + k, cx + k, cy + rr, cx, cy + rr)
                        cs.curveTo(cx - k, cy + rr, cx - rr, cy + k, cx - rr, cy)
                        cs.curveTo(cx - rr, cy - k, cx - k, cy - rr, cx, cy - rr)
                        cs.curveTo(cx + k, cy - rr, cx + rr, cy - k, cx + rr, cy)
                        cs.stroke()
                    }
                    4 -> { cs.moveTo(cx, y0 + ch * 0.22f); cs.lineTo(cx, y0 + ch * 0.78f); cs.stroke() } // derecho
                    else -> {} // vacío
                }
            }
        }
        // Cuadrícula: verticales y horizontales por fila (soporta formas irregulares).
        cs.setStrokingColor(s.lineGray)
        for (r in 0 until s.rows) {
            val span = s.span(r)
            val ch = s.heightOf(r)
            for (c in span.first..span.last + 1) {
                cs.setLineWidth(if (s.boldEvery > 0 && c % s.boldEvery == 0) 1.1f else 0.4f)
                cs.moveTo(c * cw, ys[r]); cs.lineTo(c * cw, ys[r] + ch); cs.stroke()
            }
        }
        for (r in 0..s.rows) {
            val below = if (r > 0) s.span(r - 1) else null
            val above = if (r < s.rows) s.span(r) else null
            val from = minOf(below?.first ?: Int.MAX_VALUE, above?.first ?: Int.MAX_VALUE)
            val to = maxOf(below?.last ?: Int.MIN_VALUE, above?.last ?: Int.MIN_VALUE) + 1
            cs.setLineWidth(if (s.boldEvery > 0 && r % s.boldEvery == 0) 1.1f else 0.4f)
            cs.moveTo(from * cw, ys[r]); cs.lineTo(to * cw, ys[r]); cs.stroke()
        }
        cs.setNonStrokingColor(0f)
        val fs = minOf(s.cell * 0.75f, 8f)
        if (s.numbers) {
            for (r in 0 until s.rows) {
                val n = r + 1
                val span = s.span(r)
                val right = (n % 2 == 1) == s.oddRowsRight
                val txt = n.toString()
                val tw = font.getStringWidth(txt) / 1000 * fs
                val x = if (right) (span.last + 1) * cw + 2f else span.first * cw - 2f - tw
                text(cs, txt, x, ys[r] + (s.heightOf(r) - fs * 0.7f) / 2, fs)
            }
        }
        if (s.columnNumbers) {
            for (c in 0 until s.cols) {
                val n = s.cols - c
                val txt = n.toString()
                val tw = font.getStringWidth(txt) / 1000 * fs
                text(cs, txt, c * cw + (cw - tw) / 2, -fs - 2f, fs)
            }
        }
        if (s.title != null) text(cs, s.title, 0f, s.totalHeight + 6f, 9f, bold)
    }

    private fun text(cs: PDPageContentStream, t: String, x: Float, y: Float, size: Float, f: PDType1Font = font) {
        cs.beginText()
        cs.setFont(f, size)
        cs.newLineAtOffset(x, y)
        cs.showText(t)
        cs.endText()
    }

    private val loremWords = ("Tejer con agujas circulares de cuatro milímetros montar puntos y continuar en punto jersey " +
        "hasta obtener la medida deseada luego disminuir según el gráfico repitiendo la secuencia indicada en cada vuelta").split(" ")

    fun drawParagraph(cs: PDPageContentStream, x: Float, yTop: Float, lines: Int) {
        val rnd = Random(lines + x.toInt())
        for (i in 0 until lines) {
            val words = (0 until 11).map { loremWords[rnd.nextInt(loremWords.size)] }.joinToString(" ")
            text(cs, words, x, yTop - i * 12f, 9.5f)
        }
    }

    fun drawTable(cs: PDPageContentStream, x: Float, y: Float, cols: Int, rows: Int) {
        val cw = 62f; val ch = 16f
        cs.setStrokingColor(0f); cs.setLineWidth(0.5f)
        for (c in 0..cols) { cs.moveTo(x + c * cw, y); cs.lineTo(x + c * cw, y + rows * ch); cs.stroke() }
        for (r in 0..rows) { cs.moveTo(x, y + r * ch); cs.lineTo(x + cols * cw, y + r * ch); cs.stroke() }
        for (r in 0 until rows) for (c in 0 until cols) text(cs, if (c == 0) "Talla ${r + 1}" else "${40 + r * 4 + c} cm", x + c * cw + 4, y + r * ch + 4, 8f)
    }

    fun drawLegend(cs: PDPageContentStream, x: Float, yTop: Float) {
        val names = listOf("Derecho", "Revés", "Lazada", "Dos juntos")
        cs.setStrokingColor(0.3f); cs.setLineWidth(0.4f)
        for ((i, n) in names.withIndex()) {
            val y = yTop - i * 16f
            cs.addRect(x, y, 10f, 10f); cs.stroke()
            text(cs, n, x + 16f, y + 2f, 9f)
        }
    }

    fun drawPhoto(doc: PDDocument, cs: PDPageContentStream, x: Float, y: Float, w: Float, h: Float) {
        val img = BufferedImage(300, 220, BufferedImage.TYPE_INT_RGB)
        val rnd = Random(7)
        for (yy in 0 until img.height) for (xx in 0 until img.width) {
            // Textura tipo "tejido": ondas + ruido, tonos medios.
            val v = (128 + 50 * kotlin.math.sin(xx / 3.0) * kotlin.math.cos(yy / 4.0) + rnd.nextInt(-40, 40)).toInt().coerceIn(0, 255)
            img.setRGB(xx, yy, (v shl 16) or ((v * 0.9).toInt() shl 8) or (v * 0.8).toInt())
        }
        val xo = LosslessFactory.createFromImage(doc, img)
        cs.drawImage(xo, x, y, w, h)
    }

    // ---- Renderizado y extracción ----

    fun render(file: File, pageIndex: Int, dpi: Float = 200f): RenderedPage {
        PDDocument.load(file).use { doc ->
            val img = PDFRenderer(doc).renderImageWithDPI(pageIndex, dpi, ImageType.RGB)
            val glyphs = extractGlyphs(doc, pageIndex)
            return RenderedPage(toMat(img), glyphs, img)
        }
    }

    fun extractGlyphs(doc: PDDocument, pageIndex: Int): List<TextGlyph> {
        val out = mutableListOf<TextGlyph>()
        val stripper = object : PDFTextStripper() {
            override fun processTextPosition(text: TextPosition) {
                out.add(
                    TextGlyph(
                        text.unicode ?: "",
                        GlyphMath.glyphBox(
                            text.x.toDouble(), text.y.toDouble(), text.widthDirAdj.toDouble(), text.heightDir.toDouble(),
                            text.dir.toInt(), text.pageWidth.toDouble(), text.pageHeight.toDouble(),
                        ),
                        text.dir.toInt(),
                    )
                )
            }
        }
        stripper.startPage = pageIndex + 1
        stripper.endPage = pageIndex + 1
        stripper.getText(doc)
        return out
    }

    fun toMat(img: BufferedImage): Mat {
        val w = img.width; val h = img.height
        val px = img.getRGB(0, 0, w, h, null, 0, w)
        val bytes = ByteArray(w * h * 3)
        for (i in px.indices) {
            val p = px[i]
            bytes[i * 3] = (p and 0xff).toByte()
            bytes[i * 3 + 1] = ((p shr 8) and 0xff).toByte()
            bytes[i * 3 + 2] = ((p shr 16) and 0xff).toByte()
        }
        val m = Mat(h, w, CvType.CV_8UC3)
        m.put(0, 0, bytes)
        return m
    }
}
