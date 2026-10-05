package dev.kortex.links.ui.create

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import dev.kortex.design.Alarm
import dev.kortex.design.Edge
import dev.kortex.design.Grotesk
import dev.kortex.design.Ink
import dev.kortex.design.KortexTheme
import dev.kortex.design.Mono
import dev.kortex.design.Muted
import dev.kortex.design.Panel
import dev.kortex.design.Synapse
import dev.kortex.design.Void
import dev.kortex.links.domain.model.AlreadySavedLink
import dev.kortex.links.domain.model.LinkAnalysis
import dev.kortex.links.domain.model.PageReadPhase
import dev.kortex.links.domain.model.linkDomain
import dev.kortex.links.ui.common.components.CandidateTagChip
import dev.kortex.links.ui.common.components.NewTagChip
import dev.kortex.links.ui.common.components.TagChip
import dev.kortex.mvi.ObserveEffects
import dev.kortex.mvi.ScopedViewModelStore

/**
 * The new-link form, with a ViewModel of its own for each opening. Keyed on the address as well as
 * the host's `key()`: when a new share replaces a half-filled form, the new store is fetched during
 * composition and the old one is cleared after, so a shared key would clear the new form's store.
 */
@Composable
fun CreateLinkRoute(onBack: () -> Unit, initialUrl: String, modifier: Modifier = Modifier) {
    ScopedViewModelStore(key = "create-link:$initialUrl") {
        CreateLink(onBack = onBack, initialUrl = initialUrl, modifier = modifier)
    }
}

/** Holds the fields' text, which never waits on a state round-trip, and hands everything else to the ViewModel. */
@Composable
private fun CreateLink(
    onBack: () -> Unit,
    initialUrl: String,
    modifier: Modifier = Modifier,
    viewModel: CreateLinkViewModel = hiltViewModel(),
) {
    BackHandler(onBack = onBack)
    val state by viewModel.state.collectAsStateWithLifecycle()
    var url by rememberSaveable { mutableStateOf(initialUrl) }
    var title by rememberSaveable { mutableStateOf("") }
    // Opening or closing the new-tag field starts it empty.
    var newTagName by rememberSaveable(state.addingTag) { mutableStateOf("") }

    // Registered after the screen-level handler, so while adding a tag back only closes the picker.
    BackHandler(enabled = state.addingTag) { viewModel.onIntent(CreateLinkIntent.CancelNewTag) }

    ObserveEffects(viewModel.effects) { effect ->
        when (effect) {
            CreateLinkEffect.Saved -> onBack()
        }
    }

    // A pre-filled (shared) or restored address is analysed at once; edits report themselves.
    LaunchedEffect(Unit) { viewModel.onIntent(CreateLinkIntent.UrlChanged(url)) }

    // Fill in the page's own title unless the user has already typed one. Keyed on the title and
    // its address only, so image progress doesn't refill a title the user cleared.
    LaunchedEffect(state.analysis.suggestedTitle, state.analysis.url) {
        val suggestedTitle = state.titleSuggestionFor(url)
        if (suggestedTitle != null && title.isBlank()) title = suggestedTitle
    }

    CreateLinkForm(
        state = state,
        url = url,
        onUrlChange = {
            url = it
            viewModel.onIntent(CreateLinkIntent.UrlChanged(it))
        },
        title = title,
        onTitleChange = { title = it },
        newTagName = newTagName,
        onNewTagNameChange = { newTagName = it },
        onIntent = viewModel::onIntent,
        onBack = onBack,
        modifier = modifier,
    )
}

/** Stateless: [url], [title] and [newTagName] are the fields' current text. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CreateLinkForm(
    state: CreateLinkState,
    url: String,
    onUrlChange: (String) -> Unit,
    title: String,
    onTitleChange: (String) -> Unit,
    newTagName: String,
    onNewTagNameChange: (String) -> Unit,
    onIntent: (CreateLinkIntent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val domain = remember(url) { linkDomain(url) }
    val alreadySaved = state.duplicateOf(url)
    val phase = state.phaseFor(url)

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
                // A tap while saving is ignored by the ViewModel; the button doesn't grey out for it.
                SaveLinkButton(
                    enabled = domain != null && alreadySaved == null,
                    onClick = { onIntent(CreateLinkIntent.Save(url, title)) },
                )
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
            AddressField(url = url, onUrlChange = onUrlChange, domain = domain, alreadySaved = alreadySaved)
            TitleField(title = title, onTitleChange = onTitleChange)
            TagsSection(
                orderedTags = state.orderedTags,
                suggestedTags = state.suggestedTags,
                candidateTags = state.candidateTags,
                phase = phase,
                selectedTags = state.selectedTags,
                onTagToggle = { onIntent(CreateLinkIntent.ToggleTag(it)) },
                isAddingTag = state.addingTag,
                newTagName = newTagName,
                onNewTagNameChange = onNewTagNameChange,
                onStartNewTag = { onIntent(CreateLinkIntent.StartNewTag) },
                onAddTag = { onIntent(CreateLinkIntent.AddTag(it)) },
            )
            AnimatedVisibility(visible = domain != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                LinkPreviewSection(
                    url = url.trim(),
                    domain = domain.orEmpty(),
                    title = title.trim(),
                    phase = phase,
                    image = state.imageFor(url),
                    selectedTags = state.selectedTags,
                    imageHidden = state.imageHidden,
                    onImageHiddenChange = { onIntent(CreateLinkIntent.SetImageHidden(it)) },
                    onRetryImage = { onIntent(CreateLinkIntent.RetryImage) },
                )
            }
        }
    }
}

// ── Fields ────────────────────────────────────────────────────────

@Composable
private fun AddressField(url: String, onUrlChange: (String) -> Unit, domain: String?, alreadySaved: AlreadySavedLink?) {
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
        val (hint, hintColor) = when {
            alreadySaved != null -> "Already saved as “${alreadySaved.title.ifBlank { "Untitled link" }}”" to Alarm
            domain != null -> "✓ $domain" to Synapse
            else -> "Paste a URL — the title fills in when available." to Muted
        }
        Text(
            text = hint,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 16.sp),
            color = hintColor,
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

/** [orderedTags]: every tag, suggestions first (see [CreateLinkState.orderedTags]). */
@OptIn(ExperimentalLayoutApi::class, ExperimentalSharedTransitionApi::class)
@Composable
private fun TagsSection(
    orderedTags: List<String>,
    suggestedTags: List<String>,
    candidateTags: List<String>,
    phase: PageReadPhase,
    selectedTags: List<String>,
    onTagToggle: (String) -> Unit,
    isAddingTag: Boolean,
    newTagName: String,
    onNewTagNameChange: (String) -> Unit,
    onStartNewTag: () -> Unit,
    onAddTag: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TagsLabelRow(isAddingTag = isAddingTag, phase = phase, hasSuggestions = suggestedTags.isNotEmpty())

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
                            sharedContentState = rememberSharedContentState("tag:$tag"),
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
private fun TagsLabelRow(isAddingTag: Boolean, phase: PageReadPhase, hasSuggestions: Boolean) {
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
                phase == PageReadPhase.ReadingPage -> TagsHint("Reading page…", Muted)
                phase == PageReadPhase.SuggestingTags -> TagsHint("Suggesting…", Muted)
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

// ── Previews ──────────────────────────────────────────────────────

private const val PREVIEW_URL = "https://curaahome.com/products/curaa-automatic-pepper-grinder"

@Composable
private fun CreateLinkFormPreview(state: CreateLinkState, url: String, title: String) {
    KortexTheme {
        CreateLinkForm(
            state = state, url = url, onUrlChange = {}, title = title, onTitleChange = {},
            newTagName = "", onNewTagNameChange = {}, onIntent = {}, onBack = {},
        )
    }
}

@Preview
@Composable
private fun CreateLinkEmptyPreview() {
    CreateLinkFormPreview(CreateLinkState(tags = listOf("sample", "ticket", "to buy")), url = "", title = "")
}

@Preview
@Composable
private fun CreateLinkFilledPreview() {
    CreateLinkFormPreview(
        CreateLinkState(
            tags = listOf("sample", "ticket", "to buy", "kitchen"),
            analysis = LinkAnalysis(PREVIEW_URL, suggestedTags = listOf("kitchen", "to buy"), phase = PageReadPhase.Done),
            selectedTags = listOf("to buy"),
        ),
        url = PREVIEW_URL,
        title = "Auto pepper grinder",
    )
}

@Preview
@Composable
private fun CreateLinkNewTagPreview() {
    CreateLinkFormPreview(
        CreateLinkState(
            tags = listOf("sample", "ticket", "to buy", "kitchen"),
            analysis = LinkAnalysis(
                PREVIEW_URL,
                suggestedTags = listOf("kitchen"),
                candidateTags = listOf("homeware", "grinder", "curaahome"),
                phase = PageReadPhase.Done,
            ),
            selectedTags = listOf("to buy"),
            addingTag = true,
        ),
        url = PREVIEW_URL,
        title = "Auto pepper grinder",
    )
}
