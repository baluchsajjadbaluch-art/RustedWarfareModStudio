package com.rustedwarfare.modstudio.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

/** Color tokens for INI syntax highlighting (readable on dark and light themes). */
object IniColors {
    val comment = Color(0xFF7A8794)
    val section = Color(0xFFF2A33C)
    val key = Color(0xFF5FB3E6)
    val value = Color(0xFFD8DEE9)
    val number = Color(0xFFE5A96B)
    val boolean = Color(0xFF9BD77F)
}

/**
 * INI syntax highlighting as a Compose [VisualTransformation] (offset-safe).
 * Styles: comments (gray italic), [sections] (amber bold), keys (blue),
 * numeric values (orange), true/false (green).
 */
class IniVisualTransformation(private val enabled: Boolean) : VisualTransformation {

    private val numberValue = Regex("""^-?\d+(\.\d+)?$""")
    private val boolValue = Regex("""^(true|false)$""", RegexOption.IGNORE_CASE)

    override fun filter(text: AnnotatedString): TransformedText {
        if (!enabled) return TransformedText(text, OffsetMapping.Identity)
        val t = text.text
        val builder = AnnotatedString.Builder(t.length)
        builder.append(t)

        var lineStart = 0
        while (lineStart <= t.length) {
            val nl = t.indexOf('\n', lineStart)
            val lineEnd = if (nl < 0) t.length else nl
            styleLine(builder, t, lineStart, lineEnd)
            if (nl < 0) break
            lineStart = nl + 1
        }
        return TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
    }

    private fun styleLine(b: AnnotatedString.Builder, t: String, start: Int, end: Int) {
        if (start >= end) return
        val line = t.substring(start, end)
        val trimmed = line.trimStart()
        if (trimmed.startsWith(";") || trimmed.startsWith("#")) {
            b.addStyle(SpanStyle(color = IniColors.comment, fontStyle = FontStyle.Italic), start, end)
            return
        }
        val open = line.indexOf('[')
        val close = line.indexOf(']')
        if (open >= 0 && close > open) {
            b.addStyle(SpanStyle(color = IniColors.section, fontWeight = FontWeight.Bold), start + open, start + close + 1)
            return
        }
        val eq = line.indexOf('=')
        if (eq > 0) {
            val keyStart = start + line.indexOfFirst { !it.isWhitespace() }
            b.addStyle(SpanStyle(color = IniColors.key, fontWeight = FontWeight.Medium), keyStart, start + eq)
            b.addStyle(SpanStyle(color = IniColors.comment), start + eq, start + eq + 1)
            val vStart = start + eq + 1
            if (vStart < end) {
                val value = line.substring(eq + 1).trim()
                val style = when {
                    numberValue.matches(value) -> SpanStyle(color = IniColors.number)
                    boolValue.matches(value) -> SpanStyle(color = IniColors.boolean)
                    else -> SpanStyle(color = IniColors.value)
                }
                b.addStyle(style, vStart, end)
            }
        } else {
            b.addStyle(SpanStyle(color = IniColors.value), start, end)
        }
    }
}
