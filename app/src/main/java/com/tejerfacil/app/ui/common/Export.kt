package com.tejerfacil.app.ui.common

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.tejerfacil.app.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Estado de la exportación a PDF de un patrón. */
@Stable
class ExportState internal constructor(
    private val container: AppContainer,
    private val launch: (suspend () -> Unit) -> Unit,
) {
    var busy by mutableStateOf(false)
    var file by mutableStateOf<File?>(null)
    var error by mutableStateOf<String?>(null)

    fun export(patternId: Long) {
        if (busy) return
        busy = true
        launch {
            try {
                file = container.exporter.export(patternId)
            } catch (t: Throwable) {
                error = t.message ?: t.javaClass.simpleName
            } finally {
                busy = false
            }
        }
    }
}

@Composable
fun rememberExportState(container: AppContainer): ExportState {
    val scope = rememberCoroutineScope()
    return remember { ExportState(container) { block -> scope.launch { block() } } }
}

/** Diálogos de la exportación: progreso, y luego compartir o guardar el PDF. */
@Composable
fun ExportDialogs(state: ExportState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val f = state.file
        if (uri != null && f != null) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
                    }.isSuccess
                }
                Toast.makeText(context, if (ok) "PDF guardado" else "No se pudo guardar el PDF", Toast.LENGTH_LONG).show()
            }
        }
        state.file = null
    }

    if (state.busy) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Exportar PDF") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.padding(end = 16.dp))
                    Text("Armando el PDF con los diagramas…")
                }
            },
            confirmButton = {},
        )
    }
    state.file?.let { f ->
        AlertDialog(
            onDismissRequest = { state.file = null },
            title = { Text("PDF listo") },
            text = { Text("«${f.name}»: una página por diagrama, con tus ediciones. ¿Qué querés hacer?") },
            confirmButton = {
                TextButton(onClick = { share(context, f); state.file = null }) { Text("Compartir") }
            },
            dismissButton = {
                TextButton(onClick = { saver.launch(f.name) }) { Text("Guardar en el teléfono") }
            },
        )
    }
    state.error?.let { msg ->
        AlertDialog(
            onDismissRequest = { state.error = null },
            title = { Text("No se pudo exportar") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { state.error = null }) { Text("Aceptar") } },
        )
    }
}

private fun share(context: Context, f: File) {
    val uri = FileProvider.getUriForFile(context, context.packageName + ".archivos", f)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, f.nameWithoutExtension)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Compartir PDF").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
