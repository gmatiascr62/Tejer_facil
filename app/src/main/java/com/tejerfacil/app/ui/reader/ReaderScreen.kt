package com.tejerfacil.app.ui.reader

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.ViewColumn
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tejerfacil.app.AppContainer
import com.tejerfacil.app.input.VolumeKeys
import com.tejerfacil.app.ui.common.HighlightDialog
import com.tejerfacil.app.ui.common.rememberImageFromFile
import com.tejerfacil.core.geometry.NormRect

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    container: AppContainer,
    diagramId: Long,
    onEditRows: () -> Unit,
    onEditSymbols: (Long, Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val vm: ReaderViewModel = viewModel(key = "lector_$diagramId") { ReaderViewModel(container, diagramId) }
    val diagram by vm.diagram.collectAsState()
    val rowEntities by vm.rows.collectAsState()
    val progress by vm.progress.collectAsState()
    val settings by vm.settings.collectAsState()
    val image = rememberImageFromFile(diagram?.imagePath)
    var command by remember { mutableStateOf<ViewerCommand?>(null) }
    var showColors by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var askCopy by remember { mutableStateOf(false) }
    var askColumns by remember { mutableStateOf(false) }
    val busy by vm.busy.collectAsState()

    val rows = remember(rowEntities) { rowEntities.map { NormRect(it.left, it.top, it.right, it.bottom) } }
    val current = progress?.currentRow ?: 0

    // Teclas de volumen: solo mientras el lector está abierto y la opción está activa.
    DisposableEffect(settings.volumeKeys) {
        VolumeKeys.active = settings.volumeKeys
        onDispose { VolumeKeys.active = false }
    }
    LaunchedEffect(Unit) {
        VolumeKeys.events.collect { d -> if (d > 0) vm.next() else vm.previous() }
    }
    // Giro de pantalla solo a pedido: la app es vertical salvo que se toque el botón.
    var landscape by rememberSaveable { mutableStateOf(false) }
    val activity = LocalContext.current.findActivity()
    DisposableEffect(landscape) {
        activity?.requestedOrientation =
            if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        onDispose { }
    }
    DisposableEffect(Unit) {
        // Al salir del lector se vuelve siempre a vertical.
        onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
    }

    val view = LocalView.current
    DisposableEffect(settings.keepScreenOn) {
        view.keepScreenOn = settings.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(diagram?.name ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") } },
                actions = {
                    IconButton(onClick = { landscape = !landscape }) {
                        Icon(
                            Icons.Filled.ScreenRotation,
                            contentDescription = if (landscape) "Volver a vertical" else "Girar a horizontal",
                        )
                    }
                    IconButton(onClick = { command = ViewerCommand(ViewerCommandKind.FIT_WIDTH) }) {
                        Icon(Icons.Filled.FitScreen, contentDescription = "Ajustar al ancho")
                    }
                    IconButton(onClick = { command = ViewerCommand(ViewerCommandKind.RESET) }) {
                        Icon(Icons.Filled.ZoomOutMap, contentDescription = "Restablecer zoom")
                    }
                    IconButton(onClick = { showColors = true }) { Icon(Icons.Filled.Palette, contentDescription = "Color del resaltado") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Más opciones") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Girar diagrama 90° ↻") },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.RotateRight, null) },
                                onClick = { menu = false; vm.rotateDiagram(90) },
                            )
                            DropdownMenuItem(
                                text = { Text("Girar diagrama 90° ↺") },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.RotateLeft, null) },
                                onClick = { menu = false; vm.rotateDiagram(-90) },
                            )
                            DropdownMenuItem(
                                text = { Text("Editar símbolos") },
                                leadingIcon = { Icon(Icons.Filled.Brush, null) },
                                onClick = {
                                    menu = false
                                    askColumns = false
                                    if (vm.isCopy.value) onEditSymbols(diagramId, false) else askCopy = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Editar columnas") },
                                leadingIcon = { Icon(Icons.Filled.ViewColumn, null) },
                                onClick = {
                                    menu = false
                                    askColumns = true
                                    if (vm.isCopy.value) onEditSymbols(diagramId, true) else askCopy = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Editar filas") },
                                leadingIcon = { Icon(Icons.Filled.Edit, null) },
                                onClick = { menu = false; onEditRows() },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilledTonalButton(
                        onClick = { vm.previous() },
                        enabled = rows.isNotEmpty() && current > 0,
                        modifier = Modifier.weight(1f).height(if (landscape) 52.dp else 72.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null, Modifier.size(32.dp))
                        Text("Fila anterior", textAlign = TextAlign.Center)
                    }
                    Column(Modifier.width(112.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (rows.isNotEmpty()) {
                            Text("Fila ${current + 1} de ${rows.size}", fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                            rowEntities.getOrNull(current)?.label?.let {
                                Text("nº $it en el gráfico", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                            }
                        } else {
                            Text("Sin filas", textAlign = TextAlign.Center)
                        }
                    }
                    Button(
                        onClick = { vm.next() },
                        enabled = rows.isNotEmpty() && current < rows.size - 1,
                        modifier = Modifier.weight(1f).height(if (landscape) 52.dp else 72.dp),
                    ) {
                        Text("Fila siguiente", textAlign = TextAlign.Center)
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(32.dp))
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize().background(Color.White)) {
            val img = image
            val p = progress
            if (img == null || p == null) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            } else {
                ChartViewer(
                    image = img,
                    rows = rows,
                    activeIndex = current,
                    highlight = Color(p.highlightColor),
                    highlightAlpha = p.highlightAlpha,
                    showOutlines = settings.showRowOutlines,
                    initialView = remember(diagramId) { SavedView(p.zoom, p.centerX, p.centerY) },
                    command = command,
                    onViewChanged = vm::onViewChanged,
                    onRowTap = vm::goTo,
                )
                busy?.let { msg ->
                    Surface(
                        Modifier.align(Alignment.Center).padding(24.dp),
                        tonalElevation = 8.dp, shape = MaterialTheme.shapes.large,
                    ) {
                        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            if (!msg.startsWith("Error")) CircularProgressIndicator()
                            Text(msg, Modifier.padding(top = 12.dp), textAlign = TextAlign.Center)
                            if (msg.startsWith("Error")) TextButton(onClick = vm::clearMessage) { Text("Aceptar") }
                        }
                    }
                }
                if (rows.isEmpty() && busy == null) {
                    Surface(
                        Modifier.align(Alignment.BottomCenter).padding(16.dp),
                        tonalElevation = 6.dp, shape = MaterialTheme.shapes.medium,
                    ) {
                        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("No se pudieron reconocer las filas de este diagrama.", textAlign = TextAlign.Center)
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = onEditRows) { Text("Definir filas") }
                        }
                    }
                }
            }
        }
    }

    if (askCopy) {
        AlertDialog(
            onDismissRequest = { askCopy = false },
            title = { Text("Editar símbolos") },
            text = { Text("Para no modificar el original, los cambios se hacen en una copia del patrón. ¿Crear la copia y editar este diagrama?") },
            confirmButton = {
                TextButton(onClick = {
                    askCopy = false
                    val cols = askColumns
                    vm.copyPatternForEditing { copyDiagramId -> onEditSymbols(copyDiagramId, cols) }
                }) { Text("Crear copia") }
            },
            dismissButton = { TextButton(onClick = { askCopy = false }) { Text("Cancelar") } },
        )
    }

    if (showColors) {
        val p = progress
        if (p != null) {
            HighlightDialog(p.highlightColor, p.highlightAlpha, onDismiss = { showColors = false }) { c, a ->
                vm.setHighlight(c, a)
                showColors = false
            }
        }
    }
}


private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
