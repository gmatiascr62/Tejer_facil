package com.tejerfacil.app.data

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Símbolos guardados por el usuario (recortes de celdas en PNG). Son
 * globales: se pueden usar en cualquier patrón.
 */
class SymbolPalette(context: Context) {
    private val dir = File(context.filesDir, "simbolos").apply { mkdirs() }
    private val _symbols = MutableStateFlow<List<File>>(emptyList())
    val symbols: StateFlow<List<File>> = _symbols.asStateFlow()

    suspend fun refresh() = withContext(Dispatchers.IO) {
        _symbols.value = dir.listFiles { f -> f.extension == "png" }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    suspend fun save(bmp: Bitmap): File = withContext(Dispatchers.IO) {
        val f = File(dir, "simbolo_${System.currentTimeMillis()}.png")
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        refresh()
        f
    }

    suspend fun delete(f: File) = withContext(Dispatchers.IO) {
        f.delete()
        refresh()
    }
}
