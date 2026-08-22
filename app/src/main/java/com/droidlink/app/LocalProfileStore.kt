package com.droidlink.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

data class LocalUserProfile(val displayName: String, val hasCustomAvatar: Boolean)

object ProfilePolicy {
    const val DEFAULT_DISPLAY_NAME = "Player"
    const val MAX_DISPLAY_NAME_LENGTH = 24
    const val MAX_AVATAR_SOURCE_BYTES = 5 * 1024 * 1024
    const val MAX_AVATAR_SOURCE_DIMENSION = 4_096
    const val AVATAR_OUTPUT_DIMENSION = 512

    fun normalizeDisplayName(value: String?, fallback: String = DEFAULT_DISPLAY_NAME): String {
        val normalized = value.orEmpty()
            .filterNot(Char::isISOControl)
            .trim()
            .replace(Regex("\\s+"), " ")
            .take(MAX_DISPLAY_NAME_LENGTH)
        return normalized.ifBlank { fallback.take(MAX_DISPLAY_NAME_LENGTH) }
    }
}

class LocalProfileStore(private val context: Context) {
    private val preferences = context.getSharedPreferences("droid_link_profile", Context.MODE_PRIVATE)
    private val profileDirectory = File(context.filesDir, "profile")
    private val avatarFile = File(profileDirectory, "avatar.png")

    fun loadProfile(): LocalUserProfile = LocalUserProfile(
        displayName = ProfilePolicy.normalizeDisplayName(preferences.getString("display_name", null)),
        hasCustomAvatar = avatarFile.isFile
    )

    fun saveDisplayName(value: String): String {
        val normalized = ProfilePolicy.normalizeDisplayName(value)
        preferences.edit().putString("display_name", normalized).apply()
        return normalized
    }

    fun loadAvatarBitmap(): Bitmap? = runCatching {
        if (!avatarFile.isFile) return@runCatching null
        BitmapFactory.decodeFile(avatarFile.absolutePath)
    }.getOrNull()

    fun importAvatar(uri: Uri): Result<Unit> = runCatching {
        val mimeType = context.contentResolver.getType(uri)
        require(mimeType == null || mimeType.startsWith("image/")) { "Selected file is not an image" }
        val bytes = readCappedImage(uri)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth in 1..ProfilePolicy.MAX_AVATAR_SOURCE_DIMENSION) { "Image width is unsupported" }
        require(bounds.outHeight in 1..ProfilePolicy.MAX_AVATAR_SOURCE_DIMENSION) { "Image height is unsupported" }

        var sampleSize = 1
        while (bounds.outWidth / sampleSize > ProfilePolicy.AVATAR_OUTPUT_DIMENSION * 2 ||
            bounds.outHeight / sampleSize > ProfilePolicy.AVATAR_OUTPUT_DIMENSION * 2
        ) sampleSize *= 2
        val decoded = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sampleSize }
        ) ?: error("Image could not be decoded")
        val longestEdge = maxOf(decoded.width, decoded.height)
        val scale = minOf(1f, ProfilePolicy.AVATAR_OUTPUT_DIMENSION.toFloat() / longestEdge)
        val output = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * scale).toInt().coerceAtLeast(1),
                (decoded.height * scale).toInt().coerceAtLeast(1),
                true
            )
        } else decoded

        check(profileDirectory.exists() || profileDirectory.mkdirs()) { "Profile directory is unavailable" }
        val temporary = File(profileDirectory, "avatar.tmp")
        FileOutputStream(temporary).use { stream ->
            check(output.compress(Bitmap.CompressFormat.PNG, 100, stream)) { "Image could not be saved" }
        }
        if (avatarFile.exists()) check(avatarFile.delete()) { "Previous profile image could not be replaced" }
        check(temporary.renameTo(avatarFile)) { "Profile image could not be finalized" }
        if (output !== decoded) decoded.recycle()
        output.recycle()
    }

    fun removeAvatar(): Boolean = !avatarFile.exists() || avatarFile.delete()

    private fun readCappedImage(uri: Uri): ByteArray {
        val output = ByteArrayOutputStream()
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                check(output.size() + count <= ProfilePolicy.MAX_AVATAR_SOURCE_BYTES) { "Image is too large" }
                output.write(buffer, 0, count)
            }
        } ?: error("Selected image is unavailable")
        return output.toByteArray().also { require(it.isNotEmpty()) { "Selected image is empty" } }
    }
}
