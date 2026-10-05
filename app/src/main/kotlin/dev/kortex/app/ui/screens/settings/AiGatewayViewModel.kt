package dev.kortex.app.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kortex.app.data.ai.AiGatewayClient
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Tools & Settings › AI GATEWAY: whether the key is set, and a one-question Jev round trip. */
@HiltViewModel
class AiGatewayViewModel @Inject constructor(
    private val gateway: AiGatewayClient,
) : ViewModel() {

    sealed interface TestState {
        data object Idle : TestState
        data object Running : TestState
        data class Passed(val summary: String) : TestState
        data class Failed(val message: String) : TestState
    }

    val isConfigured: Boolean = gateway.isConfigured
    val model: String = AiGatewayClient.JEV_MODEL

    private val _test = MutableStateFlow<TestState>(TestState.Idle)
    val test: StateFlow<TestState> = _test.asStateFlow()

    /** Asks Jev a boolean with an obvious answer; a probability near 1 means the pipe works. */
    fun runTest() {
        if (_test.value == TestState.Running) return
        _test.value = TestState.Running
        viewModelScope.launch {
            val started = System.currentTimeMillis()
            _test.value = try {
                val answers = gateway.evaluate(
                    state = JsonPrimitive("The support agent issued a full refund to the customer."),
                    questions = buildJsonObject {
                        putJsonObject("refunded") {
                            put("type", "boolean")
                            put("instructions", "Was a refund issued?")
                        }
                    },
                )
                val probability = answers["refunded"]?.jsonObject?.get("probability")?.jsonPrimitive?.doubleOrNull
                val elapsed = System.currentTimeMillis() - started
                TestState.Passed(
                    if (probability != null) "P(refunded) = ${"%.2f".format(probability)} · ${elapsed} ms"
                    else "Responded in $elapsed ms: $answers"
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                TestState.Failed(e.message ?: e::class.java.simpleName)
            }
        }
    }
}
