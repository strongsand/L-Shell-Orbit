package io.github.strongsand.lshell

import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext

@Stable
internal class CardArrangement(
    initialOrder: List<String>, initialCompact: Set<String>, val eligible: Set<String>,
    private val persist: (List<String>, Set<String>) -> Unit
) {
    var order by mutableStateOf(initialOrder); private set
    var compact by mutableStateOf(initialCompact.intersect(eligible)); private set
    var editing by mutableStateOf(false)
    var selected by mutableStateOf<String?>(null)
    var interactionEpoch = 0L; private set
    private var savedOrder = initialOrder
    private var savedCompact = compact
    fun beginInteraction() { interactionEpoch++ }
    fun select(id: String) { beginInteraction(); editing = true; selected = id }
    fun canDragDirectly(id: String) = editing && selected == id
    fun finishEditing() { commit(); editing = false; selected = null }
    fun previewMove(visible: List<String>, id: String, target: String) {
        order = CardLayoutPolicy.moveTo(order, visible, id, target)
    }
    fun restore(beforeDrag: List<String>) { order = beforeDrag }
    fun commit() {
        if (order == savedOrder && compact == savedCompact) return
        persist(order, compact); savedOrder = order; savedCompact = compact
    }
    fun toggleSize(id: String) {
        if (id !in eligible) return
        compact = if (id in compact) compact - id else compact + id
        commit()
    }
    fun previewSize(id: String, small: Boolean) {
        if (id in eligible) compact = if (small) compact + id else compact - id
    }
    fun restoreSizes(saved: Set<String>) { compact = saved.intersect(eligible) }
    fun accessibleMove(visible: List<String>, id: String, direction: Int) {
        val shown = order.filter { it in visible }
        if (id !in shown) return
        shown.getOrNull(shown.indexOf(id) + direction)?.let { previewMove(visible, id, it); commit() }
    }
}

/** Existing order keys are preserved. Compact flags are an independent, optional local preference. */
@Composable
internal fun rememberCardArrangement(group: String, defaults: List<String>,
    defaultCompact: Set<String> = emptySet(), eligible: Set<String> = defaults.toSet()): CardArrangement {
    val context = LocalContext.current.applicationContext
    return remember(context, group) {
        val orderPrefs = ChartOrderPreferences(context, group, defaults)
        val sizePrefs = context.getSharedPreferences("card_layout", Context.MODE_PRIVATE)
        val sizes = sizePrefs.getString(group, null)?.let { CardLayoutPolicy.compactIds(it.split(','), eligible) }
            ?: defaultCompact.intersect(eligible)
        CardArrangement(orderPrefs.load(), sizes, eligible) { order, compact ->
            orderPrefs.save(order)
            sizePrefs.edit().putString(group, defaults.filter { it in compact }.joinToString(",")).apply()
        }
    }
}
