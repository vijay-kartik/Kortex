package dev.kortex.app.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kortex.design.Alarm
import dev.kortex.design.Amber
import dev.kortex.design.Edge
import dev.kortex.design.Muted
import dev.kortex.design.Panel
import dev.kortex.design.Synapse
import dev.kortex.design.SynapseDim

private val Passed = Color(0xFF4ADE80)

/** Tools & Settings › AI GATEWAY: Vercel AI Gateway → typesafe-ai/jev, with a test button. */
@Composable
internal fun AiGatewaySection(vm: AiGatewayViewModel = hiltViewModel()) {
    val test by vm.test.collectAsStateWithLifecycle()
    val running = test == AiGatewayViewModel.TestState.Running

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Panel,
        border = BorderStroke(1.dp, Edge),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                "Vercel AI Gateway",
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium),
            )
            Text(
                "Model: ${vm.model} (evaluation)",
                style = MaterialTheme.typography.bodySmall,
                color = Synapse,
            )
            Text(
                if (vm.isConfigured) "API key loaded from local.properties"
                else "AI_GATEWAY_API_KEY missing from local.properties — rebuild after adding it",
                style = MaterialTheme.typography.bodySmall,
                color = if (vm.isConfigured) Muted else Amber,
                modifier = Modifier.padding(top = 2.dp),
            )

            FilledTonalButton(
                onClick = vm::runTest,
                enabled = vm.isConfigured && !running,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                colors = ButtonDefaults.filledTonalButtonColors(containerColor = SynapseDim, contentColor = Synapse),
            ) {
                if (running) {
                    CircularProgressIndicator(Modifier.size(16.dp), color = Synapse, strokeWidth = 2.dp)
                } else {
                    Text("Test Jev")
                }
            }

            when (val t = test) {
                is AiGatewayViewModel.TestState.Passed -> ResultText("Success! ${t.summary}", Passed)
                is AiGatewayViewModel.TestState.Failed -> ResultText("Failed: ${t.message}", Alarm)
                else -> Unit
            }
        }
    }
}

@Composable
private fun ResultText(text: String, color: Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier.padding(top = 8.dp),
    )
}
