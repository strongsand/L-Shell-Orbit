package io.github.strongsand.lshell

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.text.Normalizer
import java.util.Locale

data class OrbitFunction(val route: String, val title: Int, val group: Int)
internal val orbitFunctions = listOf(
    OrbitFunction("TRAFFIC", R.string.traffic_title, R.string.functions_monitoring),
    OrbitFunction("HISTORY", R.string.function_history, R.string.functions_monitoring),
    OrbitFunction("REPORTS", R.string.function_reports, R.string.functions_monitoring),
    OrbitFunction("OUTAGES", R.string.function_outages, R.string.functions_monitoring),
    OrbitFunction("ENERGY", R.string.function_energy, R.string.functions_monitoring),
    OrbitFunction("ALIGNMENT", R.string.function_alignment, R.string.functions_starlink),
    OrbitFunction("HEALTH", R.string.function_health, R.string.functions_tests),
    OrbitFunction("SPEED", R.string.function_speed, R.string.functions_tests),
    OrbitFunction("SCHEDULED_TESTS", R.string.auto_scheduled_title, R.string.functions_tests),
    OrbitFunction("SMART_TESTS", R.string.auto_smart_title, R.string.functions_tests),
    OrbitFunction("EXTREME_HISTORY", R.string.function_test_history, R.string.functions_tests),
    OrbitFunction("BEACON", R.string.beacon_title, R.string.functions_beacon),
    OrbitFunction("PREFERENCES", R.string.settings_title, R.string.settings_title)
)

fun matchesFunctionSearch(query: String, title: String, group: String): Boolean {
    fun normalized(text: String) = Normalizer.normalize(text, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "").lowercase(Locale.ROOT)
        .replace(Regex("coes\\b"), "cao")
    val searchable = normalized("$title $group")
    return normalized(query).trim().split(Regex("\\s+")).all { it in searchable }
}

@Composable
fun FunctionDirectory(onNavigate: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val searchFocus = remember { FocusRequester() }
    val localized = orbitFunctions.map {
        Triple(it, stringResource(it.title), stringResource(it.group))
    }
    val palette = MaterialTheme.colorScheme
    TextField(query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth().focusRequester(searchFocus), singleLine = true,
        shape = RoundedCornerShape(28.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = palette.surfaceContainerHigh,
            unfocusedContainerColor = palette.surfaceContainerHigh,
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
        label = { Text(stringResource(R.string.functions_search)) },
        leadingIcon = { Icon(OrbitSymbols.Search, null) },
        trailingIcon = {
            if (query.isNotEmpty()) IconButton(onClick = hapticClick {
                query = ""
                searchFocus.requestFocus()
            }) { Icon(OrbitSymbols.Clear, stringResource(R.string.ux_clear_search)) }
        })
    val filtered = remember(query, localized) { localized.filter { matchesFunctionSearch(query, it.second, it.third) } }
    if (filtered.isEmpty()) Column(Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.functions_no_results), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.ux_search_empty_detail), style = MaterialTheme.typography.bodyMedium,
            color = palette.onSurfaceVariant)
    }
    val grouped = remember(filtered) { filtered.groupBy { it.first.group } }
    grouped.forEach { (_, functions) ->
        Surface(shape = RoundedCornerShape(topStart = 32.dp, topEnd = 16.dp, bottomEnd = 32.dp, bottomStart = 16.dp),
            color = palette.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(functions.first().third, Modifier.padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 10.dp)
                    .semantics { heading() }, style = MaterialTheme.typography.titleSmall,
                    color = palette.primary)
                functions.forEach { (function, title, _) ->
                    val emphasized = function.route == "TRAFFIC"
                    val interaction = remember(function.route) { androidx.compose.foundation.interaction.MutableInteractionSource() }
                    Surface(onClick = hapticClick(DashFeedback.NAVIGATION) { onNavigate(function.route) }, interactionSource = interaction,
                        shape = RoundedCornerShape(20.dp),
                        color = amoledCardColor(if (emphasized) palette.primaryContainer else palette.surfaceContainerLow),
                        contentColor = amoledCardContent(if (emphasized) palette.onPrimaryContainer else palette.onSurface),
                        modifier = Modifier.fillMaxWidth().pressMotion(interaction, .985f).heightIn(min = 72.dp)) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Surface(shape = RoundedCornerShape(14.dp),
                                color = if (emphasized) palette.primary else palette.secondaryContainer,
                                contentColor = if (emphasized) palette.onPrimary else palette.onSecondaryContainer) {
                                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                                    Icon(functionIcon(function.route), null, Modifier.size(22.dp))
                                }
                            }
                            Text(title, Modifier.weight(1f).orbitSharedBounds("destination:${function.route}"),
                                style = MaterialTheme.typography.titleMedium)
                            Icon(OrbitSymbols.ChevronRight, null, Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    }
}

private fun functionIcon(route: String): ImageVector = when (route) {
    "TRAFFIC" -> OrbitSymbols.ShowChart
    "HISTORY" -> OrbitSymbols.History
    "TIMELINE" -> OrbitSymbols.Timeline
    "REPORTS" -> OrbitSymbols.Assessment
    "OUTAGES" -> OrbitSymbols.WarningAmber
    "ENERGY" -> OrbitSymbols.Bolt
    "ALIGNMENT" -> OrbitSymbols.Explore
    "HEALTH" -> OrbitSymbols.HealthAndSafety
    "SPEED" -> OrbitSymbols.Speed
    "SCHEDULED_TESTS" -> OrbitSymbols.Schedule
    "SMART_TESTS" -> OrbitSymbols.AutoAwesome
    "EXTREME_HISTORY" -> OrbitSymbols.ListAlt
    "BEACON" -> OrbitSymbols.Router
    else -> OrbitSymbols.Tune
}
