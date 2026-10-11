package com.tejerfacil.core.vision

import com.tejerfacil.core.geometry.IntBox
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Utilidades básicas de procesamiento de imagen sobre OpenCV. */
object Vision {

    /** Convierte a escala de grises (acepta 1, 3 o 4 canales; RGBA de Android o BGR). */
    fun toGray(src: Mat): Mat {
        val gray = Mat()
        when (src.channels()) {
            1 -> src.copyTo(gray)
            3 -> Imgproc.cvtColor(src, gray, Imgproc.COLOR_BGR2GRAY)
            4 -> Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)
            else -> throw IllegalArgumentException("Canales no soportados: ${src.channels()}")
        }
        return gray
    }

    /**
     * Máscara de tinta (255 = tinta) por umbral adaptativo: detecta líneas finas
     * y grises claros. Las zonas rellenas (barras de color, celdas sombreadas)
     * quedan como contornos, que es justamente lo que delimita las celdas.
     */
    fun inkMask(gray: Mat): Mat {
        val minDim = min(gray.cols(), gray.rows())
        var block = max(15, minDim / 50)
        if (block % 2 == 0) block++
        val ink = Mat()
        Imgproc.adaptiveThreshold(
            gray, ink, 255.0, Imgproc.ADAPTIVE_THRESH_MEAN_C, Imgproc.THRESH_BINARY_INV, block, 10.0
        )
        return ink
    }

    fun open(src: Mat, kw: Int, kh: Int): Mat {
        val k = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(max(1, kw).toDouble(), max(1, kh).toDouble()))
        val dst = Mat()
        Imgproc.morphologyEx(src, dst, Imgproc.MORPH_OPEN, k)
        k.release()
        return dst
    }

    fun dilate(src: Mat, kw: Int, kh: Int): Mat {
        val k = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(max(1, kw).toDouble(), max(1, kh).toDouble()))
        val dst = Mat()
        Imgproc.dilate(src, dst, k)
        k.release()
        return dst
    }

    /** Lee una máscara CV_8UC1 a un arreglo de bytes (fila por fila). */
    fun toBytes(m: Mat): ByteArray {
        require(m.type() == CvType.CV_8UC1)
        val cont = if (m.isContinuous) m else m.clone()
        val b = ByteArray(cont.cols() * cont.rows())
        cont.get(0, 0, b)
        if (cont !== m) cont.release()
        return b
    }

    fun countNonZero(m: Mat, box: IntBox): Int {
        val b = box.clip(m.cols(), m.rows())
        if (b.isEmpty) return 0
        val sub = m.submat(b.top, b.bottom, b.left, b.right)
        val n = Core.countNonZero(sub)
        sub.release()
        return n
    }

    fun mad(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val med = median(values)
        return median(values.map { kotlin.math.abs(it - med) })
    }

    fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val s = values.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
    }

    fun roundOdd(v: Double): Int {
        val r = v.roundToInt()
        return if (r % 2 == 0) r + 1 else r
    }
}

/** Componente conexa (resultado de connectedComponentsWithStats). */
data class Component(val box: IntBox, val area: Int, val cx: Double, val cy: Double)

object Components {
    fun find(mask: Mat, connectivity: Int = 8): List<Component> {
        val labels = Mat()
        val stats = Mat()
        val centroids = Mat()
        val n = Imgproc.connectedComponentsWithStats(mask, labels, stats, centroids, connectivity, CvType.CV_32S)
        val out = ArrayList<Component>(max(0, n - 1))
        val st = IntArray(5)
        val c = DoubleArray(2)
        for (i in 1 until n) {
            stats.get(i, 0, st)
            centroids.get(i, 0, c)
            out.add(
                Component(
                    IntBox(st[0], st[1], st[0] + st[2], st[1] + st[3]), st[4], c[0], c[1]
                )
            )
        }
        labels.release(); stats.release(); centroids.release()
        return out
    }
}
