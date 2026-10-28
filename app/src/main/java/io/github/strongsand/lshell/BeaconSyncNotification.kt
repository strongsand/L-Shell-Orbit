package io.github.strongsand.lshell

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import io.github.strongsand.lshell.beacon.BeaconState
import java.text.NumberFormat

internal object BeaconSyncNotificationPolicy {
    const val UPDATE_INTERVAL_MS = 3_000L

    // Only real acknowledged counts supplied by the existing sync state, not an ETA.
    fun progress(state: BeaconState?): Pair<Int, Int>? {
        val sync = state as? BeaconState.Syncing ?: return null
        if (sync.totalRecords <= 0 || sync.processedRecords < 0) return null
        return 10_000 to (sync.processedRecords.toDouble() / sync.totalRecords * 10_000)
            .coerceIn(0.0, 10_000.0).toInt()
    }

    fun requestsPromotion(api: Int, allowed: Boolean, active: Boolean, dismissed: Boolean): Boolean =
        api >= 36 && allowed && active && !dismissed
}

internal object BeaconSyncNotification {
    const val CHANNEL = "beacon_manual_sync"
    const val ID = 5400

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) != null) return
        manager.createNotificationChannel(NotificationChannel(CHANNEL,
            context.getString(R.string.beacon_sync_channel), NotificationManager.IMPORTANCE_LOW).apply {
            description = context.getString(R.string.beacon_sync_channel_description)
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        })
    }

    private fun open(context: Context): PendingIntent = PendingIntent.getActivity(context, ID,
        Intent(context, MainActivity::class.java).setAction("io.github.strongsand.lshell.beacon.OPEN_SYNC")
            .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("open_tab", "BEACON"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun active(context: Context, state: BeaconState?, id: Long, dismissed: Boolean): Notification {
        val syncing = context.getString(R.string.beacon_sync_notification_active)
        val sync = state as? BeaconState.Syncing
        val detail = if (sync != null && progressAvailable(sync)) {
            val format = NumberFormat.getIntegerInstance()
            context.getString(R.string.beacon_sync_notification_progress,
                format.format(sync.processedRecords), format.format(sync.totalRecords))
        } else syncing
        val builder = base(context).setContentTitle(context.getString(R.string.beacon_title))
            .setContentText(syncing).setTicker("$syncing. $detail")
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setOngoing(true).setAutoCancel(false)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setDeleteIntent(PendingIntent.getService(context, ID,
                Intent(context, BeaconSyncService::class.java).setAction(BeaconSyncService.ACTION_DEMOTE)
                    .setData(android.net.Uri.parse("lshell://beacon-sync/$id"))
                    .putExtra(BeaconSyncService.EXTRA_ID, id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        BeaconSyncNotificationPolicy.progress(state)?.let { (max, value) -> builder.setProgress(max, value, false) }
        val normal = builder.build()
        if (Build.VERSION.SDK_INT < 36) return normal
        return try { promote(context, normal, dismissed) }
        catch (_: LinkageError) { normal } catch (_: RuntimeException) { normal }
    }

    private fun progressAvailable(sync: BeaconState.Syncing) = BeaconSyncNotificationPolicy.progress(sync) != null

    @RequiresApi(36)
    private fun promote(context: Context, normal: Notification, dismissed: Boolean): Notification {
        val allowed = context.getSystemService(NotificationManager::class.java).canPostPromotedNotifications()
        val builder = Notification.Builder.recoverBuilder(context, normal)
        if (Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1)
            builder.setRequestPromotedOngoing(BeaconSyncNotificationPolicy.requestsPromotion(
                Build.VERSION.SDK_INT, allowed, active = true, dismissed = dismissed))
        return builder.setShortCriticalText(context.getString(R.string.beacon_sync_notification_chip)).build()
    }

    fun finished(context: Context, success: Boolean): Notification = base(context)
        .setSmallIcon(if (success) R.drawable.ic_beacon_sync_notification else R.drawable.ic_notification_warning)
        .setContentTitle(context.getString(if (success) R.string.beacon_sync_notification_success
            else R.string.beacon_sync_notification_failure))
        .setContentText(context.getString(R.string.beacon_title))
        .setOngoing(false).setAutoCancel(true).build()

    private fun base(context: Context) = NotificationCompat.Builder(context, CHANNEL)
        .setSmallIcon(R.drawable.ic_beacon_sync_notification).setContentIntent(open(context))
        .setOnlyAlertOnce(true).setSilent(true).setLocalOnly(true).setShowWhen(false)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .setPublicVersion(NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_beacon_sync_notification)
            .setContentTitle(context.getString(R.string.beacon_title)).build())
}
