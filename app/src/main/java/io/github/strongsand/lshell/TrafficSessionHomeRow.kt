package io.github.strongsand.lshell

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

internal fun trafficNotificationsAllowed(context: Context): Boolean {
    val manager = context.getSystemService(NotificationManager::class.java)
    return manager.areNotificationsEnabled() && manager.getNotificationChannel("traffic_sessions")?.importance != NotificationManager.IMPORTANCE_NONE &&
        (Build.VERSION.SDK_INT < 33 || androidx.core.content.ContextCompat.checkSelfPermission(context,
            android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED)
}

@Composable
internal fun TrafficSessionHomeRow(onOpen: () -> Unit) {
    val context = LocalContext.current
    val activity by remember { TrafficSessionController.get(context) }.state.collectAsState()
    val session = activity.session?.takeIf { it.active } ?: return
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, session.id) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) { now = System.currentTimeMillis(); delay(5000) }
        }
    }
    SettingsActionRow(stringResource(R.string.traffic_session_active),
        trafficShortDuration((now - session.startedAt).coerceAtLeast(0)),
        if (activity is TrafficSessionActivity.ErrorRecoverable) stringResource(R.string.session_recover) else session.name,
        onClick = onOpen)
}
