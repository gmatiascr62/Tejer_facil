package com.tejerfacil.core.rows

import com.tejerfacil.core.geometry.IntBox
import com.tejerfacil.core.geometry.NormRect
import com.tejerfacil.core.vision.GridLines
import com.tejerfacil.core.vision.Junction
import com.tejerfacil.core.vision.Lattice
import com.tejerfacil.core.vision.Vision
import org.opencv.core.Core
import org.opencv.core.Mat
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Número (rótulo) encontrado junto al diagrama, en píxeles de la imagen del diagrama. */
data class NumberLabel(val value: Int, val box: IntBox) {
    val cx: Double get() = box.centerX
    val cy: Double get() = box.centerY
}

/** Fila detectada (píxeles de la imagen del diagrama ya girada). */
data class DetectedRow(
    val top: Double,
    val bottom: Double,
    val left: Double,
    val right: Double,
    /** Número de fila tal como figura en el gráfico, si se pudo leer. */
    val label: Int? = null,
    val confidence: Double = 1.0,
) {
    val height: Double get() = bottom - top
    val centerY: Double get() = (top + bottom) / 2
    fun norm(w: Int, h: Int) = NormRect(left / w, top / h, right / w, bottom / h)
}

enum class ReadingOrder { BOTTOM_UP, TOP_DOWN }
enum class RowMethod { GRID_LINES, INK_PROFILE, NONE }

data class RowDetectionResult(
    val imageWidth: Int,
    val imageHeight: Int,
    /** Filas en orden de lectura (índice 0 = primera fila a tejer). */
    val rows: List<DetectedRow>,
    val readingOrder: ReadingOrder,
    val method: RowMethod,
    val confidence: Double,
    val cellWidth: Double,
    val cellHeight: Double,
    val notes: List<String>,
)

/**
 * Reconocimiento de filas de un diagrama ya recortado y orientado.
 *
 * Método principal (cuadrícula): se buscan las líneas horizontales que tienen
 * al menos dos cruces con líneas verticales. Cada par de líneas consecutivas
 * delimita una fila si hay al menos dos líneas verticales que atraviesan la
 * banda; los extremos izquierdo/derecho de esas verticales dan el ancho real
 * de la fila (filas más cortas en aumentos, disminuciones, formas triangulares).
 * No se suponen alturas iguales: cada fila conserva sus propias coordenadas.
 *
 * Método de reserva (sin cuadrícula horizontal): perfil horizontal de tinta;
 * se marca con confianza baja para que el usuario lo revise.
 */
class RowDetector {

    data class CellHint(val width: Double, val height: Double)

    fun detect(image: Mat, hint: CellHint? = null, labels: List<NumberLabel> = emptyList()): RowDetectionResult {
        val w = image.cols(); val h = image.rows()
        val gray = Vision.toGray(image)
        val ink = Vision.inkMask(gray)
        gray.release()
        val notes = mutableListOf<String>()

        val cell = hint?.takeIf { it.width > 3 && it.height > 3 } ?: estimateCell(ink)
        if (cell == null) {
            notes.add("no se encontró cuadrícula")
            val res = profileRows(ink, null, labels, notes)
            ink.release()
            return res
        }
        val cw = cell.width; val ch = cell.height
        val hLen = max(5, (0.7 * cw).roundToInt())
        val vLen = max(5, (0.7 * ch).roundToInt())
        val maxThick = max(5, (0.42 * min(cw, ch)).roundToInt())
        val gl = GridLines.extract(ink, hLen, vLen, maxThick)

        // Intersecciones pertenecientes a retículas (descarta cruces sueltos de letras).
        val clusters = Lattice.cluster(gl.junctions).filter { it.size >= 6 }
        // Solo la retícula principal y las piezas pegadas a ella (otros gráficos
        // que hayan quedado dentro del recorte no deben mezclar sus filas).
        val main = clusters.maxByOrNull { it.size }
        val pts = if (main == null) emptyList() else {
            val reach = 2.2 * max(cw, ch)
            val chosen = mutableListOf(main)
            var changed = true
            while (changed) {
                changed = false
                for (c in clusters) {
                    if (c in chosen) continue
                    if (chosen.any { near(it, c, reach) }) { chosen.add(c); changed = true }
                }
            }
            chosen.flatten()
        }
        val vBytes = Vision.toBytes(gl.vertical)
        val hBytes = Vision.toBytes(gl.horizontal)
        val lines = horizontalLines(pts, ch, cw, hBytes, w, h)

        val raw = mutableListOf<DetectedRow>()
        for (i in 0 until lines.size - 1) {
            val a = lines[i]
            // Siguiente línea hacia abajo que se superpone en horizontal con esta.
            val b = (i + 1 until lines.size).map { lines[it] }.firstOrNull { o ->
                o.y - a.y >= 0.35 * ch && min(o.xs.max(), a.xs.max()) - max(o.xs.min(), a.xs.min()) >= 0.9 * cw
            } ?: continue
            val xs = Lattice.cluster1D(a.xs + b.xs, max(2.0, 0.25 * cw)).map { it.pos }
            val spanning = xs.filter { columnCoverage(vBytes, w, h, it, a.y, b.y) >= 0.6 }
            if (spanning.size >= 2) {
                raw.add(DetectedRow(a.y, b.y, spanning.min(), spanning.max()))
            }
        }
        gl.release()

        if (raw.size < 2) {
            notes.add("líneas horizontales insuficientes (${raw.size})")
            val res = profileRows(ink, cell, labels, notes)
            ink.release()
            return res
        }

        val split = splitTallRows(raw, hBytes, w, h, notes)
        val med = Vision.median(split.map { it.height })
        val cleaned = split.filter { it.height >= 0.4 * med }
        ink.release()

        val regular = cleaned.count { abs(it.height - med) <= 0.35 * med }.toDouble() / cleaned.size
        val withLabels = assignLabels(cleaned, labels, cw)
        val order = readingOrder(withLabels)
        val ordered = if (order == ReadingOrder.BOTTOM_UP) withLabels.sortedByDescending { it.top } else withLabels.sortedBy { it.top }
        val labeled = withLabels.count { it.label != null }
        notes.add("${cleaned.size} filas por líneas de cuadrícula; regularidad ${"%.0f".format(regular * 100)}%")
        if (labeled > 0) notes.add("$labeled filas con número leído del PDF")
        val conf = (0.55 + 0.35 * regular + if (labeled > 0) 0.1 else 0.0).coerceAtMost(1.0)
        return RowDetectionResult(w, h, ordered, order, RowMethod.GRID_LINES, conf, cw, ch, notes)
    }

    private data class HLine(val y: Double, val xs: List<Double>)

    /** ¿Algún punto de [a] está a menos de [d] de algún punto de [b]? (por cajas primero) */
    private fun near(a: List<Junction>, b: List<Junction>, d: Double): Boolean {
        if (Lattice.bounds(a).gapTo(Lattice.bounds(b)) > d) return false
        val small = if (a.size < b.size) a else b
        val big = if (small === a) b else a
        val sorted = big.sortedBy { it.x }
        val xs = DoubleArray(sorted.size) { sorted[it].x }
        for (p in small) {
            var lo = java.util.Arrays.binarySearch(xs, p.x - d).let { if (it < 0) -it - 1 else it }
            while (lo < xs.size && xs[lo] <= p.x + d) {
                val q = sorted[lo]
                if (kotlin.math.hypot(q.x - p.x, q.y - p.y) <= d) return true
                lo++
            }
        }
        return false
    }

    /** Agrupa los cruces por su coordenada y; cada grupo con 2+ cruces es una línea horizontal. */
    private fun horizontalLines(pts: List<Junction>, ch: Double, cw: Double, hMask: ByteArray, w: Int, h: Int): List<HLine> {
        if (pts.isEmpty()) return emptyList()
        val tol = max(2.0, 0.22 * ch)
        val sorted = pts.sortedBy { it.y }
        val groups = mutableListOf<MutableList<Junction>>()
        for (p in sorted) {
            val g = groups.lastOrNull()
            if (g != null && p.y - g.last().y <= tol) g.add(p) else groups.add(mutableListOf(p))
        }
        val lines = groups.filter { it.size >= 2 }.mapNotNull { g -> continuousPart(g, cw, ch, hMask, w, h) }
        // Líneas dobles muy próximas -> una sola.
        val merged = mutableListOf<HLine>()
        for (l in lines) {
            val last = merged.lastOrNull()
            if (last != null && l.y - last.y < 0.35 * ch) {
                // Conservar la posición de la línea más fuerte (más cruces).
                val y = if (l.xs.size > last.xs.size) l.y else last.y
                merged[merged.size - 1] = HLine(y, last.xs + l.xs)
            } else merged.add(l)
        }
        return merged
    }

    /**
     * Conserva solo los tramos de línea realmente trazados entre cruces
     * consecutivos (>= 85 % de píxeles). Descarta "líneas" falsas formadas por
     * símbolos (p. ej. guiones de revés que casi tocan las verticales).
     */
    private fun continuousPart(g: List<Junction>, cw: Double, lineTol: Double, hMask: ByteArray, w: Int, h: Int): HLine? {
        val y = g.map { it.y }.average()
        val yi = y.roundToInt()
        val xs = Lattice.cluster1D(g.map { it.x }, max(2.0, 0.25 * cw)).map { it.pos }
        if (xs.size < 2) return null
        val keep = HashSet<Int>()
        var segs = 0; var good = 0
        for (i in 0 until xs.size - 1) {
            val a = xs[i].roundToInt(); val b = xs[i + 1].roundToInt()
            if (b - a < 2) continue
            segs++
            var hit = 0
            for (x in a..b) {
                var found = false
                for (dy in -2..2) {
                    val yy = yi + dy
                    if (x in 0 until w && yy in 0 until h && hMask[yy * w + x].toInt() != 0) { found = true; break }
                }
                if (found) hit++
            }
            if (hit >= 0.85 * (b - a + 1)) { good++; keep.add(i); keep.add(i + 1) }
        }
        if (good == 0) return null
        val kept = keep.sorted().map { xs[it] }
        // Afinar la altura: fila de píxeles con más línea cerca del promedio de los cruces.
        val x0 = kept.first().roundToInt().coerceIn(0, w - 1); val x1 = kept.last().roundToInt().coerceIn(0, w - 1)
        var bestY = yi; var bestN = -1
        val rad = max(3, (0.25 * lineTol).roundToInt())
        for (yy in (yi - rad)..(yi + rad)) {
            if (yy !in 0 until h) continue
            var n = 0
            val base = yy * w
            for (x in x0..x1) if (hMask[base + x].toInt() != 0) n++
            if (n > bestN || (n == bestN && kotlin.math.abs(yy - yi) < kotlin.math.abs(bestY - yi))) { bestN = n; bestY = yy }
        }
        // Centro del trazo (líneas de 2-3 px): promedio de filas con >= 90 % del máximo.
        var sumY = 0.0; var cnt = 0
        for (yy in (bestY - 3)..(bestY + 3)) {
            if (yy !in 0 until h) continue
            var n = 0
            val base = yy * w
            for (x in x0..x1) if (hMask[base + x].toInt() != 0) n++
            if (n >= 0.9 * bestN) { sumY += yy; cnt++ }
        }
        return HLine(if (cnt > 0) sumY / cnt else bestY.toDouble(), kept)
    }

    /** Fracción de la banda (y1, y2) cubierta por una línea vertical cerca de x. */
    private fun columnCoverage(v: ByteArray, w: Int, h: Int, x: Double, y1: Double, y2: Double): Double {
        val margin = max(1, ((y2 - y1) * 0.15).roundToInt())
        val a = (y1.roundToInt() + margin).coerceIn(0, h - 1)
        val b = (y2.roundToInt() - margin).coerceIn(0, h - 1)
        if (b <= a) return 0.0
        val xi = x.roundToInt()
        var hit = 0
        for (y in a..b) {
            var found = false
            for (dx in -2..2) {
                val xx = xi + dx
                if (xx in 0 until w && v[y * w + xx].toInt() != 0) { found = true; break }
            }
            if (found) hit++
        }
        return hit.toDouble() / (b - a + 1)
    }

    /**
     * Si una fila mide ~2 o más veces la altura típica, se busca una línea
     * horizontal real dentro (que no tenga cruces porque faltan verticales).
     * Solo se divide si hay evidencia en la imagen; nunca se inventan filas.
     */
    private fun splitTallRows(rows: List<DetectedRow>, hMask: ByteArray, w: Int, h: Int, notes: MutableList<String>): List<DetectedRow> {
        val med = Vision.median(rows.map { it.height })
        val out = mutableListOf<DetectedRow>()
        var splits = 0
        for (r in rows) {
            if (r.height < 1.75 * med) { out.add(r); continue }
            val x0 = r.left.roundToInt().coerceIn(0, w - 1); val x1 = r.right.roundToInt().coerceIn(0, w - 1)
            val width = max(1, x1 - x0)
            val cuts = mutableListOf<Double>()
            var y = (r.top + 0.5 * med).roundToInt()
            val end = (r.bottom - 0.5 * med).roundToInt()
            var runStart = -1
            while (y <= end && y in 0 until h) {
                var cnt = 0
                val base = y * w
                for (x in x0..x1) if (hMask[base + x].toInt() != 0) cnt++
                val isLine = cnt >= 0.5 * width
                if (isLine && runStart < 0) runStart = y
                if (!isLine && runStart >= 0) { cuts.add((runStart + y - 1) / 2.0); runStart = -1 }
                y++
            }
            if (runStart >= 0) cuts.add((runStart + y - 1) / 2.0)
            if (cuts.isEmpty()) { out.add(r); continue }
            var top = r.top
            for (c in cuts) {
                out.add(r.copy(top = top, bottom = c))
                top = c
            }
            out.add(r.copy(top = top))
            splits += cuts.size
        }
        if (splits > 0) notes.add("$splits filas altas divididas por líneas sin cruces")
        return out
    }

    /** Estimación del tamaño de celda a partir de la retícula principal. */
    fun estimateCell(ink: Mat): CellHint? {
        val minDim = min(ink.cols(), ink.rows())
        val k = max(8, minDim / 40)
        val gl = GridLines.extract(ink, k, k, max(6, minDim / 90))
        val clusters = Lattice.cluster(gl.junctions)
        gl.release()
        val main = clusters.maxByOrNull { it.size } ?: return null
        if (main.size < 9) return null
        val st = Lattice.analyze(main)
        if (st.cellWidth <= 2 || st.cellHeight <= 2) return null
        return CellHint(st.cellWidth, st.cellHeight)
    }

    /** Reserva: bandas horizontales de tinta separadas por espacios en blanco. */
    private fun profileRows(ink: Mat, cell: CellHint?, labels: List<NumberLabel>, notes: MutableList<String>): RowDetectionResult {
        val w = ink.cols(); val h = ink.rows()
        val proj = Mat()
        Core.reduce(ink, proj, 1, Core.REDUCE_SUM, org.opencv.core.CvType.CV_32S)
        val sums = IntArray(h)
        proj.get(0, 0, sums)
        proj.release()
        val counts = sums.map { it / 255 }
        val maxC = counts.maxOrNull() ?: 0
        val rows = mutableListOf<DetectedRow>()
        if (maxC > 0) {
            val thr = max(1.0, maxC * 0.04)
            var start = -1
            for (y in 0..h) {
                val on = y < h && counts[y] > thr
                if (on && start < 0) start = y
                if (!on && start >= 0) {
                    rows.add(DetectedRow(start.toDouble(), y.toDouble(), 0.0, w.toDouble(), confidence = 0.3))
                    start = -1
                }
            }
        }
        val med = if (rows.isEmpty()) 0.0 else Vision.median(rows.map { it.height })
        // Descarta bandas muy finas (ruido) y conserva el resto sin inventar divisiones.
        val kept = rows.filter { it.height >= max(3.0, 0.35 * med) }.map { r -> refineHorizontalExtent(ink, r) }
        notes.add("filas estimadas por perfil de tinta (${kept.size}); revisar manualmente")
        val withLabels = assignLabels(kept, labels, cell?.width ?: med)
        val order = readingOrder(withLabels)
        val ordered = if (order == ReadingOrder.BOTTOM_UP) withLabels.sortedByDescending { it.top } else withLabels.sortedBy { it.top }
        return RowDetectionResult(
            w, h, ordered, order, if (kept.isEmpty()) RowMethod.NONE else RowMethod.INK_PROFILE,
            if (kept.isEmpty()) 0.0 else 0.25, cell?.width ?: 0.0, cell?.height ?: med, notes,
        )
    }

    private fun refineHorizontalExtent(ink: Mat, r: DetectedRow): DetectedRow {
        val y0 = r.top.roundToInt().coerceIn(0, ink.rows() - 1)
        val y1 = r.bottom.roundToInt().coerceIn(y0 + 1, ink.rows())
        val band = ink.submat(y0, y1, 0, ink.cols())
        val proj = Mat()
        Core.reduce(band, proj, 0, Core.REDUCE_MAX)
        val b = ByteArray(ink.cols())
        proj.get(0, 0, b)
        band.release(); proj.release()
        val first = b.indexOfFirst { it.toInt() != 0 }
        val last = b.indexOfLast { it.toInt() != 0 }
        return if (first < 0) r else r.copy(left = first.toDouble(), right = (last + 1).toDouble())
    }

    companion object {
        /**
         * Asigna a cada fila el número que aparece a su izquierda o derecha (fuera
         * de la fila, a menos de 4 celdas) y verticalmente dentro de la fila.
         */
        fun assignLabels(rows: List<DetectedRow>, labels: List<NumberLabel>, cw: Double): List<DetectedRow> {
            if (labels.isEmpty()) return rows
            val reach = max(cw * 4, 10.0)
            val usedLabels = HashSet<NumberLabel>()
            val sortedRows = rows.sortedBy { it.top }
            val result = sortedRows.map { r ->
                val cands = labels.filter { l ->
                    l.cy > r.top && l.cy < r.bottom &&
                        ((l.box.right <= r.left + cw * 0.3 && r.left - l.box.right <= reach) ||
                            (l.box.left >= r.right - cw * 0.3 && l.box.left - r.right <= reach))
                }
                val best = cands.minByOrNull { l -> min(abs(l.cx - r.left), abs(l.cx - r.right)) + abs(l.cy - r.centerY) }
                if (best != null) usedLabels.add(best)
                r.copy(label = best?.value)
            }
            return dropInconsistentLabels(result)
        }

        /** Quita números que rompen la secuencia (p. ej. números de columna o de talla). */
        private fun dropInconsistentLabels(rowsTopDown: List<DetectedRow>): List<DetectedRow> {
            val idx = rowsTopDown.indices.filter { rowsTopDown[it].label != null }
            if (idx.size < 3) return rowsTopDown
            // Dirección dominante: ¿los números crecen hacia abajo o hacia arriba?
            var up = 0; var down = 0
            for (k in 0 until idx.size - 1) {
                val a = rowsTopDown[idx[k]].label!!; val b = rowsTopDown[idx[k + 1]].label!!
                if (b < a) up++ else if (b > a) down++
            }
            val sign = if (up >= down) -1 else 1 // -1: decrecen hacia abajo (orden ascendente hacia arriba)
            return rowsTopDown.mapIndexed { i, r ->
                if (r.label == null) return@mapIndexed r
                // Comparar con las filas numeradas vecinas: la diferencia esperada
                // es aproximadamente sign * (distancia en filas).
                val prev = idx.lastOrNull { it < i }
                val next = idx.firstOrNull { it > i }
                fun consistent(j: Int?): Boolean? {
                    if (j == null) return null
                    val other = rowsTopDown[j].label!!
                    val dRows = i - j
                    val dLab = r.label - other
                    return dLab * sign * dRows > 0 && abs(dLab) <= abs(dRows) * 3
                }
                val c1 = consistent(prev); val c2 = consistent(next)
                if (c1 == false && c2 == false || (c1 == false && c2 == null) || (c1 == null && c2 == false)) r.copy(label = null) else r
            }
        }

        /** Por convención los gráficos de tejido se leen de abajo hacia arriba. */
        fun readingOrder(rows: List<DetectedRow>): ReadingOrder {
            val lab = rows.filter { it.label != null }.sortedBy { it.top }
            if (lab.size < 2) return ReadingOrder.BOTTOM_UP
            var up = 0; var down = 0
            for ((a, b) in lab.zipWithNext()) {
                if (b.label!! < a.label!!) up++ else if (b.label > a.label) down++
            }
            return if (down > up) ReadingOrder.TOP_DOWN else ReadingOrder.BOTTOM_UP
        }
    }
}
