package com.tejerfacil.app.ui.symbols

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tejerfacil.app.AppContainer
import com.tejerfacil.app.ui.common.rememberImageFromFile
import com.tejerfacil.app.ui.reader.ChartViewer
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SymbolEditorScreen(
    container: AppContainer,
    diagramId: Long,
    onBack: () -> Unit,
    initialTool: SymbolTool = SymbolTool.SELECT,
) {
    val vm: SymbolEditorViewModel = viewModel(key = "simbolos_${diagramId}_$initialTool") {
        SymbolEditorViewModel(container, diagramId, initialTool)
    }
    val columnMarks by vm.columnMarks.collectAsState()
    val hasColumn by vm.hasColumn.collectAsState()
    val image by vm.image.collectAsState()
    val tool by vm.tool.collectAsState()
    val clipboard by vm.clipboard.collectAsState()
    val clipPreview by vm.clipPreview.collectAsState()
    val selectionMarks by vm.selectionMarks.collectAsState()
    val selectionCount by vm.selectionCount.collectAsState()
    val mark by vm.mark.collectAsState()
    val canUndo by vm.canUndo.collectAsState()
    val canRedo by vm.canRedo.collectAsState()
    val dirty by vm.dirty.collectAsState()
    val busy by vm.busy.collectAsState()
    val message by vm.message.collectAsState()
    val palette by vm.palette.collectAsState()
    var confirmExit by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<File?>(null) }
    val markColor = Color(0xFFD81B60)
    val selColor = Color(0xFF1E88E5)
    val colColor = Color(0xFF2E7D32)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (tool == SymbolTool.COLUMNS) "Editar columnas" else "Editar símbolos") },
                navigationIcon = {
                    IconButton(onClick = { if (dirty) confirmExit = true else onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") }
                },
                actions = {
                    IconButton(onClick = vm::undo, enabled = canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, "Deshacer") }
                    IconButton(onClick = vm::redo, enabled = canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, "Rehacer") }
                    IconButton(onClick = { vm.save {} }, enabled = dirty && busy == null) { Icon(Icons.Filled.Save, "Guardar") }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        val tools = listOf(
                            SymbolTool.SELECT to "Seleccionar", SymbolTool.COPY to "Copiar",
                            SymbolTool.PASTE to "Pegar", SymbolTool.ERASE to "Borrar", SymbolTool.COLUMNS to "Columnas",
                        )
                        tools.forEach { (t, label) ->
                            FilterChip(selected = tool == t, onClick = { vm.setTool(t) }, label = { Text(label) })
                        }
                    }
                    Text(message, style = MaterialTheme.typography.bodyMedium)
                    if (tool == SymbolTool.COLUMNS) {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { vm.insertColumn(left = true) }, enabled = hasColumn) { Text("Insertar a la izquierda") }
                            OutlinedButton(onClick = { vm.insertColumn(left = false) }, enabled = hasColumn) { Text("Insertar a la derecha") }
                            OutlinedButton(onClick = vm::deleteColumn, enabled = hasColumn) { Text("Borrar columna") }
                        }
                    }
                    if (tool == SymbolTool.SELECT) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = vm::copySelection, enabled = selectionCount > 0) { Text("Copiar selección") }
                            OutlinedButton(onClick = vm::eraseSelection, enabled = selectionCount > 0) { Text("Borrar") }
                            TextButton(onClick = vm::clearSelection, enabled = selectionCount > 0) { Text("Limpiar") }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(
                            Modifier.size(56.dp).background(Color.White).border(2.dp, if (tool == SymbolTool.PASTE) markColor else Color.Gray),
                            contentAlignment = Alignment.Center,
                        ) {
                            val clip = clipPreview
                            if (clip != null) {
                                Image(remember(clip) { clip.asImageBitmap() }, "Símbolo copiado", Modifier.fillMaxSize().padding(4.dp), contentScale = ContentScale.Fit)
                            } else {
                                Text("—", color = Color.Gray)
                            }
                        }
                        Column(Modifier.width(170.dp)) {
                            val n = clipboard?.size ?: 0
                            Text(if (n > 1) "Bloque copiado ($n celdas)" else "Símbolo copiado", style = MaterialTheme.typography.labelMedium)
                            OutlinedButton(onClick = vm::saveClipboardToPalette, enabled = n == 1) { Text("A la paleta") }
                        }
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        if (palette.isEmpty()) "Paleta vacía: copiá un símbolo y tocá «A la paleta»." else "Paleta (tocá para usar, mantené apretado para borrar):",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    if (palette.isNotEmpty()) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(palette, key = { it.absolutePath }) { f -> PaletteItem(f, onUse = { vm.useSymbol(f) }, onDelete = { deleting = f }) }
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize().background(Color.White)) {
            val img = image
            if (img != null) {
                ChartViewer(
                    image = img,
                    rows = emptyList(),
                    activeIndex = -1,
                    highlight = markColor,
                    highlightAlpha = 0.3f,
                    showOutlines = false,
                    initialView = null,
                    command = null,
                    onViewChanged = {},
                    onRowTap = {},
                    onImageTap = vm::tap,
                    marks = selectionMarks.map { it to selColor } + columnMarks.map { it to colColor } +
                        listOfNotNull(mark?.takeIf { tool != SymbolTool.COLUMNS }?.let { it to markColor }),
                    onImageDrag = if (tool == SymbolTool.SELECT) vm::dragSelect else null,
                    onImageDragEnd = vm::dragEnd,
                )
            }
            busy?.let { msg ->
                Surface(Modifier.align(Alignment.Center).padding(24.dp), tonalElevation = 8.dp, shape = MaterialTheme.shapes.large) {
                    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (!msg.startsWith("Error")) CircularProgressIndicator()
                        Text(msg, Modifier.padding(top = 12.dp), textAlign = TextAlign.Center)
                        if (msg.startsWith("Error")) TextButton(onClick = { vm.clearMessage(); onBack() }) { Text("Aceptar") }
                    }
                }
            }
        }
    }

    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text("Cambios sin guardar") },
            text = { Text("¿Guardar los cambios en los símbolos?") },
            confirmButton = { TextButton(onClick = { confirmExit = false; vm.save(onBack) }) { Text("Guardar") } },
            dismissButton = { TextButton(onClick = { confirmExit = false; onBack() }) { Text("Descartar") } },
        )
    }
    deleting?.let { f ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Quitar de la paleta") },
            text = { Text("¿Quitar este símbolo de la paleta? Los diagramas ya editados no cambian.") },
            confirmButton = { TextButton(onClick = { vm.deleteSymbol(f); deleting = null }) { Text("Quitar") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancelar") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PaletteItem(f: File, onUse: () -> Unit, onDelete: () -> Unit) {
    val img = rememberImageFromFile(f.absolutePath)
    Box(
        Modifier.size(52.dp).background(Color.White).border(1.dp, Color.Gray).combinedClickable(onClick = onUse, onLongClick = onDelete),
        contentAlignment = Alignment.Center,
    ) {
        if (img != null) Image(img, "Símbolo", Modifier.fillMaxSize().padding(4.dp), contentScale = ContentScale.Fit)
    }
}
