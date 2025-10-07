package com.example.wechatbot.ocr

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class TemplateRepository(context: Context) {
    private val galleryDir: File = (context.getExternalFilesDir(GALLERY_DIR_NAME)
        ?: File(context.filesDir, GALLERY_DIR_NAME)).apply {
        if (!exists()) {
            mkdirs()
        }
    }

    private val cache = ConcurrentHashMap<String, Bitmap>()

    fun listTemplates(): List<String> {
        return galleryDir.listFiles { file ->
            file.isFile && TEMPLATE_EXTENSIONS.any { ext -> file.name.endsWith(ext, ignoreCase = true) }
        }?.map { it.name }?.sortedBy { it.lowercase(Locale.getDefault()) } ?: emptyList()
    }

    fun loadTemplate(name: String): Bitmap? {
        val key = name.lowercase(Locale.getDefault())
        cache[key]?.let { if (!it.isRecycled) return it }
        val file = resolveFile(name) ?: return null
        val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return null
        cache[key] = bitmap
        return bitmap
    }

    fun importTemplate(contentResolver: ContentResolver, uri: Uri, desiredName: String?): String? {
        val sanitized = sanitizeName(desiredName) ?: "template_${System.currentTimeMillis()}"
        val (base, ext) = splitName(sanitized)
        val extension = ext.ifBlank { inferExtension(contentResolver, uri) ?: DEFAULT_EXTENSION }
        var index = 0
        var candidate: File
        do {
            val suffix = if (index == 0) "" else "_${index.toString().padStart(2, '0')}"
            candidate = File(galleryDir, "$base$suffix.$extension")
            index++
        } while (candidate.exists())

        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(candidate).use { output ->
                input.copyTo(output)
            }
        } ?: return null

        cache.remove(candidate.name.lowercase(Locale.getDefault()))
        return candidate.name
    }

    fun getGalleryDirectory(): File = galleryDir

    fun resolveDisplayName(contentResolver: ContentResolver, uri: Uri): String? {
        return contentResolver.query(uri, arrayOf(android.provider.MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(0)
                } else {
                    null
                }
            }
    }

    private fun resolveFile(name: String): File? {
        val direct = File(galleryDir, name)
        if (direct.exists()) return direct
        return galleryDir.listFiles()?.firstOrNull { file ->
            file.name.equals(name, ignoreCase = true)
        }
    }

    private fun sanitizeName(name: String?): String? {
        if (name.isNullOrBlank()) return null
        val normalized = name.trim().replace(Regex("[^A-Za-z0-9._-]"), "_")
        return normalized.takeIf { it.isNotBlank() }
    }

    private fun splitName(name: String): Pair<String, String> {
        val dotIndex = name.lastIndexOf('.')
        return if (dotIndex > 0 && dotIndex < name.lastIndex) {
            name.substring(0, dotIndex) to name.substring(dotIndex + 1)
        } else {
            name to ""
        }
    }

    private fun inferExtension(contentResolver: ContentResolver, uri: Uri): String? {
        val type = contentResolver.getType(uri) ?: return null
        return when (type.lowercase(Locale.getDefault())) {
            "image/png" -> "png"
            "image/jpg", "image/jpeg" -> "jpg"
            "image/webp" -> "webp"
            else -> null
        }
    }

    companion object {
        private const val GALLERY_DIR_NAME = "ocr_gallery"
        private val TEMPLATE_EXTENSIONS = listOf(".png", ".jpg", ".jpeg", ".webp")
        private const val DEFAULT_EXTENSION = "png"
    }
}
