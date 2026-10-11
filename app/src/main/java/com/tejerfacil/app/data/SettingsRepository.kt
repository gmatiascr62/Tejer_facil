package com.tejerfacil.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class AppSettings(
    val highlightColor: Int = DEFAULT_COLOR,
    val highlightAlpha: Float = 0.35f,
    val volumeKeys: Boolean = false,
    val keepScreenOn: Boolean = true,
    val showRowOutlines: Boolean = true,
) {
    companion object {
        const val DEFAULT_COLOR = 0xFFFFEB3B.toInt()
    }
}

private val Context.dataStore by preferencesDataStore(name = "ajustes")

class SettingsRepository(private val context: Context) {
    private object Keys {
        val color = intPreferencesKey("color_resaltado")
        val alpha = floatPreferencesKey("transparencia")
        val volume = booleanPreferencesKey("teclas_volumen")
        val screenOn = booleanPreferencesKey("pantalla_encendida")
        val outlines = booleanPreferencesKey("contorno_filas")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            highlightColor = p[Keys.color] ?: AppSettings.DEFAULT_COLOR,
            highlightAlpha = p[Keys.alpha] ?: 0.35f,
            volumeKeys = p[Keys.volume] ?: false,
            keepScreenOn = p[Keys.screenOn] ?: true,
            showRowOutlines = p[Keys.outlines] ?: true,
        )
    }

    suspend fun setHighlight(color: Int, alpha: Float) {
        context.dataStore.edit { it[Keys.color] = color; it[Keys.alpha] = alpha }
    }

    suspend fun setVolumeKeys(v: Boolean) { context.dataStore.edit { it[Keys.volume] = v } }
    suspend fun setKeepScreenOn(v: Boolean) { context.dataStore.edit { it[Keys.screenOn] = v } }
    suspend fun setShowRowOutlines(v: Boolean) { context.dataStore.edit { it[Keys.outlines] = v } }
}
