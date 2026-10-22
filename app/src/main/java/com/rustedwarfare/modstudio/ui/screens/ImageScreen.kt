package com.rustedwarfare.modstudio.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.rustedwarfare.modstudio.AppGraph
import com.rustedwarfare.modstudio.R
import com.rustedwarfare.modstudio.core.SafUtils
import com.rustedwarfare.modstudio.image.ImageRepository
import com.rustedwarfare.modstudio.image.ImageOps
import com.rustedwarfare.modstudio.ui.BusyOverlay
import com.rustedwarfare.modstudio.ui.InputDialog
import com.rustedwarfare.modstudio.ui.formatBytes
import com.rustedwarfare.modstudio.ui.rememberOpenDocPicker
import com.rustedwarfare.modstudio.ui.rememberOpenMultiPicker
import com.rustedwarfare.modstudio.ui.rememberTreePicker
import com.rustedwarfare.modstudio.ui.toast
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ImageScreen(graph: AppGraph, nav: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val image = graph.image
    val archive = graph.archive

    var archiveListOpen by remember { mutableStateOf(false) }
    var cropOpen by remember { mutableStateOf(false) }
    var resizeOpen by remember { mutableStateOf(false) }
    var infoOpen by remember { mutableStateOf(false) }
    var importTargetOpen by remember { mutableStateOf(false) }
    var pendingImportUris by remember { mutableStateOf<List<android.net.Uri>>(emptyList()) }
    var compareMode by remember { mutableStateOf(false) }
    var fmt by remember { mutableStateOf(ImageOps.OutFormat.PNG) }
    var quality by remember { mutableStateOf(90) }
    var saveFolderOpen by remember { mutableStateOf(false) }
    var pendingSaveName by remember { mutableStateOf<String?>(null) }

    var zoom by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }

    val openImage = rememberOpenDocPicker(arrayOf("image/*")) { uri ->
        scope.launch {
            image.loadFromUri(uri)
                .onFailure { toast(context, context.getString(R.string.image_load_failed)) }
        }
    }
    val treePicker = rememberTreePicker { uri ->
        val name = pendingSaveName ?: (image.sourceName ?: "image").substringBeforeLast('.')
        pendingSaveName = null
        scope.launch {
            image.saveToFolder(uri, fmt, quality, name)
                .onFailure { toast(context, it.message ?: "") }
        }
    }
    val multiImport = rememberOpenMultiPicker(arrayOf("image/*")) { uris ->
        pendingImportUris = uris
        importTargetOpen = true
    }

    StatusToast(image.status) { image.clearStatus() }

    if (image.busy) {
        BusyOverlay(stringResource(R.string.common_loading), null, null)
    }

    // debounced brightness/contrast application
    LaunchedEffect(image.brightness, image.contrast) {
        if (image.brightness != 0f || image.contrast != 1f) {
            delay(250)
            image.applyAdjust(image.brightness, image.contrast)
        }
    }

    val bmp = image.displayBitmap
    val original = image.baseBitmap

    ScreenScaffold(
        title = stringResource(R.string.image_title),
        onBack = { nav.popBackStack() },
        content = { pad ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(pad)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { archiveListOpen = true }) { Text(stringResource(R.string.image_open_from_archive)) }
                    Button(onClick = { openImage() }) { Text(stringResource(R.string.image_open_from_folder)) }
                }

                // preview
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(320.dp)
                        .background(Color(0xFF0D0E11))
                        .pointerInput(compareMode) {
                            detectTransformGestures { _, pan, sc, _ ->
                                zoom = (zoom * sc).coerceIn(0.2f, 8f)
                                offset += pan
                            }
                        }
                ) {
                    val shown = if (compareMode) original else bmp
                    if (shown != null) {
                        Image(
                            bitmap = shown.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    scaleX = zoom
                                    scaleY = zoom
                                    translationX = offset.x
                                    translationY = offset.y
                                }
                        )
                    } else {
                        Text(
                            stringResource(R.string.editor_hint_empty),
                            Modifier.align(Alignment.Center),
                            color = Color.White
                        )
                    }
                    if (compareMode) {
                        Text(
                            stringResource(R.string.image_original),
                            Modifier.align(Alignment.TopStart).padding(8.dp),
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }

                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Button(onClick = { bmp?.let { image.commitEdited(ImageOps.rotate(it, 90f)) } }) {
                        Text(stringResource(R.string.image_rotate))
                    }
                    Button(onClick = { bmp?.let { image.commitEdited(ImageOps.flip(it, true)) } }) {
                        Text(stringResource(R.string.image_flip_h))
                    }
                    Button(onClick = { bmp?.let { image.commitEdited(ImageOps.flip(it, false)) } }) {
                        Text(stringResource(R.string.image_flip_v))
                    }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Button(onClick = { cropOpen = true }) { Text(stringResource(R.string.image_crop)) }
                    Button(onClick = { resizeOpen = true }) { Text(stringResource(R.string.image_resize)) }
                    Button(onClick = { infoOpen = true }) { Text(stringResource(R.string.image_info)) }
                }

                Text(stringResource(R.string.image_brightness) + ": %.2f".format(image.brightness))
                Slider(
                    value = image.brightness,
                    onValueChange = { image.brightness = it },
                    valueRange = -0.5f..0.5f
                )
                Text(stringResource(R.string.image_contrast) + ": %.2f".format(image.contrast))
                Slider(
                    value = image.contrast,
                    onValueChange = { image.contrast = it },
                    valueRange = 0.5f..1.5f
                )

                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(selected = fmt == ImageOps.OutFormat.PNG, onClick = { fmt = ImageOps.OutFormat.PNG }, label = { Text("PNG") })
                    FilterChip(selected = fmt == ImageOps.OutFormat.JPEG, onClick = { fmt = ImageOps.OutFormat.JPEG }, label = { Text("JPEG") })
                    FilterChip(selected = fmt == ImageOps.OutFormat.WEBP, onClick = { fmt = ImageOps.OutFormat.WEBP }, label = { Text("WEBP") })
                    if (fmt == ImageOps.OutFormat.JPEG) {
                        Text(stringResource(R.string.image_alpha_warn), style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (fmt != ImageOps.OutFormat.PNG) {
                    Text(stringResource(R.string.image_quality) + ": $quality")
                    Slider(value = quality.toFloat(), onValueChange = { quality = it.toInt() }, valueRange = 10f..100f)
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                    Button(
                        onClick = {
                            scope.launch {
                                image.saveToArchive(fmt, quality)
                                    .onFailure { toast(context, it.message ?: "") }
                            }
                        },
                        enabled = image.source is ImageRepository.Source.InArchive
                    ) { Text(stringResource(R.string.image_save)) }
                    Button(onClick = { saveFolderOpen = true }) { Text(stringResource(R.string.action_extract)) }
                    Button(onClick = { compareMode = !compareMode }) {
                        Text(stringResource(R.string.image_compare))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = { image.resetToOriginal() }) { Text(stringResource(R.string.action_reset)) }
                    Button(onClick = { multiImport() }, enabled = archive.current != null) {
                        Text(stringResource(R.string.image_batch_import))
                    }
                }
            }
        }
    )

    // ------- dialogs -------
    if (archiveListOpen) {
        EntryPickerDialog(
            title = stringResource(R.string.image_open_from_archive),
            entries = archive.entries.filter { !it.isDirectory && SafUtils.hasImageExt(it.name) }.map { it.name },
            onPick = { name ->
                archiveListOpen = false
                scope.launch {
                    image.loadFromArchive(name)
                        .onFailure { toast(context, context.getString(R.string.image_load_failed)) }
                }
            },
            onDismiss = { archiveListOpen = false }
        )
    }
    if (cropOpen && bmp != null) {
        var l by remember { mutableStateOf(0f) }
        var t by remember { mutableStateOf(0f) }
        var r by remember { mutableStateOf(1f) }
        var b by remember { mutableStateOf(1f) }
        AlertDialog(
            onDismissRequest = { cropOpen = false },
            title = { Text(stringResource(R.string.image_crop)) },
            text = {
                Column {
                    Text("L: %.0f%%".format(l * 100)); Slider(value = l, onValueChange = { l = it }, valueRange = 0f..0.9f)
                    Text("T: %.0f%%".format(t * 100)); Slider(value = t, onValueChange = { t = it }, valueRange = 0f..0.9f)
                    Text("R: %.0f%%".format(r * 100)); Slider(value = r, onValueChange = { r = it }, valueRange = 0.1f..1f)
                    Text("B: %.0f%%".format(b * 100)); Slider(value = b, onValueChange = { b = it }, valueRange = 0.1f..1f)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    image.commitEdited(ImageOps.cropFractions(bmp, l, t, r, b))
                    cropOpen = false
                }) { Text(stringResource(R.string.action_apply)) }
            },
            dismissButton = {
                TextButton(onClick = { cropOpen = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
    if (resizeOpen && bmp != null) {
        var w by remember { mutableStateOf(bmp.width.toString()) }
        var h by remember { mutableStateOf(bmp.height.toString()) }
        var keepAspect by remember { mutableStateOf(true) }
        AlertDialog(
            onDismissRequest = { resizeOpen = false },
            title = { Text(stringResource(R.string.image_resize)) },
            text = {
                Column {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(w, { w = it }, label = { Text(stringResource(R.string.image_width)) }, modifier = Modifier.weight(1f))
                        OutlinedTextField(h, { h = it }, label = { Text(stringResource(R.string.image_height)) }, modifier = Modifier.weight(1f))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(keepAspect, { keepAspect = it })
                        Text(stringResource(R.string.image_keep_aspect), Modifier.padding(start = 8.dp))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val width = w.toIntOrNull() ?: bmp.width
                    val height = if (keepAspect) {
                        (width.toDouble() / bmp.width * bmp.height).toInt().coerceAtLeast(1)
                    } else h.toIntOrNull() ?: bmp.height
                    image.commitEdited(ImageOps.scaleTo(bmp, width, height))
                    resizeOpen = false
                }) { Text(stringResource(R.string.action_apply)) }
            },
            dismissButton = {
                TextButton(onClick = { resizeOpen = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
    if (infoOpen && image.originalBytes != null) {
        val cur = bmp
        AlertDialog(
            onDismissRequest = { infoOpen = false },
            title = { Text(stringResource(R.string.image_info)) },
            text = {
                Column {
                    Text(image.sourceName ?: "")
                    if (cur != null) Text(stringResource(R.string.image_dimensions, cur.width, cur.height))
                    Text(stringResource(R.string.image_file_size, formatBytes(image.originalBytes!!.size.toLong())))
                }
            },
            confirmButton = {
                TextButton(onClick = { infoOpen = false }) { Text(stringResource(R.string.action_ok)) }
            }
        )
    }
    if (importTargetOpen) {
        InputDialog(
            title = stringResource(R.string.archive_import_target),
            label = stringResource(R.string.common_path),
            initial = "images",
            onConfirm = { dir ->
                importTargetOpen = false
                scope.launch {
                    archive.addFiles(dir.trim().trim('/'), pendingImportUris.map {
                        it to SafUtils.displayName(context.contentResolver, it)
                    })
                }
            },
            onDismiss = { importTargetOpen = false }
        )
    }
    if (saveFolderOpen) {
        InputDialog(
            title = stringResource(R.string.image_save),
            label = stringResource(R.string.common_name),
            initial = (image.sourceName ?: "image").substringBeforeLast('.'),
            onConfirm = { name ->
                saveFolderOpen = false
                pendingSaveName = name
                treePicker()
            },
            onDismiss = { saveFolderOpen = false }
        )
    }
}

// simple channel removed — pendingSaveName state lives in ImageScreen

@Composable
internal fun EntryPickerDialog(
    title: String,
    entries: List<String>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Column(
                    Modifier
                        .fillMaxWidth()
                        .height(320.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    entries.filter { it.contains(query, ignoreCase = true) }.forEach { name ->
                        TextButton(onClick = { onPick(name) }, modifier = Modifier.fillMaxWidth()) {
                            Text(name, maxLines = 1, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}
