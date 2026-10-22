package com.rustedwarfare.modstudio.validator

import android.app.Application
import androidx.documentfile.provider.DocumentFile
import com.rustedwarfare.modstudio.archive.ArchiveRepository
import com.rustedwarfare.modstudio.core.IniParser
import com.rustedwarfare.modstudio.core.SafUtils
import com.rustedwarfare.modstudio.core.ZipPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Static mod validator for Rusted Warfare INI files.
 *
 * Honesty policy (per requirements):
 *  - unknown keys / sections are reported as INFO (never errors),
 *  - nothing is ever removed or rewritten automatically,
 *  - the knowledge base below covers keys documented by the community;
 *    it is a hint layer, not an absolute authority.
 */
class ModValidator(private val app: Application) {

    enum class Severity { ERROR, WARNING, INFO }

    data class Finding(
        val severity: Severity,
        val file: String,
        val line: Int?,
        val code: String,
        val message: String,
        val suggestion: String?
    )

    data class Report(
        val findings: List<Finding>,
        val filesScanned: Int,
        val elapsedMs: Long
    ) {
        val errors get() = findings.count { it.severity == Severity.ERROR }
        val warnings get() = findings.count { it.severity == Severity.WARNING }
        val infos get() = findings.count { it.severity == Severity.INFO }

        fun toText(): String {
            val sb = StringBuilder()
            sb.appendLine("Rusted Warfare Mod Studio — Validation Report")
            sb.appendLine("Files scanned: $filesScanned   |   Errors: $errors   Warnings: $warnings   Info: $infos")
            sb.appendLine("=".repeat(72))
            val order = Severity.values()
            for (sev in order) {
                val list = findings.filter { it.severity == sev }
                if (list.isEmpty()) continue
                sb.appendLine()
                sb.appendLine("## ${sev.name} (${list.size})")
                for (f in list) {
                    sb.appendLine("[${f.code}] ${f.file}${f.line?.let { ":$it" } ?: ""}")
                    sb.appendLine("  ${f.message}")
                    f.suggestion?.let { sb.appendLine("  >> $it") }
                }
            }
            return sb.toString()
        }
    }

    sealed interface Target {
        data class ArchiveTarget(val repo: ArchiveRepository) : Target
        data class FolderTarget(val root: DocumentFile) : Target
    }

    // ----------------------------------------------------------- knowledge

    /** Keys that must contain a number (case-insensitive, lowercase here). */
    private val NUMERIC_KEYS = setOf(
        "price", "maxhp", "radius", "mass", "techlevel", "buildspeed",
        "maxattackrange", "minattackrange", "attackspeed", "damage", "area_damage",
        "imagescale", "movementspeed", "movespeed", "turnspeed", "maxturnspeed",
        "shield", "shieldregen", "energy", "energymax", "energyregen",
        "transportslots", "crews", "height", "width", "heightoffset", "scale",
        "generation_credits", "generation_energy", "view_range", "capture_time",
        "repair_rate", "hit_delay", "lifetime", "cooldown", "delay", "power",
        "speed", "range", "reload", "blast_radius", "armour", "armor"
    )

    /** Keys that must be true/false (lowercase). */
    private val BOOLEAN_KEYS = setOf(
        "isbuilding", "canattack", "canattackflyingunits", "canattackmovingunits",
        "flammable", "selectable", "targetair", "onlyattacklos", "canmove",
        "canreverseturn", "diesOnNeutralTerrain".lowercase(), "static", "hidden",
        "hidehp", "showhandtarget", "fixedangles", "drawunderunits", "stealth"
    )

    /** Key prefixes that reference external resource files. */
    private val RESOURCE_KEY_PREFIXES = listOf(
        "image", "sound", "music", "video", "effect_", "explosion_"
    )

    /** Sections documented by the community (lowercase). */
    private val KNOWN_SECTIONS = setOf(
        "core", "unit", "graphics", "movement", "attack", "ai", "placement",
        "config", "template", "hidden_unit", "ui", "damage", "death"
    )

    /** Wildcard sections (prefix + index). */
    private val SECTION_PREFIXES = listOf(
        "turret_", "action_", "effect_", "explosion_", "projectile_",
        "damage_", "resource_", "attachment_", "area_", "sound_", "music_"
    )

    private fun isKnownSection(name: String): Boolean {
        val n = name.lowercase()
        if (n in KNOWN_SECTIONS) return true
        val base = n.substringBeforeLast('_')
        val idx = n.substringAfterLast('_', "")
        if (idx.toIntOrNull() != null && base in SECTION_PREFIXES.map { it.removeSuffix("_") }) {
            return SECTION_PREFIXES.any { n.startsWith(it) }
        }
        return SECTION_PREFIXES.any { n.startsWith(it) }
    }

    private fun isResourceKey(key: String): Boolean {
        val k = key.lowercase()
        return RESOURCE_KEY_PREFIXES.any { k.startsWith(it) || k.contains("sound") }
    }

    // -------------------------------------------------------------- run

    suspend fun validate(target: Target): Report = withContext(Dispatchers.Default) {
        val t0 = System.currentTimeMillis()
        val findings = ArrayList<Finding>()
        var filesScanned = 0

        // 1) collect ini files as (path, text) plus the full file-name set
        data class IniFile(val path: String, val text: String)

        val iniFiles = ArrayList<IniFile>()
        val allFiles = HashSet<String>()

        when (target) {
            is Target.ArchiveTarget -> {
                val repo = target.repo
                val entries = repo.entries
                for (e in entries) {
                    if (!e.isDirectory) allFiles.add(e.name)
                }
                for (e in entries) {
                    if (!e.isDirectory && ZipPaths.extension(e.name) == "ini") {
                        try {
                            iniFiles.add(IniFile(e.name, repo.readEntryText(e.name).first))
                        } catch (_: Exception) {
                            findings.add(
                                Finding(Severity.WARNING, e.name, null, "VAL_READ",
                                    "INI file could not be read", "Check file encoding")
                            )
                        }
                    }
                }
            }
            is Target.FolderTarget -> {
                val root = target.root
                for ((rel, doc) in SafUtils.walkFiles(root, limit = 3000)) {
                    allFiles.add(rel)
                }
                for ((rel, doc) in SafUtils.walkFiles(root, limit = 3000)) {
                    if (ZipPaths.extension(rel) == "ini") {
                        try {
                            val resolver = app.contentResolver
                            val (txt, _) = SafUtils.readText(resolver, doc.uri, maxBytes = 8L * 1024 * 1024)
                            iniFiles.add(IniFile(rel, txt))
                        } catch (_: Exception) {
                            findings.add(
                                Finding(Severity.WARNING, rel, null, "VAL_READ",
                                    "INI file could not be read", "Check file encoding")
                            )
                        }
                    }
                }
            }
        }
        filesScanned = iniFiles.size

        // 2) per-file checks
        for (ini in iniFiles) {
            val dir = ZipPaths.parentOf(ini.path)
            val parsed = IniParser.parse(ini.text)

            for (p in parsed.problems) {
                findings.add(
                    Finding(Severity.ERROR, ini.path, p.line, "VAL_PARSE", p.message,
                        "Fix the syntax so the game can read this file")
                )
            }

            val seenSections = HashMap<String, Int>()
            for (sec in parsed.sections) {
                val key = sec.name.lowercase()
                if (seenSections.containsKey(key)) {
                    findings.add(
                        Finding(Severity.ERROR, ini.path, sec.line, "VAL_DUP_SECTION",
                            "Duplicate section [${sec.name}] (first defined at line ${seenSections[key]})",
                            "Merge the two sections or rename one — the game usually reads only one of them")
                    )
                } else {
                    seenSections[key] = sec.line
                }

                if (!isKnownSection(sec.name)) {
                    findings.add(
                        Finding(Severity.INFO, ini.path, sec.line, "VAL_UNKNOWN_SECTION",
                            "Section [${sec.name}] is not in the known list",
                            "Verify against official modding docs; custom sections are allowed and were NOT removed")
                    )
                }

                // sequential index check for turret_1 style sections
                val m = Regex("""^(.+?)_(\d+)$""").find(sec.name.lowercase())
                if (m != null && "${m.groupValues[1]}_" in SECTION_PREFIXES) {
                    val idx = m.groupValues[2].toInt()
                    if (idx == 0) {
                        findings.add(
                            Finding(Severity.WARNING, ini.path, sec.line, "VAL_INDEX0",
                                "Indexed section [${sec.name}] starts at 0",
                                "Community convention starts these sections at 1")
                        )
                    }
                }

                // per-key checks
                val seenKeys = HashSet<String>()
                for (k in sec.keys) {
                    val kl = k.key.lowercase()

                    if (!seenKeys.add(kl)) {
                        findings.add(
                            Finding(Severity.WARNING, ini.path, k.line, "VAL_DUP_KEY",
                                "Duplicate key '${k.key}' in [${sec.name}]",
                                "The last occurrence usually wins; remove the duplicate")
                        )
                    }

                    if (k.value.isEmpty()) {
                        findings.add(
                            Finding(Severity.WARNING, ini.path, k.line, "VAL_EMPTY_VALUE",
                                "Empty value for '${k.key}'", "Set a value or remove the line")
                        )
                        continue
                    }

                    if (kl in NUMERIC_KEYS && k.value.toDoubleOrNull() == null) {
                        findings.add(
                            Finding(Severity.ERROR, ini.path, k.line, "VAL_BAD_NUMBER",
                                "'${k.key}' expects a number but found '${k.value.take(40)}'",
                                "Use a plain number like 500 or 2.5")
                        )
                    }
                    if (kl in BOOLEAN_KEYS && !k.value.equals("true", true) && !k.value.equals("false", true)) {
                        findings.add(
                            Finding(Severity.ERROR, ini.path, k.line, "VAL_BAD_BOOL",
                                "'${k.key}' expects true/false but found '${k.value.take(40)}'", null)
                        )
                    }

                    if (isResourceKey(kl)) {
                        for (ref in k.value.split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
                            val broken = ref.contains("..") || ref.startsWith("/") || ref.contains('\\')
                            val candidate = ZipPaths.join(dir, ref)
                            val exists = candidate in allFiles || ref in allFiles ||
                                allFiles.any { it.equals(candidate, ignoreCase = true) }
                            if (!exists) {
                                findings.add(
                                    Finding(Severity.ERROR, ini.path, k.line, "VAL_MISSING_FILE",
                                        "Referenced file not found: '$ref'",
                                        "Add the file at '$candidate' inside the mod or fix the path")
                                )
                            }
                            if (broken) {
                                findings.add(
                                    Finding(Severity.WARNING, ini.path, k.line, "VAL_PATH_STYLE",
                                        "Path '$ref' uses a fragile style (.. or \\ or leading /)",
                                        "Use forward slashes and paths relative to this INI file")
                                )
                            }
                        }
                    }

                    if (!isKnownKey(kl)) {
                        findings.add(
                            Finding(Severity.INFO, ini.path, k.line, "VAL_UNKNOWN_KEY",
                                "Key '${k.key}' is not in the known key list",
                                "Keep it if it is intentional — unknown keys are never removed automatically")
                        )
                    }
                }
            }
        }

        Report(findings, filesScanned, System.currentTimeMillis() - t0)
    }

    private fun isKnownKey(kl: String): Boolean {
        if (kl in NUMERIC_KEYS || kl in BOOLEAN_KEYS) return true
        if (isResourceKey(kl)) return true
        if (kl.startsWith("action") || kl.startsWith("turret") || kl.startsWith("resource")) return true
        return kl in COMMON_TEXT_KEYS
    }

    private val COMMON_TEXT_KEYS = setOf(
        "name", "description", "class", "movementtype", "targetheight", "tech",
        "teamcolor", "buildmenu", "buildspeed_unit", "die_on_terrain",
        "displaycolor", "footer", "header", "weapon", "armortype", "turret"
    )
}
