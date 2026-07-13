package net.rpcsx.ui.games

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import net.rpcsx.GameDirectory
import net.rpcsx.GameDirectoryKind
import net.rpcsx.GameDirectoryRepository
import net.rpcsx.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameDirectoriesScreen(
    navigateBack: () -> Unit
) {
    val context = LocalContext.current
    val topBarScrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()

    val gameDirLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}
            GameDirectoryRepository.add(uri, GameDirectoryKind.Games)
            GameDirectoryRepository.scanGameDirectory(context, uri)
        }
    }

    val isoDirLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}
            GameDirectoryRepository.add(uri, GameDirectoryKind.Iso)
            GameDirectoryRepository.scanIsoDirectory(context, uri)
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(topBarScrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.manage_directories),
                        fontWeight = FontWeight.Medium
                    )
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
        val directories = GameDirectoryRepository.directories

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = net.rpcsx.ui.navigation.LocalDockPadding.current)
        ) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    androidx.compose.material3.Button(
                        modifier = Modifier.weight(1f),
                        onClick = { gameDirLauncher.launch(null) }
                    ) {
                        Text("+ Carpeta de Juegos")
                    }
                    androidx.compose.material3.OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = { isoDirLauncher.launch(null) }
                    ) {
                        Text("+ Carpeta ISOs")
                    }
                }
            }

            if (directories.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.no_directories),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                items(directories, key = { it.uri }) { dir ->
                    GameDirectoryRow(
                        dir = dir,
                        onRescan = {
                            val uri = Uri.parse(dir.uri)
                            when (dir.kind) {
                                GameDirectoryKind.Games -> GameDirectoryRepository.scanGameDirectory(context, uri)
                                GameDirectoryKind.Iso -> GameDirectoryRepository.scanIsoDirectory(context, uri)
                            }
                        },
                        onRemove = {
                            GameDirectoryRepository.removeAndForget(context, dir)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun GameDirectoryRow(
    dir: GameDirectory,
    onRescan: () -> Unit,
    onRemove: () -> Unit
) {
    val context = LocalContext.current
    val displayName = remember(dir.uri) {
        runCatching { DocumentFile.fromTreeUri(context, Uri.parse(dir.uri))?.name }
            .getOrNull() ?: Uri.parse(dir.uri).lastPathSegment ?: dir.uri
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_folder),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 16.dp)
            ) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(
                        when (dir.kind) {
                            GameDirectoryKind.Games -> R.string.directory_kind_games
                            GameDirectoryKind.Iso -> R.string.directory_kind_iso
                        }
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onRescan) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_refresh),
                    contentDescription = stringResource(R.string.rescan_directory),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            IconButton(onClick = onRemove) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_delete),
                    contentDescription = stringResource(R.string.remove_directory),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
