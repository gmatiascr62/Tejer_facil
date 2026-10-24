package com.tejerfacil.app.ui.gallery

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tejerfacil.app.AppContainer
import com.tejerfacil.app.data.db.DiagramListItem
import com.tejerfacil.app.ui.common.ConfirmDialog
import com.tejerfacil.app.ui.common.TextInputDialog
import com.tejerfacil.app.ui.common.rememberImageFromFile
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    container: AppContainer,
    patternId: Long,
    onOpenDiagram: (Long) -> Unit,
    onEditCrops: (Int) -> Unit,
    onEditSymbols: (Long) -> Unit,
    onOpenPattern: (Long) -> Unit,
    onBack: () -> Unit,
) {
    val pattern by container.repo.observePattern(patternId).collectAsState(initial = null)
    val items by container.repo.observeDiagrams(patternId).collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    var menuFor by remember { mutableStateOf<DiagramListItem?>(null) }
    var renaming by remember { mutableStateOf<DiagramListItem?>(null) }
    var deleting by remember { mutableStateOf<DiagramListItem?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }

    val isCopy = pattern?.copyOf != null

    /** Crea una copia del patrón; si [diagram] no es null, abre su editor de símbolos en la copia. */
    fun makeCopy(diagram: com.tejerfacil.app.data.db.DiagramEntity?) {
        if (busy != null) return
        busy = "Creando la copia del patrón…"
        scope.launch {
            try {
                val newId = container.repo.duplicatePattern(patternId)
                busy = null
                if (diagram == null) onOpenPattern(newId)
                else container.db.diagrams().listForPattern(newId)
                    .firstOrNull { it.pageIndex == diagram.pageIndex && it.indexInPage == diagram.indexInPage }
                    ?.let { onEditSymbols(it.id) }
            } catch (t: Throwable) {
                busy = "Error: ${t.message ?: t.javaClass.simpleName}"
            }
        }
    }

    fun rotate(id: Long, delta: Int) {
        if (busy != null) return
        busy = "Girando el diagrama y reconociendo las filas…"
        scope.launch {
            busy = try {
                container.importer.rotateDiagram(id, delta)
                null
            } catch (t: Throwable) {
                "Error: ${t.message ?: t.javaClass.simpleName}"
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(pattern?.name ?: "Diagramas", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") } },
                actions = {
                    IconButton(onClick = { makeCopy(null) }) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "Crear copia editable")
                    }
                    IconButton(onClick = { onEditCrops(items?.firstOrNull()?.diagram?.pageIndex ?: 0) }) {
                        Icon(Icons.Filled.Crop, contentDescription = "Editar recortes")
                    }
                },
            )
        },
    ) { padding ->
        val list = items
        when {
            list == null -> Unit
            list.isEmpty() -> Column(
                Modifier.padding(padding).padding(32.dp).fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    if (pattern?.status == "PROCESSING") "Analizando el PDF…"
                    else "No se detectaron diagramas en este PDF.\nPodés marcarlos a mano en el editor de recortes.",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = { onEditCrops(0) }, modifier = Modifier.height(56.dp)) { Text("Agregar diagramas a mano") }
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(160.dp),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(padding),
            ) {
                items(list, key = { it.diagram.id }) { item ->
                    Box {
                        DiagramCard(item, onClick = { onOpenDiagram(item.diagram.id) }, onLongClick = { menuFor = item })
                        DropdownMenu(expanded = menuFor?.diagram?.id == item.diagram.id, onDismissRequest = { menuFor = null }) {
                            DropdownMenuItem(text = { Text("Abrir") }, onClick = { menuFor = null; onOpenDiagram(item.diagram.id) })
                            DropdownMenuItem(
                                text = { Text(if (isCopy) "Editar símbolos" else "Editar símbolos (en una copia)") },
                                onClick = { menuFor = null; if (isCopy) onEditSymbols(item.diagram.id) else makeCopy(item.diagram) },
                            )
                            DropdownMenuItem(text = { Text("Girar 90° ↻") }, onClick = { menuFor = null; rotate(item.diagram.id, 90) })
                            DropdownMenuItem(text = { Text("Girar 90° ↺") }, onClick = { menuFor = null; rotate(item.diagram.id, -90) })
                            DropdownMenuItem(text = { Text("Cambiar nombre") }, onClick = { renaming = item; menuFor = null })
                            DropdownMenuItem(text = { Text("Editar recorte de la página ${item.diagram.pageIndex + 1}") }, onClick = { menuFor = null; onEditCrops(item.diagram.pageIndex) })
                            DropdownMenuItem(text = { Text("Eliminar") }, onClick = { deleting = item; menuFor = null })
                        }
                    }
                }
            }
        }
    }

    busy?.let { msg ->
        AlertDialog(
            onDismissRequest = { if (msg.startsWith("Error")) busy = null },
            title = { Text(if (msg.startsWith("Creando")) "Copia del patrón" else "Diagrama") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!msg.startsWith("Error")) CircularProgressIndicator(Modifier.padding(end = 16.dp))
                    Text(msg)
                }
            },
            confirmButton = { if (msg.startsWith("Error")) TextButton(onClick = { busy = null }) { Text("Aceptar") } },
        )
    }

    renaming?.let { item ->
        TextInputDialog("Nombre del diagrama", item.diagram.name, "Nombre", onDismiss = { renaming = null }) { name ->
            scope.launch { container.repo.renameDiagram(item.diagram.id, name.ifBlank { item.diagram.name }) }
            renaming = null
        }
    }
    deleting?.let { item ->
        ConfirmDialog("Eliminar diagrama", "Se eliminará «${item.diagram.name}» y su progreso.", onDismiss = { deleting = null }) {
            scope.launch { container.repo.deleteDiagram(item.diagram) }
            deleting = null
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DiagramCard(item: DiagramListItem, onClick: () -> Unit, onLongClick: () -> Unit) {
    val d = item.diagram
    val thumb = rememberImageFromFile(d.thumbPath)
    Card(Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(0.8f).background(Color.White), contentAlignment = Alignment.Center) {
            if (thumb != null) {
                Image(thumb, contentDescription = d.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(4.dp))
            } else {
                Text("Procesando…")
            }
            val needsReview = d.rowConfidence < 0.5 || d.rotationConfidence < 0.5 || item.rowCount == 0
            if (needsReview) {
                AssistChip(onClick = onClick, label = { Text("Revisar") }, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp))
            }
        }
        Column(Modifier.padding(10.dp)) {
            Text(d.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.patternName, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Página ${d.pageIndex + 1}" + if (d.rotation != 0) " · girado ${d.rotation}°" else "", style = MaterialTheme.typography.bodySmall)
            val total = item.rowCount
            val current = (item.currentRow ?: 0) + 1
            if (total > 0) {
                Text("Fila $current de $total", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(progress = { current.toFloat() / total }, modifier = Modifier.fillMaxWidth())
            } else {
                Text("Sin filas reconocidas", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
