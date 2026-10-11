package com.tejerfacil.core.text

import com.tejerfacil.core.geometry.NormRect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Carácter extraído del texto vectorial del PDF (si existe).
 *
 * Coordenadas normalizadas (0..1) respecto de la página ya girada según /Rotate,
 * con origen arriba a la izquierda. [dir] es la dirección de escritura del
 * carácter en grados (0 = horizontal normal, 90 = de abajo hacia arriba,
 * 180 = cabeza abajo, 270 = de arriba hacia abajo).
 */
data class TextGlyph(
    val text: String,
    val box: NormRect,
    val dir: Int,
) {
    val isDigit: Boolean get() = text.length == 1 && text[0].isDigit()
}

/** Palabra: secuencia de glifos contiguos con la misma dirección. */
data class TextWord(val text: String, val box: NormRect, val dir: Int, val glyphs: List<TextGlyph>) {
    val isNumber: Boolean get() = text.isNotEmpty() && text.all { it.isDigit() }
    val number: Int? get() = if (isNumber && text.length <= 4) text.toInt() else null
    val isAlphaWord: Boolean get() = text.count { it.isLetter() } >= 3
}

object GlyphMath {

    /**
     * Construye la caja de un glifo a partir de los datos de PDFBox:
     * punto de origen (línea base) en coordenadas de visualización, avance
     * [advance] y altura [height] (ambos en la dirección del texto).
     */
    fun glyphBox(
        originX: Double, originY: Double, advance: Double, height: Double, dir: Int,
        pageWidth: Double, pageHeight: Double,
    ): NormRect {
        val a = max(advance, height * 0.3)
        val h = height
        val r = when (((dir % 360) + 360) % 360) {
            90 -> NormRect(originX - h, originY - a, originX, originY)
            180 -> NormRect(originX - a, originY, originX, originY + h)
            270 -> NormRect(originX, originY, originX + h, originY + a)
            else -> NormRect(originX, originY - h, originX + a, originY)
        }
        return NormRect(
            r.left / pageWidth, r.top / pageHeight, r.right / pageWidth, r.bottom / pageHeight
        )
    }

    /** Agrupa glifos en palabras (misma dirección, contiguos sobre la línea base). */
    fun groupWords(glyphs: List<TextGlyph>): List<TextWord> {
        val words = mutableListOf<TextWord>()
        val byDir = glyphs.filter { it.text.isNotBlank() }.groupBy { normDir(it.dir) }
        for ((dir, list) in byDir) {
            // Coordenada "a lo largo" del texto y coordenada "perpendicular".
            fun along(g: TextGlyph) = when (dir) {
                90 -> -g.box.centerY
                180 -> -g.box.centerX
                270 -> g.box.centerY
                else -> g.box.centerX
            }
            fun across(g: TextGlyph) = when (dir) {
                90, 270 -> g.box.centerX
                else -> g.box.centerY
            }
            fun size(g: TextGlyph) = when (dir) {
                90, 270 -> g.box.width
                else -> g.box.height
            }
            val sorted = list.sortedWith(compareBy({ across(it) }, { along(it) }))
            // Agrupar por líneas
            val lines = mutableListOf<MutableList<TextGlyph>>()
            for (g in sorted) {
                val line = lines.lastOrNull()
                if (line != null && abs(across(line.last()) - across(g)) < size(g) * 0.45) {
                    line.add(g)
                } else {
                    lines.add(mutableListOf(g))
                }
            }
            for (line in lines) {
                line.sortBy { along(it) }
                var current = mutableListOf<TextGlyph>()
                fun flush() {
                    if (current.isNotEmpty()) {
                        val box = current.map { it.box }.reduce { a, b -> a.union(b) }
                        words.add(TextWord(current.joinToString("") { it.text }, box, dir, current.toList()))
                    }
                    current = mutableListOf()
                }
                for (g in line) {
                    val prev = current.lastOrNull()
                    if (prev != null) {
                        val gap = gapAlong(prev, g, dir)
                        val sameClass = prev.isDigit == g.isDigit
                        if (gap > size(g) * 0.3 || (!sameClass && gap > size(g) * 0.1)) flush()
                    }
                    current.add(g)
                }
                flush()
            }
        }
        return words
    }

    private fun gapAlong(a: TextGlyph, b: TextGlyph, dir: Int): Double = when (dir) {
        90 -> a.box.top - b.box.bottom
        180 -> a.box.left - b.box.right
        270 -> b.box.top - a.box.bottom
        else -> b.box.left - a.box.right
    }

    fun normDir(d: Int): Int {
        val n = ((d % 360) + 360) % 360
        return listOf(0, 90, 180, 270).minBy { min(abs(it - n), 360 - abs(it - n)) }
    }
}
