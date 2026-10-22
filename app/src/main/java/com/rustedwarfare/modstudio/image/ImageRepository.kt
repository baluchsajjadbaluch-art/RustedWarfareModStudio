package com.rustedwarfare.modstudio.image

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.rustedwarfare.modstudio.archive.ArchiveRepository
import com.rustedwarfare.modstudio.core.SafUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** State holder for the image editor: load → edit → save into archive or folder. */
class ImageRepository(
    private val app: Application,
    private val archive: ArchiveRepository
) {

    sealed interface Source {
        data class InArchive(val entryName: String) : Source
        data class SafDoc(val uri: Uri) : Source
    }

    var source by mutableStateOf<Source?>(null)
        private set
    var sourceName by mutableStateOf<String?>(null)
        private set
    var originalBytes by mutableStateOf<ByteArray?>(null)
        private set
    var baseBitmap by mutableStateOf<Bitmap?>(null)
        private set
    var workingBitmap by mutableStateOf<Bitmap?>(null)
        private set
    var previewBitmap by mutableStateOf<Bitmap?>(null)
        private set
    var brightness by mutableStateOf(0f)
    var contrast by mutableStateOf(1f)
    var busy by mutableStateOf(false)
        private set
    var status by mutableStateOf<String?>(null)
        private set

    private val resolver get() = app.contentResolver

    fun reset() {
        source = null
        sourceName = null
        originalBytes = null
        baseBitmap = null
        workingBitmap = null
        previewBitmap = null
        brightness = 0f
        contrast = 1f
    }

    suspend fun loadFromArchive(entryName: String): Result<Unit> = withContext(Dispatchers.IO) {
        busy = true
        try {
            if (archive.current == null) throw IOException("No archive open")
            val bytes = archive.readEntry(entryName)
            val bmp = ImageOps.decode(bytes) ?: throw IOException("Cannot decode image")
            source = Source.InArchive(entryName)
            sourceName = entryName
            originalBytes = bytes
            baseBitmap = bmp
            workingBitmap = bmp
            previewBitmap = null
            brightness = 0f
            contrast = 1f
            Result.success(Unit)
        } catch (t: Throwable) {
            Result.failure(t)
        } finally {
            busy = false
        }
    }

    suspend fun loadFromUri(uri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        busy = true
        try {
            val bytes = SafUtils.readAllBytes(resolver, uri)
            val bmp = ImageOps.decode(bytes) ?: throw IOException("Cannot decode image")
            source = Source.SafDoc(uri)
            sourceName = SafUtils.displayName(resolver, uri)
            originalBytes = bytes
            baseBitmap = bmp
            workingBitmap = bmp
            previewBitmap = null
            brightness = 0f
            contrast = 1f
            Result.success(Unit)
        } catch (t: Throwable) {
            Result.failure(t)
        } finally {
            busy = false
        }
    }

    fun commitEdited(bmp: Bitmap) {
        workingBitmap = bmp
        previewBitmap = null
    }

    fun resetToOriginal() {
        baseBitmap?.let { workingBitmap = it }
        brightness = 0f
        contrast = 1f
        previewBitmap = null
    }

    val displayBitmap: Bitmap?
        get() = previewBitmap ?: workingBitmap

    /** Re-apply brightness/contrast over the geometry result (background thread). */
    suspend fun applyAdjust(b: Float, c: Float) = withContext(Dispatchers.Default) {
        val g = workingBitmap ?: return@withContext
        brightness = b
        contrast = c
        previewBitmap = if (b == 0f && c == 1f) null else ImageOps.adjust(g, b, c)
    }

    private fun currentBitmap(): Bitmap? = previewBitmap ?: workingBitmap

    /** Encode the current bitmap and replace the archive entry (if from archive). */
    suspend fun saveToArchive(fmt: ImageOps.OutFormat, quality: Int, newName: String? = null): Result<Unit> =
        withContext(Dispatchers.IO) {
            val bmp = currentBitmap() ?: return@withContext Result.failure(IOException("Nothing loaded"))
            val s = source
            if (s !is Source.InArchive) return@withContext Result.failure(IOException("Image is not from the archive"))
            busy = true
            try {
                val data = ImageOps.encode(bmp, fmt, quality)
                val target = newName ?: s.entryName
                val r = if (target == s.entryName) {
                    archive.replaceEntryBytes(s.entryName, data)
                } else {
                    // save under a new name: add new entry and remove old one
                    val add = archive.addEntryBytes(target, data)
                    if (add.isSuccess) archive.deleteEntries(setOf(s.entryName)) else add
                }
                if (r.isSuccess) {
                    source = Source.InArchive(target)
                    sourceName = target
                    status = "Image saved into archive"
                }
                r
            } catch (t: Throwable) {
                Result.failure(t)
            } finally {
                busy = false
            }
        }

    /** Encode and save into a SAF folder (works for any source). */
    suspend fun saveToFolder(
        targetTreeUri: Uri,
        fmt: ImageOps.OutFormat,
        quality: Int,
        fileName: String
    ): Result<String> = withContext(Dispatchers.IO) {
        val bmp = currentBitmap() ?: return@withContext Result.failure(IOException("Nothing loaded"))
        busy = true
        try {
            val root = androidx.documentfile.provider.DocumentFile.fromTreeUri(app, targetTreeUri)
                ?: throw IOException("Invalid destination")
            val name = if (fileName.endsWith(".${fmt.name.lowercase()}", true)) fileName
            else "$fileName.${if (fmt == ImageOps.OutFormat.JPEG) "jpg" else fmt.name.lowercase()}"
            val dest = root.createFile(ImageOps.mimeOf(fmt), name)
                ?: throw IOException("Cannot create $name")
            SafUtils.writeBytes(resolver, dest.uri, ImageOps.encode(bmp, fmt, quality))
            status = "Image saved"
            Result.success(name)
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
