package net.rpcsx

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.hardware.input.InputManager
import android.os.Bundle
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup.MarginLayoutParams
import android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isInvisible
import androidx.core.view.updateLayoutParams
import net.rpcsx.databinding.ActivityRpcs3Binding
import net.rpcsx.dialogs.AlertDialogQueue
import net.rpcsx.overlay.State
import net.rpcsx.utils.InputBindingPrefs
import kotlin.concurrent.thread
import kotlin.math.abs

class RPCSXActivity : Activity() {
    private lateinit var binding: ActivityRpcs3Binding
    private lateinit var unregisterUsbEventListener: () -> Unit
    private lateinit var unregisterGamepadEventListener: () -> Unit

    // One pad state per connected physical controller, keyed by device ID.
    // Slot assignment itself (device ID -> player index) lives in
    // GamepadRepository, shared with the idle Controllers settings screen.
    // The on-screen overlay owns slot 0 directly (see PadOverlay.kt), so the
    // first detected controller also targets slot 0 to preserve today's
    // single-controller behavior; additional controllers take the rest.
    private val gamepadStates = mutableMapOf<Int, State>()
    private val usesAxisL2 = mutableMapOf<Int, Boolean>()
    private val usesAxisR2 = mutableMapOf<Int, Boolean>()

    private var overlayAutoHidden = false
    private var bootThread: Thread? = null
    private val inputBindings by lazy { InputBindingPrefs.loadBindings() }
    private val maxVirtualPads by lazy {
        RPCSX.instance.getMaxVirtualPads().coerceIn(1, MaxGamepadPlayers)
    }

    private val inputDeviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) {
            onGamepadConnectionChanged()
        }

        override fun onInputDeviceRemoved(deviceId: Int) {
            val slot = GamepadRepository.slotFor(deviceId)
            gamepadStates.remove(deviceId)
            usesAxisL2.remove(deviceId)
            usesAxisR2.remove(deviceId)
            // Release any buttons/sticks this controller left pressed.
            if (slot != null) {
                sendPadData(slot, State())
            }
            onGamepadConnectionChanged()
        }

        override fun onInputDeviceChanged(deviceId: Int) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRpcs3Binding.inflate(layoutInflater)
        setContentView(binding.root)

        unregisterUsbEventListener = listenUsbEvents(this)
        enableFullScreenImmersive()
        registerGamepadListener()
        updateOrientationForWindowMode()

        binding.oscToggle.setOnClickListener {
            binding.padOverlay.isInvisible = !binding.padOverlay.isInvisible
            overlayAutoHidden = false
            binding.oscToggle.setImageResource(if (binding.padOverlay.isInvisible) R.drawable.ic_osc_off else R.drawable.ic_show_osc)
        }

        applyOverlayAutoVisibility(connectedGamepadCount() > 0)

        val gamePath = intent.getStringExtra("path")!!
        RPCSX.lastPlayedGame = gamePath

        bootThread = thread {
            if (RPCSX.getState() != EmulatorState.Stopped) {
                val state = RPCSX.getState()
                Log.w("RPCSX State", state.name)

                if (state == EmulatorState.Paused && RPCSX.activeGame.value == gamePath) {
                    RPCSX.instance.resume()
                    return@thread
                }

                if (RPCSX.getState() != EmulatorState.Stopping && RPCSX.getState() != EmulatorState.Stopped) {
                    RPCSX.instance.kill()

                    while (RPCSX.getState() != EmulatorState.Stopped) {
                        Thread.sleep(300)
                        if (Thread.interrupted()) {
                            return@thread
                        }
                    }
                }
            }

            Log.w("RPCSX State", RPCSX.getState().name)
            RPCSX.activeGame.value = gamePath

            val bootResult = RPCSX.boot(gamePath)
            if (bootResult != BootResult.NoErrors) {
                AlertDialogQueue.showDialog(
                    getString(R.string.failed_to_boot),
                    getString(R.string.error_with_msg, bootResult.name)
                )
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterGamepadListener()
        RPCSX.state.value = EmulatorState.Paused
        unregisterUsbEventListener()
        bootThread?.interrupt()
        bootThread?.join()
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        updateOrientationForWindowMode()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateOrientationForWindowMode()
    }

    // Samsung DeX and Android's desktop windowing mode both host the activity
    // in a resizable, freeform window; forcing landscape there fights the
    // window manager, so only lock orientation during normal fullscreen play.
    private fun updateOrientationForWindowMode() {
        requestedOrientation = if (isInMultiWindowMode || isDesktopModeActive()) {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    private fun isDesktopModeActive(): Boolean {
        val uiModeType = resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
        return uiModeType == Configuration.UI_MODE_TYPE_DESK
    }

    private fun registerGamepadListener() {
        unregisterGamepadEventListener = listenGamepadEvents(this)
        getSystemService(InputManager::class.java)?.registerInputDeviceListener(inputDeviceListener, null)
    }

    private fun unregisterGamepadListener() {
        getSystemService(InputManager::class.java)?.unregisterInputDeviceListener(inputDeviceListener)
        unregisterGamepadEventListener()
    }

    private fun connectedGamepadCount() = GamepadRepository.slots.size

    private fun onGamepadConnectionChanged() {
        applyOverlayAutoVisibility(connectedGamepadCount() > 0)
    }

    // Skyline-style behavior: hide the touch overlay the moment a real
    // controller shows up, and bring it back once none are left. The manual
    // toggle button always takes priority in between these transitions.
    private fun applyOverlayAutoVisibility(hasGamepad: Boolean) {
        if (hasGamepad && !binding.padOverlay.isInvisible) {
            binding.padOverlay.isInvisible = true
            binding.oscToggle.setImageResource(R.drawable.ic_osc_off)
            overlayAutoHidden = true
        } else if (!hasGamepad && overlayAutoHidden) {
            binding.padOverlay.isInvisible = false
            binding.oscToggle.setImageResource(R.drawable.ic_show_osc)
            overlayAutoHidden = false
        }
    }

    private fun assignGamepadSlot(deviceId: Int): Int? {
        val slot = GamepadRepository.attach(deviceId, InputDevice.getDevice(deviceId)?.name ?: "Gamepad")
            ?: return null
        if (deviceId !in gamepadStates) {
            gamepadStates[deviceId] = State()
        }
        return slot
    }

    private fun keyCodeToPadBit(keyCode: Int, deviceId: Int): Pair<Int, Int> {
        val event = inputBindings[keyCode] ?: Pair(0, 0)

        if (keyCode == KeyEvent.KEYCODE_BUTTON_R2) {
            if (usesAxisR2[deviceId] == true) return Pair(0, 0) else return event
        }

        if (keyCode == KeyEvent.KEYCODE_BUTTON_L2) {
            if (usesAxisL2[deviceId] == true) return Pair(0, 0) else return event
        }

        return event
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (event == null || (event.source and (InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK or InputDevice.SOURCE_DPAD)) == 0 || event.repeatCount != 0) {
            return super.onKeyDown(keyCode, event)
        }

        val slot = assignGamepadSlot(event.deviceId) ?: return super.onKeyDown(keyCode, event)
        val padBit = keyCodeToPadBit(keyCode, event.deviceId)
        if (padBit.first == 0) {
            return super.onKeyDown(keyCode, event)
        }

        val state = gamepadStates.getOrPut(event.deviceId) { State() }
        state.digital[padBit.second] = state.digital[padBit.second] or padBit.first
        sendPadData(slot, state)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (event == null || event.source and (InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK or InputDevice.SOURCE_DPAD) == 0) {
            return super.onKeyUp(keyCode, event)
        }

        val slot = GamepadRepository.slotFor(event.deviceId) ?: return super.onKeyUp(keyCode, event)
        val padBit = keyCodeToPadBit(keyCode, event.deviceId)
        if (padBit.first == 0) {
            return super.onKeyUp(keyCode, event)
        }

        val state = gamepadStates.getOrPut(event.deviceId) { State() }
        state.digital[padBit.second] =
            state.digital[padBit.second] and padBit.first.inv()
        sendPadData(slot, state)
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent?): Boolean {
        if (event == null || event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK || event.action != MotionEvent.ACTION_MOVE) {
            return super.onGenericMotionEvent(event)
        }

        val slot = assignGamepadSlot(event.deviceId) ?: return super.onGenericMotionEvent(event)
        val deviceId = event.deviceId
        val state = gamepadStates.getOrPut(deviceId) { State() }

        if (event.getAxisValue(MotionEvent.AXIS_LTRIGGER) > 0.1) {
            state.digital[1] = state.digital[1] or Digital2Flags.CELL_PAD_CTRL_L2.bit
            usesAxisL2[deviceId] = true
        } else if (usesAxisL2[deviceId] == true) {
            usesAxisL2[deviceId] = false
            state.digital[1] = state.digital[1] and Digital2Flags.CELL_PAD_CTRL_L2.bit.inv()
        }

        if (event.getAxisValue(MotionEvent.AXIS_RTRIGGER) > 0.1) {
            state.digital[1] = state.digital[1] or Digital2Flags.CELL_PAD_CTRL_R2.bit
            usesAxisR2[deviceId] = true
        } else if (usesAxisR2[deviceId] == true) {
            usesAxisR2[deviceId] = false
            state.digital[1] = state.digital[1] and Digital2Flags.CELL_PAD_CTRL_R2.bit.inv()
        }

        val dpadX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val dpadY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)

        state.digital[0] =
            state.digital[0] and (Digital1Flags.CELL_PAD_CTRL_LEFT.bit or Digital1Flags.CELL_PAD_CTRL_RIGHT.bit or Digital1Flags.CELL_PAD_CTRL_UP.bit or Digital1Flags.CELL_PAD_CTRL_DOWN.bit).inv()
        if (abs(dpadX) > 0.1f) {
            if (dpadX < 0) {
                state.digital[0] =
                    state.digital[0] or Digital1Flags.CELL_PAD_CTRL_LEFT.bit
            } else {
                state.digital[0] =
                    state.digital[0] or Digital1Flags.CELL_PAD_CTRL_RIGHT.bit
            }
        }

        if (abs(dpadY) > 0.1f) {
            if (dpadY < 0) {
                state.digital[0] =
                    state.digital[0] or Digital1Flags.CELL_PAD_CTRL_UP.bit
            } else {
                state.digital[0] =
                    state.digital[0] or Digital1Flags.CELL_PAD_CTRL_DOWN.bit
            }
        }

        state.leftStickX = (event.getAxisValue(MotionEvent.AXIS_X) * 127 + 128).toInt()
        state.leftStickY = (event.getAxisValue(MotionEvent.AXIS_Y) * 127 + 128).toInt()
        state.rightStickX = (event.getAxisValue(MotionEvent.AXIS_Z) * 127 + 128).toInt()
        state.rightStickY = (event.getAxisValue(MotionEvent.AXIS_RZ) * 127 + 128).toInt()

        sendPadData(slot, state)
        return true
    }

    private fun sendPadData(slot: Int, state: State) {
        if (slot == 0 || maxVirtualPads <= 1) {
            RPCSX.instance.overlayPadData(
                state.digital[0],
                state.digital[1],
                state.leftStickX,
                state.leftStickY,
                state.rightStickX,
                state.rightStickY
            )
        } else {
            RPCSX.instance.multiPadData(
                slot,
                state.digital[0],
                state.digital[1],
                state.leftStickX,
                state.leftStickY,
                state.rightStickX,
                state.rightStickY
            )
        }
    }

    private fun enableFullScreenImmersive() {
        with(window) {
            WindowCompat.setDecorFitsSystemWindows(this, false)
            val insetsController = WindowInsetsControllerCompat(this, decorView)
            insetsController.apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
            attributes.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        applyInsetsToPadOverlay()
    }

    private fun applyInsetsToPadOverlay() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.padOverlay) { view, windowInsets ->
            // I don't think we need `displayCutout` insets here as well
            // Since there is hardly any overlay overlapping with it
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updateLayoutParams<MarginLayoutParams> {
                leftMargin = insets.left
                rightMargin = insets.right
                topMargin = insets.top
                bottomMargin = insets.bottom
            }
            WindowInsetsCompat.CONSUMED
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enableFullScreenImmersive()
    }
}
