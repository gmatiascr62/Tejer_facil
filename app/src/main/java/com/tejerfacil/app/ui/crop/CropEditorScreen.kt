package com.tejerfacil.app.ui.crop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth

import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CallMerge
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.HorizontalSplit
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.VerticalSplit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme

import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tejerfacil.app.AppContainer
import com.tejerfacil.core.geometry.NormRect
import kotlin.math.abs
import kotlin.math.min

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CropEditorScreen(container: AppContainer, patternId: Long, initialPage: Int, onBack: () -> Unit) {
    val vm: CropEditorViewModel = viewModel(key = "recortes_$patternId") { CropEditorViewModel(container, patternId, initialPage) }
    val page by vm.page.collectAsState()
    val pageCount by vm.pageCount.collectAsState()
    val image by vm.image.collectAsState()
    val crops by vm.crops.collectAsState()
    val selected by vm.selected.collectAsState()
    val dirty by vm.dirty.collectAsState()
    val busy by vm.busy.collectAsState()
    val multi by vm.multiSelect.collectAsState()
    var confirmExit by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Editor de recortes") },
                navigationIcon = {
                    IconButton(onClick = { if (dirty) confirmExit = true else onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") }
                },
                actions = {
                    IconButton(onClick = { vm.save {} }, enabled = dirty && busy == null) { Icon(Icons.Filled.Save, "Guardar") }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        ToolButton(Icons.Filled.Add, "Agregar") { vm.add() }
                        ToolButton(Icons.Filled.Delete, "Eliminar", enabled = selected.isNotEmpty()) { vm.deleteSelected() }
                        ToolButton(Icons.Filled.RotateRight, "Girar 90°", enabled = selected.isNotEmpty()) { vm.rotateSelected() }
                        ToolButton(Icons.Filled.HorizontalSplit, "Dividir ↕", enabled = selected.size == 1) { vm.splitSelected(true) }
                        ToolButton(Icons.Filled.VerticalSplit, "Dividir ↔", enabled = selected.size == 1) { vm.splitSelected(false) }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            FilledTonalIconToggleButton(checked = multi, onCheckedChange = { vm.multiSelect.value = it }) {
                                Icon(Icons.Filled.SelectAll, "Selección múltiple")
                            }
                            Text("Varios", style = MaterialTheme.typography.labelSmall)
                        }
                        ToolButton(Icons.Filled.CallMerge, "Unir", enabled = selected.size >= 2) { vm.mergeSelected() }
                        IconButton(onClick = { vm.loadPage(page - 1) }, enabled = !dirty && page > 0) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Página anterior")
                        }
                        Text("Pág. ${page + 1}/$pageCount", textAlign = TextAlign.Center, modifier = Modifier.width(80.dp))
                        IconButton(onClick = { vm.loadPage(page + 1) }, enabled = !dirty && page < pageCount - 1) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Página siguiente")
                        }
                    }
                    if (dirty) {
                        Text(
                            "Hay cambios sin guardar en esta página.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize().background(Color(0xFFECECEC))) {
            val img = image
            if (img == null) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            } else {
                PageCropCanvas(img, crops, selected, onTap = vm::tapSelect, onDrag = vm::drag)
            }
            busy?.let { msg ->
                Surface(Modifier.align(Alignment.Center).padding(24.dp), tonalElevation = 8.dp, shape = MaterialTheme.shapes.large) {
                    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (!msg.startsWith("Error")) CircularProgressIndicator()
                        Text(msg, modifier = Modifier.padding(top = 12.dp))
                        if (msg.startsWith("Error")) TextButton(onClick = vm::clearMessage) { Text("Aceptar") }
                    }
                }
            }
        }
    }

    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text("Cambios sin guardar") },
            text = { Text("¿Guardar los cambios de los recortes de esta página?") },
            confirmButton = { TextButton(onClick = { confirmExit = false; vm.save(onBack) }) { Text("Guardar") } },
            dismissButton = { TextButton(onClick = { confirmExit = false; vm.discard(); onBack() }) { Text("Descartar") } },
        )
    }
}

@Composable
private fun ToolButton(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick, enabled = enabled) { Icon(icon, contentDescription = label) }
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

/** Página con los recortes; arrastrar bordes/esquinas para ajustar, dos dedos para zoom. */
@Composable
private fun PageCropCanvas(
    image: ImageBitmap,
    crops: List<EditCrop>,
    selected: Set<Long>,
    onTap: (Long?) -> Unit,
    onDrag: (Long, Handle, Double, Double) -> Unit,
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val currentCrops by rememberUpdatedState(crops)
    val currentSel by rememberUpdatedState(selected)
    val handleRadius = with(LocalDensity.current) { 28.dp.toPx() }

    // Transformación página(normalizada) -> pantalla.
    fun base(): Pair<Float, Offset> {
        val s = min(size.width.toFloat() / image.width, size.height.toFloat() / image.height)
        val ox = (size.width - image.width * s) / 2f
        val oy = (size.height - image.height * s) / 2f
        return s to Offset(ox, oy)
    }
    fun toScreen(nx: Double, ny: Double): Offset {
        val (s, o) = base()
        val x = o.x + (nx * image.width).toFloat() * s
        val y = o.y + (ny * image.height).toFloat() * s
        return Offset(x * zoom + pan.x, y * zoom + pan.y)
    }
    fun pxToNorm(): Pair<Double, Double> {
        val (s, _) = base()
        return 1.0 / (image.width * s * zoom) to 1.0 / (image.height * s * zoom)
    }

    fun hit(p: Offset): Pair<Long, Handle>? {
        // Agarraderas del recorte seleccionado primero.
        for (cr in currentCrops.filter { it.key in currentSel }) {
            val r = cr.rect
            val pts = listOf(
                Handle.TOP_LEFT to toScreen(r.left, r.top), Handle.TOP_RIGHT to toScreen(r.right, r.top),
                Handle.BOTTOM_LEFT to toScreen(r.left, r.bottom), Handle.BOTTOM_RIGHT to toScreen(r.right, r.bottom),
                Handle.LEFT to toScreen(r.left, r.centerY), Handle.RIGHT to toScreen(r.right, r.centerY),
                Handle.TOP to toScreen(r.centerX, r.top), Handle.BOTTOM to toScreen(r.centerX, r.bottom),
            )
            pts.minByOrNull { (it.second - p).getDistance() }?.let { (h, pos) ->
                if ((pos - p).getDistance() <= handleRadius) return cr.key to h
            }
            val tl = toScreen(r.left, r.top); val br = toScreen(r.right, r.bottom)
            if (abs(p.x - tl.x) <= handleRadius / 2 && p.y in tl.y..br.y) return cr.key to Handle.LEFT
            if (abs(p.x - br.x) <= handleRadius / 2 && p.y in tl.y..br.y) return cr.key to Handle.RIGHT
            if (abs(p.y - tl.y) <= handleRadius / 2 && p.x in tl.x..br.x) return cr.key to Handle.TOP
            if (abs(p.y - br.y) <= handleRadius / 2 && p.x in tl.x..br.x) return cr.key to Handle.BOTTOM
        }
        // Dentro de algún recorte (el más pequeño primero).
        val inside = currentCrops.filter {
            val tl = toScreen(it.rect.left, it.rect.top); val br = toScreen(it.rect.right, it.rect.bottom)
            p.x in tl.x..br.x && p.y in tl.y..br.y
        }.minByOrNull { it.rect.width * it.rect.height }
        return inside?.let { it.key to Handle.MOVE }
    }

    Canvas(
        Modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { size = it }
            .pointerInput(image) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val target = hit(down.position)
                    if (target != null) onTap(target.first)
                    var moved = false
                    var multiTouch = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size >= 2) {
                            multiTouch = true
                            val z = event.calculateZoom()
                            val c = event.calculateCentroid()
                            val pn = event.calculatePan()
                            val newZoom = (zoom * z).coerceIn(1f, 6f)
                            val f = newZoom / zoom
                            pan = Offset(c.x - (c.x - pan.x) * f + pn.x, c.y - (c.y - pan.y) * f + pn.y)
                            zoom = newZoom
                            event.changes.forEach { it.consume() }
                            continue
                        }
                        if (multiTouch) continue
                        val ch = pressed.first()
                        val delta = ch.position - ch.previousPosition
                        if (delta.getDistance() > 0f) moved = true
                        if (target != null && (target.first in currentSel || !moved)) {
                            val (kx, ky) = pxToNorm()
                            onDrag(target.first, target.second, delta.x * kx, delta.y * ky)
                        } else if (zoom > 1f) {
                            pan += delta
                        }
                        ch.consume()
                    }
                    if (!moved && !multiTouch && target == null) onTap(null)
                }
            }
    ) {
        val (s, o) = base()
        val tl0 = Offset(o.x * zoom + pan.x, o.y * zoom + pan.y)
        drawImage(
            image = image,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(image.width, image.height),
            dstOffset = IntOffset(tl0.x.toInt(), tl0.y.toInt()),
            dstSize = IntSize((image.width * s * zoom).toInt(), (image.height * s * zoom).toInt()),
        )
        for ((i, cr) in crops.withIndex()) {
            val isSel = cr.key in selected
            val tl = toScreen(cr.rect.left, cr.rect.top)
            val br = toScreen(cr.rect.right, cr.rect.bottom)
            val color = if (isSel) Color(0xFFD81B60) else Color(0xFF2E7D32)
            drawRect(color.copy(alpha = 0.10f), tl, Size(br.x - tl.x, br.y - tl.y))
            drawRect(color, tl, Size(br.x - tl.x, br.y - tl.y), style = Stroke(width = if (isSel) 4f else 3f))
            // Indicador de número y rotación.
            drawCircle(color, radius = 18f, center = Offset(tl.x + 22f, tl.y + 22f))
            drawContext.canvas.nativeCanvas.drawText(
                "${i + 1}" + if (cr.rotation != 0) " ↻${cr.rotation}°" else "",
                tl.x + 12f, tl.y + 30f,
                android.graphics.Paint().apply {
                    this.color = android.graphics.Color.WHITE; textSize = 26f; isFakeBoldText = true
                    setShadowLayer(4f, 0f, 0f, android.graphics.Color.BLACK)
                },
            )
            if (isSel) {
                val r = cr.rect
                listOf(
                    toScreen(r.left, r.top), toScreen(r.right, r.top), toScreen(r.left, r.bottom), toScreen(r.right, r.bottom),
                    toScreen(r.left, r.centerY), toScreen(r.right, r.centerY), toScreen(r.centerX, r.top), toScreen(r.centerX, r.bottom),
                ).forEach { p ->
                    drawCircle(Color.White, radius = 14f, center = p)
                    drawCircle(color, radius = 14f, center = p, style = Stroke(width = 4f))
                }
            }
        }
    }
}

