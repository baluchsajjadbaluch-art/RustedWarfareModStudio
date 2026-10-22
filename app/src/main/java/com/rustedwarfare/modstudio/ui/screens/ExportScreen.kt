package com.rustedwarfare.modstudio.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.rustedwarfare.modstudio.AppGraph
import com.rustedwarfare.modstudio.R
import com.rustedwarfare.modstudio.ui.BusyOverlay
import com.rustedwarfare.modstudio.ui.ConfirmDialog
import com.rustedwarfare.modstudio.ui.rememberCreateDocPicker
import com.rustedwarfare.modstudio.ui.rememberTreePicker
import com.rustedwarfare.modstudio.ui.toast
import com.rustedwarfare.modstudio.validator.ModValidator
import kotlinx.coroutines.launch

@Composable
fun ExportScreen(graph: AppGraph, nav: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val export = graph.export
    val archive = graph.archive
    val validator = graph.validator

    var busy by remember { mutableStateOf(false) }
    var showPreCheck by remember { mutableStateOf(false) }
    var preCheckErrors by remember { mutableStateOf(0) }
    var pendingUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var selectionMode by remember { mutableStateOf(false) }
    val selectedNames = remember { mutableStateListOf<String>() }

    fun suggestName(ext: String): String {
        val base = archive.current?.displayName?.substringBeforeLast('.')
            ?: graph.project.root?.name
            ?: "mod"
        return "$base.$ext"
    }

    /** Shared export routine with validation pre-check. */
    fun exportTo(uri: android.net.Uri, asRwmod: Boolean) {
        busy = true
        scope.launch {
            val report = if (archive.current != null) {
                validator.validate(ModValidator.Target.ArchiveTarget(archive))
            } else {
                graph.project.root?.let { validator.validate(ModValidator.Target.FolderTarget(it)) }
            }
            val errors = report?.errors ?: 0
            if (errors > 0) {
                preCheckErrors = errors
                pendingUri = uri
                showPreCheck = true
                busy = false
                return@launch
            }
            runExport(context, export, graph, uri)
            busy = false
        }
    }

    val zipWriter = rememberCreateDocPicker("application/zip", suggestName("zip")) { uri ->
        exportTo(uri, asRwmod = false)
    }
    val rwmodWriter = rememberCreateDocPicker("application/octet-stream", suggestName("rwmod")) { uri ->
        exportTo(uri, asRwmod = true)
    }
    val extractTree = rememberTreePicker { uri ->
        busy = true
        scope.launch {
            export.extractAllTo(uri)
                .onFailure { toast(context, it.message ?: "") }
            busy = false
        }
    }
    val selectionWriter = rememberCreateDocPicker("application/zip", "selection.zip") { uri ->
        val names = selectedNames.toSet()
        busy = true
        scope.launch {
            export.exportSelected(names, uri)
                .onSuccess { toast(context, context.getString(R.string.export_postcheck_ok, it)) }
                .onFailure { toast(context, it.message ?: "") }
            busy = false
        }
    }

    StatusToast(export.status) { export.clearStatus() }

    if (busy) {
        BusyOverlay(stringResource(R.string.export_running), null, null)
    }

    if (showPreCheck) {
        ConfirmDialog(
            title = stringResource(R.string.export_precheck),
            text = stringResource(R.string.export_precheck_msg, preCheckErrors),
            confirmLabel = stringResource(R.string.export_precheck_continue),
            onConfirm = {
                showPreCheck = false
                val uri = pendingUri
                pendingUri = null
                if (uri != null) {
                    busy = true
                    scope.launch {
                        runExport(context, export, graph, uri)
                        busy = false
                    }
                }
            },
            onDismiss = {
                showPreCheck = false
                pendingUri = null
            }
        )
    }

    ScreenScaffold(
        title = stringResource(R.string.export_title),
        onBack = { nav.popBackStack() },
        content = { pad ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(pad)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ExportCard(stringResource(R.string.export_as_zip)) { zipWriter() }
                ExportCard(stringResource(R.string.export_as_rwmod), enabled = archive.current != null) {
                    rwmodWriter()
                }
                ExportCard(stringResource(R.string.export_extract_all), enabled = archive.current != null) {
                    extractTree()
                }
                ExportCard(stringResource(R.string.export_selection), enabled = archive.current != null) {
                    selectionMode = !selectionMode
                }

                if (selectionMode && archive.current != null) {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(8.dp)) {
                            Text(
                                stringResource(R.string.project_selected_n, selectedNames.size),
                                style = MaterialTheme.typography.labelMedium
                            )
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .height(320.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                archive.entries.filter { !it.isDirectory }.forEach { e ->
                                    Row(Modifier.fillMaxWidth()) {
                                        Checkbox(
                                            checked = selectedNames.contains(e.name),
                                            onCheckedChange = {
                                                if (selectedNames.contains(e.name)) selectedNames.remove(e.name)
                                                else selectedNames.add(e.name)
                                            }
                                        )
                                        Text(
                                            e.name,
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 1,
                                            modifier = Modifier.padding(top = 12.dp)
                                        )
                                    }
                                }
                            }
                            Button(
                                onClick = { selectionWriter() },
                                enabled = selectedNames.isNotEmpty()
                            ) {
                                Text(stringResource(R.string.export_selection))
                            }
                        }
                    }
                }
            }
        }
    )
}

private suspend fun runExport(
    context: android.content.Context,
    export: com.rustedwarfare.modstudio.export.ExportRepository,
    graph: AppGraph,
    uri: android.net.Uri
) {
    val result = if (graph.archive.current != null) {
        export.exportOpenArchive(uri)
    } else {
        val tree = graph.project.root ?: return
        export.exportFolderAsZip(tree.uri, uri)
    }
    result
        .onSuccess { toast(context, context.getString(R.string.export_postcheck_ok, it)) }
        .onFailure { toast(context, it.message ?: "") }
}

@Composable
private fun ExportCard(title: String, enabled: Boolean = true, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            title,
            Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyLarge
        )
    }
}
