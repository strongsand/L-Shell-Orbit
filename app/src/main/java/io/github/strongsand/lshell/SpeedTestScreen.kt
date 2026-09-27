package io.github.strongsand.lshell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import java.util.Locale

@Composable
fun SpeedTestScreen(modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val haptics = rememberDashHaptics()
    var running by remember { mutableStateOf(false) }
    var routerResult by remember { mutableStateOf<SpeedTestResult?>(null) }
    var routerError by remember { mutableStateOf<String?>(null) }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Teste Único", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(8.dp))
        Text("Meça a velocidade entre o roteador Starlink e a internet. O teste pode consumir dados e só roda quando você iniciar.")
        Spacer(Modifier.height(20.dp))
        TestCard("Teste Único", "Uma medição do desempenho da conexão", routerResult, routerError, running, running) {
            routerError = null
            running = true
            scope.launch {
                try { routerResult = SpeedTestClient.router(); haptics.perform(DashFeedback.CONFIRM) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { haptics.perform(DashFeedback.WARNING); routerError = e.message ?: "Não foi possível concluir o teste." }
                finally { running = false }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("A disponibilidade depende da versão de software do roteador.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TestCard(
    title: String,
    description: String,
    result: SpeedTestResult?,
    error: String?,
    running: Boolean,
    disabled: Boolean,
    onRun: () -> Unit
) {
    Card(Modifier.fillMaxWidth().animateContentSize(), shape = DashDesign.hero,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ExpressiveIcon(Icons.Rounded.Bolt, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
                Text(title, style = MaterialTheme.typography.titleLarge)
            }
            Text(description, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (result != null) {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    if (maxWidth / LocalDensity.current.fontScale < 280.dp) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Download", style = MaterialTheme.typography.labelLarge)
                            MetricValue(formatSpeed(result.downloadMbps), "Mbps", MaterialTheme.colorScheme.primary)
                            Text("Upload", style = MaterialTheme.typography.labelLarge)
                            MetricValue(formatSpeed(result.uploadMbps), "Mbps", MaterialTheme.colorScheme.secondary)
                        }
                    } else Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text("Download", style = MaterialTheme.typography.labelLarge)
                            MetricValue(formatSpeed(result.downloadMbps), "Mbps", MaterialTheme.colorScheme.primary)
                        }
                        Column(Modifier.weight(1f)) {
                            Text("Upload", style = MaterialTheme.typography.labelLarge)
                            MetricValue(formatSpeed(result.uploadMbps), "Mbps", MaterialTheme.colorScheme.secondary)
                        }
                    }
                }
                result.latencyMs?.let { Text("Latência ${formatSpeed(it)} ms",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (error != null) Text(error, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium)
            val interaction = remember { MutableInteractionSource() }
            Button(onClick = hapticClick(DashFeedback.SPECIAL, onRun), enabled = !disabled,
                interactionSource = interaction, modifier = Modifier.pressMotion(interaction, 0.96f)) {
                if (running) CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp).size(18.dp), strokeWidth = 2.dp)
                Text(if (running) "Medindo…" else "Iniciar teste")
            }
        }
    }
}

private fun formatSpeed(value: Float): String = String.format(Locale.getDefault(), "%.1f", value)
