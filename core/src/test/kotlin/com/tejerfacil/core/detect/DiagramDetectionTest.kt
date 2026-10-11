package com.tejerfacil.core.detect

import com.tejerfacil.core.DiagramPipeline
import com.tejerfacil.core.geometry.DiagramTransform
import com.tejerfacil.core.geometry.NormRect
import com.tejerfacil.core.geometry.Rotation
import com.tejerfacil.core.rows.LabelMapper
import com.tejerfacil.core.testutil.ChartSpec
import com.tejerfacil.core.testutil.PdfFixtures
import com.tejerfacil.core.testutil.TestSupport
import com.tejerfacil.core.text.GlyphMath
import com.tejerfacil.core.vision.ImageOps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

class DiagramDetectionTest {

    companion object {
        @BeforeClass @JvmStatic
        fun setup() = TestSupport.ensureOpenCv()

        fun iou(a: NormRect, b: NormRect): Double {
            val ix = max(0.0, min(a.right, b.right) - max(a.left, b.left))
            val iy = max(0.0, min(a.bottom, b.bottom) - max(a.top, b.top))
            val inter = ix * iy
            return inter / (a.width * a.height + b.width * b.height - inter)
        }

        fun contains(outer: NormRect, inner: NormRect, tol: Double = 0.004) =
            outer.left <= inner.left + tol && outer.top <= inner.top + tol &&
                outer.right >= inner.right - tol && outer.bottom >= inner.bottom - tol
    }

    private val detector = DiagramDetector()

    @Test
    fun pdfConUnSoloDiagrama() {
        val spec = ChartSpec(cols = 24, rows = 30, cell = 9f, title = "Gráfico A")
        val fx = PdfFixtures.build(TestSupport.tmpDir, "uno", {
            paragraph(60f, 800f, 8)
            chart(spec, 150f, 200f)
            paragraph(60f, 150f, 6)
        })
        val page = PdfFixtures.render(fx.file, 0)
        val res = detector.detect(page.mat, page.glyphs)
        TestSupport.saveDetection("uno", page.mat, res.diagrams, res.rejected)
        assertEquals("cantidad de diagramas", 1, res.diagrams.size)
        val d = res.diagrams[0]
        val exp = fx.expected[0][0].grid
        assertTrue("la cuadrícula coincide (IoU=${iou(d.gridNorm(res.width, res.height), exp)})", iou(d.gridNorm(res.width, res.height), exp) > 0.9)
        // El recorte conserva números de fila (a ambos lados) y de columna (abajo).
        val crop = d.cropNorm(res.width, res.height)
        val numbers = GlyphMath.groupWords(page.glyphs).filter { it.isNumber }
        val inside = numbers.count { contains(crop, it.box) }
        assertTrue("números dentro del recorte: $inside de ${numbers.size}", inside >= numbers.size - 1)
        // No incluye el texto de los párrafos.
        assertTrue("no incluye párrafos", crop.top > 0.2 && crop.bottom < 0.85)
        assertEquals(Rotation.R0, d.rotation)
    }

    @Test
    fun pdfConVariosDiagramasTablaFotoYLeyenda() {
        val fx = PdfFixtures.build(TestSupport.tmpDir, "varios", {
            chart(ChartSpec(14, 18, cell = 9f, seed = 1), 60f, 560f)
            chart(ChartSpec(18, 12, cell = 10f, seed = 2), 330f, 600f)
            chart(ChartSpec(10, 10, cell = 11f, seed = 3), 70f, 300f)
            chart(ChartSpec(20, 16, cell = 8f, seed = 4, rowSpans = (0 until 16).map { r -> (r / 3) until (20 - r / 3) }), 320f, 330f)
            table(60f, 120f, 5, 5)
            photo(400f, 90f, 150f, 110f)
            legend(380f, 260f)
            paragraph(60f, 820f, 3)
        })
        val page = PdfFixtures.render(fx.file, 0)
        val res = detector.detect(page.mat, page.glyphs)
        TestSupport.saveDetection("varios", page.mat, res.diagrams, res.rejected)
        assertEquals("cantidad de diagramas: ${res.diagrams.map { it.gridBox }}", 4, res.diagrams.size)
        for (e in fx.expected[0]) {
            val best = res.diagrams.maxOf { iou(it.gridNorm(res.width, res.height), e.grid) }
            assertTrue("diagrama esperado encontrado (IoU=$best)", best > 0.85)
        }
    }

    @Test
    fun diagramasGiradosConTextoDelPdf() {
        val fx = PdfFixtures.build(TestSupport.tmpDir, "girados", {
            chart(ChartSpec(26, 12, cell = 10f, seed = 5), 230f, 60f, rotationDeg = 90)
            chart(ChartSpec(26, 12, cell = 10f, seed = 6), 360f, 760f, rotationDeg = 270)
        })
        val page = PdfFixtures.render(fx.file, 0)
        val res = detector.detect(page.mat, page.glyphs)
        TestSupport.saveDetection("girados", page.mat, res.diagrams, res.rejected)
        assertEquals(2, res.diagrams.size)
        val byExpected = fx.expected[0].map { e -> res.diagrams.maxBy { iou(it.gridNorm(res.width, res.height), e.grid) } to e }
        for ((d, e) in byExpected) {
            // Dibujado girado 90° antihorario -> hay que girarlo 90° horario.
            val want = if (e.rotationDeg == 90) Rotation.R90 else Rotation.R270
            assertEquals("rotación para el gráfico girado ${e.rotationDeg}°", want, d.rotation)
        }
    }

    @Test
    fun diagramasGiradosSinTextoUsanOcrDeDigitos() {
        // Simula un PDF escaneado / con el gráfico como imagen: sin glifos.
        val spec = ChartSpec(26, 12, cell = 10f, seed = 7)
        val fx = PdfFixtures.build(TestSupport.tmpDir, "girados_img", {
            chart(spec, 230f, 60f, rotationDeg = 90)
            chart(spec.copy(seed = 8), 360f, 760f, rotationDeg = 270)
        })
        val page = PdfFixtures.render(fx.file, 0)
        val res = detector.detect(page.mat, emptyList())
        assertEquals(2, res.diagrams.size)
        val pipeline = DiagramPipeline()
        for (e in fx.expected[0]) {
            val d = res.diagrams.maxBy { iou(it.gridNorm(res.width, res.height), e.grid) }
            val crop = ImageOps.cropRotate(page.mat, d.cropNorm(res.width, res.height), Rotation.R0)
            val out = pipeline.process(crop, d.cellWidth, d.cellHeight, null)
            val want = if (e.rotationDeg == 90) Rotation.R90 else Rotation.R270
            assertEquals("OCR: ${out.orientation.reason}", want, out.orientation.rotation)
            // Ya derecho: 12 filas y números 1..12 de abajo hacia arriba.
            assertEquals(12, out.rows.rows.size)
            val labels = out.rows.rows.map { it.label }
            assertTrue("números leídos por OCR: $labels", labels.count { it != null } >= 9)
            labels.forEachIndexed { i, l -> if (l != null) assertEquals(i + 1, l) }
            crop.release(); out.image.release()
        }
    }

    @Test
    fun filasDeUnDiagramaConNumeracionDelPdf() {
        val spec = ChartSpec(16, 20, cell = 10f, seed = 9)
        val fx = PdfFixtures.build(TestSupport.tmpDir, "filas", { chart(spec, 180f, 300f) })
        val page = PdfFixtures.render(fx.file, 0)
        val res = detector.detect(page.mat, page.glyphs)
        val d = res.diagrams.single()
        val cropNorm = d.cropNorm(res.width, res.height)
        val crop = ImageOps.cropRotate(page.mat, cropNorm, d.rotation)
        val words = GlyphMath.groupWords(page.glyphs)
        val t = DiagramTransform(cropNorm, d.rotation, cropNorm.toPixels(res.width, res.height).width, cropNorm.toPixels(res.width, res.height).height)
        val out = DiagramPipeline().process(
            crop, d.cellWidth, d.cellHeight,
            OrientationResult(d.rotation, 1.0, "pdf", OrientationSource.PDF_TEXT),
            pdfLabels = { LabelMapper.labelsForDiagram(words, t) },
        )
        TestSupport.saveRows("filas", out.image, out.rows.rows)
        assertEquals(20, out.rows.rows.size)
        assertEquals((1..20).toList(), out.rows.rows.map { it.label })
        // Orden de lectura: fila 1 abajo.
        assertTrue(out.rows.rows.first().top > out.rows.rows.last().top)
    }
}
