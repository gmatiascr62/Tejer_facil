package com.tejerfacil.core.vision

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Enderezado de imágenes levemente inclinadas (fotos o capturas de una foto).
 * La detección de cuadrículas necesita líneas horizontales y verticales; una
 * inclinación de pocos grados alcanza para romperlas.
 */
object Deskew {

    /**
     * Ángulo de inclinación en grados (positivo = la imagen está girada en
     * sentido antihorario y hay que girarla en sentido horario), estimado con
     * los segmentos largos casi horizontales y casi verticales. Null si no hay
     * evidencia suficiente.
     */
    fun estimateAngle(image: Mat, maxAngle: Double = 12.0): Double? {
        val gray = Vision.toGray(image)
        // Trabajar a ~1500 px para que sea rápido.
        val scale = min(1.0, 1500.0 / max(gray.cols(), gray.rows()))
        val small = Mat()
        if (scale < 1.0) Imgproc.resize(gray, small, Size(), scale, scale, Imgproc.INTER_AREA) else gray.copyTo(small)
        gray.release()
        val edges = Mat()
        Imgproc.Canny(small, edges, 50.0, 150.0)
        val minLen = max(40.0, min(small.cols(), small.rows()) / 8.0)
        val lines = Mat()
        Imgproc.HoughLinesP(edges, lines, 1.0, Math.PI / 720, 80, minLen, 6.0)
        small.release(); edges.release()
        val angles = ArrayList<Pair<Double, Double>>() // (ángulo, peso)
        val l = IntArray(4)
        for (i in 0 until lines.rows()) {
            lines.get(i, 0, l)
            val dx = (l[2] - l[0]).toDouble(); val dy = (l[3] - l[1]).toDouble()
            val len = hypot(dx, dy)
            var a = Math.toDegrees(atan2(dy, dx))
            // Llevar a (-45, 45]: horizontales y verticales votan igual.
            while (a <= -45) a += 90
            while (a > 45) a -= 90
            if (abs(a) <= maxAngle) angles.add(a to len)
        }
        lines.release()
        if (angles.size < 6) return null
        // Mediana ponderada por longitud.
        val sorted = angles.sortedBy { it.first }
        val total = sorted.sumOf { it.second }
        var acc = 0.0
        for ((a, w) in sorted) {
            acc += w
            if (acc >= total / 2) return a
        }
        return null
    }

    /** Gira la imagen [angle] grados (mismo signo que [estimateAngle]) rellenando con blanco. */
    fun rotate(image: Mat, angle: Double): Mat {
        val w = image.cols(); val h = image.rows()
        val center = Point(w / 2.0, h / 2.0)
        val m = Imgproc.getRotationMatrix2D(center, angle, 1.0)
        val rad = Math.toRadians(angle)
        val nw = (abs(w * cos(rad)) + abs(h * sin(rad))).roundToInt()
        val nh = (abs(w * sin(rad)) + abs(h * cos(rad))).roundToInt()
        m.put(0, 2, m.get(0, 2)[0] + (nw - w) / 2.0)
        m.put(1, 2, m.get(1, 2)[0] + (nh - h) / 2.0)
        val out = Mat()
        val white = if (image.channels() == 1) Scalar(255.0) else Scalar(255.0, 255.0, 255.0, 255.0)
        Imgproc.warpAffine(image, out, m, Size(nw.toDouble(), nh.toDouble()), Imgproc.INTER_LINEAR, Core.BORDER_CONSTANT, white)
        m.release()
        return out
    }

    /** Endereza si la inclinación es apreciable (entre [minAngle] y el máximo). Devuelve null si no hace falta. */
    fun straighten(image: Mat, minAngle: Double = 0.4): Mat? {
        val a = estimateAngle(image) ?: return null
        if (abs(a) < minAngle) return null
        return rotate(image, a)
    }
}
