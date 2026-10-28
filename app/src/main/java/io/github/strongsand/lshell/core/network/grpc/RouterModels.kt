package io.github.strongsand.lshell.core.network.grpc

import io.github.strongsand.lshell.proto.WifiAlerts

enum class RouterAlert { THERMAL_THROTTLE, INSTALL_PENDING, LAN_ETH_SLOW_LINK, WAN_ETH_POOR_CONNECTION }
enum class RouterBootReason {
    UNKNOWN, FORGOTTEN, POWER_CYCLE, COMMAND, SOFTWARE_UPDATE, CONFIG_UPDATE, OTHER;
    companion object {
        fun fromWire(value: Int) = entries.getOrNull(value)?.takeUnless { it == OTHER } ?: OTHER
    }
}
data class RouterBootCause(val code: Int) { val reason: RouterBootReason get() = RouterBootReason.fromWire(code) }
enum class RouterClientRole {
    UNKNOWN, CLIENT, REPEATER, CONTROLLER;
    companion object { fun fromWire(value: Int) = entries.getOrNull(value) ?: UNKNOWN }
}
enum class RouterClientInterface {
    UNKNOWN, ETHERNET, WIFI_24_GHZ, WIFI_5_GHZ, WIFI_5_GHZ_HIGH;
    companion object { fun fromWire(value: Int) = entries.getOrNull(value) ?: UNKNOWN }
}
internal fun WifiAlerts.toRouterAlerts(): List<RouterAlert> = buildList {
    if (thermalThrottle) add(RouterAlert.THERMAL_THROTTLE)
    if (installPending) add(RouterAlert.INSTALL_PENDING)
    if (lanEthSlowLink10 || lanEthSlowLink100) add(RouterAlert.LAN_ETH_SLOW_LINK)
    if (wanEthPoorConnection) add(RouterAlert.WAN_ETH_POOR_CONNECTION)
}
