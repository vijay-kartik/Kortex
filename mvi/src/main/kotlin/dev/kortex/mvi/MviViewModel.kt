package dev.kortex.mvi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Model-View-Intent ViewModel. The screen renders [state], reports everything the user does as an
 * intent through [onIntent], and reacts once to each [effects] item (navigation, share sheet,
 * snackbar) — anything that must not replay when the screen recomposes or rotates.
 *
 * Subclasses change state only through [setState], whose argument should be a pure reducer, so
 * the transitions can be unit-tested without the ViewModel.
 */
abstract class MviViewModel<S : Any, I : Any, E : Any>(initialState: S) : ViewModel() {
    private val _state = MutableStateFlow(initialState)
    val state: StateFlow<S> = _state.asStateFlow()

    // Buffered so an effect sent while the screen is stopped waits for it instead of being lost.
    private val _effects = Channel<E>(Channel.BUFFERED)
    val effects: Flow<E> = _effects.receiveAsFlow()

    /** The only way the UI talks to the ViewModel. */
    fun onIntent(intent: I) = handleIntent(intent)

    protected abstract fun handleIntent(intent: I)

    protected val currentState: S get() = _state.value

    protected fun setState(reducer: S.() -> S) = _state.update(reducer)

    protected fun sendEffect(effect: E) {
        viewModelScope.launch { _effects.send(effect) }
    }

    /** Folds each value of this flow into the state, for as long as the ViewModel lives. */
    protected fun <T> Flow<T>.reduceInto(reducer: S.(T) -> S) {
        viewModelScope.launch { collect { value -> setState { reducer(value) } } }
    }
}
