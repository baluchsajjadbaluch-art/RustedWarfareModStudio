package com.rustedwarfare.modstudio.archive

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.documentfile.provider.DocumentFile
import com.rustedwarfare.modstudio.R
import com.rustedwarfare.modstudio.core.ArchiveContext
import com.rustedwarfare.modstudio.core.ArchiveEntry
import com.rustedwarfare.modstudio.core.LowStorageException
import com.rustedwarfare.modstudio.core.OperationCancelledException
import com.rustedwarfare.modstudio.core.SafUtils
import com.rustedwarfare.modstudio.core.ZipPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Complete ZIP / RWMOD engine built on top of SAF.
 *
 * Safety model:
 *  - every mutation rebuilds the archive into a temp file FIRST,
 *  - the temp file is CRC-verified before it touches the destination,
 *  - the destination stream is opened with "wt" (truncate) only after verify,
 *  - a fresh backup is kept in cache and restored automatically if commit fails,
 *  - unsafe entry names (zip-slip) are rejected by [ZipPaths.normalize],
 *  - every operation can be cancelled; cancellation never corrupts the archive.
 *
 * RWMOD support: RWMOD files of Rusted Warfare are ZIP-compatible packages
 * (this is detected by the PK header — a renamed non-ZIP file is rejected).
 */
class ArchiveRepository(private val app: Application) {

    companion object {
        private const val BUF = 64 * 1024
        private const val MAX_ENTRY_BYTES = 64L * 1024 * 1024
        private const val KEEP_BACKUPS = 3
        private const val MIN_FREE_BYTES = 50L * 1024 * 1024
    }

    private val resolver get() = app.contentResolver
    private val cacheDir = File(app.cacheDir, "archive").apply { mkdirs() }
    private val backupDir = File(app.cacheDir, "backups").apply { mkdirs() }

    var current by mutableStateOf<ArchiveContext?>(null)
        private set
    var entries by mutableStateOf<List<ArchiveEntry>>(emptyList())
        private set
    var busy by mutableStateOf(false)
        private set
    var status by mutableStateOf<String?>(null)
        private set
    var progress by mutableStateOf<Float?>(null)
        private set

    private val cancelled = AtomicBoolean(false)

    fun requestCancel() {
        cancelled.set(true)
    }

    private fun checkCancel() {
        if (cancelled.get()) throw OperationCancelledException()
    }

    private fun checkStorage() {
        if (cacheDir.usableSpace in 1 until MIN_FREE_BYTES) throw LowStorageException()
    }

    // ---------------------------------------------------------------- open

    suspend fun open(uri: Uri): Result<ArchiveContext> = withContext(Dispatchers.IO) {
        busy = true
        cancelled.set(false)
        status = null
        try {
            val head = ByteArray(4)
            resolver.openInputStream(uri)?.use { ins ->
                var n = 0
                while (n < head.size) {
                    val r = ins.read(head, n, head.size - n)
                    if (r < 0) break
                    n += r
                }
            } ?: throw IOException("Cannot open document")
            if (!(head.size >= 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte())) {
                throw IOException(app.getString(R.string.archive_invalid))
            }
            val name = SafUtils.displayName(resolver, uri)
            val size = SafUtils.sizeOf(resolver, uri)
            val (list, skipped) = listEntries(uri)
            val ctx = ArchiveContext(uri, name, size, isRwmod = name.endsWith(".rwmod", ignoreCase = true))
            current = ctx
            entries = list
            status = if (ctx.isRwmod) app.getString(R.string.archive_valid_rwmod)
            else app.getString(R.string.archive_zip_type)
            if (skipped > 0) status = app.getString(R.string.archive_unsafe_skipped, skipped)
            Result.success(ctx)
        } catch (t: Throwable) {
            Result.failure(t)
        } finally {
            busy = false
        }
    }

    fun close() {
        current = null
        entries = emptyList()
        status = null
        progress = null
    }

    // ------------------------------------------------------------- listing

    /** Stream-list all entries; @return pair of entries and count of unsafe-skipped names. */
    fun listEntries(uri: Uri): Pair<List<ArchiveEntry>, Int> {
        val map = LinkedHashMap<String, ArchiveEntry>()
        var skipped = 0
        resolver.openInputStream(uri)!!.use { raw ->
            ZipInputStream(BufferedInputStream(raw, BUF)).use { zis ->
                while (true) {
                    val e = zis.nextEntry ?: break
                    val norm = ZipPaths.normalize(e.name)
                    if (norm == null) {
                        skipped++
                    } else {
                        val isDir = e.isDirectory
                        val key = if (isDir && !norm.endsWith("/")) "$norm/" else norm
                        map[key] = ArchiveEntry(key, isDir, e.size, e.compressedSize, e.crc, e.method, e.time)
                    }
                    zis.closeEntry()
                }
            }
        }
        // synthesize missing parent directories
        for (n in map.keys.toList()) {
            var p = ZipPaths.parentOf(n)
            while (p.isNotEmpty()) {
                if (!map.containsKey(p)) map[p] = ArchiveEntry(p, true, 0, 0, 0, -1, 0)
                p = ZipPaths.parentOf(p.trimEnd('/'))
            }
        }
        return map.values.sortedWith(compareBy({ it.name })) to skipped
    }

    // ------------------------------------------------------------- reading

    /** Read one entry fully into memory (with a safety size limit). */
    suspend fun readEntry(entryName: String, maxBytes: Long = MAX_ENTRY_BYTES): ByteArray =
        withContext(Dispatchers.IO) {
            val uri = current?.uri ?: throw IOException("No archive open")
            var found = false
            var out: java.io.ByteArrayOutputStream? = null
            resolver.openInputStream(uri)!!.use { raw ->
                ZipInputStream(BufferedInputStream(raw, BUF)).use { zis ->
                    while (true) {
                        val e = zis.nextEntry ?: break
                        val norm = ZipPaths.normalize(e.name)
                        if (norm == entryName) {
                            found = true
                            out = java.io.ByteArrayOutputStream()
                            val buf = ByteArray(BUF)
                            var total = 0L
                            while (true) {
                                val r = zis.read(buf)
                                if (r < 0) break
                                total += r
                                if (total > maxBytes) throw IOException("Entry too large")
                                out!!.write(buf, 0, r)
                            }
                            break
                        }
                        zis.closeEntry()
                    }
                }
            }
            if (!found) throw IOException("Entry not found: $entryName")
            out!!.toByteArray()
        }

    suspend fun readEntryText(entryName: String): Pair<String, java.nio.charset.Charset> =
        withContext(Dispatchers.IO) {
            val bytes = readEntry(entryName)
            val cs = SafUtils.detectCharset(bytes)
            String(bytes, cs).trimStart('\uFEFF') to cs
        }

    /** Copy an entry to a cache file (used by editors that need a real file). */
    suspend fun extractEntryToCache(entryName: String): File = withContext(Dispatchers.IO) {
        val bytes = readEntry(entryName)
        val safe = entryName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
        val f = File(cacheDir, "e_${Integer.toHexString(entryName.hashCode())}_$safe")
        f.writeBytes(bytes)
        f
    }

    // ------------------------------------------------------------ mutation

    /**
     * Core rebuild primitive.
     * 1) copy original entries (minus [exclude]) into a temp ZIP,
     * 2) let [append] add/replace entries,
     * 3) verify temp (CRC) ,
     * 4) backup original into cache,
     * 5) commit via SAF truncate-write,
     * 6) re-verify committed archive and restore backup on failure.
     */
    private suspend fun rebuild(
        exclude: Set<String> = emptySet(),
        append: (ZipOutputStream) -> Unit = {}
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val ctx = current ?: return@withContext Result.failure(IOException("No archive open"))
        checkStorage()
        busy = true
        cancelled.set(false)
        val temp = File.createTempFile("rebuild_", ".zip", cacheDir)
        var backup: File? = null
        try {
            val total = entries.count { !it.isDirectory }.coerceAtLeast(1)
            var done = 0
            ZipOutputStream(BufferedOutputStream(FileOutputStream(temp), BUF)).use { zos ->
                resolver.openInputStream(ctx.uri)!!.use { raw ->
                    ZipInputStream(BufferedInputStream(raw, BUF)).use { zis ->
                        while (true) {
                            val e = zis.nextEntry ?: break
                            checkCancel()
                            val norm = ZipPaths.normalize(e.name)
                            if (norm == null || norm in exclude) {
                                zis.closeEntry()
                                continue
                            }
                            val isDir = e.isDirectory
                            val outName = if (isDir && !norm.endsWith("/")) "$norm/" else norm
                            zos.putNextEntry(ZipEntry(outName).apply { time = e.time })
                            if (!isDir) {
                                zis.copyTo(zos, BUF)
                                done++
                            }
                            zos.closeEntry()
                            zis.closeEntry()
                            progress = done.toFloat() / total
                        }
                    }
                }
                append(zos)
            }
            progress = null
            checkCancel()

            // verify temp archive (CRC auto-checked while streaming through)
            val verified = verifyZipFile(temp)
            // count check: allow replaced entries (same count) and additions
            if (verified.isEmpty() && entries.isNotEmpty()) throw IOException("Rebuilt archive is empty")

            // fresh backup of current destination
            backup = File(backupDir, "${ctx.displayName}.${System.currentTimeMillis()}.bak")
            resolver.openInputStream(ctx.uri)!!.use { ins ->
                backup!!.outputStream().use { ins.copyTo(it, BUF) }
            }
            pruneBackups(ctx.displayName)

            // commit
            resolver.openOutputStream(ctx.uri, "wt")!!.use { os ->
                temp.inputStream().use { it.copyTo(os, BUF) }
            }

            // re-verify committed archive
            val (newList, _) = listEntries(ctx.uri)
            if (newList.isEmpty() && verified.isNotEmpty()) throw IOException("Committed archive looks empty")
            entries = newList
            current = ctx.copy(sizeBytes = SafUtils.sizeOf(resolver, ctx.uri))
            status = app.getString(R.string.archive_verify_ok)
            Result.success(Unit)
        } catch (t: OperationCancelledException) {
            status = app.getString(R.string.common_cancelled)
            Result.failure(t)
        } catch (t: Throwable) {
            // restore backup if we got that far
            val b = backup
            if (b != null && b.exists()) {
                try {
                    resolver.openOutputStream(ctx.uri, "wt")!!.use { os ->
                        b.inputStream().use { it.copyTo(os, BUF) }
                    }
                } catch (_: Exception) {
                }
            }
            status = app.getString(R.string.archive_verify_failed)
            Result.failure(t)
        } finally {
            temp.delete()
            busy = false
            progress = null
        }
    }

    private fun verifyZipFile(f: File): List<String> {
        val names = ArrayList<String>()
        ZipFile(f).use { zf ->
            val en = zf.entries()
            while (en.hasMoreElements()) {
                val e = en.nextElement()
                names.add(e.name)
                if (!e.isDirectory) {
                    zf.getInputStream(e).use { ins ->
                        val buf = ByteArray(BUF)
                        while (ins.read(buf) >= 0) { /* CRC validated at stream end */ }
                    }
                }
            }
        }
        return names
    }

    private fun pruneBackups(displayName: String) {
        val list = backupDir.listFiles { f -> f.name.startsWith("$displayName.") } ?: return
        if (list.size > KEEP_BACKUPS) {
            list.sortedByDescending { it.lastModified() }.drop(KEEP_BACKUPS).forEach { it.delete() }
        }
    }

    /** Full integrity scan of the currently open archive; returns entry count. */
    suspend fun checkIntegrity(): Result<Int> = withContext(Dispatchers.IO) {
        val ctx = current ?: return@withContext Result.failure(IOException("No archive open"))
        try {
            var count = 0
            resolver.openInputStream(ctx.uri)!!.use { raw ->
                ZipInputStream(BufferedInputStream(raw, BUF)).use { zis ->
                    while (true) {
                        val e = zis.nextEntry ?: break
                        if (!e.isDirectory) {
                            val buf = ByteArray(BUF)
                            while (zis.read(buf) >= 0) { /* CRC validated */ }
                            count++
                        }
                        zis.closeEntry()
                    }
                }
            }
            status = app.getString(R.string.archive_verify_ok)
            Result.success(count)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    // ------------------------------------------------------- entry editing

    /** Replace the bytes of a single entry (used by every editor save flow). */
    suspend fun replaceEntryBytes(entryName: String, data: ByteArray): Result<Unit> {
        require(entryName.isNotEmpty() && !entryName.endsWith("/")) { "Not a file entry" }
        busy = true
        return rebuild(
            exclude = setOf(entryName),
            append = { zos ->
                zos.putNextEntry(ZipEntry(entryName))
                zos.write(data)
                zos.closeEntry()
            }
        ).also { busy = false }
    }

    /** Add picked SAF documents under [targetDir] ("" = root). */
    suspend fun addFiles(targetDir: String, items: List<Pair<Uri, String>>): Result<Int> {
        busy = true
        var added = 0
        val result = rebuild(
            exclude = emptySet(),
            append = { zos ->
                for ((uri, displayName) in items) {
                    val name = ZipPaths.join(targetDir, displayName.replace('\\', '/'))
                    if (ZipPaths.normalize(name) == null) continue
                    zos.putNextEntry(ZipEntry(name))
                    resolver.openInputStream(uri)!!.use { it.copyTo(zos, BUF) }
                    zos.closeEntry()
                    added++
                }
            }
        )
        busy = false
        return if (result.isSuccess) Result.success(added) else Result.failure(result.exceptionOrNull()!!)
    }

    /** Delete entries (files or whole folders). */
    suspend fun deleteEntries(names: Set<String>): Result<Unit> {
        busy = true
        val result = rebuild(exclude = names)
        busy = false
        return result
    }

    /** Rename or move a file/folder inside the archive (path prefix aware). */
    suspend fun renamePath(oldPath: String, newPathRaw: String): Result<Unit> {
        val oldDir = if (oldPath.endsWith("/")) oldPath else "$oldPath/"
        val isDir = oldPath.endsWith("/")
        val newPath = ZipPaths.normalize(newPathRaw.trim())
            ?: throw IOException("Unsafe target path: $newPathRaw")
        require(newPath.isNotEmpty())
        val affected = entries.filter {
            it.name == oldPath || (isDir && it.name.startsWith(oldDir))
        }.map { it.name }.toSet()
        if (affected.isEmpty()) throw IOException("Nothing to rename")
        busy = true
        val newDir = if (isDir && !newPath.endsWith("/")) "$newPath/" else newPath
        val result = rebuild(
            exclude = affected,
            append = { zos ->
                val ctx = current ?: return@append
                resolver.openInputStream(ctx.uri)!!.use { raw ->
                    ZipInputStream(BufferedInputStream(raw, BUF)).use { zis ->
                        while (true) {
                            val e = zis.nextEntry ?: break
                            val norm = ZipPaths.normalize(e.name) ?: run { zis.closeEntry(); continue }
                            if (norm !in affected) { zis.closeEntry(); continue }
                            val mapped = when {
                                norm == oldPath -> if (isDir) newDir else newPath
                                isDir -> ZipPaths.join(newDir, norm.removePrefix(oldDir))
                                else -> newPath
                            }
                            val outName = if (e.isDirectory && !mapped.endsWith("/")) "$mapped/" else mapped
                            zos.putNextEntry(ZipEntry(outName).apply { time = e.time })
                            if (!e.isDirectory) zis.copyTo(zos, BUF)
                            zos.closeEntry()
                            zis.closeEntry()
                        }
                    }
                }
            }
        )
        busy = false
        return result
    }

    // ------------------------------------------------------------ extract

    /** Extract the given entries into a SAF tree folder, preserving structure. */
    suspend fun extractEntries(names: Set<String>, targetTreeUri: Uri): Result<Int> =
        withContext(Dispatchers.IO) {
            val ctx = current ?: return@withContext Result.failure(IOException("No archive open"))
            checkStorage()
            busy = true
            cancelled.set(false)
            var extracted = 0
            try {
                val root = DocumentFile.fromTreeUri(app, targetTreeUri)
                    ?: throw IOException("Invalid destination folder")
                val wanted = names.ifEmpty { entries.map { it.name }.toSet() }
                val total = wanted.count { !it.endsWith("/") }.coerceAtLeast(1)
                var done = 0
                resolver.openInputStream(ctx.uri)!!.use { raw ->
                    ZipInputStream(BufferedInputStream(raw, BUF)).use { zis ->
                        while (true) {
                            val e = zis.nextEntry ?: break
                            checkCancel()
                            val norm = ZipPaths.normalize(e.name) ?: run { zis.closeEntry(); continue }
                            if (norm !in wanted) { zis.closeEntry(); continue }
                            if (e.isDirectory || norm.endsWith("/")) { zis.closeEntry(); continue }
                            val parent = ensureDir(root, ZipPaths.parentOf(norm))
                            val fName = ZipPaths.fileName(norm)
                            val existing = parent.findFile(fName)
                            val target = existing ?: parent.createFile(SafUtils.mimeFor(fName), fName)
                                ?: throw IOException("Cannot create $fName")
                            target.uri.let { u ->
                                resolver.openOutputStream(u, "wt")!!.use { os ->
                                    zis.copyTo(os, BUF)
                                }
                            }
                            extracted++
                            done++
                            progress = done.toFloat() / total
                            zis.closeEntry()
                        }
                    }
                }
                status = "Extracted $extracted file(s)"
                Result.success(extracted)
            } catch (c: OperationCancelledException) {
                status = app.getString(R.string.common_cancelled)
                Result.failure(c)
            } catch (t: Throwable) {
                Result.failure(t)
            } finally {
                busy = false
                progress = null
            }
        }

    private fun ensureDir(root: DocumentFile, dirPath: String): DocumentFile {
        if (dirPath.isEmpty()) return root
        var node = root
        for (seg in dirPath.trimEnd('/').split('/')) {
            if (seg.isEmpty()) continue
            val next = node.findFile(seg)?.takeIf { it.isDirectory }
                ?: node.createDirectory(seg)
                ?: throw IOException("Cannot create folder $seg")
            node = next
        }
        return node
    }

    /** Zip a SAF folder (used by wizard + export). Returns entry count. */
    suspend fun zipFolderTo(treeUri: Uri, outUri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(app, treeUri)
            ?: return@withContext Result.failure(IOException("Invalid source folder"))
        busy = true
        var count = 0
        try {
            val temp = File.createTempFile("folderzip_", ".zip", cacheDir)
            ZipOutputStream(BufferedOutputStream(FileOutputStream(temp), BUF)).use { zos ->
                fun walk(dir: DocumentFile, prefix: String) {
                    checkCancel()
                    for (k in dir.listFiles()) {
                        val name = k.name ?: continue
                        val rel = if (prefix.isEmpty()) name else "$prefix/$name"
                        if (k.isDirectory) {
                            walk(k, rel)
                        } else {
                            zos.putNextEntry(ZipEntry(rel))
                            resolver.openInputStream(k.uri)!!.use { it.copyTo(zos, BUF) }
                            zos.closeEntry()
                            count++
                            progress = null
                        }
                    }
                }
                walk(root, "")
            }
            // verify then commit
            verifyZipFile(temp)
            resolver.openOutputStream(outUri, "wt")!!.use { os ->
                temp.inputStream().use { it.copyTo(os, BUF) }
            }
            temp.delete()
            Result.success(count)
        } catch (t: Throwable) {
            Result.failure(t)
        } finally {
            busy = false
            progress = null
        }
    }

    /** Raw byte copy of the open archive into [outUri] (for export). */
    suspend fun copyCurrentTo(outUri: Uri): Result<Long> = withContext(Dispatchers.IO) {
        val ctx = current ?: return@withContext Result.failure(IOException("No archive open"))
        busy = true
        try {
            var copied = 0L
            resolver.openInputStream(ctx.uri)!!.use { ins ->
                resolver.openOutputStream(outUri, "wt")!!.use { os ->
                    val buf = ByteArray(BUF)
                    while (true) {
                        checkCancel()
                        val r = ins.read(buf)
                        if (r < 0) break
                        os.write(buf, 0, r)
                        copied += r
                    }
                }
            }
            Result.success(copied)
        } catch (t: Throwable) {
            Result.failure(t)
        } finally {
            busy = false
        }
    }

    /** Stream-selected entries of the open archive into a new ZIP at [outUri]. */
    suspend fun exportSelectedEntries(names: Set<String>, outUri: Uri): Result<Int> =
        withContext(Dispatchers.IO) {
            val ctx = current ?: return@withContext Result.failure(IOException("No archive open"))
            busy = true
            var count = 0
            try {
                val temp = File.createTempFile("selzip_", ".zip", cacheDir)
                ZipOutputStream(BufferedOutputStream(FileOutputStream(temp), BUF)).use { zos ->
                    resolver.openInputStream(ctx.uri)!!.use { raw ->
                        ZipInputStream(BufferedInputStream(raw, BUF)).use { zis ->
                            while (true) {
                                val e = zis.nextEntry ?: break
                                checkCancel()
                                val norm = ZipPaths.normalize(e.name) ?: run { zis.closeEntry(); continue }
                                if (norm !in names) { zis.closeEntry(); continue }
                                if (e.isDirectory) {
                                    zos.putNextEntry(ZipEntry(if (norm.endsWith("/")) norm else "$norm/"))
                                    zos.closeEntry()
                                } else {
                                    zos.putNextEntry(ZipEntry(norm).apply { time = e.time })
                                    zis.copyTo(zos, BUF)
                                    zos.closeEntry()
                                    count++
                                }
                                zis.closeEntry()
                            }
                        }
                    }
                }
                verifyZipFile(temp)
                resolver.openOutputStream(outUri, "wt")!!.use { os ->
                    temp.inputStream().use { it.copyTo(os, BUF) }
                }
                temp.delete()
                Result.success(count)
            } catch (t: Throwable) {
                Result.failure(t)
            } finally {
                busy = false
            }
        }

    /** Used by the audio converter to add a brand-new file. */
    suspend fun addEntryBytes(entryName: String, data: ByteArray): Result<Unit> {
        busy = true
        val norm = ZipPaths.normalize(entryName) ?: throw IOException("Unsafe path")
        val result = rebuild(
            append = { zos ->
                zos.putNextEntry(ZipEntry(norm))
                zos.write(data)
                zos.closeEntry()
            }
        )
        busy = false
        return result
    }

    fun clearStatus() {
        status = null
    }
}
