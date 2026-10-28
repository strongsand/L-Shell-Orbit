package io.github.strongsand.lshell.ar

import android.content.Context
import io.github.strongsand.lshell.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Date
import java.util.zip.GZIPInputStream
import kotlin.math.*
import uk.me.g4dpz.satellite.GroundStationPosition
import uk.me.g4dpz.satellite.Satellite
import uk.me.g4dpz.satellite.SatelliteFactory
import uk.me.g4dpz.satellite.TLE as PredictTle

data class TLE(
    val name: String,
    val line1: String,
    val line2: String,
    val incRad: Double,
    val raan0Rad: Double,
    val argPRad: Double,
    val m0Rad: Double,
    val nRevDay: Double,
    val epochTimeMs: Long,
    val metadata: SatelliteCatalogMetadata? = null,
    val classification: SatelliteClassification = SatelliteClassification()
)

data class SateliteRA(
    val nome: String,
    val azimute: Float,
    val elevacao: Float,
    val conectado: Boolean = false,
    val noFovAntena: Boolean = true,
    val classification: SatelliteClassification = SatelliteClassification(),
    val catalogNumber: String? = null,
    val orbitEpochMs: Long? = null,
    val rangeKm: Float? = null
)

enum class TipoAstro { SOL, LUA }

data class AstroObjeto(
    val nome: String,
    val azimute: Float,
    val elevacao: Float,
    val tipo: TipoAstro
)

object StarlinkOrbitTracker {

    private const val URL_PRINCIPAL = "https://celestrak.org/NORAD/elements/supplemental/sup-gp.php?FILE=starlink&FORMAT=JSON"
    private const val URL_SECUNDARIA = "https://celestrak.org/NORAD/elements/gp.php?GROUP=STARLINK&FORMAT=JSON"
    private const val CACHE_FRESH_MS = 6 * 3_600_000L
    private const val COARSE_PASS_MS = 60_000L
    private const val COARSE_ELEVATION_FLOOR_DEG = -12.0
    @Volatile internal var lastDownloadError: ArUiText? = null
        private set

    suspend fun carregarTLEs(context: Context): List<TLE> = withContext(Dispatchers.IO) {
        lastDownloadError = null
        val cacheFile = File(context.cacheDir, "starlink_omm.json")
        val sourceFile = File(context.cacheDir, "starlink_omm_source.txt")
        val cachedFeed = runCatching { sourceFile.readText().trim() }.getOrDefault("celestrak_starlink")
            .takeIf { it in setOf("celestrak_starlink", "celestrak_starlink_supgp") } ?: "celestrak_starlink"
        val resolver = runCatching {
            context.assets.open("starlink_generation_catalog.json").bufferedReader().use {
                SatelliteGenerationResolver.fromJson(it.readText())
            }
        }.getOrElse { SatelliteGenerationResolver(emptyList()) }
        fun parse(raw: String, feed: String = cachedFeed): List<TLE> = runCatching { parseCatalog(raw, feed) }.getOrDefault(emptyList()).map { orbit ->
            orbit.copy(classification = orbit.metadata?.let(resolver::resolve) ?: SatelliteClassification())
        }

        if (cacheFile.exists() && cacheFile.length() > 1_000 &&
            (System.currentTimeMillis() - cacheFile.lastModified()) < CACHE_FRESH_MS) {
            val parsed = runCatching { parse(cacheFile.readText()) }.getOrDefault(emptyList())
            if (parsed.isNotEmpty()) return@withContext parsed
        }

        for ((url, feed) in listOf(URL_PRINCIPAL to "celestrak_starlink_supgp", URL_SECUNDARIA to "celestrak_starlink")) {
            val raw = baixarComUserAgent(url) ?: continue
            val parsed = parse(raw, feed)
            if (parsed.isNotEmpty()) {
                try { cacheFile.writeText(raw); sourceFile.writeText(feed) } catch (_: Exception) {}
                return@withContext parsed
            }
            lastDownloadError = ArUiText(R.string.ui_ar_empty_orbits)
        }

        // A stale real ephemeris is still preferable to an invented constellation.
        for (fallback in listOf(cacheFile, File(context.cacheDir, "starlink_supplemental_tle.txt"))) {
            val parsed = runCatching { if (fallback.exists()) parse(fallback.readText()) else emptyList() }.getOrDefault(emptyList())
            if (parsed.isNotEmpty()) return@withContext parsed
        }

        // Sem catálogo válido, não desenhamos satélites inventados.
        return@withContext emptyList()
    }

    private fun baixarComUserAgent(endereco: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(endereco)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0 Mobile Safari/537.36")
                setRequestProperty("Accept", "text/plain,*/*")
                setRequestProperty("Accept-Encoding", "gzip")
                connectTimeout = 12000
                readTimeout = 20000
            }
            conn = connection

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val isGzip = "gzip".equals(connection.contentEncoding, ignoreCase = true)
                val baseStream = if (isGzip) GZIPInputStream(connection.inputStream) else connection.inputStream
                BufferedReader(InputStreamReader(baseStream, Charsets.UTF_8)).use { it.readText() }
            } else {
                lastDownloadError = ArUiText(R.string.ui_ar_http_error, listOf(responseCode))
                null
            }
        } catch (e: Exception) {
            lastDownloadError = ArUiText(R.string.ui_ar_download_error,
                diagnostic = e.localizedMessage?.take(90) ?: e.javaClass.simpleName)
            null
        } finally {
            conn?.disconnect()
        }
    }

    internal fun parseCatalog(raw: String, feed: String = "celestrak_starlink_supgp"): List<TLE> = if (raw.trimStart().startsWith("["))
        OmmOrbitAdapter.parse(raw, feed) else parseTLEs(raw)

    private fun parseTLEs(raw: String): List<TLE> {
        val lines = raw.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val list = mutableListOf<TLE>()

        var i = 0
        while (i + 2 < lines.size) {
            val name = lines[i].removePrefix("0 ").trim()
            val l1 = lines[i + 1]
            val l2 = lines[i + 2]

            if (l1.startsWith("1 ") && l2.startsWith("2 ") && l1.length >= 32 && l2.length >= 63) {
                try {
                    val epochYrStr = l1.substring(18, 20).trim().toInt()
                    val fullYr = if (epochYrStr < 57) 2000 + epochYrStr else 1900 + epochYrStr
                    val epochDay = l1.substring(20, 32).trim().toDouble()

                    val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
                        clear()
                        set(java.util.Calendar.YEAR, fullYr)
                        set(java.util.Calendar.DAY_OF_YEAR, epochDay.toInt())
                        val msDay = ((epochDay - epochDay.toInt()) * 86400 * 1000).toLong()
                        timeInMillis += msDay
                    }

                    val inc = Math.toRadians(l2.substring(8, 16).trim().toDouble())
                    val raan0 = Math.toRadians(l2.substring(17, 25).trim().toDouble())
                    val argP = Math.toRadians(l2.substring(34, 42).trim().toDouble())
                    val m0 = Math.toRadians(l2.substring(43, 51).trim().toDouble())
                    val nRevDay = l2.substring(52, 63).trim().toDouble()

                    list.add(
                        TLE(
                            name = name,
                            line1 = l1,
                            line2 = l2,
                            incRad = inc,
                            raan0Rad = raan0,
                            argPRad = argP,
                            m0Rad = m0,
                            nRevDay = nRevDay,
                            epochTimeMs = cal.timeInMillis,
                            metadata = SatelliteCatalogMetadata(l1.substring(2, 7).trim(),
                                l1.substring(9, 17).trim().takeIf { Regex("[0-9]{5}[A-Z]+").matches(it) }?.let {
                                    val year = it.take(2).toInt()
                                    "${if (year < 57) 2000 + year else 1900 + year}-${it.drop(2)}"
                                }, name, "celestrak_starlink").normalized()
                        )
                    )
                } catch (_: Exception) {}
                i += 3
            } else {
                i++
            }
        }
        return list
    }

    fun isNoFovAntena(satAz: Float, satEl: Float, obsLat: Double, fovHalfAngle: Float = 52f): Boolean {
        if (satEl < 18f) return false

        val dishAz = if (obsLat < 0) 175.0 else 0.0
        val dishEl = 62.0

        val sAzRad = Math.toRadians(satAz.toDouble())
        val sElRad = Math.toRadians(satEl.toDouble())
        val sx = cos(sElRad) * sin(sAzRad)
        val sy = cos(sElRad) * cos(sAzRad)
        val sz = sin(sElRad)

        val bAzRad = Math.toRadians(dishAz)
        val bElRad = Math.toRadians(dishEl)
        val bx = cos(bElRad) * sin(bAzRad)
        val by = cos(bElRad) * cos(bAzRad)
        val bz = sin(bElRad)

        val dot = (sx * bx + sy * by + sz * bz).coerceIn(-1.0, 1.0)
        val angSeparacao = Math.toDegrees(acos(dot))
        return angSeparacao <= fovHalfAngle
    }

    private data class Sgp4Entry(val orbit: TLE, val satellite: Satellite)

    private data class Sgp4TrackerState(
        val source: List<TLE>,
        val latitude: Double,
        val longitude: Double,
        val altitudeM: Double,
        val entries: List<Sgp4Entry>,
        var nearSkyIndices: IntArray = IntArray(0),
        var lastCoarsePassMs: Long = 0L
    )

    @Volatile private var sgp4State: Sgp4TrackerState? = null

    @Synchronized
    private fun trackerFor(tles: List<TLE>, latitude: Double, longitude: Double, altitudeM: Double): Sgp4TrackerState {
        val current = sgp4State
        if (current != null && current.source === tles &&
            abs(current.latitude - latitude) < 0.001 &&
            abs(current.longitude - longitude) < 0.001 &&
            abs(current.altitudeM - altitudeM) < 100.0) return current

        val entries = tles.mapNotNull { tle ->
            if (tle.line1.isBlank() || tle.line2.isBlank()) return@mapNotNull null
            runCatching {
                Sgp4Entry(
                    orbit = tle,
                    satellite = SatelliteFactory.createSatellite(PredictTle(arrayOf(tle.name, tle.line1, tle.line2)))
                )
            }.getOrNull()
        }
        return Sgp4TrackerState(tles, latitude, longitude, altitudeM, entries).also { sgp4State = it }
    }

    @Synchronized
    fun calcularSatellitesVisiveis(
        tles: List<TLE>,
        obsLat: Double,
        obsLon: Double,
        obsAltM: Double,
        minElevacao: Float = 15f,
        apenasFovAntena: Boolean = true,
        distanciaMaximaKm: Float? = null,
        nowMs: Long = System.currentTimeMillis()
    ): List<SateliteRA> {
        val atDate = Date(nowMs)
        val ground = GroundStationPosition(obsLat, obsLon, obsAltM)
        val tracker = trackerFor(tles, obsLat, obsLon, obsAltM)

        // Mirroring Dishylink's two-stage strategy: sweep the full constellation
        // once a minute, then run live SGP4 only for satellites near the sky.
        if (tracker.nearSkyIndices.isEmpty() || nowMs - tracker.lastCoarsePassMs >= COARSE_PASS_MS) {
            tracker.nearSkyIndices = tracker.entries.indices.filter { index ->
                runCatching {
                    Math.toDegrees(tracker.entries[index].satellite.getPosition(ground, atDate).getElevation()) >
                        COARSE_ELEVATION_FLOOR_DEG
                }.getOrDefault(false)
            }.toIntArray()
            tracker.lastCoarsePassMs = nowMs
        }

        return buildList {
            for (index in tracker.nearSkyIndices) {
                val entry = tracker.entries.getOrNull(index) ?: continue
                val position = runCatching { entry.satellite.getPosition(ground, atDate) }.getOrNull() ?: continue
                val distanceKm = position.getRange()
                if (!distanceKm.isFinite() || distanceKm <= 0.0 ||
                    (distanciaMaximaKm != null && distanceKm > distanciaMaximaKm.toDouble())) continue

                val elevation = Math.toDegrees(position.getElevation()).toFloat()
                if (!elevation.isFinite() || elevation < minElevacao) continue
                val azimuth = normalizarGraus(Math.toDegrees(position.getAzimuth()).toFloat())
                if (!azimuth.isFinite()) continue

                val dentroFov = isNoFovAntena(azimuth, elevation, obsLat)
                if (!apenasFovAntena || dentroFov) {
                    add(SateliteRA(entry.orbit.name, azimuth, elevation, conectado = false, noFovAntena = dentroFov,
                        classification = entry.orbit.classification, catalogNumber = entry.orbit.metadata?.catalogNumber,
                        orbitEpochMs = entry.orbit.epochTimeMs, rangeKm = distanceKm.toFloat()))
                }
            }
        }.sortedByDescending { it.elevacao }
    }

    /** Bounded SGP4 look-ahead for a shortlist, on the orbital worker, never per camera frame. */
    @Synchronized
    internal fun candidateForecasts(
        tles: List<TLE>, shortlist: List<SateliteRA>, latitude: Double, longitude: Double,
        altitudeM: Double, nowMs: Long
    ): Map<String, ArCandidateForecast> {
        if (shortlist.isEmpty()) return emptyMap()
        val tracker = trackerFor(tles, latitude, longitude, altitudeM)
        val keys = shortlist.take(ArLinkCandidatePolicy.MAX_FORECASTS).map { it.orbitKey }.toSet()
        val ground = GroundStationPosition(latitude, longitude, altitudeM)
        return buildMap {
            for (entry in tracker.entries) {
                val key = entry.orbit.metadata?.catalogNumber?.takeIf { it.isNotBlank() } ?: entry.orbit.name
                if (key !in keys) continue
                val points = buildList {
                    for (seconds in 0..ArLinkCandidatePolicy.FORECAST_SECONDS step ArLinkCandidatePolicy.FORECAST_STEP_SECONDS) {
                        val position = runCatching { entry.satellite.getPosition(ground, Date(nowMs + seconds * 1000L)) }.getOrNull() ?: break
                        val azimuth = Math.toDegrees(position.azimuth).toFloat()
                        val elevation = Math.toDegrees(position.elevation).toFloat()
                        if (!azimuth.isFinite() || elevation !in -90f..90f) break
                        add(ArOrbitPoint(seconds, normalizarGraus(azimuth), elevation))
                    }
                }
                if (points.size >= 2) put(key, ArCandidateForecast(nowMs, points))
            }
        }
    }

    fun calcularAstroPosicoes(obsLat: Double, obsLon: Double): List<AstroObjeto> {
        val nowMs = System.currentTimeMillis()
        val jd = 2440587.5 + (nowMs / 86400000.0)
        val d = jd - 2451545.0
        val phi = Math.toRadians(obsLat)
        val gmst = Math.toRadians((280.46061837 + 360.98564736629 * d) % 360.0)
        val lst = gmst + Math.toRadians(obsLon)
        val e = Math.toRadians(23.439 - 0.00000036 * d)

        val resultado = mutableListOf<AstroObjeto>()

        // 1. SOL
        val g = Math.toRadians((357.529 + 0.98560028 * d) % 360.0)
        val q = (280.459 + 0.98564736 * d) % 360.0
        val lSun = Math.toRadians((q + 1.915 * sin(g) + 0.020 * sin(2 * g)) % 360.0)
        val decSun = asin(sin(e) * sin(lSun))
        val raSun = atan2(cos(e) * sin(lSun), cos(lSun))
        val haSun = lst - raSun

        val sinElSun = sin(phi) * sin(decSun) + cos(phi) * cos(decSun) * cos(haSun)
        val elSun = Math.toDegrees(asin(sinElSun.coerceIn(-1.0, 1.0))).toFloat()
        val cosElSun = cos(Math.toRadians(elSun.toDouble()))
        val cosAzSun = (sin(decSun) - sin(phi) * sinElSun) / (cos(phi) * cosElSun)
        val sinAzSun = -cos(decSun) * sin(haSun) / cosElSun
        var azSun = Math.toDegrees(atan2(sinAzSun, cosAzSun)).toFloat()
        if (azSun < 0) azSun += 360f

        // Internal legacy name; localized UI labels are selected using TipoAstro.
        resultado.add(AstroObjeto(nome = "Sol", azimute = azSun, elevacao = elSun, tipo = TipoAstro.SOL))

        // 2. LUA
        val lMoon = Math.toRadians((218.316 + 13.176396 * d) % 360.0)
        val mMoon = Math.toRadians((134.963 + 13.064993 * d) % 360.0)
        val fMoon = Math.toRadians((93.272 + 13.229350 * d) % 360.0)

        val lEcl = lMoon + Math.toRadians(6.289 * sin(mMoon))
        val bEcl = Math.toRadians(5.128 * sin(fMoon))

        val sinDecM = sin(bEcl) * cos(e) + cos(bEcl) * sin(e) * sin(lEcl)
        val decMoon = asin(sinDecM.coerceIn(-1.0, 1.0))
        val yRaM = sin(lEcl) * cos(e) - tan(bEcl) * sin(e)
        val xRaM = cos(lEcl)
        val raMoon = atan2(yRaM, xRaM)
        val haMoon = lst - raMoon

        val sinElM = sin(phi) * sin(decMoon) + cos(phi) * cos(decMoon) * cos(haMoon)
        val elMoon = Math.toDegrees(asin(sinElM.coerceIn(-1.0, 1.0))).toFloat()
        val cosElMoon = cos(Math.toRadians(elMoon.toDouble()))
        val cosAzM = (sin(decMoon) - sin(phi) * sinElM) / (cos(phi) * cosElMoon)
        val sinAzM = -cos(decMoon) * sin(haMoon) / cosElMoon
        var azMoon = Math.toDegrees(atan2(sinAzM, cosAzM)).toFloat()
        if (azMoon < 0) azMoon += 360f

        resultado.add(AstroObjeto(nome = "Lua", azimute = azMoon, elevacao = elMoon, tipo = TipoAstro.LUA))

        return resultado
    }

    private fun gerarConstelacaoFallback(): List<TLE> {
        val now = System.currentTimeMillis()
        val list = ArrayList<TLE>(6300)

        val conchas = arrayOf(
            Triple(53.2, 15.06, Pair(72, 22)),
            Triple(53.2, 15.08, Pair(72, 22)),
            Triple(43.0, 15.10, Pair(72, 28)),
            Triple(70.0, 15.00, Pair(36, 20)),
            Triple(97.6, 15.00, Pair(18, 20))
        )

        var idSat = 10000
        for ((incDeg, nrev, config) in conchas) {
            val planos = config.first
            val satsPorPlano = config.second
            val incRad = Math.toRadians(incDeg)

            for (p in 0 until planos) {
                val raan0 = Math.toRadians((p * (360.0 / planos)) % 360.0)
                for (s in 0 until satsPorPlano) {
                    val m0 = Math.toRadians((s * (360.0 / satsPorPlano)) % 360.0)
                    idSat++
                    list.add(
                        TLE(
                            name = "STARLINK-$idSat",
                            line1 = "",
                            line2 = "",
                            incRad = incRad,
                            raan0Rad = raan0,
                            argPRad = 0.0,
                            m0Rad = m0,
                            nRevDay = nrev,
                            epochTimeMs = now
                        )
                    )
                }
            }
        }
        return list
    }
}
