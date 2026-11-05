package com.pawmiaw.business

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

//
//  A picked or taken photo, ready for the server (iOS ImageUpload.swift): at most 1600 px on
//  its long side, JPEG, under the server's size limit. A photo from the picker or the camera
//  arrives as a content `Uri` (`PickVisualMedia`, `TakePicture`): `ImageUpload.load(uri)`.
//

object ImageUpload {
    /** The server refuses images over 3 MB. */
    const val maxBytes = 3 * 1024 * 1024

    fun jpeg(image: Bitmap, maxSide: Int = 1600, maxBytes: Int = ImageUpload.maxBytes): ByteArray? {
        val longest = max(image.width, image.height)
        if (longest <= 0) return null
        val scale = if (longest > maxSide) maxSide.toFloat() / longest else 1f
        val resized = if (scale < 1f) {
            Bitmap.createScaledBitmap(image, (image.width * scale).roundToInt().coerceAtLeast(1), (image.height * scale).roundToInt().coerceAtLeast(1), true)
        } else image
        var quality = 85
        var out = compress(resized, quality)
        while (out != null && out.size > maxBytes && quality > 30) {
            quality -= 15
            out = compress(resized, quality)
        }
        return out
    }

    /** Image bytes (any format the system reads) as upload-ready JPEG, turned the way the photo was taken. */
    fun jpeg(from: ByteArray, maxSide: Int = 1600): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(from, 0, from.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        // Decode no bigger than needed (a 48 MP photo would not fit in memory whole).
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(from, 0, from.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        return jpeg(upright(bitmap, from), maxSide)
    }

    /** A picked or taken photo (iOS `PhotosPickerItem`) as upload-ready JPEG data. */
    suspend fun load(item: Uri?): ByteArray? {
        if (item == null) return null
        return withContext(Dispatchers.IO) {
            val bytes = runCatching { AppContext.context.contentResolver.openInputStream(item)?.use { it.readBytes() } }.getOrNull()
                ?: return@withContext null
            jpeg(from = bytes)
        }
    }

    /** Sends it as the multipart field the endpoint names (`photo`, `file`, `logo`…). */
    suspend inline fun <reified T> upload(
        data: ByteArray,
        to: String,
        field: String = "photo",
        method: String = "POST",
        fields: Map<String, String> = emptyMap(),
    ): T = API.upload(to, method = method, fileField = field, fileName = "$field.jpg", mimeType = "image/jpeg", fileData = data, fields = fields)

    private fun compress(bitmap: Bitmap, quality: Int): ByteArray? {
        val stream = ByteArrayOutputStream()
        return if (bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)) stream.toByteArray() else null
    }

    /** iOS draws a photo the way it was held (`imageOrientation`); Android's decoder ignores EXIF, so turn it here. */
    private fun upright(bitmap: Bitmap, bytes: ByteArray): Bitmap {
        val orientation = runCatching {
            ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.preScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.preScale(-1f, 1f) }
            else -> return bitmap
        }
        return runCatching { Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true) }.getOrDefault(bitmap)
    }
}

/** A photo being chosen in a form: the new one (and its preview), or the saved one removed. */
data class PhotoDraft(
    val data: ByteArray? = null,
    val preview: ImageBitmap? = null,
    /** The saved photo is to be removed on save. */
    val removed: Boolean = false,
) {
    val isEmpty: Boolean get() = data == null && !removed

    // Swift's `Data` compares by content.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PhotoDraft) return false
        val sameData = if (data == null) other.data == null else other.data != null && data.contentEquals(other.data)
        return sameData && preview == other.preview && removed == other.removed
    }

    override fun hashCode(): Int = 31 * (31 * (data?.contentHashCode() ?: 0) + (preview?.hashCode() ?: 0)) + removed.hashCode()
}
