package dev.kortex.graph_storage

import android.content.Context
import io.objectbox.Box
import io.objectbox.BoxStore

/**
 * Holder of the single [BoxStore] for the whole knowledge graph.
 *
 * One store, one model: the ObjectBox Gradle plugin generates MyObjectBox
 * from all @Entity classes in THIS module, which is why every ObjectBox
 * entity — graph infrastructure now, business entities later — must live in
 * graph-storage. Feature modules keep domain logic; only @Entity classes
 * are pinned here.
 *
 * Call [init] once from Application.onCreate before any graph use.
 */
object GraphStore {

    @Volatile
    private var store: BoxStore? = null

    val boxStore: BoxStore
        get() = checkNotNull(store) {
            "GraphStore.init(context) must be called before use " +
                "(typically in Application.onCreate)."
        }

    fun init(context: Context) {
        if (store != null) return
        synchronized(this) {
            if (store == null) {
                store = MyObjectBox.builder()
                    .androidContext(context.applicationContext)
                    .name(GraphStorageConfig.STORE_NAME)
                    .build()
            }
        }
    }

    fun registry(): Box<GraphRegistryEntity> = boxStore.boxFor(GraphRegistryEntity::class.java)

    fun edges(): Box<EdgeEntity> = boxStore.boxFor(EdgeEntity::class.java)

    fun embeddings(): Box<EmbeddingEntity> = boxStore.boxFor(EmbeddingEntity::class.java)

    /** Test/teardown hook. Closes and forgets the store. */
    fun close() {
        synchronized(this) {
            store?.close()
            store = null
        }
    }
}
