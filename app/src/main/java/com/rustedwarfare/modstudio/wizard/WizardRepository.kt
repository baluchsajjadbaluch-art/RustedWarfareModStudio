package com.rustedwarfare.modstudio.wizard

import android.app.Application
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.rustedwarfare.modstudio.archive.ArchiveRepository
import com.rustedwarfare.modstudio.core.SafUtils
import com.rustedwarfare.modstudio.image.ImageOps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * New-mod wizard. Generates a working starter mod using community-documented
 * Rusted Warfare conventions: mod_info.ini, a sample unit INI, a real PNG
 * sprite and a README. Nothing invented — placeholders are clearly labelled.
 */
class WizardRepository(private val app: Application) {

    data class ModMeta(
        val name: String,
        val description: String,
        val author: String,
        val version: String
    )

    private val resolver get() = app.contentResolver

    private fun sanitizeFolderName(raw: String): String =
        raw.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").take(48).ifBlank { "NewMod" }

    fun modInfoText(meta: ModMeta): String = """
        ; mod_info.ini — metadata for your mod.
        ; Community convention: the game reads .rwmod packages (ZIP compatible);
        ; unknown files/keys are ignored by the game. This file documents your mod.
        name = ${meta.name}
        description = ${meta.description.ifBlank { "Created with RW Mod Studio" }}
        author = ${meta.author.ifBlank { "unknown" }}
        version = ${meta.version.ifBlank { "1.0" }}
    """.trimIndent() + "\n"

    fun sampleUnitText(meta: ModMeta): String = """
        ; Sample unit for Rusted Warfare — created by RW Mod Studio
        ; The keys below follow commonly documented RW modding conventions.
        ; Full key reference: official modding docs on the Corroding Games forum.
        ; TIP: run the built-in Validator after your edits.

        [core]
        name = ${meta.name} Unit
        price = 500
        buildSpeed = 8.0
        maxHp = 200
        radius = 16
        mass = 900
        isBuilding = false
        techLevel = 1

        [graphics]
        image = images/sample_unit.png
        imageScale = 1.0

        [movement]
        movementType = LAND
        targetHeight = LAND
        maxTurnSpeed = 1.6

        [attack]
        canAttack = true
        maxAttackRange = 110
    """.trimIndent() + "\n"

    fun readmeText(meta: ModMeta): String = """
        ${meta.name} v${meta.version.ifBlank { "1.0" }}
        ${"-".repeat(30)}
        ${meta.description}

        Created with Rusted Warfare Ultimate Mod Studio (Android).

        Contents:
          mod_info.ini            mod metadata
          units/sample_unit.ini   a working sample unit (edit values freely)
          images/sample_unit.png  sample sprite referenced by the unit
          sounds/                 put your .wav/.ogg sound files here

        Install (game): copy this folder (or the .rwmod file) into the
        'mods' folder of Rusted Warfare, then enable it in the game's mod menu.
    """.trimIndent() + "\n"

    private fun soundsNoteText(): String =
        "Put .wav / .ogg sound files here and reference them from your INI files.\n"

    private fun buildFileMap(meta: ModMeta): Map<String, ByteArray> = mapOf(
        "mod_info.ini" to modInfoText(meta).toByteArray(Charsets.UTF_8),
        "units/sample_unit.ini" to sampleUnitText(meta).toByteArray(Charsets.UTF_8),
        "images/sample_unit.png" to ImageOps.sampleUnitSprite(64),
        "README.txt" to readmeText(meta).toByteArray(Charsets.UTF_8),
        "sounds/README.txt" to soundsNoteText().toByteArray(Charsets.UTF_8)
    )

    private fun uniqueChildName(parent: DocumentFile, wanted: String): String {
        var name = wanted
        var i = 2
        while (parent.findFile(name) != null) {
            name = "$wanted-$i"
            i++
            if (i > 99) return "$wanted-${System.currentTimeMillis() % 10000}"
        }
        return name
    }

    /** Create the mod as a folder (recommended during development). */
    suspend fun createFolderMod(parentTreeUri: Uri, meta: ModMeta): Result<String> =
        withContext(Dispatchers.IO) {
            val parent = DocumentFile.fromTreeUri(app, parentTreeUri)
                ?: return@withContext Result.failure(IOException("Invalid parent folder"))
            try {
                val folderName = uniqueChildName(parent, sanitizeFolderName(meta.name))
                val root = parent.createDirectory(folderName)
                    ?: return@withContext Result.failure(IOException("Cannot create folder"))
                for ((rel, data) in buildFileMap(meta)) {
                    val dir = ensureDir(root, ZipPathsParent.of(rel))
                    val fileName = ZipPathsFileName.of(rel)
                    val doc = dir.createFile(SafUtils.mimeFor(fileName), fileName)
                        ?: return@withContext Result.failure(IOException("Cannot create $rel"))
                    if (doc.name != fileName) {
                        try {
                            android.provider.DocumentsContract.renameDocument(
                                resolver, doc.uri, fileName
                            )
                        } catch (_: Exception) {
                        }
                    }
                    val target = dir.findFile(fileName) ?: doc
                    resolver.openOutputStream(target.uri, "wt")!!.use { it.write(data) }
                }
                Result.success(folderName)
            } catch (t: Throwable) {
                Result.failure(t)
            }
        }

    /** Create the mod as an .rwmod package (ZIP-compatible, like real mods). */
    suspend fun createRwmod(parentTreeUri: Uri, meta: ModMeta, archiveRepo: ArchiveRepository): Result<String> =
        withContext(Dispatchers.IO) {
            val parent = DocumentFile.fromTreeUri(app, parentTreeUri)
                ?: return@withContext Result.failure(IOException("Invalid parent folder"))
            try {
                val baseName = sanitizeFolderName(meta.name)
                val fileName = uniqueChildName(parent, baseName) + ".rwmod"
                // create a hidden temp zip first via cache, then write into the doc
                val doc = parent.createFile("application/octet-stream", fileName)
                    ?: return@withContext Result.failure(IOException("Cannot create $fileName"))
                val fixed = if (doc.name != fileName) {
                    try {
                        android.provider.DocumentsContract.renameDocument(resolver, doc.uri, fileName)
                        parent.findFile(fileName) ?: doc
                    } catch (_: Exception) {
                        doc
                    }
                } else doc

                // Build zip via the archive engine (needs a tree): use temp folder in cache
                val tempDir = java.io.File(app.cacheDir, "wizard_" + System.currentTimeMillis())
                    .apply { mkdirs() }
                try {
                    for ((rel, data) in buildFileMap(meta)) {
                        val f = java.io.File(tempDir, rel.replace('/', java.io.File.separatorChar))
                        f.parentFile?.mkdirs()
                        f.writeBytes(data)
                    }
                    // zip the temp folder manually (simple, no SAF needed)
                    val zipTemp = java.io.File.createTempFile("rwmod_", ".zip", app.cacheDir)
                    java.util.zip.ZipOutputStream(
                        java.io.BufferedOutputStream(java.io.FileOutputStream(zipTemp))
                    ).use { zos ->
                        tempDir.walkTopDown().filter { it.isFile }.forEach { f ->
                            val rel = f.relativeTo(tempDir).invariantSeparatorsPath
                            zos.putNextEntry(java.util.zip.ZipEntry(rel))
                            f.inputStream().use { it.copyTo(zos) }
                            zos.closeEntry()
                        }
                    }
                    // verify
                    java.util.zip.ZipFile(zipTemp).use { zf ->
                        if (!zf.entries().hasMoreElements()) throw IOException("Empty package")
                    }
                    resolver.openOutputStream(fixed.uri, "wt")!!.use { os ->
                        zipTemp.inputStream().use { it.copyTo(os) }
                    }
                    zipTemp.delete()
                } finally {
                    tempDir.deleteRecursively()
                }
                Result.success(fileName)
            } catch (t: Throwable) {
                Result.failure(t)
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

    // tiny helpers to keep the file map keys as "a/b/c" paths
    private object ZipPathsParent {
        fun of(path: String): String {
            val i = path.lastIndexOf('/')
            return if (i <= 0) "" else path.substring(0, i + 1)
        }
    }

    private object ZipPathsFileName {
        fun of(path: String): String = path.substringAfterLast('/')
    }
}
