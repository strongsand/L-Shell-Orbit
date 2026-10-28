package io.github.strongsand.lshell

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/** Only metadata; analysis is recomputed from retained HistoryStore rows, including later Beacon imports. */
data class TrafficSession(val id: String, val name: String, val startedAt: Long,
    val endedAt: Long? = null, val timezone: String, val createdAt: Long = startedAt,
    val terminalModel: String? = null, val beaconConfigured: Boolean = false,
    val mode: TrafficSessionMode = TrafficSessionMode.BOTH, val bootCount: Int = -1,
    val interrupted: Boolean = false) {
    val active get() = endedAt == null
}

object TrafficSessionMetadata {
    fun start(name: String, now: Long, zone: String, terminalModel: String? = null, beacon: Boolean = false,
        id: String = UUID.randomUUID().toString(), mode: TrafficSessionMode = TrafficSessionMode.BOTH,
        bootCount: Int = -1): TrafficSession {
        require(now > 0 && id.matches(Regex("[A-Za-z0-9-]{1,80}")))
        val zoneId = ZoneId.of(zone)
        val default = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(zoneId).format(Instant.ofEpochMilli(now))
        return TrafficSession(id, name.trim().take(120).ifEmpty { default }, now, timezone = zone,
            terminalModel = terminalModel?.take(120), beaconConfigured = beacon, mode = mode, bootCount = bootCount)
    }
    fun finish(session: TrafficSession, now: Long): TrafficSession {
        require(session.active && now >= session.startedAt)
        return session.copy(endedAt = now)
    }
    fun json(session: TrafficSession): JSONObject = JSONObject().apply {
        put("id", session.id); put("name", session.name); put("start_ms", session.startedAt)
        put("end_ms", session.endedAt ?: JSONObject.NULL); put("timezone", session.timezone)
        put("created_ms", session.createdAt); put("terminal_model", session.terminalModel ?: JSONObject.NULL)
        put("beacon_configured", session.beaconConfigured)
        put("mode", session.mode.name); put("boot_count", session.bootCount); put("interrupted", session.interrupted)
    }
    fun encode(sessions: List<TrafficSession>): String = JSONObject().put("version", 1)
        .put("sessions", JSONArray().apply { sessions.forEach { put(json(it)) } }).toString()
    fun decode(text: String): List<TrafficSession> {
        val root = JSONObject(text)
        require(root.getInt("version") == 1)
        val array = root.getJSONArray("sessions")
        require(array.length() <= 1000)
        return List(array.length()) { index ->
            val item = array.getJSONObject(index)
            val start = item.getLong("start_ms")
            val end = if (item.isNull("end_ms")) null else item.getLong("end_ms")
            val zone = item.getString("timezone"); ZoneId.of(zone)
            require(start > 0 && (end == null || end >= start))
            require(item.getString("id").matches(Regex("[A-Za-z0-9-]{1,80}")))
            require(item.getString("name").length <= 120)
            TrafficSession(item.getString("id"), item.getString("name"), start, end, zone,
                item.getLong("created_ms"), if (item.isNull("terminal_model")) null else item.getString("terminal_model"),
                item.optBoolean("beacon_configured"),
                TrafficSessionMode.entries.firstOrNull { it.name == item.optString("mode") } ?: TrafficSessionMode.BOTH,
                item.optInt("boot_count", -1), item.optBoolean("interrupted"))
        }.also { require(it.count { s -> s.active } <= 1 && it.map { s -> s.id }.distinct().size == it.size) }
    }
}

class TrafficSessionStore(context: Context) {
    init { BackupGate.ensureRecovered(context) }
    private val file = AtomicFile(File(context.applicationContext.filesDir, "traffic_sessions.json"))
    fun read(): List<TrafficSession> = synchronized(lock) {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) emptyList()
        else TrafficSessionMetadata.decode(file.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() })
    }
    fun start(name: String, now: Long, zone: String, terminalModel: String?, beacon: Boolean,
        mode: TrafficSessionMode = TrafficSessionMode.BOTH, bootCount: Int = -1): TrafficSession = synchronized(lock) {
        val sessions = read()
        require(sessions.none { it.active } && sessions.size < 1000)
        val session = TrafficSessionMetadata.start(name, now, zone, terminalModel, beacon, mode = mode, bootCount = bootCount)
        write(listOf(session) + sessions)
        session
    }
    fun finish(id: String, now: Long, interrupted: Boolean = false): TrafficSession = synchronized(lock) {
        val sessions = read()
        val original = sessions.first { it.id == id }
        if (!original.active) return@synchronized original
        val session = TrafficSessionMetadata.finish(original, now).copy(interrupted = interrupted)
        write(sessions.map { if (it.id == id) session else it })
        session
    }
    private fun write(sessions: List<TrafficSession>) {
        val stream = file.startWrite()
        try { stream.write(TrafficSessionMetadata.encode(sessions).toByteArray(Charsets.UTF_8)); file.finishWrite(stream)
            revision.value++ }
        catch (e: Exception) { file.failWrite(stream); throw e }
    }
    companion object {
        private val lock = BackupGate.monitor
        internal val revision = kotlinx.coroutines.flow.MutableStateFlow(0L)
    }
}
