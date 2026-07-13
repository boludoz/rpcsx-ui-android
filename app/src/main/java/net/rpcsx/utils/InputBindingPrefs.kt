
package net.rpcsx.utils

import android.view.KeyEvent
import net.rpcsx.Digital1Flags
import net.rpcsx.Digital2Flags
import org.json.JSONObject

object InputBindingPrefs {
    
    val defaultBindings = mapOf(
        KeyEvent.KEYCODE_DPAD_UP to Pair(Digital1Flags.CELL_PAD_CTRL_UP.bit, 0),
        KeyEvent.KEYCODE_DPAD_DOWN to Pair(Digital1Flags.CELL_PAD_CTRL_DOWN.bit, 0),
        KeyEvent.KEYCODE_DPAD_LEFT to Pair(Digital1Flags.CELL_PAD_CTRL_LEFT.bit, 0),
        KeyEvent.KEYCODE_DPAD_RIGHT to Pair(Digital1Flags.CELL_PAD_CTRL_RIGHT.bit, 0),
        KeyEvent.KEYCODE_BUTTON_A to Pair(Digital2Flags.CELL_PAD_CTRL_CROSS.bit, 1),
        KeyEvent.KEYCODE_BUTTON_B to Pair(Digital2Flags.CELL_PAD_CTRL_CIRCLE.bit, 1),
        KeyEvent.KEYCODE_BUTTON_X to Pair(Digital2Flags.CELL_PAD_CTRL_SQUARE.bit, 1),
        KeyEvent.KEYCODE_BUTTON_Y to Pair(Digital2Flags.CELL_PAD_CTRL_TRIANGLE.bit, 1),
        KeyEvent.KEYCODE_BUTTON_L1 to Pair(Digital2Flags.CELL_PAD_CTRL_L1.bit, 1),
        KeyEvent.KEYCODE_BUTTON_R1 to Pair(Digital2Flags.CELL_PAD_CTRL_R1.bit, 1),
        KeyEvent.KEYCODE_BUTTON_L2 to Pair(Digital2Flags.CELL_PAD_CTRL_L2.bit, 1),
        KeyEvent.KEYCODE_BUTTON_R2 to Pair(Digital2Flags.CELL_PAD_CTRL_R2.bit, 1),
        KeyEvent.KEYCODE_BUTTON_START to Pair(Digital1Flags.CELL_PAD_CTRL_START.bit, 0),
        KeyEvent.KEYCODE_BUTTON_SELECT to Pair(Digital1Flags.CELL_PAD_CTRL_SELECT.bit, 0),
        KeyEvent.KEYCODE_BUTTON_THUMBL to Pair(Digital1Flags.CELL_PAD_CTRL_L3.bit, 0),
        KeyEvent.KEYCODE_BUTTON_THUMBR to Pair(Digital1Flags.CELL_PAD_CTRL_R3.bit, 0),
        666666 to Pair(Digital1Flags.CELL_PAD_CTRL_PS.bit, 0)
    )

    // Global bindings use "input_bindings_<slot>"; per-game overrides use
    // "input_bindings_<titleId>_<slot>" and fall back to the global map (and
    // then defaults) when a title has no custom mapping saved.
    private fun key(playerSlot: Int, titleId: String? = null) =
        if (titleId.isNullOrEmpty()) "input_bindings_$playerSlot"
        else "input_bindings_${titleId}_$playerSlot"

    fun saveBindings(
        playerSlot: Int,
        bindings: Map<Int, Pair<Int, Int>>,
        titleId: String? = null
    ): Boolean {
        try {
            val json = JSONObject()
            bindings.forEach { (keyCode, value) ->
                json.put(keyCode.toString(), "${value.first},${value.second}")
            }

            GeneralSettings.setValue(key(playerSlot, titleId), json.toString())
        } catch (_: Exception) {
            return false
        }
        return true
    }

    fun hasTitleBindings(titleId: String, playerSlot: Int): Boolean =
        GeneralSettings[key(playerSlot, titleId)] != null

    fun clearTitleBindings(titleId: String) {
        for (slot in 0 until net.rpcsx.MaxGamepadPlayers) {
            GeneralSettings.setValue(key(slot, titleId), null)
        }
    }

    fun loadBindings(playerSlot: Int, titleId: String? = null): Map<Int, Pair<Int, Int>> {
        val jsonString = (if (!titleId.isNullOrEmpty()) {
            GeneralSettings[key(playerSlot, titleId)] as String?
        } else null)
            ?: GeneralSettings[key(playerSlot)] as String?
            ?: return defaultBindings

        val json = JSONObject(jsonString)
        val map = mutableMapOf<Int, Pair<Int, Int>>()

        json.keys().forEach { key ->
            val parts = json.getString(key).split(",")
            val keyCode = key.toIntOrNull() ?: return@forEach
            if (parts.size == 2) {
                map[keyCode] = Pair(parts[0].toIntOrNull() ?: 0, parts[1].toIntOrNull() ?: 0)
            }
        }

        return map
    }

    fun rpcsxKeyCodeToString(keyCode: Int, digitalNumber: Int): String {
        val digital1 = Digital1Flags.values().find { keyCode == it.bit }?.name?.removePrefix("CELL_PAD_CTRL_")
        val digital2 = Digital2Flags.values().find { keyCode == it.bit }?.name?.removePrefix("CELL_PAD_CTRL_")
        if (digitalNumber == 1) return digital2 ?: "Unknown" else return digital1 ?: "Unknown"
    }
}
