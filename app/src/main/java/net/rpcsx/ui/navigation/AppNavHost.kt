package net.rpcsx.ui.navigation

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.clip
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.launch
import net.rpcsx.BuildConfig
import net.rpcsx.EmulatorState
import net.rpcsx.FirmwareRepository
import net.rpcsx.PrecompilerService
import net.rpcsx.PrecompilerServiceAction
import net.rpcsx.ProgressRepository
import net.rpcsx.R
import net.rpcsx.RPCSX
import net.rpcsx.UserRepository
import net.rpcsx.dialogs.AlertDialogQueue
import net.rpcsx.overlay.OverlayEditActivity
import net.rpcsx.ui.channels.DefaultGpuDriverChannel
import net.rpcsx.ui.channels.DevRpcsxChannel
import net.rpcsx.ui.channels.DevUiChannel
import net.rpcsx.ui.channels.ReleaseRpcsxChannel
import net.rpcsx.ui.channels.ReleaseUiChannel
import net.rpcsx.ui.channels.RpcsxCoreUpdateScreen
import net.rpcsx.ui.channels.UpdateChannelListScreen
import net.rpcsx.ui.channels.UpdateChannelsScreen
import net.rpcsx.ui.channels.channelToUiText
import net.rpcsx.ui.channels.channelsToUiText
import net.rpcsx.ui.channels.uiTextToChannel
import net.rpcsx.ui.channels.uiTextToChannels
import net.rpcsx.ui.drivers.GpuDriversScreen
import net.rpcsx.ui.games.GamesScreen
import net.rpcsx.ui.settings.AdvancedSettingsScreen
import net.rpcsx.ui.settings.ControllerSettings
import net.rpcsx.ui.settings.GraphicsSettings
import net.rpcsx.ui.settings.PlayerControllerSettings
import net.rpcsx.ui.settings.SettingsScreen
import net.rpcsx.ui.user.UsersScreen
import net.rpcsx.utils.FileUtil
import net.rpcsx.utils.RpcsxUpdater
import org.json.JSONObject

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
            drawerState = drawerState
        )

        return
    }

    val settings = remember { mutableStateOf(JSONObject(RPCSX.instance.settingsGet(""))) }
    val refreshSettings: () -> Unit = {
        settings.value = JSONObject(RPCSX.instance.settingsGet(""))
    }

    NavHost(
        navController = navController,
        startDestination = "games"
    ) {
        composable(
            route = "games"
        ) {
            GamesDestination(
                navigateToSettings = { navigateTo("settings") },
                navigateToControls = { navigateTo("controls") },
                drawerState
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
            route = "rpcsx_channels"
        ) {
            RpcsxCoreUpdateScreen(
                navigateBack = navController::navigateUp,
                navigateTo = navigateTo
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GamesDestination(
    navigateToSettings: () -> Unit,
    navigateToControls: () -> Unit,
    drawerState: androidx.compose.material3.DrawerState
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

    val installPkgLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri: Uri? ->
            if (uri != null) PrecompilerService.start(
                context,
                PrecompilerServiceAction.Install,
                uri
            )
        }
    )

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
                // TODO: FileUtil.saveGameFolderUri(prefs, it)
                val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(it, takeFlags)
                FileUtil.installPackages(context, it)
            }
        }
    )

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
                            Text(
                                "${stringResource(R.string.firmware)}: ${
                                    FirmwareRepository.version.value ?: stringResource(R.string.none)
                                }"
                            )
                        },
                        selected = false,
                        icon = { Icon(painterResource(R.drawable.hard_drive), contentDescription = null) },
                        badge = {
                            val progressChannel = FirmwareRepository.progressChannel
                            val progress = ProgressRepository.getItem(progressChannel.value)
                            val progressValue = progress?.value?.value
                            val maxValue = progress?.value?.max
                            Log.e("Main", "Update $progressChannel, $progress")
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
                        label = { Text(stringResource(R.string.settings)) },
                        selected = false,
                        icon = { Icon(painter = painterResource(id = R.drawable.ic_settings), null) },
                        onClick = navigateToSettings
                    )

                    NavigationDrawerItem(
                        label = { Text(stringResource(R.string.controls)) },
                        selected = false,
                        icon = { Icon(painterResource(id = R.drawable.gamepad), null) },
                        onClick = navigateToControls
                    )

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
            modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars),
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
                        IconButton(onClick = {
                            scope.launch {
                                if (drawerState.isClosed) {
                                    drawerState.open()
                                } else {
                                    drawerState.close()
                                }
                            }
                        }) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_menu),
                                contentDescription = "Open menu"
                            )
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
            },
            floatingActionButton = {
                DropUpFloatingActionButton(installPkgLauncher, gameFolderPickerLauncher)
            },
        ) { innerPadding -> Column(modifier = Modifier.padding(innerPadding)) { GamesScreen() } }
    }
}

@Composable
fun DropUpFloatingActionButton(
    installPkgLauncher: ActivityResultLauncher<String>,
    gameFolderPickerLauncher: ActivityResultLauncher<Uri?>
) {
    var expanded by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier.padding(16.dp),
        contentAlignment = androidx.compose.ui.Alignment.BottomEnd
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = androidx.compose.ui.Alignment.End
        ) {
            AnimatedVisibility(
                visible = expanded,
                enter = androidx.compose.animation.expandVertically(
                    animationSpec = tween(300, easing = FastOutSlowInEasing)
                ),
                exit = androidx.compose.animation.shrinkVertically(
                    animationSpec = tween(200, easing = FastOutSlowInEasing)
                )
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LabeledFabAction(
                        label = stringResource(R.string.select_game_file),
                        icon = painterResource(id = R.drawable.ic_description),
                        onClick = { installPkgLauncher.launch("*/*"); expanded = false }
                    )
                    LabeledFabAction(
                        label = stringResource(R.string.select_game_folder),
                        icon = painterResource(id = R.drawable.ic_folder),
                        onClick = { gameFolderPickerLauncher.launch(null); expanded = false }
                    )
                }
            }

            FloatingActionButton(
                onClick = { expanded = !expanded }
            ) {
                Icon(painter = painterResource(id = R.drawable.ic_add), contentDescription = "Add")
            }
        }
    }
}

@Composable
private fun LabeledFabAction(
    label: String,
    icon: androidx.compose.ui.graphics.painter.Painter,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.secondaryContainer
        ) {
            Text(
                text = label,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
        FloatingActionButton(
            onClick = onClick,
            containerColor = MaterialTheme.colorScheme.secondary
        ) {
            Icon(painter = icon, contentDescription = label)
        }
    }
}
