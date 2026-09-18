package dev.kortex.myinfo.topics.domain.usecase

import dev.kortex.myinfo.topics.domain.model.PickedFile
import dev.kortex.myinfo.topics.domain.port.FileVault

/**
 * Keeps a file the user just picked, so the sheet can show it and the topic still has it once the
 * app it came from takes its permission back. Null when the file can't be read.
 */
class KeepPickedFile(private val fileVault: FileVault) {
    suspend operator fun invoke(uri: String): PickedFile? = fileVault.store(uri)
}

/** Throws away a kept file the user never saved — a swapped attachment, or an abandoned sheet. */
class DiscardPickedFile(private val fileVault: FileVault) {
    suspend operator fun invoke(file: PickedFile) = fileVault.delete(listOf(file.file.path))
}
