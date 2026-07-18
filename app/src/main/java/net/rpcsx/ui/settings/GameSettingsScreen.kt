package net.rpcsx.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.rpcsx.GameRepository
import net.rpcsx.MaxGamepadPlayers
import net.rpcsx.R
import net.rpcsx.RPCSX
import net.rpcsx.dialogs.AlertDialogQueue
import net.rpcsx.ui.settings.components.core.PreferenceHeader
import net.rpcsx.ui.settings.components.core.PreferenceValue
import net.rpcsx.ui.settings.components.preference.RegularPreference
import net.rpcsx.ui.settings.components.preference.SingleSelectionDialog
import net.rpcsx.ui.settings.components.preference.SliderPreference
import net.rpcsx.utils.FileUtil
import net.rpcsx.utils.GameConfig
import net.rpcsx.utils.InputBindingPrefs
import org.json.JSONObject

/**
 * Per-game settings: graphics and advanced overrides stored in the title's
 * custom config (config_<TITLE_ID>.yml, applied by the emulator at boot),
 * per-game key mappings, cache clearing and reset to global defaults.
 *
 * Long-pressing an overridden value reverts it to the global setting.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameSettingsScreen(
    gamePath: String,
    modifier: Modifier = Modifier,
    navigateBack: () -> Unit,
    navigateTo: (path: String) -> Unit
) {
    val context = LocalContext.current
    val titleId = remember(gamePath) { GameConfig.titleIdForPath(gamePath) }
    val gameName = remember(gamePath) {
        GameRepository.find(gamePath)?.info?.name?.value ?: titleId
    }

    val rootSettingsJson = remember {
        try {
            JSONObject(RPCSX.instance.settingsGet(""))
        } catch (e: Exception) {
            JSONObject()
        }
    }

    val rendererPath = remember(rootSettingsJson) {
        findVideoSettingPath(rootSettingsJson, "renderer", "Renderer")
    }
    val resolutionPath = remember(rootSettingsJson) {
        findVideoSettingPath(rootSettingsJson, "resolution", "Resolution", ignoreKeyword = "scaling")
    }

    val topBarScrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    Scaffold(
        modifier = Modifier
            .nestedScroll(topBarScrollBehavior.nestedScrollConnection)
            .then(modifier),
        topBar = {
            LargeTopAppBar(
                title = {
                    Text(text = gameName, fontWeight = FontWeight.Medium)
                },
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
        ) {
            item { Spacer(modifier = Modifier.height(16.dp)) }

            item {
                RegularPreference(
                    title = titleId,
                    leadingIcon = null,
                    value = { PreferenceValue(stringResource(R.string.per_game_overrides_note)) },
                    onClick = {}
                )
            }

            item { HorizontalDivider() }

            item { PreferenceHeader(stringResource(R.string.graphics)) }

            item { GameEnumSetting(titleId, rendererPath) }
            item { GameEnumSetting(titleId, resolutionPath) }
            item { GameSliderSetting(titleId, VideoResolutionScalePath, "%") }
            item { GameEnumSetting(titleId, OutputScalingPath) }
            item { GameSliderSetting(titleId, FsrSharpeningPath, "%") }

            item { HorizontalDivider() }

            item { PreferenceHeader(stringResource(R.string.advanced_settings)) }

            item {
                RegularPreference(
                    title = stringResource(R.string.advanced_settings),
                    leadingIcon = null,
                    onClick = { navigateTo("game_adv/$titleId@@$") }
                )
            }

            item { HorizontalDivider() }

            item { PreferenceHeader(stringResource(R.string.controls)) }

            items(MaxGamepadPlayers) { playerIndex ->
                RegularPreference(
                    title = stringResource(R.string.player_slot, playerIndex + 1),
                    leadingIcon = null,
                    value = {
                        if (InputBindingPrefs.hasTitleBindings(titleId, playerIndex)) {
                            PreferenceValue(stringResource(R.string.custom_mapping))
                        }
                    },
                    onClick = { navigateTo("game_controls/$titleId/$playerIndex") }
                )
            }

            item { HorizontalDivider() }

            item {
                RegularPreference(
                    title = stringResource(R.string.delete_cache),
                    leadingIcon = null,
                    onClick = {
                        AlertDialogQueue.showDialog(
                            title = context.getString(R.string.delete_cache),
                            message = context.getString(R.string.delete_cache_desc),
                            onConfirm = {
                                FileUtil.deleteCache(context, titleId) { success ->
                                    val message = if (success) {
                                        context.getString(R.string.cache_deleted)
                                    } else {
                                        context.getString(R.string.failed_to_delete_game_cache)
                                    }
                                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                    }
                )
            }

            item {
                RegularPreference(
                    title = stringResource(R.string.reset_game_settings),
                    leadingIcon = null,
                    value = { PreferenceValue(stringResource(R.string.reset_game_settings_desc)) },
                    onClick = {
                        AlertDialogQueue.showDialog(
                            title = context.getString(R.string.reset_game_settings),
                            message = context.getString(R.string.ask_if_reset_game, gameName),
                            onConfirm = {
                                GameConfig.reset(titleId)
                                InputBindingPrefs.clearTitleBindings(titleId)
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.reset_game_settings_done),
                                    Toast.LENGTH_SHORT
                                ).show()
                                navigateBack()
                            }
                        )
                    }
                )
            }
        }
    }
}

/** Enum-typed setting rendered against the global schema with this game's
 *  override applied. Long-press reverts to the global value. */
@Composable
private fun GameEnumSetting(titleId: String, path: String, title: String? = null) {
    val node = remember(path) {
        try {
            JSONObject(RPCSX.instance.settingsGet(path))
        } catch (e: Exception) {
            JSONObject()
        }
    }
    val variants = remember(node) {
        if (node.has("variants")) {
            val arr = node.getJSONArray("variants")
            (0 until arr.length()).map { arr.getString(it) }
        } else emptyList()
    }
    if (variants.isEmpty()) return

    val globalValue = node.optString("value")
    var isOverridden by remember { mutableStateOf(GameConfig.get(titleId, path) != null) }
    var value by remember {
        mutableStateOf((GameConfig.get(titleId, path) as? String) ?: globalValue)
    }

    SingleSelectionDialog(
        currentValue = if (value in variants) value else variants[0],
        values = variants,
        icon = null,
        title = (title ?: path.substringAfterLast("@@")) + if (isOverridden) " *" else "",
        onValueChange = { newValue ->
            if (GameConfig.set(titleId, path, newValue)) {
                value = newValue
                isOverridden = true
            }
        },
        onLongClick = {
            GameConfig.remove(titleId, path)
            value = globalValue
            isOverridden = false
        }
    )
}

/** Numeric setting (int/uint) with min/max from the global schema. */
@Composable
private fun GameSliderSetting(titleId: String, path: String, unit: String = "") {
    val node = remember(path) {
        try {
            JSONObject(RPCSX.instance.settingsGet(path))
        } catch (e: Exception) {
            JSONObject()
        }
    }
    val min = node.optString("min").toFloatOrNull() ?: return
    val max = node.optString("max").toFloatOrNull() ?: return
    if (min >= max) return

    val globalValue = node.optString("value").toFloatOrNull() ?: min
    var isOverridden by remember { mutableStateOf(GameConfig.get(titleId, path) != null) }
    var value by remember {
        mutableFloatStateOf(
            (GameConfig.get(titleId, path) as? Number)?.toFloat() ?: globalValue
        )
    }

    SliderPreference(
        value = value,
        valueRange = min..max,
        steps = (max - min).toInt() - 1,
        title = path.substringAfterLast("@@") + if (isOverridden) " *" else "",
        onValueChange = { newValue ->
            if (GameConfig.set(titleId, path, newValue.toLong())) {
                value = newValue.toLong().toFloat()
                isOverridden = true
            }
        },
        valueContent = { PreferenceValue(text = "${value.toInt()}$unit") },
        onLongClick = {
            GameConfig.remove(titleId, path)
            value = globalValue
            isOverridden = false
        }
    )
}
