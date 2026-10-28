package io.github.strongsand.lshell

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.flow.collect

/** Request notification permission only from the user's Start action. */
@Composable
internal fun rememberConnectionTestStart(kind: ConnectionTestKind, rounds: Int = 1): () -> Unit {
    val context = LocalContext.current
    val controller = remember(context) { ConnectionTestController.get(context) }
    val currentRounds by rememberUpdatedState(rounds)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) controller.start(kind, currentRounds)
        else controller.permissionDenied(kind)
    }
    return {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        else controller.start(kind, currentRounds)
    }
}

/** Preserve screen feedback while visible; no vibration from background notification updates. */
@Composable
internal fun ConnectionTestFeedback(controller: ConnectionTestController, kind: ConnectionTestKind) {
    val haptics = rememberDashHaptics()
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(controller, kind, lifecycleOwner) {
        var previous = controller.state.value
        controller.state.collect { next ->
            if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
                previous.id == next.id && previous.active && next.kind == kind) {
                ConnectionTestExperience.feedback(previous, next)?.let(haptics::perform)
            }
            previous = next
        }
    }
}
