package io.github.strongsand.lshell

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class BackupFormatTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun row(n: Long = 1000L, beacon: Boolean = false): JSONObject = JSONObject().apply {
        BackupRecordCodec.historyFields.forEach { put(it, JSONObject.NULL) }
        put("time_ms", n); put("kind", if (beacon) "beacon_history" else "reading"); put("label", "Synthetic")
        put("source", if (beacon) "BEACON" else "LOCAL_PHONE"); put("download", 0.0)
        if (beacon) { put("beacon_id", "synthetic-beacon"); put("beacon_sequence", n) }
    }
    private fun archive(count: Int = 2): File {
        val dir = temporary.newFolder(); val output = File(temporary.newFolder(), "backup.zip")
        val writer = BackupWriter(dir)
        writer.records(BackupCategory.READINGS) { emit -> repeat(count) { emit(BackupRecordCodec.wrap(BackupCategory.READINGS, row(1000L + it))) } }
        writer.settings(BackupSettingsCodec.encode(listOf(BackupPreference("orbit_experience", "theme", "AMOLED"))))
        writer.finish(output, "synthetic", 2, 7, 123000, "UTC")
        return output
    }
    private fun validate(file: File) = BackupValidator.validate(file, temporary.newFolder())
    private fun modify(original: File, transform: (String, ByteArray) -> Pair<String, ByteArray>?): File {
        val output = File(temporary.newFolder(), "changed.zip")
        ZipFile(original).use { input -> ZipOutputStream(output.outputStream()).use { zip ->
            input.entries().asSequence().forEach { entry ->
                val changed = transform(entry.name, input.getInputStream(entry).readBytes())
                if (changed != null) { zip.putNextEntry(ZipEntry(changed.first)); zip.write(changed.second); zip.closeEntry() }
            }
        } }
        return output
    }
    private fun bad(file: File, reason: BackupException.Reason? = null) {
        try { validate(file).close(); fail("Invalid archive accepted") }
        catch (e: BackupException) { if (reason != null) assertEquals(reason, e.reason) }
    }
    @Test fun realZipRoundtripManifestHashesCountsAndNullableMetrics() {
        validate(archive()).use { backup ->
            assertEquals(1, backup.manifest.databaseVersion - 6)
            assertEquals(2L, backup.manifest.counts[BackupCategory.READINGS])
            assertEquals("UTC", backup.manifest.zone)
            backup.verifyStaged()
            assertEquals(2L, backup.records(BackupCategory.READINGS) {
                assertTrue(it.getJSONObject("data").isNull("latency"))
                assertEquals(0.0, it.getJSONObject("data").getDouble("download"), 0.0)
            })
            assertEquals("AMOLED", BackupSettingsCodec.plan(backupJson(backup.file(BackupCategory.SETTINGS).readBytes()), false).compatible.single().value)
        }
    }
    @Test fun emptyDatabaseBackupIsValid() { validate(archive(0)).use { assertEquals(0L, it.manifest.counts[BackupCategory.READINGS]) } }
    @Test fun streamingLargeHistoryDoesNotRequireACollection() {
        validate(archive(30_000)).use { var seen = 0L
            it.records(BackupCategory.READINGS) { seen++ }; assertEquals(30_000L, seen) }
    }
    @Test fun checksumMismatchRefused() { bad(modify(archive()) { name, b -> name to if (name.endsWith("jsonl")) b + 'x'.code.toByte() else b }) }
    @Test fun missingEntryRefused() { bad(modify(archive()) { name, b -> if (name.endsWith("jsonl")) null else name to b }) }
    @Test fun wrongCountsRefused() { bad(modify(archive()) { name, b -> name to if (name == "manifest.json")
        JSONObject(String(b)).apply { getJSONArray("files").getJSONObject(0).put("records", 3); getJSONObject("counts").put("READINGS", 3) }.toString().toByteArray() else b }) }
    @Test fun futureBackupRefusedClearly() { bad(modify(archive()) { name, b -> name to if (name == "manifest.json")
        JSONObject(String(b)).put("backupSchemaVersion", 2).toString().toByteArray() else b }, BackupException.Reason.FUTURE_VERSION) }
    @Test fun futureEntryRefusedClearly() { bad(modify(archive()) { name, b -> name to if (name == "manifest.json")
        JSONObject(String(b)).apply { getJSONArray("files").getJSONObject(0).put("schemaVersion", 2) }.toString().toByteArray() else b }, BackupException.Reason.FUTURE_VERSION) }
    @Test fun ordinaryDiagnosticExportIsNotABackup() {
        val f = File(temporary.newFolder(), "export.json"); f.writeText("{\"report\":1}"); bad(f, BackupException.Reason.NOT_BACKUP)
    }
    @Test fun traversalAndAbsoluteAndUnknownPathsRejected() {
        listOf("../history.jsonl", "/history.jsonl", "C:\\history.jsonl", "unexpected.jsonl").forEach { path ->
            bad(modify(archive()) { name, b -> (if (name.endsWith("jsonl")) path else name) to b })
        }
    }
    @Test fun duplicateManifestDescriptorsRejected() { bad(modify(archive()) { name, b -> name to if (name == "manifest.json")
        JSONObject(String(b)).apply { getJSONArray("files").put(getJSONArray("files").getJSONObject(0)) }.toString().toByteArray() else b }) }
    @Test fun symlinkCentralModeRejectedWithoutExtracting() {
        val f = archive(); val b = f.readBytes()
        val i = (0 until b.size - 46).first { b[it] == 0x50.toByte() && b[it+1] == 0x4b.toByte() && b[it+2] == 1.toByte() && b[it+3] == 2.toByte() }
        ByteBuffer.wrap(b, i + 38, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(0xa1ff0000.toInt())
        f.writeBytes(b); bad(f)
    }
    @Test fun stagedTamperingIsCaughtBeforeRestore() { validate(archive()).use { b ->
        b.file(BackupCategory.READINGS).appendText("x")
        assertThrows(IllegalArgumentException::class.java) { b.verifyStaged() }
    } }
    @Test fun invalidJsonlMissingNewlineRefused() {
        val f = File(temporary.newFolder(), "line"); f.writeText("{\"a\":1}")
        assertThrows(IllegalArgumentException::class.java) { backupLines(f) {} }
    }
    @Test fun jsonBoundsNestingAndTrailingGarbage() {
        listOf("{\"x\":1} {}", "{\"x\":" + "[".repeat(49) + "0" + "]".repeat(49) + "}").forEach {
            assertThrows(IllegalArgumentException::class.java) { backupJson(it.toByteArray()) }
        }
    }
    @Test fun malformedUtf8Rejected() { assertThrows(Exception::class.java) { backupJson(byteArrayOf(123, 34, -1, 34, 58, 49, 125)) } }
    @Test fun strictJsonRejectsDuplicateFieldsAndLenientTokenerSyntax() {
        listOf("{\"x\":1,\"x\":2}", "{\"x\":1,\"\\u0078\":2}", "{'x':1}", "{x:1}",
            "{\"x\":01}", "{\"x\":1,}", "{\"x\":[1,]}", "{\"x\":undefined}", "{\"x\":/*comment*/1}").forEach {
            assertThrows(Exception::class.java) { backupJson(it.toByteArray()) }
        }
    }
    @Test fun giantJsonlLineIsBoundedBeforeParsing() {
        val f = File(temporary.newFolder(), "giant")
        f.outputStream().use { out -> repeat(BackupLimits.MAX_LINE / 1024 + 1) { out.write(ByteArray(1024) { 65 }) } }
        assertThrows(IllegalArgumentException::class.java) { backupLines(f) {} }
    }
    @Test fun oversizedArchiveRejectedBeforeOpeningEntries() {
        val f = File(temporary.newFolder(), "huge.zip")
        java.io.RandomAccessFile(f, "rw").use { it.setLength(BackupLimits.MAX_ARCHIVE + 1) }
        bad(f, BackupException.Reason.NOT_BACKUP)
    }
    @Test fun missingManifestFieldAndMalformedJsonRejected() {
        bad(modify(archive()) { name, b -> name to if (name == "manifest.json") JSONObject(String(b)).apply { remove("backupId") }.toString().toByteArray() else b })
        bad(modify(archive()) { name, b -> name to if (name == "manifest.json") "{broken".toByteArray() else b })
    }
    @Test fun identitySurvivesJsonOrderAndNumericSpelling() {
        val a = row(); val b = JSONObject(a.toString()).put("download", 0)
        assertEquals(BackupRecordCodec.identity(BackupCategory.READINGS, a), BackupRecordCodec.identity(BackupCategory.READINGS, b))
        assertNotEquals(BackupRecordCodec.identity(BackupCategory.READINGS, a), BackupRecordCodec.identity(BackupCategory.READINGS, b.put("download", JSONObject.NULL)))
    }
    @Test fun beaconAndTerminalIdentitiesDoNotUsePhysicalSqliteIds() {
        assertEquals("beacon:synthetic-beacon:1000", BackupRecordCodec.identity(BackupCategory.READINGS, row(beacon = true)))
        val t = row().put("kind", "terminal_history").put("source_boot", 9).put("source_counter", 21)
        assertEquals("terminal:9:21", BackupRecordCodec.identity(BackupCategory.READINGS, t))
    }
    @Test fun invalidMetricAndIncompleteBeaconIdentityAreRejected() {
        assertThrows(Exception::class.java) { BackupRecordCodec.validate(BackupCategory.READINGS,
            BackupRecordCodec.wrap(BackupCategory.READINGS, row().put("latency", "20"))) }
        assertThrows(Exception::class.java) { BackupRecordCodec.validate(BackupCategory.READINGS,
            BackupRecordCodec.wrap(BackupCategory.READINGS, row(beacon = true).put("beacon_sequence", -1))) }
    }
    @Test fun unknownRemovedInvalidPreferencesAreIgnoredAndNotApplied() {
        val plan = BackupSettingsCodec.plan(BackupSettingsCodec.encode(listOf(
            BackupPreference("orbit_experience", "theme", "AMOLED"), BackupPreference("orbit_experience", "removed", true),
            BackupPreference("orbit_experience", "theme2", "unknown"), BackupPreference("daily_report_preferences", "hour", 70),
            BackupPreference("report_archive", "tree", "content://old/grant"))), false)
        assertEquals(1, plan.compatible.size); assertEquals(4, plan.ignored)
    }
    @Test fun automationRequiresSeparateExplicitConsent() {
        val json = BackupSettingsCodec.encode(listOf(BackupPreference("automatic_speed_tests", "scheduled", true)))
        assertTrue(BackupSettingsCodec.plan(json, false).automationRequested)
        assertEquals(1, BackupSettingsCodec.plan(json, false).ignored)
        assertEquals(1, BackupSettingsCodec.plan(json, true).compatible.size)
    }
    @Test fun runtimeAndSecretsCannotBecomeCompatiblePreferences() {
        val json = BackupSettingsCodec.encode(listOf(BackupPreference("beacon_preferences", "paired_key_alias", "excluded"),
            BackupPreference("monitor_preferences", "enabled", true), BackupPreference("traffic_session_runtime", "id", "old")))
        assertEquals(3, BackupSettingsCodec.plan(json, true).ignored)
    }
    @Test fun everyTestOriginIsPreservedIncludingLegacyAndFutureUnknown() {
        (TestOrigin.entries.map { it.name } + listOf("LEGACY", "FUTURE_ORIGIN")).forEach { origin ->
            val d = RecordedTest("10", TestOrigin.UNKNOWN, 1000, 2000, "COMPLETED", listOf(0.0), emptyList(), listOf(20.0)).json().put("origin", origin)
            val r = BackupRecordCodec.wrap(BackupCategory.TESTS, d); BackupRecordCodec.validate(BackupCategory.TESTS, r)
            assertEquals(origin, r.getJSONObject("data").getString("origin"))
        }
    }
    @Test fun activeRuntimeIsNeverAdmittedAsHistoricalResult() {
        val d = RecordedTest("10", TestOrigin.MANUAL, 1000, 2000, "RUNNING", emptyList(), emptyList(), emptyList()).json()
        assertThrows(Exception::class.java) { BackupRecordCodec.validate(BackupCategory.TESTS, BackupRecordCodec.wrap(BackupCategory.TESTS, d)) }
    }
    @Test fun inconsistentExecutionDetailIsRejectedBeforeTheProductionReader() {
        val detail = historicalTestDetail(ConnectionTestState(id = 10, phase = ConnectionTestPhase.COMPLETED,
            startedAt = 1000, endedAt = 2000, result = SpeedTestResult(2f, 1f, 20f)))
        val data = backupArchivedTest(detail, 3000)
        data.getJSONObject("detail").remove("total")
        assertThrows(Exception::class.java) {
            BackupRecordCodec.validate(BackupCategory.TESTS, BackupRecordCodec.wrap(BackupCategory.TESTS, data))
        }
    }
    @Test fun trafficMetadataAndClosedWeeklyMonthlySnapshotsRoundtripWithoutRecalculation() {
        val traffic = TrafficSessionMetadata.start("Synthetic", 1000, "UTC", id = "synthetic-session").copy(endedAt = 4000, bootCount = -1)
        BackupRecordCodec.validate(BackupCategory.TRAFFIC, BackupRecordCodec.wrap(BackupCategory.TRAFFIC, TrafficSessionMetadata.json(traffic)))
        HealthPeriodKind.entries.forEach { kind ->
            val period = HealthPeriod.containing(kind, 1_700_000_000_000, java.time.ZoneId.of("UTC"))
            val day = ConnectionHealthAnalyzer.day(emptyList(), emptyList(), period.start, period.start + 86_400_000, java.time.ZoneId.of("UTC"))
            val report = ConnectionHealthAnalyzer.report(period, listOf(day), null, period.end, emptyList())
            val category = if (kind == HealthPeriodKind.WEEK) BackupCategory.WEEKLY else BackupCategory.MONTHLY
            val json = ConnectionHealthJson.report(report)
            BackupRecordCodec.validate(category, BackupRecordCodec.wrap(category, json))
            assertEquals(backupCanonical(json), backupCanonical(ConnectionHealthJson.report(ConnectionHealthJson.report(json))))
        }
    }
}
