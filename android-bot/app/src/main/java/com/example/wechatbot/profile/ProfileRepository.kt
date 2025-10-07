package com.example.wechatbot.profile

import android.content.Context
import androidx.annotation.RawRes
import com.example.wechatbot.R
import java.io.File

class ProfileRepository(private val context: Context) {
    data class Document(
        val id: String,
        val displayName: String,
        val content: String,
        val editable: Boolean,
    )

    private val profilesDir: File = File(context.filesDir, "profiles").apply {
        if (!exists()) {
            mkdirs()
        }
    }

    fun loadProfiles(): List<Document> {
        val builtIn = listOfNotNull(
            loadBuiltIn(BUILTIN_DEFAULT_ID, context.getString(R.string.profile_builtin_default), R.raw.default_profile),
            loadBuiltIn(BUILTIN_DEBUG_ID, context.getString(R.string.profile_builtin_debug), R.raw.debug_profile),
            loadBuiltIn(BUILTIN_IMAGE_DEBUG_ID, context.getString(R.string.profile_builtin_image_debug), R.raw.image_probe_profile),
        )
        val custom = profilesDir.listFiles { file ->
            file.extension.equals("json", ignoreCase = true)
        }?.mapNotNull { file ->
            val content = runCatching { file.readText() }.getOrNull() ?: return@mapNotNull null
            val profile = ProfileLoader.loadFromString(content)
            val name = profile?.name?.ifBlank { null } ?: file.nameWithoutExtension
            Document(
                id = "$CUSTOM_PREFIX${file.nameWithoutExtension}",
                displayName = name,
                content = content,
                editable = true,
            )
        }?.sortedBy { it.displayName.lowercase() } ?: emptyList()
        return builtIn + custom
    }

    fun saveProfile(existingId: String?, desiredName: String, json: String): Document? {
        val parsed = ProfileLoader.loadFromString(json) ?: return null
        val normalizedName = desiredName.ifBlank { parsed.name }
        val updatedProfile = parsed.copy(name = normalizedName.ifBlank { DEFAULT_PROFILE_NAME })
        val normalizedJson = ProfileLoader.toJson(updatedProfile)
        val targetId = existingId?.takeIf { it.startsWith(CUSTOM_PREFIX) } ?: "$CUSTOM_PREFIX${System.currentTimeMillis()}"
        val file = File(profilesDir, targetId.removePrefix(CUSTOM_PREFIX) + ".json")
        file.parentFile?.mkdirs()
        file.writeText(normalizedJson)
        return Document(
            id = targetId,
            displayName = updatedProfile.name,
            content = normalizedJson,
            editable = true,
        )
    }

    fun deleteProfile(id: String): Boolean {
        if (!id.startsWith(CUSTOM_PREFIX)) return false
        val file = File(profilesDir, id.removePrefix(CUSTOM_PREFIX) + ".json")
        return file.delete()
    }

    fun loadProfileById(id: String): Document? {
        return when {
            id == BUILTIN_DEFAULT_ID -> loadBuiltIn(BUILTIN_DEFAULT_ID, context.getString(R.string.profile_builtin_default), R.raw.default_profile)
            id == BUILTIN_DEBUG_ID -> loadBuiltIn(BUILTIN_DEBUG_ID, context.getString(R.string.profile_builtin_debug), R.raw.debug_profile)
            id == BUILTIN_IMAGE_DEBUG_ID -> loadBuiltIn(BUILTIN_IMAGE_DEBUG_ID, context.getString(R.string.profile_builtin_image_debug), R.raw.image_probe_profile)
            id.startsWith(CUSTOM_PREFIX) -> {
                val file = File(profilesDir, id.removePrefix(CUSTOM_PREFIX) + ".json")
                if (!file.exists()) return null
                val content = runCatching { file.readText() }.getOrNull() ?: return null
                val profile = ProfileLoader.loadFromString(content)
                val name = profile?.name?.ifBlank { null } ?: file.nameWithoutExtension
                Document(id, name, content, editable = true)
            }
            else -> null
        }
    }

    private fun loadBuiltIn(id: String, displayName: String, @RawRes resId: Int): Document? {
        val content = ProfileLoader.readRawText(context, resId) ?: return null
        val profile = ProfileLoader.loadFromString(content)
        val name = profile?.name ?: displayName
        return Document(id, name, content, editable = false)
    }

    companion object {
        private const val CUSTOM_PREFIX = "custom:"
        private const val BUILTIN_DEFAULT_ID = "builtin:default"
        private const val BUILTIN_DEBUG_ID = "builtin:debug"
        private const val BUILTIN_IMAGE_DEBUG_ID = "builtin:image-debug"
        private const val DEFAULT_PROFILE_NAME = "自定义脚本"
    }
}
