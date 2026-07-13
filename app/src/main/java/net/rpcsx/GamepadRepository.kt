package net.rpcsx

import android.content.Context
import android.hardware.input.InputManager
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

data class GamepadSlot(val deviceId: Int, val deviceName: String)

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

        fun attach(deviceId: Int, deviceName: String): Int? {
            slotFor(deviceId)?.let { return it }
            val usedSlots = slots.keys
            val preferred = preferredSlot(deviceId)?.takeIf { it !in usedSlots }
            val slot = preferred
                ?: (0 until MaxGamepadPlayers).firstOrNull { it !in usedSlots }
                ?: return null
            slots[slot] = GamepadSlot(deviceId, deviceName)
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
        }

        fun detach(deviceId: Int) {
            slotFor(deviceId)?.let { slots.remove(it) }
        }
    }
}
