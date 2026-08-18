package edu.iitgoa.attendance.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.camera.core.ImageProxy
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Longest edge we upload. The server downscales anything larger to 1280 before
 * running detection, and the face model works from a small crop after that, so
 * sending more than this is bytes on a student's data plan for no accuracy.
 */
private const val MAX_EDGE = 720
private const val JPEG_QUALITY = 80

/**
 * Turns a CameraX capture into an upright, right-sized JPEG.
 *
 * Rotation is baked into the pixels rather than left in an EXIF tag, because
 * Pillow on the server does not auto-apply EXIF orientation — a sideways frame
 * would simply fail to detect a face.
 */
fun ImageProxy.toUprightJpeg(): ByteArray {
    val raw = ByteArray(planes[0].buffer.remaining()).also {
        planes[0].buffer.get(it)
    }

    val bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.size)
        ?: return raw // Not decodable here; let the server reject it.

    val rotationDegrees = imageInfo.rotationDegrees.takeIf { it != 0 }
        ?: raw.exifRotationDegrees()

    return bitmap
        .rotated(rotationDegrees)
        .scaledToFit(MAX_EDGE)
        .toJpegBytes()
}

private fun ByteArray.exifRotationDegrees(): Int = runCatching {
    when (
        ExifInterface(ByteArrayInputStream(this))
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    ) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90
        ExifInterface.ORIENTATION_ROTATE_180 -> 180
        ExifInterface.ORIENTATION_ROTATE_270 -> 270
        else -> 0
    }
}.getOrDefault(0)

private fun Bitmap.rotated(degrees: Int): Bitmap {
    if (degrees == 0) return this
    val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
    return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
}

private fun Bitmap.scaledToFit(maxEdge: Int): Bitmap {
    val longest = maxOf(width, height)
    if (longest <= maxEdge) return this
    val scale = maxEdge.toFloat() / longest
    return Bitmap.createScaledBitmap(
        this,
        (width * scale).toInt().coerceAtLeast(1),
        (height * scale).toInt().coerceAtLeast(1),
        true,
    )
}

private fun Bitmap.toJpegBytes(): ByteArray = ByteArrayOutputStream().use { out ->
    compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
    out.toByteArray()
}
