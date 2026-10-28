package io.github.strongsand.lshell

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.strongsand.lshell.beacon.*
import io.github.strongsand.lshell.core.model.*
import io.github.strongsand.lshell.core.network.grpc.RouterClientInfo
import io.github.strongsand.lshell.core.network.grpc.RouterClientRole
import io.github.strongsand.lshell.core.network.grpc.RouterClientInterface
import java.time.Instant
import java.time.ZoneId

/** Synthetic presentation only: no DB, controller, RPC or work scheduling. */
@Composable private fun RichHomePreview(dark: Boolean = false, amoled: Boolean = false,
    online: Boolean = true, beacon: Boolean = true, activeTest: Boolean = false, reliable: Boolean = false) {
    val snapshot = DishySnapshot(isOnline = online, state = if (online) DishState.CONNECTED else DishState.UNREACHABLE,
        deviceInfo = DishDeviceInfo(hardwareVersion = "Synthetic terminal", softwareVersion = "preview.1", uptimeSeconds = 3600, ethSpeedMbps = 1000),
        throughput = ThroughputStats(285_000_000f, 33_000_000f), latency = LatencyStats(21f, .001f, true))
    val device = BeaconDevice("preview", "Preview Beacon", "beacon.local", 80)
    val runtime = BeaconRuntimeStatus(device, lastSampleAtMillis = System.currentTimeMillis() - 180_000,
        pendingRecords = 12, recording = true)
    val report = remember(reliable) {
        val period = HealthPeriod.containing(HealthPeriodKind.WEEK, Instant.parse("2026-10-08T12:00:00Z").toEpochMilli(), ZoneId.of("UTC"))
        val observed = ((period.end - period.start) * if (reliable) .9 else .18).toLong()
        val day = ConnectionHealthAnalyzer.day(emptyList(), emptyList(), period.start, period.end, ZoneId.of("UTC"))
            .copy(observedMs = observed, stateMs = observed, onlineMs = observed, rawRows = 700)
        PreparedHealthReport.from(HealthReport(period, period.end, period.end, true, listOf(day), emptyList(),
            if (reliable) HealthConfidence.HIGH else HealthConfidence.LOW,
            if (reliable) HealthGrade.GOOD else HealthGrade.INSUFFICIENT, null, 0, emptyList(), aggregate = day))
    }
    CompositionLocalProvider(LocalExperience provides ExperienceSettings(theme = if (amoled) OrbitTheme.AMOLED else if (dark) OrbitTheme.DARK else OrbitTheme.LIGHT),
        LocalOrbitDark provides dark, LocalOrbitMotion provides false) {
        LShellTheme(darkTheme = dark) {
            Surface {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).padding(bottom = 80.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    DishStatusHeroCard(snapshot, if (online) 21.1f else null)
                    SectionHeading(stringResource(R.string.rich_connection_now))
                    DashboardMetrics(snapshot, powerWatts = if (online) 21.1f else null)
                    BeaconHomeCard(if (beacon) BeaconState.Recording(runtime) else BeaconState.NotConfigured, {})
                    HomeShortcutCards(if (activeTest) ConnectionTestState(kind = ConnectionTestKind.EXTREME,
                        phase = ConnectionTestPhase.RUNNING, completed = 1, total = 5) else ConnectionTestState(), report)
                    ObstructionCard(snapshot)
                    HardwareInfoCard(snapshot)
                }
            }
        }
    }
}

@Preview(name = "Home connected · light", locale = "pt", widthDp = 393, heightDp = 852)
@Composable private fun RichHomeLight() = RichHomePreview()
@Preview(name = "Home connected · dark", locale = "en", widthDp = 393, heightDp = 852)
@Composable private fun RichHomeDark() = RichHomePreview(dark = true)
@Preview(name = "Home AMOLED · reliable", locale = "pt", widthDp = 393, heightDp = 852)
@Composable private fun RichHomeAmoled() = RichHomePreview(dark = true, amoled = true, reliable = true)
@Preview(name = "Home offline · ES", locale = "es", widthDp = 393, heightDp = 852)
@Composable private fun RichHomeOffline() = RichHomePreview(online = false)
@Preview(name = "Home without Beacon", locale = "pt", widthDp = 393, heightDp = 852)
@Composable private fun RichHomeWithoutBeacon() = RichHomePreview(beacon = false)
@Preview(name = "Active extreme", locale = "pt", widthDp = 393, heightDp = 852)
@Composable private fun RichHomeActiveTest() = RichHomePreview(activeTest = true)
@Preview(name = "Font 1.15", locale = "pt", fontScale = 1.15f, widthDp = 393, heightDp = 852)
@Composable private fun RichHomeFont115() = RichHomePreview()
@Preview(name = "Font 1.3", locale = "pt", fontScale = 1.3f, widthDp = 393, heightDp = 852)
@Composable private fun RichHomeFont130() = RichHomePreview()
@Preview(name = "Large font · narrow", locale = "pt", fontScale = 2f, widthDp = 360, heightDp = 852)
@Composable private fun RichHomeLargeFont() = RichHomePreview()

@Composable private fun MeshPreview(count: Int) {
    MaterialTheme(colorScheme = darkColorScheme(), typography = DashDesign.typography) {
        Surface {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(androidx.compose.ui.res.pluralStringResource(R.plurals.rich_mesh_nodes, count), style = MaterialTheme.typography.titleLarge)
                repeat(count) { index -> RouterClientCard(RouterClientInfo(
                    if (index == 0) "Área externa com um nome muito longo para validar quebra de linha" else "Synthetic node ${index + 1}",
                    RouterClientRole.REPEATER, RouterClientInterface.WIFI_5_GHZ, "", emptyList(), "", -59f, 20f, 7200, 3_600_000_000, 190_000_000, true, reportedRole = 2)) }
                RouterClientCard(RouterClientInfo("Synthetic phone", RouterClientRole.CLIENT, RouterClientInterface.WIFI_24_GHZ, "", emptyList(), "",
                    null, null, 0, 0, 0, false, false, false, 1))
            }
        }
    }
}
@Preview(name = "One mesh node + client", locale = "pt", widthDp = 393, heightDp = 852)
@Composable private fun OneMeshNodePreview() = MeshPreview(1)
@Preview(name = "Many mesh nodes", locale = "en", widthDp = 393, heightDp = 852)
@Composable private fun ManyMeshNodesPreview() = MeshPreview(3)
@Preview(name = "Mesh · large font", locale = "es", fontScale = 1.3f, widthDp = 360, heightDp = 852)
@Composable private fun LargeFontMeshPreview() = MeshPreview(1)
