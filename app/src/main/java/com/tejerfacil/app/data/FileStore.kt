package com.tejerfacil.app.data

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream

/** Archivos locales: PDF original e imágenes de diagramas. */
class FileStore(context: Context) {
    private val root = File(context.filesDir, "patrones").apply { mkdirs() }

    fun patternDir(patternId: Long): File = File(root, patternId.toString()).apply { mkdirs() }
    fun tempDir(): File = File(root, "tmp").apply { mkdirs() }
    fun pdfFile(patternId: Long): File = File(patternDir(patternId), "original.pdf")

    fun diagramImage(patternId: Long, diagramId: Long, version: Long): File =
        File(File(patternDir(patternId), "diagramas").apply { mkdirs() }, "d${diagramId}_$version.png")

    fun diagramThumb(patternId: Long, diagramId: Long, version: Long): File =
        File(File(patternDir(patternId), "miniaturas").apply { mkdirs() }, "d${diagramId}_$version.jpg")

    fun savePng(bmp: Bitmap, file: File) {
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    fun saveJpeg(bmp: Bitmap, file: File, quality: Int = 88) {
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }
    }

    fun deletePattern(patternId: Long) {
        File(root, patternId.toString()).deleteRecursively()
    }

    fun deleteQuietly(path: String?) {
        if (!path.isNullOrBlank()) runCatching { File(path).delete() }
    }
}
