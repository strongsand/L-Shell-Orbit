package io.github.strongsand.lshell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource

private data class Credit(val name: String, val use: String, val url: String, val licenseUrl: String? = null)

@Composable
internal fun CreditsSheet(onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = DashDesign.hero) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(R.string.settings_credits), style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.expressiveReveal("credits-title"))
            Text(stringResource(R.string.ui_technologies_and_sources_used_in_l_shell_orbit_links_open_the_pro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.expressiveReveal("credits-description", 35))
            CreditGroup(stringResource(R.string.ui_interface_and_navigation), 0, listOf(
                Credit(stringResource(R.string.ui_jetpack_compose_and_material_3), stringResource(R.string.ui_interface_components_and_animations), "https://developer.android.com/develop/ui/compose"),
                Credit("Material Icons", stringResource(R.string.ui_interface_icons), "https://fonts.google.com/icons"),
                Credit("Material Symbols", stringResource(R.string.symbols_credit_use), "https://fonts.google.com/icons",
                    "https://www.apache.org/licenses/LICENSE-2.0"),
                Credit("Navigation Compose", stringResource(R.string.ui_navigation_and_screen_transitions), "https://developer.android.com/guide/navigation"),
                Credit("Android Canvas", stringResource(R.string.ui_charts_obstruction_map_and_widget_artwork), "https://developer.android.com/develop/ui/views/graphics/draw-on-canvas")
            ))
            CreditGroup(stringResource(R.string.ui_android_features), 1, listOf(
                Credit("CameraX", stringResource(R.string.ui_camera_preview_in_the_ar_sky_view), "https://developer.android.com/media/camera/camerax"),
                Credit("Jetpack Glance", stringResource(R.string.ui_home_screen_widgets), "https://developer.android.com/develop/ui/compose/glance"),
                Credit("WorkManager", stringResource(R.string.ui_background_work), "https://developer.android.com/topic/libraries/architecture/workmanager"),
                Credit("DataStore", stringResource(R.string.ui_local_preferences), "https://developer.android.com/topic/libraries/architecture/datastore"),
                Credit(stringResource(R.string.ui_androidx_core_activity_and_lifecycle), stringResource(R.string.ui_android_integration), "https://developer.android.com/jetpack/androidx")
            ))
            CreditGroup(stringResource(R.string.ui_communication_and_data), 2, listOf(
                Credit("Kotlin Coroutines", stringResource(R.string.ui_asynchronous_tasks), "https://github.com/Kotlin/kotlinx.coroutines"),
                Credit(stringResource(R.string.ui_grpc_java_kotlin_and_okhttp_transport), stringResource(R.string.ui_local_communication_with_the_dish_and_router), "https://grpc.io"),
                Credit("Protocol Buffers", stringResource(R.string.ui_telemetry_messages), "https://protobuf.dev")
            ))
            CreditGroup(stringResource(R.string.ui_orbital_data_and_references), 3, listOf(
                Credit("CelesTrak", stringResource(R.string.ui_supplemental_spacex_ephemerides_used_in_ar), "https://celestrak.org"),
                Credit(stringResource(R.string.satellite_catalog_credit_name), stringResource(R.string.satellite_catalog_credit_use),
                    "https://planet4589.org/space/con/star/"),
                Credit("predict4java", stringResource(R.string.ui_sgp4_sdp4_orbital_propagation_in_ar_mit), "https://github.com/g4dpz/predict4java"),
                Credit("starlink-grpc-tools", stringResource(R.string.ui_community_reference_for_the_local_grpc_interface_the_unlicense), "https://github.com/sparky8512/starlink-grpc-tools"),
                Credit("Dishylink", stringResource(R.string.ui_reference_for_orbital_tracking_and_reading_the_obstruction_map_mi), "https://github.com/daveyhert/Dishylink")
            ))
            Card(Modifier.fillMaxWidth().expressiveReveal("equipment-data", 210), shape = DashDesign.section,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.padding(DashDesign.inset), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.ui_equipment_data), style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.ui_telemetry_and_speed_tests_query_the_local_grpc_services_of_the_st),
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            CreditGroup(stringResource(R.string.product_tests), 4, listOf(
                Credit("JUnit 4", stringResource(R.string.ui_automated_project_tests), "https://junit.org/junit4/")
            ))
            Text(stringResource(R.string.ui_l_shell_orbit_is_an_independent_unofficial_project_without_affili),
                modifier = Modifier.fillMaxWidth().expressiveReveal("credits-disclaimer", 250),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Text(stringResource(R.string.ui_made_with_love_by_hurricane), modifier = Modifier.fillMaxWidth()
                .expressiveReveal("credits-signature", 280),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun CreditGroup(title: String, index: Int, items: List<Credit>) {
    val uriHandler = LocalUriHandler.current
    Card(Modifier.fillMaxWidth().expressiveReveal(title, 70 + index * 40), shape = DashDesign.section,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(DashDesign.inset), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary)
            items.forEach { credit ->
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(credit.name, style = MaterialTheme.typography.labelLarge)
                        Text(credit.use, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = hapticClick { uriHandler.openUri(credit.url) }) {
                        Icon(OrbitSymbols.OpenInNew, contentDescription = androidx.compose.ui.res.stringResource(R.string.editorial_project, credit.name))
                    }
                    credit.licenseUrl?.let { license ->
                        IconButton(onClick = hapticClick { uriHandler.openUri(license) }) {
                            Icon(OrbitSymbols.Info, contentDescription = stringResource(R.string.symbols_license))
                        }
                    }
                }
            }
        }
    }
}
