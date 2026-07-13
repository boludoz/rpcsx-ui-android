package net.rpcsx.utils

import android.content.Context
import android.view.InputDevice
import android.view.KeyEvent
import net.rpcsx.Digital1Flags
import net.rpcsx.Digital2Flags

// Matches a connected InputDevice against SDL's community-maintained
// GameControllerDB (https://github.com/mdqinc/SDL_GameControllerDB, bundled
// as assets/game_controller_db.txt, platform:Android entries only) to apply
// a known-good button mapping automatically, the same idea as NetherSX2's
// "Automatic Mapping".
//
// Matching is done by device NAME rather than by parsing each entry's GUID:
// the GUID scheme those entries were generated with (SDL 2.0.16-era Android
// backend, per the file's own header) hashes device name + capability bits
// in a way that isn't documented and couldn't be verified against this
// dataset (checked empirically: none of the bundled entries decode to a
// plain vendor/product byte layout the way current SDL's GUID scheme does).
// Android's InputDevice.name is what's actually reliably reported, so
// matching on that — exact match first, then a conservative substring
// fallback — is the verifiable option: a miss just falls through to the
// app's regular defaults instead of risking a wrong guess.
object GamepadAutoMapper {
    // Same fixed key order SDL's Android backend assigns button indices
    // (bN) from, in order, skipping keys the device doesn't report.
    private val buttonKeyOrder = intArrayOf(
        KeyEvent.KEYCODE_BUTTON_A,
        KeyEvent.KEYCODE_BUTTON_B,
        KeyEvent.KEYCODE_BUTTON_X,
        KeyEvent.KEYCODE_BUTTON_Y,
        KeyEvent.KEYCODE_BACK,
        KeyEvent.KEYCODE_MENU,
        KeyEvent.KEYCODE_BUTTON_MODE,
        KeyEvent.KEYCODE_BUTTON_START,
        KeyEvent.KEYCODE_BUTTON_THUMBL,
        KeyEvent.KEYCODE_BUTTON_THUMBR,
        KeyEvent.KEYCODE_BUTTON_L1,
        KeyEvent.KEYCODE_BUTTON_R1,
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_BUTTON_SELECT,
        KeyEvent.KEYCODE_DPAD_CENTER,
    )

    // SDL role name -> our CELL_PAD bit, mirroring InputBindingPrefs.defaultBindings.
    private val roleToBit = mapOf(
        "a" to Pair(Digital2Flags.CELL_PAD_CTRL_CROSS.bit, 1),
        "b" to Pair(Digital2Flags.CELL_PAD_CTRL_CIRCLE.bit, 1),
        "x" to Pair(Digital2Flags.CELL_PAD_CTRL_SQUARE.bit, 1),
        "y" to Pair(Digital2Flags.CELL_PAD_CTRL_TRIANGLE.bit, 1),
        "leftshoulder" to Pair(Digital2Flags.CELL_PAD_CTRL_L1.bit, 1),
        "rightshoulder" to Pair(Digital2Flags.CELL_PAD_CTRL_R1.bit, 1),
        "lefttrigger" to Pair(Digital2Flags.CELL_PAD_CTRL_L2.bit, 1),
        "righttrigger" to Pair(Digital2Flags.CELL_PAD_CTRL_R2.bit, 1),
        "start" to Pair(Digital1Flags.CELL_PAD_CTRL_START.bit, 0),
        "back" to Pair(Digital1Flags.CELL_PAD_CTRL_SELECT.bit, 0),
        "leftstick" to Pair(Digital1Flags.CELL_PAD_CTRL_L3.bit, 0),
        "rightstick" to Pair(Digital1Flags.CELL_PAD_CTRL_R3.bit, 0),
        "guide" to Pair(Digital1Flags.CELL_PAD_CTRL_PS.bit, 0),
    )

    // Minimum name length eligible for the substring fallback match, to
    // avoid short/generic names ("Pad", "USB") matching unrelated devices.
    private const val MIN_SUBSTRING_NAME_LENGTH = 6

    private data class DbEntry(val name: String, val buttons: Map<String, Int>)

    private var cachedEntries: List<DbEntry>? = null

    private fun loadEntries(context: Context): List<DbEntry> {
        cachedEntries?.let { return it }

        val entries = mutableListOf<DbEntry>()
        context.assets.open("game_controller_db.txt").bufferedReader().useLines { lines ->
            for (line in lines) {
                if (line.isBlank() || line.startsWith("#")) continue
                val fields = line.split(",")
                if (fields.size < 2) continue

                val name = fields[1].trim()
                if (name.isEmpty()) continue

                val buttons = mutableMapOf<String, Int>()
                for (field in fields.drop(2)) {
                    val parts = field.split(":")
                    if (parts.size != 2) continue
                    val (role, target) = parts
                    if (role !in roleToBit) continue
                    // Only plain digital buttons ("bN"); axis/hat-encoded
                    // roles ("aN", "hN.M", "+aN"/"-aN") are already handled
                    // unconditionally by RPCSXActivity for every device.
                    if (target.firstOrNull() == 'b') {
                        val buttonIndex = target.drop(1).toIntOrNull()
                        if (buttonIndex != null) {
                            buttons[role] = buttonIndex
                        }
                    }
                }

                entries += DbEntry(name, buttons)
            }
        }

        return entries.also { cachedEntries = it }
    }

    private fun findEntry(entries: List<DbEntry>, deviceName: String): DbEntry? {
        entries.find { it.name.equals(deviceName, ignoreCase = true) }?.let { return it }

        if (deviceName.length < MIN_SUBSTRING_NAME_LENGTH) return null
        return entries.find { entry ->
            entry.name.length >= MIN_SUBSTRING_NAME_LENGTH &&
                (deviceName.contains(entry.name, ignoreCase = true) ||
                    entry.name.contains(deviceName, ignoreCase = true))
        }
    }

    // Applies a known-good mapping for `device` to `playerSlot` if a DB entry
    // matches its name, otherwise resets that slot to the app's regular
    // defaults (which already work for most standard-compliant controllers
    // via Android's own HID key normalization). Always leaves the slot in a
    // usable state. Returns the matched controller name, or null if no DB
    // entry matched (defaults were applied instead).
    fun applyAutomaticMapping(context: Context, playerSlot: Int, device: InputDevice): String? {
        val entry = device.name?.let { findEntry(loadEntries(context), it) }

        if (entry == null) {
            InputBindingPrefs.saveBindings(playerSlot, InputBindingPrefs.defaultBindings)
            return null
        }

        val presentKeys = buttonKeyOrder.filter { device.hasKeys(it)[0] }
        val bindings = mutableMapOf<Int, Pair<Int, Int>>()
        entry.buttons.forEach { (role, buttonIndex) ->
            val keyCode = presentKeys.getOrNull(buttonIndex) ?: return@forEach
            val bit = roleToBit[role] ?: return@forEach
            bindings[keyCode] = bit
        }

        // Fill in anything the DB entry didn't specify (e.g. no "guide"
        // button listed) with the app's regular defaults for that role.
        InputBindingPrefs.defaultBindings.forEach { (keyCode, bit) ->
            if (bindings.values.none { it == bit }) {
                bindings[keyCode] = bit
            }
        }

        InputBindingPrefs.saveBindings(playerSlot, bindings)
        return entry.name
    }
}
