package net.rpcsx.ui.channels

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import net.rpcsx.R
import net.rpcsx.RPCSX
import net.rpcsx.dialogs.AlertDialogQueue
import net.rpcsx.ui.settings.components.core.DeletableListItem
import net.rpcsx.ui.settings.components.core.PreferenceSubtitle
import net.rpcsx.ui.settings.components.preference.RegularPreference
import net.rpcsx.utils.FileUtil
import net.rpcsx.utils.GeneralSettings
import net.rpcsx.utils.RpcsxUpdater
import java.io.File

const val DefaultGpuDriverChannel = "https://github.com/K11MCH1/AdrenoToolsDrivers"
const val DevUiChannel = "https://github.com/boludoz/rpcsx-ui-android"
const val ReleaseUiChannel = "https://github.com/boludoz/rpcsx-ui-android"
const val ReleaseRpcsxChannel = "https://github.com/boludoz/rpcsx"
const val DevRpcsxChannel = "https://github.com/boludoz/rpcsx"

fun channelToUiText(channel: String, releaseRepo: String, devRepo: String): String {
    if (channel == releaseRepo) return "Release"
    if (channel == devRepo) return "Development"
    return channel
}

fun uiTextToChannel(channel: String, releaseRepo: String, devRepo: String): String {
    if (channel == "Release") return releaseRepo
    if (channel == "Development") return devRepo
    return channel
}

fun channelsToUiText(list: List<String>, releaseRepo: String, devRepo: String): List<String> {
    return list.map { channelToUiText(it, releaseRepo, devRepo) }
}

fun uiTextToChannels(list: List<String>, releaseRepo: String, devRepo: String): List<String> {
    return list.map { uiTextToChannel(it, releaseRepo, devRepo) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateChannelsScreen(
    navigateBack: () -> Unit,
    navigateTo: (id: String) -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE) }
    val snackbarHostState = remember { SnackbarHostState() }
    val topBarScrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()

    var activeCorePath by remember { mutableStateOf(GeneralSettings["rpcsx_library"] as? String) }
    var activeCoreArch by remember { mutableStateOf(GeneralSettings["rpcsx_installed_arch"] as? String) }
    var downloadArch by remember { mutableStateOf(RpcsxUpdater.getArch()) }
    var rpcsxChannel by remember { mutableStateOf(prefs.getString("rpcsx_channel", ReleaseRpcsxChannel)!!) }

    var downloadedVersions by remember { mutableStateOf(RpcsxUpdater.getDownloadedVersions(context)) }
    var expandedArch by remember { mutableStateOf(false) }

    val installCustomLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val targetArch = RpcsxUpdater.getArch()
            val target = File(context.filesDir.canonicalPath, "librpcsx-android_${targetArch}_custom-${System.currentTimeMillis()}.so")
            if (target.exists()) {
                target.delete()
            }

            try {
                FileUtil.saveFile(context, uri, target.path)
                val libVersion = RPCSX.instance.getLibraryVersion(target.path)
                if (libVersion != null) {
                    RpcsxUpdater.installUpdate(context, target)
                    activeCorePath = target.path
                    activeCoreArch = targetArch
                    downloadedVersions = RpcsxUpdater.getDownloadedVersions(context)
                } else {
                    target.delete()
                    Toast.makeText(context, "Invalid library or failed to read version", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Installation failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.download_channels), fontWeight = FontWeight.Medium) },
                scrollBehavior = topBarScrollBehavior,
                navigationIcon = {
                    IconButton(onClick = navigateBack) {
                        Icon(painter = painterResource(id = R.drawable.ic_keyboard_arrow_left), null)
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Update Channels",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            // UI update channel
            item {
                RegularPreference(
                    title = stringResource(R.string.ui_update_channel),
                    leadingIcon = null,
                    subtitle = { PreferenceSubtitle(text = channelToUiText(prefs.getString("ui_channel", ReleaseUiChannel)!!, ReleaseUiChannel, DevUiChannel)) },
                    onClick = {
                        navigateTo("ui_channels")
                    }
                )
            }

            // GPU Driver Update Channel
            item {
                RegularPreference(
                    title = stringResource(R.string.driver_download_channel),
                    leadingIcon = null,
                    subtitle = { PreferenceSubtitle(text = prefs.getString("gpu_driver_channel", DefaultGpuDriverChannel)!!) },
                    onClick = {
                        navigateTo("gpu_driver_channels")
                    }
                )
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Core Update Settings",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            // RPCSX Download Channel (repo list)
            item {
                RegularPreference(
                    title = stringResource(R.string.rpcsx_download_channel),
                    leadingIcon = null,
                    subtitle = { PreferenceSubtitle(text = channelToUiText(rpcsxChannel, ReleaseRpcsxChannel, DevRpcsxChannel)) },
                    onClick = {
                        navigateTo("rpcsx_repositories")
                    }
                )
            }

            // Target Architecture Selection (dropdown)
            item {
                Box {
                    RegularPreference(
                        title = "Download Architecture",
                        leadingIcon = null,
                        subtitle = { PreferenceSubtitle(text = downloadArch) },
                        onClick = { expandedArch = true }
                    )

                    DropdownMenu(
                        expanded = expandedArch,
                        onDismissRequest = { expandedArch = false }
                    ) {
                        listOf(
                            "armv8-a",
                            "armv8.1-a",
                            "armv8.2-a",
                            "armv8.4-a",
                            "armv8.5-a",
                            "armv9-a",
                            "armv9.1-a"
                        ).forEach { arch ->
                            DropdownMenuItem(
                                text = { Text(arch) },
                                onClick = {
                                    RpcsxUpdater.setArch(arch)
                                    downloadArch = arch
                                    expandedArch = false
                                }
                            )
                        }
                    }
                }
            }

            // Active Core Status Card
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Currently Active Core",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (activeCorePath != null) {
                                "Version: ${RpcsxUpdater.getCurrentVersion() ?: "Unknown"}\nPath: $activeCorePath"
                            } else {
                                "No active core installed"
                            },
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            // Install Custom Core option
            item {
                Button(
                    onClick = { installCustomLauncher.launch("*/*") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_add),
                        contentDescription = null,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text("Install custom RPCSX library")
                }
            }

            // Downloaded Cores section
            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Downloaded Cores",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            if (downloadedVersions.isEmpty()) {
                item {
                    Text(
                        text = "No downloaded cores found.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            } else {
                items(downloadedVersions) { version ->
                    val isActive = activeCorePath == version.filePath
                    DeletableListItem(
                        onDelete = {
                            RpcsxUpdater.deleteVersion(context, version)
                            activeCorePath = GeneralSettings["rpcsx_library"] as? String
                            activeCoreArch = GeneralSettings["rpcsx_installed_arch"] as? String
                            downloadedVersions = RpcsxUpdater.getDownloadedVersions(context)
                        }
                    ) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            onClick = {
                                if (!isActive) {
                                    val file = File(version.filePath)
                                    RpcsxUpdater.installUpdate(context, file)
                                    activeCorePath = version.filePath
                                    activeCoreArch = version.arch
                                }
                            },
                            colors = CardDefaults.cardColors(
                                containerColor = if (isActive) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                }
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = version.version,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "Architecture: ${version.arch}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        RpcsxUpdater.deleteVersion(context, version)
                                        activeCorePath = GeneralSettings["rpcsx_library"] as? String
                                        activeCoreArch = GeneralSettings["rpcsx_installed_arch"] as? String
                                        downloadedVersions = RpcsxUpdater.getDownloadedVersions(context)
                                    }
                                ) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_delete),
                                        contentDescription = "Delete build",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Wipe Downloads Button
            item {
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = {
                        RpcsxUpdater.wipeDownloads(context)
                        activeCorePath = null
                        activeCoreArch = null
                        downloadedVersions = emptyList()
                        Toast.makeText(context, "All downloaded cores wiped", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Wipe Downloads", color = MaterialTheme.colorScheme.onError)
                }
            }
        }
    }
}
