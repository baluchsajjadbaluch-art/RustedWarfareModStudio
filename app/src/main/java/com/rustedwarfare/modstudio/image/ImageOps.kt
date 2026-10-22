package com.rustedwarfare.modstudio.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Pure image operations used by the image editor screen. */
object ImageOps {

    enum class OutFormat { PNG, JPEG, WEBP }

    data class LoadedImage(val bitmap: Bitmap, val bytes: ByteArray, val sourceName: String)

    fun formatFromName(name: String): OutFormat = when {
        name.endsWith(".jpg", true) || name.endsWith(".jpeg", true) -> OutFormat.JPEG
        name.endsWith(".webp", true) -> OutFormat.WEBP
        else -> OutFormat.PNG
    }

    fun mimeOf(fmt: OutFormat): String = when (fmt) {
        OutFormat.PNG -> "image/png"
        OutFormat.JPEG -> "image/jpeg"
        OutFormat.WEBP -> "image/webp"
    }

    fun decode(bytes: ByteArray, maxDim: Int = 8192): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        var w = bounds.outWidth
        var h = bounds.outHeight
        while (w / 2 >= maxDim || h / 2 >= maxDim) {
            w /= 2; h /= 2; sample *= 2
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    fun rotate(bmp: Bitmap, degrees: Float): Bitmap {
        val m = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    fun flip(bmp: Bitmap, horizontal: Boolean): Bitmap {
        val m = Matrix().apply {
            if (horizontal) setScale(-1f, 1f) else setScale(1f, -1f)
            postTranslate(if (horizontal) bmp.width.toFloat() else 0f, if (horizontal) 0f else bmp.height.toFloat())
        }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    fun scaleTo(bmp: Bitmap, w: Int, h: Int): Bitmap {
        val width = max(1, min(w, 8192))
        val height = max(1, min(h, 8192))
        return Bitmap.createScaledBitmap(bmp, width, height, true)
    }

    /** Crop by fractional bounds (0..1). */
    fun cropFractions(bmp: Bitmap, left: Float, top: Float, right: Float, bottom: Float): Bitmap {
        val l = (left.coerceIn(0f, 1f) * bmp.width).roundToInt()
        val t = (top.coerceIn(0f, 1f) * bmp.height).roundToInt()
        val r = (right.coerceIn(0f, 1f) * bmp.width).roundToInt()
        val b = (bottom.coerceIn(0f, 1f) * bmp.height).roundToInt()
        val w = max(1, min(r, bmp.width) - l)
        val h = max(1, min(b, bmp.height) - t)
        return Bitmap.createBitmap(bmp, l, t, w, h)
    }

    /**
     * Brightness (-0.5..0.5) + contrast (0.5..1.5, 1.0 = neutral) via ColorMatrix.
     * Only RGB rows are modified — alpha (transparency) is fully preserved.
     */
    fun adjust(bmp: Bitmap, brightness: Float, contrast: Float): Bitmap {
        if (brightness == 0f && contrast == 1f) return bmp
        val b = brightness * 255f
        val c = contrast.coerceIn(0.2f, 3f)
        val off = 128f * (1f - c)
        val cm = ColorMatrix(
            floatArrayOf(
                c, 0f, 0f, 0f, b + off,
                0f, c, 0f, 0f, b + off,
                0f, 0f, c, 0f, b + off,
                0f, 0f, 0f, 1f, 0f
            )
        )
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(cm) }
        val out = bmp.copy(Bitmap.Config.ARGB_8888, true)
        Canvas(out).drawBitmap(bmp, 0f, 0f, paint)
        return out
    }

    fun encode(bmp: Bitmap, fmt: OutFormat, quality: Int): ByteArray {
        val q = quality.coerceIn(10, 100)
        val compressFormat = when (fmt) {
            OutFormat.PNG -> Bitmap.CompressFormat.PNG
            OutFormat.JPEG -> Bitmap.CompressFormat.JPEG
            OutFormat.WEBP -> Bitmap.CompressFormat.WEBP
        }
        val out = ByteArrayOutputStream()
        bmp.compress(compressFormat, q, out)
        return out.toByteArray()
    }

    /** Simple generated sprite for the new-mod wizard (a small tank top-view). */
    fun sampleUnitSprite(size: Int = 64): ByteArray {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val body = Paint().apply { color = 0xFF5C6B4A.toInt(); isAntiAlias = true }
        val dark = Paint().apply { color = 0xFF3B452F.toInt(); isAntiAlias = true }
        val accent = Paint().apply { color = 0xFFF2A33C.toInt(); isAntiAlias = true }
        // tracks
        c.drawRect(6f, 8f, 14f, size - 8f, dark)
        c.drawRect(size - 14f, 8f, size - 6f, size - 8f, dark)
        // hull
        c.drawRect(14f, 14f, size - 14f, size - 14f, body)
        // turret
        c.drawCircle(size / 2f, size / 2f, 9f, dark)
        // barrel
        c.drawRect(size / 2f - 2f, 2f, size / 2f + 2f, size / 2f - 6f, accent)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }
}
