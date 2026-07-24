package net.rpcsx.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import net.rpcsx.utils.CommunityConfigFetch
import net.rpcsx.utils.FileUtil
import net.rpcsx.utils.InputBindingPrefs
import net.rpcsx.utils.PerGameConfigRepository
import org.json.JSONObject

/** Fallback identity for a game entry that somehow has no titleId yet
 *  (ParamSfoParser couldn't read its PARAM.SFO). Matches the layout the
 *  engine itself uses: hdd0 games live in .../game/<TITLE_ID>/, disc/ISO
 *  games in .../games/<TITLE_ID>/ (path may point at <TITLE_ID>.iso directly),
 *  so the path's last segment is usually the title id anyway. */
private fun titleIdForPath(path: String): String {
    var id = path.trimEnd('/').substringAfterLast('/')
    if (id.endsWith(".iso", ignoreCase = true)) {
        id = id.dropLast(4)
    }
    return id.uppercase()
}

/**
 * Per-game settings: a one-tap RPCS3 community config, graphics and advanced
 * overrides stored in the core's own per-title custom config
 * (config/custom_configs/config_<TITLE_ID>.yml, applied at boot via
 * cfg_mode::custom), per-game key mappings, cache clearing and reset to
 * global defaults.
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
    val scope = rememberCoroutineScope()
    val game = remember(gamePath) { GameRepository.find(gamePath) }
    val titleId = remember(game, gamePath) {
        game?.info?.titleId?.value?.takeIf { it.isNotEmpty() } ?: titleIdForPath(gamePath)
    }
    val gameName = remember(gamePath) {
        game?.info?.name?.value ?: titleId
    }

    var reloadKey by remember { mutableStateOf(0) }
    var communityConfigBusy by remember { mutableStateOf(false) }
    val hasCustomConfig = remember(titleId, reloadKey) { PerGameConfigRepository.hasCustomConfig(titleId) }

    val rootSettingsJson = remember(reloadKey) {
        try {
            JSONObject(RPCSX.instance.settingsGet("", ""))
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

            item(key = "community_config") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        tonalElevation = 2.dp
                    ) {
                        Text(
                            text = if (hasCustomConfig) {
                                stringResource(R.string.custom_config_active_note)
                            } else {
                                stringResource(R.string.custom_config_global_note)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(20.dp)
                        )
                    }

                    Button(
                        onClick = {
                            scope.launch {
                                communityConfigBusy = true
                                val fetch = withContext(Dispatchers.IO) {
                                    PerGameConfigRepository.fetchCommunityConfig(titleId)
                                }
                                communityConfigBusy = false
                                when (fetch) {
                                    is CommunityConfigFetch.Found -> {
                                        AlertDialogQueue.showDialog(
                                            title = context.getString(R.string.apply_community_config_title),
                                            message = context.getString(R.string.apply_community_config_message, fetch.yaml.trim()),
                                            confirmText = context.getString(R.string.apply),
                                            onConfirm = {
                                                scope.launch {
                                                    communityConfigBusy = true
                                                    val ok = withContext(Dispatchers.IO) {
                                                        PerGameConfigRepository.importConfig(titleId, fetch.yaml)
                                                    }
                                                    communityConfigBusy = false
                                                    if (ok) reloadKey++
                                                    Toast.makeText(
                                                        context,
                                                        context.getString(
                                                            if (ok) R.string.community_config_applied
                                                            else R.string.community_config_rejected
                                                        ),
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                }
                                            }
                                        )
                                    }
                                    is CommunityConfigFetch.NotFound ->
                                        Toast.makeText(context, context.getString(R.string.community_config_not_found), Toast.LENGTH_SHORT).show()
                                    is CommunityConfigFetch.Error ->
                                        Toast.makeText(context, context.getString(R.string.community_config_failed, fetch.message), Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        enabled = !communityConfigBusy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (communityConfigBusy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Icon(painter = painterResource(R.drawable.ic_cloud_download), contentDescription = null)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.use_community_config))
                    }
                }
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
                                PerGameConfigRepository.deleteCustomConfig(titleId)
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

/** Enum-typed setting rendered from the merged per-title schema node
 *  ("value" is the effective value, "overridden" marks per-title ones).
 *  Long-press reverts to the global value. */
@Composable
private fun GameEnumSetting(titleId: String, path: String, title: String? = null) {
    val node = remember(titleId, path) { PerGameConfigRepository.node(titleId, path) }
    val variants = remember(node) {
        if (node.has("variants")) {
            val arr = node.getJSONArray("variants")
            (0 until arr.length()).map { arr.getString(it) }
        } else emptyList()
    }
    if (variants.isEmpty()) return

    var isOverridden by remember { mutableStateOf(node.optBoolean("overridden", false)) }
    var value by remember { mutableStateOf(node.optString("value")) }

    SingleSelectionDialog(
        currentValue = if (value in variants) value else variants[0],
        values = variants,
        icon = null,
        title = (title ?: path.substringAfterLast("@@")) + if (isOverridden) " *" else "",
        onValueChange = { newValue ->
            if (PerGameConfigRepository.set(titleId, path, newValue)) {
                value = newValue
                isOverridden = true
            }
        },
        onLongClick = {
            PerGameConfigRepository.remove(titleId, path)
            value = PerGameConfigRepository.node("", path).optString("value")
            isOverridden = false
        }
    )
}

/** Numeric setting (int/uint) with min/max from the merged schema node. */
@Composable
private fun GameSliderSetting(titleId: String, path: String, unit: String = "") {
    val node = remember(titleId, path) { PerGameConfigRepository.node(titleId, path) }
    val min = node.optString("min").toFloatOrNull() ?: return
    val max = node.optString("max").toFloatOrNull() ?: return
    if (min >= max) return

    var isOverridden by remember { mutableStateOf(node.optBoolean("overridden", false)) }
    var value by remember {
        mutableFloatStateOf(node.optString("value").toFloatOrNull() ?: min)
    }

    SliderPreference(
        value = value,
        valueRange = min..max,
        steps = (max - min).toInt() - 1,
        title = path.substringAfterLast("@@") + if (isOverridden) " *" else "",
        onValueChange = { newValue ->
            if (PerGameConfigRepository.set(titleId, path, newValue.toLong())) {
                value = newValue.toLong().toFloat()
                isOverridden = true
            }
        },
        valueContent = { PreferenceValue(text = "${value.toInt()}$unit") },
        onLongClick = {
            PerGameConfigRepository.remove(titleId, path)
            value = PerGameConfigRepository.node("", path).optString("value").toFloatOrNull() ?: min
            isOverridden = false
        }
    )
}
