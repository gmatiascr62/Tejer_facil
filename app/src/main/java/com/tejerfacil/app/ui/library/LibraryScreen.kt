package com.tejerfacil.app.ui.library

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tejerfacil.app.AppContainer
import com.tejerfacil.app.data.db.PatternSummary
import com.tejerfacil.app.ui.common.ConfirmDialog
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    container: AppContainer,
    onOpenPattern: (Long) -> Unit,
    onImportStarted: () -> Unit,
    onSettings: () -> Unit,
) {
    val patterns by container.repo.observeLibrary().collectAsState(initial = null)
    val importState by container.importer.state.collectAsState()
    val scope = rememberCoroutineScope()
    var toDelete by remember { mutableStateOf<PatternSummary?>(null) }
    var copying by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            container.importer.start(uri)
            onImportStarted()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Mis patrones") },
                actions = {
                    IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, contentDescription = "Configuración") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { picker.launch(arrayOf("application/pdf")) },
                icon = { Icon(Icons.Filled.UploadFile, contentDescription = null) },
                text = { Text("Importar PDF", style = MaterialTheme.typography.titleMedium) },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (copying) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                Text("Creando la copia…", Modifier.padding(horizontal = 16.dp))
            }
            importState?.let { st ->
                Card(Modifier.fillMaxWidth().padding(16.dp).clickable { onImportStarted() }) {
                    Column(Modifier.padding(16.dp)) {
                        Text(if (st.running) "Importando ${st.fileName}" else "Importación terminada", fontWeight = FontWeight.Bold)
                        Text(st.error ?: st.stage)
                        if (st.running) {
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(progress = { st.fraction }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
            val list = patterns
            when {
                list == null -> Unit
                list.isEmpty() -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "Todavía no hay patrones.\nTocá «Importar PDF» para elegir un patrón de tejido.\n\nLa app separa los diagramas, reconoce las filas y guarda tu progreso. Todo funciona sin conexión.",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                else -> LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(list, key = { it.pattern.id }) { s ->
                        PatternCard(
                            s,
                            onClick = { onOpenPattern(s.pattern.id) },
                            onDelete = { toDelete = s },
                            onCopy = {
                                scope.launch {
                                    copying = true
                                    runCatching { container.repo.duplicatePattern(s.pattern.id) }
                                    copying = false
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    toDelete?.let { s ->
        ConfirmDialog(
            title = "Eliminar patrón",
            text = "Se eliminará «${s.pattern.name}», sus diagramas y el progreso guardado.",
            onDismiss = { toDelete = null },
            onConfirm = {
                scope.launch { container.repo.deletePattern(s.pattern.id) }
                toDelete = null
            },
        )
    }
}

@Composable
private fun PatternCard(s: PatternSummary, onClick: () -> Unit, onDelete: () -> Unit, onCopy: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.PictureAsPdf, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(s.pattern.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (s.pattern.copyOf != null) {
                    Text("Copia editable", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
                }
                val status = when (s.pattern.status) {
                    "PROCESSING" -> "Procesando…"
                    "ERROR" -> "Error: ${s.pattern.message ?: "desconocido"}"
                    else -> "${s.diagramCount} diagramas"
                }
                Text("${s.pattern.pageCount} páginas · $status")
                Text(DateFormat.getDateInstance().format(Date(s.pattern.importedAt)), style = MaterialTheme.typography.bodySmall)
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Opciones")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Crear copia para editar") },
                        leadingIcon = { Icon(Icons.Filled.ContentCopy, null) },
                        onClick = { menu = false; onCopy() },
                    )
                    DropdownMenuItem(
                        text = { Text("Eliminar") },
                        leadingIcon = { Icon(Icons.Filled.Delete, null) },
                        onClick = { menu = false; onDelete() },
                    )
                }
            }
        }
    }
}
