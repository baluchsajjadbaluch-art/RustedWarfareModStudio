package com.rustedwarfare.modstudio.core

import android.net.Uri

/** Context of the archive (.zip / .rwmod) currently open through SAF. */
data class ArchiveContext(
    val uri: Uri,
    val displayName: String,
    val sizeBytes: Long,
    val isRwmod: Boolean
)

/** A single entry inside the open archive. Directory entries end with '/'. */
data class ArchiveEntry(
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val compressedSize: Long,
    val crc: Long,
    val method: Int,
    val lastModified: Long
)

/** Thrown when the user cancels a long archive operation. */
class OperationCancelledException : Exception("cancelled by user")

/** Thrown when free storage is too low to continue safely. */
class LowStorageException : Exception("low storage")
