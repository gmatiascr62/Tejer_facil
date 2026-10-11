package com.tejerfacil.app.input

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Puente entre la Activity (que recibe las teclas físicas) y el lector.
 * Solo se capturan las teclas de volumen mientras el lector está abierto y
 * el usuario habilitó la opción ([active] = true).
 */
object VolumeKeys {
    @Volatile var active: Boolean = false
    private val _events = MutableSharedFlow<Int>(extraBufferCapacity = 16)
    val events: SharedFlow<Int> = _events

    /** +1 = siguiente fila, -1 = anterior. Devuelve true si el evento se consumió. */
    fun emit(delta: Int): Boolean {
        if (!active) return false
        _events.tryEmit(delta)
        return true
    }
}
