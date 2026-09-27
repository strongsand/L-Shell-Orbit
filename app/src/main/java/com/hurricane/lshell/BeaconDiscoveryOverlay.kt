package com.hurricane.lshell

import android.animation.ValueAnimator
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.blur
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.hurricane.lshell.beacon.BeaconDevice
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BeaconPermissionSheet(denied: Boolean, onContinue: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        shape = DashDesign.hero,
        scrimColor = MaterialTheme.colorScheme.scrim.copy(alpha = .46f)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(shape = DashDesign.cookie, color = MaterialTheme.colorScheme.primaryContainer) {
                Icon(Icons.Rounded.Security, null, Modifier.padding(18.dp).size(38.dp))
            }
            Text(
                if (denied) "Permissão necessária para encontrar o Beacon"
                else "Encontre seu L-Shell Beacon",
                style = MaterialTheme.typography.headlineSmall
            )
            Text(
                "O app usa o Bluetooth somente para localizar e configurar um Beacon próximo. " +
                    "A senha do Wi-Fi é enviada diretamente pelo vínculo Bluetooth protegido.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = hapticClick(DashFeedback.CONFIRM, onContinue), modifier = Modifier.fillMaxWidth()) {
                Text(if (denied) "Tentar novamente" else "Continuar")
            }
            TextButton(onClick = hapticClick(action = onDismiss)) { Text("Agora não") }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BeaconNearbySheet(device: BeaconDevice, onPair: () -> Unit, onDismiss: () -> Unit) {
    var illustrationVisible by remember(device.id) { mutableStateOf(false) }
    var textVisible by remember(device.id) { mutableStateOf(false) }
    var buttonVisible by remember(device.id) { mutableStateOf(false) }
    LaunchedEffect(device.id) {
        illustrationVisible = true
        delay(130)
        textVisible = true
        delay(140)
        buttonVisible = true
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        shape = DashDesign.hero,
        scrimColor = MaterialTheme.colorScheme.scrim.copy(alpha = .52f)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            AnimatedVisibility(
                illustrationVisible,
                enter = fadeIn(tween(220)) + scaleIn(spring(dampingRatio = .72f, stiffness = 240f), .78f)
            ) { BeaconDeviceIllustration() }
            AnimatedVisibility(textVisible,
                enter = fadeIn(tween(220)) + slideInVertically(tween(240)) { it / 6 }) {
                Surface(shape = DashDesign.section, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.fillMaxWidth().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Novo Beacon encontrado nas proximidades", style = MaterialTheme.typography.headlineSmall)
                    Text("L-Shell Beacon está pronto para ser configurado.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Surface(shape = DashDesign.pill, color = MaterialTheme.colorScheme.secondaryContainer) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Rounded.Bluetooth, null, Modifier.size(17.dp))
                        Text("Bluetooth próximo · conexão protegida", style = MaterialTheme.typography.labelLarge)
                    }
                    }
                }
                }
            }
            AnimatedVisibility(buttonVisible,
                enter = fadeIn(tween(180)) + slideInVertically(spring(dampingRatio = .82f, stiffness = 320f)) { it / 4 }) {
                Column(Modifier.fillMaxWidth()) {
                    Button(onClick = hapticClick(DashFeedback.CONFIRM, onPair), modifier = Modifier.fillMaxWidth()) {
                        Text("Iniciar pareamento")
                    }
                    TextButton(onClick = hapticClick(action = onDismiss), modifier = Modifier.fillMaxWidth()) {
                        Text("Agora não")
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** Replace only the painter below when the final Beacon vector is ready. */
@Composable
fun BeaconDeviceIllustration(modifier: Modifier = Modifier) {
    val animations = remember { ValueAnimator.areAnimatorsEnabled() }
    val entrance = remember { Animatable(if (animations) 0f else 1f) }
    val pulse = remember { Animatable(if (animations) .88f else 1f) }
    val drift = remember { Animatable(0f) }
    LaunchedEffect(animations) {
        if (animations) {
            entrance.animateTo(1f, spring(dampingRatio = .58f, stiffness = 220f))
        }
    }
    LaunchedEffect(animations) {
        if (animations) {
            repeat(4) {
                pulse.animateTo(1.08f, tween(700))
                pulse.animateTo(.9f, tween(700))
            }
            pulse.animateTo(.96f, tween(250))
        }
    }
    LaunchedEffect(animations) {
        if (animations) {
            repeat(3) {
                drift.animateTo(-3f, tween(850))
                drift.animateTo(2f, tween(850))
            }
            drift.animateTo(0f, tween(300))
        }
    }
    Box(modifier.size(172.dp), contentAlignment = Alignment.Center) {
        Surface(
            Modifier.size(144.dp).blur(5.dp).graphicsLayer { scaleX = pulse.value; scaleY = pulse.value; alpha = .18f },
            shape = DashDesign.cookie,
            color = MaterialTheme.colorScheme.primary
        ) {}
        Surface(
            Modifier.size(124.dp).graphicsLayer {
                translationY = (1f - entrance.value) * 20f + drift.value
                scaleX = .82f + entrance.value * .18f
                scaleY = scaleX
            },
            shape = DashDesign.hero,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_beacon_placeholder), null, Modifier.size(76.dp))
            }
        }
    }
}
