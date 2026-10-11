package com.tejerfacil.core.ocr

import com.tejerfacil.core.geometry.IntBox
import com.tejerfacil.core.geometry.Rotation
import com.tejerfacil.core.rows.NumberLabel
import com.tejerfacil.core.vision.Component
import com.tejerfacil.core.vision.Components
import com.tejerfacil.core.vision.GridLines
import com.tejerfacil.core.vision.Vision
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Reconocimiento de dígitos impresos por comparación con plantillas
 * (sin conexión, sin modelos externos). Pensado para los números de fila y
 * columna que rodean un gráfico de tejido: caracteres aislados, de imprenta,
 * de tamaño similar a una celda.
 */
object DigitOcr {

    data class Match(val digit: Int, val score: Double)

    private class Tpl(val digit: Int, val aspect: Double, val vec: DoubleArray)

    private val templates: List<Tpl> by lazy {
        DigitTemplates.all.map { t ->
            val v = DoubleArray(t.data.length) { i -> Character.digit(t.data[i], 16) / 15.0 }
            Tpl(t.digit, t.aspect, zNorm(v))
        }
    }

    const val MIN_SCORE = 0.62

    private fun zNorm(v: DoubleArray): DoubleArray {
        val m = v.average()
        var ss = 0.0
        for (x in v) ss += (x - m) * (x - m)
        val sd = sqrt(ss / v.size).takeIf { it > 1e-6 } ?: 1.0
        return DoubleArray(v.size) { (v[it] - m) / sd }
    }

    /** Clasifica un recorte binario (255 = tinta) ya derecho. */
    fun classify(patch: Mat): Match {
        val w = patch.cols(); val h = patch.rows()
        if (w < 2 || h < 4) return Match(-1, 0.0)
        val small = Mat()
        Imgproc.resize(patch, small, Size(DigitTemplates.W.toDouble(), DigitTemplates.H.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        val bytes = ByteArray(DigitTemplates.W * DigitTemplates.H)
        small.get(0, 0, bytes)
        small.release()
        val v = zNorm(DoubleArray(bytes.size) { (bytes[it].toInt() and 0xff) / 255.0 })
        val aspect = w.toDouble() / h
        var best = Match(-1, -1.0)
        for (t in templates) {
            var dot = 0.0
            for (i in v.indices) dot += v[i] * t.vec[i]
            val corr = dot / v.size
            val penalty = 0.45 * abs(ln(aspect / t.aspect))
            val score = corr - penalty
            if (score > best.score) best = Match(t.digit, score)
        }
        return best
    }

    /** Extrae el recorte de una componente y lo gira [rotation] (sentido horario). */
    fun patch(ink: Mat, box: IntBox, rotation: Rotation): Mat {
        val b = box.clip(ink.cols(), ink.rows())
        val sub = ink.submat(b.top, b.bottom, b.left, b.right)
        val out = Mat()
        when (rotation) {
            Rotation.R0 -> sub.copyTo(out)
            Rotation.R90 -> Core.rotate(sub, out, Core.ROTATE_90_CLOCKWISE)
            Rotation.R180 -> Core.rotate(sub, out, Core.ROTATE_180)
            Rotation.R270 -> Core.rotate(sub, out, Core.ROTATE_90_COUNTERCLOCKWISE)
        }
        sub.release()
        return out
    }

    /**
     * Componentes con tamaño de dígito que NO están dentro de una celda de la
     * cuadrícula (los símbolos de las celdas tienen líneas en los cuatro lados).
     */
    fun candidates(ink: Mat, cellW: Double, cellH: Double): List<Component> {
        val cMin = min(cellW, cellH); val cMax = max(cellW, cellH)
        val hLen = max(5, (0.7 * cellW).roundToInt())
        val vLen = max(5, (0.7 * cellH).roundToInt())
        val gl = GridLines.extract(ink, hLen, vLen, max(5, (0.42 * cMin).roundToInt()))
        val w = ink.cols(); val h = ink.rows()
        val hB = Vision.toBytes(gl.horizontal)
        val vB = Vision.toBytes(gl.vertical)
        gl.release()
        val reach = (cMax * 0.9).roundToInt()
        return Components.find(ink).filter { c ->
            val big = max(c.box.width, c.box.height); val small = min(c.box.width, c.box.height)
            big >= cMin * 0.28 && big <= cMax * 1.25 && small >= 1 && c.area >= 5 &&
                c.area < 0.75 * c.box.width * c.box.height + 4 && // no bloques rellenos
                !insideCell(c, hB, vB, w, h, reach)
        }
    }

    private fun insideCell(c: Component, hB: ByteArray, vB: ByteArray, w: Int, h: Int, reach: Int): Boolean {
        val cy = c.cy.roundToInt().coerceIn(0, h - 1)
        val cx = c.cx.roundToInt().coerceIn(0, w - 1)
        fun scan(dx: Int, dy: Int, mask: ByteArray, startX: Int, startY: Int): Boolean {
            var x = startX; var y = startY
            for (i in 0 until reach) {
                if (x !in 0 until w || y !in 0 until h) return false
                if (mask[y * w + x].toInt() != 0) return true
                x += dx; y += dy
            }
            return false
        }
        val left = scan(-1, 0, vB, c.box.left - 1, cy)
        val right = scan(1, 0, vB, c.box.right, cy)
        val up = scan(0, -1, hB, cx, c.box.top - 1)
        val down = scan(0, 1, hB, cx, c.box.bottom)
        return left && right && up && down
    }

    data class RotationVote(val rotation: Rotation, val confidence: Double, val digits: Int, val detail: String)

    /**
     * Vota la rotación que deja derechos los dígitos que rodean el gráfico.
     * Devuelve null si no hay suficientes dígitos reconocibles.
     */
    fun voteRotation(ink: Mat, comps: List<Component>, maxSamples: Int = 400): RotationVote? {
        val sample = if (comps.size > maxSamples) comps.shuffled(kotlin.random.Random(1)).take(maxSamples) else comps
        val sums = DoubleArray(4)
        var used = 0
        for (c in sample) {
            val scores = DoubleArray(4)
            for (r in Rotation.entries) {
                val p = patch(ink, c.box, r)
                scores[r.ordinal] = classify(p).score
                p.release()
            }
            val best = scores.max()
            if (best < MIN_SCORE) continue
            used++
            for (i in 0 until 4) sums[i] += max(0.0, scores[i] - 0.3)
        }
        if (used < 4) return null
        val order = sums.indices.sortedByDescending { sums[it] }
        val first = sums[order[0]]; val second = sums[order[1]]
        if (first <= 0) return null
        val margin = (first - second) / first
        val rot = Rotation.entries[order[0]]
        return RotationVote(
            rot, (0.4 + margin * 2.5).coerceIn(0.0, 0.95), used,
            "OCR de $used dígitos: ${Rotation.entries.joinToString { "${it.degrees}°=${"%.1f".format(sums[it.ordinal])}" }}",
        )
    }

    /** Lee números (dígitos derechos agrupados en horizontal) en una imagen ya orientada. */
    fun readNumbers(ink: Mat, comps: List<Component>): List<NumberLabel> {
        data class D(val c: Component, val digit: Int)
        val digits = comps.mapNotNull { c ->
            val p = patch(ink, c.box, Rotation.R0)
            val m = classify(p)
            p.release()
            if (m.score >= MIN_SCORE) D(c, m.digit) else null
        }.sortedBy { it.c.box.left }
        val used = BooleanArray(digits.size)
        val out = mutableListOf<NumberLabel>()
        for (i in digits.indices) {
            if (used[i]) continue
            val group = mutableListOf(digits[i]); used[i] = true
            var last = digits[i]
            while (true) {
                var nextIdx = -1
                for (j in digits.indices) {
                    if (used[j]) continue
                    val a = last.c.box; val b = digits[j].c.box
                    val hMax = max(a.height, b.height); val hMin = min(a.height, b.height)
                    val overlap = min(a.bottom, b.bottom) - max(a.top, b.top)
                    val gap = b.left - a.right
                    if (hMax <= hMin * 1.4 && overlap >= 0.6 * hMin && gap >= -1 && gap <= 0.6 * hMax) {
                        if (nextIdx < 0 || b.left < digits[nextIdx].c.box.left) nextIdx = j
                    }
                }
                if (nextIdx < 0) break
                used[nextIdx] = true
                last = digits[nextIdx]
                group.add(last)
            }
            if (group.size > 4) continue
            val value = group.joinToString("") { it.digit.toString() }.toInt()
            val box = group.map { it.c.box }.reduce { a, b -> a.union(b) }
            out.add(NumberLabel(value, box))
        }
        return out
    }

    fun ink8(gray: Mat): Mat {
        val ink = Vision.inkMask(gray)
        require(ink.type() == CvType.CV_8UC1)
        return ink
    }
}
