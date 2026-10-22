package com.rustedwarfare.modstudio.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.rustedwarfare.modstudio.AppGraph
import com.rustedwarfare.modstudio.R
import com.rustedwarfare.modstudio.Routes

@Composable
fun ToolsScreen(graph: AppGraph, nav: NavController) {
    ScreenScaffold(
        title = stringResource(R.string.nav_more),
        onBack = null,
        content = { pad ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(pad)
                    .verticalScroll(rememberScrollState())
            ) {
                ToolRow(Icons.Filled.Verified, stringResource(R.string.feature_validator), stringResource(R.string.feature_validator_desc)) { nav.navigate(Routes.VALIDATOR) }
                HorizontalDivider()
                ToolRow(Icons.Filled.Image, stringResource(R.string.feature_image), stringResource(R.string.feature_image_desc)) { nav.navigate(Routes.IMAGE) }
                HorizontalDivider()
                ToolRow(Icons.Filled.AudioFile, stringResource(R.string.feature_audio), stringResource(R.string.feature_audio_desc)) { nav.navigate(Routes.AUDIO) }
                HorizontalDivider()
                ToolRow(Icons.Filled.CreateNewFolder, stringResource(R.string.feature_wizard), stringResource(R.string.feature_wizard_desc)) { nav.navigate(Routes.WIZARD) }
                HorizontalDivider()
                ToolRow(Icons.Filled.IosShare, stringResource(R.string.feature_export), stringResource(R.string.feature_export_desc)) { nav.navigate(Routes.EXPORT) }
                HorizontalDivider()
                ToolRow(Icons.Filled.Description, stringResource(R.string.feature_editor), stringResource(R.string.feature_editor_desc)) { nav.navigate(Routes.EDITOR) }
                HorizontalDivider()
                ToolRow(Icons.Filled.Archive, stringResource(R.string.feature_archive), stringResource(R.string.feature_archive_desc)) { nav.navigate(Routes.ARCHIVE) }
                HorizontalDivider()
                ToolRow(Icons.Filled.Settings, stringResource(R.string.settings_title), stringResource(R.string.settings_locale_note)) { nav.navigate(Routes.SETTINGS) }
                Spacer(Modifier.size(24.dp))
            }
        }
    )
}

@Composable
private fun ToolRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    desc: String,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxSize()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.padding(start = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.weight(1f))
    }
}
