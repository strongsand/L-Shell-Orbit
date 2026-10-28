package io.github.strongsand.lshell

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.isActive

/** Keep the dense traffic charts as individual lazy items. Same long-press/edge
 * speed policy as the small-card grid, without eagerly composing offscreen charts.
 */
@Composable
internal fun ReorderableChart(id: String, label: String, arrangement: CardArrangement, visible: List<String>,
    list: LazyListState, host: CardEditorScroll, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    var dragging by remember { mutableStateOf(false) }
    var top by remember { mutableFloatStateOf(0f) }
    var height by remember { mutableFloatStateOf(1f) }
    var finger by remember { mutableFloatStateOf(0f) }
    var grab by remember { mutableFloatStateOf(0f) }
    var before by remember { mutableStateOf<List<String>>(emptyList()) }
    var lastTarget by remember { mutableStateOf<String?>(null) }
    var released by remember { mutableFloatStateOf(0f) }
    var releaseStarted by remember { mutableFloatStateOf(0f) }
    val settle = remember { Animatable(0f) }
    val currentVisible by rememberUpdatedState(visible)
    val haptics = rememberDashHaptics()
    val density = LocalDensity.current.density
    val motion = LocalOrbitMotion.current
    val scale = animateFloatAsState(if (dragging && motion) 1.015f else 1f,
        OrbitMotion.spec(OrbitMotionRole.TOUCH, motion), label = "lazy card lift")
    fun end(cancel: Boolean) {
        if (!dragging) return
        released = finger - grab - top
        dragging = false
        if (cancel) arrangement.restore(before) else {
            arrangement.commit(); haptics.perform(DashFeedback.DRAG_COMPLETED)
        }
    }
    LaunchedEffect(dragging, released, motion) {
        if (!dragging) {
            settle.snapTo(released); releaseStarted = released
            settle.animateTo(0f, OrbitMotion.spec(OrbitMotionRole.SPATIAL, motion))
        }
    }
    LaunchedEffect(dragging, list) {
        if (!dragging) return@LaunchedEffect
        var previous = withFrameNanos { it }
        while (isActive && dragging) {
            val frame = withFrameNanos { it }
            val seconds = ((frame - previous) / 1_000_000_000f).coerceIn(0f, .032f)
            previous = frame
            val speed = CardLayoutPolicy.edgeSpeed(finger, host.viewport.top, host.viewport.bottom, density)
            val items = list.layoutInfo.visibleItemsInfo
            val own = items.firstOrNull { it.key == "chart:$id" } ?: continue
            val base = top - own.offset
            val shown = arrangement.order.filter { it in currentVisible }
            val first = items.firstOrNull { it.key == "chart:${shown.firstOrNull()}" }
            val last = items.firstOrNull { it.key == "chart:${shown.lastOrNull()}" }
            if (speed != 0f) list.scrollBy(CardLayoutPolicy.boundedScroll(speed * seconds,
                first?.let { base + it.offset } ?: -Float.MAX_VALUE,
                last?.let { base + it.offset + it.size } ?: Float.MAX_VALUE,
                host.viewport.top, host.viewport.bottom))
            val center = (finger - grab + height / 2).coerceIn(host.viewport.top + 1, host.viewport.bottom - 1)
            val target = items.firstOrNull {
                it.key != own.key && it.key.toString().startsWith("chart:") &&
                    center >= base + it.offset && center < base + it.offset + it.size
            }?.key?.toString()?.removePrefix("chart:")
            if (target == null) lastTarget = null
            else if (target != lastTarget && target in currentVisible) {
                lastTarget = target
                arrangement.previewMove(currentVisible, id, target)
                haptics.perform(DashFeedback.DRAG_SNAP)
            }
        }
    }
    DisposableEffect(id) { onDispose { end(true) } }
    val up = stringResource(R.string.chart_move_up)
    val down = stringResource(R.string.chart_move_down)
    val description = stringResource(R.string.chart_reorder, label)
    val shown = arrangement.order.filter { it in visible }
    var menu by remember { mutableStateOf(false) }
    fun move(direction: Int) { arrangement.accessibleMove(currentVisible, id, direction); haptics.perform(DashFeedback.DRAG_SNAP) }
    Box(modifier.fillMaxWidth().onGloballyPositioned {
        top = it.positionInRoot().y; height = it.size.height.toFloat()
    }.zIndex(if (dragging) 2f else 0f).pointerInput(id) {
        try { detectDragGesturesAfterLongPress(
            onDragStart = {
                before = arrangement.order; grab = it.y; finger = top + it.y
                dragging = true; released = 0f; lastTarget = null
                haptics.perform(DashFeedback.DRAG_STARTED)
            }, onDragEnd = { end(false) }, onDragCancel = { end(true) }
        ) { change, _ -> change.consume(); finger = top + change.position.y } }
        finally { end(true) }
    }.semantics {
        customActions = buildList {
            if (shown.indexOf(id) > 0) add(CustomAccessibilityAction(up) { move(-1); true })
            if (shown.indexOf(id) < shown.lastIndex) add(CustomAccessibilityAction(down) { move(1); true })
        }
    }) {
        Column(Modifier.fillMaxWidth().graphicsLayer {
            translationY = if (dragging) finger - grab - top else if (released != releaseStarted) released else settle.value
            scaleX = scale.value; scaleY = scale.value
            shadowElevation = if (dragging) 8.dp.toPx() else 0f
            shape = DashDesign.section
        }) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(OrbitSymbols.DragHandle, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.weight(1f))
                Box {
                    IconButton(enabled = !dragging, onClick = hapticClick { menu = true }) { Icon(OrbitSymbols.Tune, description) }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(up) }, enabled = shown.indexOf(id) > 0, onClick = { menu = false; move(-1) })
                        DropdownMenuItem(text = { Text(down) }, enabled = shown.indexOf(id) < shown.lastIndex, onClick = { menu = false; move(1) })
                    }
                }
            }
            content()
        }
    }
}
