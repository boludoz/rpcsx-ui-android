package net.rpcsx.ui.navigation

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import net.rpcsx.GamepadRepository
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ListItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.navArgument
import kotlinx.coroutines.launch
import net.rpcsx.BuildConfig
import net.rpcsx.EmulatorState
import net.rpcsx.FirmwareRepository
import net.rpcsx.GameDirectoryKind
import net.rpcsx.GameDirectoryRepository
import net.rpcsx.PrecompilerService
import net.rpcsx.PrecompilerServiceAction
import net.rpcsx.ProgressRepository
import net.rpcsx.R
import net.rpcsx.RPCSX
import net.rpcsx.StorageAccess
import net.rpcsx.UserRepository
import androidx.documentfile.provider.DocumentFile
import net.rpcsx.dialogs.AlertDialogQueue
import net.rpcsx.overlay.OverlayEditActivity
import net.rpcsx.ui.channels.DefaultGpuDriverChannel
import net.rpcsx.ui.channels.DevRpcsxChannel
import net.rpcsx.ui.channels.DevUiChannel
import net.rpcsx.ui.channels.ReleaseRpcsxChannel
import net.rpcsx.ui.channels.ReleaseUiChannel
import net.rpcsx.ui.channels.UpdateChannelListScreen
import net.rpcsx.ui.channels.UpdateChannelsScreen
import net.rpcsx.ui.channels.channelToUiText
import net.rpcsx.ui.channels.channelsToUiText
import net.rpcsx.ui.channels.uiTextToChannel
import net.rpcsx.ui.channels.uiTextToChannels
import net.rpcsx.ui.drivers.GpuDriversScreen
import net.rpcsx.ui.games.GameDirectoriesScreen
import net.rpcsx.ui.games.GamesScreen
import net.rpcsx.ui.settings.AdvancedSettingsScreen
import net.rpcsx.ui.settings.GameSettingsScreen
import net.rpcsx.ui.settings.ControllerSettings
import net.rpcsx.ui.settings.GraphicsSettings
import net.rpcsx.ui.settings.PlayerControllerSettings
import net.rpcsx.ui.settings.SettingsScreen
import net.rpcsx.ui.user.UsersScreen
import net.rpcsx.utils.PerGameConfigRepository
import net.rpcsx.utils.GeneralSettings
import net.rpcsx.utils.GeneralSettings.boolean
import net.rpcsx.utils.RpcsxUpdater
import org.json.JSONObject

val LocalDockPadding = compositionLocalOf { 0.dp }

@Preview
@Composable
fun AppNavHost() {
    val context = LocalContext.current
    val navController = rememberNavController()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE) }
    val rpcsxLibrary by remember { RPCSX.activeLibrary }

    val navigateTo: (String) -> Unit = { route ->
        navController.navigate(route) {
            launchSingleTop = true
            restoreState = true
        }
    }

    var gpuDriverChannelList =
        prefs.getStringSet("gpu_driver_channel_list", setOf(DefaultGpuDriverChannel))?.toList()
    if (gpuDriverChannelList == null) {
        gpuDriverChannelList = listOf(DefaultGpuDriverChannel)
    }
    var gpuDriverChannels by remember { mutableStateOf(gpuDriverChannelList) }

    var uiChannelList =
        prefs.getStringSet("ui_channel_list", setOf(ReleaseUiChannel, DevUiChannel))?.toList()
    if (uiChannelList == null) {
        uiChannelList = listOf(ReleaseUiChannel, DevUiChannel)
    }
    var uiChannels by remember { mutableStateOf(uiChannelList) }

    var rpcsxChannelList =
        prefs.getStringSet("rpcsx_channel_list", setOf(ReleaseRpcsxChannel, DevRpcsxChannel))
            ?.toList()
    if (rpcsxChannelList == null) {
        rpcsxChannelList = listOf(ReleaseRpcsxChannel, DevRpcsxChannel)
    }
    var rpcsxChannels by remember { mutableStateOf(rpcsxChannelList) }

    val isValidChannel = { channel: String, releaseRepo: String, devRepo: String ->
        channel != "Release" && channel != "Development" && channel != releaseRepo && channel != devRepo
    }

    if (prefs.getString("gpu_driver_channel", "") == "") {
        prefs.edit {
            putString("gpu_driver_channel", DefaultGpuDriverChannel)
        }
    }

    if (prefs.getString("ui_channel", "") == "") {
        prefs.edit {
            putString("ui_channel", DevUiChannel)
        }
    }

    if (prefs.getString("rpcsx_channel", "") == "") {
        prefs.edit {
            putString("rpcsx_channel", DevRpcsxChannel)
        }
    }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val showDock = currentRoute in listOf("games", "settings", "controls", "game_directories")

    val installPkgLauncher = rememberLauncherForActivityResult(
        // GetContent (ACTION_GET_CONTENT) rather than OpenDocument
        // (ACTION_OPEN_DOCUMENT): many third-party file managers only
        // implement the former, so requiring OpenDocument silently excluded
        // them from the PKG/PUP/ISO picker on those devices.
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri: Uri? ->
            if (uri != null) {
                // ISOs are booted straight from the content URI later (fd
                // boot - works for any provider, including Downloads), so
                // keep long-term read access to it. PKG/EDAT/PUP files are
                // consumed during install and need no persistent grant.
                // GetContent grants are not always persistable (depends on
                // the source provider), so failure here is expected and fine.
                val name = DocumentFile.fromSingleUri(context, uri)?.name
                if (name?.endsWith(".iso", ignoreCase = true) == true) {
                    try {
                        context.contentResolver.takePersistableUriPermission(
                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                PrecompilerService.start(
                    context,
                    PrecompilerServiceAction.Install,
                    uri
                )
            }
        }
    )

    // Offered once, the first time the user installs a PKG/PUP, when "All
    // files access" isn't granted yet (see PrecompilerService.install for
    // what it speeds up). Declining just proceeds through the normal SAF
    // path - this never blocks the install, only offers a faster one.
    val requestFastPkgInstall = {
        if (StorageAccess.isGranted() || GeneralSettings["asked_fast_pkg_install"].boolean()) {
            installPkgLauncher.launch("*/*")
        } else {
            GeneralSettings["asked_fast_pkg_install"] = true
            AlertDialogQueue.showDialog(
                title = context.getString(R.string.fast_pkg_install_title),
                message = context.getString(R.string.fast_pkg_install_message),
                onConfirm = { StorageAccess.requestAccess(context) },
                onDismiss = { installPkgLauncher.launch("*/*") },
                confirmText = context.getString(R.string.enable),
                dismissText = context.getString(R.string.not_now)
            )
        }
    }

    val installFwLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri: Uri? ->
            if (uri != null) PrecompilerService.start(
                context,
                PrecompilerServiceAction.InstallFirmware,
                uri
            )
        }
    )

    val gameFolderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
        onResult = { uri: Uri? ->
            uri?.let {
                val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(it, takeFlags)
                GameDirectoryRepository.add(it, GameDirectoryKind.Games)
                GameDirectoryRepository.scanGameDirectory(context, it)
            }
        }
    )

    val isoDirPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
        onResult = { uri: Uri? ->
            uri?.let {
                val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(it, takeFlags)
                GameDirectoryRepository.add(it, GameDirectoryKind.Iso)
                GameDirectoryRepository.scanIsoDirectory(context, it)
            }
        }
    )

    BackHandler(enabled = drawerState.isOpen) {
        scope.launch {
            drawerState.close()
        }
    }

    AlertDialogQueue.AlertDialog()

    if (rpcsxLibrary == null) {
        GamesDestination(
            navigateToSettings = { },
            navigateToControls = { },
            drawerState = drawerState,
            navigateToUsers = { },
            installFwLauncher = installFwLauncher
        )

        return
    }

    val settings = remember { mutableStateOf(JSONObject(RPCSX.instance.settingsGet("", ""))) }
    val refreshSettings: () -> Unit = {
        settings.value = JSONObject(RPCSX.instance.settingsGet("", ""))
    }

    var dockHeight by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current

    CompositionLocalProvider(LocalDockPadding provides dockHeight) {
        Box(modifier = Modifier.fillMaxSize()) {
            NavHost(
                navController = navController,
                startDestination = "games",
                modifier = Modifier.fillMaxSize()
            ) {
            composable(
                route = "games"
            ) {
                GamesDestination(
                    navigateToSettings = {
                        navController.navigate("settings") {
                            popUpTo("games") { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    navigateToControls = {
                        navController.navigate("controls") {
                            popUpTo("games") { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    drawerState = drawerState,
                    navigateToDirectories = {
                        navController.navigate("game_directories") {
                            popUpTo("games") { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    navigateToGameSettings = { path ->
                        navController.navigate("game_settings/${Uri.encode(path)}")
                    },
                    navigateToUsers = {
                        navController.navigate("users") {
                            popUpTo("games") { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    installFwLauncher = installFwLauncher
                )
            }

        composable(
            route = "users"
        ) {
            UsersScreen(navigateBack = navController::navigateUp)
        }

        fun unwrapSetting(obj: JSONObject, path: String = "") {
            obj.keys().forEach self@{ key ->
                val item = obj[key]
                val elemPath = "$path@@$key"
                val elemObject = item as? JSONObject
                if (elemObject == null) {
                    Log.e("Main", "element is not object: settings$elemPath, $item")
                    return@self
                }

                if (elemObject.has("type")) {
                    return@self
                }

                Log.e("Main", "registration settings$elemPath")

                composable(
                    route = "settings$elemPath"
                ) {
                    AdvancedSettingsScreen(
                        navigateBack = navController::navigateUp,
                        navigateTo = navigateTo,
                        settings = elemObject,
                        path = elemPath
                    )
                }

                unwrapSetting(elemObject, elemPath)
            }
        }

        composable(
            route = "settings@@$"
        ) {
            AdvancedSettingsScreen(
                navigateBack = navController::navigateUp,
                navigateTo = navigateTo,
                settings = settings.value,
            )
        }

        composable(
            route = "settings"
        ) {
            SettingsScreen(
                navigateBack = navController::navigateUp,
                navigateTo = navigateTo,
                onRefresh = refreshSettings
            )
        }

        composable(
            route = "graphics"
        ) {
            GraphicsSettings(
                navigateBack = navController::navigateUp,
                navigateTo = navigateTo
            )
        }

        composable(
            route = "game_directories"
        ) {
            GameDirectoriesScreen(
                navigateBack = navController::navigateUp
            )
        }

        composable(
            route = "licenses"
        ) {
            net.rpcsx.ui.licenses.LicensesScreen(
                navigateBack = navController::navigateUp
            )
        }

        composable(
            route = "game_settings/{gamePath}",
            arguments = listOf(navArgument("gamePath") { type = NavType.StringType })
        ) { entry ->
            GameSettingsScreen(
                gamePath = entry.arguments?.getString("gamePath") ?: "",
                navigateBack = navController::navigateUp,
                navigateTo = navigateTo
            )
        }



        // Per-game advanced settings: same dynamic tree as the global
        // "settings" routes, but values are merged with (and written to) the
        // title's custom config overrides instead of the emulator's global
        // config. Registered per settings-tree node, parameterized by title
        // id (the core's own per-game config key - see
        // PerGameConfigRepository / config/custom_configs/config_<id>.yml).
        fun registerGameAdvanced(obj: JSONObject, path: String) {
            composable(
                route = "game_adv/{titleId}$path${if (path.isEmpty()) "@@$" else ""}",
                arguments = listOf(
                    navArgument("titleId") { type = NavType.StringType }
                )
            ) { entry ->
                val titleId = entry.arguments?.getString("titleId") ?: ""
                var merged by remember(titleId) { mutableStateOf(PerGameConfigRepository.mergedSettings(titleId)) }
                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner, titleId) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            merged = PerGameConfigRepository.mergedSettings(titleId)
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }
                AdvancedSettingsScreen(
                    navigateBack = navController::navigateUp,
                    navigateTo = navigateTo,
                    settings = PerGameConfigRepository.subtree(merged, path) ?: JSONObject(),
                    path = path,
                    routePrefix = "game_adv/$titleId",
                    applySetting = { p, v ->
                        val value: Any = when {
                            v == "true" -> true
                            v == "false" -> false
                            v.startsWith("\"") && v.endsWith("\"") -> v.substring(1, v.length - 1)
                            else -> v.toLongOrNull() ?: v.toDoubleOrNull() ?: v
                        }
                        PerGameConfigRepository.set(titleId, p, value)
                    },
                    showInstallRpcsx = false
                )
            }

            obj.keys().forEach self@{ key ->
                val elemObject = obj[key] as? JSONObject ?: return@self
                if (elemObject.has("type")) {
                    return@self
                }
                registerGameAdvanced(elemObject, "$path@@$key")
            }
        }
        registerGameAdvanced(settings.value, "")

        composable(
            route = "controls"
        ) {
            ControllerSettings(
                navigateBack = navController::navigateUp,
                navigateToPlayer = { slot -> navController.navigate("controls_player_$slot") },
                navigateToTouchpad = {
                    context.startActivity(Intent(context, OverlayEditActivity::class.java))
                }
            )
        }

        composable(
            route = "controls_player_0"
        ) {
            PlayerControllerSettings(
                playerSlot = 0,
                navigateBack = navController::navigateUp
            )
        }

        composable(
            route = "controls_player_1"
        ) {
            PlayerControllerSettings(
                playerSlot = 1,
                navigateBack = navController::navigateUp
            )
        }

        composable(
            route = "controls_player_2"
        ) {
            PlayerControllerSettings(
                playerSlot = 2,
                navigateBack = navController::navigateUp
            )
        }

        composable(
            route = "controls_player_3"
        ) {
            PlayerControllerSettings(
                playerSlot = 3,
                navigateBack = navController::navigateUp
            )
        }

        composable(
            route = "drivers"
        ) {
            GpuDriversScreen(
                navigateBack = navController::navigateUp
            )
        }

        composable(
            route = "update_channels"
        ) {
            UpdateChannelsScreen(
                navigateBack = navController::navigateUp,
                navigateTo = navigateTo,
            )
        }

        composable(
            route = "gpu_driver_channels"
        ) {
            UpdateChannelListScreen(
                navigateBack = navController::navigateUp,
                title = stringResource(R.string.driver_download_channel),
                items = gpuDriverChannels.toList(),
                selected = prefs.getString("gpu_driver_channel", null),
                onSelect = { channel ->
                    prefs.edit {
                        putString("gpu_driver_channel", channel)
                    }

                    navController.navigateUp()
                },
                onDelete = { channel ->
                    gpuDriverChannels = gpuDriverChannels.filter { it != channel }

                    prefs.edit {
                        putStringSet("gpu_driver_channel_list", gpuDriverChannels.toSet())
                    }
                },
                onAdd = { channel ->
                    if (gpuDriverChannels.find { it == channel } != null) {
                        return@UpdateChannelListScreen
                    }

                    gpuDriverChannels = gpuDriverChannels + channel

                    prefs.edit {
                        putStringSet("gpu_driver_channel_list", gpuDriverChannels.toSet())
                    }
                },
                isDeletable = { gpuDriverChannels.size > 1 })
        }

        composable(
            route = "ui_channels"
        ) {
            UpdateChannelListScreen(
                navigateBack = navController::navigateUp,
                title = stringResource(R.string.ui_update_channel),
                items = channelsToUiText(uiChannels, ReleaseUiChannel, DevUiChannel),
                selected = channelToUiText(
                    prefs.getString("ui_channel", ReleaseUiChannel)!!,
                    ReleaseUiChannel,
                    DevUiChannel
                ),
                onSelect = { channel ->
                    prefs.edit {
                        putString(
                            "ui_channel",
                            uiTextToChannel(channel, ReleaseUiChannel, DevUiChannel)
                        )
                    }

                    navController.navigateUp()
                },
                onDelete = { channel ->
                    uiChannels = uiChannels.filter { it != channel }

                    prefs.edit {
                        putStringSet(
                            "ui_channel_list",
                            uiTextToChannels(uiChannels, ReleaseUiChannel, DevUiChannel).toSet()
                        )
                    }
                },
                onAdd = { channel ->
                    if (!isValidChannel(
                            channel,
                            ReleaseUiChannel,
                            DevUiChannel
                        ) || uiChannels.find { it == channel } != null
                    ) {
                        return@UpdateChannelListScreen
                    }

                    uiChannels += channel

                    prefs.edit {
                        putStringSet(
                            "ui_channel_list",
                            uiTextToChannels(uiChannels, ReleaseUiChannel, DevUiChannel).toSet()
                        )
                    }
                },
                isDeletable = { isValidChannel(it, ReleaseUiChannel, DevUiChannel) }
            )
        }

        composable(
            route = "rpcsx_repositories"
        ) {
            UpdateChannelListScreen(
                navigateBack = navController::navigateUp,
                title = stringResource(R.string.rpcsx_download_channel),
                items = channelsToUiText(rpcsxChannels, ReleaseRpcsxChannel, DevRpcsxChannel),
                selected = channelToUiText(
                    prefs.getString("rpcsx_channel", ReleaseRpcsxChannel)!!,
                    ReleaseRpcsxChannel,
                    DevRpcsxChannel
                ),
                onSelect = { channel ->
                    prefs.edit {
                        putString(
                            "rpcsx_channel",
                            uiTextToChannel(channel, ReleaseRpcsxChannel, DevRpcsxChannel)
                        )
                    }

                    navController.navigateUp()
                },
                onDelete = { channel ->
                    rpcsxChannels = rpcsxChannels.filter { it != channel }

                    prefs.edit {
                        putStringSet(
                            "rpcsx_channel_list",
                            uiTextToChannels(
                                rpcsxChannels,
                                ReleaseRpcsxChannel,
                                DevRpcsxChannel
                            ).toSet()
                        )
                    }
                },
                onAdd = { channel ->
                    if (!isValidChannel(
                            channel,
                            ReleaseRpcsxChannel,
                            DevRpcsxChannel
                        ) || rpcsxChannels.find { it == channel } != null
                    ) {
                        return@UpdateChannelListScreen
                    }

                    rpcsxChannels += channel

                    prefs.edit {
                        putStringSet(
                            "rpcsx_channel_list",
                            uiTextToChannels(
                                rpcsxChannels,
                                ReleaseRpcsxChannel,
                                DevRpcsxChannel
                            ).toSet()
                        )
                    }
                },
                isDeletable = { isValidChannel(it, ReleaseRpcsxChannel, DevRpcsxChannel) }
            )
        }

        unwrapSetting(settings.value)
    }

            if (showDock) {
                FloatingDock(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp)
                        .navigationBarsPadding()
                        .onGloballyPositioned { coordinates ->
                            with(density) {
                                dockHeight = coordinates.size.height.toDp() + 16.dp
                            }
                        },
                    currentRoute = currentRoute ?: "games",
                    onNavigateToGames = {
                        navController.navigate("games") {
                            popUpTo("games") { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onNavigateToSettings = {
                        navController.navigate("settings") {
                            popUpTo("games") { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onNavigateToControls = {
                        navController.navigate("controls") {
                            popUpTo("games") { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onNavigateToDirectories = {
                        navController.navigate("game_directories") {
                            popUpTo("games") { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onNavigateToUsers = {
                        navController.navigate("users") {
                            popUpTo("games") { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onAddGameFile = requestFastPkgInstall,
                    onAddGameFolder = { gameFolderPickerLauncher.launch(null) },
                    onAddIsoFolder = { isoDirPickerLauncher.launch(null) }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GamesDestination(
    navigateToSettings: () -> Unit,
    navigateToControls: () -> Unit,
    drawerState: androidx.compose.material3.DrawerState,
    navigateToDirectories: () -> Unit = {},
    navigateToGameSettings: (String) -> Unit = {},
    navigateToUsers: () -> Unit = {},
    installFwLauncher: ActivityResultLauncher<String>
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // val prefs = remember { context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE) }
    var emulatorState by remember { RPCSX.state }
    val emulatorActiveGame by remember { RPCSX.activeGame }
    val rpcsxLibrary by remember { RPCSX.activeLibrary }
    val activeUser by remember { UserRepository.activeUser }

    if (rpcsxLibrary == null) {
        GamesScreen()
        return
    }

    LaunchedEffect(Unit) {
        UserRepository.load()
    }

    // Remove launchers from here as they are at AppNavHost level


    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Column(
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .verticalScroll(
                            rememberScrollState()
                        )
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp, bottom = 8.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(
                                        MaterialTheme.colorScheme.primaryContainer,
                                        MaterialTheme.colorScheme.surfaceVariant
                                    )
                                )
                            )
                            .padding(16.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(48.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    val firstChar = UserRepository.getUsername(activeUser)?.firstOrNull()?.uppercase() ?: "U"
                                    Text(
                                        text = firstChar,
                                        style = MaterialTheme.typography.titleLarge.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onPrimary
                                        )
                                    )
                                }
                            }
                            Column {
                                Text(
                                    text = UserRepository.getUsername(activeUser) ?: "User",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "RPCSX User",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    NavigationDrawerItem(
                        label = {
                            val progressChannel = FirmwareRepository.progressChannel
                            val progress = ProgressRepository.getItem(progressChannel.value)
                            val progressValue = progress?.value?.value
                            val maxValue = progress?.value?.max
                            val progressMessage = progress?.value?.message?.value

                            Column {
                                Text(
                                    "${stringResource(R.string.firmware)}: ${
                                        FirmwareRepository.version.value ?: stringResource(R.string.none)
                                    }"
                                )
                                // A long PUP install shows only a small spinner
                                // badge otherwise, which can look like the app
                                // froze - surface the actual percentage/message.
                                if (progressValue != null && maxValue != null) {
                                    val statusText = if (maxValue.longValue > 0L) {
                                        val percent = (progressValue.longValue * 100 / maxValue.longValue).coerceIn(0, 100)
                                        progressMessage ?: "$percent%"
                                    } else {
                                        progressMessage ?: stringResource(R.string.installing_dir)
                                    }
                                    Text(
                                        text = statusText,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        },
                        selected = false,
                        icon = { Icon(painterResource(R.drawable.hard_drive), contentDescription = null) },
                        badge = {
                            val progressChannel = FirmwareRepository.progressChannel
                            val progress = ProgressRepository.getItem(progressChannel.value)
                            val progressValue = progress?.value?.value
                            val maxValue = progress?.value?.max
                            if (progressValue != null && maxValue != null) {
                                if (maxValue.longValue != 0L) {
                                    CircularProgressIndicator(
                                        modifier = Modifier
                                            .width(32.dp)
                                            .height(32.dp),
                                        color = MaterialTheme.colorScheme.secondary,
                                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                                        progress = {
                                            progressValue.longValue.toFloat() / maxValue.longValue.toFloat()
                                        },
                                    )
                                } else {
                                    CircularProgressIndicator(
                                        modifier = Modifier
                                            .width(32.dp)
                                            .height(32.dp),
                                        color = MaterialTheme.colorScheme.secondary,
                                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                                    )
                                }
                            }
                        }, // Placeholder
                        onClick = {
                            if (FirmwareRepository.progressChannel.value == null) {
                                installFwLauncher.launch("*/*")
                            }
                        }
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    NavigationDrawerItem(
                        label = { Text(stringResource(R.string.device_info)) },
                        selected = false,
                        icon = { Icon(painterResource(R.drawable.perm_device_information), contentDescription = null) },
                        onClick = {
                            AlertDialogQueue.showDialog(
                                context.getString(R.string.device_info),
                                RPCSX.instance.systemInfo(),
                                confirmText = context.getString(android.R.string.copy),
                                dismissText = context.getString(R.string.close),
                                onConfirm = {
                                    val clipboard =
                                        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = ClipData.newPlainText(
                                        context.getString(R.string.device_info),
                                        RPCSX.instance.systemInfo()
                                    )
                                    clipboard.setPrimaryClip(clip)
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.copied_to_clipboard),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            )
                        }
                    )

                    HorizontalDivider()

                    NavigationDrawerItem(
                        label = { Text(stringResource(R.string.about)) },
                        selected = false,
                        icon = { Icon(painter = painterResource(id = R.drawable.ic_info), contentDescription = null) },
                        onClick = {
                            val versionInfo = "UI: ${BuildConfig.Version}\nRPCSX: ${RpcsxUpdater.getCurrentVersion()}"
                            AlertDialogQueue.showDialog(
                                title = "RPCSX UI Android",
                                message = versionInfo,
                                confirmText = context.getString(android.R.string.copy),
                                dismissText = context.getString(R.string.close),
                                onConfirm = {
                                    val clipboard =
                                        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = ClipData.newPlainText(
                                        context.getString(R.string.about),
                                        versionInfo
                                    )
                                    clipboard.setPrimaryClip(clip)
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.copied_to_clipboard),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            )
                        }
                    )

                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground,
                    ),
                    title = {
                        Text(
                            "RPCSX",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    navigationIcon = {
                        val isGamepadConnected = GamepadRepository.slots.isNotEmpty() && FrameNavigationManager.isGamepadInputActive
                        val isHeaderActive = isGamepadConnected && FrameNavigationManager.activeFrame == NavigationFrame.HEADER
                        val isFrameFocused = isHeaderActive && FrameNavigationManager.focusLevel == FocusLevel.FRAME_LEVEL
                        val isItemFocused = isHeaderActive && FrameNavigationManager.focusLevel == FocusLevel.ITEM_LEVEL

                        IconButton(
                            onClick = {
                                scope.launch {
                                    if (drawerState.isClosed) drawerState.open() else drawerState.close()
                                }
                            },
                            modifier = Modifier
                                .then(
                                    if (isFrameFocused || isItemFocused) Modifier.border(3.5.dp, MaterialTheme.colorScheme.primary, CircleShape)
                                    else Modifier
                                )
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.size(32.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    val firstChar = UserRepository.getUsername(activeUser)?.firstOrNull()?.uppercase() ?: "U"
                                    Text(
                                        text = firstChar,
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    )
                                }
                            }
                        }
                    },
                    actions = {
                        if (emulatorActiveGame != null && emulatorState != EmulatorState.Stopped && emulatorState != EmulatorState.Stopping) {
                            IconButton(onClick = {
                                emulatorState = EmulatorState.Stopped
                                RPCSX.instance.kill()
                            }) {
                                Icon(
                                    imageVector = ImageVector.vectorResource(R.drawable.ic_stop),
                                    contentDescription = null
                                )
                            }
                        }
                    }
                )
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                GamesScreen(navigateToGameSettings)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FloatingDock(
    modifier: Modifier = Modifier,
    currentRoute: String,
    onNavigateToGames: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToControls: () -> Unit,
    onNavigateToDirectories: () -> Unit,
    onNavigateToUsers: () -> Unit,
    onAddGameFile: () -> Unit,
    onAddGameFolder: () -> Unit,
    onAddIsoFolder: () -> Unit
) {
    var showBottomSheet by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        FrameNavigationManager.onPerformDockAction = { index ->
            when (index) {
                0 -> onNavigateToGames()
                1 -> onNavigateToControls()
                2 -> showBottomSheet = true
                3 -> onNavigateToDirectories()
                4 -> onNavigateToSettings()
            }
        }
    }

    val isGamepadConnected = GamepadRepository.slots.isNotEmpty() && FrameNavigationManager.isGamepadInputActive
    val isDockActive = isGamepadConnected && FrameNavigationManager.activeFrame == NavigationFrame.BOTTOM_DOCK
    val isDockFrameFocused = isDockActive && FrameNavigationManager.focusLevel == FocusLevel.FRAME_LEVEL
    val isDockItemFocused = isDockActive && FrameNavigationManager.focusLevel == FocusLevel.ITEM_LEVEL

    Surface(
        modifier = modifier
            .shadow(16.dp, RoundedCornerShape(32.dp)),
        shape = RoundedCornerShape(32.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
        tonalElevation = 0.dp,
        border = if (isDockFrameFocused) BorderStroke(3.5.dp, MaterialTheme.colorScheme.primary)
                 else BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Games (Index 0)
            val isGamesFocused = isDockItemFocused && FrameNavigationManager.activeDockIndex == 0
            IconButton(
                onClick = onNavigateToGames,
                modifier = Modifier
                    .then(
                        if (isGamesFocused) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, CircleShape)
                        else if (currentRoute == "games") Modifier.background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f), CircleShape)
                        else Modifier
                    )
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.hard_drive),
                    contentDescription = "Games",
                    tint = if (currentRoute == "games" || isGamesFocused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
            }

            // Controls (Index 1)
            val isControlsFocused = isDockItemFocused && FrameNavigationManager.activeDockIndex == 1
            IconButton(
                onClick = onNavigateToControls,
                modifier = Modifier
                    .then(
                        if (isControlsFocused) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, CircleShape)
                        else if (currentRoute == "controls") Modifier.background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f), CircleShape)
                        else Modifier
                    )
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.gamepad),
                    contentDescription = "Controls",
                    tint = if (currentRoute == "controls" || isControlsFocused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
            }

            // Add Game (Index 2)
            val isAddFocused = isDockItemFocused && FrameNavigationManager.activeDockIndex == 2
            IconButton(
                onClick = { showBottomSheet = true },
                modifier = Modifier
                    .then(
                        if (isAddFocused) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, CircleShape)
                        else Modifier.background(MaterialTheme.colorScheme.secondaryContainer, CircleShape)
                    )
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_add),
                    contentDescription = "Add Game",
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(24.dp)
                )
            }

            // Directories (Index 3)
            val isDirFocused = isDockItemFocused && FrameNavigationManager.activeDockIndex == 3
            IconButton(
                onClick = onNavigateToDirectories,
                modifier = Modifier
                    .then(
                        if (isDirFocused) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, CircleShape)
                        else if (currentRoute == "game_directories") Modifier.background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f), CircleShape)
                        else Modifier
                    )
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_folder),
                    contentDescription = "Directories",
                    tint = if (currentRoute == "game_directories" || isDirFocused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
            }

            // Settings (Index 4)
            val isSettingsFocused = isDockItemFocused && FrameNavigationManager.activeDockIndex == 4
            IconButton(
                onClick = onNavigateToSettings,
                modifier = Modifier
                    .then(
                        if (isSettingsFocused) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, CircleShape)
                        else if (currentRoute.startsWith("settings")) Modifier.background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f), CircleShape)
                        else Modifier
                    )
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_settings),
                    contentDescription = "Settings",
                    tint = if (currentRoute.startsWith("settings") || isSettingsFocused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }

    if (showBottomSheet) {
        ModalBottomSheet(
            onDismissRequest = { showBottomSheet = false },
            sheetState = rememberModalBottomSheetState(),
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp)
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.add),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
                
                ListItem(
                    headlineContent = { Text(stringResource(R.string.select_game_file)) },
                    leadingContent = { Icon(painterResource(id = R.drawable.ic_description), null) },
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            showBottomSheet = false
                            onAddGameFile()
                        }
                )

                ListItem(
                    headlineContent = { Text(stringResource(R.string.select_game_folder)) },
                    leadingContent = { Icon(painterResource(id = R.drawable.ic_folder), null) },
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            showBottomSheet = false
                            onAddGameFolder()
                        }
                )

                ListItem(
                    headlineContent = { Text(stringResource(R.string.select_iso_folder)) },
                    leadingContent = { Icon(painterResource(id = R.drawable.ic_folder), null) },
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            showBottomSheet = false
                            onAddIsoFolder()
                        }
                )
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

