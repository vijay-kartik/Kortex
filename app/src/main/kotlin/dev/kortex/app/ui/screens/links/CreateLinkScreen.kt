package dev.kortex.app.ui.screens.links

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kortex.app.ui.Edge
import dev.kortex.app.ui.Grotesk
import dev.kortex.app.ui.Ink
import dev.kortex.app.ui.KortexTheme
import dev.kortex.app.ui.Mono
import dev.kortex.app.ui.Muted
import dev.kortex.app.ui.Panel
import dev.kortex.app.ui.Synapse
import dev.kortex.app.ui.Void
import dev.kortex.app.ui.components.NewTagChip
import dev.kortex.app.ui.components.TagChip

@Composable
fun CreateLinkScreen(
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier,
    tags: List<String> = emptyList(),
    onNewTag: () -> Unit = {},
    onSave: (url: String, title: String) -> Unit = { _, _ -> },
) {
    BackHandler(onBack = onBack)
    var url by rememberSaveable { mutableStateOf("") }
    var title by rememberSaveable { mutableStateOf("") }

    CreateLinkContent(
        url = url,
        onUrlChange = { url = it },
        title = title,
        onTitleChange = { title = it },
        tags = tags,
        onBack = onBack,
        onNewTag = onNewTag,
        onSave = { onSave(url.trim(), title.trim()) },
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateLinkContent(
    url: String,
    onUrlChange: (String) -> Unit,
    title: String,
    onTitleChange: (String) -> Unit,
    tags: List<String>,
    onBack: () -> Unit,
    onNewTag: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val domain = remember(url) { linkDomain(url) }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "New link",
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.Bold,
                            lineHeight = 28.sp,
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Text("←", fontSize = 24.sp, color = Muted)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        bottomBar = {
            Box(
                Modifier
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp)
            ) {
                SaveLinkButton(enabled = domain != null, onClick = onSave)
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            AddressField(url = url, onUrlChange = onUrlChange, domain = domain)
            TitleField(title = title, onTitleChange = onTitleChange)
            TagsSection(tags = tags, onNewTag = onNewTag)
        }
    }
}

// ── Fields ────────────────────────────────────────────────────────

@Composable
private fun AddressField(url: String, onUrlChange: (String) -> Unit, domain: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FieldLabel("ADDRESS")
        FieldBox(
            value = url,
            onValueChange = onUrlChange,
            placeholder = "https://",
            textStyle = TextStyle(fontFamily = Mono, fontSize = 14.sp, lineHeight = 23.sp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
            inlinePlaceholder = true,
        )
        Text(
            text = if (domain != null) "✓ $domain" else "Paste a URL — the title fills in when available.",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 16.sp),
            color = if (domain != null) Synapse else Muted,
        )
    }
}

@Composable
private fun TitleField(title: String, onTitleChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FieldLabel("TITLE")
        FieldBox(
            value = title,
            onValueChange = onTitleChange,
            placeholder = "Untitled link",
            textStyle = TextStyle(fontFamily = Grotesk, fontSize = 17.sp, lineHeight = 23.sp, letterSpacing = 0.5.sp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 1.5.sp),
        color = Muted,
    )
}

/**
 * Single-line input: raised panel at rest, void + synapse outline while focused.
 * [inlinePlaceholder] keeps the hint beside the cursor (the "https://" prefix look)
 * instead of underneath it.
 */
@Composable
private fun FieldBox(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    textStyle: TextStyle,
    keyboardOptions: KeyboardOptions,
    inlinePlaceholder: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(12.dp)

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = textStyle.copy(color = Ink),
        cursorBrush = SolidColor(Synapse),
        keyboardOptions = keyboardOptions,
        interactionSource = interactionSource,
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(if (focused) Void else Panel)
                    .border(1.dp, if (focused) Synapse else Edge, shape)
                    .padding(horizontal = 16.dp, vertical = 15.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (value.isEmpty() && inlinePlaceholder) {
                    Text(placeholder, style = textStyle, color = Muted)
                    Box(Modifier.weight(1f)) { innerTextField() }
                } else {
                    Box(Modifier.weight(1f)) {
                        if (value.isEmpty()) {
                            Text(placeholder, style = textStyle, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        innerTextField()
                    }
                }
            }
        },
    )
}

// ── Tags ──────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagsSection(tags: List<String>, onNewTag: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FieldLabel("TAGS")
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            tags.forEach { TagChip(it) }
            NewTagChip(onClick = onNewTag)
        }
    }
}



// ── Save ──────────────────────────────────────────────────────────

@Composable
private fun SaveLinkButton(enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(shape)
            .background(if (enabled) Synapse else Panel)
            .border(1.dp, if (enabled) Synapse else Edge, shape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "Save link",
            style = MaterialTheme.typography.labelLarge.copy(fontSize = 16.sp),
            color = if (enabled) Void else Muted,
        )
    }
}

/** Host of a well-formed http(s) URL without "www.", or null while the address isn't usable yet. */
private fun linkDomain(url: String): String? {
    val uri = Uri.parse(url.trim())
    if (uri.scheme !in setOf("http", "https")) return null
    val host = uri.host?.takeIf { '.' in it && !it.startsWith('.') && !it.endsWith('.') } ?: return null
    return host.removePrefix("www.")
}

// ── Previews ──────────────────────────────────────────────────────

@Preview
@Composable
private fun CreateLinkEmptyPreview() {
    KortexTheme {
        CreateLinkContent(
            url = "", onUrlChange = {}, title = "", onTitleChange = {},
            tags = listOf("sample", "ticket", "to buy"),
            onBack = {}, onNewTag = {}, onSave = {},
        )
    }
}

@Preview
@Composable
private fun CreateLinkFilledPreview() {
    KortexTheme {
        CreateLinkContent(
            url = "https://curaahome.com/products/curaa-automatic-pepper-grinder",
            onUrlChange = {}, title = "", onTitleChange = {},
            tags = listOf("sample", "ticket", "to buy"),
            onBack = {}, onNewTag = {}, onSave = {},
        )
    }
}
