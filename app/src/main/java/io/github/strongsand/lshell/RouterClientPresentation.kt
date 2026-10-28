package io.github.strongsand.lshell

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.strongsand.lshell.core.network.grpc.*

/** Labels for already-reported fields; does not infer AP association or backhaul. */
internal object RouterClientPresentation {
    fun roleLabel(role: RouterClientRole) = when (role) {
        RouterClientRole.CLIENT -> R.string.router_role_client
        RouterClientRole.REPEATER -> R.string.rich_mesh_node
        RouterClientRole.CONTROLLER -> R.string.router_role_controller
        else -> R.string.router_role_unknown
    }
    fun interfaceLabel(value: RouterClientInterface): Int = when (value) {
        RouterClientInterface.ETHERNET -> R.string.domain_router_ethernet
        RouterClientInterface.WIFI_24_GHZ -> R.string.rich_wifi_24
        RouterClientInterface.WIFI_5_GHZ -> R.string.domain_router_wifi_5
        RouterClientInterface.WIFI_5_GHZ_HIGH -> R.string.rich_wifi_5_high
        RouterClientInterface.UNKNOWN -> R.string.rich_interface_unknown
    }
    fun alertLabel(value: RouterAlert): Int = when (value) {
        RouterAlert.THERMAL_THROTTLE -> R.string.domain_router_thermal
        RouterAlert.INSTALL_PENDING -> R.string.domain_router_update
        RouterAlert.LAN_ETH_SLOW_LINK -> R.string.domain_router_slow_ethernet
        RouterAlert.WAN_ETH_POOR_CONNECTION -> R.string.domain_router_dish_link
    }
    fun bootLabel(value: RouterBootReason): Int = when (value) {
        RouterBootReason.UNKNOWN -> R.string.editorial_state_unknown
        RouterBootReason.FORGOTTEN -> R.string.domain_router_boot_forgotten
        RouterBootReason.POWER_CYCLE -> R.string.domain_router_boot_power
        RouterBootReason.COMMAND -> R.string.domain_router_boot_command
        RouterBootReason.SOFTWARE_UPDATE -> R.string.domain_router_boot_software
        RouterBootReason.CONFIG_UPDATE -> R.string.domain_router_boot_config
        RouterBootReason.OTHER -> R.string.domain_router_boot_other
    }
    fun counters(client: RouterClientInfo): List<Pair<Int, Long>> = buildList {
        if (client.receivedBytesAvailable) add(R.string.router_received to client.receivedBytes)
        if (client.sentBytesAvailable) add(R.string.router_sent to client.sentBytes)
    }
}

@Composable
internal fun routerClientName(client: RouterClientInfo): String =
    client.name.ifBlank { stringResource(R.string.domain_router_unnamed) }

@Composable
internal fun routerBootLabel(cause: RouterBootCause): String =
    if (cause.reason == RouterBootReason.OTHER) stringResource(R.string.domain_router_boot_other, cause.code)
    else stringResource(RouterClientPresentation.bootLabel(cause.reason))
