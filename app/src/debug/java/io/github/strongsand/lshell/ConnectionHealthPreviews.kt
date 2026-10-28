package io.github.strongsand.lshell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId

/** Synthetic editor-only previews. No database, work scheduling or device access. */
private fun previewReport(coverage: Double, latency: Double, loss: Double, previousLatency: Double? = null): HealthReport {
    val zone = ZoneId.of("UTC")
    val period = HealthPeriod.containing(HealthPeriodKind.WEEK, Instant.parse("2026-10-08T12:00:00Z").toEpochMilli(), zone)
    fun make(p: HealthPeriod, ping: Double): HealthReport {
        val days = p.days().map { (a, b) ->
            val observed = ((b - a) * coverage / 100).toLong()
            val unavailable = if (loss >= 5) observed / 10 else 0L
            ConnectionHealthAnalyzer.day(emptyList(), emptyList(), a, b, zone).copy(
                observedMs = observed, stateMs = observed, onlineMs = observed - unavailable,
                offlineMs = unavailable, outages = if (unavailable > 0) 3 else 0,
                stats = mapOf("latency" to HealthDistribution.of(List(100) { ping }), "loss" to HealthDistribution.of(List(100) { loss })),
                metricCoveredMs = mapOf("latency" to observed, "loss" to observed),
                localValues = 200, longestGapMs = b - a - observed)
        }
        return ConnectionHealthAnalyzer.report(p, days, null, p.end, emptyList())
    }
    val current = make(period, latency)
    val previous = previousLatency?.let { make(period.previous(), it) }
    return ConnectionHealthAnalyzer.report(period, current.days, previous, period.end, emptyList())
}

@Composable private fun ReportPreview(coverage: Double, latency: Double, loss: Double, previous: Double? = null) {
    val ui = remember(coverage, latency, loss, previous) {
        HealthReportUi(PreparedHealthReport.from(previewReport(coverage, latency, loss, previous))!!)
    }
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                HealthHero(ui)
                HealthOverview(ui)
                HealthComparisonCard(ui)
                HealthQuality(ui)
                HealthConfidenceCard(ui)
            }
        }
    }
}

@Preview(name = "Insufficient · PT", locale = "pt", widthDp = 393, heightDp = 852)
@Composable private fun InsufficientReportPreview() = ReportPreview(18.5, 21.0, .1)

@Preview(name = "Healthy · EN", locale = "en", widthDp = 393, heightDp = 852)
@Composable private fun HealthyReportPreview() = ReportPreview(92.0, 21.0, .1)

@Preview(name = "Poor · ES", locale = "es", widthDp = 393, heightDp = 852)
@Composable private fun PoorReportPreview() = ReportPreview(92.0, 230.0, 8.0)

@Preview(name = "Improving", locale = "pt", widthDp = 393, heightDp = 852)
@Composable private fun ImprovingReportPreview() = ReportPreview(92.0, 21.0, .1, 32.0)

@Preview(name = "Worsening", locale = "pt", widthDp = 393, heightDp = 852)
@Composable private fun WorseningReportPreview() = ReportPreview(92.0, 50.0, .1, 21.0)

@Preview(name = "Large font", locale = "pt", fontScale = 1.5f, widthDp = 360, heightDp = 852)
@Composable private fun LargeFontReportPreview() = ReportPreview(18.5, 21.0, .1)
