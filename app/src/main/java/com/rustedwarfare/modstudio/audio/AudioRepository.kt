package com.rustedwarfare.modstudio.audio

import android.app.Application
import android.media.MediaExtractor
import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.rustedwarfare.modstudio.archive.ArchiveRepository
import com.rustedwarfare.modstudio.core.SafUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Audio editor state: playback via MediaPlayer (on a cache file copy),
 * technical info, replace-in-archive, batch import and WAV conversion.
 */
class AudioRepository(
    private val app: Application,
    private val archive: ArchiveRepository
) {

    sealed interface Source {
        data class InArchive(val entryName: String) : Source
        data class SafDoc(val uri: Uri) : Source
    }

    data class Current(
        val name: String,
        val source: Source,
        val bytes: ByteArray,
        val info: AudioOps.AudioInfo?
    )

    var current by mutableStateOf<Current?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var status by mutableStateOf<String?>(null)
        private set
    var playing by mutableStateOf(false)
        private set
    var positionMs by mutableStateOf(0L)
        private set

    private val playerCacheDir = File(app.cacheDir, "audio").apply { mkdirs() }
    private var player: MediaPlayer? = null
    private var playFile: File? = null

    private val resolver get() = app.contentResolver

    private fun probeBytes(bytes: ByteArray): AudioOps.AudioInfo? = try {
        val f = File.createTempFile("probe", ".aud", playerCacheDir)
        try {
            f.writeBytes(bytes)
            val ex = MediaExtractor()
            try {
                ex.setDataSource(f.absolutePath)
                var fmt: MediaFormat? = null
                var dur = 0L
                for (i in 0 until ex.trackCount) {
                    val fm = ex.getTrackFormat(i)
                    val mime = fm.getString(MediaFormat.KEY_MIME) ?: continue
                    if (mime.startsWith("audio/")) {
                        fmt = fm
                        dur = fm.getLong(MediaFormat.KEY_DURATION)
                        break
                    }
                }
                fmt?.let { AudioOps.probe(it, dur) }
            } finally {
                ex.release()
            }
        } finally {
            f.delete()
        }
    } catch (_: Exception) {
        null
    }

    suspend fun loadFromArchive(entryName: String): Result<Unit> = withContext(Dispatchers.IO) {
        busy = true
        stopPlayback()
        try {
            if (archive.current == null) throw IOException("No archive open")
            val bytes = archive.readEntry(entryName)
            current = Current(entryName, Source.InArchive(entryName), bytes, probeBytes(bytes))
            Result.success(Unit)
        } catch (t: Throwable) {
            Result.failure(t)
        } finally {
            busy = false
        }
    }

    suspend fun loadFromUri(uri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        busy = true
        stopPlayback()
        try {
            val bytes = SafUtils.readAllBytes(resolver, uri)
            val name = SafUtils.displayName(resolver, uri)
            current = Current(name, Source.SafDoc(uri), bytes, probeBytes(bytes))
            Result.success(Unit)
        } catch (t: Throwable) {
            Result.failure(t)
        } finally {
            busy = false
        }
    }

    // ---------------------------------------------------------- playback

    private fun cachePlayFile(bytes: ByteArray, name: String): File {
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(60)
        val f = File(playerCacheDir, "p_${Integer.toHexString(name.hashCode())}_$safe")
        if (!f.exists() || f.length() != bytes.size.toLong()) f.writeBytes(bytes)
        return f
    }

    fun startPlayback() {
        val c = current ?: return
        try {
            if (player == null) {
                playFile = cachePlayFile(c.bytes, c.name)
                player = MediaPlayer().apply {
                    setDataSource(playFile!!.absolutePath)
                    setOnCompletionListener {
                        playing = false
                        positionMs = 0
                    }
                    prepare()
                }
            }
            player?.start()
            playing = true
        } catch (t: Throwable) {
            playing = false
            status = "Playback failed: ${t.message}"
        }
    }

    fun pausePlayback() {
        player?.pause()
        playing = false
    }

    fun stopPlayback() {
        try {
            player?.stop()
            player?.release()
        } catch (_: Exception) {
        }
        player = null
        playing = false
        positionMs = 0
    }

    fun seekTo(ms: Long) {
        player?.seekTo(ms.toInt())
        positionMs = ms
    }

    fun tickPosition(): Long {
        val p = player ?: return 0
        positionMs = try { p.currentPosition.toLong() } catch (_: Exception) { positionMs }
        return positionMs
    }

    protected fun finalize() {
        try { player?.release() } catch (_: Exception) {}
    }

    // ---------------------------------------------------------- mutation

    /** Replace the currently loaded archive entry with picked audio bytes. */
    suspend fun replaceInArchive(newUri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        val c = current ?: return@withContext Result.failure(IOException("Nothing loaded"))
        val src = c.source
        if (src !is Source.InArchive) return@withContext Result.failure(IOException("Audio is not from the archive"))
        busy = true
        try {
            stopPlayback()
            val data = SafUtils.readAllBytes(resolver, newUri)
            val r = archive.replaceEntryBytes(src.entryName, data)
            if (r.isSuccess) {
                current = c.copy(bytes = data, info = probeBytes(data))
                status = "Audio replaced"
            }
            r
        } catch (t: Throwable) {
            Result.failure(t)
        } finally {
            busy = false
        }
    }

    /** Add a picked audio file under [targetDir] in the open archive. */
    suspend fun batchImport(targetDir: String, uris: List<Uri>): Result<Int> {
        if (archive.current == null) return Result.failure(IOException("No archive open"))
        return archive.addFiles(targetDir, uris.map { it to SafUtils.displayName(resolver, it) })
    }

    /** Save the current audio into a folder of the tree (kept as-is, original bytes). */
    suspend fun saveToFolder(treeUri: Uri, fileName: String): Result<String> = withContext(Dispatchers.IO) {
        val c = current ?: return@withContext Result.failure(IOException("Nothing loaded"))
        busy = true
        try {
            val root = androidx.documentfile.provider.DocumentFile.fromTreeUri(app, treeUri)
                ?: throw IOException("Invalid destination")
            val dest = root.createFile("application/octet-stream", fileName)
                ?: throw IOException("Cannot create $fileName")
            SafUtils.writeBytes(resolver, dest.uri, c.bytes)
            status = "Audio saved"
            Result.success(fileName)
        } catch (t: Throwable) {
            Result.failure(t)
        } finally {
            busy = false
        }
    }

    /** Genuinely decode the current audio to 16-bit PCM WAV bytes. */
    suspend fun convertCurrentToWav(): Result<Pair<ByteArray, AudioOps.AudioInfo>> =
        withContext(Dispatchers.Default) {
            val c = current ?: return@withContext Result.failure(IOException("Nothing loaded"))
            busy = true
            var temp: File? = null
            try {
                temp = File.createTempFile("conv", ".aud", playerCacheDir)
                temp.writeBytes(c.bytes)
                val (wav, info) = AudioOps.decodeToWav(temp)
                Result.success(wav to info)
            } catch (t: Throwable) {
                Result.failure(t)
            } finally {
                temp?.delete()
                busy = false
            }
        }

    /** Write converted WAV into the open archive as a new entry. */
    suspend fun addWavToArchive(entryName: String, wav: ByteArray): Result<Unit> {
        if (archive.current == null) return Result.failure(IOException("No archive open"))
        return archive.addEntryBytes(entryName, wav)
    }

    /**
     * Search all INI files for references to this audio file name
     * (community convention: sounds are referenced from INI keys).
     */
    suspend fun checkIniReferences(): Int = withContext(Dispatchers.IO) {
        val c = current ?: return@withContext 0
        val name = c.name.substringAfterLast('/').lowercase()
        var count = 0
        if (archive.current != null) {
            for (e in archive.entries) {
                if (!e.isDirectory && e.name.endsWith(".ini", true)) {
                    try {
                        val text = archive.readEntryText(e.name).first.lowercase()
                        if (name in text) count++
                    } catch (_: Exception) {
                    }
                }
            }
        }
        count
    }

    fun clearStatus() {
        status = null
    }
}
