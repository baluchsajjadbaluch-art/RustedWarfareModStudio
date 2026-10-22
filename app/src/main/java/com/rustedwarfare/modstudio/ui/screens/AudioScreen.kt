package com.rustedwarfare.modstudio.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.rustedwarfare.modstudio.audio.AudioOps
import com.rustedwarfare.modstudio.audio.AudioRepository
import com.rustedwarfare.modstudio.core.SafUtils
import com.rustedwarfare.modstudio.ui.BusyOverlay
import com.rustedwarfare.modstudio.ui.InputDialog
import com.rustedwarfare.modstudio.ui.rememberCreateDocPicker
import com.rustedwarfare.modstudio.ui.rememberOpenDocPicker
import com.rustedwarfare.modstudio.ui.rememberOpenMultiPicker
import com.rustedwarfare.modstudio.ui.toast
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun AudioScreen(graph: AppGraph, nav: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val audio = graph.audio
    val archive = graph.archive

    var archiveListOpen by remember { mutableStateOf(false) }
    var convertResult by remember { mutableStateOf<Pair<ByteArray, AudioOps.AudioInfo>?>(null) }
    var importTargetOpen by remember { mutableStateOf(false) }
    var pendingImportUris by remember { mutableStateOf<List<android.net.Uri>>(emptyList()) }
    var wavArchiveName by remember { mutableStateOf<String?>(null) }

    val openAudio = rememberOpenDocPicker(arrayOf("audio/*")) { uri ->
        scope.launch {
            audio.loadFromUri(uri).onFailure { toast(context, it.message ?: "") }
        }
    }
    val replacePicker = rememberOpenDocPicker(arrayOf("audio/*")) { uri ->
        scope.launch {
            audio.replaceInArchive(uri)
                .onFailure { toast(context, it.message ?: "") }
        }
    }
    val multiImport = rememberOpenMultiPicker(arrayOf("audio/*")) { uris ->
        pendingImportUris = uris
        importTargetOpen = true
    }
    val wavWriter = rememberCreateDocPicker("audio/wav", "converted.wav") { uri ->
        convertResult?.let { (wav, _) ->
            scope.launch {
                try {
                    context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(wav) }
                    toast(context, context.getString(R.string.audio_saved))
                } catch (t: Throwable) {
                    toast(context, t.message ?: "")
                }
            }
        }
    }

    StatusToast(audio.status) { audio.clearStatus() }

    if (audio.busy) {
        BusyOverlay(stringResource(R.string.common_loading), null, null)
    }

    val current = audio.current

    // position ticker
    LaunchedEffect(audio.playing) {
        while (audio.playing) {
            audio.tickPosition()
            delay(200)
        }
    }

    ScreenScaffold(
        title = stringResource(R.string.audio_title),
        onBack = { nav.popBackStack() },
        content = { pad ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(pad)
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { archiveListOpen = true }) { Text(stringResource(R.string.audio_open_from_archive)) }
                    Button(onClick = { openAudio() }) { Text(stringResource(R.string.audio_open_from_folder)) }
                }

                current?.let { c ->
                    Card(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Text(c.name, style = MaterialTheme.typography.titleSmall)

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                                Button(onClick = {
                                    if (audio.playing) audio.pausePlayback() else audio.startPlayback()
                                }) {
                                    Text(if (audio.playing) stringResource(R.string.audio_pause) else stringResource(R.string.audio_play))
                                }
                                Button(onClick = { audio.stopPlayback() }) {
                                    Text(stringResource(R.string.audio_stop))
                                }
                            }

                            val dur = c.info?.durationMs ?: 0L
                            if (dur > 0) {
                                Slider(
                                    value = audio.positionMs.toFloat().coerceIn(0f, dur.toFloat()),
                                    onValueChange = { audio.seekTo(it.toLong()) },
                                    valueRange = 0f..dur.toFloat().coerceAtLeast(1f)
                                )
                                Text(
                                    stringResource(R.string.audio_duration, AudioOps.formatDuration(audio.positionMs)) +
                                        " / " + AudioOps.formatDuration(dur),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }

                            c.info?.let { info ->
                                Text(stringResource(R.string.audio_sample_rate, info.sampleRate), style = MaterialTheme.typography.bodySmall)
                                Text(stringResource(R.string.audio_channels, info.channels), style = MaterialTheme.typography.bodySmall)
                                if (info.bitrateKbps > 0) {
                                    Text(stringResource(R.string.audio_bitrate, info.bitrateKbps), style = MaterialTheme.typography.bodySmall)
                                }
                                Text(stringResource(R.string.audio_codec, info.mime), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
                        Button(
                            onClick = { replacePicker() },
                            enabled = c.source is AudioRepository.Source.InArchive
                        ) { Text(stringResource(R.string.audio_replace)) }
                        Button(onClick = { multiImport() }, enabled = archive.current != null) {
                            Text(stringResource(R.string.audio_import_batch))
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                        Button(onClick = {
                            scope.launch {
                                audio.convertCurrentToWav()
                                    .onSuccess { convertResult = it }
                                    .onFailure { toast(context, it.message ?: "") }
                            }
                        }) { Text(stringResource(R.string.audio_convert_wav)) }
                        Button(onClick = {
                            scope.launch {
                                val n = audio.checkIniReferences()
                                toast(
                                    context,
                                    if (n > 0) context.getString(R.string.audio_refs_found, n)
                                    else context.getString(R.string.audio_no_refs)
                                )
                            }
                        }) { Text(stringResource(R.string.audio_check_refs)) }
                    }
                }
            }
        }
    )

    // ------- dialogs -------
    if (archiveListOpen) {
        val entries = archive.entries.filter { !it.isDirectory && SafUtils.hasAudioExt(it.name) }.map { it.name }
        EntryPickerDialog(
            title = stringResource(R.string.audio_open_from_archive),
            entries = entries,
            onPick = { name ->
                archiveListOpen = false
                scope.launch {
                    audio.loadFromArchive(name).onFailure { toast(context, it.message ?: "") }
                }
            },
            onDismiss = { archiveListOpen = false }
        )
    }
    if (importTargetOpen) {
        InputDialog(
            title = stringResource(R.string.archive_import_target),
            label = stringResource(R.string.common_path),
            initial = "sounds",
            onConfirm = { dir ->
                importTargetOpen = false
                scope.launch {
                    audio.batchImport(dir.trim().trim('/'), pendingImportUris)
                }
            },
            onDismiss = { importTargetOpen = false }
        )
    }
    convertResult?.let { (wav, info) ->
        AlertDialog(
            onDismissRequest = { convertResult = null },
            title = { Text(stringResource(R.string.audio_convert_wav)) },
            text = {
                Column {
                    Text(stringResource(R.string.audio_sample_rate, info.sampleRate))
                    Text(stringResource(R.string.audio_channels, info.channels))
                    Text("${wav.size / 1024} KB")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    convertResult = null
                    val base = (current?.name ?: "audio").substringAfterLast('/').substringBeforeLast('.')
                    val entry = "sounds/${base}_converted.wav"
                    wavArchiveName = entry
                    if (archive.current != null) {
                        scope.launch {
                            audio.addWavToArchive(entry, wav)
                                .onSuccess { toast(context, context.getString(R.string.audio_saved)) }
                                .onFailure { toast(context, it.message ?: "") }
                        }
                    } else {
                        wavWriter()
                    }
                }) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = {
                TextButton(onClick = { convertResult = null }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
    wavArchiveName?.let {
        // consumed when archive absent — reset after export dialog closes
        if (archive.current != null) wavArchiveName = null
    }
}
