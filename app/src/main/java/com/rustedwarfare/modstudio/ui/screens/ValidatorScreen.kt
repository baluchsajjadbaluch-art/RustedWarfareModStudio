package com.rustedwarfare.modstudio.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.rustedwarfare.modstudio.AppGraph
import com.rustedwarfare.modstudio.R
import com.rustedwarfare.modstudio.ui.BusyOverlay
import com.rustedwarfare.modstudio.ui.rememberCreateDocPicker
import com.rustedwarfare.modstudio.ui.rememberTreePicker
import com.rustedwarfare.modstudio.ui.toast
import com.rustedwarfare.modstudio.validator.ModValidator
import kotlinx.coroutines.launch

@Composable
fun ValidatorScreen(graph: AppGraph, nav: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val validator = graph.validator

    var report by remember { mutableStateOf<ModValidator.Report?>(null) }
    var running by remember { mutableStateOf(false) }
    var folderTarget by remember { mutableStateOf<androidx.documentfile.provider.DocumentFile?>(null) }

    val treePicker = rememberTreePicker { uri ->
        val doc = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, uri)
        folderTarget = doc
        report = null
    }
    val reportWriter = rememberCreateDocPicker("text/plain", "rwms_report.txt") { uri ->
        report?.let {
            scope.launch {
                try {
                    context.contentResolver.openOutputStream(uri, "wt")!!.use { os ->
                        os.write(it.toText().toByteArray(Charsets.UTF_8))
                    }
                    toast(context, context.getString(R.string.validator_report_saved))
                } catch (t: Throwable) {
                    toast(context, t.message ?: "")
                }
            }
        }
    }

    val hasArchive = graph.archive.current != null

    ScreenScaffold(
        title = stringResource(R.string.validator_title),
        onBack = { nav.popBackStack() },
        content = { pad ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(pad)
                    .padding(16.dp)
            ) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(stringResource(R.string.validator_target), style = MaterialTheme.typography.titleSmall)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    running = true
                                    scope.launch {
                                        report = validator.validate(
                                            ModValidator.Target.ArchiveTarget(graph.archive)
                                        )
                                        running = false
                                    }
                                },
                                enabled = hasArchive && !running
                            ) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                                Text(stringResource(R.string.validator_uses_archive))
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                            TextButton(onClick = { treePicker() }) {
                                Text(
                                    if (folderTarget == null) stringResource(R.string.validator_pick_folder)
                                    else (folderTarget?.name ?: "")
                                )
                            }
                            Button(
                                onClick = {
                                    val t = folderTarget ?: return@Button
                                    running = true
                                    scope.launch {
                                        report = validator.validate(ModValidator.Target.FolderTarget(t))
                                        running = false
                                    }
                                },
                                enabled = folderTarget != null && !running
                            ) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                                Text(stringResource(R.string.validator_run))
                            }
                        }
                        Text(
                            stringResource(R.string.validator_unknown_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (running) {
                    BusyOverlay(stringResource(R.string.validator_running), null, null)
                }

                report?.let { rep ->
                    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(onClick = {}, label = { Text(stringResource(R.string.validator_errors_n, rep.errors)) })
                        AssistChip(onClick = {}, label = { Text(stringResource(R.string.validator_warnings_n, rep.warnings)) })
                        AssistChip(onClick = {}, label = { Text(stringResource(R.string.validator_infos_n, rep.infos)) })
                    }
                    Text(
                        stringResource(R.string.validator_files_scanned, rep.filesScanned) + "  ·  ${rep.elapsedMs} ms",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (rep.findings.isEmpty()) {
                        Text(stringResource(R.string.validator_clean), Modifier.padding(top = 16.dp), color = MaterialTheme.colorScheme.tertiary)
                    }
                    Row(Modifier.padding(top = 8.dp)) {
                        TextButton(onClick = { reportWriter() }) {
                            Icon(Icons.Filled.SaveAlt, contentDescription = null)
                            Text(stringResource(R.string.validator_export_report))
                        }
                    }
                    LazyColumn(Modifier.fillMaxSize().padding(top = 4.dp)) {
                        items(rep.findings) { f ->
                            FindingRow(f)
                        }
                    }
                }
            }
        }
    )
}

@Composable
private fun FindingRow(f: ModValidator.Finding) {
    val (icon, tint) = when (f.severity) {
        ModValidator.Severity.ERROR -> Icons.Filled.Error to Color(0xFFE57373)
        ModValidator.Severity.WARNING -> Icons.Filled.Warning to Color(0xFFF2A33C)
        ModValidator.Severity.INFO -> Icons.Filled.Info to MaterialTheme.colorScheme.secondary
    }
    Card(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(Modifier.padding(10.dp)) {
            Icon(icon, contentDescription = null, tint = tint)
            Column(Modifier.padding(start = 10.dp)) {
                Text(
                    "[${f.code}] ${f.file}" + (f.line?.let { "  ·  " + stringResource(R.string.validator_line, it) } ?: ""),
                    style = MaterialTheme.typography.labelMedium
                )
                Text(f.message, style = MaterialTheme.typography.bodySmall)
                f.suggestion?.let {
                    Text(
                        stringResource(R.string.validator_suggestion, it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
