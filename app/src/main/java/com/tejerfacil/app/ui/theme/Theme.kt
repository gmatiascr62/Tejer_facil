package com.tejerfacil.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Light = lightColorScheme(
    primary = Color(0xFF8E4A6B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFD8E8),
    secondary = Color(0xFF6B5A63),
    tertiary = Color(0xFF7D5636),
    background = Color(0xFFFFF8F9),
    surface = Color(0xFFFFF8F9),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFFFAFD3),
    onPrimary = Color(0xFF561D3C),
    primaryContainer = Color(0xFF713353),
    secondary = Color(0xFFD7C1CA),
    tertiary = Color(0xFFF0BC95),
)

@Composable
fun TejerFacilTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
