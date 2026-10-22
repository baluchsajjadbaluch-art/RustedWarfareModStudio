package com.rustedwarfare.modstudio.core

/**
 * Minimal, format-preserving INI parser used by the editor, the validator and
 * the wizard. Pure Kotlin so it can be unit-tested on the JVM.
 *
 * Rules:
 *  - ';' or '#' at line start  -> comment line
 *  - '[name]' on its own line  -> section
 *  - 'key = value'             -> entry inside the current section
 *  - trailing comments after a value (' ;' / ' #' preceded by whitespace) are stripped
 */
object IniParser {

    data class ParsedKey(val key: String, val value: String, val line: Int)
    data class ParsedSection(val name: String, val line: Int, val keys: List<ParsedKey>)
    data class ParseProblem(val line: Int, val message: String)
    data class ParsedIni(
        val sections: List<ParsedSection>,
        val problems: List<ParseProblem>,
        val totalLines: Int
    )

    private val sectionLine = Regex("""^\s*\[\s*([^\[\]]+?)\s*\]\s*$""")
    private val unclosedSection = Regex("""^\s*\[.*$""")

    fun isCommentLine(raw: String): Boolean {
        val t = raw.trimStart()
        return t.startsWith(";") || t.startsWith("#")
    }

    fun stripTrailingComment(value: String): String {
        var out = value
        val i = out.indexOf(';')
        if (i > 0 && out[i - 1].isWhitespace()) out = out.substring(0, i)
        val j = out.indexOf('#')
        if (j > 0 && out[j - 1].isWhitespace()) out = out.substring(0, j)
        return out
    }

    fun parse(text: String): ParsedIni {
        val sections = ArrayList<ParsedSection>()
        val problems = ArrayList<ParseProblem>()
        val lines = text.lines()

        var currentName: String? = null
        var currentLine = 0
        val keys = ArrayList<ParsedKey>()

        fun flush() {
            val name = currentName ?: return
            sections.add(ParsedSection(name, currentLine, keys.toList()))
            keys.clear()
        }

        for ((idx, raw) in lines.withIndex()) {
            val lineNo = idx + 1
            if (raw.isBlank() || isCommentLine(raw)) continue

            val sec = sectionLine.matchEntire(raw)
            if (sec != null) {
                flush()
                currentName = sec.groupValues[1]
                currentLine = lineNo
                continue
            }
            if (unclosedSection.matchEntire(raw) != null && !raw.contains('=')) {
                problems.add(ParseProblem(lineNo, "Unclosed section header: '${raw.trim()}'"))
                continue
            }
            val eq = raw.indexOf('=')
            if (eq <= 0) {
                problems.add(
                    ParseProblem(lineNo, "Not a comment, section or 'key = value' line: '${raw.trim().take(60)}'")
                )
                continue
            }
            val key = raw.substring(0, eq).trim()
            val value = stripTrailingComment(raw.substring(eq + 1)).trim()
            if (key.isEmpty()) {
                problems.add(ParseProblem(lineNo, "Empty key before '='"))
                continue
            }
            if (currentName == null) {
                problems.add(ParseProblem(lineNo, "Key '$key' appears before any [section] header"))
            }
            keys.add(ParsedKey(key, value, lineNo))
        }
        flush()
        return ParsedIni(sections, problems, lines.size)
    }
}
