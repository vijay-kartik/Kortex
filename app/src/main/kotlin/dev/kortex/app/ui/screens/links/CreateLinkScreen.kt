package dev.kortex.app.ui.screens.links

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.text.KeyboardActions
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kortex.app.ui.Edge
import dev.kortex.app.ui.Grotesk
import dev.kortex.app.ui.Ink
import dev.kortex.app.ui.KortexTheme
import dev.kortex.app.ui.Mono
import dev.kortex.app.ui.Muted
import dev.kortex.app.ui.Panel
import dev.kortex.app.ui.Synapse
import dev.kortex.app.ui.Void
import dev.kortex.app.ui.components.CandidateTagChip
import dev.kortex.app.ui.components.NewTagChip
import dev.kortex.app.ui.components.TagChip

@Composable
fun CreateLinkScreen(
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier,
    initialUrl: String = "",
    viewModel: CreateLinkViewModel = hiltViewModel(),
) {
    BackHandler(onBack = onBack)
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var url by rememberSaveable { mutableStateOf(initialUrl) }
    var title by rememberSaveable { mutableStateOf("") }
    var selectedTags by rememberSaveable { mutableStateOf(listOf<String>()) }
    var isAddingTag by rememberSaveable { mutableStateOf(false) }
    var newTagName by rememberSaveable { mutableStateOf("") }

    fun closeNewTag() {
        newTagName = ""
        isAddingTag = false
    }

    // Registered after the screen-level handler, so while adding a tag back only closes the picker.
    BackHandler(enabled = isAddingTag, onBack = ::closeNewTag)

    // Keeps analysis in step with the field, including a pre-filled address and restored state.
    LaunchedEffect(url) { viewModel.onUrlChange(url) }

    // Fill in the page's own title unless the user has already typed one. The address check keeps a
    // title left over from a previous form (e.g. before a link was shared in) out of this one.
    LaunchedEffect(uiState.suggestedTitle, uiState.analyzedUrl) {
        val suggestedTitle = uiState.suggestedTitle
        if (suggestedTitle != null && title.isBlank() && uiState.analyzedUrl == url.trim()) title = suggestedTitle
    }

    CreateLinkContent(
        url = url,
        onUrlChange = { url = it },
        title = title,
        onTitleChange = { title = it },
        tags = uiState.tags,
        suggestedTags = uiState.suggestedTags,
        candidateTags = uiState.candidateTags,
        isAnalyzing = uiState.isAnalyzing,
        selectedTags = selectedTags,
        onTagToggle = { tag -> selectedTags = if (tag in selectedTags) selectedTags - tag else selectedTags + tag },
        isAddingTag = isAddingTag,
        newTagName = newTagName,
        onNewTagNameChange = { newTagName = it },
        onStartNewTag = { isAddingTag = true },
        onAddTag = { name ->
            val trimmed = name.trim()
            if (trimmed.isNotEmpty()) {
                // Reuse the stored spelling so typing an existing tag in another case still selects its chip.
                val tag = uiState.tags.firstOrNull { it.equals(trimmed, ignoreCase = true) }
                    ?: trimmed.also(viewModel::createTag)
                if (tag !in selectedTags) selectedTags = selectedTags + tag
            }
            closeNewTag()
        },
        onBack = onBack,
        onSave = { viewModel.save(url.trim(), title.trim(), selectedTags, onSaved = onBack) },
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
    suggestedTags: List<String>,
    candidateTags: List<String>,
    isAnalyzing: Boolean,
    selectedTags: List<String>,
    onTagToggle: (String) -> Unit,
    isAddingTag: Boolean,
    newTagName: String,
    onNewTagNameChange: (String) -> Unit,
    onStartNewTag: () -> Unit,
    onAddTag: (String) -> Unit,
    onBack: () -> Unit,
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
            TagsSection(
                tags = tags,
                suggestedTags = suggestedTags,
                candidateTags = candidateTags,
                isAnalyzing = isAnalyzing,
                selectedTags = selectedTags,
                onTagToggle = onTagToggle,
                isAddingTag = isAddingTag,
                newTagName = newTagName,
                onNewTagNameChange = onNewTagNameChange,
                onStartNewTag = onStartNewTag,
                onAddTag = onAddTag,
            )
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

@OptIn(ExperimentalLayoutApi::class, ExperimentalSharedTransitionApi::class)
@Composable
private fun TagsSection(
    tags: List<String>,
    suggestedTags: List<String>,
    candidateTags: List<String>,
    isAnalyzing: Boolean,
    selectedTags: List<String>,
    onTagToggle: (String) -> Unit,
    isAddingTag: Boolean,
    newTagName: String,
    onNewTagNameChange: (String) -> Unit,
    onStartNewTag: () -> Unit,
    onAddTag: (String) -> Unit,
) {
    // Suggestions lead, best match first; the rest keep their stored order.
    val orderedTags = remember(tags, suggestedTags) {
        suggestedTags.filter { it in tags } + tags.filterNot { it in suggestedTags }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TagsLabelRow(isAddingTag = isAddingTag, isAnalyzing = isAnalyzing, hasSuggestions = suggestedTags.isNotEmpty())

        // One surface changing shape: "+ new tag" grows into the name field while each chip
        // glides to its row under YOUR TAGS; everything without a counterpart cross-fades.
        SharedTransitionLayout {
            AnimatedContent(
                targetState = isAddingTag,
                transitionSpec = {
                    fadeIn(tween(durationMillis = 220, delayMillis = 90)) togetherWith
                        fadeOut(tween(durationMillis = 90)) using SizeTransform(clip = false)
                },
                label = "tags mode",
            ) { adding ->
                val newTagBounds = Modifier.sharedBounds(
                    sharedContentState = rememberSharedContentState(NEW_TAG_KEY),
                    animatedVisibilityScope = this,
                    resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
                )
                val tagChip: @Composable (String) -> Unit = { tag ->
                    TagChip(
                        text = tag,
                        selected = tag in selectedTags,
                        suggested = tag in suggestedTags,
                        onSelectedChange = { onTagToggle(tag) },
                        modifier = Modifier.sharedElement(
                            state = rememberSharedContentState("tag:$tag"),
                            animatedVisibilityScope = this,
                        ),
                    )
                }

                if (adding) {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        NewTagField(
                            value = newTagName,
                            onValueChange = onNewTagNameChange,
                            onDone = { onAddTag(newTagName) },
                            modifier = newTagBounds,
                        )
                        if (candidateTags.isNotEmpty()) {
                            TagGroupLabel("FROM THIS PAGE")
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                candidateTags.forEach { CandidateTagChip(it, onClick = { onAddTag(it) }) }
                            }
                        }
                        if (orderedTags.isNotEmpty()) {
                            TagGroupLabel("YOUR TAGS")
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                orderedTags.forEach { tagChip(it) }
                            }
                        }
                    }
                } else {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        orderedTags.forEach { tagChip(it) }
                        NewTagChip(onClick = onStartNewTag, modifier = newTagBounds)
                    }
                }
            }
        }
    }
}

@Composable
private fun TagsLabelRow(isAddingTag: Boolean, isAnalyzing: Boolean, hasSuggestions: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        FieldLabel("TAGS")
        AnimatedVisibility(visible = isAddingTag) {
            Text(
                "New tag",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp),
                color = Synapse,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
        Box(Modifier.weight(1f))
        AnimatedVisibility(visible = !isAddingTag, enter = fadeIn(), exit = fadeOut()) {
            when {
                isAnalyzing -> TagsHint("Reading page…", Muted)
                hasSuggestions -> TagsHint("Suggested from page", Synapse)
            }
        }
    }
}

@Composable
private fun TagsHint(text: String, color: Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 16.sp),
        color = color,
    )
}

@Composable
private fun TagGroupLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, letterSpacing = 1.2.sp),
        color = Muted,
    )
}

/** Name input the "+ new tag" chip opens into. Takes focus on arrival so the keyboard rises with the morph. */
@Composable
private fun NewTagField(
    value: String,
    onValueChange: (String) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    val shape = RoundedCornerShape(10.dp)
    val textStyle = TextStyle(fontFamily = Grotesk, fontSize = 15.sp, letterSpacing = 0.1.sp)

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = textStyle.copy(color = Ink),
        cursorBrush = SolidColor(Synapse),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        // Done with an empty name just closes the picker.
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(focusRequester),
        decorationBox = { innerTextField ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(Panel)
                    .border(1.dp, Synapse, shape)
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                if (value.isEmpty()) {
                    Text("tag name", style = textStyle, color = Muted)
                }
                innerTextField()
            }
        },
    )
}

private const val NEW_TAG_KEY = "new-tag"

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
internal fun linkDomain(url: String): String? {
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
            tags = listOf("sample", "ticket", "to buy"), suggestedTags = emptyList(), candidateTags = emptyList(),
            isAnalyzing = false, selectedTags = emptyList(), onTagToggle = {},
            isAddingTag = false, newTagName = "", onNewTagNameChange = {}, onStartNewTag = {}, onAddTag = {},
            onBack = {}, onSave = {},
        )
    }
}

@Preview
@Composable
private fun CreateLinkFilledPreview() {
    KortexTheme {
        CreateLinkContent(
            url = "https://curaahome.com/products/curaa-automatic-pepper-grinder",
            onUrlChange = {}, title = "Auto pepper grinder", onTitleChange = {},
            tags = listOf("sample", "ticket", "to buy", "kitchen"), suggestedTags = listOf("kitchen", "to buy"),
            candidateTags = emptyList(), isAnalyzing = false, selectedTags = listOf("to buy"), onTagToggle = {},
            isAddingTag = false, newTagName = "", onNewTagNameChange = {}, onStartNewTag = {}, onAddTag = {},
            onBack = {}, onSave = {},
        )
    }
}

@Preview
@Composable
private fun CreateLinkNewTagPreview() {
    KortexTheme {
        CreateLinkContent(
            url = "https://curaahome.com/products/curaa-automatic-pepper-grinder",
            onUrlChange = {}, title = "Auto pepper grinder", onTitleChange = {},
            tags = listOf("sample", "ticket", "to buy", "kitchen"), suggestedTags = listOf("kitchen"),
            candidateTags = listOf("homeware", "grinder", "curaahome"), isAnalyzing = false,
            selectedTags = listOf("to buy"), onTagToggle = {},
            isAddingTag = true, newTagName = "", onNewTagNameChange = {}, onStartNewTag = {}, onAddTag = {},
            onBack = {}, onSave = {},
        )
    }
}
