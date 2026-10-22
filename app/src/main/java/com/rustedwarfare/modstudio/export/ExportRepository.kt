package com.rustedwarfare.modstudio.export

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.documentfile.provider.DocumentFile
import com.rustedwarfare.modstudio.archive.ArchiveRepository
import com.rustedwarfare.modstudio.project.ProjectRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * Export engine: ZIP / RWMOD export with a validation pre-check and a real
 * post-export verification (re-opens the produced file and CRC-checks it).
 */
class ExportRepository(
    private val app: Application,
    private val archive: ArchiveRepository,
    private val project: ProjectRepository
) {

    var busy by mutableStateOf(false)
        private set
    var status by mutableStateOf<String?>(null)
        private set

    /**
     * Verify a produced archive at [uri] by fully streaming it (CRC is checked
     * by ZipInputStream at every entry end). Returns the file-entry count.
     */
    private fun verifyOutput(uri: Uri): Int {
        var count = 0
        app.contentResolver.openInputStream(uri)!!.use { raw ->
            ZipInputStream(raw.buffered(64 * 1024)).use { zis ->
                while (true) {
                    val e = zis.nextEntry ?: break
                    if (!e.isDirectory) {
                        val buf = ByteArray(64 * 1024)
                        while (zis.read(buf) >= 0) { /* CRC validated */ }
                        count++
                    }
                    zis.closeEntry()
                }
            }
        }
        return count
    }

    /** Export the open archive as-is (already validated when last saved). */
    suspend fun exportOpenArchive(outUri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        if (archive.current == null) return@withContext Result.failure(IOException("No archive open"))
        busy = true
        try {
            val r = archive.copyCurrentTo(outUri)
            if (r.isFailure) return@withContext Result.failure(r.exceptionOrNull()!!)
            val count = verifyOutput(outUri)
            status = "Export verified: $count entries, CRC OK"
            Result.success(count)
        } catch (t: Throwable) {
            Result.failure(t)
        } finally {
            busy = false
        }
    }

    /** Export selected entries of the open archive into a fresh ZIP. */
    suspend fun exportSelected(names: Set<String>, outUri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        if (archive.current == null) return@withContext Result.failure(IOException("No archive open"))
        busy = true
        try {
            val r = archive.exportSelectedEntries(names, outUri)
            if (r.isFailure) return@withContext Result.failure(r.exceptionOrNull()!!)
            val count = verifyOutput(outUri)
            status = "Export verified: $count entries, CRC OK"
            Result.success(count)
        } catch (t: Throwable) {
            Result.failure(t)
        } finally {
            busy = false
        }
    }

    /** Zip an opened mod folder into [outUri] (used for folder → ZIP / RWMOD). */
    suspend fun exportFolderAsZip(treeUri: Uri, outUri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        busy = true
        try {
            val r = archive.zipFolderTo(treeUri, outUri)
            if (r.isFailure) return@withContext Result.failure(r.exceptionOrNull()!!)
            val count = verifyOutput(outUri)
            status = "Export verified: $count entries, CRC OK"
            Result.success(count)
        } catch (t: Throwable) {
            Result.failure(t)
        } finally {
            busy = false
        }
    }

    /** Extract the whole open archive into a SAF tree. */
    suspend fun extractAllTo(treeUri: Uri): Result<Int> {
        if (archive.current == null) return Result.failure(IOException("No archive open"))
        return archive.extractEntries(emptySet(), treeUri)
    }

    fun clearStatus() {
        status = null
    }
}
