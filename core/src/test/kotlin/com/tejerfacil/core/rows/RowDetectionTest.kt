package com.tejerfacil.core.rows

import com.tejerfacil.core.detect.DiagramDetector
import com.tejerfacil.core.geometry.DiagramTransform
import com.tejerfacil.core.geometry.Rotation
import com.tejerfacil.core.testutil.ChartSpec
import com.tejerfacil.core.testutil.PdfFixtures
import com.tejerfacil.core.testutil.Placement
import com.tejerfacil.core.testutil.TestSupport
import com.tejerfacil.core.vision.ImageOps
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import kotlin.math.abs

class RowDetectionTest {

    companion object {
        @BeforeClass @JvmStatic
        fun setup() = TestSupport.ensureOpenCv()
        const val DPI = 200f
    }

    /** Detecta el diagrama y sus filas; devuelve también la transformación página->diagrama. */
    private fun run(name: String, spec: ChartSpec, x: Float, y: Float): Triple<RowDetectionResult, DiagramTransform, Placement> {
        val fx = PdfFixtures.build(TestSupport.tmpDir, name, { chart(spec, x, y) })
        val page = PdfFixtures.render(fx.file, 0, DPI)
        val res = DiagramDetector().detect(page.mat, page.glyphs)
        val d = res.diagrams.single()
        val norm = d.cropNorm(res.width, res.height)
        val box = norm.toPixels(res.width, res.height)
        val crop = ImageOps.cropRotate(page.mat, norm, Rotation.R0)
        val rows = RowDetector().detect(crop, RowDetector.CellHint(d.cellWidth, d.cellHeight))
        TestSupport.saveRows(name, crop, rows.rows)
        crop.release()
        return Triple(rows, DiagramTransform(norm, Rotation.R0, box.width, box.height), Placement(spec, x, y))
    }

    /** Posición esperada (px de la imagen del diagrama) del borde inferior/superior de la fila r. */
    private fun expectedRow(p: Placement, r: Int, t: DiagramTransform): Pair<Double, Double> {
        val s = p.spec
        var y0 = 0f
        for (i in 0 until r) y0 += s.heightOf(i)
        val y1 = y0 + s.heightOf(r)
        val ph = PDRectangle.A4.height
        val topNorm = 1.0 - (p.y + y1) / ph
        val botNorm = 1.0 - (p.y + y0) / ph
        val top = t.pageToDiagram(t.crop.left + 0.0001, topNorm).y
        val bottom = t.pageToDiagram(t.crop.left + 0.0001, botNorm).y
        return top to bottom
    }

    private fun expectedX(p: Placement, col: Int, t: DiagramTransform): Double {
        val xn = (p.x + col * p.spec.cellW) / PDRectangle.A4.width
        return t.pageToDiagram(xn.toDouble(), t.crop.top + 0.0001).x
    }

    @Test
    fun filasConAlturasDiferentes() {
        val heights = listOf(10f, 10f, 16f, 10f, 22f, 10f, 13f, 10f, 10f, 18f, 10f, 12f)
        val spec = ChartSpec(14, heights.size, cell = 10f, rowHeights = heights, seed = 11)
        val (res, t, p) = run("alturas", spec, 150f, 300f)
        assertEquals("cantidad de filas", heights.size, res.rows.size)
        val pxPerPt = DPI / 72.0
        for ((i, row) in res.rows.withIndex()) {
            val (top, bottom) = expectedRow(p, i, t)
            assertTrue("fila ${i + 1}: arriba ${row.top} vs $top", abs(row.top - top) <= 3.5)
            assertTrue("fila ${i + 1}: abajo ${row.bottom} vs $bottom", abs(row.bottom - bottom) <= 3.5)
            assertEquals("altura de la fila ${i + 1}", heights[i] * pxPerPt, row.height, 4.0)
        }
    }

    @Test
    fun diagramaIrregularTriangularConAumentos() {
        // Forma triangular: cada fila es 1 punto más angosta de cada lado.
        val rows = 10
        val spans = (0 until rows).map { r -> r until (20 - r) }
        val spec = ChartSpec(20, rows, cell = 10f, rowSpans = spans, seed = 12)
        val (res, t, p) = run("triangulo", spec, 120f, 400f)
        assertEquals(rows, res.rows.size)
        for ((i, row) in res.rows.withIndex()) {
            val left = expectedX(p, spans[i].first, t)
            val right = expectedX(p, spans[i].last + 1, t)
            assertTrue("fila ${i + 1}: izquierda ${row.left} vs $left", abs(row.left - left) <= 4)
            assertTrue("fila ${i + 1}: derecha ${row.right} vs $right", abs(row.right - right) <= 4)
        }
    }

    @Test
    fun diagramaConHuecoYFormaEscalonada() {
        // Disminuciones de a 2 puntos en un solo lado a partir de la fila 9 (sisa).
        val rows = 14
        val spans = (0 until rows).map { r -> if (r < 8) 0 until 24 else ((r - 7) * 2) until 24 }
        val spec = ChartSpec(24, rows, cell = 9f, rowSpans = spans, seed = 13)
        val (res, _, _) = run("escalonado", spec, 140f, 380f)
        assertEquals(rows, res.rows.size)
        // Sin filas inventadas: todas con altura similar.
        val med = res.rows.map { it.height }.sorted()[rows / 2]
        assertTrue(res.rows.all { abs(it.height - med) < 0.2 * med })
    }

    @Test
    fun ordenDeLecturaPorNumeracion() {
        val rowsTopDown = listOf(
            DetectedRow(0.0, 10.0, 0.0, 100.0, label = 3),
            DetectedRow(10.0, 20.0, 0.0, 100.0, label = 2),
            DetectedRow(20.0, 30.0, 0.0, 100.0, label = 1),
        )
        assertEquals(ReadingOrder.BOTTOM_UP, RowDetector.readingOrder(rowsTopDown))
        val inverted = rowsTopDown.mapIndexed { i, r -> r.copy(label = i + 1) }
        assertEquals(ReadingOrder.TOP_DOWN, RowDetector.readingOrder(inverted))
        // Sin números: convención de tejido (de abajo hacia arriba).
        assertEquals(ReadingOrder.BOTTOM_UP, RowDetector.readingOrder(rowsTopDown.map { it.copy(label = null) }))
    }

    @Test
    fun noAsignaNumerosIncoherentes() {
        val rows = (0 until 6).map { i -> DetectedRow(i * 10.0, i * 10.0 + 10, 50.0, 150.0) }
        // Números a la derecha de cada fila (6 arriba ... 1 abajo) y un número de columna "40" mal ubicado.
        val labels = (0 until 6).map { i -> NumberLabel(6 - i, com.tejerfacil.core.geometry.IntBox(155, i * 10 + 2, 165, i * 10 + 8)) } +
            NumberLabel(40, com.tejerfacil.core.geometry.IntBox(30, 22, 45, 28))
        val out = RowDetector.assignLabels(rows, labels.filter { it.value != 4 } + labels.last(), 10.0)
        assertEquals(listOf(6, 5, null, 3, 2, 1), out.map { it.label })
    }
}
