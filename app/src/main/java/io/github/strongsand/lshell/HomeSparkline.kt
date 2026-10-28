package io.github.strongsand.lshell

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntSize

/** Layout-only text exclusions. Drawing never measures text or reads interactive chart state. */
@Stable
internal class HomeSparklineSpace {
    private data class Ink(val coordinates: LayoutCoordinates, val rect: Rect, val topLeft: Offset?, val bottomRight: Offset?)
    private var root: LayoutCoordinates? = null
    private var rootSize = IntSize.Zero
    private val expected = mutableSetOf<String>()
    private val ink = mutableMapOf<String, Ink>()
    var exclusions by mutableStateOf<List<Rect>?>(null); private set
    fun expect(key: String) { expected += key; refresh() }
    fun remove(key: String) { expected -= key; ink.remove(key); refresh() }
    fun root(coordinates: LayoutCoordinates) {
        // Scrolling the entire card doesn't change its internal free corridor.
        if (root === coordinates && rootSize == coordinates.size) return
        root = coordinates; rootSize = coordinates.size; refresh()
    }
    fun place(key: String, coordinates: LayoutCoordinates, rect: Rect) {
        if (!coordinates.isAttached) return
        val card = root?.takeIf { it.isAttached }
        val topLeft = card?.localPositionOf(coordinates, rect.topLeft)
        val bottomRight = card?.localPositionOf(coordinates, rect.bottomRight)
        val before = ink[key]
        if (before != null && before.coordinates === coordinates && before.rect == rect && before.topLeft == topLeft && before.bottomRight == bottomRight) return
        ink[key] = Ink(coordinates, rect, topLeft, bottomRight); refresh()
    }
    private fun refresh() {
        val card = root?.takeIf { it.isAttached } ?: return
        val boxes = expected.mapNotNull { key -> ink[key]?.takeIf { it.coordinates.isAttached }?.let {
            val topLeft = card.localPositionOf(it.coordinates, it.rect.topLeft)
            val bottomRight = card.localPositionOf(it.coordinates, it.rect.bottomRight)
            Rect(topLeft, bottomRight)
        } }
        exclusions = boxes.takeIf { it.isNotEmpty() && it.size == expected.size }
    }
}

/** Same Text API/style, with tight ink bounds even when a weighted label occupies a wide slot. */
@Composable
internal fun SparklineText(text: String, space: HomeSparklineSpace?, key: String,
    modifier: Modifier = Modifier, style: TextStyle, color: Color) {
    if (space == null) { Text(text, modifier, style = style, color = color); return }
    val anchor = remember(space, key) { SparklineTextAnchor(space, key) }
    DisposableEffect(anchor) { space.expect(key); onDispose { space.remove(key) } }
    Text(text, modifier.onGloballyPositioned(anchor::placed), style = style, color = color, onTextLayout = anchor::layout)
}

private class SparklineTextAnchor(val space: HomeSparklineSpace, val key: String) {
    private var coordinates: LayoutCoordinates? = null
    private var ink: Rect? = null
    fun placed(at: LayoutCoordinates) { coordinates = at; publish() }
    fun layout(result: TextLayoutResult) {
        if (result.lineCount == 0) return
        ink = Rect((0 until result.lineCount).minOf { result.getLineLeft(it) }, 0f,
            (0 until result.lineCount).maxOf { result.getLineRight(it) }, result.size.height.toFloat())
        publish()
    }
    private fun publish() { coordinates?.let { at -> ink?.let { space.place(key, at, it) } } }
}

/** Passive cached path only. No pointer/semantics, marker, timer, animation or network owner. */
@Composable
internal fun HomeSparkline(series: MetricHistorySeries?, space: HomeSparklineSpace, color: Color, modifier: Modifier) {
    if (series == null || LocalCardEditing.current) return
    val drawing = remember(series, space, color) {
        Modifier.drawWithCache {
            val blocked = space.exclusions
            val band = blocked?.let { boxes -> HomeSparklinePolicy.band(size.width / density, size.height / density,
                boxes.map { SparklineBand(it.left / density, it.right / density) }) }
            if (band == null) onDrawBehind {} else {
                val plot = MetricChartPresentation.reduce(series, MetricTimeViewport(series.start, series.end),
                    (band.width / 3f).toInt().coerceIn(16, 180))
                val peak = series.values.maxOrNull()?.coerceAtLeast(1.0) ?: 1.0
                val left = band.left * density; val right = band.right * density
                val top = 12f * density; val height = (size.height - 2 * top).coerceAtLeast(1f)
                fun point(p: TrafficPlotPoint) = Offset(left + chartTimeFraction(p.time, series.start, series.end) * (right - left),
                    top + height * (1f - (p.value!! / peak).toFloat().coerceIn(0f, 1f)))
                val path = Path()
                plot.forEachIndexed { index, p ->
                    if (p.connect && index > 0) { val a = point(plot[index - 1]); val b = point(p)
                        path.moveTo(a.x, a.y); path.lineTo(b.x, b.y) }
                }
                val opacity = HomeSparklinePolicy.opacityStops(band, blocked.orEmpty().map {
                    SparklineBand(it.left / density, it.right / density)
                })
                val fade = Brush.horizontalGradient(*opacity.map { (position, alpha) ->
                    position to color.copy(alpha = color.alpha * alpha)
                }.toTypedArray(), startX = left, endX = right)
                val stroke = Stroke((size.height * .025f).coerceIn(1.2f * density, 2.4f * density), cap = StrokeCap.Round)
                onDrawBehind { drawPath(path, fade, style = stroke) }
            }
        }
    }
    Box(modifier.onGloballyPositioned(space::root).then(drawing))
}
