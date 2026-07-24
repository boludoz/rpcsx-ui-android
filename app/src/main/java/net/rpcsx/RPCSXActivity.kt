package net.rpcsx

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.hardware.input.InputManager
import android.os.Build
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
import net.rpcsx.utils.GeneralSettings
import net.rpcsx.utils.InputBindingPrefs
import android.net.Uri
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.abs

class RPCSXActivity : Activity() {
    private lateinit var binding: ActivityRpcs3Binding
    private lateinit var unregisterUsbEventListener: () -> Unit

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
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val hideOverlayRunnable = Runnable {
        binding.padOverlay.animate()
            .alpha(0f)
            .setDuration(300)
            .withEndAction {
                binding.padOverlay.isInvisible = true
            }
            .start()
    }

    private fun showOverlayAndResetTimer() {
        val autoHideSecs = (GeneralSettings["touchpad_auto_hide_seconds"] as? Int) ?: 5

        if (binding.padOverlay.isInvisible || binding.padOverlay.alpha < 1f) {
            binding.padOverlay.isInvisible = false
            binding.padOverlay.animate()
                .alpha(1f)
                .setDuration(150)
                .start()
        }

        handler.removeCallbacks(hideOverlayRunnable)

        if (autoHideSecs > 0) {
            handler.postDelayed(hideOverlayRunnable, autoHideSecs * 1000L)
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        showOverlayAndResetTimer()
        return super.dispatchTouchEvent(ev)
    }

    override fun onResume() {
        super.onResume()
        inputBindingsBySlot.clear()
        showOverlayAndResetTimer()
        startRumblePoller()
        for (slot in 0 until MaxGamepadPlayers) {
            GamepadRepository.updateLightbarForSlot(slot)
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(hideOverlayRunnable)
        stopRumblePoller()
    }

    // Phone vibrator, used for backend rumble on the touch-overlay port when no
    // physical controller is assigned to it.
    private val phoneVibrator by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(android.os.Vibrator::class.java)
        }
    }

    @Volatile
    private var rumbleRunning = false
    private var rumbleThread: Thread? = null
    // Edge-trigger state for the phone vibrator (overlay port), packed the same
    // way as the controller path so a steady strength holds without re-trigger.
    private var lastPhoneRumble = -1L

    // Long continuous one-shot, replaced only when the strength changes.
    private val phoneRumbleHoldMs = 60_000L

    private fun applyPhoneRumble(large: Int, small: Int) {
        val l = large.coerceIn(0, 255)
        val s = small.coerceIn(0, 255)
        val packed = (l.toLong() shl 8) or s.toLong()
        if (lastPhoneRumble == packed) return
        lastPhoneRumble = packed
        try {
            val v = phoneVibrator ?: return
            if (!v.hasVibrator()) return
            val amplitude = maxOf(l, s)
            if (amplitude <= 0) {
                v.cancel()
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(
                    android.os.VibrationEffect.createOneShot(
                        phoneRumbleHoldMs,
                        amplitude.coerceIn(1, 255)
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(phoneRumbleHoldMs)
            }
        } catch (_: Exception) {}
    }

    private fun cancelPhoneVibration() {
        lastPhoneRumble = -1L
        try { phoneVibrator?.cancel() } catch (_: Exception) {}
    }

    // Backend rumble: the core writes per-pad motor strength (cellPadSetActDirect);
    // poll it once per frame and drive the mapped physical controller (or the
    // phone for the touch overlay). Event-driven input can't carry this, and
    // Android exposes no rumble callback, so a tight poll is the low-latency
    // path most emulators use. applyRumble/applyPhoneRumble are edge-triggered,
    // so a per-frame poll is cheap and the latency floor is one frame (~16ms).
    private fun startRumblePoller() {
        if (rumbleThread != null) return
        if (GeneralSettings["pad_vibration"] == false) return

        rumbleRunning = true
        rumbleThread = thread(isDaemon = true, name = "rumble-poller") {
            val pollMs = 16L // one frame @60fps
            val activeDevices = HashSet<Int>()
            var overlayTouched = false

            while (rumbleRunning) {
                try {
                    for (slot in 0 until maxVirtualPads) {
                        val vib = RPCSX.instance.getPadVibration(slot)
                        val large = (vib shr 8) and 0xff
                        val small = vib and 0xff

                        val deviceId = GamepadRepository.deviceIdForSlot(slot)
                        if (deviceId != null) {
                            GamepadRepository.applyRumble(deviceId, large, small)
                            activeDevices.add(deviceId)
                        } else if (slot == overlaySlot) {
                            // Touch overlay with no physical pad -> phone.
                            applyPhoneRumble(large, small)
                            overlayTouched = true
                        }
                    }
                } catch (_: Exception) {}

                try {
                    Thread.sleep(pollMs)
                } catch (_: InterruptedException) {
                    break
                }
            }

            activeDevices.forEach { GamepadRepository.cancelDeviceRumble(it) }
            if (overlayTouched) cancelPhoneVibration()
        }
    }

    private fun stopRumblePoller() {
        rumbleRunning = false
        rumbleThread?.interrupt()
        rumbleThread = null
    }

    private var bootThread: Thread? = null
    // Each player slot has its own independent button mapping; cache lazily
    // since it's read on every key event but only changes via Settings.
    // Per-game mappings (saved from the game's settings screen) take
    // precedence over the global ones when present.
    private val inputBindingsBySlot = mutableMapOf<Int, Map<Int, Pair<Int, Int>>>()
    private val activeTitleId by lazy {
        val path = intent.getStringExtra("path") ?: ""
        GameRepository.find(path)?.info?.titleId?.value?.takeIf { it.isNotEmpty() } ?: run {
            var id = path.trimEnd('/').substringAfterLast('/')
            if (id.endsWith(".iso", ignoreCase = true)) {
                id = id.dropLast(4)
            }
            id.uppercase()
        }
    }
    private fun bindingsForSlot(slot: Int) =
        inputBindingsBySlot.getOrPut(slot) { InputBindingPrefs.loadBindings(slot, activeTitleId) }
    private val maxVirtualPads by lazy {
        RPCSX.instance.getMaxVirtualPads().coerceIn(1, MaxGamepadPlayers)
    }

    // Player port the on-screen touch overlay drives (default 0). The overlay
    // always coexists with whatever physical controller is assigned to the
    // same port — their inputs are merged in mergedOverlayState — so e.g. the
    // touchpad can act as player 2 alongside a controller on player 1.
    private val overlaySlot by lazy {
        ((GeneralSettings["overlay_forced_slot"] as? Int) ?: 0).coerceIn(0, maxVirtualPads - 1)
    }

    private val inputDeviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) {
            InputDevice.getDevice(deviceId)?.let {
                if (isGamepadDevice(it)) GamepadRepository.attach(deviceId, it.name)
            }
            onGamepadConnectionChanged()
        }

        override fun onInputDeviceRemoved(deviceId: Int) {
            // Capture the slot BEFORE detaching so we can zero out pad state.
            val slot = GamepadRepository.slotFor(deviceId)
            GamepadRepository.detach(deviceId)
            gamepadStates.remove(deviceId)
            usesAxisL2.remove(deviceId)
            usesAxisR2.remove(deviceId)
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


        // The overlay and a physical slot-0 controller both target the same
        // native pad, so route the overlay's touches through sendPadData
        // too instead of it calling into native directly — otherwise
        // whichever side last sent a full State() would silently drop the
        // other's currently-held buttons.
        binding.padOverlay.onPadStateChanged = {
            sendPadData(overlaySlot, binding.padOverlay.currentState)
        }

        applyOverlayAutoVisibility(connectedGamepadCount() > 0 && overlaySlot == 0)

        val gamePath = intent.getStringExtra("path")!!
        RPCSX.lastPlayedGame = gamePath

        bootThread = thread {
            if (RPCSX.getState() != EmulatorState.Stopped) {
                val state = RPCSX.getState()
                Log.w("RPCSX State", state.name)

                if ((state == EmulatorState.Paused || state == EmulatorState.Running)
                    && RPCSX.activeGame.value == gamePath) {
                    if (state == EmulatorState.Paused) {
                        RPCSX.instance.resume()
                    }
                    return@thread
                }

                if (RPCSX.getState() != EmulatorState.Stopping && RPCSX.getState() != EmulatorState.Stopped) {
                    RPCSX.instance.kill()
                }

                while (RPCSX.getState() != EmulatorState.Stopped) {
                    Thread.sleep(300)
                    if (Thread.interrupted()) {
                        return@thread
                    }
                }
            }

            Log.w("RPCSX State", RPCSX.getState().name)
            RPCSX.activeGame.value = gamePath

            val game = GameRepository.find(gamePath)
            val sourceUri = game?.info?.sourceUri?.value
            val contentUri =
                if (sourceUri != null && Uri.parse(sourceUri).scheme == "content") {
                    Uri.parse(sourceUri)
                } else {
                    null
                }
            var sourceGone = false

            // Matches rpcs3::utils::get_custom_config_path(serial) on the
            // native side exactly (config/custom_configs/config_<serial>.yml)
            // - PerGameConfigRepository writes there directly now, keyed by
            // title id instead of a uuid, so this must resolve to the same
            // path the core itself would for cfg_mode::custom.
            val configPathFile = File(RPCSX.rootDirectory, "config/custom_configs/config_${activeTitleId}.yml")
            val configPath = if (configPathFile.exists()) configPathFile.absolutePath else ""

            // SAF-backed ISO (any DocumentsProvider, Downloads included):
            // boot from a detached fd - no filesystem path is ever needed,
            // the persisted URI permission keeps this working across
            // reboots. Otherwise boot by path as usual. A fresh fd is opened
            // per attempt because the core consumes it.
            val attemptBoot: () -> BootResult = if (contentUri != null) {
                {
                    val fd = try {
                        contentResolver.openFileDescriptor(contentUri, "r")?.detachFd() ?: -1
                    } catch (e: Exception) {
                        e.printStackTrace()
                        -1
                    }
                    if (fd < 0) {
                        sourceGone = true
                        BootResult.InvalidFileOrFolder
                    } else {
                        BootResult.fromInt(RPCSX.instance.bootIsoFd(fd, configPath))
                    }
                }
            } else {
                val bootPath = if (!sourceUri.isNullOrEmpty()) sourceUri else gamePath
                { RPCSX.boot(bootPath, configPath) }
            }

            // The previous instance may still be tearing down when the user
            // backs out and relaunches quickly - the state poll above can see
            // Stopped while the core's Load still reports still_running.
            // Retry briefly instead of surfacing a transient error.
            var bootResult = attemptBoot()
            var retries = 0
            while (bootResult == BootResult.StillRunning && retries < 10) {
                Thread.sleep(300)
                if (Thread.interrupted()) {
                    return@thread
                }
                bootResult = attemptBoot()
                retries++
            }

            if (bootResult != BootResult.NoErrors) {
                AlertDialogQueue.showDialog(
                    getString(R.string.failed_to_boot),
                    if (sourceGone) {
                        // The URI no longer opens (file deleted or permission
                        // revoked) - tell the user to re-add instead of the
                        // opaque error name.
                        getString(R.string.iso_source_not_resolvable)
                    } else {
                        getString(R.string.error_with_msg, bootResult.name)
                    }
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
        stopRumblePoller()
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
        val inputManager = getSystemService(InputManager::class.java)
        inputManager?.registerInputDeviceListener(inputDeviceListener, null)
        // Enumerate already-connected devices so they get a slot immediately.
        for (deviceId in InputDevice.getDeviceIds()) {
            InputDevice.getDevice(deviceId)?.let {
                if (isGamepadDevice(it)) GamepadRepository.attach(deviceId, it.name)
            }
        }
        onGamepadConnectionChanged()
    }

    private fun unregisterGamepadListener() {
        getSystemService(InputManager::class.java)?.unregisterInputDeviceListener(inputDeviceListener)
    }

    private fun connectedGamepadCount() = GamepadRepository.slots.size

    private fun onGamepadConnectionChanged() {
        // Auto-hide on controller connect only applies when the overlay shares
        // port 1 with the controller. On a custom port the user deliberately
        // set the touchpad up as a separate player, so it stays visible.
        applyOverlayAutoVisibility(connectedGamepadCount() > 0 && overlaySlot == 0)
    }

    // Skyline-style behavior: hide the touch overlay the moment a real
    // controller shows up, and bring it back once none are left. The manual
    // toggle button always takes priority in between these transitions.
    private fun applyOverlayAutoVisibility(hasGamepad: Boolean) {
        if (hasGamepad && !binding.padOverlay.isInvisible) {
            binding.padOverlay.isInvisible = true
            overlayAutoHidden = true
        } else if (!hasGamepad && overlayAutoHidden) {
            binding.padOverlay.isInvisible = false
            overlayAutoHidden = false
            showOverlayAndResetTimer()
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

    // Hot path: called on every key/motion event. Once a device is assigned,
    // slotFor is a plain map lookup, so the costly InputDevice.getDevice()
    // binder call in assignGamepadSlot only runs the first time a device is
    // seen - not on every button press / stick sample during gameplay.
    private fun resolveSlot(deviceId: Int): Int? =
        GamepadRepository.slotFor(deviceId) ?: assignGamepadSlot(deviceId)

    private fun keyCodeToPadBit(keyCode: Int, deviceId: Int, slot: Int): Pair<Int, Int> {
        val event = bindingsForSlot(slot)[keyCode] ?: Pair(0, 0)

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

        val slot = resolveSlot(event.deviceId) ?: return super.onKeyDown(keyCode, event)
        val padBit = keyCodeToPadBit(keyCode, event.deviceId, slot)
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
        val padBit = keyCodeToPadBit(keyCode, event.deviceId, slot)
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

        val slot = resolveSlot(event.deviceId) ?: return super.onGenericMotionEvent(event)
        val deviceId = event.deviceId
        val state = gamepadStates.getOrPut(deviceId) { State() }

        val rawLTrigger = event.getAxisValue(MotionEvent.AXIS_LTRIGGER)
        if (rawLTrigger > 0.1) {
            state.digital[1] = state.digital[1] or Digital2Flags.CELL_PAD_CTRL_L2.bit
            usesAxisL2[deviceId] = true
        } else if (usesAxisL2[deviceId] == true) {
            usesAxisL2[deviceId] = false
            state.digital[1] = state.digital[1] and Digital2Flags.CELL_PAD_CTRL_L2.bit.inv()
        }
        state.leftTrigger = if (usesAxisL2[deviceId] == true) (rawLTrigger * 255).toInt().coerceIn(0, 255) else -1

        val rawRTrigger = event.getAxisValue(MotionEvent.AXIS_RTRIGGER)
        if (rawRTrigger > 0.1) {
            state.digital[1] = state.digital[1] or Digital2Flags.CELL_PAD_CTRL_R2.bit
            usesAxisR2[deviceId] = true
        } else if (usesAxisR2[deviceId] == true) {
            usesAxisR2[deviceId] = false
            state.digital[1] = state.digital[1] and Digital2Flags.CELL_PAD_CTRL_R2.bit.inv()
        }
        state.rightTrigger = if (usesAxisR2[deviceId] == true) (rawRTrigger * 255).toInt().coerceIn(0, 255) else -1

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
        if (slot == overlaySlot || maxVirtualPads <= 1) {
            // The overlay's port is shared with whatever physical controller
            // is assigned to it, so combine both sources here rather than
            // sending `state` alone and clobbering whichever one didn't
            // trigger this particular call.
            val merged = mergedOverlayState(if (slot != overlaySlot) state else null)
            // overlayPadData targets slot 0 and exists on every engine build;
            // multiPadData is needed for any other overlay port.
            val target = if (maxVirtualPads <= 1) 0 else overlaySlot
            if (target == 0) {
                RPCSX.instance.overlayPadData(
                    merged.digital[0],
                    merged.digital[1],
                    merged.leftStickX,
                    merged.leftStickY,
                    merged.rightStickX,
                    merged.rightStickY,
                    merged.leftTrigger,
                    merged.rightTrigger
                )
            } else {
                RPCSX.instance.multiPadData(
                    target,
                    merged.digital[0],
                    merged.digital[1],
                    merged.leftStickX,
                    merged.leftStickY,
                    merged.rightStickX,
                    merged.rightStickY,
                    merged.leftTrigger,
                    merged.rightTrigger
                )
            }
        } else {
            RPCSX.instance.multiPadData(
                slot,
                state.digital[0],
                state.digital[1],
                state.leftStickX,
                state.leftStickY,
                state.rightStickX,
                state.rightStickY,
                state.leftTrigger,
                state.rightTrigger
            )
        }
    }

    // Combines the overlay's touch state with the physical controller on the
    // overlay's port (if any), so both can be held/pressed at once instead of
    // one input source overwriting the other. Digital buttons are OR'd
    // together; sticks prefer whichever source is actually deflected from
    // center, falling back to `extra` (a controller from another slot being
    // funneled into slot 0 on engine builds that only support one virtual
    // pad) and finally to the port's physical gamepad.
    // Reused across events so the input hot path allocates nothing. Safe: all
    // key/motion events run on the main thread, and sendPadData reads the six
    // fields into the JNI call immediately without retaining the object.
    private val scratchMergedState = State()
    // Never mutated: stand-in for "no controller on the overlay port".
    private val neutralState = State()

    private fun mergedOverlayState(extra: State?): State {
        val overlayPadDeviceId = GamepadRepository.slots[overlaySlot]?.deviceId
        val gamepadState = overlayPadDeviceId?.let { gamepadStates[it] } ?: neutralState
        val overlayState = binding.padOverlay.currentState

        fun isCentered(s: State) =
            s.leftStickX == 127 && s.leftStickY == 127 && s.rightStickX == 127 && s.rightStickY == 127

        val stickSource = when {
            !isCentered(overlayState) -> overlayState
            extra != null && !isCentered(extra) -> extra
            else -> gamepadState
        }

        val out = scratchMergedState
        out.digital[0] = gamepadState.digital[0] or overlayState.digital[0] or (extra?.digital?.get(0) ?: 0)
        out.digital[1] = gamepadState.digital[1] or overlayState.digital[1] or (extra?.digital?.get(1) ?: 0)
        out.leftStickX = stickSource.leftStickX
        out.leftStickY = stickSource.leftStickY
        out.rightStickX = stickSource.rightStickX
        out.rightStickY = stickSource.rightStickY
        // Prefer whichever source actually has an analog reading (the touch
        // overlay never does); ties keep the gamepad's.
        out.leftTrigger = listOf(gamepadState, overlayState, extra).firstOrNull { it != null && it.leftTrigger >= 0 }?.leftTrigger ?: -1
        out.rightTrigger = listOf(gamepadState, overlayState, extra).firstOrNull { it != null && it.rightTrigger >= 0 }?.rightTrigger ?: -1
        return out
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
