package com.rustedwarfare.modstudio.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import com.rustedwarfare.modstudio.core.SettingsStore

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(graph: AppGraph, nav: NavController) {
    val settings = graph.settings
    ScreenScaffold(
        title = stringResource(R.string.settings_title),
        onBack = { nav.popBackStack() },
        content = { pad ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(pad)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.titleMedium)
                ThemeOption(stringResource(R.string.settings_system), settings.themeMode == SettingsStore.THEME_SYSTEM) {
                    settings.setTheme(SettingsStore.THEME_SYSTEM)
                }
                ThemeOption(stringResource(R.string.settings_dark), settings.themeMode == SettingsStore.THEME_DARK) {
                    settings.setTheme(SettingsStore.THEME_DARK)
                }
                ThemeOption(stringResource(R.string.settings_light), settings.themeMode == SettingsStore.THEME_LIGHT) {
                    settings.setTheme(SettingsStore.THEME_LIGHT)
                }

                Spacer(Modifier.padding(top = 16.dp))
                Text(stringResource(R.string.settings_font) + ": ${settings.editorFontSize.toInt()}")
                Slider(
                    value = settings.editorFontSize,
                    onValueChange = { settings.setEditorFontSize(it) },
                    valueRange = 10f..28f,
                    steps = 17
                )

                Spacer(Modifier.padding(top = 8.dp))
                Text(stringResource(R.string.settings_undo_depth) + ": ${settings.undoDepth}")
                Slider(
                    value = settings.undoDepth.toFloat(),
                    onValueChange = { settings.setUndoDepth(it.toInt()) },
                    valueRange = 20f..1000f
                )

                Spacer(Modifier.padding(top = 8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.settings_keep_screen), Modifier.weight(1f))
                    Switch(checked = settings.keepScreenOn, onCheckedChange = { settings.setKeepScreenOn(it) })
                }

                Spacer(Modifier.padding(top = 24.dp))
                Text(stringResource(R.string.settings_about), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.settings_version, "1.0.0"),
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    stringResource(R.string.settings_locale_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    )
}

@Composable
private fun ThemeOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label)
    }
}
