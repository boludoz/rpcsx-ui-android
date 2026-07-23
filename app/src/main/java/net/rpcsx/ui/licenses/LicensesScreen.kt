package net.rpcsx.ui.licenses

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RichTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.rpcsx.R
import net.rpcsx.dialogs.AlertDialogQueue
import net.rpcsx.utils.LicenseEntry
import net.rpcsx.utils.LicenseManager
import net.rpcsx.utils.LicenseType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LicenseItem(entry: LicenseEntry, onDelete: () -> Unit) {
    // isPersistent keeps the tooltip open (instead of auto-dismissing after
    // a moment) so the delete action inside it is actually tappable.
    val tooltipState = rememberTooltipState(isPersistent = true)
    val typeLabel = if (entry.type == LicenseType.Rap) "RAP" else "EDAT"
    val displayName = entry.gameName ?: entry.titleId ?: stringResource(R.string.license_unassociated)

    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(),
        state = tooltipState,
        tooltip = {
            RichTooltip(
                title = { Text(displayName) },
                action = {
                    TextButton(onClick = {
                        tooltipState.dismiss()
                        onDelete()
                    }) {
                        Text(stringResource(R.string.delete))
                    }
                }
            ) {
                Column {
                    Text(stringResource(R.string.license_info_type, typeLabel))
                    Text(stringResource(R.string.license_info_content_id, entry.contentId))
                    if (entry.titleId != null) {
                        Text(stringResource(R.string.license_info_title_id, entry.titleId))
                    }
                }
            }
        }
    ) {
        // TooltipBox's anchor content already opens the tooltip on
        // long-press (touch) or hover (pointer) - no extra gesture wiring
        // needed here.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayName,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = entry.contentId,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = typeLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesScreen(navigateBack: () -> Unit) {
    val context = LocalContext.current
    var licenses by remember { mutableStateOf(LicenseManager.list()) }

    Scaffold(
        modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.licenses)) },
                navigationIcon = {
                    IconButton(onClick = navigateBack) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_keyboard_arrow_left),
                            contentDescription = null
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        if (licenses.isEmpty()) {
            Box(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    stringResource(R.string.no_licenses_installed),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(innerPadding),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(licenses, key = { it.file.absolutePath }) { entry ->
                    LicenseItem(
                        entry = entry,
                        onDelete = {
                            AlertDialogQueue.showDialog(
                                title = context.getString(R.string.delete_license),
                                message = context.getString(R.string.ask_delete_license, entry.contentId),
                                confirmText = context.getString(R.string.delete),
                                onConfirm = {
                                    LicenseManager.delete(entry)
                                    licenses = LicenseManager.list()
                                }
                            )
                        }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}
