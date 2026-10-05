package dev.kortex.sync.finance

import android.util.Log
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import dev.kortex.finance.data.local.FinancePullResult
import dev.kortex.finance.data.local.FinanceSyncDao
import dev.kortex.finance.data.local.FinanceSyncSchema
import dev.kortex.finance.data.local.RemoteRow
import dev.kortex.sync.PendingWrite
import dev.kortex.sync.RemoteChanges
import dev.kortex.sync.SyncCollection
import dev.kortex.sync.SyncRemote
import kotlinx.coroutines.flow.Flow

/**
 * Moves finance.db's records to and from `users/{uid}/fin*` (docs/FINANCE_PLAN.md › Firestore).
 * Rows are keyed by their document ids, so nothing is translated, and nothing is held back: a
 * transaction pulled before its account names the account's uid, which resolves once it lands.
 * Built-in categories are seeded on every phone and never synced. Balances aren't stored anywhere,
 * so two phones only ever conflict over the same record, and the last writer wins.
 */
internal class FinanceSync(
    private val remote: SyncRemote,
    private val dao: FinanceSyncDao,
) {
    /** The finance collections, in pull and push order: what's referred to before what refers to it. */
    val collections: List<SyncCollection> = listOf(
        SyncCollection.FinCategories,
        SyncCollection.FinAccounts,
        SyncCollection.FinStatements,
        SyncCollection.FinRecurring,
        SyncCollection.FinMerchants,
        SyncCollection.FinTransactions,
    )

    suspend fun countChanged(userUid: String): Int = collections.sumOf { remote.countChanged(userUid, it) }

    /** Applies every finance document written since the last pull; [onPage] gets each page's size. */
    suspend fun pull(userUid: String, onPage: (Int) -> Unit) {
        for (collection in collections) {
            remote.pullChanged(userUid, collection) { docs ->
                apply(collection, docs)
                onPage(docs.size)
            }
        }
    }

    /** One collection's documents as other clients write them, for live sync. */
    fun changes(userUid: String, collection: SyncCollection): Flow<RemoteChanges> = remote.changes(userUid, collection)

    /** Applies pulled or heard documents of one finance collection. */
    suspend fun apply(collection: SyncCollection, docs: List<DocumentSnapshot>): FinancePullResult {
        fun <T> rows(read: (String, Map<String, Any?>) -> RemoteRow<T>?): List<RemoteRow<T>> = docs.mapNotNull { doc ->
            read(doc.id, doc.data.orEmpty()).also { if (it == null) Log.w(TAG, "Skipping malformed ${collection.path} doc ${doc.id}") }
        }
        return when (collection) {
            SyncCollection.FinCategories -> dao.applyPulledCategories(rows(::remoteCategory))
            SyncCollection.FinAccounts -> dao.applyPulledAccounts(rows(::remoteAccount))
            SyncCollection.FinStatements -> dao.applyPulledStatements(rows(::remoteStatement))
            SyncCollection.FinRecurring -> dao.applyPulledRecurring(rows(::remoteRecurring))
            SyncCollection.FinMerchants -> dao.applyPulledMerchants(rows(::remoteMerchant))
            SyncCollection.FinTransactions -> dao.applyPulledTransactions(rows(::remoteTransaction))
            else -> error("${collection.path} isn't a finance collection")
        }
    }

    /**
     * Writes every local change: deletes first, then records in [collections] order. A row is
     * marked in sync only once its batch commits, and only if it hasn't changed again meanwhile.
     */
    suspend fun push(userUid: String) {
        val now = FieldValue.serverTimestamp()
        fun doc(collection: SyncCollection, uid: String) = remote.collection(userUid, collection).document(uid)

        val deletes = dao.tombstones().mapNotNull { tombstone ->
            val collection = collectionOf(tombstone.kind) ?: return@mapNotNull null
            PendingWrite(doc(collection, tombstone.uid), finDeletedDoc(tombstone.deletedAtMillis, now)) {
                dao.deleteTombstone(tombstone.kind, tombstone.uid, tombstone.deletedAtMillis)
            }
        }
        val categories = dao.dirtyCategories().map { row ->
            PendingWrite(doc(SyncCollection.FinCategories, row.uid), categoryDoc(row, now)) { dao.markCategoryPushed(row.uid, row.dirty) }
        }
        val accounts = dao.dirtyAccounts().map { row ->
            PendingWrite(doc(SyncCollection.FinAccounts, row.uid), accountDoc(row, now)) { dao.markAccountPushed(row.uid, row.dirty) }
        }
        val statements = dao.dirtyStatements().map { row ->
            PendingWrite(doc(SyncCollection.FinStatements, row.uid), statementDoc(row, now)) { dao.markStatementPushed(row.uid, row.dirty) }
        }
        val recurring = dao.dirtyRecurring().map { row ->
            PendingWrite(doc(SyncCollection.FinRecurring, row.uid), recurringDoc(row, now)) { dao.markRecurringPushed(row.uid, row.dirty) }
        }
        val merchants = dao.dirtyMerchants().map { row ->
            PendingWrite(doc(SyncCollection.FinMerchants, row.uid), merchantDoc(row, now)) { dao.markMerchantPushed(row.uid, row.dirty) }
        }
        val transactions = dao.dirtyTransactions().map { row ->
            PendingWrite(doc(SyncCollection.FinTransactions, row.uid), transactionDoc(row, now)) { dao.markTransactionPushed(row.uid, row.dirty) }
        }
        remote.write(deletes + categories + accounts + statements + recurring + merchants + transactions)
    }

    private fun collectionOf(kind: String): SyncCollection? = when (kind) {
        FinanceSyncSchema.KIND_CATEGORY -> SyncCollection.FinCategories
        FinanceSyncSchema.KIND_ACCOUNT -> SyncCollection.FinAccounts
        FinanceSyncSchema.KIND_STATEMENT -> SyncCollection.FinStatements
        FinanceSyncSchema.KIND_RECURRING -> SyncCollection.FinRecurring
        FinanceSyncSchema.KIND_MERCHANT -> SyncCollection.FinMerchants
        FinanceSyncSchema.KIND_TRANSACTION -> SyncCollection.FinTransactions
        else -> null.also { Log.w(TAG, "Unknown tombstone kind $kind") }
    }

    private companion object {
        const val TAG = "FinanceSync"
    }
}
