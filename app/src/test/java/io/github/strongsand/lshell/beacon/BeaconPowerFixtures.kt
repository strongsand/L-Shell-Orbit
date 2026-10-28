package io.github.strongsand.lshell.beacon

import org.json.JSONObject

internal enum class BeaconPowerScenario(val key: String) {
    NORMAL("normal"), DISCHARGE("discharge"), LOW_VOLTAGE("low_voltage"), CHARGING("charging"),
    VOLTAGE_DROP("voltage_drop"), SOLAR("solar"), DISCONNECTED("disconnected")
}

/** Test-only C++ v0.1 samples. This class is excluded from the runtime APK. */
internal object BeaconPowerFixtures {
    fun read(text: String, scenario: BeaconPowerScenario): List<BeaconPowerPayload> {
        val root = JSONObject(text)
        require(root.getInt("fixture_version") == 1)
        val scenarios = root.getJSONArray("scenarios")
        val entry = (0 until scenarios.length()).map { scenarios.getJSONObject(it) }
            .single { it.getString("id") == scenario.key }
        val readings = entry.getJSONArray("readings")
        require(readings.length() in 1..120)
        return (0 until readings.length()).map { index ->
            val reading = readings.getJSONObject(index)
            // The frame's source uptime is virtual. Do not assign a fake wall-clock timestamp.
            val status = JSONObject().put("boot_id", reading.get("boot_id"))
                .put("beacon_uptime_ms", reading.get("capture_uptime_ms")).put("electrical", reading)
            BeaconPowerParser.parse(status, setOf(POWER_SIMULATION_CAPABILITY)).also {
                require(it.reading != null && it.condition != BeaconPowerCondition.INVALID)
            }
        }
    }
}
