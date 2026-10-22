package com.rustedwarfare.modstudio.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FindInPage
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.rustedwarfare.modstudio.AppGraph
import com.rustedwarfare.modstudio.R
import com.rustedwarfare.modstudio.editor.EditorRepository
import com.rustedwarfare.modstudio.editor.IniVisualTransformation
import com.rustedwarfare.modstudio.ui.BusyOverlay
import com.rustedwarfare.modstudio.ui.ConfirmDialog
import com.rustedwarfare.modstudio.ui.InputDialog
import com.rustedwarfare.modstudio.ui.rememberOpenDocPicker
import com.rustedwarfare.modstudio.ui.toast
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(graph: AppGraph, nav: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val editor = graph.editor

    var showFind by remember { mutableStateOf(false) }
    var gotoOpen by remember { mutableStateOf(false) }
    var closeTabPending by remember { mutableStateOf<EditorRepository.EditBuffer?>(null) }
    var searchAllOpen by remember { mutableStateOf(false) }

    val openDoc = rememberOpenDocPicker(arrayOf("*/*")) { uri ->
        scope.launch {
            editor.openSafFile(uri)
                .onFailure { toast(context, context.getString(R.string.editor_save_failed, it.message ?: "")) }
        }
    }

    StatusToast(editor.status) { editor.clearStatus() }

    val buffer = editor.active

    ScreenScaffold(
        title = stringResource(R.string.editor_title),
        onBack = null,
        actions = {
            IconButton(onClick = { openDoc() }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.editor_open_file))
            }
            IconButton(onClick = { searchAllOpen = true }) {
                Icon(Icons.Filled.FindInPage, contentDescription = stringResource(R.string.editor_find))
            }
            IconButton(onClick = {
                buffer?.let { b ->
                    scope.launch {
                        editor.save(b)
                            .onFailure { toast(context, context.getString(R.string.editor_save_failed, it.message ?: "")) }
                    }
                }
            }) {
                Icon(Icons.Filled.Save, contentDescription = stringResource(R.string.editor_save))
            }
        },
        content = { pad ->
            if (buffer == null) {
                Column(
                    Modifier.fillMaxSize().padding(pad).padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Filled.Description, contentDescription = null)
                    Text(stringResource(R.string.editor_hint_empty), Modifier.padding(top = 12.dp))
                    Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        androidx.compose.material3.Button(onClick = { openDoc() }) {
                            Text(stringResource(R.string.editor_open_file))
                        }
                        androidx.compose.material3.Button(onClick = { nav.navigate(com.rustedwarfare.modstudio.Routes.ARCHIVE) }) {
                            Icon(Icons.Filled.Archive, contentDescription = null)
                            Text(stringResource(R.string.editor_open_from_archive))
                        }
                    }
                }
            } else {
                EditorBody(
                    graph = graph,
                    buffer = buffer,
                    showFind = showFind,
                    onToggleFind = { showFind = !showFind },
                    onGoto = { gotoOpen = true },
                    onCloseTab = { closeTabPending = it }
                )
            }
        }
    )

    // -------- tab bar (below scaffold content uses its own surface) --------
    // (Tabs are rendered inside EditorBody when buffers exist)

    if (gotoOpen) {
        InputDialog(
            title = stringResource(R.string.editor_goto_line),
            label = stringResource(R.string.editor_goto_line),
            initial = "1",
            onConfirm = { line ->
                gotoOpen = false
                val n = line.trim().toIntOrNull() ?: return@InputDialog
                gotoLineSignal.intValue = n
            },
            onDismiss = { gotoOpen = false }
        )
    }

    closeTabPending?.let { b ->
        if (b.isDirty) {
            ConfirmDialog(
                title = stringResource(R.string.editor_close_tab),
                text = stringResource(R.string.editor_confirm_close),
                confirmLabel = stringResource(R.string.action_close),
                onConfirm = {
                    editor.closeBuffer(b)
                    closeTabPending = null
                },
                onDismiss = { closeTabPending = null }
            )
        } else {
            editor.closeBuffer(b)
            closeTabPending = null
        }
    }

    if (searchAllOpen) {
        ProjectSearchDialog(graph) { searchAllOpen = false }
    }
}

// signal channel from the toolbar dialog to the editor body
private val gotoLineSignal = androidx.compose.runtime.mutableIntStateOf(0)

@Composable
private fun EditorBody(
    graph: AppGraph,
    buffer: EditorRepository.EditBuffer,
    showFind: Boolean,
    onToggleFind: () -> Unit,
    onGoto: () -> Unit,
    onCloseTab: (EditorRepository.EditBuffer) -> Unit
) {
    val editor = graph.editor
    val scope = rememberCoroutineScope()
    val settings = graph.settings
    val density = LocalDensity.current
    val context = LocalContext.current
    val scroll = rememberScrollState()

    val fontSize = settings.editorFontSize
    val lineSp = fontSize * 1.35f

    var tfv by remember(buffer.id) { mutableStateOf(TextFieldValue(buffer.text, TextRange(buffer.selection.start))) }

    // sync external changes (undo/redo/replace-all/goto)
    LaunchedEffect(buffer.id, buffer.text) {
        if (buffer.text != tfv.text) {
            tfv = TextFieldValue(buffer.text, buffer.selection)
        }
    }

    // goto line support
    LaunchedEffect(gotoLineSignal.intValue) {
        val line = gotoLineSignal.intValue
        if (line > 0) {
            val lineHeightPx = with(density) { lineSp.sp.toDp().toPx() }
            val target = ((line - 1).coerceAtLeast(0)) * lineHeightPx
            scroll.animateScrollTo(target.toInt())
        }
    }

    // find state
    var query by remember(buffer.id) { mutableStateOf("") }
    var replacement by remember(buffer.id) { mutableStateOf("") }
    var caseSensitive by remember(buffer.id) { mutableStateOf(false) }
    var matchIndex by remember(buffer.id) { mutableStateOf(0) }
    val matches = remember(tfv.text, query, caseSensitive) {
        editor.findMatches(tfv.text, query, caseSensitive)
    }

    fun jumpTo(m: EditorRepository.Match) {
        tfv = tfv.copy(selection = TextRange(m.start, m.end))
        val line = tfv.text.substring(0, m.start).count { it == '\n' }
        val lineHeightPx = with(density) { lineSp.sp.toDp().toPx() }
        scope.launch { scroll.animateScrollTo(((line - 2).coerceAtLeast(0) * lineHeightPx).toInt()) }
    }

    Column(Modifier.fillMaxSize()) {
        // ---- tab strip ----
        if (editor.buffers.size > 1) {
            Surface(tonalElevation = 2.dp) {
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    editor.buffers.forEach { b ->
                        FilterChip(
                            selected = b.id == buffer.id,
                            onClick = { editor.activeId = b.id },
                            label = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        (if (b.isDirty) stringResource(R.string.editor_unsaved) else "") + b.name,
                                        maxLines = 1
                                    )
                                    IconButton(
                                        onClick = { onCloseTab(b) },
                                        modifier = Modifier.width(20.dp).height(20.dp)
                                    ) {
                                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.editor_close_tab), modifier = Modifier.width(14.dp))
                                    }
                                }
                            },
                            modifier = Modifier.padding(4.dp)
                        )
                    }
                }
            }
        }

        // ---- find bar ----
        if (showFind) {
            Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it; matchIndex = 0 },
                            placeholder = { Text(stringResource(R.string.editor_find_hint), style = MaterialTheme.typography.bodySmall) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = {
                            if (matches.isNotEmpty()) {
                                matchIndex = (matchIndex - 1 + matches.size) % matches.size
                                jumpTo(matches[matchIndex])
                            }
                        }) { Text(stringResource(R.string.editor_prev)) }
                        TextButton(onClick = {
                            if (matches.isNotEmpty()) {
                                matchIndex = (matchIndex + 1) % matches.size
                                jumpTo(matches[matchIndex])
                            }
                        }) { Text(stringResource(R.string.editor_next)) }
                    }
                    Text(
                        if (query.isEmpty()) "" else
                            if (matches.isEmpty()) stringResource(R.string.editor_no_matches)
                            else stringResource(R.string.editor_matches, matches.size),
                        style = MaterialTheme.typography.labelSmall
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(
                            value = replacement,
                            onValueChange = { replacement = it },
                            placeholder = { Text(stringResource(R.string.editor_replace_hint), style = MaterialTheme.typography.bodySmall) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = {
                            val m = matches.getOrNull(matchIndex) ?: return@TextButton
                            val sb = StringBuilder()
                            sb.append(tfv.text, 0, m.start).append(replacement).append(tfv.text.substring(m.end))
                            editor.setText(buffer, sb.toString())
                            tfv = TextFieldValue(sb.toString(), TextRange(m.start + replacement.length))
                        }) { Text(stringResource(R.string.editor_replace_one)) }
                        TextButton(onClick = {
                            val n = editor.replaceAll(buffer, query, replacement, caseSensitive)
                            matchIndex = 0
                            if (n == 0) toast(context, context.getString(R.string.editor_no_matches))
                        }) { Text(stringResource(R.string.editor_replace_all)) }
                        FilterChip(
                            selected = caseSensitive,
                            onClick = { caseSensitive = !caseSensitive },
                            label = { Text(stringResource(R.string.editor_case_sensitive)) }
                        )
                    }
                }
            }
        }

        // ---- editor area with line numbers (shared scroll) ----
        Row(
            Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            val lineCount = remember(tfv.text) { tfv.text.count { it == '\n' } + 1 }
            Column(
                Modifier
                    .width(52.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .verticalScroll(scroll)
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                repeat(lineCount) { i ->
                    Text(
                        "${i + 1}",
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = (fontSize * 0.8f).sp,
                            lineHeight = lineSp.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }
            BasicTextField(
                value = tfv,
                onValueChange = {
                    tfv = it
                    editor.setText(buffer, it.text)
                },
                textStyle = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = fontSize.sp,
                    lineHeight = lineSp.sp,
                    color = MaterialTheme.colorScheme.onSurface
                ),
                visualTransformation = if (buffer.name.endsWith(".ini", true)) IniVisualTransformation(true) else IniVisualTransformation(false),
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(scroll)
                    .padding(8.dp)
            )
        }

        // ---- status / toolbar ----
        Surface(tonalElevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { editor.undo(buffer) }) {
                    Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = stringResource(R.string.editor_undo))
                }
                IconButton(onClick = { editor.redo(buffer) }) {
                    Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = stringResource(R.string.editor_redo))
                }
                IconButton(onClick = onToggleFind) {
                    Icon(Icons.Filled.FindInPage, contentDescription = stringResource(R.string.editor_find))
                }
                TextButton(onClick = onGoto) { Text(stringResource(R.string.editor_goto_line)) }
                Spacer(Modifier.weight(1f))
                Text(
                    stringResource(
                        R.string.editor_lines_chars,
                        tfv.text.count { it == '\n' } + 1,
                        tfv.text.length
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun ProjectSearchDialog(graph: AppGraph, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var caseSensitive by remember { mutableStateOf(false) }
    var hits by remember { mutableStateOf<List<EditorRepository.SearchHit>>(emptyList()) }
    var running by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.editor_find)) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(R.string.editor_find_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        selected = caseSensitive,
                        onClick = { caseSensitive = !caseSensitive },
                        label = { Text(stringResource(R.string.editor_case_sensitive)) }
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = {
                        running = true
                        scope.launch {
                            hits = graph.editor.searchProject(query, caseSensitive)
                            running = false
                        }
                    }) { Text(stringResource(R.string.editor_find)) }
                }
                Text(
                    if (running) stringResource(R.string.common_loading)
                    else stringResource(R.string.editor_matches, hits.size),
                    style = MaterialTheme.typography.labelSmall
                )
                Column(
                    Modifier
                        .fillMaxWidth()
                        .height(280.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    hits.forEach { h ->
                        Column(Modifier.padding(vertical = 4.dp)) {
                            Text(
                                "${h.file}:${h.line}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                h.text,
                                style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        }
    )
}
