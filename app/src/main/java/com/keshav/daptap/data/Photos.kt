package com.keshav.daptap.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File

/** Photo storage and the three encodings DapTap needs: contact photo, NFC thumbnail, relay avatar. */
object Photos {
    fun myPhotoFile(context: Context) = File(context.filesDir, "me.jpg")

    fun peopleDir(context: Context) = File(context.filesDir, "people").apply { mkdirs() }

    fun readMine(context: Context): ByteArray? = myPhotoFile(context).takeIf { it.exists() }?.readBytes()

    /** Imports a picked image: square-crops, downsizes to 720 px and stores it as the user's photo. */
    fun importMine(context: Context, uri: Uri): Boolean = runCatching {
        val bitmap = decode(context, uri) ?: return false
        val square = squareCrop(bitmap, 720)
        myPhotoFile(context).writeBytes(jpeg(square, 88))
        true
    }.getOrDefault(false)

    fun deleteMine(context: Context) { myPhotoFile(context).delete() }

    fun decodeBytes(bytes: ByteArray): Bitmap? = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()

    /** Small JPEG that fits under [maxBytes]; null if the source can't be decoded. */
    fun thumbnail(source: ByteArray, size: Int, maxBytes: Int): ByteArray? {
        val bmp = decodeBytes(source) ?: return null
        val square = squareCrop(bmp, size)
        var q = 80
        while (q >= 30) {
            val out = jpeg(square, q)
            if (out.size <= maxBytes) return out
            q -= 10
        }
        return if (size > 48) thumbnail(source, size * 3 / 4, maxBytes) else null
    }

    fun jpeg(bitmap: Bitmap, quality: Int): ByteArray =
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()

    fun squareCrop(src: Bitmap, size: Int): Bitmap {
        val side = minOf(src.width, src.height)
        val x = (src.width - side) / 2
        val y = (src.height - side) / 2
        val cropped = Bitmap.createBitmap(src, x, y, side, side)
        return if (side <= size) cropped else Bitmap.createScaledBitmap(cropped, size, size, true)
    }

    private fun decode(context: Context, uri: Uri): Bitmap? {
        if (Build.VERSION.SDK_INT >= 28) {
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val longest = maxOf(info.size.width, info.size.height)
                if (longest > 2048) decoder.setTargetSampleSize(longest / 1024)
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
        val bmp = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val orientation = context.contentResolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        return if (degrees == 0f) bmp else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(degrees) }, true)
    }
}
