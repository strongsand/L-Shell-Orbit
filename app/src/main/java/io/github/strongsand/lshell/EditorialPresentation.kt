package io.github.strongsand.lshell

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.strongsand.lshell.core.model.DishState

/** Display-only mapping: historical labels and telemetry identifiers remain intact. */
internal fun Context.telemetryText(value: String, technical: Boolean = false): String {
    return renderTelemetryText(value, technical) { id, args -> getString(id, *args) }
}

/** Same resource renderer used by Compose/notifications and JVM locale regression tests. */
internal fun renderTelemetryText(value: String, technical: Boolean = false,
    resolve: (Int, Array<out Any>) -> String): String {
    fun text(id: Int, vararg args: Any) = resolve(id, args)
    fun render(part: String) = renderTelemetryText(part, technical, resolve)
    if (technical && value == "Estrangulamento Térmico Ativo (Thermal Throttle)")
        return text(R.string.editorial_alert_thermal_detail)
    val event = HistoryEventCodec.parse(value)
    when (event.type) {
        CanonicalHistoryEventType.ALERT_RAISED -> return text(R.string.editorial_alert_prefix, render(event.payload))
        CanonicalHistoryEventType.ALERT_RESOLVED -> return text(R.string.editorial_alert_resolved_prefix, render(event.payload))
        CanonicalHistoryEventType.STATE_TRANSITION -> return text(R.string.domain_history_transition,
            render(event.fromState!!.displayName), render(event.toState!!.displayName))
        CanonicalHistoryEventType.CONNECTION_LOST -> return text(R.string.editorial_stored_123)
        CanonicalHistoryEventType.CONNECTION_RESTORED -> return text(R.string.editorial_stored_122)
        CanonicalHistoryEventType.EXTREME_TEST -> return if (event.payload.isNotEmpty())
            text(R.string.domain_history_extreme_completed, render(event.payload)) else text(R.string.function_extreme)
        CanonicalHistoryEventType.STATE -> if (value != event.state!!.displayName) return render(event.state.displayName)
        CanonicalHistoryEventType.PACKET_LOSS -> return text(R.string.editorial_moment_loss)
        CanonicalHistoryEventType.LATENCY_PEAK -> return text(R.string.editorial_moment_latency)
        CanonicalHistoryEventType.OBSTRUCTION_PEAK -> return text(R.string.editorial_moment_obstruction)
        CanonicalHistoryEventType.DEVICE_RESTART -> return text(R.string.editorial_moment_restart)
        CanonicalHistoryEventType.ETHERNET_CHANGED -> return text(R.string.editorial_moment_ethernet)
        CanonicalHistoryEventType.DOWNLOAD_PEAK -> return text(R.string.editorial_moment_download)
        else -> Unit
    }
    event.alert?.let { return text(when (it) {
        HistoryAlert.THERMAL_THROTTLE -> R.string.editorial_alert_thermal
        HistoryAlert.THERMAL_SHUTDOWN -> R.string.editorial_alert_shutdown
        HistoryAlert.POWER_SUPPLY_THERMAL -> R.string.editorial_alert_supply
        HistoryAlert.MOTORS_STUCK -> R.string.editorial_alert_motors
        HistoryAlert.MAST_NOT_VERTICAL -> R.string.editorial_alert_mast
        HistoryAlert.SLOW_ETHERNET -> R.string.editorial_alert_ethernet
        HistoryAlert.NO_ETHERNET -> R.string.editorial_alert_no_ethernet
        HistoryAlert.POWER_SAVE -> R.string.editorial_alert_saving
        HistoryAlert.INSTALL_PENDING -> R.string.editorial_alert_install
        HistoryAlert.UNEXPECTED_LOCATION -> R.string.editorial_alert_location
        HistoryAlert.HEATING -> R.string.editorial_alert_heating
        HistoryAlert.OBSTRUCTED -> R.string.editorial_alert_obstructed
        HistoryAlert.PACKET_LOSS -> R.string.editorial_alert_loss
    }) }
    if (value.startsWith("Resolvido após ")) return text(R.string.domain_history_resolved_after, value.removePrefix("Resolvido após "))
    if (value.startsWith("Falha durou ")) return text(R.string.domain_history_outage_duration, value.removePrefix("Falha durou "))
    // Existing Extreme summaries are stored text. Decode only the complete known grammar.
    Regex("^(\\d+) testes(?: · (\\d+) sem resultado)?(?: · perda máxima ([\\d.,]+)%)?$")
        .matchEntire(value)?.let { match ->
            return buildList {
                add(text(R.string.domain_history_test_count, match.groupValues[1]))
                match.groupValues[2].takeIf { it.isNotEmpty() }?.let { add(text(R.string.domain_history_no_result_count, it)) }
                match.groupValues[3].takeIf { it.isNotEmpty() }?.let { add(text(R.string.domain_history_peak_loss, it)) }
            }.joinToString(" · ")
        }
    val id = when (value) {
    "Desconhecido" -> R.string.editorial_state_unknown
    "Conectado" -> R.string.editorial_state_connected
    "Buscando Satélites" -> R.string.editorial_state_searching
    "Inicializando" -> R.string.editorial_state_booting
    "Recolhida (Stowed)" -> R.string.editorial_state_stowed
    "Proteção Térmica" -> R.string.editorial_state_thermal_shutdown
    "Sem Satélites no FOV" -> R.string.editorial_state_no_sats
    "Visada Obstruída" -> R.string.editorial_state_obstructed
    "Sem enlace de descida" -> R.string.editorial_state_no_downlink
    "Sem resposta de rede" -> R.string.editorial_state_no_pings
    "Ajustando antena" -> R.string.editorial_state_actuator_activity
    "Testando cabo" -> R.string.editorial_state_cable_test
    "Em repouso" -> R.string.editorial_state_sleeping
    "Rádio desativado" -> R.string.editorial_state_inhibit_rf
    "Inalcançável (Fora da Rede)" -> R.string.editorial_state_unreachable
        "Não houve dados suficientes neste período" -> R.string.domain_history_insufficient
        "Sem resolução registrada" -> R.string.domain_history_unresolved
        "Concluído" -> R.string.product_completed
        "O período teve monitoramento parcial" -> R.string.editorial_stored_124
        "Conexão estável durante o período monitorado" -> R.string.editorial_stored_125
        "Conexão estável, com pequenas interrupções" -> R.string.editorial_stored_126
        "Foram detectadas interrupções relevantes" -> R.string.editorial_stored_127
        "A conexão apresentou instabilidade no período monitorado" -> R.string.editorial_stored_128
        else -> null
    }
    return id?.let { text(it) } ?: value
}

@Composable
internal fun telemetryLabel(value: String): String =
    LocalContext.current.telemetryText(value, LocalExperience.current.technical)

@Composable
internal fun dishStateLabel(state: DishState): String = telemetryLabel(state.displayName)

@Composable
internal fun signalLabel(aboveNoise: Boolean?, detailed: Boolean = false): String = stringResource(when (aboveNoise) {
    true -> if (detailed) R.string.editorial_signal_above else R.string.editorial_signal_adequate
    false -> if (detailed) R.string.editorial_signal_not_above else R.string.editorial_signal_low
    null -> R.string.beacon_unknown
})

@Composable
internal fun uptimeLabel(seconds: Long): String {
    if (seconds <= 0L) return stringResource(R.string.beacon_unknown)
    val days = seconds / 86400L
    val hours = seconds % 86400L / 3600L
    val minutes = seconds % 3600L / 60L
    return if (days > 0L) stringResource(R.string.editorial_uptime_days, days, hours, minutes)
    else stringResource(R.string.editorial_uptime_hours, hours, minutes)
}

@Composable
internal fun storedDishStateLabel(name: String?): String {
    if (name == null) return stringResource(R.string.editorial_no_reading)
    val state = DishState.entries.firstOrNull { it.name == name }
    return state?.let { dishStateLabel(it) }
        ?: if (LocalExperience.current.technical) name else stringResource(R.string.beacon_unknown)
}
