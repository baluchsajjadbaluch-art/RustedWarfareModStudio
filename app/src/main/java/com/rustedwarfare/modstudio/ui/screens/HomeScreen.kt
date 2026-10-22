package com.rustedwarfare.modstudio.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.BuildCircle
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.rustedwarfare.modstudio.AppGraph
import com.rustedwarfare.modstudio.R
import com.rustedwarfare.modstudio.Routes
import com.rustedwarfare.modstudio.core.SafUtils
import com.rustedwarfare.modstudio.ui.rememberOpenDocPicker
import com.rustedwarfare.modstudio.ui.rememberTreePicker
import com.rustedwarfare.modstudio.ui.toast
import kotlinx.coroutines.launch

private data class Feature(val route: String, val titleRes: Int, val descRes: Int, val icon: androidx.compose.ui.graphics.vector.ImageVector)

@Composable
fun HomeScreen(graph: AppGraph, nav: NavController) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val recents = graph.recents.recents.value

    val openArchive = rememberOpenDocPicker(arrayOf("*/*")) { uri ->
        scope.launch {
            graph.archive.open(uri)
                .onSuccess { nav.navigate(Routes.ARCHIVE) }
                .onFailure { toast(context, context.getString(R.string.common_error, it.message ?: "")) }
        }
    }
    val openFolder = rememberTreePicker { uri ->
        scope.launch {
            graph.project.openTree(uri)
                .onSuccess { nav.navigate(Routes.PROJECTS) }
                .onFailure { toast(context, context.getString(R.string.common_error, it.message ?: "")) }
        }
    }

    val features = listOf(
        Feature(Routes.ARCHIVE, R.string.feature_archive, R.string.feature_archive_desc, Icons.Filled.Archive),
        Feature(Routes.EDITOR, R.string.feature_editor, R.string.feature_editor_desc, Icons.Filled.Description),
        Feature(Routes.VALIDATOR, R.string.feature_validator, R.string.feature_validator_desc, Icons.Filled.Verified),
        Feature(Routes.IMAGE, R.string.feature_image, R.string.feature_image_desc, Icons.Filled.Image),
        Feature(Routes.AUDIO, R.string.feature_audio, R.string.feature_audio_desc, Icons.Filled.AudioFile),
        Feature(Routes.PROJECTS, R.string.feature_project, R.string.feature_project_desc, Icons.Filled.Folder),
        Feature(Routes.WIZARD, R.string.feature_wizard, R.string.feature_wizard_desc, Icons.Filled.CreateNewFolder),
        Feature(Routes.EXPORT, R.string.feature_export, R.string.feature_export_desc, Icons.Filled.IosShare)
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column {
                Text(stringResource(R.string.home_title), style = MaterialTheme.typography.headlineSmall)
                Text(
                    stringResource(R.string.home_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Card(modifier = Modifier.weight(1f).clickable { openFolder() }) {
                    Column(Modifier.padding(12.dp)) {
                        Icon(Icons.Filled.FolderOpen, contentDescription = null)
                        Text(stringResource(R.string.home_open_folder), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Card(modifier = Modifier.weight(1f).clickable { openArchive() }) {
                    Column(Modifier.padding(12.dp)) {
                        Icon(Icons.Filled.Archive, contentDescription = null)
                        Text(stringResource(R.string.home_open_archive), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        item {
            Text(
                stringResource(
                    when {
                        graph.archive.current != null -> R.string.home_workspace_archive
                        graph.project.root != null -> R.string.home_workspace_folder
                        else -> R.string.home_workspace_none
                    }
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        item {
            Text(stringResource(R.string.home_features), style = MaterialTheme.typography.titleMedium)
        }
        items(features.chunked(2)) { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (f in pair) {
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { nav.navigate(f.route) }
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Icon(f.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.height(6.dp))
                            Text(stringResource(f.titleRes), style = MaterialTheme.typography.titleSmall)
                            Text(
                                stringResource(f.descRes),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 3
                            )
                        }
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.home_recent), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { nav.navigate(Routes.SETTINGS) }) {
                    Icon(Icons.Filled.Settings, contentDescription = null)
                    Text(stringResource(R.string.settings_title))
                }
            }
        }
        if (recents.isEmpty()) {
            item { Text(stringResource(R.string.home_no_recent), style = MaterialTheme.typography.bodySmall) }
        } else {
            items(recents) { r ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            scope.launch {
                                if (SafUtils.hasArchiveExt(r.name)) {
                                    graph.archive.open(android.net.Uri.parse(r.uri))
                                    nav.navigate(Routes.ARCHIVE)
                                } else {
                                    graph.project.openTree(android.net.Uri.parse(r.uri))
                                    nav.navigate(Routes.PROJECTS)
                                }
                            }
                        }
                ) {
                    Row(
                        Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (SafUtils.hasArchiveExt(r.name)) Icons.Filled.Archive else Icons.Filled.Folder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary
                        )
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(r.name, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.size(24.dp)) }
    }
}
