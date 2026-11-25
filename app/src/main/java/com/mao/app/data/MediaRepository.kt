package com.mao.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.storage.storage
import io.ktor.http.ContentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlin.time.Duration.Companion.hours

class Attachment(
    val bytes: ByteArray,
    val name: String,
    val mime: String,
    val kind: String,          // image | video | document | voice
    val durationMs: Int? = null,
) {
    val size: Long get() = bytes.size.toLong()
}

class MediaRepository(private val supabase: SupabaseClient, private val context: Context) {

    companion object {
        const val MAX_UPLOAD_BYTES = 50L * 1024 * 1024
        private const val CHAT_BUCKET = "chat-media"
        private const val AVATAR_BUCKET = "avatars"
    }

    private val signedCache = HashMap<String, Pair<String, Long>>()
    private val cacheLock = Mutex()

    /** Load a picked file. Large photos are downscaled to keep uploads fast. */
    suspend fun readAttachment(uri: Uri, kindHint: String? = null): Attachment = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        var name = "file"
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                name = c.getString(0) ?: name
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        if (size > MAX_UPLOAD_BYTES) error("Files must be smaller than 50 MB")
        val kind = kindHint ?: when {
            mime.startsWith("image/") -> "image"
            mime.startsWith("video/") -> "video"
            else -> "document"
        }
        var bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Could not read the file")
        if (bytes.size > MAX_UPLOAD_BYTES) error("Files must be smaller than 50 MB")
        var finalMime = mime
        if (kind == "image" && mime != "image/gif") {
            downscaleJpeg(bytes, 2048)?.let { bytes = it; finalMime = "image/jpeg"; name = name.substringBeforeLast('.') + ".jpg" }
        }
        Attachment(bytes, name, finalMime, kind)
    }

    fun readVoiceFile(file: File, durationMs: Int): Attachment =
        Attachment(file.readBytes(), "voice-${System.currentTimeMillis()}.m4a", "audio/mp4", "voice", durationMs)

    /** Uploads into chat-media/<conversation>/<me>/<random>.<ext> and returns the path. */
    suspend fun uploadChatMedia(conversationId: String, attachment: Attachment): String {
        val me = supabase.requireMyId()
        val ext = extensionFor(attachment)
        val path = "$conversationId/$me/${UUID.randomUUID()}.$ext"
        supabase.storage.from(CHAT_BUCKET).upload(path, attachment.bytes) {
            upsert = false
            contentType = runCatching { ContentType.parse(attachment.mime) }.getOrDefault(ContentType.Application.OctetStream)
        }
        return path
    }

    suspend fun deleteChatMedia(path: String) {
        runCatching { supabase.storage.from(CHAT_BUCKET).delete(path) }
        cacheLock.withLock { signedCache.remove(path) }
    }

    /** Short-lived signed URL for a private chat file (cached until shortly before expiry). */
    suspend fun signedUrl(path: String): String {
        val now = System.currentTimeMillis()
        cacheLock.withLock { signedCache[path]?.takeIf { it.second > now }?.let { return it.first } }
        val url = supabase.storage.from(CHAT_BUCKET).createSignedUrl(path, 1.hours)
        cacheLock.withLock { signedCache[path] = url to now + 50 * 60 * 1000 }
        return url
    }

    /** Uploads a square-ish avatar for yourself or a group and returns its public URL. */
    suspend fun uploadAvatar(uri: Uri, groupId: String? = null): String = withContext(Dispatchers.IO) {
        val raw = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Could not read the image")
        val jpeg = downscaleJpeg(raw, 640, force = true) ?: error("Unsupported image")
        val folder = if (groupId != null) "groups/$groupId" else "users/${supabase.requireMyId()}"
        val path = "$folder/avatar-${System.currentTimeMillis()}.jpg"
        val bucket = supabase.storage.from(AVATAR_BUCKET)
        bucket.upload(path, jpeg) { upsert = true; contentType = ContentType.Image.JPEG }
        bucket.publicUrl(path)
    }

    private fun extensionFor(a: Attachment): String =
        a.name.substringAfterLast('.', "").lowercase().takeIf { it.length in 1..5 && it.all(Char::isLetterOrDigit) }
            ?: MimeTypeMap.getSingleton().getExtensionFromMimeType(a.mime)
            ?: "bin"

    private fun downscaleJpeg(bytes: ByteArray, maxSide: Int, force: Boolean = false): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        val largest = maxOf(bounds.outWidth, bounds.outHeight)
        if (!force && largest <= maxSide && bytes.size < 1_500_000) return null
        var sample = 1
        while (largest / (sample * 2) >= maxSide) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val scale = maxSide.toFloat() / maxOf(decoded.width, decoded.height)
        val scaled = if (scale < 1f) Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true) else decoded
        // Re-encoding drops EXIF, so bake the camera orientation into the pixels.
        val rotation = runCatching { androidx.exifinterface.media.ExifInterface(bytes.inputStream()).rotationDegrees }.getOrDefault(0)
        val bmp = if (rotation != 0) {
            Bitmap.createBitmap(scaled, 0, 0, scaled.width, scaled.height, android.graphics.Matrix().apply { postRotate(rotation.toFloat()) }, true)
        } else scaled
        return ByteArrayOutputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
            out.toByteArray()
        }
    }
}
