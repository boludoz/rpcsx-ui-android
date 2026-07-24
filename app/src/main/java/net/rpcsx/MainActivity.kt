package net.rpcsx

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import net.rpcsx.dialogs.AlertDialogQueue
import net.rpcsx.ui.navigation.AppNavHost
import net.rpcsx.utils.GeneralSettings
import net.rpcsx.utils.GitHub
import net.rpcsx.utils.RpcsxUpdater
import java.io.File
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private lateinit var unregisterUsbEventListener: () -> Unit
    private lateinit var unregisterGamepadEventListener: () -> Unit
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // GeneralSettings must be ready before the gamepad listener runs:
        // GamepadRepository.attach reads persisted port preferences from it.
        GeneralSettings.init(this)
        GameDirectoryRepository.load()

        unregisterGamepadEventListener = listenGamepadEvents(this)

        if (!RPCSX.initialized) {
            Permission.PostNotifications.requestPermission(this)

            with(getSystemService(NOTIFICATION_SERVICE) as NotificationManager) {
                val channel = NotificationChannel(
                    "rpcsx-progress",
                    getString(R.string.installation_progress),
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }

                createNotificationChannel(channel)
            }

            val customRoot = GeneralSettings["custom_root_directory"] as? String
            RPCSX.rootDirectory = if (!customRoot.isNullOrEmpty() && File(customRoot).exists()) {
                customRoot
            } else {
                applicationContext.getExternalFilesDir(null).toString()
            }
            if (!RPCSX.rootDirectory.endsWith("/")) {
                RPCSX.rootDirectory += "/"
            }

            lifecycleScope.launch {
                GameRepository.load()
                thread {
                    // Drop stale registrations: directories whose SAF grant is
                    // gone and games whose ISO/source no longer exists.
                    GameDirectoryRepository.pruneInvalid(this@MainActivity)
                    GameRepository.pruneInvalid(this@MainActivity)
                }
            }

            FirmwareRepository.load()
            GitHub.initialize(this)
            RpcsxUpdater.syncDownloadedVersionsJson(this)

            var rpcsxLibrary = GeneralSettings["rpcsx_library"] as? String
            val rpcsxUpdateStatus = GeneralSettings["rpcsx_update_status"]
            val rpcsxPrevLibrary = GeneralSettings["rpcsx_prev_library"] as? String

            if (rpcsxLibrary != null) {
                if (rpcsxUpdateStatus == false && rpcsxPrevLibrary != null) {
                    GeneralSettings["rpcsx_library"] = rpcsxPrevLibrary
                    GeneralSettings["rpcsx_installed_arch"] = GeneralSettings["rpcsx_prev_installed_arch"]
                    GeneralSettings["rpcsx_prev_installed_arch"] = null
                    GeneralSettings["rpcsx_prev_library"] = null
                    GeneralSettings["rpcsx_bad_version"] = RpcsxUpdater.getFileVersion(File(rpcsxLibrary))
                    GeneralSettings.sync()

                    File(rpcsxLibrary).delete()
                    rpcsxLibrary = rpcsxPrevLibrary

                    AlertDialogQueue.showDialog(
                        getString(R.string.failed_to_update_rpcsx),
                        getString(R.string.failed_to_load_new_version)
                    )
                } else if (rpcsxUpdateStatus == null) {
                    GeneralSettings["rpcsx_update_status"] = false
                    GeneralSettings.sync()
                }

                RPCSX.openLibrary(rpcsxLibrary)
            }

            val nativeLibraryDir =
                packageManager.getApplicationInfo(packageName, 0).nativeLibraryDir
            RPCSX.nativeLibDirectory = nativeLibraryDir

            if (RPCSX.activeLibrary.value != null) {
                RPCSX.instance.initialize(RPCSX.rootDirectory, UserRepository.getUserFromSettings())
                val gpuDriverPath = GeneralSettings["gpu_driver_path"] as? String
                val gpuDriverName = GeneralSettings["gpu_driver_name"] as? String

                if (gpuDriverPath != null && gpuDriverName != null) {
                    RPCSX.instance.setCustomDriver(gpuDriverPath, gpuDriverName, nativeLibraryDir)
                }

                lifecycleScope.launch {
                    UserRepository.load()
                }

                RPCSX.initialized = true

                thread {
                    RPCSX.instance.startMainThreadProcessor()
                }

                thread {
                    RPCSX.instance.processCompilationQueue()
                }

                thread {
                    // Persisted game/ISO directories are registered in place
                    // (no copy), so re-scanning them at every launch is cheap.
                    GameDirectoryRepository.directories.forEach { dir ->
                        val uri = android.net.Uri.parse(dir.uri)
                        when (dir.kind) {
                            GameDirectoryKind.Games -> GameDirectoryRepository.scanGameDirectory(this, uri)
                            GameDirectoryKind.Iso -> GameDirectoryRepository.scanIsoDirectory(this, uri)
                        }
                    }
                }

                GeneralSettings["rpcsx_update_status"] = true
                if (rpcsxPrevLibrary != null) {
                    if (rpcsxLibrary != rpcsxPrevLibrary) {
                        File(rpcsxPrevLibrary).delete()
                    }

                    GeneralSettings["rpcsx_prev_library"] = null
                    GeneralSettings["rpcsx_prev_installed_arch"] = null
                    GeneralSettings.sync()
                }
            }

            val updateFile = File(RPCSX.rootDirectory + "cache", "rpcsx-${BuildConfig.Version}.apk")
            if (updateFile.exists()) {
                updateFile.delete()
            }
        }

        setContent {
            RPCSXTheme {
                AppNavHost()
            }
        }

        if (RPCSX.activeLibrary.value != null) {
            unregisterUsbEventListener = listenUsbEvents(this)
        } else {
            unregisterUsbEventListener = {}
        }
    }

    override fun onResume() {
        super.onResume()
        for (slot in 0 until MaxGamepadPlayers) {
            GamepadRepository.updateLightbarForSlot(slot)
        }
    }

    private var lastStickDirection = 0
    private var lastStickFireTime = 0L
    private var stickRepeatCount = 0

    private fun handleNavKeyCode(keyCode: Int): net.rpcsx.ui.navigation.NavResult {
        return when (keyCode) {
            android.view.KeyEvent.KEYCODE_DPAD_UP -> net.rpcsx.ui.navigation.FrameNavigationManager.onDpadUp()
            android.view.KeyEvent.KEYCODE_DPAD_DOWN -> net.rpcsx.ui.navigation.FrameNavigationManager.onDpadDown()
            android.view.KeyEvent.KEYCODE_DPAD_LEFT -> net.rpcsx.ui.navigation.FrameNavigationManager.onDpadLeft()
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> net.rpcsx.ui.navigation.FrameNavigationManager.onDpadRight()
            else -> net.rpcsx.ui.navigation.NavResult(false)
        }
    }

    private fun fireStickNav(keyCode: Int) {
        val res = handleNavKeyCode(keyCode)
        if (!res.consumed) {
            super.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyCode))
            super.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, keyCode))
        }
    }

    override fun onGenericMotionEvent(event: android.view.MotionEvent): Boolean {
        if (RPCSX.activeGame.value != null || RPCSX.state.value != EmulatorState.Stopped) {
            return super.onGenericMotionEvent(event)
        }

        if (isGamepadDevice(event.device) && (event.source and android.view.InputDevice.SOURCE_JOYSTICK) == android.view.InputDevice.SOURCE_JOYSTICK) {
            val slot = GamepadRepository.slotFor(event.deviceId)
            if (slot != null) {
                val leftX = (event.getAxisValue(android.view.MotionEvent.AXIS_X) * 127 + 128).toInt().coerceIn(0, 255)
                val leftY = (event.getAxisValue(android.view.MotionEvent.AXIS_Y) * 127 + 128).toInt().coerceIn(0, 255)
                val rightX = (event.getAxisValue(android.view.MotionEvent.AXIS_Z) * 127 + 128).toInt().coerceIn(0, 255)
                val rightY = (event.getAxisValue(android.view.MotionEvent.AXIS_RZ) * 127 + 128).toInt().coerceIn(0, 255)

                GamepadRepository.updateStickPosition(slot, leftX, leftY, rightX, rightY)
            }

            val rawX = event.getAxisValue(android.view.MotionEvent.AXIS_X)
            val rawY = event.getAxisValue(android.view.MotionEvent.AXIS_Y)
            val hatX = event.getAxisValue(android.view.MotionEvent.AXIS_HAT_X)
            val hatY = event.getAxisValue(android.view.MotionEvent.AXIS_HAT_Y)
            val x = if (kotlin.math.abs(rawX) > 0.5f) rawX else hatX
            val y = if (kotlin.math.abs(rawY) > 0.5f) rawY else hatY

            val targetKeyCode = when {
                y < -0.5f -> android.view.KeyEvent.KEYCODE_DPAD_UP
                y > 0.5f -> android.view.KeyEvent.KEYCODE_DPAD_DOWN
                x < -0.5f -> android.view.KeyEvent.KEYCODE_DPAD_LEFT
                x > 0.5f -> android.view.KeyEvent.KEYCODE_DPAD_RIGHT
                else -> 0
            }

            if (targetKeyCode != 0) {
                val now = System.currentTimeMillis()
                if (targetKeyCode != lastStickDirection) {
                    // New direction: fire immediately, reset repeat state
                    lastStickDirection = targetKeyCode
                    stickRepeatCount = 0
                    lastStickFireTime = now
                    fireStickNav(targetKeyCode)
                } else {
                    // Same direction held: initial 350ms pause, then accelerate
                    val delay = when {
                        stickRepeatCount == 0 -> 350L
                        stickRepeatCount < 4 -> 200L
                        else -> 120L
                    }
                    if (now - lastStickFireTime >= delay) {
                        lastStickFireTime = now
                        stickRepeatCount++
                        fireStickNav(targetKeyCode)
                    }
                }
                return true
            } else {
                lastStickDirection = 0
                stickRepeatCount = 0
            }
        }
        return super.onGenericMotionEvent(event)
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (RPCSX.activeGame.value != null || RPCSX.state.value != EmulatorState.Stopped) {
            return super.dispatchKeyEvent(event)
        }

        if (event.action == android.view.KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_UP,
                android.view.KeyEvent.KEYCODE_DPAD_DOWN,
                android.view.KeyEvent.KEYCODE_DPAD_LEFT,
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    val res = handleNavKeyCode(event.keyCode)
                    if (res.consumed) return true
                }
                android.view.KeyEvent.KEYCODE_BUTTON_A, android.view.KeyEvent.KEYCODE_DPAD_CENTER -> {
                    if (net.rpcsx.ui.navigation.FrameNavigationManager.onCrossPressed()) {
                        return true
                    }
                    val syntheticEvent = android.view.KeyEvent(
                        event.downTime, event.eventTime,
                        android.view.KeyEvent.ACTION_DOWN,
                        android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                        event.repeatCount, event.metaState,
                        event.deviceId, event.scanCode, event.flags, event.source
                    )
                    return super.dispatchKeyEvent(syntheticEvent)
                }
                android.view.KeyEvent.KEYCODE_BUTTON_B, android.view.KeyEvent.KEYCODE_BACK -> {
                    if (net.rpcsx.ui.navigation.FrameNavigationManager.onCirclePressed()) {
                        return true
                    }
                    onBackPressedDispatcher.onBackPressed()
                    return true
                }
            }
        }

        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterUsbEventListener()
        unregisterGamepadEventListener()
    }
}
