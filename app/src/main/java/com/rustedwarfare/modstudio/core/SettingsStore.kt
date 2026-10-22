package com.rustedwarfare.modstudio.core

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/** App settings backed by SharedPreferences, exposed as Compose state. */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("rwms_settings", Context.MODE_PRIVATE)

    companion object {
        const val THEME_SYSTEM = "system"
        const val THEME_DARK = "dark"
        const val THEME_LIGHT = "light"
    }

    var themeMode: String by mutableStateOf(prefs.getString("theme", THEME_SYSTEM) ?: THEME_SYSTEM)
        private set

    var editorFontSize: Float by mutableStateOf(prefs.getFloat("font", 15f))
        private set

    var undoDepth: Int by mutableStateOf(prefs.getInt("undo", 200))
        private set

    var keepScreenOn: Boolean by mutableStateOf(prefs.getBoolean("keepScreen", false))
        private set

    fun updateTheme(mode: String) {
        themeMode = mode
        prefs.edit().putString("theme", mode).apply()
    }

    fun updateEditorFontSize(size: Float) {
        editorFontSize = size.coerceIn(10f, 28f)
        prefs.edit().putFloat("font", editorFontSize).apply()
    }

    fun updateUndoDepth(depth: Int) {
        undoDepth = depth.coerceIn(20, 1000)
        prefs.edit().putInt("undo", undoDepth).apply()
    }

    fun updateKeepScreenOn(v: Boolean) {
        keepScreenOn = v
        prefs.edit().putBoolean("keepScreen", v).apply()
    }
}
