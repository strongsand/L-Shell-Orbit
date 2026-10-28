package io.github.strongsand.lshell.ar

import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

/** Confidence in a geometric ranking, NEVER a probability of serving this terminal. */
internal enum class ArCandidateConfidence { LOW, MEDIUM, HIGH }
internal enum class ArCandidateStatus { AVAILABLE, NO_LIVE_DISH, NO_ORIENTATION, NO_LOCATION, NO_CANDIDATES }

internal fun candidatePulseEnabled(systemMotion: Boolean,
    reducedEffects: Boolean, active: Boolean, hasVisibleCandidate: Boolean): Boolean =
    systemMotion && !reducedEffects && active && hasVisibleCandidate

internal fun candidateVisualStrength(confidence: ArCandidateConfidence, rank: Int): Float =
    (when (confidence) {
        ArCandidateConfidence.HIGH -> 1f
        ArCandidateConfidence.MEDIUM -> .85f
        ArCandidateConfidence.LOW -> .70f
    }) / (1f + rank.coerceAtLeast(0) * .5f)

internal data class ArLinkInputs(
    val liveDish: Boolean,
    val dishAgeMs: Long,
    val dishAzimuth: Float?,
    val dishElevation: Float?,
    val locationAgeMs: Long,
    val locationAccuracyM: Float?
)

internal data class ArOrbitPoint(val secondsAhead: Int, val azimuth: Float, val elevation: Float)

internal data class ArCandidateForecast(val atTimeMs: Long, val points: List<ArOrbitPoint>) {
    val valid: Boolean = points.size >= 2 && points.first().secondsAhead == 0 &&
        points.all { it.azimuth.isFinite() && it.elevation in -90f..90f && it.secondsAhead in 0..ArLinkCandidatePolicy.FORECAST_SECONDS } &&
        points.zipWithNext().all { (a, b) -> b.secondsAhead > a.secondsAhead }

    /** First sampled exit from an assumed cone, not a beam/handover schedule. Null is unknown. */
    fun exitSeconds(dishAzimuth: Float, dishElevation: Float): Int? =
        points.takeIf { valid }?.firstOrNull { !isInCandidateRegion(it.azimuth, it.elevation, dishAzimuth, dishElevation) }
            ?.secondsAhead

    fun remainingSeconds(nowMs: Long, dishAzimuth: Float, dishElevation: Float): Int? =
        exitSeconds(dishAzimuth, dishElevation)?.let { (it - (nowMs - atTimeMs).coerceAtLeast(0L) / 1000).coerceAtLeast(0).toInt() }
}

internal data class ArLinkCandidate(
    val satellite: SateliteRA,
    val separationDegrees: Float,
    val score: Float,
    val forecast: ArCandidateForecast?
)

internal data class ArLinkEstimate(
    val status: ArCandidateStatus,
    val confidence: ArCandidateConfidence = ArCandidateConfidence.LOW,
    val candidates: List<ArLinkCandidate> = emptyList()
)

internal val SateliteRA.orbitKey: String get() = catalogNumber?.takeIf { it.isNotBlank() } ?: nome

internal fun angularSeparationDegrees(az1: Float, el1: Float, az2: Float, el2: Float): Float {
    if (!az1.isFinite() || !el1.isFinite() || !az2.isFinite() || !el2.isFinite()) return Float.NaN
    val a = Math.toRadians((az1 - az2).toDouble())
    val e1 = Math.toRadians(el1.toDouble())
    val e2 = Math.toRadians(el2.toDouble())
    return Math.toDegrees(acos((cos(e1) * cos(e2) * cos(a) + sin(e1) * sin(e2)).coerceIn(-1.0, 1.0))).toFloat()
}

/** The existing 52-degree estimated cone; no claim that this is the actual RF mask. */
internal fun isInCandidateRegion(azimuth: Float, elevation: Float, dishAzimuth: Float, dishElevation: Float): Boolean =
    elevation in 18f..90f && angularSeparationDegrees(azimuth, elevation, dishAzimuth, dishElevation) <= DISH_VIEW_HALF_ANGLE_DEG

internal object ArLinkCandidatePolicy {
    const val ESTIMATE_INTERVAL_MS = 5_000L
    const val FORECAST_SECONDS = 180
    const val FORECAST_STEP_SECONDS = 15
    const val MAX_FORECASTS = 6
    private const val DAY_MS = 86_400_000L

    fun inputStatus(input: ArLinkInputs): ArCandidateStatus? = when {
        !input.liveDish || input.dishAgeMs !in 0L..15_000L -> ArCandidateStatus.NO_LIVE_DISH
        input.dishAzimuth?.isFinite() != true || input.dishAzimuth !in 0f..360f ||
            input.dishElevation?.isFinite() != true || input.dishElevation !in 0.01f..90f -> ArCandidateStatus.NO_ORIENTATION
        input.locationAgeMs !in 0L..30_000L -> ArCandidateStatus.NO_LOCATION
        else -> null
    }

    fun rank(
        satellites: List<SateliteRA>, input: ArLinkInputs, nowMs: Long,
        forecasts: Map<String, ArCandidateForecast> = emptyMap()
    ): List<ArLinkCandidate> {
        if (inputStatus(input) != null) return emptyList()
        val az = input.dishAzimuth!!
        val el = input.dishElevation!!
        return satellites.asSequence().filter { sat ->
            val epoch = sat.orbitEpochMs
            epoch != null && epoch > 0L && nowMs - epoch in -6 * 3_600_000L..7 * DAY_MS &&
                isInCandidateRegion(sat.azimute, sat.elevacao, az, el)
        }.distinctBy { it.orbitKey }.map { sat ->
            val separation = angularSeparationDegrees(sat.azimute, sat.elevacao, az, el)
            val forecast = forecasts[sat.orbitKey]?.takeIf { it.valid && nowMs - it.atTimeMs in 0L..10_000L }
            // Missing forecast contributes no residence evidence, never an invented exit time.
            val residence = forecast?.let {
                val seconds = it.exitSeconds(az, el) ?: it.points.lastOrNull()?.secondsAhead ?: 0
                (seconds - (nowMs - it.atTimeMs) / 1000L).coerceAtLeast(0L).toFloat() / FORECAST_SECONDS
            } ?: 0f
            ArLinkCandidate(sat, separation,
                .75f * (1f - separation / DISH_VIEW_HALF_ANGLE_DEG) +
                    .15f * (sat.elevacao / 90f) + .10f * residence.coerceIn(0f, 1f), forecast)
        }.sortedWith(compareByDescending<ArLinkCandidate> { it.score }.thenBy { it.satellite.orbitKey }).toList()
    }

    fun estimate(
        satellites: List<SateliteRA>, input: ArLinkInputs, nowMs: Long,
        forecasts: Map<String, ArCandidateForecast> = emptyMap(), previousKey: String? = null
    ): ArLinkEstimate {
        inputStatus(input)?.let { return ArLinkEstimate(it) }
        val ranked = rank(satellites, input, nowMs, forecasts)
        val best = ranked.firstOrNull() ?: return ArLinkEstimate(ArCandidateStatus.NO_CANDIDATES)
        val margin = ranked.getOrNull(1)?.let { best.score - it.score } ?: 0f
        val age = nowMs - best.satellite.orbitEpochMs!!
        val preciseLocation = input.locationAccuracyM?.let { it.isFinite() && it in 0f..25f } == true
        // Product policy thresholds, not calibrated probabilities or evidence of a radio link.
        val confidence = when {
            !preciseLocation || age !in -3_600_000L..3 * DAY_MS -> ArCandidateConfidence.LOW
            age <= DAY_MS && input.dishAgeMs <= 5_000L && input.locationAgeMs <= 15_000L &&
                best.score >= .80f && best.separationDegrees <= 15f && margin >= .12f -> ArCandidateConfidence.HIGH
            best.score >= .60f && margin >= .045f -> ArCandidateConfidence.MEDIUM
            else -> ArCandidateConfidence.LOW
        }
        // Retain near-ties to avoid flicker; this NEVER upgrades confidence.
        val previous = ranked.firstOrNull { it.satellite.orbitKey == previousKey && best.score - it.score <= .025f }
        val ordered = if (previous != null) listOf(previous) + ranked.filter { it !== previous } else ranked
        return ArLinkEstimate(ArCandidateStatus.AVAILABLE, confidence,
            ordered.take(1))
    }
}

internal data class ArScreenPoint(val x: Float, val y: Float)

/** Same angular scale and negative-roll rotation as the existing marker Canvas. */
internal fun projectArSatellite(
    width: Float, height: Float, cameraAzimuth: Float, cameraElevation: Float, roll: Float,
    azimuth: Float, elevation: Float
): ArScreenPoint? {
    if (width <= 0f || height <= 0f || !width.isFinite() || !height.isFinite() || !cameraAzimuth.isFinite() ||
        !cameraElevation.isFinite() || !roll.isFinite() || !azimuth.isFinite() || !elevation.isFinite()) return null
    val deltaAz = ((azimuth - cameraAzimuth + 540f) % 360f) - 180f
    val deltaEl = elevation - cameraElevation
    if (kotlin.math.abs(deltaAz) > 60f / 1.4f || kotlin.math.abs(deltaEl) > (60f * height / width) / 1.4f) return null
    val x = deltaAz / 60f * width
    val y = -deltaEl / 60f * width
    val angle = Math.toRadians(-roll.toDouble())
    return ArScreenPoint(width / 2f + x * cos(angle).toFloat() - y * sin(angle).toFloat(),
        height / 2f + x * sin(angle).toFloat() + y * cos(angle).toFloat())
}
