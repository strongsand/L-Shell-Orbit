package io.github.strongsand.lshell.ar

import org.json.JSONArray
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

internal data class OmmElements(
    val metadata: SatelliteCatalogMetadata,
    val epoch: Instant,
    val meanMotion: Double,
    val eccentricity: Double,
    val inclination: Double,
    val ascendingNode: Double,
    val pericenter: Double,
    val meanAnomaly: Double,
    val bstar: Double,
    val meanMotionDot: Double,
    val meanMotionDdot: Double
)

/** CelesTrak JSON uses the OMM field names and UTC/TEME/SGP4 defaults. */
internal object OmmOrbitAdapter {
    fun parse(raw: String, feed: String): List<TLE> {
        val array = JSONArray(raw)
        return (0 until array.length()).mapNotNull { index ->
            runCatching {
                val item = array.getJSONObject(index)
                listOf("CENTER_NAME" to "EARTH", "REF_FRAME" to "TEME", "TIME_SYSTEM" to "UTC",
                    "MEAN_ELEMENT_THEORY" to "SGP4").forEach { (key, expected) ->
                    require(!item.has(key) || item.getString(key) == expected)
                }
                val epochText = item.getString("EPOCH")
                val epoch = runCatching { Instant.parse(epochText) }.getOrElse {
                    LocalDateTime.parse(epochText).toInstant(ZoneOffset.UTC)
                }
                toOrbit(OmmElements(
                    SatelliteCatalogMetadata(SatelliteIdentity.normalizeNoradId(item.get("NORAD_CAT_ID")),
                        item.optString("OBJECT_ID").takeIf { it.isNotBlank() && it != "null" },
                        item.getString("OBJECT_NAME"), feed).normalized(), epoch,
                    item.getDouble("MEAN_MOTION"), item.getDouble("ECCENTRICITY"),
                    item.getDouble("INCLINATION"), item.getDouble("RA_OF_ASC_NODE"),
                    item.getDouble("ARG_OF_PERICENTER"), item.getDouble("MEAN_ANOMALY"),
                    item.getDouble("BSTAR"), item.getDouble("MEAN_MOTION_DOT"), item.getDouble("MEAN_MOTION_DDOT")))
            }.getOrNull()
        }
    }

    fun toOrbit(elements: OmmElements): TLE {
        val metadata = elements.metadata.normalized()
        require(metadata.catalogNumber != null)
        require(metadata.objectName.isNotBlank())
        require(listOf(elements.meanMotion, elements.eccentricity, elements.inclination, elements.ascendingNode,
            elements.pericenter, elements.meanAnomaly, elements.bstar, elements.meanMotionDot,
            elements.meanMotionDdot).all { it.isFinite() })
        require(elements.meanMotion > 0.0 && elements.meanMotion < 100.0)
        require(elements.eccentricity >= 0.0 && elements.eccentricity < 1.0)
        require(elements.inclination in 0.0..180.0)
        require(listOf(elements.ascendingNode, elements.pericenter, elements.meanAnomaly).all { it >= 0.0 && it < 360.0 })
        val utc = elements.epoch.atOffset(ZoneOffset.UTC)
        // predict4java still has the TLE two-digit epoch convention. Reject unsupported epochs explicitly.
        require(utc.year in 1957..2056)
        val day = utc.dayOfYear + (utc.toLocalTime().toSecondOfDay() + utc.nano / 1e9) / 86400.0
        val first = CharArray(69) { ' ' }
        val second = CharArray(69) { ' ' }
        fun put(line: CharArray, start: Int, width: Int, value: String) {
            require(value.length == width)
            value.toCharArray().copyInto(line, start)
        }
        fun fixed(value: Double, width: Int, decimals: Int) = String.format(Locale.US, "%${width}.${decimals}f", value)
        // Internal adapter token only: the propagator never uses catnum for orbital calculations.
        // Real identity stays in metadata, including six/nine-digit catalog numbers.
        put(first, 0, 8, "1 00000U"); put(second, 0, 7, "2 00000")
        put(first, 18, 14, String.format(Locale.US, "%02d", utc.year % 100) + fixed(day, 12, 8))
        val dot = fixed(elements.meanMotionDot, 11, 8).replace("0.", ".").padStart(10)
        put(first, 33, 10, dot)
        put(first, 44, 8, compactExponent(elements.meanMotionDdot))
        put(first, 53, 8, compactExponent(elements.bstar))
        put(first, 62, 1, "0"); put(first, 64, 4, "   1")
        put(second, 8, 8, fixed(elements.inclination, 8, 4))
        put(second, 17, 8, fixed(elements.ascendingNode, 8, 4))
        put(second, 26, 7, (elements.eccentricity * 1e7).roundToInt().toString().padStart(7, '0'))
        put(second, 34, 8, fixed(elements.pericenter, 8, 4)); put(second, 43, 8, fixed(elements.meanAnomaly, 8, 4))
        put(second, 52, 11, fixed(elements.meanMotion, 11, 8)); put(second, 63, 5, "    0")
        fun checksum(line: CharArray) {
            line[68] = ('0'.code + line.take(68).sumOf { if (it.isDigit()) it.digitToInt() else if (it == '-') 1 else 0 } % 10).toChar()
        }
        checksum(first); checksum(second)
        return TLE(metadata.objectName, String(first), String(second), Math.toRadians(elements.inclination),
            Math.toRadians(elements.ascendingNode), Math.toRadians(elements.pericenter), Math.toRadians(elements.meanAnomaly),
            elements.meanMotion, elements.epoch.toEpochMilli(), metadata = metadata)
    }

    private fun compactExponent(value: Double): String {
        if (value == 0.0) return " 00000-0"
        var exponent = floor(log10(abs(value))).toInt() + 1
        var mantissa = (abs(value) / 10.0.pow(exponent) * 1e5).roundToInt()
        if (mantissa == 100000) { exponent++; mantissa = 10000 }
        // predict4java 1.2.2 reads only the exponent digit and assumes it is negative.
        require(exponent in -9..0)
        return (if (value < 0) "-" else " ") + mantissa.toString().padStart(5, '0') + "-" + abs(exponent)
    }
}
