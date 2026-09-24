package dev.kortex.myinfo.topics.ui.common

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import dev.kortex.myinfo.topics.data.files.TopicFiles
import dev.kortex.myinfo.topics.domain.model.StoredFile

/**
 * Hands a file — one a topic keeps, or an email attachment just downloaded — to whichever app
 * opens its type, with read access for that one launch.
 * @return false when nothing on the phone will take it, so the caller can say so.
 */
internal fun Context.openFile(file: StoredFile): Boolean {
    val uri = TopicFiles.contentUri(this, file.path) ?: return false
    val view = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, file.mimeType)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        startActivity(view)
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}
