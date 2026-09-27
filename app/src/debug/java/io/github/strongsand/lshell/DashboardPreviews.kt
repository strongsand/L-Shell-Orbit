package io.github.strongsand.lshell

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.github.strongsand.lshell.core.model.*

// Static design previews: no repository, network, camera or monitoring service.
private val previewTerminal = DishySnapshot(
    isOnline = true,
    state = DishState.CONNECTED,
    throughput = ThroughputStats(downlinkBps = 9_300_000f, uplinkBps = 1_200_000f),
    latency = LatencyStats(popPingLatencyMs = 21f, popPingDropRate = 0f, isSnrAboveNoiseFloor = true),
    obstruction = DishObstruction(fractionObstructed = 0.001f, validSeconds = 3600f),
    deviceInfo = DishDeviceInfo(hardwareVersion = "rev4_panda_prod1",
        softwareVersion = "2026.09.08.mr86422.53947", uptimeSeconds = 126960, ethSpeedMbps = 1000)
)

@Preview(name = "Compacto · claro", widthDp = 360, heightDp = 800, showBackground = true)
@Preview(name = "Compacto · escuro", widthDp = 360, heightDp = 800, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Fonte ampliada", widthDp = 320, heightDp = 800, fontScale = 1.6f)
@Preview(name = "Paisagem · tela larga", widthDp = 1000, heightDp = 600)
@Composable
private fun ConnectedDashboardPreview() {
    LShellTheme {
        LShellApp(previewTerminal, emptyList(), emptyList())
    }
}

@Preview(name = "Alerta · valores grandes", widthDp = 360, heightDp = 800)
@Composable
private fun WarningDashboardPreview() {
    LShellTheme {
        LShellApp(previewTerminal.copy(
            state = DishState.THERMAL_SHUTDOWN,
            alerts = DishAlerts(thermalShutdown = true),
            throughput = ThroughputStats(downlinkBps = 1_234_500_000f, uplinkBps = 100_000_000f),
            obstruction = DishObstruction(currentlyObstructed = true, fractionObstructed = 0.2f)
        ), emptyList(), emptyList())
    }
}

@Preview(name = "Sem dados", widthDp = 320, heightDp = 740)
@Composable
private fun WaitingDashboardPreview() {
    LShellTheme {
        LShellApp(DishySnapshot(), emptyList(), emptyList())
    }
}
