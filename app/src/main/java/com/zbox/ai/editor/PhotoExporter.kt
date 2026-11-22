package com.zbox.ai.editor

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ExportFormat(
    val label: String,
    val ext: String,
    val mime: String,
    val compressFormat: Bitmap.CompressFormat,
    val defaultQuality: Int,
) {
    JPEG_HIGH("JPEG 高画质 (95%)", "jpg", "image/jpeg", Bitmap.CompressFormat.JPEG, 95),
    JPEG_LITE("JPEG 轻量 (85%)", "jpg", "image/jpeg", Bitmap.CompressFormat.JPEG, 85),
    PNG_LOSSLESS("PNG 无损", "png", "image/png", Bitmap.CompressFormat.PNG, 100),
}

data class ExportResult(
    val displayName: String,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val relativePath: String,
)

/**
 * 负责读取全分辨率源图并将修图结果保存到手机系统相册（Pictures/Zbox）。
 */
object PhotoExporter {

    fun loadFullResolution(
        context: Context,
        uri: Uri?,
        filePath: String?,
        maxExportEdge: Int = 6000,
    ): Bitmap? {
        if (uri != null) {
            loadFromUri(context, uri, maxExportEdge)?.let { return it }
        }
        if (!filePath.isNullOrBlank()) {
            loadFromFile(File(filePath), maxExportEdge)?.let { return it }
        }
        return null
    }

    private fun loadFromUri(context: Context, uri: Uri, maxEdge: Int): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val src = android.graphics.ImageDecoder.createSource(context.contentResolver, uri)
            android.graphics.ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                val w = info.size.width
                val h = info.size.height
                val longEdge = maxOf(w, h)
                if (maxEdge > 0 && longEdge > maxEdge) {
                    val ratio = maxEdge.toFloat() / longEdge
                    decoder.setTargetSize((w * ratio).toInt().coerceAtLeast(1), (h * ratio).toInt().coerceAtLeast(1))
                }
                decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
            var sample = 1
            val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
            while (maxEdge > 0 && longEdge / sample > maxEdge) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bmp = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return@runCatching null
            val orient = runCatching {
                context.contentResolver.openInputStream(uri)?.use {
                    ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                } ?: ExifInterface.ORIENTATION_NORMAL
            }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
            applyExifOrientation(bmp, orient)
        }
    }.getOrNull()

    private fun loadFromFile(file: File, maxEdge: Int): Bitmap? = runCatching {
        if (!file.exists() || !file.isFile) return@runCatching null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        var sample = 1
        val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
        while (maxEdge > 0 && longEdge / sample > maxEdge) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = BitmapFactory.decodeFile(file.path, opts) ?: return@runCatching null
        val orient = runCatching {
            ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        applyExifOrientation(bmp, orient)
    }.getOrNull()

    private fun applyExifOrientation(bmp: Bitmap, orientation: Int): Bitmap {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            else -> return bmp
        }
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        if (rotated !== bmp) bmp.recycle()
        return rotated
    }

    fun saveBitmapToPhoneGallery(
        context: Context,
        bitmap: Bitmap,
        baseName: String,
        format: ExportFormat = ExportFormat.JPEG_HIGH,
        quality: Int = format.defaultQuality,
    ): Result<ExportResult> = runCatching {
        val cleanBase = baseName
            .substringBeforeLast('.')
            .replace(Regex("[^A-Za-z0-9_\\-\\u4e00-\\u9fa5]"), "_")
            .take(36)
            .ifBlank { "ZBOX_PHOTO" }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "${cleanBase}_ZBOX_${stamp}.${format.ext}"

        val bos = ByteArrayOutputStream()
        val ok = bitmap.compress(format.compressFormat, quality.coerceIn(50, 100), bos)
        check(ok) { "图像编码失败" }
        val bytes = bos.toByteArray()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cr = context.contentResolver
            val relPath = "${Environment.DIRECTORY_PICTURES}/Zbox"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, format.mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("无法创建相册保存条目")
            try {
                cr.openOutputStream(uri)?.use { it.write(bytes) }
                    ?: error("无法写入系统相册输出流")
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                cr.update(uri, values, null, null)
            } catch (t: Throwable) {
                runCatching { cr.delete(uri, null, null) }
                throw t
            }
            ExportResult(
                displayName = fileName,
                width = bitmap.width,
                height = bitmap.height,
                sizeBytes = bytes.size.toLong(),
                relativePath = relPath,
            )
        } else {
            @Suppress("DEPRECATION")
            val pubDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Zbox")
            val targetDir = if (pubDir.exists() || pubDir.mkdirs()) {
                pubDir
            } else {
                File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "Zbox").apply { mkdirs() }
            }
            val outFile = File(targetDir, fileName)
            FileOutputStream(outFile).use { it.write(bytes) }
            MediaScannerConnection.scanFile(context, arrayOf(outFile.absolutePath), arrayOf(format.mime), null)
            ExportResult(
                displayName = fileName,
                width = bitmap.width,
                height = bitmap.height,
                sizeBytes = bytes.size.toLong(),
                relativePath = outFile.parent ?: "Pictures/Zbox",
            )
        }
    }
}
