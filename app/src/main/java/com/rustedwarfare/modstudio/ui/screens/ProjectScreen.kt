package com.rustedwarfare.modstudio.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoveUp
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.navigation.NavController
import com.rustedwarfare.modstudio.AppGraph
import com.rustedwarfare.modstudio.R
import com.rustedwarfare.modstudio.Routes
import com.rustedwarfare.modstudio.core.SafUtils
import com.rustedwarfare.modstudio.ui.BusyOverlay
import com.rustedwarfare.modstudio.ui.ConfirmDialog
import com.rustedwarfare.modstudio.ui.InputDialog
import com.rustedwarfare.modstudio.ui.formatBytes
import com.rustedwarfare.modstudio.ui.rememberTreePicker
import com.rustedwarfare.modstudio.ui.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@Composable
fun ProjectScreen(graph: AppGraph, nav: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val project = graph.project

    var refreshTick by remember { mutableIntStateOf(0) }
    var multiSelect by remember { mutableStateOf(false) }
    val selectedDocs = remember { mutableStateListOf<DocumentFile>() }
    val expandedUris = remember { mutableStateListOf<String>() }
    var query by remember { mutableStateOf("") }
    var sortMenu by remember { mutableStateOf(false) }
    var newFileIn by remember { mutableStateOf<DocumentFile?>(null) }
    var newFolderIn by remember { mutableStateOf<DocumentFile?>(null) }
    var renameTarget by remember { mutableStateOf<DocumentFile?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var propsTarget by remember { mutableStateOf<DocumentFile?>(null) }
    var copyMoveDocs by remember { mutableStateOf<List<DocumentFile>>(emptyList()) }
    var copyMoveIsCopy by remember { mutableStateOf(true) }
    var awaitingDestForFile by remember { mutableStateOf<DocumentFile?>(null) }

    val openFolder = rememberTreePicker { uri ->
        scope.launch {
            project.openTree(uri)
                .onFailure { toast(context, context.getString(R.string.common_error, it.message ?: "")) }
        }
    }

    // copy/move destination picker (SAF tree)
    val destPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            scope.launch {
                val dest = DocumentFile.fromTreeUri(context, uri)
                if (dest != null) {
                    for (doc in copyMoveDocs) {
                        val r = if (copyMoveIsCopy) project.copyTree(doc, dest) else project.moveTree(doc, dest)
                        r.onFailure { toast(context, it.message ?: "") }
                    }
                    refreshTick++
                }
                copyMoveDocs = emptyList()
            }
        }
    }

    StatusToast(project.status) { project.clearStatus() }

    if (project.busy) {
        BusyOverlay(stringResource(R.string.project_saving), null, null)
    }

    fun openFile(doc: DocumentFile) {
        val name = doc.name ?: return
        scope.launch {
            when {
                SafUtils.hasArchiveExt(name) -> {
                    graph.archive.open(doc.uri)
                    nav.navigate(Routes.ARCHIVE)
                }
                SafUtils.hasImageExt(name) -> {
                    graph.image.loadFromUri(doc.uri)
                        .onSuccess { nav.navigate(Routes.IMAGE) }
                        .onFailure { toast(context, it.message ?: "") }
                }
                SafUtils.hasAudioExt(name) -> {
                    graph.audio.loadFromUri(doc.uri)
                        .onSuccess { nav.navigate(Routes.AUDIO) }
                        .onFailure { toast(context, it.message ?: "") }
                }
                SafUtils.hasTextExt(name) -> {
                    graph.editor.openSafFile(doc.uri)
                        .onSuccess { nav.navigate(Routes.EDITOR) }
                        .onFailure { toast(context, it.message ?: "") }
                }
                else -> toast(context, name)
            }
        }
    }

    val root = project.root
    ScreenScaffold(
        title = stringResource(R.string.project_title),
        onBack = null,
        actions = {
            IconButton(onClick = { openFolder() }) {
                Icon(Icons.Filled.FolderOpen, contentDescription = stringResource(R.string.project_open_folder))
            }
            if (root != null) {
                IconButton(onClick = { newFileIn = root }) {
                    Icon(Icons.Filled.NoteAdd, contentDescription = stringResource(R.string.project_new_file))
                }
                IconButton(onClick = { newFolderIn = root }) {
                    Icon(Icons.Filled.CreateNewFolder, contentDescription = stringResource(R.string.project_new_folder))
                }
                IconButton(onClick = { multiSelect = !multiSelect; selectedDocs.clear() }) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = stringResource(R.string.project_multi_select),
                        tint = if (multiSelect) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Box {
                    TextButton(onClick = { sortMenu = true }) { Text(stringResource(R.string.project_sort)) }
                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                        listOf(
                            "NAME" to com.rustedwarfare.modstudio.project.ProjectRepository.SortMode.NAME,
                            "SIZE" to com.rustedwarfare.modstudio.project.ProjectRepository.SortMode.SIZE,
                            "TYPE" to com.rustedwarfare.modstudio.project.ProjectRepository.SortMode.TYPE,
                            "DATE" to com.rustedwarfare.modstudio.project.ProjectRepository.SortMode.DATE
                        ).forEach { (label, mode) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    project.sortMode = mode
                                    sortMenu = false
                                    refreshTick++
                                }
                            )
                        }
                    }
                }
            }
        },
        content = { pad ->
            if (root == null) {
                Column(
                    Modifier.fillMaxSize().padding(pad).padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.width(64.dp))
                    Text(stringResource(R.string.home_workspace_none), Modifier.padding(top = 8.dp))
                    Button(onClick = { openFolder() }, Modifier.padding(top = 16.dp)) {
                        Text(stringResource(R.string.project_open_folder))
                    }
                }
            } else {
                Column(Modifier.fillMaxSize().padding(pad)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text(stringResource(R.string.project_search)) },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                    )

                    if (multiSelect && selectedDocs.isNotEmpty()) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(stringResource(R.string.project_selected_n, selectedDocs.size))
                            IconButton(onClick = {
                                copyMoveDocs = selectedDocs.toList(); copyMoveIsCopy = true; destPicker.launch(null)
                            }) {
                                Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.action_copy))
                            }
                            IconButton(onClick = {
                                copyMoveDocs = selectedDocs.toList(); copyMoveIsCopy = false; destPicker.launch(null)
                            }) {
                                Icon(Icons.Filled.MoveUp, contentDescription = stringResource(R.string.action_move))
                            }
                            IconButton(onClick = { confirmDelete = true }) {
                                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.action_delete))
                            }
                            IconButton(onClick = { selectedDocs.clear() }) {
                                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_cancel))
                            }
                        }
                    }

                    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 32.dp)) {
                        if (query.isBlank()) {
                            item {
                                TreeNode(
                                    graph = graph,
                                    dir = root,
                                    depth = 0,
                                    expandedUris = expandedUris,
                                    refreshTick = refreshTick,
                                    multiSelect = multiSelect,
                                    selectedDocs = selectedDocs,
                                    onToggle = { doc ->
                                        val uri = doc.uri.toString()
                                        if (expandedUris.contains(uri)) expandedUris.remove(uri) else expandedUris.add(uri)
                                    },
                                    onOpenFile = { openFile(it) },
                                    onNewFileIn = { newFileIn = it },
                                    onNewFolderIn = { newFolderIn = it },
                                    onRename = { renameTarget = it },
                                    onDelete = { selectedDocs.clear(); selectedDocs.add(it); confirmDelete = true },
                                    onProps = { propsTarget = it }
                                )
                            }
                        } else {
                            val hits by produceState<List<DocumentFile>>(emptyList(), query, refreshTick) {
                                value = withContext(Dispatchers.IO) { project.searchTree(query) }
                            }
                            if (hits.isEmpty()) {
                                item { Text(stringResource(R.string.project_empty), Modifier.padding(16.dp)) }
                            }
                            for (doc in hits) {
                                item(doc.uri.toString()) {
                                    FlatFileRow(doc) { openFile(doc) }
                                }
                            }
                        }
                    }
                }
            }
        }
    )

    // ---------------- dialogs ----------------
    newFileIn?.let { dir ->
        InputDialog(
            title = stringResource(R.string.project_new_file),
            label = stringResource(R.string.project_file_name),
            initial = "new.ini",
            onConfirm = { name ->
                newFileIn = null
                scope.launch {
                    project.createFile(dir, name).onSuccess { refreshTick++ }
                }
            },
            onDismiss = { newFileIn = null }
        )
    }
    newFolderIn?.let { dir ->
        InputDialog(
            title = stringResource(R.string.project_new_folder),
            label = stringResource(R.string.common_name),
            initial = "new_folder",
            onConfirm = { name ->
                newFolderIn = null
                scope.launch {
                    project.createFolder(dir, name).onSuccess { refreshTick++ }
                }
            },
            onDismiss = { newFolderIn = null }
        )
    }
    renameTarget?.let { doc ->
        InputDialog(
            title = stringResource(R.string.action_rename),
            label = stringResource(R.string.common_name),
            initial = doc.name ?: "",
            onConfirm = { name ->
                renameTarget = null
                scope.launch {
                    project.rename(doc, name).onSuccess { refreshTick++ }
                }
            },
            onDismiss = { renameTarget = null }
        )
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.action_delete),
            text = stringResource(R.string.common_confirm_delete, selectedDocs.size),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = {
                confirmDelete = false
                scope.launch {
                    project.delete(selectedDocs.toList()).onSuccess {
                        selectedDocs.clear()
                        refreshTick++
                    }
                }
            },
            onDismiss = { confirmDelete = false }
        )
    }
    propsTarget?.let { doc ->
        PropertiesDialog(doc) { propsTarget = null }
    }
}

@Composable
private fun FlatFileRow(doc: DocumentFile, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            when {
                SafUtils.hasImageExt(doc.name ?: "") -> Icons.Filled.Image
                SafUtils.hasAudioExt(doc.name ?: "") -> Icons.Filled.AudioFile
                else -> Icons.Filled.Description
            },
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
        Column(Modifier.padding(start = 12.dp)) {
            Text(doc.name ?: "", style = MaterialTheme.typography.bodyMedium)
            Text(formatBytes(doc.length()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TreeNode(
    graph: AppGraph,
    dir: DocumentFile,
    depth: Int,
    expandedUris: androidx.compose.runtime.snapshots.SnapshotStateList<String>,
    refreshTick: Int,
    multiSelect: Boolean,
    selectedDocs: MutableList<DocumentFile>,
    onToggle: (DocumentFile) -> Unit,
    onOpenFile: (DocumentFile) -> Unit,
    onNewFileIn: (DocumentFile) -> Unit,
    onNewFolderIn: (DocumentFile) -> Unit,
    onRename: (DocumentFile) -> Unit,
    onDelete: (DocumentFile) -> Unit,
    onProps: (DocumentFile) -> Unit
) {
    val kids by produceState<List<DocumentFile>>(emptyList(), dir.uri, refreshTick) {
        value = withContext(Dispatchers.IO) {
            graph.project.children(dir)
        }
    }

    for (kid in kids) {
        val isDir = kid.isDirectory
        val expanded = expandedUris.contains(kid.uri.toString())
        Row(
            Modifier
                .fillMaxWidth()
                .clickable {
                    when {
                        multiSelect && !isDir -> {
                            if (selectedDocs.contains(kid)) selectedDocs.remove(kid) else selectedDocs.add(kid)
                        }
                        isDir -> onToggle(kid)
                        else -> onOpenFile(kid)
                    }
                }
                .padding(start = (depth * 20).dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (multiSelect && !isDir) {
                Checkbox(
                    checked = selectedDocs.contains(kid),
                    onCheckedChange = {
                        if (selectedDocs.contains(kid)) selectedDocs.remove(kid) else selectedDocs.add(kid)
                    }
                )
            } else {
                Spacer(Modifier.width(8.dp))
            }
            Icon(
                when {
                    isDir -> if (expanded) Icons.Filled.FolderOpen else Icons.Filled.Folder
                    SafUtils.hasImageExt(kid.name ?: "") -> Icons.Filled.Image
                    SafUtils.hasAudioExt(kid.name ?: "") -> Icons.Filled.AudioFile
                    else -> Icons.Filled.Description
                },
                contentDescription = null,
                tint = if (isDir) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
            )
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(kid.name ?: "", style = MaterialTheme.typography.bodyMedium)
                if (!isDir) {
                    Text(
                        formatBytes(kid.length()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (!multiSelect) {
                if (isDir) {
                    IconButton(onClick = { onNewFileIn(kid) }, modifier = Modifier.width(36.dp)) {
                        Icon(Icons.Filled.NoteAdd, contentDescription = null, modifier = Modifier.width(18.dp))
                    }
                } else {
                    IconButton(onClick = { onRename(kid) }, modifier = Modifier.width(36.dp)) {
                        Icon(Icons.Filled.DriveFileRenameOutline, contentDescription = null, modifier = Modifier.width(18.dp))
                    }
                    IconButton(onClick = { onProps(kid) }, modifier = Modifier.width(36.dp)) {
                        Icon(Icons.Filled.Info, contentDescription = null, modifier = Modifier.width(18.dp))
                    }
                }
            }
        }
        if (isDir && expanded) {
            TreeNode(
                graph = graph,
                dir = kid,
                depth = depth + 1,
                expandedUris = expandedUris,
                refreshTick = refreshTick,
                multiSelect = multiSelect,
                selectedDocs = selectedDocs,
                onToggle = onToggle,
                onOpenFile = onOpenFile,
                onNewFileIn = onNewFileIn,
                onNewFolderIn = onNewFolderIn,
                onRename = onRename,
                onDelete = onDelete,
                onProps = onProps
            )
        }
    }
}

@Composable
private fun PropertiesDialog(doc: DocumentFile, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.project_properties)) },
        text = {
            Column {
                Text(stringResource(R.string.common_name) + ": " + (doc.name ?: ""))
                Text(stringResource(R.string.project_size, formatBytes(doc.length())))
                Text(
                    stringResource(
                        R.string.project_modified,
                        DateFormat.getDateTimeInstance().format(Date(doc.lastModified()))
                    )
                )
                Text(stringResource(R.string.project_type, SafUtils.mimeFor(doc.name ?: "")))
                LaunchedEffect(Unit) { /* nothing */ }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) }
        }
    )
}
