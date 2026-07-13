package net.rpcsx.utils

import android.content.Context
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import net.rpcsx.Digital1Flags
import net.rpcsx.Digital2Flags

// Matches a connected InputDevice against SDL's community-maintained
// GameControllerDB (https://github.com/mdqinc/SDL_GameControllerDB, bundled
// as assets/game_controller_db.txt, platform:Android entries only) by
// reconstructing the same GUID SDL's own Android backend computes for that
// device, then comparing it against each entry's GUID field directly —
// the same lookup SDL itself performs, rather than guessing from names.
//
// The bundled DB is crowd-sourced across SDL versions, so its 297 Android
// entries actually use two different GUID layouts (verified by decoding
// every entry in this file):
//  - ~79%: SDL's older Android GUID, which is just InputDevice.getDescriptor()
//    (a stable per-model 16-hex-char string Android itself generates) used
//    as the raw 16 GUID bytes, no further encoding.
//  - ~21%: current SDL's scheme (src/joystick/android/SDL_sysjoystick.c,
//    Android_AddJoystick + SDL_CreateJoystickGUID): bus (u16 LE, always
//    0x0005 = SDL_HARDWARE_BUS_BLUETOOTH on Android) + crc (u16, always
//    0 — this DB's generator doesn't feed a name into it) + vendor id
//    (u16 LE) + 0 + product id (u16 LE) + 0 + button capability mask
//    (u16 LE) + axis capability mask (u16 LE). Confirmed byte-for-byte
//    against real entries, e.g. "8BitDo M30" (vendor 0x2dc8, product
//    0x5006, buttons 0xffff, axes 0x3f) decodes to exactly
//    05000000c82d000006500000ffff3f00.
// Both candidates are computed for the connected device and checked
// against the DB; a device only fails to match if neither its descriptor
// nor its vendor/product/capability signature appears in the file, in
// which case the app's regular defaults are applied instead.
object GamepadAutoMapper {
    private const val BUS_BLUETOOTH = 0x0005

    // SDL's Android getButtonMask() key -> capability bit (low 16 bits only;
    // higher bits get truncated away when packed into the 16-bit GUID word,
    // so keys that only ever set bit 16+ are omitted here).
    private val buttonMaskBits = listOf(
        KeyEvent.KEYCODE_BUTTON_A to 0,
        KeyEvent.KEYCODE_DPAD_CENTER to 0,
        KeyEvent.KEYCODE_BUTTON_B to 1,
        KeyEvent.KEYCODE_BUTTON_X to 2,
        KeyEvent.KEYCODE_BUTTON_Y to 3,
        KeyEvent.KEYCODE_BACK to 4,
        KeyEvent.KEYCODE_BUTTON_SELECT to 4,
        KeyEvent.KEYCODE_BUTTON_MODE to 5,
        KeyEvent.KEYCODE_MENU to 6,
        KeyEvent.KEYCODE_BUTTON_START to 6,
        KeyEvent.KEYCODE_BUTTON_THUMBL to 7,
        KeyEvent.KEYCODE_BUTTON_THUMBR to 8,
        KeyEvent.KEYCODE_BUTTON_L1 to 9,
        KeyEvent.KEYCODE_BUTTON_R1 to 10,
        KeyEvent.KEYCODE_DPAD_UP to 11,
        KeyEvent.KEYCODE_DPAD_DOWN to 12,
        KeyEvent.KEYCODE_DPAD_LEFT to 13,
        KeyEvent.KEYCODE_DPAD_RIGHT to 14,
        KeyEvent.KEYCODE_BUTTON_L2 to 15,
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

    // Fixed key order SDL's Android backend assigns "bN" button indices from,
    // in order, skipping keys the device doesn't report. Used only to turn a
    // matched DB entry's role->index mapping back into a real keycode.
    private val buttonKeyOrder = intArrayOf(
        KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_X,
        KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU,
        KeyEvent.KEYCODE_BUTTON_MODE, KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_THUMBL,
        KeyEvent.KEYCODE_BUTTON_THUMBR, KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1,
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_DPAD_CENTER,
    )

    // Minimum name length eligible for the last-resort substring fallback,
    // to avoid short/generic names ("Pad", "USB") matching unrelated devices.
    private const val MIN_SUBSTRING_NAME_LENGTH = 6

    private data class DbEntry(val guid: String, val name: String, val buttons: Map<String, Int>)

    private var cachedEntries: List<DbEntry>? = null

    private fun loadEntries(context: Context): List<DbEntry> {
        cachedEntries?.let { return it }

        val entries = mutableListOf<DbEntry>()
        context.assets.open("game_controller_db.txt").bufferedReader().useLines { lines ->
            for (line in lines) {
                if (line.isBlank() || line.startsWith("#")) continue
                val fields = line.split(",")
                if (fields.size < 2) continue

                val guid = fields[0].trim().lowercase()
                val name = fields[1].trim()
                if (guid.length != 32 || name.isEmpty()) continue

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

                entries += DbEntry(guid, name, buttons)
            }
        }

        return entries.also { cachedEntries = it }
    }

    private fun le16Bytes(value: Int): Pair<Byte, Byte> {
        val v = value and 0xFFFF
        return Pair((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    // SDL's older Android backend: InputDevice.getDescriptor() (a stable
    // per-model identifier Android itself generates) used verbatim as the
    // raw GUID bytes.
    private fun legacyGuid(descriptor: String): String {
        val bytes = ByteArray(16)
        val raw = descriptor.toByteArray(Charsets.US_ASCII)
        raw.copyInto(bytes, 0, 0, minOf(raw.size, 16))
        return bytes.toHex()
    }

    // Current SDL Android backend: bus + vendor/product ids + button/axis
    // capability masks. Only meaningful when the device reports a real
    // USB vendor id (Bluetooth-paired gamepads reporting 0 fall through to
    // the legacy/name matches instead).
    private fun modernGuid(vendorId: Int, productId: Int, buttonMask: Int, axisMask: Int): String? {
        if (vendorId == 0) return null
        val bytes = ByteArray(16)
        val (busLo, busHi) = le16Bytes(BUS_BLUETOOTH)
        val (vLo, vHi) = le16Bytes(vendorId)
        val (pLo, pHi) = le16Bytes(productId)
        val (bLo, bHi) = le16Bytes(buttonMask)
        val (aLo, aHi) = le16Bytes(axisMask)
        bytes[0] = busLo; bytes[1] = busHi
        // bytes[2], bytes[3] stay 0 (crc, unused by this DB's generator)
        bytes[4] = vLo; bytes[5] = vHi
        bytes[8] = pLo; bytes[9] = pHi
        bytes[12] = bLo; bytes[13] = bHi
        bytes[14] = aLo; bytes[15] = aHi
        return bytes.toHex()
    }

    private fun computeButtonMask(device: InputDevice): Int {
        val keys = buttonMaskBits.map { it.first }.toIntArray()
        val present = device.hasKeys(*keys)
        var mask = 0
        buttonMaskBits.forEachIndexed { index, (_, bit) ->
            if (present[index]) mask = mask or (1 shl bit)
        }
        return mask
    }

    private fun computeAxisMask(device: InputDevice): Int {
        val ranges = device.motionRanges
        var mask = 0
        if (ranges.size >= 2) mask = mask or 0x0003
        if (ranges.size >= 4) mask = mask or 0x000c
        if (ranges.size >= 6) mask = mask or 0x0030
        val haveZ = ranges.any { it.axis == MotionEvent.AXIS_Z }
        val havePastZBeforeRz = ranges.any { it.axis > MotionEvent.AXIS_Z && it.axis < MotionEvent.AXIS_RZ }
        if (haveZ && havePastZBeforeRz) mask = mask or 0x8000
        return mask
    }

    private fun findByGuid(entries: List<DbEntry>, device: InputDevice): DbEntry? {
        val descriptor = device.descriptor
        if (!descriptor.isNullOrEmpty()) {
            val legacy = legacyGuid(descriptor)
            entries.find { it.guid == legacy }?.let { return it }
        }

        val modern = modernGuid(
            device.vendorId, device.productId,
            computeButtonMask(device), computeAxisMask(device),
        )
        if (modern != null) {
            entries.find { it.guid == modern }?.let { return it }
        }

        return null
    }

    private fun findByName(entries: List<DbEntry>, deviceName: String): DbEntry? {
        entries.find { it.name.equals(deviceName, ignoreCase = true) }?.let { return it }

        if (deviceName.length < MIN_SUBSTRING_NAME_LENGTH) return null
        return entries.find { entry ->
            entry.name.length >= MIN_SUBSTRING_NAME_LENGTH &&
                (deviceName.contains(entry.name, ignoreCase = true) ||
                    entry.name.contains(deviceName, ignoreCase = true))
        }
    }

    // Applies a known-good mapping for `device` to `playerSlot` if a DB entry
    // matches it (by GUID first — the same lookup SDL itself performs —
    // falling back to a conservative name match), otherwise resets that slot
    // to the app's regular defaults (which already work for most
    // standard-compliant controllers via Android's own HID key
    // normalization). Always leaves the slot in a usable state. Returns the
    // matched controller name, or null if no DB entry matched (defaults
    // were applied instead).
    fun applyAutomaticMapping(context: Context, playerSlot: Int, device: InputDevice): String? {
        val entries = loadEntries(context)
        val entry = findByGuid(entries, device) ?: device.name?.let { findByName(entries, it) }

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
