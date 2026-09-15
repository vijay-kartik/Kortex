package dev.kortex.app.ui.screens.links

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kortex.app.R
import dev.kortex.app.ui.Muted
import dev.kortex.app.ui.Synapse
import dev.kortex.app.ui.SynapseDim
import dev.kortex.app.ui.Void


@Preview
@Composable
fun LinksScreen(modifier: Modifier = Modifier, viewModel: LinksViewModel = hiltViewModel()) {
    val uiState by viewModel.linksScreenUiState.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                },
                containerColor = Synapse,
                contentColor = Void,
            ) {
                Icon(Icons.Default.Add, contentDescription = "New Link")
            }
        }
    ) { innerPadding ->
        Box(
            modifier = modifier.padding(innerPadding).fillMaxSize().imePadding(),
            contentAlignment = Alignment.Center
        ) {
            if (uiState == LinksScreenUiState.EmptyLinksUiState)
                EmptyLinksScreen()
            else
                LinksWithSearchScreen()
        }
    }
}

@Composable
fun EmptyLinksScreen() {
    Column(
        modifier = Modifier
            .padding(horizontal = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(56.dp)
                .background(SynapseDim, RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(R.drawable.ic_link),
                contentDescription = null,
                tint = Synapse,
                modifier = Modifier.size(28.dp)
            )
        }
        Text("No links yet", style = MaterialTheme.typography.titleMedium)
        Text(
            "Tap + to create your first link.",
            style = MaterialTheme.typography.bodySmall,
            color = Muted,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun LinksWithSearchScreen() {
    Column(modifier = Modifier
        .padding(horizontal = 14.dp)) {

    }
}