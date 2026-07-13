package net.rpcsx

import android.content.Context
import android.hardware.input.InputManager
import android.os.Build
import android.os.VibrationEffect
import android.view.InputDevice
import androidx.compose.runtime.mutableStateMapOf
import net.rpcsx.utils.GeneralSettings

const val MaxGamepadPlayers = 4

fun isGamepadDevice(device: InputDevice?): Boolean {
    if (device == null || device.isVirtual) return false
    val sources = device.sources
    return (sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
        (sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
}

// Tracks physical gamepad hot-plug system-wide (not tied to any single
// Activity), so both the in-game input routing (RPCSXActivity) and the
// idle Controllers settings screen see the same live device/slot state.
fun listenGamepadEvents(context: Context): () -> Unit {
    val inputManager = context.getSystemService(InputManager::class.java)

    val listener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) {
            InputDevice.getDevice(deviceId)?.let {
                if (isGamepadDevice(it)) GamepadRepository.attach(deviceId, it.name)
            }
        }

        override fun onInputDeviceRemoved(deviceId: Int) {
            GamepadRepository.detach(deviceId)
        }

        override fun onInputDeviceChanged(deviceId: Int) {}
    }

    inputManager?.registerInputDeviceListener(listener, null)

    for (deviceId in InputDevice.getDeviceIds()) {
        InputDevice.getDevice(deviceId)?.let {
            if (isGamepadDevice(it)) GamepadRepository.attach(deviceId, it.name)
        }
    }

    return {
        inputManager?.unregisterInputDeviceListener(listener)
    }
}

data class GamepadSlot(
    val deviceId: Int,
    val deviceName: String,
    val vendorId: Int = 0,
    val productId: Int = 0,
    val descriptor: String = ""
) {
    val displayTitle: String
        get() = if (vendorId != 0 || productId != 0) {
            "$deviceName [VID:${vendorId.toString(16).padStart(4, '0').uppercase()} PID:${productId.toString(16).padStart(4, '0').uppercase()}]"
        } else {
            deviceName
        }
}

class GamepadRepository {
    companion object {
        val slots = mutableStateMapOf<Int, GamepadSlot>()

        fun slotFor(deviceId: Int): Int? =
            slots.entries.find { it.value.deviceId == deviceId }?.key

        // Port preference persisted per physical device. deviceId changes
        // across reconnects, but InputDevice.descriptor is stable, so a
        // controller reclaims the port the user assigned to it.
        private fun portPrefKey(deviceId: Int): String? =
            InputDevice.getDevice(deviceId)?.descriptor?.let { "gamepad_port_$it" }

        private fun preferredSlot(deviceId: Int): Int? =
            portPrefKey(deviceId)?.let { GeneralSettings[it] as? Int }
                ?.takeIf { it in 0 until MaxGamepadPlayers }

        private fun savePreferredSlot(deviceId: Int, slot: Int) {
            portPrefKey(deviceId)?.let { GeneralSettings[it] = slot }
        }

        private val vibratorCache = java.util.concurrent.ConcurrentHashMap<Int, android.os.Vibrator>()
        private val vibratorManagerCache = java.util.concurrent.ConcurrentHashMap<Int, android.os.VibratorManager>()
        // Last strength sent to each device, packed (large shl 8) or small, so
        // backend rumble is edge-triggered: the continuous effect is only
        // replaced when the game actually changes the motor strength.
        private val lastRumble = java.util.concurrent.ConcurrentHashMap<Int, Long>()
        private val vibrationExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

        // A single long one-shot held continuously and replaced only on change:
        // sustained rumble stays perfectly steady (no per-tick re-trigger
        // stutter) yet any change lands within one poll tick.
        private const val RUMBLE_HOLD_MS = 60_000L

        // ConcurrentHashMap can't hold null, so cache only real vibrators and
        // probe misses each call (cheap; InputDevice.getDevice is cached by the
        // system). Avoids the getOrPut(null) NPE the old code could hit.
        private fun getCachedVibrator(deviceId: Int): android.os.Vibrator? {
            vibratorCache[deviceId]?.let { return it }
            val device = InputDevice.getDevice(deviceId) ?: return null
            val v = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    device.vibratorManager.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    device.vibrator
                }
            } catch (_: Exception) {
                null
            } ?: return null
            if (!v.hasVibrator()) return null
            vibratorCache[deviceId] = v
            return v
        }

        private fun getCachedVibratorManager(deviceId: Int): android.os.VibratorManager? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
            vibratorManagerCache[deviceId]?.let { return it }
            val device = InputDevice.getDevice(deviceId) ?: return null
            val vm = try {
                device.vibratorManager
            } catch (_: Exception) {
                null
            } ?: return null
            vibratorManagerCache[deviceId] = vm
            return vm
        }

        fun vibrateDevice(deviceId: Int, durationMs: Long = 300L) {
            vibrateDeviceFast(deviceId, durationMs)
        }

        fun vibrateDeviceFast(deviceId: Int, durationMs: Long = 80L, amplitude: Int = 255) {
            vibrationExecutor.execute {
                try {
                    val vibrator = getCachedVibrator(deviceId)
                    if (vibrator != null && vibrator.hasVibrator()) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            val eff = VibrationEffect.createOneShot(
                                durationMs.coerceAtLeast(10L),
                                amplitude.coerceIn(1, 255)
                            )
                            vibrator.vibrate(eff)
                        } else {
                            @Suppress("DEPRECATION")
                            vibrator.vibrate(durationMs)
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        fun identifySlot(slot: Int) {
            val gamepadSlot = slots[slot] ?: return
            vibrateDeviceFast(gamepadSlot.deviceId, 400L)
        }

        fun deviceIdForSlot(slot: Int): Int? = slots[slot]?.deviceId

        // Backend rumble driver, called every poll tick with the large/small
        // motor strengths (0-255) the game requested. Edge-triggered: does
        // nothing while the strength is unchanged, so the continuous effect
        // holds steady. When the controller exposes two actuators the motors
        // are mapped separately (DualShock-like feel); otherwise they are
        // combined onto the single vibrator. Runs on the caller's (poller)
        // thread to keep latency to one tick.
        fun applyRumble(deviceId: Int, large: Int, small: Int) {
            val l = large.coerceIn(0, 255)
            val s = small.coerceIn(0, 255)
            val packed = (l.toLong() shl 8) or s.toLong()
            if (lastRumble[deviceId] == packed) return
            lastRumble[deviceId] = packed

            try {
                if (l == 0 && s == 0) {
                    stopVibrator(deviceId)
                    return
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val vm = getCachedVibratorManager(deviceId)
                    val ids = vm?.vibratorIds
                    if (vm != null && ids != null && ids.size >= 2) {
                        val combined = android.os.CombinedVibration.startParallel()
                        if (l > 0) combined.addVibrator(
                            ids[0], VibrationEffect.createOneShot(RUMBLE_HOLD_MS, l)
                        )
                        if (s > 0) combined.addVibrator(
                            ids[1], VibrationEffect.createOneShot(RUMBLE_HOLD_MS, s)
                        )
                        vm.vibrate(combined.combine())
                        return
                    }
                }

                val vibrator = getCachedVibrator(deviceId) ?: return
                val amplitude = maxOf(l, s).coerceIn(1, 255)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(RUMBLE_HOLD_MS, amplitude))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(RUMBLE_HOLD_MS)
                }
            } catch (_: Exception) {}
        }

        // Stop hardware only; keeps the edge-trigger cache so a sustained zero
        // stays a no-op.
        private fun stopVibrator(deviceId: Int) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    getCachedVibratorManager(deviceId)?.cancel()
                }
                getCachedVibrator(deviceId)?.cancel()
            } catch (_: Exception) {}
        }

        // Full stop + forget device (used on detach / poller shutdown).
        fun cancelDeviceRumble(deviceId: Int) {
            lastRumble.remove(deviceId)
            stopVibrator(deviceId)
        }

        fun attach(deviceId: Int, deviceName: String): Int? {
            slotFor(deviceId)?.let { return it }
            val device = InputDevice.getDevice(deviceId)
            val vendorId = device?.vendorId ?: 0
            val productId = device?.productId ?: 0
            val descriptor = device?.descriptor ?: ""
            val usedSlots = slots.keys
            val preferred = preferredSlot(deviceId)?.takeIf { it !in usedSlots }
            val slot = preferred
                ?: (0 until MaxGamepadPlayers).firstOrNull { it !in usedSlots }
                ?: return null
            slots[slot] = GamepadSlot(deviceId, deviceName, vendorId, productId, descriptor)
            // Trigger brief identification vibration pulse when connected
            vibrateDevice(deviceId, 250L)
            return slot
        }

        // Moves a device to the chosen port; if another controller already
        // occupies it, the two swap. Both preferences are persisted so the
        // arrangement survives reconnects.
        fun reassign(deviceId: Int, newSlot: Int) {
            if (newSlot !in 0 until MaxGamepadPlayers) return
            val currentSlot = slotFor(deviceId) ?: return
            if (currentSlot == newSlot) return

            val moving = slots[currentSlot] ?: return
            val occupant = slots[newSlot]

            slots[newSlot] = moving
            if (occupant != null) {
                slots[currentSlot] = occupant
                savePreferredSlot(occupant.deviceId, currentSlot)
            } else {
                slots.remove(currentSlot)
            }
            savePreferredSlot(deviceId, newSlot)
            identifySlot(newSlot)
        }

        fun detach(deviceId: Int) {
            slotFor(deviceId)?.let { slots.remove(it) }
            cancelDeviceRumble(deviceId)
            vibratorCache.remove(deviceId)
            vibratorManagerCache.remove(deviceId)
        }
    }
}
