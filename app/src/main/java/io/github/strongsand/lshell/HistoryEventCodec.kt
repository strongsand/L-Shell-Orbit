package io.github.strongsand.lshell

import io.github.strongsand.lshell.core.model.DishState
import java.util.Locale

internal enum class CanonicalHistoryEventType {
    CONNECTION_LOST, CONNECTION_RESTORED, ALERT_RAISED, ALERT_RESOLVED, ALERT,
    STATE, STATE_TRANSITION, PACKET_LOSS, LATENCY_PEAK, OBSTRUCTION_PEAK,
    DEVICE_RESTART, ETHERNET_CHANGED, EXTREME_TEST, NORMAL_TEST, DOWNLOAD_PEAK,
    BEACON, UNKNOWN
}

internal enum class HistoryAlert {
    THERMAL_THROTTLE, THERMAL_SHUTDOWN, POWER_SUPPLY_THERMAL, MOTORS_STUCK,
    MAST_NOT_VERTICAL, SLOW_ETHERNET, NO_ETHERNET, POWER_SAVE, INSTALL_PENDING,
    UNEXPECTED_LOCATION, HEATING, OBSTRUCTED, PACKET_LOSS
}

internal data class ParsedHistoryEvent(
    val type: CanonicalHistoryEventType,
    val raw: String,
    val payload: String = "",
    val alert: HistoryAlert? = null,
    val state: DishState? = null,
    val fromState: DishState? = null,
    val toState: DishState? = null
) {
    // Unknown text is part of the identity. Never merge unrelated future events.
    val semanticKey: String get() = when (type) {
        CanonicalHistoryEventType.ALERT, CanonicalHistoryEventType.ALERT_RAISED,
        CanonicalHistoryEventType.ALERT_RESOLVED -> "${type.name}:${alert?.name ?: payload}"
        CanonicalHistoryEventType.STATE -> "STATE:${state?.name}"
        CanonicalHistoryEventType.STATE_TRANSITION -> "TRANSITION:${fromState?.name}:${toState?.name}"
        CanonicalHistoryEventType.UNKNOWN, CanonicalHistoryEventType.BEACON,
        CanonicalHistoryEventType.EXTREME_TEST -> "${type.name}:$raw"
        else -> type.name
    }
    val alertKey: String get() = alert?.name ?: payload
}

/** Read-only compatibility for stored SQLite/JSON labels. Producers and file formats stay intact. */
internal object HistoryEventCodec {
    private fun key(value: String) = value.trim().lowercase(Locale.ROOT)
    private val aliases = buildMap<String, CanonicalHistoryEventType> {
        fun add(type: CanonicalHistoryEventType, vararg values: String) = values.forEach { put(key(it), type) }
        add(CanonicalHistoryEventType.CONNECTION_LOST, "Conexão perdida", "Conexão com a antena perdida", "Connection lost", "Dish connection lost", "Conexión perdida", "Conexión con la antena perdida")
        add(CanonicalHistoryEventType.CONNECTION_RESTORED, "Conexão restabelecida", "Conexão com a antena restabelecida", "Conexão restaurada", "Connection restored", "Dish connection restored", "Conexión restablecida", "Conexión con la antena restablecida", "Conexión restaurada")
        add(CanonicalHistoryEventType.PACKET_LOSS, "Perda elevada de pacotes", "High packet loss", "Pérdida elevada de paquetes")
        add(CanonicalHistoryEventType.LATENCY_PEAK, "Pico de latência", "Latency peak", "Pico de latencia")
        add(CanonicalHistoryEventType.OBSTRUCTION_PEAK, "Maior obstrução", "Highest obstruction", "Mayor obstrucción", "Obstrução")
        add(CanonicalHistoryEventType.DEVICE_RESTART, "Dishy reiniciada", "Antena reiniciada", "Dish restarted", "Antena reiniciada")
        add(CanonicalHistoryEventType.ETHERNET_CHANGED, "Velocidade Ethernet alterada", "Ethernet speed changed", "Velocidad Ethernet modificada")
        add(CanonicalHistoryEventType.DOWNLOAD_PEAK, "Maior tráfego de download observado", "Highest observed download traffic", "Mayor tráfico de descarga observado")
        add(CanonicalHistoryEventType.EXTREME_TEST, "Teste Extremo", "Teste Extremo concluído", "Extreme test", "Prueba extrema")
        add(CanonicalHistoryEventType.NORMAL_TEST, "Teste Único", "Teste de velocidade", "Speed test", "Prueba de velocidad")
        add(CanonicalHistoryEventType.BEACON, "Beacon conectado", "Beacon desconectado", "Beacon sincronizado")
    }
    private val alertAliases = buildMap<String, HistoryAlert> {
        fun add(type: HistoryAlert, vararg values: String) = values.forEach { put(key(it), type) }
        add(HistoryAlert.THERMAL_THROTTLE, "Estrangulamento Térmico Ativo (Thermal Throttle)", "Desempenho limitado por temperatura", "Thermal throttle", "THERMAL_THROTTLE")
        add(HistoryAlert.THERMAL_SHUTDOWN, "Desligamento de Emergência por Alta Temperatura", "Desligamento por alta temperatura", "Thermal shutdown", "THERMAL_SHUTDOWN")
        add(HistoryAlert.POWER_SUPPLY_THERMAL, "Fonte com limitação térmica", "POWER_SUPPLY_THERMAL_THROTTLE")
        add(HistoryAlert.MOTORS_STUCK, "Motores Travados ou Bloqueados", "MOTORS_STUCK")
        add(HistoryAlert.MAST_NOT_VERTICAL, "Mastro Inclinado (Não Vertical)", "MAST_NOT_NEAR_VERTICAL")
        add(HistoryAlert.SLOW_ETHERNET, "Cabo de Rede Limitado a 10/100 Mbps", "SLOW_ETHERNET_SPEEDS")
        add(HistoryAlert.NO_ETHERNET, "Sem conexão Ethernet", "NO_ETHERNET_LINK")
        add(HistoryAlert.POWER_SAVE, "Economia de energia ativa", "POWER_SAVE_IDLE")
        add(HistoryAlert.INSTALL_PENDING, "Instalação pendente", "INSTALL_PENDING")
        add(HistoryAlert.UNEXPECTED_LOCATION, "Localização Inesperada da Célula", "UNEXPECTED_LOCATION")
        add(HistoryAlert.HEATING, "Aquecimento para Derretimento de Gelo Ativo", "IS_HEATING")
        add(HistoryAlert.OBSTRUCTED, "Antena obstruída agora")
        add(HistoryAlert.PACKET_LOSS, "Perda elevada de pacotes na conexão")
    }
    private val stateAliases = buildMap<String, DishState> {
        DishState.entries.forEach { put(key(it.name), it); put(key(it.displayName), it) }
        fun add(state: DishState, vararg values: String) = values.forEach { put(key(it), state) }
        add(DishState.CONNECTED, "Connected", "Conectado")
        add(DishState.SEARCHING, "Searching for satellites", "Buscando satélites")
        add(DishState.UNKNOWN, "Unknown", "Desconocido")
        add(DishState.UNREACHABLE, "Unreachable", "Inalcanzable")
    }
    private val prefixes = listOf(
        "Alerta resolvido:" to CanonicalHistoryEventType.ALERT_RESOLVED,
        "Alert resolved:" to CanonicalHistoryEventType.ALERT_RESOLVED,
        "Alerta resuelta:" to CanonicalHistoryEventType.ALERT_RESOLVED,
        "Alerta:" to CanonicalHistoryEventType.ALERT_RAISED,
        "Alert:" to CanonicalHistoryEventType.ALERT_RAISED
    )

    fun parse(label: String): ParsedHistoryEvent {
        val value = label.trim()
        prefixes.firstOrNull { value.startsWith(it.first, ignoreCase = true) }?.let { (prefix, type) ->
            val payload = value.substring(prefix.length).trim()
            return ParsedHistoryEvent(type, label, payload, alertAliases[key(payload)])
        }
        val sides = value.split('→')
        if (sides.size == 2) {
            val from = stateAliases[key(sides[0])]
            val to = stateAliases[key(sides[1])]
            if (from != null && to != null) return ParsedHistoryEvent(
                CanonicalHistoryEventType.STATE_TRANSITION, label, fromState = from, toState = to)
        }
        aliases[key(value)]?.let { return ParsedHistoryEvent(it, label) }
        if (value.startsWith("Teste Extremo concluído:", ignoreCase = true)) return ParsedHistoryEvent(
            CanonicalHistoryEventType.EXTREME_TEST, label, value.substringAfter(':').trim())
        stateAliases[key(value)]?.let { return ParsedHistoryEvent(CanonicalHistoryEventType.STATE, label, state = it) }
        alertAliases[key(value)]?.let { return ParsedHistoryEvent(CanonicalHistoryEventType.ALERT, label, value, it) }
        return ParsedHistoryEvent(CanonicalHistoryEventType.UNKNOWN, label)
    }

    fun category(label: String): OccurrenceCategory = category(parse(label))
    fun category(event: ParsedHistoryEvent): OccurrenceCategory = when (event.type) {
        CanonicalHistoryEventType.CONNECTION_LOST, CanonicalHistoryEventType.CONNECTION_RESTORED,
        CanonicalHistoryEventType.STATE, CanonicalHistoryEventType.STATE_TRANSITION -> OccurrenceCategory.CONNECTION
        CanonicalHistoryEventType.EXTREME_TEST, CanonicalHistoryEventType.NORMAL_TEST -> OccurrenceCategory.TESTS
        CanonicalHistoryEventType.DEVICE_RESTART, CanonicalHistoryEventType.ETHERNET_CHANGED -> OccurrenceCategory.HARDWARE
        CanonicalHistoryEventType.ALERT, CanonicalHistoryEventType.ALERT_RAISED, CanonicalHistoryEventType.ALERT_RESOLVED -> when (event.alert) {
            HistoryAlert.THERMAL_THROTTLE, HistoryAlert.THERMAL_SHUTDOWN, HistoryAlert.POWER_SUPPLY_THERMAL,
            HistoryAlert.MAST_NOT_VERTICAL, HistoryAlert.MOTORS_STUCK, HistoryAlert.SLOW_ETHERNET, HistoryAlert.NO_ETHERNET -> OccurrenceCategory.HARDWARE
            else -> OccurrenceCategory.QUALITY
        }
        CanonicalHistoryEventType.PACKET_LOSS, CanonicalHistoryEventType.LATENCY_PEAK,
        CanonicalHistoryEventType.OBSTRUCTION_PEAK, CanonicalHistoryEventType.DOWNLOAD_PEAK -> OccurrenceCategory.QUALITY
        CanonicalHistoryEventType.BEACON -> OccurrenceCategory.BEACON
        CanonicalHistoryEventType.UNKNOWN -> OccurrenceCategory.OTHER
    }

    fun timelineTone(label: String): Int {
        val event = parse(label)
        return when (event.type) {
        CanonicalHistoryEventType.PACKET_LOSS -> 3
        CanonicalHistoryEventType.CONNECTION_LOST, CanonicalHistoryEventType.CONNECTION_RESTORED,
        CanonicalHistoryEventType.STATE, CanonicalHistoryEventType.STATE_TRANSITION -> 2
        CanonicalHistoryEventType.LATENCY_PEAK -> 1
        else -> if (event.alert == HistoryAlert.PACKET_LOSS) 3 else 0
        }
    }
    fun priority(label: String): Int = when (parse(label).type) {
        CanonicalHistoryEventType.CONNECTION_LOST -> 4
        CanonicalHistoryEventType.ALERT, CanonicalHistoryEventType.ALERT_RAISED, CanonicalHistoryEventType.ALERT_RESOLVED -> 3
        CanonicalHistoryEventType.PACKET_LOSS, CanonicalHistoryEventType.LATENCY_PEAK -> 2
        else -> 1
    }
}
