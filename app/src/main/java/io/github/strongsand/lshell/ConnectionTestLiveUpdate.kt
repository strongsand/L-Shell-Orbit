package io.github.strongsand.lshell

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi

/** Native only. No custom active RemoteViews, synthetic percentage or per-frame notification posts. */
internal object ConnectionTestLiveUpdate {
    fun apply(context: Context, normal: Notification, state: ConnectionTestState, promote: Boolean): Notification {
        if (Build.VERSION.SDK_INT < 36) return normal
        return try { Api36.apply(context, normal, state, promote) }
        catch (_: LinkageError) { normal } catch (_: RuntimeException) { normal }
    }

    @RequiresApi(36)
    private object Api36 {
        fun apply(context: Context, normal: Notification, state: ConnectionTestState, promote: Boolean): Notification {
            val allowed = context.getSystemService(NotificationManager::class.java).canPostPromotedNotifications()
            val builder = Notification.Builder.recoverBuilder(context, normal)
            if (Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1)
                builder.setRequestPromotedOngoing(ConnectionTestNotificationUi.requestsPromotion(state, 36, allowed, !promote))
            if (ConnectionTestNotificationUi.usesProgressStyle(state, 36)) {
                val upload = TestTransferPresentation.mode(state) == TestTransferMode.UPLOAD
                val tint = context.getColor(R.color.test_result_accent)
                // SystemUI owns animation direction. Swap source/destination icons for upload.
                val style = Notification.ProgressStyle().setStyledByProgress(true)
                    .setProgressStartIcon(Icon.createWithResource(context,
                        if (upload) R.drawable.ic_test_flow_person else R.drawable.ic_test_flow_satellite).setTint(tint))
                    .setProgressEndIcon(Icon.createWithResource(context,
                        if (upload) R.drawable.ic_test_flow_satellite else R.drawable.ic_test_flow_person).setTint(tint))
                val progress = ExtremeTestNotificationModel.progress(state)
                if (TestTransferPresentation.nativeIndeterminate(state) || progress == null) style.setProgressIndeterminate(true)
                else {
                    ConnectionTestNotificationUi.segmentLengths(state).forEach {
                        style.addProgressSegment(Notification.ProgressStyle.Segment(it))
                    }
                    style.setProgress(progress.second)
                }
                builder.setStyle(style)
            }
            ConnectionTestPresentation.chip(state)?.let(builder::setShortCriticalText)
            val result = builder.build()
            if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
                Log.d("ConnectionTest", "PROMOTION_ELIGIBLE=${result.hasPromotableCharacteristics()} ALLOWED=$allowed")
            return result
        }
    }
}
