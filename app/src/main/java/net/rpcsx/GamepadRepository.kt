package net.rpcsx

import android.content.Context
import android.hardware.input.InputManager
import android.view.InputDevice
import androidx.compose.runtime.mutableStateMapOf

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

        fun attach(deviceId: Int, deviceName: String): Int? {
            slotFor(deviceId)?.let { return it }
            val usedSlots = slots.keys
            val freeSlot = (0 until MaxGamepadPlayers).firstOrNull { it !in usedSlots }
                ?: return null
            slots[freeSlot] = GamepadSlot(deviceId, deviceName)
            return freeSlot
        }

        fun detach(deviceId: Int) {
            slotFor(deviceId)?.let { slots.remove(it) }
        }
    }
}
