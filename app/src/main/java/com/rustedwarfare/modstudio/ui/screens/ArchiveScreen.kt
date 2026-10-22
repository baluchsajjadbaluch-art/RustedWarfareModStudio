package com.rustedwarfare.modstudio.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.rustedwarfare.modstudio.AppGraph
import com.rustedwarfare.modstudio.R
import com.rustedwarfare.modstudio.Routes
import com.rustedwarfare.modstudio.core.SafUtils
import com.rustedwarfare.modstudio.core.ZipPaths
import com.rustedwarfare.modstudio.ui.ConfirmDialog
import com.rustedwarfare.modstudio.ui.BusyOverlay
import com.rustedwarfare.modstudio.ui.InputDialog
import com.rustedwarfare.modstudio.ui.formatBytes
import com.rustedwarfare.modstudio.ui.rememberOpenDocPicker
import com.rustedwarfare.modstudio.ui.rememberOpenMultiPicker
import com.rustedwarfare.modstudio.ui.rememberTreePicker
import com.rustedwarfare.modstudio.ui.toast
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchiveScreen(graph: AppGraph, nav: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val archive = graph.archive
    val ctx = archive.current

    var selectionMode by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<String>() }
    var renameTarget by remember { mutableStateOf<String?>(null) }
    var previewText by remember { mutableStateOf<Pair<String, String>?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var addFilesTarget by remember { mutableStateOf(false) }
    var pendingAddUris by remember { mutableStateOf<List<android.net.Uri>>(emptyList()) }
    var extractAll by remember { mutableStateOf(false) }
    var extractSelected by remember { mutableStateOf(false) }

    val openDoc = rememberOpenDocPicker(arrayOf("*/*")) { uri ->
        scope.launch {
            graph.archive.open(uri)
                .onFailure { toast(context, context.getString(R.string.common_error, it.message ?: "")) }
        }
    }
    val treePicker = rememberTreePicker { uri ->
        val wanted = if (extractSelected) selected.toSet() else emptySet()
        scope.launch {
            extractAll = false
            extractSelected = false
            archive.extractEntries(wanted, uri)
                .onFailure { toast(context, context.getString(R.string.common_error, it.message ?: "")) }
        }
    }
    val multiAdd = rememberOpenMultiPicker(arrayOf("*/*")) { uris ->
        pendingAddUris = uris
        addFilesTarget = true
    }

    StatusToast(archive.status) { archive.clearStatus() }

    if (archive.busy) {
        BusyOverlay(stringResource(R.string.common_loading), archive.progress) { archive.requestCancel() }
    }

    ScreenScaffold(
        title = stringResource(R.string.archive_title),
        onBack = null,
        actions = {
            IconButton(onClick = { openDoc() }) {
                Icon(Icons.Filled.Archive, contentDescription = stringResource(R.string.archive_open))
            }
        },
        content = { pad ->
            if (ctx == null) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(pad)
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Filled.Archive, contentDescription = null, modifier = Modifier.size(64.dp))
                    Text(stringResource(R.string.archive_empty), Modifier.padding(top = 12.dp))
                    Text(
                        stringResource(R.string.archive_open_zip_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(onClick = { openDoc() }, Modifier.padding(top = 16.dp)) {
                        Text(stringResource(R.string.archive_open))
                    }
                }
            } else {
                Column(Modifier.fillMaxSize().padding(pad)) {
                    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Text(ctx.displayName, style = MaterialTheme.typography.titleMedium)
                            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                AssistChip(
                                    onClick = {},
                                    label = {
                                        Text(
                                            if (ctx.isRwmod) "RWMOD ✓" else "ZIP",
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                )
                                AssistChip(
                                    onClick = {},
                                    label = { Text(stringResource(R.string.archive_entries, archive.entries.size)) }
                                )
                                AssistChip(
                                    onClick = {},
                                    label = { Text(formatBytes(ctx.sizeBytes)) }
                                )
                            }
                        }
                    }

                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Button(onClick = { multiAdd() }, enabled = !selectionMode) {
                            Icon(Icons.Filled.Add, contentDescription = null)
                            Text(stringResource(R.string.archive_add_files))
                        }
                        Button(onClick = { extractAll = true }, enabled = !selectionMode) {
                            Icon(Icons.Filled.FileDownload, contentDescription = null)
                            Text(stringResource(R.string.archive_extract_all))
                        }
                    }

                    if (selectionMode) {
                        Row(
                            Modifier.padding(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            IconButton(onClick = { confirmDelete = true }) {
                                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.archive_delete_selected))
                            }
                            IconButton(onClick = { extractSelected = true }) {
                                Icon(Icons.Filled.FileDownload, contentDescription = stringResource(R.string.archive_extract_selected))
                            }
                            IconButton(onClick = { renameTarget = selected.firstOrNull() }) {
                                Icon(Icons.Filled.DriveFileRenameOutline, contentDescription = stringResource(R.string.action_rename))
                            }
                            IconButton(onClick = {
                                selectionMode = false
                                selected.clear()
                            }) {
                                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_cancel))
                            }
                            Text(
                                stringResource(R.string.project_selected_n, selected.size),
                                Modifier.align(Alignment.CenterVertically)
                            )
                        }
                    }

                    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
                        items(archive.entries, key = { it.name }) { e ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        when {
                                            e.isDirectory -> {
                                                selectionMode = true
                                                if (!selected.contains(e.name)) selected.add(e.name)
                                            }
                                            selectionMode -> {
                                                if (selected.contains(e.name)) selected.remove(e.name) else selected.add(e.name)
                                            }
                                            SafUtils.hasTextExt(e.name) -> {
                                                scope.launch {
                                                    graph.editor.openArchiveEntry(e.name)
                                                        .onSuccess { nav.navigate(Routes.EDITOR) }
                                                        .onFailure { toast(context, it.message ?: "") }
                                                }
                                            }
                                            SafUtils.hasImageExt(e.name) -> {
                                                scope.launch {
                                                    graph.image.loadFromArchive(e.name)
                                                        .onSuccess { nav.navigate(Routes.IMAGE) }
                                                        .onFailure { toast(context, it.message ?: "") }
                                                }
                                            }
                                            SafUtils.hasAudioExt(e.name) -> {
                                                scope.launch {
                                                    graph.audio.loadFromArchive(e.name)
                                                        .onSuccess { nav.navigate(Routes.AUDIO) }
                                                        .onFailure { toast(context, it.message ?: "") }
                                                }
                                            }
                                            else -> {
                                                scope.launch {
                                                    try {
                                                        val (text, _) = archive.readEntryText(e.name)
                                                        previewText = e.name to text.take(4000)
                                                    } catch (t: Throwable) {
                                                        toast(context, t.message ?: "")
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (selectionMode && !e.isDirectory) {
                                    Checkbox(
                                        checked = selected.contains(e.name),
                                        onCheckedChange = {
                                            if (selected.contains(e.name)) selected.remove(e.name) else selected.add(e.name)
                                        }
                                    )
                                }
                                Icon(
                                    when {
                                        e.isDirectory -> Icons.Filled.Folder
                                        SafUtils.hasImageExt(e.name) -> Icons.Filled.Image
                                        SafUtils.hasAudioExt(e.name) -> Icons.Filled.AudioFile
                                        else -> Icons.Filled.Description
                                    },
                                    contentDescription = null,
                                    tint = if (e.isDirectory) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
                                )
                                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                                    Text(e.name, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                                    Text(
                                        if (e.isDirectory) stringResource(R.string.common_folder)
                                        else "${formatBytes(e.size)} → ${formatBytes(e.compressedSize)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(onClick = { renameTarget = e.name }) {
                                    Icon(Icons.Filled.DriveFileRenameOutline, contentDescription = stringResource(R.string.action_rename))
                                }
                            }
                        }
                    }
                }
            }
        }
    )

    // dialogs
    if (confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.archive_delete_selected),
            text = stringResource(R.string.common_confirm_delete, selected.size),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = {
                confirmDelete = false
                val names = selected.toSet()
                scope.launch {
                    archive.deleteEntries(names)
                        .onSuccess {
                            selected.clear()
                            selectionMode = false
                        }
                }
            },
            onDismiss = { confirmDelete = false }
        )
    }
    renameTarget?.let { target ->
        InputDialog(
            title = stringResource(R.string.action_rename),
            label = stringResource(R.string.archive_new_name),
            initial = target,
            onConfirm = { newName ->
                renameTarget = null
                scope.launch {
                    archive.renamePath(target, newName)
                        .onSuccess {
                            selected.clear()
                            selectionMode = false
                        }
                }
            },
            onDismiss = { renameTarget = null }
        )
    }
    previewText?.let { (name, text) ->
        AlertDialogPreview(name, text) { previewText = null }
    }
    if (addFilesTarget) {
        InputDialog(
            title = stringResource(R.string.archive_import_target),
            label = stringResource(R.string.common_path),
            initial = "",
            onConfirm = { dir ->
                addFilesTarget = false
                scope.launch {
                    val safeDir = dir.trim().trim('/').replace('\\', '/')
                    if (safeDir.isNotEmpty() && ZipPaths.normalize("$safeDir/x") == null) {
                        toast(context, "Unsafe path")
                    } else {
                        archive.addFiles(safeDir, pendingAddUris.map { it to SafUtils.displayName(context.contentResolver, it) })
                    }
                }
            },
            onDismiss = { addFilesTarget = false }
        )
    }
    if (extractAll || extractSelected) {
        ConfirmDialog(
            title = stringResource(R.string.archive_extract_selected),
            text = if (extractAll) stringResource(R.string.archive_extract_all)
            else stringResource(R.string.project_selected_n, selected.size),
            confirmLabel = stringResource(R.string.action_extract),
            onConfirm = { treePicker() },
            onDismiss = { extractAll = false; extractSelected = false }
        )
    }
}

@Composable
private fun AlertDialogPreview(name: String, text: String, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(name) },
        text = {
            LazyColumn {
                items(text.lines()) { line ->
                    Text(line, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        }
    )
}
