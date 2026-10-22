package com.rustedwarfare.modstudio.editor

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.documentfile.provider.DocumentFile
import com.rustedwarfare.modstudio.archive.ArchiveRepository
import com.rustedwarfare.modstudio.core.SettingsStore
import com.rustedwarfare.modstudio.core.SafUtils
import com.rustedwarfare.modstudio.core.ZipPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.charset.Charset
import java.util.UUID

/**
 * Multi-tab code/INI editor engine: undo/redo (coalesced), find & replace,
 * per-tab charset, saving to SAF files or straight into the open archive,
 * plus project-wide text search.
 */
class EditorRepository(
    private val app: Application,
    private val archive: ArchiveRepository,
    private val settings: SettingsStore,
    private val project: com.rustedwarfare.modstudio.project.ProjectRepository
) {

    sealed interface Source {
        data class SafFile(val uri: Uri) : Source
        data class InArchive(val entryName: String) : Source
    }

    class EditBuffer(
        val id: String,
        val source: Source,
        val name: String,
        initialText: String,
        val charset: Charset
    ) {
        var text: String by mutableStateOf(initialText)
        var savedText: String by mutableStateOf(initialText)
        var selection: TextRange by mutableStateOf(TextRange(initialText.length))
        val undoStack = ArrayDeque<String>()
        val redoStack = ArrayDeque<String>()
        var lastPushMs: Long = 0L
        val isDirty: Boolean get() = text != savedText
    }

    val buffers = mutableStateListOf<EditBuffer>()
    var activeId by mutableStateOf<String?>(null)
    var status by mutableStateOf<String?>(null)
        private set

    val active: EditBuffer?
        get() = buffers.firstOrNull { it.id == activeId }

    val dirtyCount: Int
        get() = buffers.count { it.isDirty }

    // ------------------------------------------------------------- opening

    private val resolver get() = app.contentResolver

    suspend fun openSafFile(uri: Uri): Result<EditBuffer> = withContext(Dispatchers.IO) {
        try {
            val existing = buffers.firstOrNull {
                it.source is Source.SafFile && (it.source as Source.SafFile).uri == uri
            }
            if (existing != null) {
                activeId = existing.id
                return@withContext Result.success(existing)
            }
            val name = SafUtils.displayName(resolver, uri)
            val (text, cs) = SafUtils.readText(resolver, uri, maxBytes = 4L * 1024 * 1024)
            addBuffer(Source.SafFile(uri), name, text, cs)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    suspend fun openArchiveEntry(entryName: String): Result<EditBuffer> = withContext(Dispatchers.IO) {
        try {
            val existing = buffers.firstOrNull {
                it.source is Source.InArchive && it.source.entryName == entryName
            }
            if (existing != null) {
                activeId = existing.id
                return@withContext Result.success(existing)
            }
            if (archive.current == null) throw IOException("No archive open")
            val (text, cs) = archive.readEntryText(entryName)
            addBuffer(Source.InArchive(entryName), ZipPaths.fileName(entryName), text, cs)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    private fun addBuffer(source: Source, name: String, text: String, cs: Charset): Result<EditBuffer> {
        if (text.length > 4_000_000) throw IOException("File too large for the in-memory editor")
        if (buffers.size >= 8) throw IOException("Too many open tabs (max 8)")
        val b = EditBuffer(UUID.randomUUID().toString(), source, name, text, cs)
        buffers.add(b)
        activeId = b.id
        return Result.success(b)
    }

    // ------------------------------------------------------------- editing

    fun setText(b: EditBuffer, newText: String) {
        val now = System.currentTimeMillis()
        if (b.undoStack.isEmpty() || now - b.lastPushMs > 400) {
            b.undoStack.addLast(b.text)
            while (b.undoStack.size > settings.undoDepth) b.undoStack.removeFirst()
            b.redoStack.clear()
        }
        b.lastPushMs = now
        b.text = newText
    }

    fun undo(b: EditBuffer): Boolean {
        val prev = b.undoStack.removeLastOrNull() ?: return false
        b.redoStack.addLast(b.text)
        b.text = prev
        b.lastPushMs = 0L
        return true
    }

    fun redo(b: EditBuffer): Boolean {
        val next = b.redoStack.removeLastOrNull() ?: return false
        b.undoStack.addLast(b.text)
        b.text = next
        b.lastPushMs = 0L
        return true
    }

    fun closeBuffer(b: EditBuffer) {
        val idx = buffers.indexOf(b)
        buffers.remove(b)
        activeId = when {
            buffers.isEmpty() -> null
            idx < buffers.size -> buffers[idx.coerceAtLeast(0)].id
            else -> buffers.last().id
        }
    }

    // ------------------------------------------------------------- saving

    suspend fun save(b: EditBuffer): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            when (val s = b.source) {
                is Source.SafFile -> SafUtils.writeText(resolver, s.uri, b.text, b.charset)
                is Source.InArchive -> {
                    val r = archive.replaceEntryBytes(s.entryName, b.text.toByteArray(b.charset))
                    if (r.isFailure) throw r.exceptionOrNull() ?: IOException("Archive save failed")
                }
            }
            b.savedText = b.text
            status = app.getString(com.rustedwarfare.modstudio.R.string.editor_saved)
            Result.success(Unit)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    // ------------------------------------------------------ find & replace

    data class Match(val start: Int, val end: Int)

    fun findMatches(text: String, query: String, caseSensitive: Boolean): List<Match> {
        if (query.isEmpty()) return emptyList()
        val hay = if (caseSensitive) text else text.lowercase()
        val needle = if (caseSensitive) query else query.lowercase()
        val out = ArrayList<Match>()
        var i = hay.indexOf(needle)
        while (i >= 0 && out.size < 20_000) {
            out.add(Match(i, i + needle.length))
            i = hay.indexOf(needle, i + needle.length)
        }
        return out
    }

    fun replaceAll(b: EditBuffer, query: String, replacement: String, caseSensitive: Boolean): Int {
        val matches = findMatches(b.text, query, caseSensitive)
        if (matches.isEmpty()) return 0
        val sb = StringBuilder(b.text.length + matches.size * replacement.length)
        var last = 0
        for (m in matches) {
            sb.append(b.text, last, m.start)
            sb.append(replacement)
            last = m.end
        }
        sb.append(b.text.substring(last))
        setText(b, sb.toString())
        return matches.size
    }

    // -------------------------------------------------- project-wide search

    data class SearchHit(val file: String, val line: Int, val text: String)

    suspend fun searchProject(query: String, caseSensitive: Boolean): List<SearchHit> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext emptyList<SearchHit>()
            val hits = ArrayList<SearchHit>()
            val needle = if (caseSensitive) query else query.lowercase()

            fun scan(fileName: String, content: String) {
                if (hits.size >= 400) return
                for ((i, ln) in content.lines().withIndex()) {
                    val hay = if (caseSensitive) ln else ln.lowercase()
                    if (needle in hay) {
                        hits.add(SearchHit(fileName, i + 1, ln.trim().take(120)))
                        if (hits.size >= 400) return
                    }
                }
            }

            if (archive.current != null) {
                for (e in archive.entries) {
                    if (hits.size >= 400) break
                    if (!e.isDirectory && SafUtils.hasTextExt(e.name)) {
                        try {
                            scan(e.name, archive.readEntryText(e.name).first)
                        } catch (_: Exception) {
                        }
                    }
                }
            } else {
                val root = project.root
                if (root != null) {
                    for ((rel, doc) in SafUtils.walkFiles(root, limit = 300)) {
                        if (hits.size >= 400) break
                        if (SafUtils.hasTextExt(rel)) {
                            try {
                                val (txt, _) = SafUtils.readText(resolver, doc.uri, maxBytes = 2L * 1024 * 1024)
                                scan(rel, txt)
                            } catch (_: Exception) {
                            }
                        }
                    }
                }
            }
            hits
        }

    fun clearStatus() {
        status = null
    }
}
