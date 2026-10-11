package com.tejerfacil.app.ui.rows

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tejerfacil.app.AppContainer
import com.tejerfacil.app.ui.common.TextInputDialog
import com.tejerfacil.app.ui.common.rememberImageFromFile
import com.tejerfacil.app.ui.reader.ChartViewer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RowEditorScreen(container: AppContainer, diagramId: Long, onBack: () -> Unit) {
    val vm: RowEditorViewModel = viewModel(key = "filas_$diagramId") { RowEditorViewModel(container, diagramId) }
    val diagram by vm.diagram.collectAsState()
    val rows by vm.rows.collectAsState()
    val selected by vm.selected.collectAsState()
    val bottomUp by vm.bottomUp.collectAsState()
    val edge by vm.edge.collectAsState()
    val dirty by vm.dirty.collectAsState()
    val busy by vm.busy.collectAsState()
    val image = rememberImageFromFile(diagram?.imagePath)
    var labelDialog by remember { mutableStateOf(false) }
    var splitDialog by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    var confirmExit by remember { mutableStateOf(false) }

    val rects = remember(rows) { rows.map { it.rect } }
    val selIndex = rows.indexOfFirst { it.key == selected }
    // Número de la fila seleccionada en orden de lectura.
    val readingNumber = if (selIndex < 0) 0 else if (bottomUp) rows.size - selIndex else selIndex + 1

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Editor de filas") },
                navigationIcon = { IconButton(onClick = { if (dirty) confirmExit = true else onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") } },
                actions = {
                    IconButton(onClick = { vm.save(onBack) }, enabled = dirty) { Icon(Icons.Filled.Save, "Guardar") }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        if (selIndex >= 0) "Fila $readingNumber de ${rows.size}" + (rows[selIndex].label?.let { " · nº $it en el gráfico" } ?: "")
                        else "${rows.size} filas · tocá una fila para elegirla",
                        fontWeight = FontWeight.Bold,
                    )
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Borde:")
                        Edge.entries.forEach { e ->
                            FilterChip(selected = edge == e, onClick = { vm.edge.value = e }, label = { Text(e.title) })
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val vertical = edge == Edge.TOP || edge == Edge.BOTTOM
                        FilledTonalButton(onClick = { vm.nudge(-4) }, enabled = selIndex >= 0, modifier = Modifier.weight(1f).height(56.dp)) {
                            Text(if (vertical) "▲ Subir" else "◀ Izquierda")
                        }
                        FilledTonalButton(onClick = { vm.nudge(-1) }, enabled = selIndex >= 0, modifier = Modifier.weight(0.6f).height(56.dp)) { Text("fino") }
                        FilledTonalButton(onClick = { vm.nudge(1) }, enabled = selIndex >= 0, modifier = Modifier.weight(0.6f).height(56.dp)) { Text("fino") }
                        FilledTonalButton(onClick = { vm.nudge(4) }, enabled = selIndex >= 0, modifier = Modifier.weight(1f).height(56.dp)) {
                            Text(if (vertical) "Bajar ▼" else "Derecha ▶")
                        }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = { vm.selectNeighbor(-1) }) { Text("↑ Fila") }
                        OutlinedButton(onClick = { vm.selectNeighbor(1) }) { Text("↓ Fila") }
                        OutlinedButton(onClick = vm::addAbove) { Text("Agregar arriba") }
                        OutlinedButton(onClick = vm::addBelow) { Text("Agregar abajo") }
                        OutlinedButton(onClick = { splitDialog = true }, enabled = selIndex >= 0) { Text("Dividir") }
                        OutlinedButton(onClick = vm::mergeWithBelow, enabled = selIndex >= 0 && selIndex < rows.size - 1) { Text("Unir con la de abajo") }
                        OutlinedButton(onClick = vm::deleteSelected, enabled = selIndex >= 0) { Text("Eliminar") }
                        OutlinedButton(onClick = { labelDialog = true }, enabled = selIndex >= 0) { Text("Número…") }
                        Box {
                            OutlinedButton(onClick = { moreMenu = true }) { Text("Más…") }
                            DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text(if (bottomUp) "Orden: de abajo hacia arriba ✓" else "Orden: de abajo hacia arriba") },
                                    onClick = { vm.setBottomUp(true); moreMenu = false },
                                )
                                DropdownMenuItem(
                                    text = { Text(if (!bottomUp) "Orden: de arriba hacia abajo ✓" else "Orden: de arriba hacia abajo") },
                                    onClick = { vm.setBottomUp(false); moreMenu = false },
                                )
                                DropdownMenuItem(text = { Text("Numerar todas 1…N") }, onClick = { vm.numberSequentially(); moreMenu = false })
                                DropdownMenuItem(text = { Text("Volver a detectar automáticamente") }, onClick = { vm.redetect(); moreMenu = false })
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize().background(Color.White)) {
            val img = image
            if (img == null) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            } else {
                ChartViewer(
                    image = img,
                    rows = rects,
                    activeIndex = selIndex,
                    highlight = Color(0xFFE91E63),
                    highlightAlpha = 0.3f,
                    showOutlines = true,
                    initialView = null,
                    command = null,
                    onViewChanged = {},
                    onRowTap = vm::selectIndex,
                )
            }
            if (rows.isEmpty() && img != null) {
                AssistChip(
                    onClick = vm::addAbove,
                    label = { Text("Sin filas: tocá aquí para crear una y ajustala; luego «Dividir» en N filas") },
                    modifier = Modifier.align(Alignment.TopCenter).padding(12.dp),
                )
            }
            busy?.let { msg ->
                Surface(Modifier.align(Alignment.Center), tonalElevation = 8.dp, shape = MaterialTheme.shapes.large) {
                    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (!msg.startsWith("Error")) CircularProgressIndicator()
                        Text(msg, Modifier.padding(top = 12.dp))
                        if (msg.startsWith("Error")) TextButton(onClick = vm::clearMessage) { Text("Aceptar") }
                    }
                }
            }
        }
    }

    if (labelDialog) {
        val current = rows.getOrNull(selIndex)?.label?.toString() ?: ""
        TextInputDialog("Número de fila en el gráfico", current, "Número (vacío = sin número)", numeric = true, onDismiss = { labelDialog = false }) {
            vm.setLabel(it.toIntOrNull()); labelDialog = false
        }
    }
    if (splitDialog) {
        TextInputDialog("Dividir la fila", "2", "Cantidad de filas iguales", numeric = true, onDismiss = { splitDialog = false }) {
            vm.splitSelected((it.toIntOrNull() ?: 2).coerceIn(2, 400)); splitDialog = false
        }
    }
    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text("Cambios sin guardar") },
            text = { Text("¿Guardar los cambios en las filas?") },
            confirmButton = { TextButton(onClick = { confirmExit = false; vm.save(onBack) }) { Text("Guardar") } },
            dismissButton = { TextButton(onClick = { confirmExit = false; onBack() }) { Text("Descartar") } },
        )
    }
}
