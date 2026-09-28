package dev.kortex.links.data

import android.database.sqlite.SQLiteConstraintException
import dev.kortex.links.domain.model.Link
import dev.kortex.links.domain.model.LinkDraft
import dev.kortex.links.domain.model.LinkImageSource
import dev.kortex.links.domain.model.TagCount
import dev.kortex.links.domain.repository.LinksRepository
import dev.kortex.links.domain.repository.SaveLinkResult
import dev.kortex.links.images.LinkImageStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomLinksRepository(
    private val linkDao: LinkDao,
    private val tagDao: TagDao,
    private val imageStore: LinkImageStore,
): LinksRepository {
    override fun observeTagNames(): Flow<List<String>> = tagDao.observeAll().map { tags -> tags.map { it.name } }

    /** Newest first. */
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeLinks(): Flow<List<Link>> = linkDao.observeLinksWithTags().map { list -> list.map { it.toDomain() } }

    /** Every tag, including unused ones (count 0), ordered by name. Tags orphaned by [deleteLink] are gone. */
    override fun observeTagCounts(): Flow<List<TagCount>> = tagDao.observeTagLinkCounts()

    /** No-op if a tag with this name already exists (names are case-insensitive). */
    override suspend fun createTag(name: String) {
        tagDao.insert(TagEntity(name = name.trim()))
    }

    /**
     * Deletes the link together with its tag assignments, any tag left with no links because of it,
     * and its thumbnail in app storage. The rows go first, in one transaction: a crash before the
     * files are removed leaves only orphan files, which [LinkImageStore] sweeps up, never a link
     * pointing at a missing image.
     */
    override suspend fun deleteLink(id: Long) {
        linkDao.deleteWithOrphanedTags(id)
        imageStore.deleteImages(id)
    }

    /**
     * Replaces the link's tags with [tagNames], creating any that don't exist yet. Tags the link
     * drops that no other link carries are deleted, as [deleteLink] does.
     */
    override suspend fun setLinkTags(linkId: Long, tagNames: List<String>) {
        linkDao.replaceTags(linkId, tagNames.map { it.trim() }.filter { it.isNotEmpty() })
    }

    /** The saved link that [url] is an address of (see [linkUrlKey]), or null; follows saves as they happen. */
    override fun observeSavedLink(url: String): Flow<Link?> = linkDao.observeWithTagsByUrlKey(linkUrlKey(url)).map { it?.toDomain() }

    /**
     * Saves right away; the thumbnail follows when its download finishes, even if that's after the
     * form has closed. [imageHidden] is kept either way so the user's choice survives a late image.
     *
     * @return false, saving nothing, when this address is already saved.
     */
    override suspend fun saveLink(
        draft: LinkDraft,
        nowMillis: Long,
    ): SaveLinkResult {
        val tagIds = if (draft.tags.isEmpty()) emptyList() else tagDao.getByNames(draft.tags).map { it.id }
        val linkId = try {
            linkDao.insertWithTags(
                LinkEntity(
                    url = draft.url,
                    title = draft.title,
                    createdAtMillis = nowMillis,
                    imageUrl = (draft.image as? LinkImageSource.Known)?.imageUrl,
                    imageHidden = draft.imageHidden,
                ),
                tagIds,
            )
        } catch (e: SQLiteConstraintException) {
            // The unique urlKey index: the form's live check can lag a keystroke behind the field.
            return SaveLinkResult.AlreadySaved
        }
        imageStore.attachWhenReady(linkId, draft.url, draft.image)
        return SaveLinkResult.Saved(linkId)
    }
}
