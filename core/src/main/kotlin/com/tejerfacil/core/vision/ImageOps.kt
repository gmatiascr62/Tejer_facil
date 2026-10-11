package com.tejerfacil.core.vision

import com.tejerfacil.core.geometry.NormRect
import com.tejerfacil.core.geometry.Rotation
import org.opencv.core.Core
import org.opencv.core.Mat

object ImageOps {
    /**
     * Recorta y gira (múltiplos de 90°, sin pérdida) una región de la página.
     * Los píxeles no se redibujan ni se modifican.
     */
    fun cropRotate(page: Mat, crop: NormRect, rotation: Rotation): Mat {
        val box = crop.normalized().toPixels(page.cols(), page.rows())
        require(!box.isEmpty) { "Recorte vacío" }
        val sub = page.submat(box.top, box.bottom, box.left, box.right)
        val out = rotate(sub, rotation)
        sub.release()
        return out
    }

    /** Copia girada (sentido horario) de [src]. */
    fun rotate(src: Mat, rotation: Rotation): Mat {
        val out = Mat()
        when (rotation) {
            Rotation.R0 -> src.copyTo(out)
            Rotation.R90 -> Core.rotate(src, out, Core.ROTATE_90_CLOCKWISE)
            Rotation.R180 -> Core.rotate(src, out, Core.ROTATE_180)
            Rotation.R270 -> Core.rotate(src, out, Core.ROTATE_90_COUNTERCLOCKWISE)
        }
        return out
    }
}
