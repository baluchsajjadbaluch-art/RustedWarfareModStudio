package com.rustedwarfare.modstudio.project

import android.app.Application
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.documentfile.provider.DocumentFile
import com.rustedwarfare.modstudio.core.RecentsStore
import com.rustedwarfare.modstudio.core.SafUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Mod project manager: opens a SAF tree and provides the full set of file
 * operations (create / rename / delete / copy / move), sorted listing and
 * recursive copy for folders. Recent projects are remembered.
 */
class ProjectRepository(
    private val app: Application,
    private val recents: RecentsStore
) {

    enum class SortMode { NAME, SIZE, TYPE, DATE }

    var root by mutableStateOf<DocumentFile?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var status by mutableStateOf<String?>(null)
        private set
    var sortMode by mutableStateOf(SortMode.NAME)

    private val resolver get() = app.contentResolver

    suspend fun openTree(uri: Uri): Result<DocumentFile> = withContext(Dispatchers.IO) {
        try {
            val doc = DocumentFile.fromTreeUri(app, uri)
                ?: throw IOException("Invalid folder")
            root = doc
            recents.add(uri, doc.name ?: "folder")
            status = "Opened ${doc.name}"
            Result.success(doc)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    fun close() {
        root = null
    }

    fun children(dir: DocumentFile, query: String = ""): List<DocumentFile> {
        val kids = dir.listFiles().filterNotNull().let { list ->
            when (sortMode) {
                SortMode.NAME -> list.sortedWith(compareBy({ !it.isDirectory }, { (it.name ?: "").lowercase() }))
                SortMode.SIZE -> list.sortedWith(compareBy({ !it.isDirectory }, { it.length() }))
                SortMode.TYPE -> list.sortedWith(compareBy({ !it.isDirectory }, { SafUtils.mimeFor(it.name ?: "") }))
                SortMode.DATE -> list.sortedByDescending { it.lastModified() }
            }
        }
        if (query.isBlank()) return kids
        val q = query.lowercase()
        return kids.filter { (it.name ?: "").lowercase().contains(q) }
    }

    /** Recursive search across the whole tree (flat results). */
    fun searchTree(query: String, limit: Int = 300): List<DocumentFile> {
        if (query.isBlank()) return emptyList()
        val q = query.lowercase()
        val out = ArrayList<DocumentFile>()
        val r = root ?: return out
        for ((_, doc) in SafUtils.walkFiles(r, limit = 2000)) {
            if ((doc.name ?: "").lowercase().contains(q)) {
                out.add(doc)
                if (out.size >= limit) break
            }
        }
        return out
    }

    // -------------------------------------------------------- operations

    private fun mimeFor(name: String): String {
        val mm = MimeTypeMap.getSingleton()
        val ext = mm.getFileExtensionFromUrl(name)?.lowercase()
        return ext?.let { mm.getMimeTypeFromExtension(it) } ?: "application/octet-stream"
    }

    /**
     * Create a file with an EXACT name. Some providers rewrite the name based
     * on the MIME type (e.g. foo.ini -> foo.txt); we detect that and rename
     * back through DocumentsContract.
     */
    suspend fun createFile(dir: DocumentFile, name: String): Result<DocumentFile> =
        withContext(Dispatchers.IO) {
            busy = true
            try {
                require(name.isNotBlank())
                val created = dir.createFile(mimeFor(name), name)
                    ?: throw IOException("createFile failed")
                val fixed: DocumentFile = if (created.name != name) {
                    try {
                        DocumentsContract.renameDocument(resolver, created.uri, name)
                        dir.findFile(name) ?: created
                    } catch (_: Exception) {
                        created
                    }
                } else created
                status = "Created $name"
                Result.success(fixed)
            } catch (t: Throwable) {
                Result.failure(t)
            } finally {
                busy = false
            }
        }

    suspend fun createFolder(dir: DocumentFile, name: String): Result<DocumentFile> =
        withContext(Dispatchers.IO) {
            busy = true
            try {
                val created = dir.createDirectory(name)
                    ?: throw IOException("createDirectory failed")
                status = "Created folder $name"
                Result.success(created)
            } catch (t: Throwable) {
                Result.failure(t)
            } finally {
                busy = false
            }
        }

    suspend fun delete(files: Collection<DocumentFile>): Result<Int> = withContext(Dispatchers.IO) {
        busy = true
        var n = 0
        try {
            for (f in files) {
                if (f.delete()) n++
            }
            status = "Deleted $n item(s)"
            Result.success(n)
        } catch (t: Throwable) {
            Result.failure(t)
        } finally {
            busy = false
        }
    }

    suspend fun rename(file: DocumentFile, newName: String): Result<DocumentFile> =
        withContext(Dispatchers.IO) {
            busy = true
            try {
                val newUri = DocumentsContract.renameDocument(resolver, file.uri, newName)
                val doc = file.parentFile?.findFile(newName)
                    ?: DocumentFile.fromSingleUri(app, newUri)
                    ?: file
                status = "Renamed to $newName"
                Result.success(doc)
            } catch (t: Throwable) {
                Result.failure(t)
            } finally {
                busy = false
            }
        }

    /** Recursive copy of a file or folder into [targetDir]. */
    suspend fun copyTree(src: DocumentFile, targetDir: DocumentFile): Result<Int> =
        withContext(Dispatchers.IO) {
            busy = true
            var count = 0
            try {
                fun rec(node: DocumentFile, destDir: DocumentFile) {
                    if (node.isDirectory) {
                        val name = node.name ?: return
                        val sub = destDir.findFile(name)?.takeIf { it.isDirectory }
                            ?: destDir.createDirectory(name)
                            ?: throw IOException("Cannot create folder $name")
                        for (k in node.listFiles()) rec(k, sub)
                    } else {
                        val name = node.name ?: return
                        val dest = destDir.createFile(mimeFor(name), name)
                            ?: throw IOException("Cannot create $name")
                        resolver.openInputStream(node.uri)!!.use { ins ->
                            resolver.openOutputStream(dest.uri, "wt")!!.use { os ->
                                ins.copyTo(os, 64 * 1024)
                            }
                        }
                        count++
                    }
                }
                rec(src, targetDir)
                status = "Copied $count file(s)"
                Result.success(count)
            } catch (t: Throwable) {
                Result.failure(t)
            } finally {
                busy = false
            }
        }

    /** Move = copy + delete original (SAF has no atomic move). */
    suspend fun moveTree(src: DocumentFile, targetDir: DocumentFile): Result<Int> =
        withContext(Dispatchers.IO) {
            val srcParent = src.parentFile
            if (srcParent != null && srcParent.uri == targetDir.uri) {
                return@withContext Result.failure(IOException("Already in this folder"))
            }
            val copied = copyTree(src, targetDir)
            if (copied.isFailure) return@withContext copied
            delete(listOf(src))
            copied
        }

    /** Copy picked SAF documents into a folder of this tree (batch import). */
    suspend fun importUris(targetDir: DocumentFile, uris: List<Uri>): Result<Int> =
        withContext(Dispatchers.IO) {
            busy = true
            var n = 0
            try {
                for (u in uris) {
                    val name = SafUtils.displayName(resolver, u)
                    val dest = targetDir.createFile(mimeFor(name), name)
                        ?: continue
                    resolver.openInputStream(u)!!.use { ins ->
                        resolver.openOutputStream(dest.uri, "wt")!!.use { os -> ins.copyTo(os, 64 * 1024) }
                    }
                    n++
                }
                status = "Imported $n file(s)"
                Result.success(n)
            } catch (t: Throwable) {
                Result.failure(t)
            } finally {
                busy = false
            }
        }

    fun clearStatus() {
        status = null
    }
}
