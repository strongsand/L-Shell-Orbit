package io.github.strongsand.lshell

import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext

/** Stable IDs, not translated titles. Unknown/duplicate IDs cannot hide new charts. */
internal object ChartOrderPolicy {
    fun normalize(saved: List<String>, defaults: List<String>): List<String> =
        (saved.filter { it in defaults } + defaults).distinct()

    fun move(order: List<String>, visible: List<String>, id: String, direction: Int): List<String> {
        val shown = order.filter { it in visible }
        val from = shown.indexOf(id)
        val to = from + direction
        if (from < 0 || to !in shown.indices) return order
        val target = shown[to]
        return order.toMutableList().apply {
            val a = indexOf(id); val b = indexOf(target)
            this[a] = target; this[b] = id
        }
    }
}

internal class ChartOrderPreferences(context: Context, private val group: String, private val defaults: List<String>) {
    private val prefs = context.applicationContext.getSharedPreferences("chart_order", Context.MODE_PRIVATE)
    fun load() = ChartOrderPolicy.normalize(prefs.getString(group, "").orEmpty().split(','), defaults)
    fun save(order: List<String>) { prefs.edit().putString(group, ChartOrderPolicy.normalize(order, defaults).joinToString(",")).apply() }
}

@Composable
internal fun rememberChartOrder(group: String, defaults: List<String>): Pair<List<String>, (List<String>) -> Unit> {
    val context = LocalContext.current
    val prefs = remember(context, group) { ChartOrderPreferences(context, group, defaults) }
    var order by remember(prefs) { mutableStateOf(prefs.load()) }
    return order to { next -> order = ChartOrderPolicy.normalize(next, defaults); prefs.save(order) }
}

