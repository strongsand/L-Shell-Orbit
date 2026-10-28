package io.github.strongsand.lshell

/** A notification Intent is a one-time request, not a permanent destination. */
internal object AppLaunchNavigation {
    const val REPORT_END = "requested_report_end"
    const val SESSION_ID = "requested_traffic_session_id"
    const val REQUEST_ID = "destination_request_id"

    fun destination(tab: String?, requestId: Int, handledRequestId: Int?): MainTab? {
        if (requestId == handledRequestId) return null
        return MainTab.entries.firstOrNull { it.name == tab && it != MainTab.HOME }
    }
}
