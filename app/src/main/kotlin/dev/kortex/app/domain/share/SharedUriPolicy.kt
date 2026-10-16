package dev.kortex.app.domain.share

import dev.kortex.core.state.Attachment
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * What the share intake agrees to open. The shared Uri is read with Kortex's own identity
 * and permissions, so it must come from another app's provider: never a raw `file://` path
 * (which would reach Kortex's private storage) and never one of Kortex's own providers.
 */
object SharedUriPolicy {

    /** Largest file the intake loads into memory. */
    const val MAX_BYTES: Long = 20L * 1024 * 1024

    /**
     * True when [scheme]/[authority] point at a `content://` provider owned by another app.
     * [providerPackage] resolves an authority to the package that owns it (null if unknown).
     */
    fun isAllowedSource(
        scheme: String?,
        authority: String?,
        ownPackage: String,
        providerPackage: (String) -> String?,
    ): Boolean {
        if (scheme != "content") return false
        // ContentResolver routes "<userId>@<authority>" to <authority>, so judge that part.
        val host = authority?.substringAfterLast('@')?.takeIf { it.isNotBlank() } ?: return false
        if (host == ownPackage || host.startsWith("$ownPackage.")) return false
        return providerPackage(host) != ownPackage
    }

    /** Only photos and PDFs, as typed by the provider (never the caller's `intent.type`). */
    fun isAllowedMimeType(mimeType: String?): Boolean {
        val type = mimeType?.lowercase() ?: return false
        return type.startsWith("image/") || type == "application/pdf"
    }

    /** A declared size over the cap is refused up front; an unknown one is bounded by [readCapped]. */
    fun fitsSizeCap(declaredSize: Long?): Boolean = declaredSize == null || declaredSize <= MAX_BYTES

    /** Reads at most [max] bytes; null when the stream holds more than that. */
    fun readCapped(input: InputStream, max: Long = MAX_BYTES): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > max) return null
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
}

/** Outcome of loading a shared file for the composer. */
sealed interface SharedFileLoad {
    data class Loaded(val attachment: Attachment) : SharedFileLoad
    data class Refused(val message: String) : SharedFileLoad
}
