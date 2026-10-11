package com.tejerfacil.core.detect

import com.tejerfacil.core.geometry.IntBox
import com.tejerfacil.core.geometry.Rotation
import com.tejerfacil.core.text.TextWord
import com.tejerfacil.core.vision.Component
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

enum class OrientationSource { PDF_TEXT, OCR, LAYOUT, DEFAULT, MANUAL }

data class OrientationResult(
    val rotation: Rotation,
    val confidence: Double,
    val reason: String,
    val source: OrientationSource = OrientationSource.LAYOUT,
)

/**
 * Estima la rotación que hay que aplicar a un diagrama para que quede derecho.
 *
 * 1. Si el PDF tiene texto vectorial: se usa la dirección de escritura de los
 *    números y rótulos que rodean la cuadrícula (dato exacto del PDF).
 * 2. Si no: se analizan las componentes pequeñas (dígitos) alrededor de la
 *    cuadrícula. Los números de varias cifras escritos de lado tienen sus
 *    dígitos apilados verticalmente. El sentido (90° u 270°) se deduce de que
 *    los números con más cifras (filas altas) están del lado de "arriba".
 */
object OrientationEstimator {

    fun estimate(
        gridBox: IntBox, cropBox: IntBox, cell: Double, pageW: Int, pageH: Int,
        words: List<TextWord>, comps: List<Component>,
    ): OrientationResult {
        fromWords(gridBox, cropBox, pageW, pageH, words)?.let { return it }
        return fromComponents(gridBox, cropBox, cell, comps)
    }

    private fun dirToRotation(dir: Int): Rotation = when (dir) {
        90 -> Rotation.R90
        180 -> Rotation.R180
        270 -> Rotation.R270
        else -> Rotation.R0
    }

    fun fromWords(gridBox: IntBox, cropBox: IntBox, pageW: Int, pageH: Int, words: List<TextWord>): OrientationResult? {
        val inside = words.filter {
            val b = it.box.toPixels(pageW, pageH)
            cropBox.contains(b.centerX, b.centerY)
        }
        if (inside.isEmpty()) return null
        val outside = inside.filter {
            val b = it.box.toPixels(pageW, pageH)
            !gridBox.expand(-2).contains(b.centerX, b.centerY)
        }
        // Preferir rótulos fuera de la cuadrícula (números de fila/columna); si
        // no hay, usar todo el texto del recorte.
        val pool = outside.ifEmpty { inside }
        val weights = HashMap<Int, Double>()
        for (w in pool) {
            val weight = if (w.isNumber) 2.0 * w.text.length else w.text.length.toDouble()
            weights[w.dir] = (weights[w.dir] ?: 0.0) + weight
        }
        val total = weights.values.sum()
        if (total < 2.0) return null
        val (dir, wDir) = weights.maxBy { it.value }
        val share = wDir / total
        if (share < 0.6) return null
        return OrientationResult(
            dirToRotation(dir), min(1.0, share),
            "texto del PDF con dirección $dir° (${"%.0f".format(share * 100)}%)",
            OrientationSource.PDF_TEXT,
        )
    }

    private data class Label(val members: MutableList<Component>) {
        val box: IntBox get() = members.map { it.box }.reduce { a, b -> a.union(b) }
    }

    fun fromComponents(gridBox: IntBox, cropBox: IntBox, cell: Double, comps: List<Component>): OrientationResult {
        val cand = comps.filter {
            !gridBox.contains(it.cx, it.cy) && cropBox.contains(it.cx, it.cy) &&
                it.box.width <= cell * 1.5 && it.box.height <= cell * 1.5 &&
                max(it.box.width, it.box.height) >= cell * 0.2 && it.area >= 6
        }
        if (cand.size < 3) return OrientationResult(Rotation.R0, 0.3, "sin rótulos suficientes; se asume derecho", OrientationSource.DEFAULT)

        // Unir dígitos vecinos y registrar si el vecino está al lado o arriba/abajo.
        val n = cand.size
        val parent = IntArray(n) { it }
        fun find(i: Int): Int { var r = i; while (parent[r] != r) r = parent[r]; return r }
        var hLinks = 0; var vLinks = 0
        val sorted = cand.indices.sortedBy { cand[it].cx }
        for (a in 0 until n) for (b in a + 1 until n) {
            val ca = cand[sorted[a]]; val cb = cand[sorted[b]]
            if (cb.box.left - ca.box.right > cell) break
            val ba = ca.box; val bb = cb.box
            val oy = min(ba.bottom, bb.bottom) - max(ba.top, bb.top)
            val ox = min(ba.right, bb.right) - max(ba.left, bb.left)
            val gx = max(bb.left - ba.right, ba.left - bb.right)
            val gy = max(bb.top - ba.bottom, ba.top - bb.bottom)
            val minH = min(ba.height, bb.height); val minW = min(ba.width, bb.width)
            val similar = max(ba.height, bb.height) <= 1.8 * minH && max(ba.width, bb.width) <= 1.8 * minW ||
                // dígitos como "1" son estrechos: comparar la dimensión mayor
                kotlin.math.abs(max(ba.width, ba.height) - max(bb.width, bb.height)) <= 0.35 * max(max(ba.width, ba.height), max(bb.width, bb.height))
            if (!similar) continue
            val horizontal = oy >= 0.7 * minH && gx in -1..(0.45 * max(ba.height, bb.height)).toInt() && ox <= 0
            val vertical = ox >= 0.7 * minW && gy in -1..(0.45 * max(ba.width, bb.width)).toInt() && oy <= 0
            if (horizontal || vertical) {
                if (horizontal) hLinks++ else vLinks++
                val ra = find(sorted[a]); val rb = find(sorted[b])
                if (ra != rb) parent[ra] = rb
            }
        }
        if (hLinks + vLinks < 2) return OrientationResult(Rotation.R0, 0.3, "rótulos de una sola cifra; se asume derecho")
        if (hLinks >= vLinks * 1.5) {
            return OrientationResult(Rotation.R0, min(0.9, 0.5 + 0.05 * hLinks), "dígitos en horizontal ($hLinks/$vLinks)")
        }
        if (vLinks < hLinks * 1.5) return OrientationResult(Rotation.R0, 0.3, "orientación ambigua ($hLinks/$vLinks)")

        // Texto de lado. Agrupar en rótulos y ver de qué lado están los de más cifras.
        val labels = cand.indices.groupBy { find(it) }.values.map { Label(it.map { i -> cand[i] }.toMutableList()) }
        // Rótulos situados arriba o abajo de la cuadrícula (números de fila del gráfico acostado).
        val side = labels.filter { val b = it.box; b.centerY < gridBox.top || b.centerY > gridBox.bottom }
        val use = if (side.size >= 3) side else labels
        val xs = use.map { it.box.centerX }
        val ks = use.map { it.members.size.toDouble() }
        val corr = correlation(xs, ks)
        return when {
            corr < -0.3 -> OrientationResult(Rotation.R90, min(0.85, 0.5 + 0.4 * -corr), "texto vertical; números mayores a la izquierda")
            corr > 0.3 -> OrientationResult(Rotation.R270, min(0.85, 0.5 + 0.4 * corr), "texto vertical; números mayores a la derecha")
            else -> OrientationResult(Rotation.R90, 0.35, "texto vertical; sentido incierto (revisar)")
        }
    }

    private fun correlation(a: List<Double>, b: List<Double>): Double {
        if (a.size < 3) return 0.0
        val ma = a.average(); val mb = b.average()
        var sab = 0.0; var saa = 0.0; var sbb = 0.0
        for (i in a.indices) {
            val da = a[i] - ma; val db = b[i] - mb
            sab += da * db; saa += da * da; sbb += db * db
        }
        if (saa <= 0 || sbb <= 0) return 0.0
        return sab / sqrt(saa * sbb)
    }
}
