package com.rustedwarfare.modstudio.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.rustedwarfare.modstudio.AppGraph
import com.rustedwarfare.modstudio.R
import com.rustedwarfare.modstudio.ui.BusyOverlay
import com.rustedwarfare.modstudio.ui.rememberTreePicker
import com.rustedwarfare.modstudio.ui.toast
import com.rustedwarfare.modstudio.wizard.WizardRepository
import kotlinx.coroutines.launch

@Composable
fun NewModWizardScreen(graph: AppGraph, nav: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val wizard = graph.wizard

    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var author by remember { mutableStateOf("") }
    var version by remember { mutableStateOf("1.0") }
    var asFolder by remember { mutableStateOf(true) }
    var parentUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var parentName by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }

    val parentPicker = rememberTreePicker { uri ->
        parentUri = uri
        parentName = com.rustedwarfare.modstudio.core.SafUtils.displayName(context.contentResolver, uri)
    }

    ScreenScaffold(
        title = stringResource(R.string.wizard_title),
        onBack = { nav.popBackStack() },
        content = { pad ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(pad)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.wizard_name)) },
                    placeholder = { Text(stringResource(R.string.wizard_name_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.wizard_description)) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                OutlinedTextField(
                    value = author,
                    onValueChange = { author = it },
                    label = { Text(stringResource(R.string.wizard_author)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                OutlinedTextField(
                    value = version,
                    onValueChange = { version = it },
                    label = { Text(stringResource(R.string.wizard_version)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )

                Card(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    Column(Modifier.padding(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = asFolder, onClick = { asFolder = true })
                            Text(stringResource(R.string.wizard_as_folder))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = !asFolder, onClick = { asFolder = false })
                            Text(stringResource(R.string.wizard_as_rwmod))
                        }
                    }
                }

                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(onClick = { parentPicker() }) {
                        Text(
                            parentName ?: stringResource(R.string.wizard_pick_parent),
                            maxLines = 1
                        )
                    }
                }

                Text(
                    stringResource(R.string.wizard_info_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp)
                )

                Button(
                    onClick = {
                        if (name.isBlank()) {
                            toast(context, context.getString(R.string.wizard_name_required))
                            return@Button
                        }
                        val parent = parentUri
                        if (parent == null) {
                            toast(context, context.getString(R.string.wizard_pick_parent))
                            return@Button
                        }
                        creating = true
                        scope.launch {
                            val meta = WizardRepository.ModMeta(name.trim(), description.trim(), author.trim(), version.trim())
                            val result = if (asFolder) {
                                wizard.createFolderMod(parent, meta)
                            } else {
                                wizard.createRwmod(parent, meta, graph.archive)
                            }
                            creating = false
                            result
                                .onSuccess {
                                    toast(context, context.getString(R.string.wizard_created_ok) + ": $it")
                                    nav.popBackStack()
                                }
                                .onFailure { toast(context, it.message ?: "") }
                        }
                    },
                    enabled = !creating,
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
                ) {
                    Text(stringResource(R.string.wizard_create))
                }
            }
        }
    )

    if (creating) {
        BusyOverlay(stringResource(R.string.wizard_creating), null, null)
    }
}
