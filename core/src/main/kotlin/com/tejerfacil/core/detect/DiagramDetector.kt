package com.tejerfacil.core.detect

import com.tejerfacil.core.geometry.IntBox
import com.tejerfacil.core.geometry.NormRect
import com.tejerfacil.core.geometry.Rotation
import com.tejerfacil.core.text.GlyphMath
import com.tejerfacil.core.text.TextGlyph
import com.tejerfacil.core.text.TextWord
import com.tejerfacil.core.vision.Component
import com.tejerfacil.core.vision.Components
import com.tejerfacil.core.vision.GridLines
import com.tejerfacil.core.vision.Junction
import com.tejerfacil.core.vision.Lattice
import com.tejerfacil.core.vision.LatticeStats
import com.tejerfacil.core.vision.Vision
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class DetectionMethod { GRID, MANUAL }

/** Diagrama encontrado en una página. Coordenadas en píxeles de la página renderizada. */
data class DetectedDiagram(
    /** Recorte final (cuadrícula + números + marcas cercanas). */
    val box: IntBox,
    /** Zona de la cuadrícula propiamente dicha. */
    val gridBox: IntBox,
    val cellWidth: Double,
    val cellHeight: Double,
    val rotation: Rotation,
    /** 0..1: qué tan segura es la orientación detectada. */
    val rotationConfidence: Double,
    /** 0..1: qué tan seguro es que se trate de un diagrama. */
    val confidence: Double,
    val method: DetectionMethod,
    val stats: LatticeStats?,
    val notes: List<String>,
) {
    fun cropNorm(pageW: Int, pageH: Int): NormRect = box.toNorm(pageW, pageH)
    fun gridNorm(pageW: Int, pageH: Int): NormRect = gridBox.toNorm(pageW, pageH)
}

data class PageAnalysis(
    val width: Int,
    val height: Int,
    val diagrams: List<DetectedDiagram>,
    /** Candidatos descartados (para depuración y documentación). */
    val rejected: List<Pair<IntBox, String>>,
)

/**
 * Detección de diagramas de tejido en una página renderizada.
 *
 * Idea central: un gráfico de tejido es una retícula de celdas. Se extraen las
 * líneas horizontales y verticales finas, se calculan sus intersecciones y se
 * agrupan por proximidad. Cada grupo con estructura de retícula (varias
 * columnas y filas, separación regular, celdas aproximadamente cuadradas, sin
 * textura fotográfica y sin texto dentro de las celdas) es un diagrama. Luego
 * se amplía el recorte para incluir números de filas/columnas y marcas.
 */
class DiagramDetector(private val params: Params = Params()) {

    data class Params(
        /** Mínimo de líneas verticales/horizontales de la retícula. */
        val minLines: Int = 4,
        val minJunctions: Int = 14,
        val minFill: Double = 0.30,
        val minRegularity: Double = 0.5,
        val maxCellAspect: Double = 2.6,
        /** Margen (en celdas) para incluir números y marcas alrededor. */
        val labelMarginCells: Double = 1.7,
        val maxExpandCells: Double = 5.0,
        val minLineCoverage: Double = 0.6,
    )

    /** [debug] recibe imágenes intermedias (solo para pruebas). */
    fun detect(page: Mat, glyphs: List<TextGlyph> = emptyList(), debug: ((String, Mat) -> Unit)? = null): PageAnalysis {
        val w = page.cols(); val h = page.rows()
        val s = max(w, h) / 2339.0 // escala relativa a A4 @ 200 ppp
        val gray = Vision.toGray(page)
        val ink = Vision.inkMask(gray)

        val lineLen = max(10, (14 * s).roundToInt())
        val maxThick = max(6, (10 * s).roundToInt())
        val lines = GridLines.extract(ink, lineLen, lineLen, maxThick)
        if (debug != null) {
            debug("ink", ink)
            val both = Mat()
            Core.bitwise_or(lines.horizontal, lines.vertical, both)
            for (j in lines.junctions) Imgproc.circle(both, org.opencv.core.Point(j.x, j.y), 3, org.opencv.core.Scalar(128.0), -1)
            debug("lines", both)
            both.release()
        }
        val clusters = Lattice.cluster(lines.junctions)
        val hBytes = Vision.toBytes(lines.horizontal)
        val vBytes = Vision.toBytes(lines.vertical)
        lines.release()

        val comps = Components.find(ink)
        val words = GlyphMath.groupWords(glyphs)
        val paragraphBoxes = paragraphWordBoxes(words).map { it.toPixels(w, h) }

        val rejected = mutableListOf<Pair<IntBox, String>>()
        data class Cand(val points: MutableList<Junction>, var stats: LatticeStats, var grid: IntBox)

        val accepted = mutableListOf<Cand>()
        val leftovers = mutableListOf<Cand>()
        for (c in clusters) {
            if (c.size < 6) continue
            val st = Lattice.analyze(c)
            val grid = Lattice.bounds(c).expand(2).clip(w, h)
            val reason = rejectReason(st, c.size)
                ?: coverageReason(c, st, hBytes, vBytes, w, h)
            if (reason == null) accepted.add(Cand(c.toMutableList(), st, grid))
            else {
                leftovers.add(Cand(c.toMutableList(), st, grid))
                if (c.size >= params.minJunctions) rejected.add(grid to reason)
            }
        }

        // Unir fragmentos de un mismo gráfico (p. ej. separados por un ochos sin líneas).
        var merged = true
        while (merged) {
            merged = false
            loop@ for (i in accepted.indices) for (j in i + 1 until accepted.size) {
                val a = accepted[i]; val b = accepted[j]
                if (sameChart(a.stats, a.grid, b.stats, b.grid)) {
                    a.points.addAll(b.points)
                    a.stats = Lattice.analyze(a.points)
                    a.grid = a.grid.union(b.grid)
                    accepted.removeAt(j)
                    merged = true
                    break@loop
                }
            }
        }
        // Absorber pequeños fragmentos rechazados que estén pegados a un gráfico.
        for (l in leftovers) {
            if (l.points.size < 4) continue
            val target = accepted.firstOrNull { a ->
                val cell = max(a.stats.cellWidth, a.stats.cellHeight)
                a.grid.gapTo(l.grid) <= cell * 2.2 && similarCell(a.stats, l.stats, lenient = true)
            } ?: continue
            target.points.addAll(l.points)
            target.grid = target.grid.union(l.grid)
            target.stats = Lattice.analyze(target.points)
        }

        // Filtros de contenido: tablas de texto y fotografías.
        val diagrams = mutableListOf<DetectedDiagram>()
        val notes = mutableMapOf<Cand, MutableList<String>>()
        val finalCands = accepted.filter { c ->
            val cw = c.stats.cellWidth; val ch = c.stats.cellHeight
            if (isTable(c.grid, cw, ch, comps)) {
                rejected.add(c.grid to "tabla de texto"); false
            } else if (isPhoto(gray, c.grid)) {
                rejected.add(c.grid to "fotografía"); false
            } else true
        }

        for (c in finalCands) {
            val cell = max(c.stats.cellWidth, c.stats.cellHeight).takeIf { it > 2 } ?: (12 * s)
            val others = finalCands.filter { it !== c }.map { it.grid }
            val box = expandWithAnnotations(c.grid, cell, comps, others, paragraphBoxes, w, h)
            val orient = OrientationEstimator.estimate(
                gridBox = c.grid, cropBox = box, cell = cell, pageW = w, pageH = h,
                words = words, comps = comps,
            )
            val conf = confidence(c.stats)
            val n = notes.getOrPut(c) { mutableListOf() }
            n.add("retícula ${c.stats.nx}x${c.stats.ny} líneas, celda ${"%.1f".format(c.stats.cellWidth)}x${"%.1f".format(c.stats.cellHeight)} px")
            n.add("orientación: ${orient.reason}")
            diagrams.add(
                DetectedDiagram(
                    box = box, gridBox = c.grid,
                    cellWidth = c.stats.cellWidth, cellHeight = c.stats.cellHeight,
                    rotation = orient.rotation, rotationConfidence = orient.confidence,
                    confidence = conf, method = DetectionMethod.GRID, stats = c.stats, notes = n,
                )
            )
        }

        // Recortes que se superponen mucho -> mismo diagrama.
        val result = mergeOverlapping(diagrams)
        gray.release(); ink.release()
        return PageAnalysis(w, h, sortReadingOrder(result), rejected)
    }

    private fun rejectReason(st: LatticeStats, n: Int): String? {
        if (n < params.minJunctions) return "pocos cruces ($n)"
        if (st.nx < params.minLines || st.ny < params.minLines) return "pocas líneas (${st.nx}x${st.ny})"
        if (st.cellWidth <= 0 || st.cellHeight <= 0) return "sin separación"
        val aspect = st.cellWidth / st.cellHeight
        if (aspect > params.maxCellAspect || aspect < 1 / params.maxCellAspect) return "celdas no cuadradas (${"%.2f".format(aspect)})"
        if (st.fill < params.minFill) return "retícula incompleta (${"%.2f".format(st.fill)})"
        if (st.regularityX < params.minRegularity || st.regularityY < params.minRegularity)
            return "separación irregular (${"%.2f".format(st.regularityX)}/${"%.2f".format(st.regularityY)}, ${st.nx}x${st.ny}, celda ${"%.1f".format(st.cellWidth)}x${"%.1f".format(st.cellHeight)})"
        return null
    }

    /**
     * En una cuadrícula real las líneas son continuas entre cruces. En texto o
     * dibujos los "cruces" aparecen sueltos. Se mide qué fracción de cada línea
     * (entre su primer y último cruce) está realmente trazada.
     */
    private fun coverageReason(points: List<Junction>, st: LatticeStats, hB: ByteArray, vB: ByteArray, w: Int, h: Int): String? {
        val tol = max(2.0, min(st.cellWidth, st.cellHeight) * 0.3)
        val (covH, perH) = lineCoverage(points, tol, hB, w, h, horizontal = true)
        val (covV, perV) = lineCoverage(points, tol, vB, w, h, horizontal = false)
        if (min(covH, covV) < params.minLineCoverage) return "líneas discontinuas (${"%.2f".format(covH)}/${"%.2f".format(covV)})"
        // En una cuadrícula cada línea cruza varias otras; en ruido de fotos los
        // "cruces" aparecen de a pares.
        if (min(perH, perV) < 3.5) return "líneas cortas (${"%.1f".format(perH)}/${"%.1f".format(perV)} cruces por línea)"
        return null
    }

    /** Devuelve (fracción trazada, cruces promedio por línea). */
    private fun lineCoverage(points: List<Junction>, tol: Double, mask: ByteArray, w: Int, h: Int, horizontal: Boolean): Pair<Double, Double> {
        fun a(p: Junction) = if (horizontal) p.x else p.y
        fun b(p: Junction) = if (horizontal) p.y else p.x
        val byLine = points.sortedBy { b(it) }
        val lines = mutableListOf<MutableList<Junction>>()
        for (p in byLine) {
            val l = lines.lastOrNull()
            if (l != null && b(p) - b(l.last()) <= tol) l.add(p) else lines.add(mutableListOf(p))
        }
        var total = 0L; var hit = 0L
        var nLines = 0; var nPts = 0
        for (l in lines) {
            if (l.size < 2) continue
            nLines++; nPts += l.size
            val pos = l.map { b(it) }.average().roundToInt()
            val from = l.minOf { a(it) }.roundToInt(); val to = l.maxOf { a(it) }.roundToInt()
            for (t in from..to) {
                var found = false
                for (d in -2..2) {
                    val x = if (horizontal) t else pos + d
                    val y = if (horizontal) pos + d else t
                    if (x in 0 until w && y in 0 until h && mask[y * w + x].toInt() != 0) { found = true; break }
                }
                total++
                if (found) hit++
            }
        }
        val cov = if (total == 0L) 0.0 else hit.toDouble() / total
        return cov to (if (nLines == 0) 0.0 else nPts.toDouble() / nLines)
    }

    private fun similarCell(a: LatticeStats, b: LatticeStats, lenient: Boolean = false): Boolean {
        val lo = if (lenient) 0.6 else 0.75
        fun ok(x: Double, y: Double) = x <= 0 || y <= 0 || (x / y in lo..(1 / lo))
        return ok(a.cellWidth, b.cellWidth) && ok(a.cellHeight, b.cellHeight)
    }

    private fun sameChart(sa: LatticeStats, a: IntBox, sb: LatticeStats, b: IntBox): Boolean {
        if (!similarCell(sa, sb)) return false
        val cell = max(max(sa.cellWidth, sa.cellHeight), max(sb.cellWidth, sb.cellHeight))
        if (a.intersects(b)) return true
        val gap = a.gapTo(b)
        if (gap > cell * 1.6) return false
        // Deben estar alineadas (compartir rango en x o en y).
        val ox = min(a.right, b.right) - max(a.left, b.left)
        val oy = min(a.bottom, b.bottom) - max(a.top, b.top)
        return ox > 0.5 * min(a.width, b.width) || oy > 0.5 * min(a.height, b.height)
    }

    /** Tabla de texto: muchas componentes pequeñas (letras) por celda. */
    private fun isTable(grid: IntBox, cw: Double, ch: Double, comps: List<Component>): Boolean {
        if (cw <= 0 || ch <= 0) return false
        val cells = HashMap<Long, Int>()
        for (c in comps) {
            if (!grid.contains(c.cx, c.cy)) continue
            if (c.box.width > cw * 0.9 || c.box.height > ch * 0.9 || c.area < 4) continue
            val ix = ((c.cx - grid.left) / cw).toInt()
            val iy = ((c.cy - grid.top) / ch).toInt()
            val key = ix.toLong() * 100000L + iy
            cells[key] = (cells[key] ?: 0) + 1
        }
        if (cells.isEmpty()) return false
        val totalCells = max(1.0, (grid.width / cw) * (grid.height / ch))
        val avg = cells.values.average()
        val multi = cells.values.count { it >= 3 }.toDouble() / cells.size
        return avg >= 2.6 && multi >= 0.5 && cells.size >= totalCells * 0.25
    }

    /** Fotografía: muchos tonos medios con mucha textura. */
    private fun isPhoto(gray: Mat, grid: IntBox): Boolean {
        val b = grid.clip(gray.cols(), gray.rows())
        if (b.isEmpty) return false
        val sub = gray.submat(b.top, b.bottom, b.left, b.right)
        val lo = Mat(); val hi = Mat(); val mid = Mat()
        Core.inRange(sub, org.opencv.core.Scalar(50.0), org.opencv.core.Scalar(205.0), mid)
        val midFrac = Core.countNonZero(mid).toDouble() / b.area
        var photo = false
        if (midFrac > 0.45) {
            val edges = Mat()
            Imgproc.Canny(sub, edges, 40.0, 120.0)
            val edgeFrac = Core.countNonZero(edges).toDouble() / b.area
            photo = edgeFrac > 0.12
            edges.release()
        }
        lo.release(); hi.release(); mid.release(); sub.release()
        return photo
    }

    private fun expandWithAnnotations(
        grid: IntBox, cell: Double, comps: List<Component>, otherGrids: List<IntBox>,
        paragraphs: List<IntBox>, w: Int, h: Int,
    ): IntBox {
        val limit = grid.expand((cell * params.maxExpandCells).roundToInt())
        val margin = (cell * params.labelMarginCells).roundToInt()
        var box = grid
        val used = BooleanArray(comps.size)
        for (iter in 0 until 4) {
            val zone = box.expand(margin)
            var added = false
            for ((i, c) in comps.withIndex()) {
                if (used[i]) continue
                if (!c.box.intersects(zone)) continue
                if (grid.containsBox(c.box)) { used[i] = true; continue }
                if (!limit.containsBox(c.box)) continue
                if (otherGrids.any { it.contains(c.cx, c.cy) }) continue
                if (paragraphs.any { it.contains(c.cx, c.cy) }) continue
                // Objetos grandes que no son parte del gráfico (fotos, otros bloques).
                if (c.box.width > cell * 3.5 && c.box.height > cell * 3.5 && !grid.intersects(c.box)) continue
                used[i] = true
                box = box.union(c.box)
                added = true
            }
            if (!added) break
        }
        val pad = max(3, (cell * 0.35).roundToInt())
        return box.expand(pad).clip(w, h)
    }

    private fun paragraphWordBoxes(words: List<TextWord>): List<NormRect> {
        // Líneas de texto con 4 o más palabras alfabéticas -> párrafo.
        val out = mutableListOf<NormRect>()
        val byDir = words.groupBy { it.dir }
        for ((dir, ws) in byDir) {
            fun across(wd: TextWord) = if (dir == 90 || dir == 270) wd.box.centerX else wd.box.centerY
            fun size(wd: TextWord) = if (dir == 90 || dir == 270) wd.box.width else wd.box.height
            val sorted = ws.sortedBy { across(it) }
            val lines = mutableListOf<MutableList<TextWord>>()
            for (wd in sorted) {
                val l = lines.lastOrNull()
                if (l != null && kotlin.math.abs(across(l.last()) - across(wd)) < size(wd) * 0.5) l.add(wd)
                else lines.add(mutableListOf(wd))
            }
            for (l in lines) {
                if (l.count { it.isAlphaWord } >= 4) out.addAll(l.filter { !it.isNumber }.map { it.box })
            }
        }
        return out
    }

    private fun confidence(st: LatticeStats): Double {
        val size = min(1.0, (st.nx * st.ny) / 60.0)
        val reg = (st.regularityX + st.regularityY) / 2
        return (0.4 * size + 0.35 * reg + 0.25 * min(1.0, st.fill / 0.7)).coerceIn(0.0, 1.0)
    }

    private fun mergeOverlapping(list: List<DetectedDiagram>): List<DetectedDiagram> {
        val out = list.toMutableList()
        var changed = true
        while (changed) {
            changed = false
            loop@ for (i in out.indices) for (j in i + 1 until out.size) {
                val a = out[i]; val b = out[j]
                val small = if (a.box.area < b.box.area) a.box else b.box
                val big = if (small === a.box) b.box else a.box
                if (small.overlapFraction(big) > 0.6) {
                    val keep = if (a.gridBox.area >= b.gridBox.area) a else b
                    out[i] = keep.copy(
                        box = a.box.union(b.box), gridBox = a.gridBox.union(b.gridBox),
                        notes = keep.notes + "unido con un fragmento superpuesto",
                    )
                    out.removeAt(j)
                    changed = true
                    break@loop
                }
            }
        }
        return out
    }

    private fun sortReadingOrder(list: List<DetectedDiagram>): List<DetectedDiagram> {
        // Agrupar en "bandas" verticales y ordenar de izquierda a derecha.
        val sorted = list.sortedBy { it.box.top }
        val bands = mutableListOf<MutableList<DetectedDiagram>>()
        for (d in sorted) {
            val band = bands.lastOrNull()
            if (band != null && band.any { overlapY(it.box, d.box) > 0.4 }) band.add(d) else bands.add(mutableListOf(d))
        }
        return bands.flatMap { b -> b.sortedBy { it.box.left } }
    }

    private fun overlapY(a: IntBox, b: IntBox): Double {
        val o = min(a.bottom, b.bottom) - max(a.top, b.top)
        return if (o <= 0) 0.0 else o.toDouble() / min(a.height, b.height)
    }

}
