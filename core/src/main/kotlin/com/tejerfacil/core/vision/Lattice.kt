package com.tejerfacil.core.vision

import com.tejerfacil.core.geometry.IntBox
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Posición de una línea de la cuadrícula (x de vertical o y de horizontal) y cuántos cruces tiene. */
data class LinePos(val pos: Double, val count: Int)

/** Resumen de la estructura reticular de un grupo de intersecciones. */
data class LatticeStats(
    val columns: List<LinePos>,
    val rows: List<LinePos>,
    val cellWidth: Double,
    val cellHeight: Double,
    /** Fracción de diferencias entre líneas cercanas a la mediana (0..1). */
    val regularityX: Double,
    val regularityY: Double,
    /** Cruces encontrados / (columnas * filas). */
    val fill: Double,
) {
    val nx: Int get() = columns.size
    val ny: Int get() = rows.size
}

object Lattice {

    /** Distancia al vecino más próximo de cada punto (barrido ordenado por x). */
    fun nearestNeighbor(points: List<Junction>): DoubleArray {
        val n = points.size
        val idx = points.indices.sortedBy { points[it].x }
        val xs = DoubleArray(n) { points[idx[it]].x }
        val nn = DoubleArray(n) { Double.MAX_VALUE }
        for (a in 0 until n) {
            val pa = points[idx[a]]
            var best = Double.MAX_VALUE
            var b = a + 1
            while (b < n && xs[b] - pa.x < best) {
                val pb = points[idx[b]]
                val d = hypot(pb.x - pa.x, pb.y - pa.y)
                if (d > 0.5 && d < best) best = d
                b++
            }
            b = a - 1
            while (b >= 0 && pa.x - xs[b] < best) {
                val pb = points[idx[b]]
                val d = hypot(pb.x - pa.x, pb.y - pa.y)
                if (d > 0.5 && d < best) best = d
                b--
            }
            nn[idx[a]] = best
        }
        return nn
    }

    /**
     * Agrupa intersecciones en retículas: dos puntos quedan unidos si están a
     * menos de [linkFactor] veces la menor de sus distancias al vecino más
     * próximo. Así se tolera la falta de alguna línea pero no se unen
     * diagramas separados por un espacio blanco.
     */
    fun cluster(points: List<Junction>, linkFactor: Double = 1.7, maxLink: Double = Double.MAX_VALUE): List<List<Junction>> {
        val n = points.size
        if (n == 0) return emptyList()
        val nn = nearestNeighbor(points)
        val parent = IntArray(n) { it }
        fun find(i: Int): Int {
            var r = i
            while (parent[r] != r) r = parent[r]
            var c = i
            while (parent[c] != r) { val nx = parent[c]; parent[c] = r; c = nx }
            return r
        }
        val idx = points.indices.sortedBy { points[it].x }
        for (a in 0 until n) {
            val ia = idx[a]
            val pa = points[ia]
            if (nn[ia] == Double.MAX_VALUE) continue
            val reach = min(nn[ia] * linkFactor, maxLink)
            var b = a + 1
            while (b < n) {
                val ib = idx[b]
                val pb = points[ib]
                if (pb.x - pa.x > reach) break
                val d = hypot(pb.x - pa.x, pb.y - pa.y)
                if (d <= min(reach, nn[ib] * linkFactor)) {
                    val ra = find(ia); val rb = find(ib)
                    if (ra != rb) parent[ra] = rb
                }
                b++
            }
        }
        return points.indices.groupBy { find(it) }.values.map { g -> g.map { points[it] } }
    }

    /** Agrupa valores 1D cercanos (tolerancia absoluta). Devuelve centro y cantidad. */
    fun cluster1D(values: List<Double>, tol: Double): List<LinePos> {
        if (values.isEmpty()) return emptyList()
        val s = values.sorted()
        val out = mutableListOf<LinePos>()
        var sum = s[0]; var cnt = 1; var last = s[0]
        for (i in 1 until s.size) {
            val v = s[i]
            if (v - last <= tol) {
                sum += v; cnt++
            } else {
                out.add(LinePos(sum / cnt, cnt))
                sum = v; cnt = 1
            }
            last = v
        }
        out.add(LinePos(sum / cnt, cnt))
        return out
    }

    fun analyze(points: List<Junction>): LatticeStats {
        val nn = nearestNeighbor(points).filter { it < Double.MAX_VALUE }
        val nnMed = if (nn.isEmpty()) 10.0 else Vision.median(nn)
        val tol = max(2.0, nnMed * 0.3)
        val cols = cluster1D(points.map { it.x }, tol).filter { it.count >= 2 }
        val rows = cluster1D(points.map { it.y }, tol).filter { it.count >= 2 }
        // Separación local: vecino inmediato a la derecha (misma línea) y abajo (misma columna).
        val (cw, regX) = localSpacing(points, tol, horizontal = true)
        val (ch, regY) = localSpacing(points, tol, horizontal = false)
        val fill = if (cols.isEmpty() || rows.isEmpty()) 0.0
        else points.size.toDouble() / (cols.size.toDouble() * rows.size.toDouble())
        return LatticeStats(cols, rows, cw, ch, regX, regY, min(1.0, fill))
    }

    /**
     * Distancia típica al vecino siguiente sobre la misma línea. Es robusto
     * frente a partes del gráfico desalineadas entre sí (no usa posiciones
     * globales). Regularidad = fracción de distancias ~múltiplo de la típica.
     */
    fun localSpacing(points: List<Junction>, tol: Double, horizontal: Boolean): Pair<Double, Double> {
        if (points.size < 2) return 0.0 to 0.0
        fun a(p: Junction) = if (horizontal) p.x else p.y
        fun b(p: Junction) = if (horizontal) p.y else p.x
        val sorted = points.sortedWith(compareBy({ (b(it) / (tol * 2)).toLong() }, { a(it) }))
        // Agrupar por "línea" (coordenada perpendicular) con tolerancia.
        val byLine = points.sortedBy { b(it) }
        val lines = mutableListOf<MutableList<Junction>>()
        for (p in byLine) {
            val l = lines.lastOrNull()
            if (l != null && b(p) - b(l.last()) <= tol) l.add(p) else lines.add(mutableListOf(p))
        }
        val diffs = ArrayList<Double>()
        for (l in lines) {
            val s = l.map { a(it) }.sorted()
            for (i in 0 until s.size - 1) {
                val d = s[i + 1] - s[i]
                if (d > tol) diffs.add(d)
            }
        }
        if (sorted.isEmpty() || diffs.isEmpty()) return 0.0 to 0.0
        val med = Vision.median(diffs)
        val small = diffs.filter { it <= med * 1.15 }
        val base = if (small.isNotEmpty()) Vision.median(small) else med
        var ok = 0
        for (d in diffs) {
            val k = (d / base).roundToInt().coerceAtLeast(1)
            if (k <= 3 && abs(d - k * base) <= base * 0.3) ok++
        }
        return base to ok.toDouble() / diffs.size
    }

    /**
     * Separación típica entre líneas: mediana de las diferencias "elementales".
     * Si faltan líneas, algunas diferencias son múltiplos; se usa la más
     * frecuente entre las pequeñas.
     */
    fun spacing(positions: List<Double>): Pair<Double, Double> {
        if (positions.size < 2) return 0.0 to 0.0
        val diffs = positions.zipWithNext { a, b -> b - a }.filter { it > 1.0 }
        if (diffs.isEmpty()) return 0.0 to 0.0
        // Mediana del tercio inferior + mediana global: tomar la menor razonable.
        val med = Vision.median(diffs)
        val small = diffs.filter { it <= med * 1.15 }
        val base = if (small.isNotEmpty()) Vision.median(small) else med
        // Regularidad: diferencias que son ~múltiplos enteros de la base.
        var ok = 0
        for (d in diffs) {
            val k = (d / base).roundToInt().coerceAtLeast(1)
            if (k <= 3 && abs(d - k * base) <= base * 0.3) ok++
        }
        return base to ok.toDouble() / diffs.size
    }

    fun bounds(points: List<Junction>): IntBox {
        val minX = points.minOf { it.x }; val maxX = points.maxOf { it.x }
        val minY = points.minOf { it.y }; val maxY = points.maxOf { it.y }
        return IntBox(floor(minX).toInt(), floor(minY).toInt(), ceil(maxX).toInt() + 1, ceil(maxY).toInt() + 1)
    }

    fun rms(a: Double, b: Double) = sqrt((a * a + b * b) / 2)
}
