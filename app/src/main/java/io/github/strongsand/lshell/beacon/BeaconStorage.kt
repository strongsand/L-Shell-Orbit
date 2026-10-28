package io.github.strongsand.lshell.beacon

import android.content.Context

class BeaconPreferences(context: Context) : BeaconSyncCursor {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun pairedBeacon(): PairedBeacon? {
        val id = prefs.getString(KEY_ID, null) ?: return null
        val alias = prefs.getString(KEY_ALIAS, null) ?: return null
        return PairedBeacon(
            device = BeaconDevice(
                id = id,
                name = prefs.getString(KEY_NAME, null) ?: "L-Shell Beacon",
                host = prefs.getString(KEY_HOST, null) ?: AndroidNsdBeaconDiscovery.DEFAULT_HOSTNAME,
                port = prefs.getInt(KEY_PORT, 0),
                firmwareVersion = prefs.getString(KEY_FIRMWARE, null)
            ),
            keyAlias = alias,
            pairedAtMillis = prefs.getLong(KEY_PAIRED_AT, 0L),
            lastSyncedSequence = prefs.getLong(KEY_LAST_SEQUENCE, 0L).coerceAtLeast(0L)
        )
    }

    fun savePairedBeacon(beacon: PairedBeacon) {
        prefs.edit()
            .putString(KEY_ID, beacon.device.id)
            .putString(KEY_NAME, beacon.device.name)
            .putString(KEY_HOST, beacon.device.host)
            .putInt(KEY_PORT, beacon.device.port)
            .putString(KEY_FIRMWARE, beacon.device.firmwareVersion)
            .putString(KEY_ALIAS, beacon.keyAlias)
            .putLong(KEY_PAIRED_AT, beacon.pairedAtMillis)
            .putLong(KEY_LAST_SEQUENCE, beacon.lastSyncedSequence)
            .apply()
    }

    override fun configuredBeacon(): BeaconDevice? {
        val id = prefs.getString(KEY_CONFIGURED_ID, null) ?: return null
        return BeaconDevice(
            id = id,
            name = prefs.getString(KEY_CONFIGURED_NAME, null) ?: "L-Shell Beacon",
            host = prefs.getString(KEY_CONFIGURED_HOST, null).orEmpty(),
            port = prefs.getInt(KEY_CONFIGURED_PORT, 0),
            firmwareVersion = prefs.getString(KEY_CONFIGURED_FIRMWARE, null),
            protocolVersion = prefs.getInt(KEY_CONFIGURED_PROTOCOL, 0).takeIf { it > 0 },
            implementation = prefs.getString("configured_implementation", null),
            capabilities = prefs.getStringSet("configured_capabilities", emptySet()).orEmpty().toSet()
        )
    }

    fun saveConfiguredBeacon(device: BeaconDevice) {
        prefs.edit()
            .putString(KEY_CONFIGURED_ID, device.id)
            .putString("configured_implementation", device.implementation)
            .putStringSet("configured_capabilities", device.capabilities)
            .putString(KEY_CONFIGURED_NAME, device.name)
            .putString(KEY_CONFIGURED_HOST, device.host)
            .putInt(KEY_CONFIGURED_PORT, device.port)
            .putString(KEY_CONFIGURED_FIRMWARE, device.firmwareVersion)
            .putInt(KEY_CONFIGURED_PROTOCOL, device.protocolVersion ?: 0)
            .apply()
    }

    override fun setLastSyncedSequence(sequence: Long) { prefs.edit()
        .putLong(KEY_LAST_SEQUENCE, sequence).putLong(KEY_LAST_SYNC_AT, System.currentTimeMillis()).apply()
    }
    override fun lastSyncedSequence(): Long = prefs.getLong(KEY_LAST_SEQUENCE, 0L).coerceAtLeast(0L)
    fun confirmDiscardThrough(sequence: Long) {
        require(sequence > lastSyncedSequence())
        check(prefs.edit().putLong(KEY_LAST_SEQUENCE, sequence).commit())
    }
    fun lastSyncAtMillis(): Long? = prefs.getLong(KEY_LAST_SYNC_AT, 0L).takeIf { it > 0L }

    fun settings(): BeaconSettings = BeaconSettings(
        backgroundSync = prefs.getBoolean("background_sync", false),
        syncIntervalMinutes = BeaconSyncSchedule.normalizeInterval(prefs.getLong("sync_interval_minutes", 30)),
        automaticSync = prefs.getBoolean(KEY_AUTO_SYNC, true),
        wifiOnly = prefs.getBoolean(KEY_WIFI_ONLY, true),
        retentionDays = prefs.getInt(KEY_RETENTION, 30).coerceIn(1, 365),
        notifications = prefs.getBoolean(KEY_NOTIFICATIONS, true),
        notifyOffline = prefs.getBoolean(KEY_NOTIFY_OFFLINE, true),
        notifyDishyUnavailable = prefs.getBoolean(KEY_NOTIFY_DISHY, true)
    )

    fun saveSettings(settings: BeaconSettings) {
        prefs.edit()
            .putBoolean("background_sync", settings.backgroundSync)
            .putLong("sync_interval_minutes", BeaconSyncSchedule.normalizeInterval(settings.syncIntervalMinutes))
            .putBoolean(KEY_AUTO_SYNC, settings.automaticSync)
            .putBoolean(KEY_WIFI_ONLY, settings.wifiOnly)
            .putInt(KEY_RETENTION, settings.retentionDays.coerceIn(1, 365))
            .putBoolean(KEY_NOTIFICATIONS, settings.notifications)
            .putBoolean(KEY_NOTIFY_OFFLINE, settings.notifyOffline)
            .putBoolean(KEY_NOTIFY_DISHY, settings.notifyDishyUnavailable)
            .apply()
    }

    fun clearPairing() {
        prefs.edit().remove(KEY_ID).remove(KEY_NAME).remove(KEY_HOST).remove(KEY_PORT)
            .remove(KEY_FIRMWARE).remove(KEY_ALIAS).remove(KEY_PAIRED_AT).remove(KEY_LAST_SEQUENCE)
            .remove(KEY_LAST_SYNC_AT)
            .remove(KEY_CONFIGURED_ID).remove(KEY_CONFIGURED_NAME).remove(KEY_CONFIGURED_HOST)
            .remove(KEY_CONFIGURED_PORT).remove(KEY_CONFIGURED_FIRMWARE).remove(KEY_CONFIGURED_PROTOCOL)
            .remove("configured_implementation").remove("configured_capabilities")
            .commit()
    }

    fun recordBackgroundSync(imported: Int) = prefs.edit()
        .putLong(KEY_LAST_SYNC_AT, System.currentTimeMillis())
        .putInt("background_imported", imported).putLong("background_completed", System.currentTimeMillis()).apply()

    fun observeSync(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.registerOnSharedPreferenceChangeListener(listener)

    fun stopObservingSync(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.unregisterOnSharedPreferenceChangeListener(listener)

    private companion object {
        const val FILE = "beacon_preferences"
        const val KEY_ID = "paired_id"
        const val KEY_NAME = "paired_name"
        const val KEY_HOST = "paired_host"
        const val KEY_PORT = "paired_port"
        const val KEY_FIRMWARE = "paired_firmware"
        const val KEY_ALIAS = "paired_key_alias"
        const val KEY_PAIRED_AT = "paired_at"
        const val KEY_LAST_SEQUENCE = "last_sequence"
        const val KEY_LAST_SYNC_AT = "last_sync_at"
        const val KEY_AUTO_SYNC = "automatic_sync"
        const val KEY_WIFI_ONLY = "wifi_only"
        const val KEY_RETENTION = "retention_days"
        const val KEY_NOTIFICATIONS = "notifications"
        const val KEY_NOTIFY_OFFLINE = "notify_offline"
        const val KEY_NOTIFY_DISHY = "notify_dishy_unavailable"
        const val KEY_CONFIGURED_ID = "configured_id"
        const val KEY_CONFIGURED_NAME = "configured_name"
        const val KEY_CONFIGURED_HOST = "configured_host"
        const val KEY_CONFIGURED_PORT = "configured_port"
        const val KEY_CONFIGURED_FIRMWARE = "configured_firmware"
        const val KEY_CONFIGURED_PROTOCOL = "configured_protocol"
    }
}
