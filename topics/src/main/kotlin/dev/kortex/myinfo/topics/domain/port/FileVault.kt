package dev.kortex.myinfo.topics.domain.port

import dev.kortex.myinfo.topics.domain.model.PickedFile

/**
 * App storage for the files topics keep: a doc, an image, a bill's invoice. Picked files are
 * copied in rather than referenced, so a topic still has them once the app that shared them
 * revokes its permission or the original is gone.
 */
interface FileVault {
    /**
     * Copies whatever [uri] points at into app storage. Null when it can't be read, is empty or
     * is bigger than the vault takes.
     */
    suspend fun store(uri: String): PickedFile?

    /** Removes stored files. Paths the vault doesn't own, and files already gone, are ignored. */
    suspend fun delete(paths: Collection<String>)
}
