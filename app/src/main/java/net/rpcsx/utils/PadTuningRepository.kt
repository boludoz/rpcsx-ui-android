package net.rpcsx.utils

import net.rpcsx.RPCSX
import org.json.JSONObject

/**
 * Per-player-slot pad tuning (deadzone, anti-deadzone, squircle, trigger
 * threshold, vibration multiplier/threshold/switch-motors, PS3 peripheral
 * type). Bridges directly to g_cfg_input.player[i]->config - the same
 * per-player cfg_pad desktop RPCS3's Qt pad dialog reads/writes. Global
 * scope only: cfg_pad isn't a per-game concept upstream either, so unlike
 * PerGameConfigRepository there is no per-title override layer here.
 */
object PadTuningRepository {
    // Exact cfg_pad field names (rpcs3/Emu/Io/pad_config.h) - the path the
    // native bridge (_rpcsx_padConfigGet/Set) looks these up by.
    const val LEFT_STICK_DEADZONE = "Left Stick Deadzone"
    const val RIGHT_STICK_DEADZONE = "Right Stick Deadzone"
    const val LEFT_STICK_ANTI_DEADZONE = "Left Stick Anti-Deadzone"
    const val RIGHT_STICK_ANTI_DEADZONE = "Right Stick Anti-Deadzone"
    const val LEFT_SQUIRCLING = "Left Pad Squircling Factor"
    const val RIGHT_SQUIRCLING = "Right Pad Squircling Factor"
    const val LEFT_TRIGGER_THRESHOLD = "Left Trigger Threshold"
    const val RIGHT_TRIGGER_THRESHOLD = "Right Trigger Threshold"
    const val VIBRATION_LARGE_MULTIPLIER = "Large Vibration Motor Multiplier"
    const val VIBRATION_SMALL_MULTIPLIER = "Small Vibration Motor Multiplier"
    const val SWITCH_VIBRATION_MOTORS = "Switch Vibration Motors"
    const val VIBRATION_THRESHOLD = "Vibration Threshold"
    const val DEVICE_CLASS_TYPE = "Device Class Type"
    const val COLOR_R = "Color Value R"
    const val COLOR_G = "Color Value G"
    const val COLOR_B = "Color Value B"
    const val LED_LOW_BATTERY_BLINK = "Blink LED when battery is below 20%"
    const val LED_BATTERY_INDICATOR = "Use LED as a battery indicator"
    const val LED_BATTERY_BRIGHTNESS = "LED battery indicator brightness"
    const val PLAYER_LED_ENABLED = "Player LED enabled"

    /** Schema node for one field ("type"/"value"/"default"/"min"/"max"/"variants"). */
    fun node(playerIndex: Int, path: String): JSONObject = try {
        JSONObject(RPCSX.instance.padConfigGet(playerIndex, path))
    } catch (_: Exception) {
        JSONObject()
    }

    fun getInt(playerIndex: Int, path: String): Int? =
        node(playerIndex, path).optString("value").toIntOrNull()

    fun getBool(playerIndex: Int, path: String): Boolean? {
        val n = node(playerIndex, path)
        if (!n.has("value")) return null
        return n.optString("value") == "true"
    }

    /** `value` as the JSON scalar the core's config bridge expects. */
    private fun toJsonScalar(value: Any): String = when (value) {
        is Boolean, is Number -> value.toString()
        else -> JSONObject.quote(value.toString())
    }

    fun setInt(playerIndex: Int, path: String, value: Int): Boolean =
        RPCSX.instance.padConfigSet(playerIndex, path, toJsonScalar(value))

    fun setBool(playerIndex: Int, path: String, value: Boolean): Boolean =
        RPCSX.instance.padConfigSet(playerIndex, path, toJsonScalar(value))

    fun resetToDefault(playerIndex: Int, path: String): Boolean =
        RPCSX.instance.padConfigResetToDefault(playerIndex, path)
}
