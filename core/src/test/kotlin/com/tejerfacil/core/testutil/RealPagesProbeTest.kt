package com.tejerfacil.core.testutil

import com.tejerfacil.core.DiagramPipeline
import com.tejerfacil.core.detect.DiagramDetector
import com.tejerfacil.core.detect.OrientationResult
import com.tejerfacil.core.detect.OrientationSource
import org.junit.Assume
import org.junit.Test
import org.opencv.imgcodecs.Imgcodecs
import java.io.File

/**
 * Sonda manual sobre páginas reales (no versionadas). Ejecutar con
 * -DrealPages=/carpeta/200ppp -DdebugOut=/salida [-DrealFilter=nombre]
 * Si existe una carpeta hermana "real400" con las mismas páginas a 400 ppp,
 * los recortes se toman de ahí (como hace la app al re-renderizar).
 */
class RealPagesProbeTest {
    @Test
    fun probe() {
        TestSupport.ensureOpenCv()
        val dir = System.getProperty("realPages")?.takeIf { it.isNotBlank() }?.let(::File)
        Assume.assumeTrue(dir != null && dir.isDirectory)
        val hiDir = File(dir!!.parentFile, "real400")
        val filter = System.getProperty("realFilter") ?: ""
        val det = DiagramDetector()
        val pipeline = DiagramPipeline()
        for (f in dir.listFiles()!!.filter { it.name.endsWith(".png") && it.name.contains(filter) }.sortedBy { it.name }) {
            val m0 = Imgcodecs.imread(f.absolutePath)
            val m = if (System.getProperty("deskew") == "1") (com.tejerfacil.core.vision.Deskew.straighten(m0)?.also { m0.release() } ?: m0) else m0
            val t0 = System.currentTimeMillis()
            val dbg = TestSupport.debugDir
            val res = det.detect(m, debug = if (dbg != null && System.getProperty("realMasks") == "1") { n, mat -> Imgcodecs.imwrite(File(dbg, "${f.nameWithoutExtension}_$n.png").absolutePath, mat) } else null)
            val t1 = System.currentTimeMillis()
            println("${f.name}: ${res.diagrams.size} diagramas (${t1 - t0} ms) inclinación=${com.tejerfacil.core.vision.Deskew.estimateAngle(m)}")
            TestSupport.saveDetection(f.nameWithoutExtension, m, res.diagrams, res.rejected)
            val hiFile = File(hiDir, f.name)
            val hi = if (hiFile.exists()) Imgcodecs.imread(hiFile.absolutePath) else m
            val k = hi.cols().toDouble() / m.cols()
            for ((i, d) in res.diagrams.withIndex()) {
                val norm = d.cropNorm(res.width, res.height)
                val crop = com.tejerfacil.core.vision.ImageOps.cropRotate(hi, norm, com.tejerfacil.core.geometry.Rotation.R0)
                val initial = OrientationResult(d.rotation, d.rotationConfidence, d.notes.last(), OrientationSource.LAYOUT)
                val t2 = System.currentTimeMillis()
                val out = pipeline.process(crop, d.cellWidth * k, d.cellHeight * k, initial)
                val t3 = System.currentTimeMillis()
                val r = out.rows
                val labeled = r.rows.count { it.label != null }
                println("   notas=${d.notes} conf=${d.confidence}"); println("   #$i rot=${out.orientation.rotation} (${"%.2f".format(out.orientation.confidence)}, ${out.orientation.source}) ${out.orientation.reason}")
                println("      filas=${r.rows.size} numeradas=$labeled orden=${r.readingOrder} método=${r.method} conf=${"%.2f".format(r.confidence)} (${t3 - t2} ms) etiquetas=${out.labels.size}")
                println("      alturas=${r.rows.map { it.height.toInt() }} celda=${"%.1f".format(r.cellWidth)}x${"%.1f".format(r.cellHeight)}")
                println("      primeras=${r.rows.take(6).map { it.label }} últimas=${r.rows.takeLast(4).map { it.label }}")
                if (System.getProperty("cellProbe") == "1") {
                    val grid = com.tejerfacil.core.rows.CellGrid.build(out.image, r.rows)
                    println("      celdas por fila (primeras 5): " + r.rows.take(5).map { row ->
                        grid.rows.firstOrNull { kotlin.math.abs(it.top - row.top) < 1 }?.xs?.size?.minus(1) })
                    TestSupport.debugDir?.let { dir ->
                        val m = out.image.clone()
                        for (gr in grid.rows) for (x in gr.xs) org.opencv.imgproc.Imgproc.line(m, org.opencv.core.Point(x, gr.top), org.opencv.core.Point(x, gr.bottom), org.opencv.core.Scalar(0.0, 0.0, 255.0), 2)
                        org.opencv.imgcodecs.Imgcodecs.imwrite(File(dir, "${f.nameWithoutExtension}_d${i}_cells.png").absolutePath, m)
                        m.release()
                    }
                }
                TestSupport.saveRows("${f.nameWithoutExtension}_d$i", out.image, r.rows)
                crop.release(); out.image.release()
            }
            if (hi !== m) hi.release()
            m.release()
        }
    }
}
