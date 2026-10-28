package io.github.strongsand.lshell

internal data class OutageContext(val outage: ReportInterruption, val before: HistoryEntry?,
    val during: HistoryEntry?, val after: HistoryEntry?)

/** Input order is the existing time_ms ASC, id ASC order, including ties. */
internal class OutageHistoryLookup(rows: List<HistoryEntry>, checkCancellation: () -> Unit = {}) {
    private val readings = ArrayList<HistoryEntry>()

    init {
        rows.forEachIndexed { index, row ->
            if (index and 1023 == 0) checkCancellation()
            if (row.kind == "reading" || row.kind == "beacon_history") readings.add(row)
        }
    }

    private fun lowerBound(time: Long): Int {
        var low = 0
        var high = readings.size
        while (low < high) {
            val middle = low + (high - low) / 2
            if (readings[middle].timestamp < time) low = middle + 1 else high = middle
        }
        return low
    }

    fun context(outage: ReportInterruption): OutageContext {
        val atStart = lowerBound(outage.start)
        // maxByOrNull keeps the FIRST row at the winning timestamp, not the last tied row.
        val before = if (atStart > 0) readings[lowerBound(readings[atStart - 1].timestamp)] else null
        val during = readings.getOrNull(atStart)?.takeIf {
            outage.start <= outage.end && it.timestamp <= outage.end
        }
        val after = readings.getOrNull(lowerBound(outage.end))
        return OutageContext(outage, before, during, after)
    }
}

/** Only overlap candidates are indexed here; SQL still owns source/identity filtering. */
internal class HistoryTimestampIndex(sortedTimestamps: LongArray) {
    private val times = sortedTimestamps.copyOf()

    fun overlaps(timestamp: Long): Boolean {
        val start = timestamp - 500L
        val end = timestamp + 500L
        // Preserve the existing SQL BETWEEN result when Long arithmetic wraps.
        if (start > end) return false
        var low = 0
        var high = times.size
        while (low < high) {
            val middle = low + (high - low) / 2
            if (times[middle] < start) low = middle + 1 else high = middle
        }
        return low < times.size && times[low] <= end
    }
}

/** Saturation is only for prefetch bounds; per-sample decisions retain the old arithmetic. */
internal fun historyOverlapBounds(first: Long, last: Long): LongRange {
    if (first > last) return Long.MIN_VALUE..Long.MAX_VALUE
    val start = if (first < Long.MIN_VALUE + 500L) Long.MIN_VALUE else first - 500L
    val end = if (last > Long.MAX_VALUE - 500L) Long.MAX_VALUE else last + 500L
    return start..end
}
