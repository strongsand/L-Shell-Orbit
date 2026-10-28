package io.github.strongsand.lshell

import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.time.ZoneId
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Logical format, independent of SQLite's user_version and physical row IDs. */
internal enum class BackupCategory(val path: String) {
    READINGS("history/records.jsonl"), EVENTS("history/events.jsonl"),
    TESTS("tests/results.jsonl"), EXTREME("tests/extreme.jsonl"),
    TRAFFIC("traffic/sessions.jsonl"), DAILY("reports/daily.jsonl"),
    WEEKLY("reports/weekly.jsonl"), MONTHLY("reports/monthly.jsonl"),
    DAYS("history/observation-days.jsonl"), SETTINGS("settings/preferences.json")
}

internal class BackupException(val reason: Reason, cause: Throwable? = null) : IOException(reason.name, cause) {
    enum class Reason { NOT_BACKUP, FUTURE_VERSION, CORRUPT, LIMIT, INVALID_RECORD, BUSY, STORAGE, RECOVERY }
}

internal data class BackupFile(val category: BackupCategory, val records: Long, val bytes: Long, val sha256: String) {
    fun json() = JSONObject().put("path", category.path).put("schemaVersion", 1)
        .put("records", records).put("bytes", bytes).put("sha256", sha256)
}
internal data class BackupManifest(val id: String, val createdAt: Long, val appVersion: String,
    val versionCode: Long, val databaseVersion: Int, val zone: String, val files: List<BackupFile>) {
    val counts get() = files.associate { it.category to it.records }
    fun json() = JSONObject().put("format", FORMAT).put("backupSchemaVersion", VERSION)
        .put("backupId", id).put("createdAt", createdAt).put("appVersionName", appVersion)
        .put("appVersionCode", versionCode).put("sourceDatabaseSchemaVersion", databaseVersion)
        .put("timezone", zone).put("files", JSONArray(files.map { it.json() }))
        .put("counts", JSONObject(files.associate { it.category.name to it.records }))
    companion object {
        const val FORMAT = "l-shell-orbit-backup"
        const val VERSION = 1
        fun parse(j: JSONObject): BackupManifest {
            if (j.optString("format") != FORMAT) throw BackupException(BackupException.Reason.NOT_BACKUP)
            j.backupRequireInteger("backupSchemaVersion", "createdAt", "appVersionCode", "sourceDatabaseSchemaVersion")
            if (j.getInt("backupSchemaVersion") > VERSION) throw BackupException(BackupException.Reason.FUTURE_VERSION)
            require(j.getInt("backupSchemaVersion") == VERSION)
            val a = j.getJSONArray("files")
            require(a.length() in 1..BackupCategory.entries.size)
            val files = (0 until a.length()).map { i -> a.getJSONObject(i).let { f ->
                val category = BackupCategory.entries.firstOrNull { it.path == f.getString("path") }
                    ?: throw BackupException(BackupException.Reason.FUTURE_VERSION)
                f.backupRequireInteger("schemaVersion", "records", "bytes")
                if (f.getInt("schemaVersion") > 1) throw BackupException(BackupException.Reason.FUTURE_VERSION)
                require(f.getInt("schemaVersion") == 1)
                BackupFile(category, f.getLong("records"), f.getLong("bytes"), f.getString("sha256")).also {
                    require(it.records in 0..BackupLimits.MAX_RECORDS && it.bytes in 0..BackupLimits.MAX_TOTAL)
                    require(it.sha256.matches(Regex("[0-9a-f]{64}")))
                    require(j.getJSONObject("counts").getLong(category.name) == it.records)
                }
            } }
            require(files.map { it.category }.distinct().size == files.size)
            require(j.getJSONObject("counts").keys().asSequence().toSet() == files.map { it.category.name }.toSet())
            require(files.any { it.category == BackupCategory.SETTINGS })
            require(files.any { it.category == BackupCategory.READINGS })
            val id = j.getString("backupId"); UUID.fromString(id)
            val zone = j.getString("timezone"); ZoneId.of(zone)
            return BackupManifest(id, j.getLong("createdAt"), j.getString("appVersionName"),
                j.getLong("appVersionCode"), j.getInt("sourceDatabaseSchemaVersion"), zone, files).also {
                require(it.createdAt > 0 && it.versionCode > 0 && it.databaseVersion > 0 && it.appVersion.length in 1..80)
            }
        }
    }
}

internal object BackupLimits {
    const val MAX_ARCHIVE = 256L * 1024 * 1024
    const val MAX_TOTAL = 1024L * 1024 * 1024
    const val MAX_LINE = 2 * 1024 * 1024
    const val MAX_MANIFEST = 32 * 1024
    const val MAX_SETTINGS = 64 * 1024
    const val MAX_RECORDS = 2_000_000L
}

internal fun backupHash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
internal fun ByteArray.hex(): String {
    val digits = "0123456789abcdef"; val chars = CharArray(size * 2)
    forEachIndexed { i, b -> val n = b.toInt() and 255; chars[i * 2] = digits[n ushr 4]; chars[i * 2 + 1] = digits[n and 15] }
    return String(chars)
}
internal fun backupHash(file: File): String {
    val d = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input -> val b = ByteArray(32 * 1024)
        while (true) { val n = input.read(b); if (n < 0) break; d.update(b, 0, n) } }
    return d.digest().hex()
}

/** Stable semantic identity: object order, whitespace and numeric spelling do not change a key. */
internal fun backupCanonical(value: Any?): String = when (value) {
    null, JSONObject.NULL -> "null"
    is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") {
        JSONObject.quote(it) + ":" + backupCanonical(value.get(it)) }
    is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { backupCanonical(value.get(it)) }
    is Number -> java.math.BigDecimal(value.toString()).stripTrailingZeros().toPlainString()
    is Boolean -> value.toString()
    is String -> JSONObject.quote(value)
    else -> error("Unsupported JSON value")
}

/** Bounds allocations before parsing, including nesting. JSONTokener is otherwise lenient. */
internal fun backupJson(bytes: ByteArray): JSONObject {
    val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    var depth = 0; var quoted = false; var escaped = false
    for (c in text) {
        if (quoted) { if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false }
        else if (c == '"') quoted = true
        else if (c == '{' || c == '[') { depth++; require(depth <= 48) }
        else if (c == '}' || c == ']') { depth--; require(depth >= 0) }
    }
    require(depth == 0 && !quoted)
    BackupJsonSyntax(text).validate()
    val tokener = org.json.JSONTokener(text)
    val result = tokener.nextValue() as? JSONObject ?: error("Expected object")
    require(tokener.nextClean() == '\u0000')
    return result
}

internal fun backupLines(file: File, visit: (JSONObject) -> Unit): Long {
    var count = 0L
    file.inputStream().buffered().use { input ->
        val line = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) { require(line.size() == 0) { "Unterminated JSONL" }; break }
            if (b == 10) {
                require(line.size() > 0); visit(backupJson(line.toByteArray())); line.reset()
                count++; require(count <= BackupLimits.MAX_RECORDS)
            } else { require(line.size() < BackupLimits.MAX_LINE); line.write(b) }
        }
    }
    return count
}

/** Only app-controlled filenames are written. Input archive paths are never used as extraction paths. */
internal class ValidatedBackup internal constructor(val manifest: BackupManifest, val directory: File) : Closeable {
    fun file(category: BackupCategory) = File(directory, category.name)
    fun records(category: BackupCategory, visit: (JSONObject) -> Unit) =
        if (category == BackupCategory.SETTINGS || !file(category).exists()) 0L else backupLines(file(category), visit)
    fun verifyStaged() { manifest.files.forEach { require(backupHash(file(it.category)) == it.sha256) } }
    override fun close() { directory.deleteRecursively() }
}

internal class BackupWriter(private val directory: File) {
    private val files = mutableListOf<BackupFile>()
    init { require(directory.mkdirs() || directory.isDirectory) }
    fun records(category: BackupCategory, produce: ((JSONObject) -> Unit) -> Unit) {
        require(category != BackupCategory.SETTINGS && files.none { it.category == category })
        val file = File(directory, category.name); var count = 0L
        file.outputStream().buffered().use { output -> produce { row ->
            BackupRecordCodec.validate(category, row)
            val bytes = row.toString().toByteArray(Charsets.UTF_8)
            require(bytes.size <= BackupLimits.MAX_LINE && ++count <= BackupLimits.MAX_RECORDS)
            output.write(bytes); output.write(10)
        } }
        require(file.length() <= BackupLimits.MAX_TOTAL)
        files += BackupFile(category, count, file.length(), backupHash(file))
    }
    fun settings(settings: JSONObject) {
        require(files.none { it.category == BackupCategory.SETTINGS })
        val bytes = settings.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= BackupLimits.MAX_SETTINGS)
        BackupSettingsCodec.plan(settings, false)
        val file = File(directory, BackupCategory.SETTINGS.name); file.writeBytes(bytes)
        files += BackupFile(BackupCategory.SETTINGS, 1, bytes.size.toLong(), backupHash(bytes))
    }
    fun finish(destination: File, appVersion: String, code: Long, dbVersion: Int,
        now: Long = System.currentTimeMillis(), zone: String = ZoneId.systemDefault().id): BackupManifest {
        require(files.any { it.category == BackupCategory.SETTINGS } && files.sumOf { it.bytes } <= BackupLimits.MAX_TOTAL)
        val manifest = BackupManifest(UUID.randomUUID().toString(), now, appVersion, code, dbVersion, zone, files.toList())
        ZipOutputStream(destination.outputStream().buffered()).use { zip ->
            files.forEach { f -> zip.putNextEntry(ZipEntry(f.category.path))
                File(directory, f.category.name).inputStream().use { it.copyTo(zip) }; zip.closeEntry() }
            zip.putNextEntry(ZipEntry("manifest.json")); zip.write(manifest.json().toString().toByteArray(Charsets.UTF_8)); zip.closeEntry()
        }
        require(destination.length() <= BackupLimits.MAX_ARCHIVE)
        return manifest
    }
}

internal object BackupValidator {
    fun validate(archive: File, tempRoot: File): ValidatedBackup {
        val stage = File(tempRoot, "validated-${UUID.randomUUID()}")
        try {
            if (archive.length() !in 22..BackupLimits.MAX_ARCHIVE) throw BackupException(BackupException.Reason.NOT_BACKUP)
            checkCentralDirectory(archive)
            check(stage.mkdirs())
            ZipFile(archive).use { zip ->
                val entries = zip.entries().asSequence().toList()
                require(entries.size <= BackupCategory.entries.size + 1)
                require(entries.map { it.name }.distinct().size == entries.size)
                val manifestEntry = entries.singleOrNull { it.name == "manifest.json" }
                    ?: throw BackupException(BackupException.Reason.NOT_BACKUP)
                val manifestBytes = zip.getInputStream(manifestEntry).use { readBounded(it, BackupLimits.MAX_MANIFEST.toLong()) }
                val manifest = BackupManifest.parse(backupJson(manifestBytes))
                require(entries.map { it.name }.toSet() == (manifest.files.map { it.category.path } + "manifest.json").toSet())
                var total = manifestBytes.size.toLong()
                manifest.files.forEach { f ->
                    val e = zip.getEntry(f.category.path)
                    require(!e.isDirectory && e.size == f.bytes && e.compressedSize >= 0)
                    // Allow ordinary highly repetitive history; reject extreme compression before inflating.
                    require(e.size <= maxOf(1024 * 1024L, e.compressedSize * 1000))
                    val output = File(stage, f.category.name)
                    zip.getInputStream(e).use { input -> output.outputStream().buffered().use { out ->
                        val b = ByteArray(32 * 1024); var written = 0L
                        while (true) { val n = input.read(b); if (n < 0) break
                            written += n; total += n
                            require(written <= f.bytes && total <= BackupLimits.MAX_TOTAL)
                            out.write(b, 0, n) }
                        require(written == f.bytes)
                    } }
                    require(backupHash(output) == f.sha256)
                    val count = if (f.category == BackupCategory.SETTINGS) {
                        require(output.length() <= BackupLimits.MAX_SETTINGS)
                        BackupSettingsCodec.plan(backupJson(output.readBytes()), false); 1L
                    } else backupLines(output) { BackupRecordCodec.validate(f.category, it) }
                    require(count == f.records)
                }
                return ValidatedBackup(manifest, stage)
            }
        } catch (e: Exception) {
            stage.deleteRecursively()
            if (e is BackupException) throw e
            if (e is IOException && e !is java.util.zip.ZipException && e !is EOFException && e !is java.nio.charset.CharacterCodingException)
                throw BackupException(BackupException.Reason.STORAGE, e)
            throw BackupException(BackupException.Reason.CORRUPT, e)
        }
    }
    fun readBounded(input: InputStream, limit: Long): ByteArray {
        val output = ByteArrayOutputStream(); val b = ByteArray(16 * 1024); var count = 0L
        while (true) { val n = input.read(b); if (n < 0) break; count += n
            if (count > limit) throw BackupException(BackupException.Reason.LIMIT)
            output.write(b, 0, n) }
        return output.toByteArray()
    }
    /** Java ZipEntry does not expose Unix mode. Inspect it before opening any archive member. */
    private fun checkCentralDirectory(file: File) = RandomAccessFile(file, "r").use { input ->
        val size = minOf(input.length(), 65557L).toInt(); val tail = ByteArray(size)
        input.seek(input.length() - size); input.readFully(tail)
        fun u16(b: ByteArray, offset: Int) = ByteBuffer.wrap(b, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 65535
        fun u32(b: ByteArray, offset: Int) = ByteBuffer.wrap(b, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
        val end = (size - 22 downTo 0).firstOrNull { u32(tail, it) == 0x06054b50L && it + 22 + u16(tail, it + 20) == size }
            ?: throw BackupException(BackupException.Reason.NOT_BACKUP)
        require(u16(tail, end + 4) == 0 && u16(tail, end + 6) == 0)
        val count = u16(tail, end + 10)
        require(count == u16(tail, end + 8) && count in 1..BackupCategory.entries.size + 1)
        val offset = u32(tail, end + 16); val centralSize = u32(tail, end + 12)
        require(offset + centralSize == input.length() - size + end)
        input.seek(offset)
        repeat(count) {
            val b = ByteArray(46); input.readFully(b); require(u32(b, 0) == 0x02014b50L)
            require(u16(b, 8) and 1 == 0 && u16(b, 10) in setOf(0, 8)) // no encryption/unknown codec
            val mode = (u32(b, 38) ushr 16).toInt() and 0xf000
            require(mode == 0 || mode == 0x8000) // no symlink, directory, socket or other special entry
            val name = ByteArray(u16(b, 28)); input.readFully(name)
            val path = String(name, Charsets.UTF_8)
            require(path == "manifest.json" || BackupCategory.entries.any { it.path == path })
            input.seek(input.filePointer + u16(b, 30) + u16(b, 32))
            require(input.filePointer <= offset + centralSize)
        }
        require(input.filePointer == offset + centralSize)
    }
}
