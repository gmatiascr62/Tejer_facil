package com.tejerfacil.app.ui.common

import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Colores sugeridos para el resaltado (todos permiten leer los símbolos). */
val HighlightPalette: List<Color> = listOf(
    Color(0xFFFFEB3B), // amarillo
    Color(0xFFFF9800), // naranja
    Color(0xFF4CAF50), // verde
    Color(0xFF29B6F6), // celeste
    Color(0xFFE91E63), // rosa
    Color(0xFF9C27B0), // violeta
)

/** Carga una imagen desde un archivo en segundo plano (con submuestreo opcional). */
@Composable
fun rememberImageFromFile(path: String?, maxSide: Int = 0): ImageBitmap? {
    val state = produceState<ImageBitmap?>(initialValue = null, path, maxSide) {
        value = if (path.isNullOrBlank()) null else withContext(Dispatchers.IO) {
            runCatching {
                val f = File(path)
                if (!f.exists()) return@runCatching null
                val opts = BitmapFactory.Options()
                if (maxSide > 0) {
                    opts.inJustDecodeBounds = true
                    BitmapFactory.decodeFile(path, opts)
                    var sample = 1
                    while (maxOf(opts.outWidth, opts.outHeight) / (sample * 2) >= maxSide) sample *= 2
                    opts.inJustDecodeBounds = false
                    opts.inSampleSize = sample
                }
                BitmapFactory.decodeFile(path, opts)?.asImageBitmap()
            }.getOrNull()
        }
    }
    return state.value
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColorChooser(selected: Int, onSelect: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (c in HighlightPalette) {
            val isSel = (c.toArgb() and 0xFFFFFF) == (selected and 0xFFFFFF)
            Box(
                Modifier
                    .size(48.dp)
                    .background(c, CircleShape)
                    .border(if (isSel) 4.dp else 1.dp, if (isSel) MaterialTheme.colorScheme.onSurface else Color.Gray, CircleShape)
                    .clickable { onSelect(c.toArgb()) }
            )
        }
    }
}

/** Diálogo de color y transparencia del resaltado, con vista previa. */
@Composable
fun HighlightDialog(color: Int, alpha: Float, onDismiss: () -> Unit, onConfirm: (Int, Float) -> Unit) {
    var c by remember { mutableIntStateOf(color) }
    var a by remember { mutableFloatStateOf(alpha) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Color del resaltado") },
        text = {
            Column {
                ColorChooser(c) { c = it }
                Spacer(Modifier.height(16.dp))
                Text("Opacidad: ${(a * 100).toInt()} %")
                Slider(value = a, onValueChange = { a = it }, valueRange = 0.1f..0.8f)
                HighlightPreview(c, a)
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(c, a) }) { Text("Aplicar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
fun HighlightPreview(color: Int, alpha: Float) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(Color.White, RoundedCornerShape(8.dp))
            .border(1.dp, Color.LightGray, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.fillMaxWidth().height(28.dp).background(Color(color).copy(alpha = alpha)))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf("○", "/", "—", "|", "●", "\\", "○").forEach { Text(it, color = Color.Black) }
        }
    }
}

@Composable
fun TextInputDialog(title: String, initial: String, label: String, numeric: Boolean = false, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { v -> value = if (numeric) v.filter { it.isDigit() }.take(4) else v },
                label = { Text(label) },
                singleLine = true,
                modifier = Modifier.padding(top = 4.dp),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(value) }) { Text("Aceptar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String = "Eliminar", onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
