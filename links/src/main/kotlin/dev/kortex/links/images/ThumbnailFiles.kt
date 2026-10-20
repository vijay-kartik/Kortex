package dev.kortex.links.images

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject

/**
 * Where thumbnails live. Unsaved downloads sit in [draftsRoot], which the system may clear whenever
 * it needs space; a saved link's copy goes to [imagesRoot] as `<linkId>-<hash>.jpg`. Ids are never
 * reused, so the `<linkId>-` prefix owns its files. Creating one touches no disk.
 */
class ThumbnailFiles(private val draftsRoot: File, private val imagesRoot: File) {

    @Inject
    constructor(@ApplicationContext context: Context) :
        this(File(context.cacheDir, "link-image-drafts"), File(context.filesDir, "link-images"))

    /** The thumbnail shrunk from [imageUrl], before any link owns it. */
    fun draft(imageUrl: String) = File(draftsDir(), "${sha1(imageUrl)}.jpg")

    /** The full-size download of [imageUrl], kept only until it is shrunk. */
    fun original(imageUrl: String) = File(draftsDir(), "${sha1(imageUrl)}.download")

    /** Copies the [draft] of [imageUrl] out to [linkId]'s own file, replacing any earlier copy. */
    fun save(linkId: Long, imageUrl: String, draft: File): File {
        val saved = File(imagesDir(), "${prefix(linkId)}${sha1(imageUrl).take(12)}.jpg")
        draft.copyTo(saved, overwrite = true)
        return saved
    }

    /** Deletes every saved thumbnail of [linkId]. */
    fun delete(linkId: Long) {
        val prefix = prefix(linkId)
        imagesDir().listFiles { file -> file.name.startsWith(prefix) }?.forEach { it.delete() }
    }

    /** Every saved thumbnail, of links live or gone. */
    fun saved(): List<File> = imagesDir().listFiles().orEmpty().toList()

    /** The link [file] belongs to, or null for a file not named by [save]. */
    fun linkIdOf(file: File): Long? = file.name.substringBefore('-').toLongOrNull()

    private fun prefix(linkId: Long) = "$linkId-"

    private fun draftsDir() = draftsRoot.apply { mkdirs() }

    private fun imagesDir() = imagesRoot.apply { mkdirs() }
}

private fun sha1(value: String): String =
    MessageDigest.getInstance("SHA-1").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
