package com.rustedwarfare.modstudio.core

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.mutableStateOf
import org.json.JSONArray

/** Recently opened mod folders / archives (most recent first, max 6). */
class RecentsStore(context: Context) {

    data class Recent(val uri: String, val name: String, val time: Long)

    private val prefs = context.getSharedPreferences("rwms_recents", Context.MODE_PRIVATE)

    val recents by lazy { mutableStateOf(load()) }

    private fun load(): List<Recent> {
        return try {
            val arr = JSONArray(prefs.getString("items", "[]") ?: "[]")
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Recent(o.getString("uri"), o.getString("name"), o.getLong("time"))
            }.sortedByDescending { it.time }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun save() {
        val arr = JSONArray()
        recents.value.take(6).forEach { r ->
            arr.put(
                org.json.JSONObject()
                    .put("uri", r.uri)
                    .put("name", r.name)
                    .put("time", r.time)
            )
        }
        prefs.edit().putString("items", arr.toString()).apply()
    }

    fun add(uri: Uri, name: String) {
        val list = recents.value.filterNot { it.uri == uri.toString() }.toMutableList()
        list.add(0, Recent(uri.toString(), name, System.currentTimeMillis()))
        recents.value = list.take(6)
        save()
    }

    fun remove(uri: String) {
        recents.value = recents.value.filterNot { it.uri == uri }
        save()
    }
}
