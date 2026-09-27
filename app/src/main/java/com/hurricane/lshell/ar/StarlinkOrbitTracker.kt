package com.hurricane.lshell.ar

import android.content.Context
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
    val epochTimeMs: Long
)

data class SateliteRA(
    val nome: String,
    val azimute: Float,
    val elevacao: Float,
    val conectado: Boolean = false,
    val noFovAntena: Boolean = true
)

enum class TipoAstro { SOL, LUA }

data class AstroObjeto(
    val nome: String,
    val azimute: Float,
    val elevacao: Float,
    val tipo: TipoAstro
)

object StarlinkOrbitTracker {

    private const val URL_PRINCIPAL = "https://celestrak.org/NORAD/elements/supplemental/sup-gp.php?FILE=starlink&FORMAT=tle"
    private const val URL_SECUNDARIA = "https://celestrak.org/NORAD/elements/gp.php?GROUP=STARLINK&FORMAT=TLE"
    private const val CACHE_FRESH_MS = 6 * 3_600_000L
    private const val COARSE_PASS_MS = 60_000L
    private const val COARSE_ELEVATION_FLOOR_DEG = -12.0
    @Volatile var lastDownloadError: String = ""
        private set

    suspend fun carregarTLEs(context: Context): List<TLE> = withContext(Dispatchers.IO) {
        lastDownloadError = ""
        val cacheFile = File(context.cacheDir, "starlink_supplemental_tle.txt")

        if (cacheFile.exists() && cacheFile.length() > 1_000 &&
            (System.currentTimeMillis() - cacheFile.lastModified()) < CACHE_FRESH_MS) {
            val cached = cacheFile.readText()
            val parsed = parseCatalog(cached)
            if (parsed.isNotEmpty()) return@withContext parsed
        }

        val raw = baixarComUserAgent(URL_PRINCIPAL) ?: baixarComUserAgent(URL_SECUNDARIA)

        if (!raw.isNullOrBlank()) {
            val parsed = parseCatalog(raw)
            if (parsed.isNotEmpty()) {
                try { cacheFile.writeText(raw) } catch (_: Exception) {}
                return@withContext parsed
            }
            lastDownloadError = "Resposta sem órbitas legíveis"
        }

        // A stale real ephemeris is still preferable to an invented constellation.
        if (cacheFile.exists() && cacheFile.length() > 1_000) {
            val parsed = parseCatalog(cacheFile.readText())
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
                lastDownloadError = "HTTP $responseCode"
                null
            }
        } catch (e: Exception) {
            lastDownloadError = e.localizedMessage?.take(90) ?: e.javaClass.simpleName
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun parseCatalog(raw: String): List<TLE> = parseTLEs(raw)

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
                            epochTimeMs = cal.timeInMillis
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

    private data class Sgp4Entry(val name: String, val satellite: Satellite)

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
                    name = tle.name,
                    satellite = SatelliteFactory.createSatellite(PredictTle(arrayOf(tle.name, tle.line1, tle.line2)))
                )
            }.getOrNull()
        }
        return Sgp4TrackerState(tles, latitude, longitude, altitudeM, entries).also { sgp4State = it }
    }

    fun calcularSatellitesVisiveis(
        tles: List<TLE>,
        obsLat: Double,
        obsLon: Double,
        obsAltM: Double,
        minElevacao: Float = 15f,
        apenasFovAntena: Boolean = true,
        distanciaMaximaKm: Float? = null
    ): List<SateliteRA> {
        val nowMs = System.currentTimeMillis()
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
                    add(SateliteRA(entry.name, azimuth, elevation, conectado = false, noFovAntena = dentroFov))
                }
            }
        }.sortedByDescending { it.elevacao }
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
