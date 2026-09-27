package com.hurricane.lshell

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
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

private data class Credit(val name: String, val use: String, val url: String)

@Composable
internal fun CreditsSheet(onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = DashDesign.hero) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Créditos", style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.expressiveReveal("credits-title"))
            Text("Tecnologias e fontes realmente usadas no L-Shell Orbit. Os links levam aos projetos e aos seus avisos oficiais.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.expressiveReveal("credits-description", 35))
            CreditGroup("Interface e navegação", 0, listOf(
                Credit("Jetpack Compose e Material 3", "Interface, componentes e animações", "https://developer.android.com/develop/ui/compose"),
                Credit("Material Icons", "Ícones da interface", "https://fonts.google.com/icons"),
                Credit("Navigation Compose", "Navegação e transições entre telas", "https://developer.android.com/guide/navigation"),
                Credit("Android Canvas", "Gráficos, mapa de obstrução e arte dos widgets", "https://developer.android.com/develop/ui/views/graphics/draw-on-canvas")
            ))
            CreditGroup("Recursos Android", 1, listOf(
                Credit("CameraX", "Prévia da câmera no céu em AR", "https://developer.android.com/media/camera/camerax"),
                Credit("Jetpack Glance", "Widgets da tela inicial", "https://developer.android.com/develop/ui/compose/glance"),
                Credit("WorkManager", "Trabalho em segundo plano", "https://developer.android.com/topic/libraries/architecture/workmanager"),
                Credit("DataStore", "Preferências locais", "https://developer.android.com/topic/libraries/architecture/datastore"),
                Credit("AndroidX Core, Activity e Lifecycle", "Integração com o Android", "https://developer.android.com/jetpack/androidx")
            ))
            CreditGroup("Comunicação e dados", 2, listOf(
                Credit("Kotlin Coroutines", "Tarefas assíncronas", "https://github.com/Kotlin/kotlinx.coroutines"),
                Credit("gRPC Java/Kotlin e transporte OkHttp", "Comunicação local com a antena e o roteador", "https://grpc.io"),
                Credit("Protocol Buffers", "Mensagens da telemetria", "https://protobuf.dev")
            ))
            CreditGroup("Dados orbitais e referências", 3, listOf(
                Credit("CelesTrak", "Efemérides suplementares da SpaceX usadas na AR", "https://celestrak.org"),
                Credit("predict4java", "Propagação orbital SGP4/SDP4 no modo AR (MIT)", "https://github.com/g4dpz/predict4java"),
                Credit("starlink-grpc-tools", "Referência comunitária para a interface gRPC local (The Unlicense)", "https://github.com/sparky8512/starlink-grpc-tools"),
                Credit("Dishylink", "Referência para rastreamento orbital e leitura do mapa de obstrução (MIT)", "https://github.com/daveyhert/Dishylink")
            ))
            Card(Modifier.fillMaxWidth().expressiveReveal("equipment-data", 210), shape = DashDesign.section,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.padding(DashDesign.inset), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Dados do equipamento", style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary)
                    Text("A telemetria e o teste de velocidade consultam os serviços gRPC locais do terminal e do roteador Starlink.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            CreditGroup("Testes", 4, listOf(
                Credit("JUnit 4", "Testes automatizados do projeto", "https://junit.org/junit4/")
            ))
            Text("L-Shell Orbit é um projeto independente e não oficial, sem afiliação ou endosso da Starlink ou SpaceX.",
                modifier = Modifier.fillMaxWidth().expressiveReveal("credits-disclaimer", 250),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Text("feito com amor por hurricane", modifier = Modifier.fillMaxWidth()
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
                        Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = "Abrir projeto ${credit.name}")
                    }
                }
            }
        }
    }
}
