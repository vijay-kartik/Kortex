package dev.kortex.links.data

import android.database.sqlite.SQLiteConstraintException
import dev.kortex.links.images.LinkImageSource
import dev.kortex.links.images.LinkImageStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LinksRepository @Inject constructor(
    private val linkDao: LinkDao,
    private val tagDao: TagDao,
    private val imageStore: LinkImageStore,
) {
    fun observeTagNames(): Flow<List<String>> = tagDao.observeAll().map { tags -> tags.map { it.name } }

    /** Newest first. */
    fun observeLinks(): Flow<List<LinkWithTags>> = linkDao.observeLinksWithTags()

    /** Every tag, including unused ones (count 0), ordered by name. */
    fun observeTagLinkCounts(): Flow<List<TagLinkCount>> = tagDao.observeTagLinkCounts()

    /** No-op if a tag with this name already exists (names are case-insensitive). */
    suspend fun createTag(name: String) {
        tagDao.insert(TagEntity(name = name.trim()))
    }

    /**
     * Deletes the link together with its tag assignments and its thumbnail in app storage. The row
     * goes first: a crash before the files are removed leaves only orphans, which [LinkImageStore]
     * sweeps up, never a link pointing at a missing image.
     */
    suspend fun deleteLink(id: Long) {
        linkDao.delete(id)
        imageStore.deleteImages(id)
    }

    /** The saved link that [url] is an address of (see [linkUrlKey]), or null; follows saves as they happen. */
    fun observeSavedLink(url: String): Flow<LinkEntity?> = linkDao.observeByUrlKey(linkUrlKey(url))

    /**
     * Saves right away; the thumbnail follows when its download finishes, even if that's after the
     * form has closed. [imageHidden] is kept either way so the user's choice survives a late image.
     *
     * @return false, saving nothing, when this address is already saved.
     */
    suspend fun saveLink(
        url: String,
        title: String,
        tagNames: List<String>,
        image: LinkImageSource = LinkImageSource.Unknown,
        imageHidden: Boolean = false,
    ): Boolean {
        val tagIds = if (tagNames.isEmpty()) emptyList() else tagDao.getByNames(tagNames).map { it.id }
        val linkId = try {
            linkDao.insertWithTags(
                LinkEntity(
                    url = url,
                    title = title,
                    createdAtMillis = System.currentTimeMillis(),
                    imageUrl = (image as? LinkImageSource.Known)?.imageUrl,
                    imageHidden = imageHidden,
                ),
                tagIds,
            )
        } catch (e: SQLiteConstraintException) {
            // The unique urlKey index: the form's live check can lag a keystroke behind the field.
            return false
        }
        imageStore.attachWhenReady(linkId, url, image)
        return true
    }
}
