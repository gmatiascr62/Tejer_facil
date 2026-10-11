package com.tejerfacil.app.ui.reader

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.tejerfacil.core.geometry.NormRect
import com.tejerfacil.core.viewer.Viewport
import com.tejerfacil.core.viewer.ViewportMath
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Vista guardada: zoom relativo a "ajustar al ancho" y punto central (normalizado). */
data class SavedView(val zoom: Float, val centerX: Float, val centerY: Float)

enum class ViewerCommandKind { FIT_WIDTH, RESET }
data class ViewerCommand(val kind: ViewerCommandKind, val nonce: Long = System.nanoTime())

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

/**
 * Visor del diagrama. La imagen y el resaltado se dibujan con la MISMA
 * transformación (pantalla = imagen * escala + desplazamiento), y las filas
 * están en coordenadas normalizadas de la imagen; por eso el resaltado queda
 * alineado con el gráfico en cualquier zoom o desplazamiento.
 */
@Composable
fun ChartViewer(
    image: ImageBitmap,
    rows: List<NormRect>,
    activeIndex: Int,
    highlight: Color,
    highlightAlpha: Float,
    showOutlines: Boolean,
    initialView: SavedView?,
    command: ViewerCommand?,
    onViewChanged: (SavedView) -> Unit,
    onRowTap: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val imgW = image.width
    val imgH = image.height
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var vp by remember(image) { mutableStateOf<Viewport?>(null) }
    val scope = rememberCoroutineScope()
    var anim by remember { mutableStateOf<Job?>(null) }
    val currentRows by rememberUpdatedState(rows)
    val currentActive by rememberUpdatedState(activeIndex)
    val viewChanged by rememberUpdatedState(onViewChanged)
    val rowTap by rememberUpdatedState(onRowTap)

    fun vw() = viewSize.width.toFloat()
    fun vh() = viewSize.height.toFloat()

    fun report(v: Viewport) {
        if (viewSize == IntSize.Zero) return
        val fit = ViewportMath.fitWidthScale(imgW, vw())
        val (cx, cy) = ViewportMath.screenToImageNorm(vw() / 2f, vh() / 2f, imgW, imgH, v)
        viewChanged(SavedView(v.scale / fit, cx.toFloat(), cy.toFloat()))
    }

    fun animateTo(target: Viewport) {
        val start = vp ?: return
        anim?.cancel()
        anim = scope.launch {
            animate(0f, 1f, animationSpec = tween(durationMillis = 380, easing = FastOutSlowInEasing)) { t, _ ->
                vp = Viewport(
                    lerp(start.scale, target.scale, t),
                    lerp(start.offsetX, target.offsetX, t),
                    lerp(start.offsetY, target.offsetY, t),
                )
            }
            vp = target
            report(target)
        }
    }

    fun centered(v: Viewport): Viewport {
        val row = currentRows.getOrNull(currentActive)
        return if (row != null) ViewportMath.centerOnRow(row, imgW, imgH, vw(), vh(), v)
        else ViewportMath.clamp(v, imgW, imgH, vw(), vh())
    }

    // Estado inicial: zoom y posición guardados, con la fila activa centrada.
    LaunchedEffect(viewSize, image) {
        if (viewSize == IntSize.Zero || vp != null) return@LaunchedEffect
        val fit = ViewportMath.fitWidthScale(imgW, vw())
        val minS = ViewportMath.minScale(imgW, imgH, vw(), vh())
        val scale = ((initialView?.zoom ?: 1f) * fit).coerceIn(minS, ViewportMath.MAX_SCALE)
        val cx = initialView?.centerX ?: 0.5f
        val cy = initialView?.centerY ?: 0.5f
        val v = Viewport(scale, vw() / 2f - cx * imgW * scale, vh() / 2f - cy * imgH * scale)
        vp = centered(v)
    }

    // Al cambiar de fila: animar para dejarla en el centro, respetando el zoom.
    LaunchedEffect(activeIndex, rows) {
        val v = vp ?: return@LaunchedEffect
        animateTo(centered(v))
    }

    LaunchedEffect(command?.nonce) {
        val c = command ?: return@LaunchedEffect
        val v = vp ?: return@LaunchedEffect
        val scale = when (c.kind) {
            ViewerCommandKind.FIT_WIDTH -> ViewportMath.fitWidthScale(imgW, vw())
            ViewerCommandKind.RESET -> ViewportMath.fitInsideScale(imgW, imgH, vw(), vh())
        }
        animateTo(centered(v.copy(scale = scale)))
    }

    Canvas(
        modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { viewSize = it }
            .pointerInput(image) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val v = vp
                    if (v != null) {
                        anim?.cancel()
                        val w = size.width.toFloat(); val h = size.height.toFloat()
                        val minS = ViewportMath.minScale(imgW, imgH, w, h)
                        var n = ViewportMath.zoomAround(v, zoom, centroid.x, centroid.y, minS)
                        n = n.copy(offsetX = n.offsetX + pan.x, offsetY = n.offsetY + pan.y)
                        n = ViewportMath.clamp(n, imgW, imgH, w, h)
                        vp = n
                        report(n)
                    }
                }
            }
            .pointerInput(image) {
                detectTapGestures(
                    onDoubleTap = { p ->
                        vp?.let { v ->
                            val w = size.width.toFloat(); val h = size.height.toFloat()
                            val fit = ViewportMath.fitWidthScale(imgW, w)
                            val minS = ViewportMath.minScale(imgW, imgH, w, h)
                            val target = if (v.scale < fit * 2.2f) ViewportMath.zoomAround(v, 2f, p.x, p.y, minS)
                            else centered(v.copy(scale = fit))
                            animateTo(ViewportMath.clamp(target, imgW, imgH, w, h))
                        }
                    },
                    onTap = { p ->
                        vp?.let { v ->
                            ViewportMath.hitTestRow(p.x, p.y, currentRows, imgW, imgH, v)?.let { rowTap(it) }
                        }
                    },
                )
            }
    ) {
        val v = vp ?: return@Canvas
        withTransform({
            translate(v.offsetX, v.offsetY)
            scale(v.scale, v.scale, pivot = Offset.Zero)
        }) {
            drawImage(
                image = image,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(imgW, imgH),
                dstOffset = IntOffset.Zero,
                dstSize = IntSize(imgW, imgH),
                filterQuality = FilterQuality.Medium,
            )
            val px = 1f / v.scale // 1 píxel de pantalla en coordenadas de imagen
            if (showOutlines) {
                for ((i, r) in rows.withIndex()) {
                    if (i == activeIndex) continue
                    drawRect(
                        color = Color(0x553F51B5),
                        topLeft = Offset((r.left * imgW).toFloat(), (r.top * imgH).toFloat()),
                        size = Size((r.width * imgW).toFloat(), (r.height * imgH).toFloat()),
                        style = Stroke(width = 1.5f * px),
                    )
                }
            }
            rows.getOrNull(activeIndex)?.let { r ->
                val top = (r.top * imgH).toFloat()
                val height = (r.height * imgH).toFloat()
                // Banda suave a todo el ancho (para ver los números de fila) ...
                drawRect(highlight.copy(alpha = highlightAlpha * 0.45f), Offset(0f, top), Size(imgW.toFloat(), height))
                // ... y la fila real más marcada, con borde.
                val left = (r.left * imgW).toFloat()
                val width = (r.width * imgW).toFloat()
                drawRect(highlight.copy(alpha = highlightAlpha), Offset(left, top), Size(width, height))
                drawRect(
                    highlight.copy(alpha = minOf(1f, highlightAlpha + 0.5f)), Offset(left, top), Size(width, height),
                    style = Stroke(width = 3f * px),
                )
            }
        }
    }
}
