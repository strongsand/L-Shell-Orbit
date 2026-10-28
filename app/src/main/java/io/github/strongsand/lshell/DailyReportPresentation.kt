package io.github.strongsand.lshell

/** Uses the report's already merged offline duration; never sums overlapping events. */
internal data class DailyInterruptionUi(val rows: List<ReportInterruption>, val offlineMs: Long) {
    val longest = rows.maxByOrNull { it.durationMs }
    val count get() = rows.size
    companion object {
        fun from(report: DailyReport) = DailyInterruptionUi(report.interruptions.sortedBy { it.start }, report.offlineMs)
    }
}
