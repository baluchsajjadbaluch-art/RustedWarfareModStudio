package com.rustedwarfare.modstudio.core

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Thin, testable helpers around the Storage Access Framework.
 * The app deliberately avoids broad storage permissions — everything flows
 * through SAF documents / trees.
 */
object SafUtils {

    const val DEFAULT_MAX_BYTES: Long = 64L * 1024 * 1024
    private const val BUF = 64 * 1024

    fun displayName(resolver: ContentResolver, uri: Uri): String {
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0) c.getString(i)?.let { return it }
                }
            }
        } catch (_: Exception) {
        }
        return uri.lastPathSegment ?: "file"
    }

    fun sizeOf(resolver: ContentResolver, uri: Uri): Long {
        try {
            resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(OpenableColumns.SIZE)
                    if (i >= 0 && !c.isNull(i)) return c.getLong(i)
                }
            }
        } catch (_: Exception) {
        }
        return -1L
    }

    fun readAllBytes(
        resolver: ContentResolver,
        uri: Uri,
        maxBytes: Long = DEFAULT_MAX_BYTES
    ): ByteArray {
        val ins = resolver.openInputStream(uri) ?: throw IOException("Cannot open: $uri")
        ins.use {
            val out = ByteArrayOutputStream(maxBytes.coerceAtMost(1L * 1024 * 1024).toInt())
            val buf = ByteArray(BUF)
            var total = 0L
            while (true) {
                val r = it.read(buf)
                if (r < 0) break
                total += r
                if (total > maxBytes) throw IOException("File exceeds the ${maxBytes / (1024 * 1024)} MB safety limit")
                out.write(buf, 0, r)
            }
            return out.toByteArray()
        }
    }

    /** Detect charset: BOM first, then strict UTF-8 test, then windows-1252. */
    fun detectCharset(bytes: ByteArray): Charset {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return StandardCharsets.UTF_8
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return StandardCharsets.UTF_16LE
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return StandardCharsets.UTF_16BE
        try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
            return StandardCharsets.UTF_8
        } catch (_: Exception) {
        }
        return charset("windows-1252")
    }

    fun readText(
        resolver: ContentResolver,
        uri: Uri,
        maxBytes: Long = DEFAULT_MAX_BYTES
    ): Pair<String, Charset> {
        val bytes = readAllBytes(resolver, uri, maxBytes)
        val cs = detectCharset(bytes)
        val text = String(bytes, cs).trimStart('\uFEFF')
        return text to cs
    }

    fun writeBytes(resolver: ContentResolver, uri: Uri, data: ByteArray) {
        val os = resolver.openOutputStream(uri, "wt") ?: throw IOException("Cannot write: $uri")
        os.use { it.write(data) }
    }

    fun writeText(resolver: ContentResolver, uri: Uri, text: String, cs: Charset = StandardCharsets.UTF_8) {
        writeBytes(resolver, uri, text.toByteArray(cs))
    }

    fun mimeFor(name: String): String {
        val ext = MimeTypeMap.getFileExtensionFromUrl(name)?.lowercase()
        return ext?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) } ?: "application/octet-stream"
    }

    /** Recursively walk a tree, returning "relative/path" to DocumentFile pairs. */
    fun walkFiles(root: DocumentFile, limit: Int = 5000): List<Pair<String, DocumentFile>> {
        val out = ArrayList<Pair<String, DocumentFile>>()
        fun rec(dir: DocumentFile, prefix: String) {
            if (out.size >= limit) return
            for (k in dir.listFiles()) {
                if (out.size >= limit) return
                val rel = if (prefix.isEmpty()) (k.name ?: "") else "$prefix/${k.name}"
                if (k.isDirectory) rec(k, rel) else out.add(rel to k)
            }
        }
        rec(root, "")
        return out
    }

    private val IMAGE_EXT = Regex("(?i)\\.(png|jpe?g|webp|bmp|gif)$")
    private val AUDIO_EXT = Regex("(?i)\\.(wav|ogg|mp3|m4a|flac)$")
    private val TEXT_EXT = Regex("(?i)\\.(ini|txt|md|json|xml|csv|ts|cfg|conf|log|yml|yaml|lua|properties|info)$")
    private val ARCHIVE_EXT = Regex("(?i)\\.(zip|rwmod)$")

    fun hasImageExt(name: String): Boolean = IMAGE_EXT.containsMatchIn(name)
    fun hasAudioExt(name: String): Boolean = AUDIO_EXT.containsMatchIn(name)
    fun hasTextExt(name: String): Boolean = TEXT_EXT.containsMatchIn(name)
    fun hasArchiveExt(name: String): Boolean = ARCHIVE_EXT.containsMatchIn(name)
}
