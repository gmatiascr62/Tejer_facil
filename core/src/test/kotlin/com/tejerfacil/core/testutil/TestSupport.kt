package com.tejerfacil.core.testutil

import com.tejerfacil.core.detect.DetectedDiagram
import com.tejerfacil.core.geometry.IntBox
import com.tejerfacil.core.rows.DetectedRow
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import java.nio.file.Files

object TestSupport {
    init {
        nu.pattern.OpenCV.loadLocally()
    }

    fun ensureOpenCv() = Unit

    val tmpDir: File by lazy { Files.createTempDirectory("tejer-test").toFile() }

    /** Carpeta de imágenes de depuración (-DdebugOut=/ruta), o null. */
    val debugDir: File? by lazy {
        System.getProperty("debugOut")?.takeIf { it.isNotBlank() }?.let { File(it).apply { mkdirs() } }
    }

    fun saveDetection(name: String, page: Mat, diagrams: List<DetectedDiagram>, rejected: List<Pair<IntBox, String>> = emptyList()) {
        val dir = debugDir ?: return
        val out = page.clone()
        for (r in rejected) rect(out, r.first, Scalar(0.0, 0.0, 255.0), 2)
        for (d in diagrams) {
            rect(out, d.gridBox, Scalar(255.0, 0.0, 0.0), 2)
            rect(out, d.box, Scalar(0.0, 180.0, 0.0), 3)
            Imgproc.putText(out, "${d.rotation.degrees}", Point(d.box.left + 5.0, d.box.top + 30.0), Imgproc.FONT_HERSHEY_SIMPLEX, 1.0, Scalar(0.0, 120.0, 255.0), 2)
        }
        Imgcodecs.imwrite(File(dir, "$name.png").absolutePath, out)
        out.release()
    }

    fun saveRows(name: String, img: Mat, rows: List<DetectedRow>) {
        val dir = debugDir ?: return
        val out = Mat()
        if (img.channels() == 1) Imgproc.cvtColor(img, out, Imgproc.COLOR_GRAY2BGR) else img.copyTo(out)
        for ((i, r) in rows.withIndex()) {
            val color = if (i % 2 == 0) Scalar(0.0, 200.0, 255.0) else Scalar(255.0, 120.0, 0.0)
            Imgproc.rectangle(out, Point(r.left, r.top), Point(r.right, r.bottom), color, 1)
            Imgproc.putText(out, "${i + 1}${r.label?.let { ":$it" } ?: ""}", Point(r.right + 2, r.bottom - 2), Imgproc.FONT_HERSHEY_PLAIN, 0.8, Scalar(0.0, 0.0, 255.0), 1)
        }
        Imgcodecs.imwrite(File(dir, "$name.png").absolutePath, out)
        out.release()
    }

    private fun rect(m: Mat, b: IntBox, c: Scalar, t: Int) =
        Imgproc.rectangle(m, Point(b.left.toDouble(), b.top.toDouble()), Point(b.right.toDouble(), b.bottom.toDouble()), c, t)
}
