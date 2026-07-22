package net.rpcsx.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.focusable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import net.rpcsx.ui.settings.components.base.BaseDialogPreference
import net.rpcsx.ui.settings.components.core.PreferenceHeader
import net.rpcsx.ui.settings.components.core.PreferenceIcon
import net.rpcsx.ui.settings.components.core.PreferenceValue
import net.rpcsx.ui.settings.components.preference.HomePreference
import net.rpcsx.ui.settings.components.preference.RegularPreference
import net.rpcsx.ui.settings.components.preference.SingleSelectionDialog
import net.rpcsx.ui.settings.components.preference.SliderPreference
import net.rpcsx.ui.settings.components.preference.SwitchPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.rpcsx.GamepadRepository
import net.rpcsx.MaxGamepadPlayers
import net.rpcsx.R
import net.rpcsx.RPCSX
import net.rpcsx.UserRepository
import net.rpcsx.dialogs.AlertDialogQueue
import net.rpcsx.provider.AppDataDocumentProvider
import net.rpcsx.ui.common.ComposePreview
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import net.rpcsx.utils.FileUtil
import net.rpcsx.utils.GamepadAutoMapper
import net.rpcsx.utils.GeneralSettings
import net.rpcsx.utils.InputBindingPrefs
import net.rpcsx.utils.RpcsxUpdater
import org.json.JSONObject
import java.io.File
import kotlin.math.ceil

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedSettingsScreen(
    modifier: Modifier = Modifier,
    navigateBack: () -> Unit,
    navigateTo: (path: String) -> Unit,
    settings: JSONObject,
    path: String = "",
    // Route prefix for sub-node navigation and value sink. The defaults edit
    // the global config through the emulator; the per-game settings screen
    // passes "game_adv/<titleId>" and a lambda that writes the title's
    // custom-config overrides instead.
    routePrefix: String = "settings",
    applySetting: (String, String) -> Boolean = { p, v -> RPCSX.instance.settingsSet("", p, v) },
    showInstallRpcsx: Boolean = true
) {
    val context = LocalContext.current
    val settingValue = remember(settings) { mutableStateOf(settings) }
    var searchQuery by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val filteredKeys = remember(searchQuery, settings, isSearching, path) {
        if (!isSearching || searchQuery.isBlank()) {
            settings.keys().asSequence().mapNotNull { key ->
                val obj = settingValue.value[key] as? JSONObject
                val itemPath = "$path@@$key"
                if (obj != null) itemPath to obj else null
            }.toList()
        } else {
            buildList {
                settings.keys().forEach { parentKey ->
                    val parentObj = settings[parentKey] as? JSONObject ?: return@forEach

                    parentObj.keys().forEach { childKey ->
                        val childObj = parentObj[childKey] as? JSONObject ?: return@forEach

                        if (childKey.contains(searchQuery, ignoreCase = true)) {
                            val itemPath = "$parentKey@@$childKey"
                            add(itemPath to childObj)
                        }
                    }
                }
            }
        }
    }

    val installRpcsxLauncher =
        rememberLauncherForActivityResult(contract = ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) {
                val target = File(context.filesDir.canonicalPath, "librpcsx-dev.so")
                if (target.exists()) {
                    target.delete()
                }

                scope.launch {
                    withContext(Dispatchers.IO) {
                        FileUtil.saveFile(context, uri, target.path)
                    }

                    if (RPCSX.instance.getLibraryVersion(target.path) != null) {
                        RpcsxUpdater.installUpdate(context, target)
                    }
                }
            }
        }

    val topBarScrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    Scaffold(
        modifier = Modifier
            .nestedScroll(topBarScrollBehavior.nestedScrollConnection)
            .then(modifier),
        topBar = {
            val titlePath = path.replace("@@", " / ").removePrefix(" / ")
            LargeTopAppBar(
                title = {
                    AnimatedContent(
                        targetState = isSearching,
                        transitionSpec = {
                            fadeIn(tween(220)) + slideInVertically { -it / 2 } togetherWith
                                    fadeOut(tween(150)) + slideOutVertically { -it / 2 }
                        },
                        label = "SearchTransition"
                    ) { searching ->
                        if (searching) {
                            var expanded by remember { mutableStateOf(false) }

                            CompositionLocalProvider(
                                LocalTextStyle provides MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp)
                            ) {
                                SearchBar(
                                    expanded = expanded,
                                    onExpandedChange = {},
                                    modifier = Modifier.fillMaxWidth().animateContentSize(),
                                    windowInsets = WindowInsets(0, 0, 0, 0),
                                    inputField = {
                                        SearchBarDefaults.InputField(
                                            query = searchQuery,
                                            onQueryChange = { searchQuery = it },
                                            onSearch = { expanded = false },
                                            placeholder = { Text(stringResource(R.string.search)) },
                                            leadingIcon = {
                                                Icon(painter = painterResource(id = R.drawable.ic_search), null)
                                            },
                                            trailingIcon = {
                                                IconButton(onClick = {
                                                    if (searchQuery.isNotEmpty()) {
                                                        searchQuery = ""
                                                    } else {
                                                        isSearching = false
                                                    }
                                                }) {
                                                    Icon(painter = painterResource(id = R.drawable.ic_close), null)
                                                }
                                            },
                                            expanded = expanded,
                                            onExpandedChange = {}
                                        )
                                    }
                                ) {}
                            }
                        } else {
                            Text(
                                text = titlePath.ifEmpty { stringResource(R.string.advanced_settings) },
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                },
                scrollBehavior = topBarScrollBehavior,
                navigationIcon = {
                    IconButton(
                        onClick = navigateBack,
                        modifier = Modifier.padding(0.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_keyboard_arrow_left),
                            contentDescription = null
                        )
                    }
                },
                actions = {
                    if (!isSearching) {
                        IconButton(
                            onClick = { isSearching = true }
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_search),
                                contentDescription = "Search"
                            )
                        }
                    }
                },
            )
        }
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
            contentPadding = PaddingValues(bottom = net.rpcsx.ui.navigation.LocalDockPadding.current)
        ) {
            items(filteredKeys, key = { it.first }) { (itemPath, itemObject) ->
                val key = itemPath.substringAfterLast("@@")
                when (val type =
                        if (itemObject.has("type")) itemObject.getString("type") else null) {
                        null -> {
                            RegularPreference(
                                title = key, leadingIcon = null, onClick = {
                                    Log.e(
                                        "Main",
                                        "Navigate to $routePrefix$itemPath, object $itemObject"
                                    )
                                    navigateTo("$routePrefix$itemPath")
                                }
                            )
                        }

                        "bool" -> {
                            var itemValue by remember(itemObject) { mutableStateOf(itemObject.getBoolean("value")) }
                            val def = itemObject.getBoolean("default")
                            SwitchPreference(
                                checked = itemValue,
                                title = key + if (itemValue == def) "" else " *",
                                leadingIcon = null,
                                onClick = { value ->
                                    if (!applySetting(
                                            itemPath, if (value) "true" else "false"
                                        )
                                    ) {
                                        AlertDialogQueue.showDialog(
                                            context.getString(R.string.error),
                                            context.getString(
                                                R.string.failed_to_assign_value,
                                                value.toString(),
                                                itemPath
                                            )
                                        )
                                    } else {
                                        itemObject.put("value", value)
                                        itemValue = value
                                    }
                                },
                                onLongClick = {
                                    AlertDialogQueue.showDialog(
                                        title = context.getString(R.string.reset_setting),
                                        message = context.getString(R.string.ask_if_reset_key, key),
                                        onConfirm = {
                                            if (applySetting(
                                                    itemPath, def.toString()
                                                )
                                            ) {
                                                itemObject.put("value", def)
                                                itemValue = def
                                            } else {
                                                AlertDialogQueue.showDialog(
                                                    context.getString(R.string.error),
                                                    context.getString(
                                                        R.string.failed_to_reset_key,
                                                        key
                                                    )
                                                )
                                            }
                                        })
                                })
                        }

                        "enum" -> {
                            var itemValue by remember(itemObject) { mutableStateOf(itemObject.getString("value")) }
                            val def = itemObject.getString("default")
                            val variantsJson = itemObject.getJSONArray("variants")
                            val variants = ArrayList<String>()
                            for (i in 0..<variantsJson.length()) {
                                variants.add(variantsJson.getString(i))
                            }

                            SingleSelectionDialog(
                                currentValue = if (itemValue in variants) itemValue else variants[0],
                                values = variants,
                                icon = null,
                                title = key + if (itemValue == def) "" else " *",
                                onValueChange = { value ->
                                    if (!applySetting(
                                            itemPath, "\"" + value + "\""
                                        )
                                    ) {
                                        AlertDialogQueue.showDialog(
                                            context.getString(R.string.error),
                                            context.getString(
                                                R.string.failed_to_assign_value,
                                                value,
                                                itemPath
                                            )
                                        )
                                    } else {
                                        itemObject.put("value", value)
                                        itemValue = value
                                    }
                                },
                                onLongClick = {
                                    AlertDialogQueue.showDialog(
                                        title = context.getString(R.string.reset_setting),
                                        message = context.getString(R.string.ask_if_reset_key, key),
                                        onConfirm = {
                                            if (applySetting(
                                                    itemPath, "\"" + def + "\""
                                                )
                                            ) {
                                                itemObject.put("value", def)
                                                itemValue = def
                                            } else {
                                                AlertDialogQueue.showDialog(
                                                    context.getString(R.string.error),
                                                    context.getString(
                                                        R.string.failed_to_reset_key,
                                                        key
                                                    )
                                                )
                                            }
                                        })
                                })
                        }

                        "uint", "int" -> {
                            var max = 0L
                            var min = 0L
                            var initialItemValue = 0L
                            var def = 0L
                            try {
                                initialItemValue = itemObject.getString("value").toLong()
                                max = itemObject.getString("max").toLong()
                                min = itemObject.getString("min").toLong()
                                def = itemObject.getString("default").toLong()
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                            var itemValue by remember(itemObject) { mutableLongStateOf(initialItemValue) }
                            if (min < max) {
                                SliderPreference(
                                    value = itemValue.toFloat(),
                                    valueRange = min.toFloat()..max.toFloat(),
                                    title = key + if (itemValue == def) "" else " *",
                                    steps = (max - min).toInt() - 1,
                                    onValueChange = { value ->
                                        if (!applySetting(
                                                itemPath, value.toLong().toString()
                                            )
                                        ) {
                                            AlertDialogQueue.showDialog(
                                                context.getString(R.string.error),
                                                context.getString(
                                                    R.string.failed_to_assign_value,
                                                    value.toString(),
                                                    itemPath
                                                )
                                            )
                                        } else {
                                            itemObject.put(
                                                "value", value.toLong().toString()
                                            )
                                            itemValue = value.toLong()
                                        }
                                    },
                                    valueContent = { PreferenceValue(text = itemValue.toString()) },
                                    onLongClick = {
                                        AlertDialogQueue.showDialog(
                                            title = context.getString(R.string.reset_setting),
                                            message = context.getString(
                                                R.string.ask_if_reset_key,
                                                key
                                            ),
                                            onConfirm = {
                                                if (applySetting(
                                                        itemPath, def.toString()
                                                    )
                                                ) {
                                                    itemObject.put("value", def)
                                                    itemValue = def
                                                } else {
                                                    AlertDialogQueue.showDialog(
                                                        context.getString(R.string.error),
                                                        context.getString(
                                                            R.string.failed_to_reset_key,
                                                            key
                                                        )
                                                    )
                                                }
                                            })
                                    })
                            }
                        }

                        "float" -> {
                            var itemValue by remember(itemObject) {
                                mutableDoubleStateOf(
                                    itemObject.getString(
                                        "value"
                                    ).toDouble()
                                )
                            }
                            val max = if (itemObject.has("max")) itemObject.getString("max")
                                .toDouble() else 0.0
                            val min = if (itemObject.has("min")) itemObject.getString("min")
                                .toDouble() else 0.0
                            val def =
                                if (itemObject.has("default")) itemObject.getString("default")
                                    .toDouble() else 0.0

                            if (min < max) {
                                SliderPreference(
                                    value = itemValue.toFloat(),
                                    valueRange = min.toFloat()..max.toFloat(),
                                    title = key + if (itemValue == def) "" else " *",
                                    steps = ceil(max - min).toInt() - 1,
                                    onValueChange = { value ->
                                        if (!applySetting(
                                                itemPath, value.toString()
                                            )
                                        ) {
                                            AlertDialogQueue.showDialog(
                                                context.getString(R.string.error),
                                                context.getString(
                                                    R.string.failed_to_assign_value,
                                                    value.toString(),
                                                    itemPath
                                                )
                                            )
                                        } else {
                                            itemObject.put("value", value.toDouble().toString())
                                            itemValue = value.toDouble()
                                        }
                                    },
                                    valueContent = { PreferenceValue(text = itemValue.toString()) },
                                    onLongClick = {
                                        AlertDialogQueue.showDialog(
                                            title = context.getString(R.string.reset_setting),
                                            message = context.getString(
                                                R.string.ask_if_reset_key,
                                                key
                                            ),
                                            onConfirm = {
                                                if (applySetting(
                                                        itemPath, def.toString()
                                                    )
                                                ) {
                                                    itemObject.put("value", def)
                                                    itemValue = def
                                                } else {
                                                    AlertDialogQueue.showDialog(
                                                        context.getString(R.string.error),
                                                        context.getString(
                                                            R.string.failed_to_reset_key,
                                                            key
                                                        )
                                                    )
                                                }
                                            })
                                    })
                            }
                        }

                        else -> {
                            Log.e("Main", "Unimplemented setting type $type")
                        }
                    }
            }

            if (showInstallRpcsx && path.isEmpty()) {
                item(key = "install_dev_rpcsx") {
                    RegularPreference(
                        title = stringResource(R.string.install_custom_rpcsx_lib),
                        leadingIcon = null,
                        onClick = { installRpcsxLauncher.launch("*/*") }
                    )
                }
            }
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    navigateBack: () -> Unit,
    navigateTo: (path: String) -> Unit,
    onRefresh: () -> Unit
) {
    val topBarScrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    val activeUser by remember { UserRepository.activeUser }

    Scaffold(
        modifier = Modifier
            .nestedScroll(topBarScrollBehavior.nestedScrollConnection)
            .then(modifier), topBar = {
            LargeTopAppBar(
                title = { Text(text = stringResource(R.string.settings), fontWeight = FontWeight.Medium) },
                scrollBehavior = topBarScrollBehavior,
                navigationIcon = {
                    IconButton(
                        onClick = navigateBack
                    ) {
                        Icon(painter = painterResource(id = R.drawable.ic_keyboard_arrow_left), null)
                    }
                })
        }
    ) { contentPadding ->
        val context = LocalContext.current
        val configPicker = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
            onResult = { uri: Uri? ->
                uri?.let { 
                    if (FileUtil.importConfig(context, it))
                        onRefresh()
                }
            }
        )

        val configExporter = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument("application/json"),
            onResult = { uri: Uri? ->
                uri?.let { FileUtil.exportConfig(context, it) }
            }
        )
        
        val lazyListState = rememberLazyListState()
        val coroutineScope = rememberCoroutineScope()

        LazyColumn(
            state = lazyListState,
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type == PointerEventType.Scroll) {
                                val delta = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                                if (delta != 0f) {
                                    coroutineScope.launch {
                                        lazyListState.scrollBy(delta * 120f)
                                    }
                                }
                            }
                        }
                    }
                },
            contentPadding = PaddingValues(bottom = net.rpcsx.ui.navigation.LocalDockPadding.current)
        ) {
            item {
                Spacer(modifier = Modifier.height(16.dp))
            }

            item(
                key = "internal_directory"
            ) {
                HomePreference(
                    title = stringResource(R.string.view_internal_dir),
                    icon = { PreferenceIcon(icon = painterResource(R.drawable.ic_folder)) },
                    description = stringResource(R.string.view_internal_dir_description),
                    onClick = {
                        if (!FileUtil.launchInternalDir(context)) {
                            AlertDialogQueue.showDialog(
                                context.getString(R.string.failed_to_view_internal_dir),
                                context.getString(R.string.no_activity_to_handle_action)
                            )
                        }
                    }
                )
            }

            item(
                key = "users"
            ) {
                HomePreference(
                    title = stringResource(R.string.users),
                    description = "${stringResource(R.string.active_user)}: ${UserRepository.getUsername(activeUser)}",
                    icon = {
                        PreferenceIcon(icon = painterResource(id = R.drawable.ic_person))
                    },
                    onClick = {
                        navigateTo("users")
                    }
                )
            }

            item(key = "update_channels") {
                HomePreference(
                    title = stringResource(R.string.download_channels),
                    icon = { PreferenceIcon(icon = painterResource(R.drawable.ic_cloud_download)) },
                    description = "",
                    onClick = {
                        navigateTo("update_channels")
                    }
                )
            }

            item(key = "auto_download_updates") {
                val autoDownload = (GeneralSettings["auto_download_updates"] as? Boolean) ?: true
                SwitchPreference(
                    checked = autoDownload,
                    title = { Text("Descarga automática de actualizaciones") },
                    leadingIcon = { PreferenceIcon(icon = painterResource(R.drawable.ic_cloud_download)) },
                    subtitle = { Text("Descargar e instalar automáticamente actualizaciones de emulador y parches al iniciar") },
                    onClick = { value -> GeneralSettings["auto_download_updates"] = value }
                )
            }

            item(key = "custom_root_directory") {
                val currentRoot = (GeneralSettings["custom_root_directory"] as? String)
                    ?: context.getExternalFilesDir(null)?.toString() ?: ""
                val customRootLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                    if (uri != null) {
                        try {
                            context.contentResolver.takePersistableUriPermission(
                                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                        } catch (_: Exception) {}
                        val resolvedPath = try {
                            RPCSX.instance.resolveTreeUriToPath(uri.toString())
                        } catch (_: Exception) { null }
                        val pathToSave = if (!resolvedPath.isNullOrEmpty()) resolvedPath else uri.toString()
                        GeneralSettings["custom_root_directory"] = pathToSave
                        RPCSX.rootDirectory = if (pathToSave.endsWith("/")) pathToSave else "$pathToSave/"
                    }
                }
                RegularPreference(
                    title = { Text("Directorio de datos del emulador") },
                    leadingIcon = { PreferenceIcon(icon = painterResource(R.drawable.ic_folder)) },
                    value = { PreferenceValue(currentRoot) },
                    onClick = { customRootLauncher.launch(null) }
                )
            }

            item(key = "advanced_settings") {
                HomePreference(
                    title = stringResource(R.string.advanced_settings),
                    icon = { Icon(painterResource(R.drawable.tune), null) },
                    description = stringResource(R.string.advanced_settings_description),
                    onClick = {
                        navigateTo("settings@@$")
                    },
                    onLongClick = {
                        AlertDialogQueue.showDialog(
                            title = context.getString(R.string.manage_settings),
                            confirmText = context.getString(R.string.export),
                            dismissText = context.getString(R.string.import_),
                            onDismiss = {
                                configPicker.launch(arrayOf("*/*"))
                            },
                            onConfirm = {
                                configExporter.launch("config.json")
                            }
                        )
                    }
                )
            }

            item(
                key = "custom_driver"
            ) {
                HomePreference(
                    title = stringResource(R.string.custom_driver),
                    icon = { Icon(painterResource(R.drawable.memory), contentDescription = null) },
                    description = stringResource(R.string.custom_driver_description),
                    onClick = {
                        if (RPCSX.instance.supportsCustomDriverLoading()) {
                            navigateTo("drivers")
                        } else {
                            AlertDialogQueue.showDialog(
                                title = context.getString(R.string.custom_driver_not_supported),
                                message = context.getString(R.string.custom_driver_not_supported_description),
                                confirmText = context.getString(R.string.close),
                                dismissText = ""
                            )
                        }
                    }  
                )
            }

            item(key = "controls") {
                HomePreference(
                    title = stringResource(R.string.controls),
                    icon = { Icon(painterResource(R.drawable.gamepad), null) },
                    description = stringResource(R.string.controls_description),
                    onClick = { navigateTo("controls") }
                )
            }

            item(key = "graphics") {
                HomePreference(
                    title = stringResource(R.string.graphics),
                    icon = { Icon(painterResource(R.drawable.ic_palette), null) },
                    description = stringResource(R.string.graphics_description),
                    onClick = { navigateTo("graphics") }
                )
            }

            item(key = "language") {
                var showLanguageDialog by remember { mutableStateOf(false) }
                val languageOptions = listOf(
                    "" to stringResource(R.string.language_auto),
                    "en" to "English",
                    "es" to "Español",
                    "pt" to "Português",
                    "zh-CN" to "简体中文",
                    "de" to "Deutsch",
                    "ru" to "Русский",
                    "uk" to "Українська",
                    "hi" to "हिन्दी",
                    "id" to "Bahasa Indonesia",
                    "ja" to "日本語",
                    "ar" to "العربية",
                    "fa" to "فارسی",
                    "fr" to "Français",
                    "it" to "Italiano",
                    "ko" to "한국어",
                    "tr" to "Türkçe",
                    "pl" to "Polski",
                    "nl" to "Nederlands",
                    "vi" to "Tiếng Việt",
                    "tl" to "Tagalog"
                )
                val currentLangTag = AppCompatDelegate.getApplicationLocales().toLanguageTags()
                val normalizedLangTag = when {
                    currentLangTag.isEmpty() || currentLangTag == "und" -> ""
                    currentLangTag.startsWith("in") -> "id"
                    else -> currentLangTag.split(",")[0].trim()
                }
                val currentLanguageName = languageOptions.find { it.first == normalizedLangTag }?.second ?: languageOptions[0].second

                HomePreference(
                    title = stringResource(R.string.language),
                    icon = { Icon(painterResource(R.drawable.ic_language), null) },
                    description = currentLanguageName,
                    onClick = { showLanguageDialog = true }
                )

                if (showLanguageDialog) {
                    BaseDialogPreference(
                        onDismissRequest = { showLanguageDialog = false },
                        title = { Text(stringResource(R.string.language)) },
                        content = {
                            val scrollState = rememberScrollState()
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 400.dp)
                                    .verticalScroll(scrollState)
                            ) {
                                languageOptions.forEach { option ->
                                    val isSelected = option.first == normalizedLangTag
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .selectable(
                                                selected = isSelected,
                                                role = Role.RadioButton,
                                                onClick = {
                                                    showLanguageDialog = false
                                                    val localeList = if (option.first.isEmpty()) {
                                                        LocaleListCompat.getEmptyLocaleList()
                                                    } else {
                                                        LocaleListCompat.forLanguageTags(option.first)
                                                    }
                                                    AppCompatDelegate.setApplicationLocales(localeList)
                                                }
                                            )
                                            .padding(vertical = 12.dp, horizontal = 24.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RadioButton(
                                            selected = isSelected,
                                            onClick = null
                                        )
                                        Spacer(modifier = Modifier.width(16.dp))
                                        Text(
                                            text = option.second,
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            }
                        }
                    )
                }
            }

            item(key = "share_logs") {
                HomePreference(
                    title = stringResource(R.string.share_log),
                    icon = { Icon(painter = painterResource(id = R.drawable.ic_share), contentDescription = null) },
                    description = stringResource(R.string.share_log_description),
                    onClick = {
                        val file = DocumentFile.fromSingleUri(
                            context, DocumentsContract.buildDocumentUri(
                                AppDataDocumentProvider.AUTHORITY,
                                "${AppDataDocumentProvider.ROOT_ID}/cache/RPCSX${if (RPCSX.lastPlayedGame.isNotEmpty()) "" else ".old"}.log"
                            )
                        )

                        if (file != null && file.exists() && file.length() != 0L) {
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                setDataAndType(file.uri, "text/plain")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                putExtra(Intent.EXTRA_STREAM, file.uri)
                            }
                            context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_log)))
                        } else {
                            Toast.makeText(context, context.getString(R.string.log_not_found), Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }
        }
    }
}

// Hub screen: one entry per player slot (independent button mapping each,
// mirroring NetherSX2/AetherSX2's per-controller settings) plus a separate
// Touchpad entry for repositioning the on-screen overlay buttons.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControllerSettings(
    modifier: Modifier = Modifier,
    navigateBack: () -> Unit,
    navigateToPlayer: (Int) -> Unit,
    navigateToTouchpad: () -> Unit
) {
    val topBarScrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    Scaffold(
        modifier = Modifier
            .nestedScroll(topBarScrollBehavior.nestedScrollConnection)
            .then(modifier),
        topBar = {
            LargeTopAppBar(
                title = { Text(text = stringResource(R.string.controls), fontWeight = FontWeight.Medium) },
                scrollBehavior = topBarScrollBehavior,
                navigationIcon = {
                    IconButton(
                        onClick = navigateBack
                    ) {
                        Icon(painter = painterResource(id = R.drawable.ic_keyboard_arrow_left), null)
                    }
                }
            )
        }
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
            contentPadding = PaddingValues(bottom = net.rpcsx.ui.navigation.LocalDockPadding.current)
        ) {
            item {
                Spacer(modifier = Modifier.height(16.dp))
            }

            item {
                PreferenceHeader(stringResource(R.string.connected_controllers))
            }

            items(MaxGamepadPlayers) { playerIndex ->
                val slot = GamepadRepository.slots[playerIndex]
                RegularPreference(
                    title = stringResource(R.string.player_slot, playerIndex + 1),
                    leadingIcon = null,
                    value = {
                        PreferenceValue(slot?.displayTitle ?: stringResource(R.string.controller_not_connected))
                    },
                    onClick = {
                        if (slot != null) {
                            GamepadRepository.identifySlot(playerIndex)
                        }
                        navigateToPlayer(playerIndex)
                    }
                )

                // Port selector for the controller occupying this slot.
                // Picking an occupied port swaps the two controllers; the
                // choice is persisted per device and restored on reconnect.
                if (slot != null) {
                    val portLabels = (0 until MaxGamepadPlayers).map {
                        stringResource(R.string.player_slot, it + 1)
                    }
                    SingleSelectionDialog(
                        title = stringResource(R.string.controller_port),
                        subtitle = { Text(slot.deviceName) },
                        icon = null,
                        currentValue = portLabels[playerIndex],
                        values = portLabels,
                        onValueChange = { value ->
                            val idx = portLabels.indexOf(value)
                            if (idx >= 0) {
                                GamepadRepository.reassign(slot.deviceId, idx)
                            }
                        }
                    )
                }
            }

            item {
                HorizontalDivider()
            }

            item {
                PreferenceHeader(stringResource(R.string.touchpad))
            }

            item {
                RegularPreference(
                    title = stringResource(R.string.edit_overlay),
                    leadingIcon = null,
                    onClick = navigateToTouchpad
                )
            }

            item {
                val context = LocalContext.current
                var forcedSlot by remember {
                    mutableStateOf(
                        ((GeneralSettings["overlay_forced_slot"] as? Int) ?: 0)
                            .coerceIn(0, MaxGamepadPlayers - 1)
                    )
                }
                val labels = (0 until MaxGamepadPlayers).map {
                    context.getString(R.string.player_slot, it + 1)
                }
                SingleSelectionDialog(
                    title = stringResource(R.string.touchpad_port),
                    subtitle = { Text(stringResource(R.string.touchpad_port_desc)) },
                    icon = null,
                    currentValue = labels[forcedSlot],
                    values = labels,
                    onValueChange = { value ->
                        val idx = labels.indexOf(value).coerceIn(0, MaxGamepadPlayers - 1)
                        forcedSlot = idx
                        GeneralSettings["overlay_forced_slot"] = idx
                    }
                )
            }

            item {
                val context = LocalContext.current
                var autoHideSeconds by remember {
                    mutableStateOf(
                        ((GeneralSettings["touchpad_auto_hide_seconds"] as? Int) ?: 5)
                    )
                }
                val values = listOf(
                    context.getString(R.string.never),
                    context.getString(R.string.seconds_2),
                    context.getString(R.string.seconds_5),
                    context.getString(R.string.seconds_10),
                    context.getString(R.string.seconds_15),
                    context.getString(R.string.seconds_30)
                )
                val secondsMap = listOf(0, 2, 5, 10, 15, 30)
                val currentLabel = values[secondsMap.indexOf(autoHideSeconds).coerceIn(0, secondsMap.size - 1)]
                SingleSelectionDialog(
                    title = stringResource(R.string.touchpad_auto_hide),
                    subtitle = { Text(stringResource(R.string.touchpad_auto_hide_desc)) },
                    icon = null,
                    currentValue = currentLabel,
                    values = values,
                    onValueChange = { value ->
                        val idx = values.indexOf(value).coerceIn(0, values.size - 1)
                        val secs = secondsMap[idx]
                        autoHideSeconds = secs
                        GeneralSettings["touchpad_auto_hide_seconds"] = secs
                    }
                )
            }
        }
    }
}

internal const val OutputScalingPath = "Video@@Output Scaling Mode"
internal const val VideoResolutionScalePath = "Video@@Resolution Scale"
internal const val FsrSharpeningPath = "Video@@Vulkan@@FidelityFX CAS Sharpening Intensity"
internal const val FsrScalingValue = "FidelityFX Super Resolution"

// Helper function to search the settings schema JSON dynamically for matching keys
internal fun findVideoSettingPath(settings: JSONObject, searchKeyword: String, defaultKey: String, ignoreKeyword: String? = null): String {
    val videoGroup = settings.optJSONObject("Video") ?: return "Video@@$defaultKey"
    for (key in videoGroup.keys()) {
        if (key.contains(searchKeyword, ignoreCase = true) && (ignoreKeyword == null || !key.contains(ignoreKeyword, ignoreCase = true))) {
            return "Video@@$key"
        }
        val subGroup = videoGroup.optJSONObject(key)
        if (subGroup != null) {
            for (subKey in subGroup.keys()) {
                if (subKey.contains(searchKeyword, ignoreCase = true) && (ignoreKeyword == null || !subKey.contains(ignoreKeyword, ignoreCase = true))) {
                    return "Video@@$key@@$subKey"
                }
            }
        }
    }
    return "Video@@$defaultKey"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GraphicsSettings(
    modifier: Modifier = Modifier,
    navigateBack: () -> Unit,
    navigateTo: (path: String) -> Unit
) {
    val topBarScrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    val context = LocalContext.current

    // Dynamically find settings paths using settingsGet("") JSON
    val rootSettingsJson = remember {
        try {
            JSONObject(RPCSX.instance.settingsGet("", ""))
        } catch (e: Exception) {
            JSONObject()
        }
    }

    val scalingNode = remember {
        try {
            JSONObject(RPCSX.instance.settingsGet("", OutputScalingPath))
        } catch (e: Exception) {
            JSONObject()
        }
    }
    var scalingValue by remember {
        mutableStateOf(if (scalingNode.has("value")) scalingNode.getString("value") else "")
    }
    val scalingVariants = remember(scalingNode) {
        if (scalingNode.has("variants")) {
            val variantsJson = scalingNode.getJSONArray("variants")
            (0 until variantsJson.length()).map { variantsJson.getString(it) }
        } else emptyList()
    }

    val sharpeningNode = remember {
        try {
            JSONObject(RPCSX.instance.settingsGet("", FsrSharpeningPath))
        } catch (e: Exception) {
            JSONObject()
        }
    }
    var sharpeningValue by remember {
        mutableFloatStateOf(if (sharpeningNode.has("value")) sharpeningNode.getString("value").toFloatOrNull() ?: 0f else 0f)
    }
    val sharpeningMin = remember(sharpeningNode) {
        if (sharpeningNode.has("min")) sharpeningNode.getString("min").toFloatOrNull() ?: 0f else 0f
    }
    val sharpeningMax = remember(sharpeningNode) {
        if (sharpeningNode.has("max")) sharpeningNode.getString("max").toFloatOrNull() ?: 100f else 100f
    }

    val videoResolutionScaleNode = remember {
        try {
            JSONObject(RPCSX.instance.settingsGet("", VideoResolutionScalePath))
        } catch (e: Exception) {
            JSONObject()
        }
    }
    var videoResolutionScaleValue by remember {
        mutableFloatStateOf(if (videoResolutionScaleNode.has("value")) videoResolutionScaleNode.getString("value").toFloatOrNull() ?: 100f else 100f)
    }
    val videoResolutionScaleMin = remember(videoResolutionScaleNode) {
        if (videoResolutionScaleNode.has("min")) videoResolutionScaleNode.getString("min").toFloatOrNull() ?: 25f else 25f
    }
    val videoResolutionScaleMax = remember(videoResolutionScaleNode) {
        if (videoResolutionScaleNode.has("max")) videoResolutionScaleNode.getString("max").toFloatOrNull() ?: 800f else 800f
    }

    val rendererPath = remember(rootSettingsJson) {
        findVideoSettingPath(rootSettingsJson, "renderer", "Renderer")
    }

    val resolutionPath = remember(rootSettingsJson) {
        findVideoSettingPath(rootSettingsJson, "resolution", "Resolution", ignoreKeyword = "scaling")
    }

    // Renderer setting
    val rendererNode = remember(rendererPath) {
        try {
            JSONObject(RPCSX.instance.settingsGet("", rendererPath))
        } catch (e: Exception) {
            JSONObject()
        }
    }
    var rendererValue by remember {
        mutableStateOf(if (rendererNode.has("value")) rendererNode.getString("value") else "")
    }
    val rendererVariants = remember(rendererNode) {
        if (rendererNode.has("variants")) {
            val variantsJson = rendererNode.getJSONArray("variants")
            (0 until variantsJson.length()).map { variantsJson.getString(it) }
        } else emptyList()
    }

    // Resolution setting
    val resolutionNode = remember(resolutionPath) {
        try {
            JSONObject(RPCSX.instance.settingsGet("", resolutionPath))
        } catch (e: Exception) {
            JSONObject()
        }
    }
    var resolutionValue by remember {
        mutableStateOf(if (resolutionNode.has("value")) resolutionNode.getString("value") else "")
    }
    val resolutionVariants = remember(resolutionNode) {
        if (resolutionNode.has("variants")) {
            val variantsJson = resolutionNode.getJSONArray("variants")
            (0 until variantsJson.length()).map { variantsJson.getString(it) }
        } else emptyList()
    }

    // Resolution slider option if numeric
    var resolutionSliderValue by remember {
        mutableFloatStateOf(if (resolutionNode.has("value") && !resolutionNode.has("variants")) resolutionNode.getString("value").toFloatOrNull() ?: 100f else 100f)
    }
    val resolutionMin = remember(resolutionNode) {
        if (resolutionNode.has("min")) resolutionNode.getString("min").toFloatOrNull() ?: 50f else 50f
    }
    val resolutionMax = remember(resolutionNode) {
        if (resolutionNode.has("max")) resolutionNode.getString("max").toFloatOrNull() ?: 300f else 300f
    }

    Scaffold(
        modifier = Modifier
            .nestedScroll(topBarScrollBehavior.nestedScrollConnection)
            .then(modifier),
        topBar = {
            LargeTopAppBar(
                title = { Text(text = stringResource(R.string.graphics), fontWeight = FontWeight.Medium) },
                scrollBehavior = topBarScrollBehavior,
                navigationIcon = {
                    IconButton(onClick = navigateBack) {
                        Icon(painter = painterResource(id = R.drawable.ic_keyboard_arrow_left), null)
                    }
                }
            )
        }
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
            contentPadding = PaddingValues(bottom = net.rpcsx.ui.navigation.LocalDockPadding.current)
        ) {
            item {
                Spacer(modifier = Modifier.height(16.dp))
            }

            // 1. Rendering Engine
            if (rendererVariants.isNotEmpty()) {
                item {
                    val cleanTitle = rendererPath.substringAfterLast("@@")
                    SingleSelectionDialog(
                        currentValue = if (rendererValue in rendererVariants) rendererValue else rendererVariants[0],
                        values = rendererVariants,
                        title = cleanTitle,
                        icon = null,
                        onValueChange = { value ->
                            if (RPCSX.instance.settingsSet("", rendererPath, "\"$value\"")) {
                                rendererValue = value
                            } else {
                                AlertDialogQueue.showDialog(
                                    context.getString(R.string.error),
                                    context.getString(
                                        R.string.failed_to_assign_value,
                                        value,
                                        rendererPath
                                    )
                                )
                            }
                        }
                    )
                }
            }

            // 2. Resolution (List or Slider)
            if (resolutionVariants.isNotEmpty()) {
                item {
                    val cleanTitle = resolutionPath.substringAfterLast("@@")
                    SingleSelectionDialog(
                        currentValue = if (resolutionValue in resolutionVariants) resolutionValue else resolutionVariants[0],
                        values = resolutionVariants,
                        title = cleanTitle,
                        icon = null,
                        onValueChange = { value ->
                            if (RPCSX.instance.settingsSet("", resolutionPath, "\"$value\"")) {
                                resolutionValue = value
                            } else {
                                AlertDialogQueue.showDialog(
                                    context.getString(R.string.error),
                                    context.getString(
                                        R.string.failed_to_assign_value,
                                        value,
                                        resolutionPath
                                    )
                                )
                            }
                        }
                    )
                }
            } else if (resolutionNode.has("min")) {
                item {
                    val cleanTitle = resolutionPath.substringAfterLast("@@")
                    SliderPreference(
                        value = resolutionSliderValue,
                        valueRange = resolutionMin..resolutionMax,
                        title = cleanTitle,
                        steps = maxOf(0, (resolutionMax - resolutionMin).toInt() - 1),
                        valueContent = { PreferenceValue(text = "${resolutionSliderValue.toInt()}%") },
                        onValueChange = { value ->
                            if (RPCSX.instance.settingsSet("", resolutionPath, value.toLong().toString())) {
                                resolutionSliderValue = value
                            } else {
                                AlertDialogQueue.showDialog(
                                    context.getString(R.string.error),
                                    context.getString(
                                        R.string.failed_to_assign_value,
                                        value.toString(),
                                        resolutionPath
                                    )
                                )
                            }
                        }
                    )
                }
            }

            // Resolution Scale (Video@@Resolution Scale)
            if (videoResolutionScaleNode.has("min")) item {
                SliderPreference(
                    value = videoResolutionScaleValue,
                    valueRange = videoResolutionScaleMin..videoResolutionScaleMax,
                    title = stringResource(R.string.resolution_scale),
                    steps = maxOf(0, (videoResolutionScaleMax - videoResolutionScaleMin).toInt() - 1),
                    valueContent = { PreferenceValue(text = "${videoResolutionScaleValue.toInt()}%") },
                    onValueChange = { value ->
                        if (RPCSX.instance.settingsSet("", VideoResolutionScalePath, value.toLong().toString())) {
                            videoResolutionScaleValue = value
                        } else {
                            AlertDialogQueue.showDialog(
                                context.getString(R.string.error),
                                context.getString(
                                    R.string.failed_to_assign_value,
                                    value.toString(),
                                    VideoResolutionScalePath
                                )
                            )
                        }
                    }
                )
            }

            // Output Scaling
            if (scalingVariants.isNotEmpty()) {
                item {
                    SingleSelectionDialog(
                        currentValue = if (scalingValue in scalingVariants) scalingValue else scalingVariants[0],
                        values = scalingVariants,
                        title = stringResource(R.string.output_scaling),
                        icon = null,
                        onValueChange = { value ->
                            if (RPCSX.instance.settingsSet("", OutputScalingPath, "\"$value\"")) {
                                scalingValue = value
                            } else {
                                AlertDialogQueue.showDialog(
                                    context.getString(R.string.error),
                                    context.getString(
                                        R.string.failed_to_assign_value,
                                        value,
                                        OutputScalingPath
                                    )
                                )
                            }
                        }
                    )
                }
            }

            // FSR Sharpening
            if (sharpeningNode.has("min")) item {
                SliderPreference(
                    value = sharpeningValue,
                    valueRange = sharpeningMin..sharpeningMax,
                    title = stringResource(R.string.fsr_sharpening),
                    subtitle = if (scalingValue == FsrScalingValue) null else stringResource(R.string.fsr_sharpening_requires_fsr),
                    enabled = scalingValue == FsrScalingValue,
                    steps = maxOf(0, (sharpeningMax - sharpeningMin).toInt() - 1),
                    valueContent = { PreferenceValue(text = "${sharpeningValue.toInt()}%") },
                    onValueChange = { value ->
                        if (RPCSX.instance.settingsSet("", FsrSharpeningPath, value.toLong().toString())) {
                            sharpeningValue = value
                        } else {
                            AlertDialogQueue.showDialog(
                                context.getString(R.string.error),
                                context.getString(
                                    R.string.failed_to_assign_value,
                                    value.toString(),
                                    FsrSharpeningPath
                                )
                            )
                        }
                    }
                )
            }
        }
    }
}

// Per-player key mapping screen: same remap flow the old single global
// screen had, but scoped to one of the independent per-slot bindings.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerControllerSettings(
    playerSlot: Int,
    modifier: Modifier = Modifier,
    navigateBack: () -> Unit,
    // When set, edits this title's private key mappings instead of the
    // global ones (used from the per-game settings screen).
    titleId: String? = null
) {
    val topBarScrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    Scaffold(
        modifier = Modifier
            .nestedScroll(topBarScrollBehavior.nestedScrollConnection)
            .then(modifier),
        topBar = {
            LargeTopAppBar(
                title = { Text(text = stringResource(R.string.player_slot, playerSlot + 1), fontWeight = FontWeight.Medium) },
                scrollBehavior = topBarScrollBehavior,
                navigationIcon = {
                    IconButton(
                        onClick = navigateBack
                    ) {
                        Icon(painter = painterResource(id = R.drawable.ic_keyboard_arrow_left), null)
                    }
                }
            )
        }
    ) { contentPadding ->
        val context = LocalContext.current
        val inputBindings = remember(playerSlot, titleId) {
            mutableStateMapOf<Int, Pair<Int, Int>>().apply {
                putAll(InputBindingPrefs.loadBindings(playerSlot, titleId))
            }
        }

        var showDialog by remember { mutableStateOf(false) }
        var currentInput by remember { mutableStateOf(-1) }
        var currentInputName by remember { mutableStateOf("") }
        val requester = remember { FocusRequester() }
        val noDeviceMessage = stringResource(R.string.automatic_mapping_no_device)

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
            contentPadding = PaddingValues(bottom = net.rpcsx.ui.navigation.LocalDockPadding.current)
        ) {
            item {
                Spacer(modifier = Modifier.height(16.dp))
            }

            item {
                val slot = GamepadRepository.slots[playerSlot]
                RegularPreference(
                    title = stringResource(R.string.connected_controllers),
                    leadingIcon = null,
                    value = {
                        PreferenceValue(slot?.deviceName ?: stringResource(R.string.controller_not_connected))
                    },
                    onClick = {}
                )
            }

            // Automatic mapping writes the global per-slot bindings, so it is
            // only offered when editing those (not a title's private mapping).
            if (titleId == null) item {
                RegularPreference(
                    title = stringResource(R.string.automatic_mapping),
                    leadingIcon = null,
                    onClick = {
                        val deviceId = GamepadRepository.slots[playerSlot]?.deviceId
                        val device = deviceId?.let { InputDevice.getDevice(it) }
                        if (device == null) {
                            Toast.makeText(context, noDeviceMessage, Toast.LENGTH_SHORT).show()
                        } else {
                            val matchedName = GamepadAutoMapper.applyAutomaticMapping(context, playerSlot, device)
                            inputBindings.clear()
                            inputBindings.putAll(InputBindingPrefs.loadBindings(playerSlot))
                            val message = matchedName?.let {
                                context.getString(R.string.automatic_mapping_applied, it)
                            } ?: context.getString(
                                R.string.automatic_mapping_defaults,
                                device.name ?: "?",
                            )
                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }

            item {
                HorizontalDivider()
            }

            item {
                PreferenceHeader(stringResource(R.string.key_mappings))
            }

            inputBindings.toList()
                .sortedBy { (_, value) ->
                    val name = InputBindingPrefs.rpcsxKeyCodeToString(value.first, value.second)
                    InputBindingPrefs.defaultBindings.values.indexOfFirst { defValue ->
                        InputBindingPrefs.rpcsxKeyCodeToString(
                            defValue.first,
                            defValue.second
                        ) == name
                    }
                }
                .forEach { binding ->
                    item {
                        RegularPreference(
                            title = InputBindingPrefs.rpcsxKeyCodeToString(
                                binding.second.first,
                                binding.second.second
                            ),
                            value = {
                                PreferenceValue(
                                    if (binding.first.toString().length > 4) stringResource(R.string.none)
                                    else KeyEvent.keyCodeToString(binding.first)
                                )
                            },
                            onClick = {
                                currentInput = binding.first
                                currentInputName = InputBindingPrefs.rpcsxKeyCodeToString(
                                    binding.second.first,
                                    binding.second.second
                                )
                                showDialog = true
                            }
                        )
                    }
                }
        }

        if (showDialog) {
            InputBindingDialog(
                onReset = {
                    InputBindingPrefs.defaultBindings.forEach {
                        if (InputBindingPrefs.rpcsxKeyCodeToString(
                                it.value.first,
                                it.value.second
                            ) == currentInputName
                        ) {
                            inputBindings[currentInput]?.let { value ->
                                inputBindings.remove(currentInput)
                                inputBindings[it.key] = value
                            }
                            InputBindingPrefs.saveBindings(playerSlot, inputBindings.toMap(), titleId)
                        }
                    }
                },
                onDismissRequest = { showDialog = false },
                modifier = Modifier
                    .onPreviewKeyEvent { keyEvent ->
                        if (keyEvent.type == KeyEventType.KeyDown) {
                            if (showDialog) {
                                val newKey = keyEvent.nativeKeyEvent.keyCode
                                val displacedValue = inputBindings[newKey]
                                val currentValue = inputBindings[currentInput]
                                if (displacedValue != null && currentValue != null) {
                                    inputBindings[currentInput] = displacedValue
                                }
                                if (currentValue != null) {
                                    if (displacedValue == null) {
                                        inputBindings.remove(currentInput)
                                    }
                                    inputBindings[newKey] = currentValue
                                }
                                InputBindingPrefs.saveBindings(playerSlot, inputBindings.toMap(), titleId)
                                showDialog = false
                                true
                            } else false
                        } else false
                    }
                    .focusRequester(requester)
                    .focusable()

            )

            LaunchedEffect(showDialog) {
                requester.requestFocus()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InputBindingDialog(
    modifier: Modifier = Modifier,
    onReset: () -> Unit = {},
    onDismissRequest: () -> Unit = {}
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.perform_input),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )

            Spacer(modifier = Modifier.height(10.dp))

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(75.dp)
            ) {
                ButtonMappingAnim()
            }

            Spacer(modifier = Modifier.height(10.dp))

            Button(
                onClick = onReset,
                modifier = Modifier.align(Alignment.End)
            ) {
                Text(stringResource(R.string.reset))
            }
        }
    }
}

@Composable
fun ButtonMappingAnim() {
    val infiniteTransition = rememberInfiniteTransition()

    val scaleX by infiniteTransition.animateFloat(
        initialValue = 1.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 568),
            repeatMode = RepeatMode.Reverse
        )
    )

    val scaleY by infiniteTransition.animateFloat(
        initialValue = 1.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 568),
            repeatMode = RepeatMode.Reverse
        )
    )

    Image(
        painter = painterResource(id = R.drawable.button_mapping),
        contentDescription = null,
        modifier = Modifier
            .graphicsLayer(
                scaleX = scaleX,
                scaleY = scaleY
            )
            .fillMaxSize()
    )
}

@Preview
@Composable
private fun SettingsScreenPreview() {
    ComposePreview {
//        SettingsScreen {}
    }
}
