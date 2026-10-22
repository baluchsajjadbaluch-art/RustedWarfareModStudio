package com.rustedwarfare.modstudio.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IniParserTest {

    @Test
    fun `parses sections keys and values`() {
        val text = """
            ; a comment
            [core]
            name = Tank
            price = 500 ; trailing comment
            maxHp = 200

            [graphics]
            image = body.png
        """.trimIndent()

        val ini = IniParser.parse(text)
        assertEquals(2, ini.sections.size)
        val core = ini.sections[0]
        assertEquals("core", core.name)
        assertEquals(3, core.keys.size)
        assertEquals("Tank", core.keys[0].value)
        assertEquals("500", core.keys[1].value) // trailing comment stripped
        assertEquals(4, core.keys[1].line)      // real 1-based line number
        assertTrue(ini.problems.isEmpty())
    }

    @Test
    fun `detects key before any section as problem`() {
        val ini = IniParser.parse("name = orphan\n[core]\nprice = 1\n")
        assertEquals(1, ini.problems.size)
        assertTrue(ini.problems[0].message.contains("before any [section]"))
    }

    @Test
    fun `detects garbage lines`() {
        val ini = IniParser.parse("[core]\n???\nprice = 1\n")
        assertEquals(1, ini.problems.size)
    }

    @Test
    fun `isCommentLine covers both markers`() {
        assertTrue(IniParser.isCommentLine("; hi"))
        assertTrue(IniParser.isCommentLine("# hi"))
        assertTrue(IniParser.isCommentLine("   # hi"))
        assertFalse(IniParser.isCommentLine("name = hi"))
    }
}

class ZipPathsTest {

    @Test
    fun `normalizes simple paths`() {
        assertEquals("units/tank.ini", ZipPaths.normalize("units/tank.ini"))
        assertEquals("units/tank.ini", ZipPaths.normalize("units\\tank.ini"))
        assertEquals("a/b/", ZipPaths.normalize("/a/b/"))
    }

    @Test
    fun `rejects traversal`() {
        assertNull(ZipPaths.normalize("../evil.txt"))
        assertNull(ZipPaths.normalize("a/../../evil.txt"))
        assertNull(ZipPaths.normalize("..\\..\\evil.txt"))
    }

    @Test
    fun `rejects absolute and drive paths`() {
        assertNull(ZipPaths.normalize("C:/Windows/evil.txt"))
        assertNull(ZipPaths.normalize("//server/share"))
        assertNull(ZipPaths.normalize(""))
    }

    @Test
    fun `parent and filename helpers`() {
        assertEquals("units/", ZipPaths.parentOf("units/tank.ini"))
        assertEquals("", ZipPaths.parentOf("tank.ini"))
        assertEquals("tank.ini", ZipPaths.fileName("units/tank.ini"))
        assertEquals("units/", ZipPaths.join("units", "tank.ini").removeSuffix("tank.ini"))
        assertEquals("ini", ZipPaths.extension("UNITS/Tank.INI"))
    }
}
