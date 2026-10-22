package com.rustedwarfare.modstudio.core

/**
 * Pure-Kotlin path utilities shared by the archive engine and unit tests.
 * Implements zip-slip protection: any entry that would escape the archive root
 * is rejected (returns null) and never written to disk.
 */
object ZipPaths {

    const val SEPARATOR: Char = '/'

    /**
     * Normalize an archive entry name.
     * - converts '\' to '/'
     * - rejects Windows drive paths (C:\...), UNC paths and any ".." traversal
     * - collapses "." and empty segments
     * - keeps a trailing '/' for directory entries
     *
     * @return normalized name, or null when the path is unsafe.
     */
    fun normalize(raw: String): String? {
        if (raw.isEmpty()) return null
        val s = raw.replace('\\', '/')
        // Reject Windows drive letters (C:/) and UNC (//server)
        if (s.length >= 2 && s[1] == ':') return null
        if (s.startsWith("//")) return null
        val trailingDir = s.endsWith("/")
        val parts = s.split(SEPARATOR)
        val out = ArrayList<String>(parts.size)
        for (p in parts) {
            when (p) {
                "", "." -> continue
                ".." -> return null // traversal — reject entire entry
                else -> out.add(p)
            }
        }
        if (out.isEmpty()) return null
        var joined = out.joinToString("/")
        if (trailingDir) joined += "/"
        return joined
    }

    /** Parent directory of a path, with trailing '/'; "" for root-level entries. */
    fun parentOf(path: String): String {
        val t = path.trimEnd(SEPARATOR)
        val i = t.lastIndexOf(SEPARATOR)
        return if (i <= 0) "" else t.substring(0, i + 1)
    }

    fun isDirectoryPath(name: String): Boolean = name.endsWith("/")

    /** Join a directory (may be "") with a child name. */
    fun join(dir: String, name: String): String =
        if (dir.isEmpty()) name else dir.trimEnd(SEPARATOR) + "/" + name

    /** File name of a path (last segment, without trailing '/'). */
    fun fileName(path: String): String {
        val t = path.trimEnd(SEPARATOR)
        val i = t.lastIndexOf(SEPARATOR)
        return if (i < 0) t else t.substring(i + 1)
    }

    /** Lowercase extension without the dot; "" when none. */
    fun extension(name: String): String {
        val f = fileName(name)
        val i = f.lastIndexOf('.')
        return if (i <= 0) "" else f.substring(i + 1).lowercase()
    }
}
