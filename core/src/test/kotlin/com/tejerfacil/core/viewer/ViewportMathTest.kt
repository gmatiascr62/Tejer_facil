package com.tejerfacil.core.viewer

import com.tejerfacil.core.geometry.DiagramTransform
import com.tejerfacil.core.geometry.NormRect
import com.tejerfacil.core.geometry.Rotation
import com.tejerfacil.core.testutil.TestSupport
import com.tejerfacil.core.vision.ImageOps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar

class ViewportMathTest {
    private val imgW = 1200
    private val imgH = 3000
    private val viewW = 1080f
    private val viewH = 2000f
    private val rows = (0 until 100).map { i -> NormRect(0.1, i * 0.01, 0.9, i * 0.01 + 0.01) }

    @Test
    fun laFilaActivaQuedaCentradaEnCualquierZoom() {
        for (scale in listOf(0.3f, 0.9f, 1f, 2.5f, 7f)) {
            for (i in listOf(0, 1, 37, 50, 98, 99)) {
                val vp = ViewportMath.centerOnRow(rows[i], imgW, imgH, viewW, viewH, Viewport(scale, 13f, -400f))
                val r = ViewportMath.rowOnScreen(rows[i], imgW, imgH, vp)
                assertEquals("zoom $scale fila $i", viewH / 2f, r.centerY, 0.5f)
                assertEquals("el zoom no cambia", scale, vp.scale)
            }
        }
    }

    @Test
    fun elResaltadoSigueAlDiagramaAlHacerZoom() {
        var vp = Viewport(1f, 0f, 0f)
        val row = rows[42]
        // Un punto del diagrama (esquina de la fila) bajo el foco del gesto.
        val focusX = 500f; val focusY = 900f
        val before = ViewportMath.screenToImageNorm(focusX, focusY, imgW, imgH, vp)
        for (f in listOf(1.7f, 0.6f, 3.1f)) {
            vp = ViewportMath.zoomAround(vp, f, focusX, focusY, minScale = 0.1f)
            val after = ViewportMath.screenToImageNorm(focusX, focusY, imgW, imgH, vp)
            assertEquals(before.first, after.first, 1e-4)
            assertEquals(before.second, after.second, 1e-4)
            // El rectángulo en pantalla corresponde exactamente a la fila escalada.
            val r = ViewportMath.rowOnScreen(row, imgW, imgH, vp)
            assertEquals((row.top * imgH).toFloat() * vp.scale + vp.offsetY, r.top, 0.01f)
            assertEquals((row.bottom - row.top).toFloat() * imgH * vp.scale, r.bottom - r.top, 0.01f)
        }
    }

    @Test
    fun tocarUnaFilaLaSelecciona() {
        val vp = Viewport(2.2f, -300f, -1500f)
        for (i in listOf(10, 55, 70)) {
            val r = ViewportMath.rowOnScreen(rows[i], imgW, imgH, vp)
            assertEquals(i, ViewportMath.hitTestRow(r.centerX, r.centerY, rows, imgW, imgH, vp))
        }
    }

    @Test
    fun hayEspacioParaCentrarPrimeraYUltimaFila() {
        val first = ViewportMath.centerOnRow(rows[0], imgW, imgH, viewW, viewH, Viewport(1f, 0f, 0f))
        val clamped = ViewportMath.clamp(first, imgW, imgH, viewW, viewH)
        assertEquals(first, clamped)
        val last = ViewportMath.centerOnRow(rows[99], imgW, imgH, viewW, viewH, Viewport(1f, 0f, 0f))
        assertEquals(last, ViewportMath.clamp(last, imgW, imgH, viewW, viewH))
        assertTrue(ViewportMath.rowOnScreen(rows[99], imgW, imgH, last).centerY in (viewH / 2 - 1)..(viewH / 2 + 1))
    }

    @Test
    fun rotacionDePuntosCoincideConOpenCv() {
        TestSupport.ensureOpenCv()
        val w = 7; val h = 4
        val m = Mat(h, w, CvType.CV_8UC1, Scalar(0.0))
        m.put(1, 5, byteArrayOf(9)) // x=5, y=1
        for (r in Rotation.entries) {
            val out = ImageOps.rotate(m, r)
            val p = DiagramTransform.rotatePoint(5.5, 1.5, r, w, h)
            val x = p.x.toInt(); val y = p.y.toInt()
            assertEquals("rotación $r", 9.0, out.get(y, x)[0], 0.0)
            out.release()
        }
    }
}
