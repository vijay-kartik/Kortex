package dev.kortex.sync

import android.content.Context
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.MemoryCacheSettings
import dev.kortex.finance.data.local.FinanceSyncDao
import dev.kortex.links.data.LinkSyncDao
import dev.kortex.links.domain.port.LinkThumbnails
import dev.kortex.myinfo.topics.data.local.TopicSyncDao
import dev.kortex.myinfo.topics.domain.port.FileVault
import dev.kortex.sync.finance.FinanceSync
import dev.kortex.sync.links.LinkSync
import dev.kortex.sync.topics.TopicSync

/** Builds [CloudSync] and what it syncs through; the app's DI calls it once. */
object SyncModule {

    fun cloudSync(
        context: Context,
        account: CloudAccount,
        linkDao: LinkSyncDao,
        linkImages: LinkThumbnails,
        topicDao: TopicSyncDao,
        topicFiles: FileVault,
        financeDao: FinanceSyncDao,
    ): CloudSync {
        val store = SyncStore(context)
        // Room is the app's copy; Firestore's own disk cache would only be a third one.
        val firestore = FirebaseFirestore.getInstance().apply {
            firestoreSettings = FirebaseFirestoreSettings.Builder()
                .setLocalCacheSettings(MemoryCacheSettings.newBuilder().build())
                .build()
        }
        val remote = SyncRemote(firestore, store)
        val topics = TopicSync(remote, store, topicDao, linkDao, topicFiles)
        // In pull and push order: items refer to links, and finances to neither.
        val parts = listOf(
            LinkSync(remote, linkDao, linkImages, afterHeard = topics::retryHeldItems),
            topics,
            FinanceSync(remote, financeDao),
        )
        val counts = object : LibraryCounts {
            override suspend fun links() = linkDao.linkCount()
            override suspend fun topics() = topicDao.topicCount()
            override suspend fun financeAccounts() = financeDao.accountCount()
            override suspend fun financeEntries() = financeDao.entryCount()
        }
        return FirestoreCloudSync(account.user, store, parts, counts)
    }
}
