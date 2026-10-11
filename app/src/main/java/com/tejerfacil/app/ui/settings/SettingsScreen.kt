package com.tejerfacil.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tejerfacil.app.AppContainer
import com.tejerfacil.app.data.AppSettings
import com.tejerfacil.app.ui.common.ColorChooser
import com.tejerfacil.app.ui.common.HighlightPreview
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(container: AppContainer, onBack: () -> Unit) {
    val s by container.settings.settings.collectAsState(initial = AppSettings())
    val scope = rememberCoroutineScope()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Configuración") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).padding(20.dp).fillMaxSize().verticalScroll(rememberScrollState())) {
            Text("Resaltado de la fila activa", style = MaterialTheme.typography.titleMedium)
            Text("Color por defecto para los diagramas nuevos (cada diagrama puede tener el suyo).", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
            ColorChooser(s.highlightColor) { c -> scope.launch { container.settings.setHighlight(c, s.highlightAlpha) } }
            Spacer(Modifier.height(12.dp))
            Text("Opacidad: ${(s.highlightAlpha * 100).toInt()} %")
            Slider(
                value = s.highlightAlpha,
                onValueChange = { a -> scope.launch { container.settings.setHighlight(s.highlightColor, a) } },
                valueRange = 0.1f..0.8f,
            )
            HighlightPreview(s.highlightColor, s.highlightAlpha)
            Spacer(Modifier.height(20.dp))
            HorizontalDivider()
            SwitchRow(
                "Avanzar y retroceder con el volumen",
                "Volumen + = fila siguiente, volumen − = fila anterior. Solo dentro del lector.",
                s.volumeKeys,
            ) { v -> scope.launch { container.settings.setVolumeKeys(v) } }
            SwitchRow("Mantener la pantalla encendida", "Mientras el lector está abierto.", s.keepScreenOn) { v ->
                scope.launch { container.settings.setKeepScreenOn(v) }
            }
            SwitchRow("Mostrar el contorno de las demás filas", "Ayuda a ver dónde empieza y termina cada fila.", s.showRowOutlines) { v ->
                scope.launch { container.settings.setShowRowOutlines(v) }
            }
            Spacer(Modifier.height(20.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            Text("Acerca de", style = MaterialTheme.typography.titleMedium)
            Text(
                "Tejer Fácil funciona sin conexión: el análisis de los PDF (detección de diagramas, orientación y filas) se hace en el teléfono con OpenCV. " +
                    "La detección automática puede fallar en algunos patrones; en ese caso usá los editores de recortes y de filas.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
