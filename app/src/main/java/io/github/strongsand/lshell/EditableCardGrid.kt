package io.github.strongsand.lshell

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.BorderStroke
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.roundToInt

internal val LocalCardEditing = compositionLocalOf { false }

@Stable
internal class CardEditorScroll(val scroll: ScrollableState) {
    var viewport by mutableStateOf(Rect.Zero)
}

@Composable
internal fun rememberCardEditorScroll(scroll: ScrollableState) = remember(scroll) { CardEditorScroll(scroll) }
internal fun Modifier.cardEditorViewport(host: CardEditorScroll) = onGloballyPositioned { host.viewport = it.boundsInRoot() }

private data class CardRelease(val id: String, val rootPosition: Offset, val sequence: Long)

@Stable
private class CardDragState(val arrangement: CardArrangement, val haptics: DashHaptics) {
    var active by mutableStateOf<String?>(null)
    var resizing by mutableStateOf(false)
    var cancelResize: (() -> Unit)? = null
    var selectedRootBounds: () -> Rect? = { null }
    var pointerRoot by mutableStateOf(Offset.Zero)
    var root by mutableStateOf(Offset.Zero)
    var width = 0f
    var height = 0f
    private var sequence = 0L
    var grab = Offset.Zero
    var size = Size.Zero
    var visible = emptyList<String>()
    val bounds = mutableStateMapOf<String, Rect>()
    var release by mutableStateOf<CardRelease?>(null)
    private var before = emptyList<String>()
    private var lastTarget: String? = null

    fun floatingRoot(): Offset = Offset((pointerRoot.x - grab.x).coerceIn(root.x,
        (root.x + width - size.width).coerceAtLeast(root.x)), pointerRoot.y - grab.y)

    fun start(at: Offset) {
        val id = visible.firstOrNull { bounds[it]?.contains(at) == true } ?: return
        val rect = bounds.getValue(id)
        before = arrangement.order
        arrangement.select(id)
        release = null; active = id; lastTarget = null
        grab = at - rect.topLeft; size = rect.size; pointerRoot = root + at
        haptics.perform(DashFeedback.DRAG_STARTED)
    }

    fun target() {
        val id = active ?: return
        val center = floatingRoot() - root + Offset(size.width / 2, size.height / 2)
        val candidate = visible.firstOrNull { it != id && bounds[it]?.contains(center) == true }
        if (candidate == null) { lastTarget = null; return }
        if (candidate == lastTarget) return
        lastTarget = candidate
        arrangement.previewMove(visible, id, candidate)
        haptics.perform(DashFeedback.DRAG_SNAP)
    }

    fun end(cancel: Boolean) {
        val id = active ?: return
        release = CardRelease(id, floatingRoot(), ++sequence)
        active = null; lastTarget = null
        if (cancel) arrangement.restore(before) else {
            arrangement.commit()
            haptics.perform(DashFeedback.DRAG_COMPLETED)
        }
    }
}

/** A small non-lazy grid inside the existing screen scroll owner. Stable direct
 * children preserve chart inspection state when crossing rows; no nested scroller.
 */
@Composable
internal fun EditableCardGrid(
    arrangement: CardArrangement,
    ids: List<String> = arrangement.order,
    host: CardEditorScroll,
    label: @Composable (String) -> String,
    content: @Composable (String, Boolean) -> Unit
) {
    val haptics = rememberDashHaptics()
    val drag = remember(arrangement, haptics) { CardDragState(arrangement, haptics) }
    val density = LocalDensity.current
    val motion = LocalOrbitMotion.current
    val session = LocalCardEditorSession.current
    fun finish() {
        drag.end(cancel = true); drag.cancelResize?.invoke(); arrangement.finishEditing()
    }
    BackHandler(arrangement.editing) { finish() }
    val editing = arrangement.editing
    SideEffect {
        if (editing) session?.bind(arrangement, { drag.selectedRootBounds() },
            { drag.active != null || drag.resizing }, ::finish)
        else session?.release(arrangement)
    }
    SideEffect { drag.visible = ids }
    DisposableEffect(drag, session) { onDispose { finish(); session?.release(arrangement) } }
    LaunchedEffect(drag.active, host) {
        if (drag.active == null) return@LaunchedEffect
        var previousFrame = withFrameNanos { it }
        while (isActive && drag.active != null) {
            val frame = withFrameNanos { it }
            val seconds = ((frame - previousFrame) / 1_000_000_000f).coerceIn(0f, .032f)
            previousFrame = frame
            val speed = CardLayoutPolicy.edgeSpeed(drag.pointerRoot.y, host.viewport.top, host.viewport.bottom, density.density)
            if (abs(speed) > .1f) host.scroll.scrollBy(CardLayoutPolicy.boundedScroll(speed * seconds,
                drag.root.y, drag.root.y + drag.height, host.viewport.top, host.viewport.bottom))
            // Layout coordinates follow actual scrolling even when the finger is stationary.
            drag.target()
        }
    }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columns = CardLayoutPolicy.columns(maxWidth.value, density.fontScale)
            val slots = remember(ids, arrangement.compact, columns) { CardLayoutPolicy.slots(ids, arrangement.compact, columns) }
            val gap = with(density) { 12.dp.roundToPx() }
            Layout(modifier = Modifier.fillMaxWidth().onGloballyPositioned {
                drag.root = it.positionInRoot(); drag.width = it.size.width.toFloat(); drag.height = it.size.height.toFloat()
            }.pointerInput(drag) {
                try {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val hit = drag.visible.firstOrNull { drag.bounds[it]?.contains(down.position) == true }
                        if (hit != null) {
                            val start = if (arrangement.canDragDirectly(hit)) awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                                else awaitLongPressOrCancellation(down.id)
                            if (start != null) {
                                start.consume()
                                drag.start(down.position)
                                drag.pointerRoot = drag.root + start.position
                                val dropped = drag(start.id) { change ->
                                    change.consume(); drag.pointerRoot = drag.root + change.position; drag.target()
                                }
                                if (dropped) currentEvent.changes.forEach { it.consume() }
                                drag.end(!dropped)
                            }
                        }
                    }
                } finally { drag.end(true) }
            }, content = {
                ids.forEach { id -> key(id) {
                    EditableCard(id, label(id), drag, motion, columns == 2) {
                        CompositionLocalProvider(LocalCardEditing provides arrangement.editing) {
                            content(id, columns == 2 && id in arrangement.compact)
                        }
                    }
                } }
            }) { measurables, constraints ->
                val cell = (constraints.maxWidth - gap * (columns - 1)) / columns
                val measured = measurables.mapIndexed { index, measurable ->
                    val span = slots[index].span
                    measurable.measure(Constraints.fixedWidth(cell * span + gap * (span - 1)))
                }
                val rows = slots.groupBy { it.row }
                val rowHeights = rows.mapValues { (_, cells) -> cells.maxOf { measured[ids.indexOf(it.id)].height } }
                val rowY = mutableMapOf<Int, Int>()
                var height = 0
                rowHeights.toSortedMap().forEach { (row, h) -> rowY[row] = height; height += h + gap }
                layout(constraints.maxWidth, (height - if (rows.isEmpty()) 0 else gap).coerceAtLeast(0)) {
                    measured.forEachIndexed { index, placeable ->
                        val slot = slots[index]
                        placeable.placeRelative(slot.column * (cell + gap), rowY.getValue(slot.row))
                    }
                }
            }
        }
}

@Composable
private fun EditableCard(id: String, label: String, drag: CardDragState, motion: Boolean, twoColumns: Boolean, content: @Composable () -> Unit) {
    val arrangement = drag.arrangement
    val editorSession = LocalCardEditorSession.current
    fun finishSelected() { editorSession?.finish() ?: arrangement.finishEditing() }
    val active = drag.active == id
    val selected = arrangement.editing && arrangement.selected == id
    val release = drag.release?.takeIf { it.id == id }
    val selectionAlpha = animateFloatAsState(if (selected) 1f else 0f,
        OrbitMotion.spec(OrbitMotionRole.STATE, motion), label = "card selection")
    var position by remember { mutableStateOf<Offset?>(null) }
    var initialized by remember { mutableStateOf(false) }
    var released by remember { mutableStateOf<CardRelease?>(null) }
    val origin = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val scale = animateFloatAsState(if ((active || selected) && motion) 1.015f else 1f,
        OrbitMotion.spec(OrbitMotionRole.TOUCH, motion), label = "card lift")
    LaunchedEffect(position, active, release, motion) {
        val target = position ?: return@LaunchedEffect
        if (active) return@LaunchedEffect
        if (!initialized) { origin.snapTo(target); initialized = true }
        if (release != null && released != release) { origin.snapTo(release.rootPosition - drag.root); released = release }
        origin.animateTo(target, OrbitMotion.spec(OrbitMotionRole.SPATIAL, motion))
        if (drag.release == release) drag.release = null
    }
    fun drawnOrigin(): Offset = when {
        active -> drag.floatingRoot() - drag.root
        release != null && released != release -> release.rootPosition - drag.root
        initialized -> origin.value
        else -> position ?: Offset.Zero
    }
    SideEffect {
        if (selected) drag.selectedRootBounds = {
            drag.bounds[id]?.let { rect ->
                val extra = Offset(rect.width, rect.height) * ((scale.value - 1f) / 2f)
                Rect(drag.root + drawnOrigin() - extra,
                    Size(rect.width * scale.value, rect.height * scale.value))
            }
        }
    }
    val done = stringResource(R.string.cards_done)
    val up = stringResource(R.string.chart_move_up)
    val down = stringResource(R.string.chart_move_down)
    val resize = stringResource(if (id in arrangement.compact) R.string.cards_normal else R.string.cards_compact, label)
    val start = stringResource(R.string.cards_edit_card, label)
    val options = stringResource(R.string.cards_options, label)
    var menu by remember { mutableStateOf(false) }
    val shown = arrangement.order.filter { it in drag.visible }
    val sizeState = stringResource(if (id in arrangement.compact) R.string.cards_size_compact else R.string.cards_size_normal)
    val spokenState = stringResource(R.string.cards_accessibility_state, sizeState, shown.indexOf(id) + 1, shown.size)
    fun move(direction: Int) { arrangement.accessibleMove(drag.visible, id, direction); drag.haptics.perform(DashFeedback.DRAG_SNAP) }
    fun resize() {
        arrangement.toggleSize(id)
        drag.haptics.perform(if (id in arrangement.compact) DashFeedback.TOGGLE_ON else DashFeedback.TOGGLE_OFF)
    }
    Box(Modifier.fillMaxWidth().onPlaced {
        val point = it.positionInParent()
        position = point
        drag.bounds[id] = Rect(point, Size(it.size.width.toFloat(), it.size.height.toFloat()))
    }.zIndex(if (active || release != null) 2f else 0f).semantics {
        stateDescription = spokenState
        customActions = buildList {
            add(CustomAccessibilityAction(start) { arrangement.select(id); drag.haptics.perform(DashFeedback.DRAG_STARTED); true })
            if (shown.indexOf(id) > 0) add(CustomAccessibilityAction(up) { move(-1); true })
            if (shown.indexOf(id) < shown.lastIndex) add(CustomAccessibilityAction(down) { move(1); true })
            if (id in arrangement.eligible) add(CustomAccessibilityAction(resize) { resize(); true })
            if (selected) add(CustomAccessibilityAction(done) { finishSelected(); true })
        }
    }) {
        Box(Modifier.fillMaxWidth().graphicsLayer {
            val at = position ?: Offset.Zero
            val drawn = drawnOrigin()
            translationX = drawn.x - at.x; translationY = drawn.y - at.y
            scaleX = scale.value; scaleY = scale.value
            shadowElevation = 8.dp.toPx() * if (active) 1f else selectionAlpha.value.coerceIn(0f, 1f)
            shape = DashDesign.section
        }) {
            content()
            val tint = MaterialTheme.colorScheme.primary
            Canvas(Modifier.matchParentSize()) {
                val alpha = selectionAlpha.value.coerceIn(0f, 1f)
                if (alpha > 0f) drawRoundRect(tint.copy(alpha = alpha),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(24.dp.toPx()), style = Stroke(2.dp.toPx()))
            }
            if (selected) {
                Box(Modifier.align(Alignment.TopEnd)) {
                    IconButton(enabled = drag.active == null, onClick = hapticClick { menu = true }) { Icon(OrbitSymbols.Tune, options) }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(done) }, onClick = { menu = false; finishSelected() })
                        DropdownMenuItem(text = { Text(up) }, enabled = shown.indexOf(id) > 0,
                            onClick = { menu = false; move(-1) })
                        DropdownMenuItem(text = { Text(down) }, enabled = shown.indexOf(id) < shown.lastIndex,
                            onClick = { menu = false; move(1) })
                        if (id in arrangement.eligible) DropdownMenuItem(text = { Text(resize) },
                            onClick = { menu = false; resize() })
                    }
                }
                if (twoColumns && id in arrangement.eligible && !active) {
                    ResizeHandle(id, label, drag, Modifier.align(Alignment.CenterStart), -1f)
                    ResizeHandle(id, label, drag, Modifier.align(Alignment.CenterEnd), 1f)
                }
            }
        }
    }
}

@Composable
private fun ResizeHandle(id: String, label: String, drag: CardDragState, modifier: Modifier, direction: Float) {
    val arrangement = drag.arrangement
    var root by remember { mutableStateOf(Offset.Zero) }
    val description = stringResource(R.string.cards_resize_handle, label)
    var resizing by remember { mutableStateOf(false) }
    var before by remember { mutableStateOf(emptySet<String>()) }
    var startX by remember { mutableFloatStateOf(0f) }; var width by remember { mutableFloatStateOf(0f) }
    fun end(cancel: Boolean) {
        if (!resizing) return
        resizing = false; drag.resizing = false; drag.cancelResize = null
        if (cancel) arrangement.restoreSizes(before) else {
            arrangement.commit(); drag.haptics.perform(DashFeedback.DRAG_COMPLETED)
        }
    }
    DisposableEffect(id) { onDispose { end(true) } }
    Box(modifier.size(48.dp).onGloballyPositioned { root = it.positionInRoot() }
        .pointerInput(id) {
            try { detectDragGestures(onDragStart = {
                before = arrangement.compact; resizing = true; drag.resizing = true
                arrangement.beginInteraction(); drag.cancelResize = { end(true) }
                startX = root.x + it.x; width = drag.bounds[id]?.width ?: 0f
                drag.haptics.perform(DashFeedback.DRAG_STARTED)
            }, onDragEnd = { end(false) }, onDragCancel = { end(true) }) { change, _ ->
                change.consume()
                val target = CardLayoutPolicy.resizeCompact(width + (root.x + change.position.x - startX) * direction,
                    drag.width, id in arrangement.compact)
                if (target != (id in arrangement.compact)) {
                    arrangement.previewSize(id, target); drag.haptics.perform(DashFeedback.DRAG_SNAP)
                }
            } } finally { end(true) }
        }.semantics {
            contentDescription = description
            role = Role.Button
            onClick(label = description) {
                arrangement.toggleSize(id); drag.haptics.perform(DashFeedback.DRAG_SNAP); true
            }
        }, contentAlignment = Alignment.Center) {
        Surface(Modifier.size(14.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primary,
            border = BorderStroke(2.dp, MaterialTheme.colorScheme.surface)) {}
    }
}
