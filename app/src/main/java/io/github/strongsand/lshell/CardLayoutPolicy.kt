package io.github.strongsand.lshell

import kotlin.math.min

internal data class CardSlot(val id: String, val row: Int, val column: Int, val span: Int)

/** Layout IDs, spans and gesture policy only. No telemetry or pixel-sized preferences. */
internal object CardLayoutPolicy {
    /** Two semantic sizes, with hysteresis to avoid a haptic/relayout loop near the boundary. */
    fun resizeCompact(width: Float, fullWidth: Float, currentlyCompact: Boolean): Boolean {
        if (!width.isFinite() || !fullWidth.isFinite() || fullWidth <= 0f) return currentlyCompact
        return if (currentlyCompact) width < fullWidth * .79f else width < fullWidth * .71f
    }
    fun compactIds(saved: List<String>, eligible: Set<String>) = saved.filter { it in eligible }.toSet()

    fun columns(widthDp: Float, fontScale: Float): Int =
        if (widthDp / fontScale.coerceAtLeast(1f) >= 308f) 2 else 1

    /** Widths measured with actual localized text, icon, value style and font scale. */
    fun indicatorColumns(widthDp: Float, requiredWidthsDp: List<Float>): Int {
        val count = requiredWidthsDp.size
        val cell = requiredWidthsDp.filter { it.isFinite() }.maxOrNull() ?: return 1
        val candidates = if (count == 4) listOf(4, 2, 1) else (count downTo 1).toList()
        return candidates.firstOrNull { widthDp >= it * cell + (it - 1) * 8f } ?: 1
    }

    fun slots(ids: List<String>, compact: Set<String>, columns: Int): List<CardSlot> {
        var row = 0; var column = 0
        return ids.map { id ->
            val span = if (columns == 2 && id in compact) 1 else columns
            if (column + span > columns) { row++; column = 0 }
            CardSlot(id, row, column, span).also {
                column += span
                if (column == columns) { row++; column = 0 }
            }
        }
    }

    /** Reinsert in the visible order; filtered-out IDs retain their exact slots. */
    fun moveTo(order: List<String>, visible: List<String>, id: String, target: String): List<String> {
        val shown = order.filter { it in visible }
        val from = shown.indexOf(id); val to = shown.indexOf(target)
        if (from < 0 || to < 0 || from == to) return order
        val next = shown.toMutableList().apply { removeAt(from); add(to, id) }.iterator()
        return order.map { if (it in shown) next.next() else it }
    }

    /** Speed in px/s. Frame time is applied separately; no per-event fixed scrolling. */
    fun edgeSpeed(y: Float, top: Float, bottom: Float, density: Float): Float {
        if (!y.isFinite() || bottom <= top || density <= 0f) return 0f
        val edge = min(64f * density, (bottom - top) / 3f)
        val amount = when {
            y < top + edge -> -((top + edge - y) / edge).coerceIn(0f, 1f)
            y > bottom - edge -> ((y - bottom + edge) / edge).coerceIn(0f, 1f)
            else -> 0f
        }
        return 220f * density * amount * kotlin.math.abs(amount)
    }

    /** Do not scroll a whole editable section out of the lazy viewport while dragging it. */
    fun boundedScroll(delta: Float, groupTop: Float, groupBottom: Float, top: Float, bottom: Float): Float =
        if (delta < 0) delta.coerceAtLeast((groupTop - top).coerceAtMost(0f))
        else delta.coerceAtMost((groupBottom - bottom).coerceAtLeast(0f))
}
