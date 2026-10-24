package com.tejerfacil.core.rows

import com.tejerfacil.core.detect.DiagramDetector
import com.tejerfacil.core.geometry.Rotation
import com.tejerfacil.core.testutil.ChartSpec
import com.tejerfacil.core.testutil.PdfFixtures
import com.tejerfacil.core.testutil.TestSupport
import com.tejerfacil.core.vision.ImageOps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import kotlin.math.abs

class CellGridTest {
    companion object {
        @BeforeClass @JvmStatic
        fun setup() = TestSupport.ensureOpenCv()
    }

    @Test
    fun ubicaLaCeldaTocadaEnFormaIrregular() {
        val rows = 8
        val spans = (0 until rows).map { r -> r until (16 - r) }
        val spec = ChartSpec(16, rows, cell = 12f, rowSpans = spans, seed = 21)
        val fx = PdfFixtures.build(TestSupport.tmpDir, "celdas", { chart(spec, 150f, 400f) })
        val page = PdfFixtures.render(fx.file, 0, 200f)
        val res = DiagramDetector().detect(page.mat, page.glyphs)
        val d = res.diagrams.single()
        val crop = ImageOps.cropRotate(page.mat, d.cropNorm(res.width, res.height), Rotation.R0)
        val det = RowDetector().detect(crop, RowDetector.CellHint(d.cellWidth, d.cellHeight))
        val grid = CellGrid.build(crop, det.rows)
        val cellPx = 12 * 200 / 72.0

        // Fila inferior (la más ancha): 16 celdas.
        val bottom = det.rows.first() // orden de lectura: abajo primero
        val cy = bottom.centerY
        val cellsFound = (0 until 16).mapNotNull { i -> grid.cellAt(bottom.left + (i + 0.5) * cellPx, cy) }
        assertEquals(16, cellsFound.size)
        for (c in cellsFound) {
            assertTrue("ancho ${c.width} ~ $cellPx", abs(c.width - cellPx) < 4)
            assertTrue("alto ${c.height} ~ $cellPx", abs(c.height - cellPx) < 4)
        }
        // Celdas contiguas no se superponen y comparten borde.
        for ((a, b) in cellsFound.zipWithNext()) assertTrue(abs(a.right - b.left) <= 1)

        // Fila superior (más angosta): un toque fuera de su ancho no es una celda.
        val top = det.rows.last()
        assertNull(grid.cellAt(bottom.left + 0.5 * cellPx, top.centerY))
        assertNotNull(grid.cellAt((top.left + top.right) / 2, top.centerY))

        // El interior deja intactas las líneas.
        val inner = CellGrid.interior(cellsFound[0])
        assertTrue(inner.left > cellsFound[0].left && inner.right < cellsFound[0].right)
        crop.release()
    }
}
