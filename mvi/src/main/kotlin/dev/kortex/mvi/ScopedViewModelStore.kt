package dev.kortex.mvi

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.VIEW_MODEL_STORE_OWNER_KEY
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

/**
 * Gives [content] its own ViewModelStore, for screens shown without a navigation library (the
 * app's full-screen overlays). ViewModels obtained inside — `hiltViewModel()` included — survive
 * configuration changes and are cleared once [content] leaves composition for good, so reopening
 * the screen starts fresh instead of reusing the activity's instance.
 */
@Composable
fun ScopedViewModelStore(key: String, content: @Composable () -> Unit) {
    val parent = checkNotNull(LocalViewModelStoreOwner.current) { "ScopedViewModelStore needs a ViewModelStoreOwner" }
    val stores: ScopedViewModelStores = viewModel(
        viewModelStoreOwner = parent,
        factory = viewModelFactory { initializer { ScopedViewModelStores() } },
    )
    val owner = remember(stores, key) { ScopedOwner(stores.storeFor(key), parent) }
    val activity = LocalContext.current.findActivity()
    DisposableEffect(stores, key) {
        // Leaving because of a rotation isn't leaving: the recreated screen picks the store up again.
        onDispose { if (activity?.isChangingConfigurations != true) stores.clear(key) }
    }
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner, content = content)
}

/** Lives in the parent's store and holds one child store per key. */
private class ScopedViewModelStores : ViewModel() {
    private val stores = mutableMapOf<String, ViewModelStore>()

    fun storeFor(key: String): ViewModelStore = stores.getOrPut(key) { ViewModelStore() }

    fun clear(key: String) {
        stores.remove(key)?.clear()
    }

    override fun onCleared() {
        stores.values.forEach { it.clear() }
        stores.clear()
    }
}

/**
 * Borrows the parent's factory (Hilt's, under `@AndroidEntryPoint`) but points the creation extras
 * at this store, so each ViewModel's SavedStateHandle bookkeeping is cleared along with it.
 */
private class ScopedOwner(
    override val viewModelStore: ViewModelStore,
    private val parent: ViewModelStoreOwner,
) : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
    private val parentDefaults = parent as? HasDefaultViewModelProviderFactory

    override val defaultViewModelProviderFactory: ViewModelProvider.Factory
        get() = parentDefaults?.defaultViewModelProviderFactory ?: ViewModelProvider.NewInstanceFactory()

    override val defaultViewModelCreationExtras: CreationExtras
        get() = MutableCreationExtras(parentDefaults?.defaultViewModelCreationExtras ?: CreationExtras.Empty).apply {
            set(VIEW_MODEL_STORE_OWNER_KEY, this@ScopedOwner)
        }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
