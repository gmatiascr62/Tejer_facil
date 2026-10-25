package com.tejerfacil.core.vision

import com.tejerfacil.core.detect.DiagramDetector
import com.tejerfacil.core.testutil.ChartSpec
import com.tejerfacil.core.testutil.PdfFixtures
import com.tejerfacil.core.testutil.TestSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import kotlin.math.abs

class DeskewTest {
    companion object {
        @BeforeClass @JvmStatic
        fun setup() = TestSupport.ensureOpenCv()
    }

    @Test
    fun enderezaUnaFotoInclinadaYLuegoDetectaElDiagrama() {
        val fx = PdfFixtures.build(TestSupport.tmpDir, "inclinado", {
            chart(ChartSpec(20, 24, cell = 11f, seed = 31), 150f, 300f)
            paragraph(60f, 800f, 4)
        })
        val page = PdfFixtures.render(fx.file, 0, 150f)
        // Derecha: no hace falta enderezar.
        val a0 = Deskew.estimateAngle(page.mat)
        assertTrue("ángulo derecho ~0: $a0", a0 == null || abs(a0) < 0.3)
        assertNull(Deskew.straighten(page.mat))

        for (tilt in listOf(4.0, -7.0)) {
            // Simula una foto inclinada (giro antihorario positivo en OpenCV).
            val tilted = Deskew.rotate(page.mat, -tilt)
            // Inclinada así, la detección por líneas horizontales/verticales falla o empeora.
            val est = Deskew.estimateAngle(tilted)
            assertNotNull(est)
            assertEquals("estimación para $tilt°", tilt, est!!, 0.5)
            val fixed = Deskew.straighten(tilted)!!
            val res = DiagramDetector().detect(fixed)
            assertEquals("diagramas tras enderezar ($tilt°)", 1, res.diagrams.size)
            tilted.release(); fixed.release()
        }
    }
}
