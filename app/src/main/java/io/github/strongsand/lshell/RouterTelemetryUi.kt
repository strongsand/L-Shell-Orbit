package io.github.strongsand.lshell

import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle

/** Destination lifecycle, not composition alone: stopped/back-stack routes release the transport. */
@Composable
internal fun rememberRouterTelemetry(includeDetails: Boolean): State<RouterTelemetryState> {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val reading = remember { mutableStateOf(RouterTelemetryState()) }
    LaunchedEffect(lifecycle, includeDetails) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try { RouterTelemetryRuntime.shared.observe(includeDetails).collect { reading.value = it } }
            finally { reading.value = RouterTelemetryState() }
        }
    }
    return reading
}
