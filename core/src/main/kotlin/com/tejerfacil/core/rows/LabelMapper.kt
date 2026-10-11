package com.tejerfacil.core.rows

import com.tejerfacil.core.geometry.DiagramTransform
import com.tejerfacil.core.geometry.IntBox
import com.tejerfacil.core.text.TextWord
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object LabelMapper {
    /**
     * Convierte las palabras numéricas del PDF que caen dentro del recorte en
     * rótulos con coordenadas de la imagen del diagrama ya girada.
     */
    fun labelsForDiagram(words: List<TextWord>, t: DiagramTransform): List<NumberLabel> {
        val out = mutableListOf<NumberLabel>()
        for (w in words) {
            val n = w.number ?: continue
            val b = w.box
            if (b.centerX < t.crop.left || b.centerX > t.crop.right || b.centerY < t.crop.top || b.centerY > t.crop.bottom) continue
            val p1 = t.pageToDiagram(b.left, b.top)
            val p2 = t.pageToDiagram(b.right, b.bottom)
            out.add(
                NumberLabel(
                    n,
                    IntBox(
                        min(p1.x, p2.x).roundToInt(), min(p1.y, p2.y).roundToInt(),
                        max(p1.x, p2.x).roundToInt(), max(p1.y, p2.y).roundToInt(),
                    )
                )
            )
        }
        return out
    }
}
