package net.rpcsx

import android.view.Surface
import androidx.compose.runtime.mutableStateOf

enum class Digital1Flags(val bit: Int)
{
    None(0),
    CELL_PAD_CTRL_SELECT(0x00000001),
    CELL_PAD_CTRL_L3(0x00000002),
    CELL_PAD_CTRL_R3(0x00000004),
    CELL_PAD_CTRL_START(0x00000008),
    CELL_PAD_CTRL_UP(0x00000010),
    CELL_PAD_CTRL_RIGHT(0x00000020),
    CELL_PAD_CTRL_DOWN(0x00000040),
    CELL_PAD_CTRL_LEFT(0x00000080),
    CELL_PAD_CTRL_PS(0x00000100),
}

enum class Digital2Flags(val bit: Int)
{
    None(0),
    CELL_PAD_CTRL_L2(0x00000001),
    CELL_PAD_CTRL_R2(0x00000002),
    CELL_PAD_CTRL_L1(0x00000004),
    CELL_PAD_CTRL_R1(0x00000008),
    CELL_PAD_CTRL_TRIANGLE(0x00000010),
    CELL_PAD_CTRL_CIRCLE(0x00000020),
    CELL_PAD_CTRL_CROSS(0x00000040),
    CELL_PAD_CTRL_SQUARE(0x00000080),
};

enum class EmulatorState {
    Stopped,
    Loading,
    Stopping,
    Running,
    Paused,
    Frozen, // paused but cannot resume
    Ready,
    Starting;

    companion object {
        fun fromInt(value: Int) = EmulatorState.entries.first { it.ordinal == value }
    }
}

// Mirror of the core's game_boot_result (Emu/System.h) - the ordinals MUST
// match, entries here are mapped by position.
enum class BootResult
{
    NoErrors,
    GenericError,
    NothingToBoot,
    WrongDiscLocation,
    InvalidFileOrFolder,
    InvalidBDvdFolder,
    InstallFailed,
    DecryptionError,
    FileCreationError,
    FirmwareMissing,
    FirmwareVersion,
    UnsupportedDiscType,
    SavestateCorrupted,
    SavestateVersionUnsupported,
    StillRunning,
    AlreadyAdded,
    CurrentlyRestricted,
    DatabaseConfigMissing;

    companion object {
        fun fromInt(value: Int) = entries.first { it.ordinal == value }
    }
};

class RPCSX {
    external fun openLibrary(path: String): Boolean
    external fun getLibraryVersion(path: String): String?
    external fun initialize(rootDir: String, user: String): Boolean
    external fun installFw(fd: Int, progressId: Long): Boolean
    external fun install(fd: Int, progressId: Long, gamePath: String): Boolean
    external fun installKey(fd: Int, requestId: Long, gamePath: String): Boolean
    external fun boot(path: String, configPath: String): Int
    // Boots an ISO from a detached SAF file descriptor (works for any
    // DocumentsProvider, including Downloads). Ownership of fd transfers to
    // native.
    external fun bootIsoFd(fd: Int, configPath: String): Int
    external fun surfaceEvent(surface: Surface, event: Int): Boolean
    external fun usbDeviceEvent(fd: Int, vendorId: Int, productId: Int, event: Int): Boolean
    external fun processCompilationQueue(): Boolean
    external fun startMainThreadProcessor(): Boolean
    // leftTrigger/rightTrigger are analog L2/R2 axis values (0-255), or -1
    // if the caller has no analog reading for that trigger (falls back to
    // digital1/digital2's L2/R2 bits on the native side).
    external fun overlayPadData(digital1: Int, digital2: Int, leftStickX: Int, leftStickY: Int, rightStickX: Int, rightStickY: Int, leftTrigger: Int, rightTrigger: Int): Boolean
    external fun multiPadData(playerIndex: Int, digital1: Int, digital2: Int, leftStickX: Int, leftStickY: Int, rightStickX: Int, rightStickY: Int, leftTrigger: Int, rightTrigger: Int): Boolean
    external fun getMaxVirtualPads(): Int
    // Backend rumble strength for a virtual pad: (large << 8) | small, each
    // 0-255. 0 when idle. Polled by RPCSXActivity to drive the vibrator.
    external fun getPadVibration(playerIndex: Int): Int
    // Live stick position for a virtual pad, already deadzone/squircle-
    // processed by the core: (lx<<24)|(ly<<16)|(rx<<8)|ry, each 0-255.
    // Polled by the pad tuning screen's live preview canvas.
    external fun getStickPosition(playerIndex: Int): Int
    external fun collectGameInfo(rootDir: String, progressId: Long): Boolean
    // Resolves a SAF tree URI to a real filesystem path natively and scans it
    // in place (no copy). Returns false if the URI cannot be resolved to a
    // real, readable path (e.g. cloud storage) so the caller can show an
    // explicit error instead of silently copying.
    external fun collectGameInfoFromUri(treeUri: String, progressId: Long): Boolean
    external fun collectIsoInfoFromUri(treeUri: String, progressId: Long): Boolean
    external fun resolveTreeUriToPath(treeUri: String): String?
    external fun systemInfo(): String
    // Settings bridge for the GLOBAL config.json (JSON content, edited by
    // the core itself). titleId is ignored (kept for JNI compatibility);
    // per-game overrides are handled by PerGameConfigRepository/the
    // customConfig* calls below.
    external fun settingsGet(titleId: String, path: String): String
    external fun settingsSet(titleId: String, path: String, value: String): Boolean
    external fun settingsRemove(titleId: String, path: String): Boolean
    // Pushes one per-game override into the running game's live config (no
    // file is touched; dynamic settings only). Empty value = revert to the
    // effective global value.
    external fun settingsLiveApply(path: String, value: String): Boolean

    // Per-game custom configuration (config/custom_configs/config_<serial>
    // .yml, serial = the game's title id - same file desktop RPCS3 boots
    // with via cfg_mode::custom). Storage-only: PerGameConfigRepository
    // merges customConfigGetOverrides onto the schema from settingsGet
    // itself to render "value"/"overridden" for the UI.
    external fun customConfigExists(serial: String): Boolean
    external fun customConfigDelete(serial: String): Boolean
    external fun customConfigGetOverrides(serial: String): String
    external fun customConfigSet(serial: String, path: String, value: String): Boolean
    external fun customConfigRemove(serial: String, path: String): Boolean
    external fun customConfigImportYaml(serial: String, yaml: String): Boolean

    // Per-player-slot pad tuning (deadzone, anti-deadzone, squircle, trigger
    // threshold, vibration multiplier/threshold/switch-motors, PS3
    // peripheral type). Bridges directly to g_cfg_input.player[i]->config -
    // the same per-player cfg_pad desktop RPCS3's Qt pad dialog reads/writes
    // - global scope only, no per-game override. See PadTuningRepository.
    external fun padConfigGet(playerIndex: Int, path: String): String
    external fun padConfigSet(playerIndex: Int, path: String, value: String): Boolean
    external fun padConfigResetToDefault(playerIndex: Int, path: String): Boolean

    external fun shutdown()
    external fun getState() : Int
    external fun kill()
    external fun resume()
    external fun openHomeMenu()
    external fun loginUser(userId: String)
    external fun getUser(): String
    external fun getTitleId(): String
    external fun supportsCustomDriverLoading() : Boolean
    external fun isInstallableFile(fd: Int) : Boolean
    external fun getDirInstallPath(sfoFd: Int) : String?
    external fun getVersion(): String
    external fun setCustomDriver(path: String, libraryName: String, hookDir: String): Boolean


    companion object {
        var initialized = false
        val instance = RPCSX()
        var rootDirectory = ""
        var nativeLibDirectory = ""
        var lastPlayedGame = ""
        var activeGame = mutableStateOf<String?>(null)
        var state = mutableStateOf(EmulatorState.Stopped)
        var activeLibrary = mutableStateOf<String?>(null)

        fun boot(path: String, configPath: String = ""): BootResult {
            return BootResult.fromInt(instance.boot(path, configPath))
        }

        fun updateState() {
            val newState = EmulatorState.fromInt(instance.getState())
            if (newState != state.value) {
                state.value = newState
            }
        }

        fun getState(): EmulatorState {
            updateState()
            return state.value
        }

        fun getHdd0Dir(): String {
            return rootDirectory + "config/dev_hdd0/"
        }

        fun openLibrary(path: String): Boolean {
            if (!instance.openLibrary(path)) {
                return false
            }

            activeLibrary.value = path
            return true
        }

        init {
            System.loadLibrary("rpcsx-android")
        }
    }
}
