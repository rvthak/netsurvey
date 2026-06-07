package com.rvthak.netsurvey.ui.map

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min

private const val MIN_USER_SCALE = 0.5f
private const val MAX_USER_SCALE = 8f

/** Decode the plan PNG off the main thread into an [ImageBitmap]. */
@Composable
private fun rememberPlanBitmap(path: String): ImageBitmap? =
    produceState<ImageBitmap?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull()
        }
    }.value

/**
 * Pan/zoom floor-plan canvas with pins glued to the image (SPEC §8).
 *
 * Transform: `screen = imagePx * effScale + offset`, where
 * `effScale = baseScale * userScale` and `baseScale` fits the image to the view.
 * Pins are stored as 0..1 image fractions, so they stick to the plan through any
 * pan/zoom. Long-pressing empty space reports the fraction back for a new pin.
 */
@Composable
fun MapCanvas(
    imagePath: String,
    pins: List<PinUi>,
    onPinTap: (Long) -> Unit,
    onLongPressEmpty: (xFrac: Float, yFrac: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val image = rememberPlanBitmap(imagePath)
    if (image == null) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text("Loading plan…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    val imgW = image.width.toFloat()
    val imgH = image.height.toFloat()

    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    // View transform; reset whenever the plan image changes.
    var userScale by remember(imagePath) { mutableFloatStateOf(1f) }
    var offset by remember(imagePath) { mutableStateOf(Offset.Zero) }
    var initialized by remember(imagePath) { mutableStateOf(false) }

    val density = LocalDensity.current
    val pinRadiusPx = with(density) { 7.dp.toPx() }
    val pinRingPx = with(density) { 2.dp.toPx() }
    val labelGapPx = with(density) { 4.dp.toPx() }
    val tapSlopPx = with(density) { 24.dp.toPx() }

    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = Color.White, fontSize = 11.sp)
    val pinColor = MaterialTheme.colorScheme.primary
    val pinRing = Color.White
    val labelBg = Color(0xCC000000)

    fun baseScale(): Float =
        if (canvasSize.width == 0 || canvasSize.height == 0) 1f
        else min(canvasSize.width / imgW, canvasSize.height / imgH)

    fun effScale(): Float = baseScale() * userScale

    fun fracToScreen(fx: Float, fy: Float): Offset {
        val s = effScale()
        return Offset(fx * imgW * s + offset.x, fy * imgH * s + offset.y)
    }

    fun screenToFrac(pos: Offset): Offset {
        val s = effScale()
        return Offset((pos.x - offset.x) / (s * imgW), (pos.y - offset.y) / (s * imgH))
    }

    // Centre the image to fit, once the view has been measured.
    LaunchedEffect(canvasSize, imagePath) {
        if (!initialized && canvasSize.width > 0 && canvasSize.height > 0) {
            val bs = min(canvasSize.width / imgW, canvasSize.height / imgH)
            offset = Offset(
                (canvasSize.width - imgW * bs) / 2f,
                (canvasSize.height - imgH * bs) / 2f,
            )
            userScale = 1f
            initialized = true
        }
    }

    Canvas(
        modifier = modifier
            .onSizeChanged { canvasSize = it }
            .pointerInput(imagePath) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val oldScale = userScale
                    val newScale = (userScale * zoom).coerceIn(MIN_USER_SCALE, MAX_USER_SCALE)
                    val k = newScale / oldScale
                    // Keep the point under the centroid fixed, then apply the pan.
                    offset = Offset(
                        centroid.x * (1 - k) + offset.x * k + pan.x,
                        centroid.y * (1 - k) + offset.y * k + pan.y,
                    )
                    userScale = newScale
                }
            }
            .pointerInput(imagePath, pins) {
                detectTapGestures(
                    onTap = { pos ->
                        val nearest = pins.minByOrNull {
                            (fracToScreen(it.xFrac, it.yFrac) - pos).getDistance()
                        }
                        if (nearest != null &&
                            (fracToScreen(nearest.xFrac, nearest.yFrac) - pos).getDistance() <= tapSlopPx
                        ) {
                            onPinTap(nearest.typeId)
                        }
                    },
                    onLongPress = { pos ->
                        val f = screenToFrac(pos)
                        if (f.x in 0f..1f && f.y in 0f..1f) onLongPressEmpty(f.x, f.y)
                    },
                )
            },
    ) {
        val s = effScale()
        drawImage(
            image = image,
            dstOffset = IntOffset(offset.x.toInt(), offset.y.toInt()),
            dstSize = IntSize((imgW * s).toInt().coerceAtLeast(1), (imgH * s).toInt().coerceAtLeast(1)),
        )

        pins.forEach { pin ->
            val p = fracToScreen(pin.xFrac, pin.yFrac)
            drawCircle(color = pinRing, radius = pinRadiusPx + pinRingPx, center = p)
            // Colour by the primary metric; unmeasured spots keep the neutral dot.
            drawCircle(color = pin.color ?: pinColor, radius = pinRadiusPx, center = p)

            // Show the primary-metric value; fall back to just the name when a spot
            // has no measured value yet (SPEC §8).
            val label = if (pin.valueLabel != null) "${pin.name}  ${pin.valueLabel}" else pin.name
            val layout = measurer.measure(label, labelStyle)
            val lx = p.x - layout.size.width / 2f
            val ly = p.y + pinRadiusPx + pinRingPx + labelGapPx
            drawRect(
                color = labelBg,
                topLeft = Offset(lx - labelGapPx, ly - labelGapPx / 2f),
                size = Size(layout.size.width + labelGapPx * 2f, layout.size.height + labelGapPx),
            )
            drawText(layout, topLeft = Offset(lx, ly))
        }
    }
}
