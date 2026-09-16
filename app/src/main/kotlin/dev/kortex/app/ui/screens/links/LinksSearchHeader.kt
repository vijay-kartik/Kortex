package dev.kortex.app.ui.screens.links

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import dev.kortex.app.R
import dev.kortex.app.ui.Edge
import dev.kortex.app.ui.EmphasizedAccelerate
import dev.kortex.app.ui.EmphasizedDecelerate
import dev.kortex.app.ui.Grotesk
import dev.kortex.app.ui.Ink
import dev.kortex.app.ui.Mono
import dev.kortex.app.ui.Muted
import dev.kortex.app.ui.Panel
import dev.kortex.app.ui.StandardEasing
import dev.kortex.app.ui.Synapse
import kotlin.math.roundToInt

/**
 * Count line + search pill. Focusing the pill expands it to full width (Figma: Search anim / Spec):
 *
 * 1. 0–150ms, emphasized-accelerate: pill's left edge travels left, border warms to Synapse 60%,
 *    the count line drops 18dp and dims as it tucks under the field, the list drops 12dp.
 * 2. 150–400ms, emphasized-decelerate: pill reaches full width with a Synapse border and icon,
 *    hint fades out as the cursor and ✕ fade in, the count line settles on its own row,
 *    the list settles 28dp down.
 *
 * ✕ clears the query and reverses in a single 300ms standard step. Back collapses but keeps
 * the query, so reopening puts the cursor after it.
 */
@Composable
internal fun LinksSearchHeader(
    linkCount: Int,
    tagCount: Int,
    query: String,
    onQueryChange: (String) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val progress = remember { Animatable(if (expanded) 1f else 0f) }
    LaunchedEffect(expanded) {
        when {
            !expanded -> progress.animateTo(0f, tween(CLOSE_MS, easing = StandardEasing))
            progress.value == 0f -> progress.animateTo(
                1f,
                keyframes {
                    durationMillis = EXPAND_MS
                    // A keyframe's easing applies to the segment that starts at it.
                    0f at 0 using EmphasizedAccelerate
                    HANDOFF at HANDOFF_MS using EmphasizedDecelerate
                },
            )
            // Reopened mid-close: finish from where it is instead of jumping back to rest.
            else -> progress.animateTo(
                1f,
                tween((EXPAND_MS * (1f - progress.value)).roundToInt(), easing = EmphasizedDecelerate),
            )
        }
    }
    val motion = SearchMotion(progress.value)

    val focusManager = LocalFocusManager.current
    BackHandler(enabled = expanded) {
        focusManager.clearFocus()
        onExpandedChange(false)
    }

    val textStyle = MaterialTheme.typography.labelLarge
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // The resting pill hugs its text; it only ever grows from this width.
    val restWidth = remember(query, textStyle, density) {
        val textWidth = textMeasurer.measure(query.ifEmpty { HINT }, textStyle, maxLines = 1).size.width
        with(density) { (PILL_CHROME_WIDTH + textWidth.toDp() + 1.dp).coerceAtMost(PILL_REST_MAX_WIDTH) }
    }

    Box(
        modifier
            .fillMaxWidth()
            // Growing the header, rather than translating the list, keeps scroll bounds honest.
            .height(HEADER_HEIGHT + motion.listShift),
    ) {
        Text(
            "${countLabel(linkCount, "LINK")} · ${countLabel(tagCount, "TAG")}",
            style = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 1.sp),
            color = Muted,
            modifier = Modifier
                .offset(y = META_REST_Y + motion.metaShift)
                .graphicsLayer { alpha = motion.metaAlpha },
        )
        SearchPill(
            query = query,
            onQueryChange = onQueryChange,
            motion = motion,
            expanded = expanded,
            onFocused = { onExpandedChange(true) },
            onClose = {
                onQueryChange("")
                focusManager.clearFocus()
                onExpandedChange(false)
            },
            onSearch = { focusManager.clearFocus() },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .layout { measurable, constraints ->
                    val restPx = restWidth.roundToPx()
                    val width = (restPx + (constraints.maxWidth - restPx) * progress.value).roundToInt()
                    val placeable = measurable.measure(Constraints.fixed(width, PILL_HEIGHT.roundToPx()))
                    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                },
        )
    }
}

@Composable
private fun SearchPill(
    query: String,
    onQueryChange: (String) -> Unit,
    motion: SearchMotion,
    expanded: Boolean,
    onFocused: () -> Unit,
    onClose: () -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(20.dp)
    val textStyle = MaterialTheme.typography.labelLarge
    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        textStyle = textStyle.copy(color = Ink),
        cursorBrush = SolidColor(Synapse.copy(alpha = motion.cursorAlpha)),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        modifier = modifier.onFocusChanged { if (it.isFocused) onFocused() },
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(shape)
                    .background(Panel)
                    .border(1.dp, motion.borderColor, shape)
                    // 1dp border + 12dp, as in Figma where the border sits outside the padding.
                    .padding(start = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painterResource(R.drawable.ic_search),
                    contentDescription = null,
                    tint = motion.iconTint,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text(
                            HINT,
                            style = textStyle,
                            color = Muted,
                            maxLines = 1,
                            modifier = Modifier.graphicsLayer { alpha = motion.hintAlpha },
                        )
                    }
                    innerTextField()
                }
                // Resting end padding that widens into the ✕ slot as the pill expands.
                Box(
                    Modifier
                        .width(motion.trailingWidth)
                        .fillMaxHeight()
                        .clickable(enabled = expanded, onClickLabel = "Close search", onClick = onClose)
                        .semantics { contentDescription = "Close search" },
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        "✕",
                        style = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Medium, fontSize = 16.sp),
                        color = Muted,
                        maxLines = 1,
                        modifier = Modifier
                            .padding(start = CLOSE_GLYPH_INSET)
                            .graphicsLayer { alpha = motion.closeAlpha }
                            .clearAndSetSemantics {},
                    )
                }
            }
        },
    )
}

/** Every animated value, derived from one 0 → 1 progress where [HANDOFF] marks the 150ms phase boundary. */
private class SearchMotion(progress: Float) {
    private val inPhase1 = progress <= HANDOFF
    private val phase1 = (progress / HANDOFF).coerceIn(0f, 1f)
    private val phase2 = ((progress - HANDOFF) / (1f - HANDOFF)).coerceIn(0f, 1f)

    val borderColor = if (inPhase1) lerp(Edge, SynapseWarm, phase1) else lerp(SynapseWarm, Synapse, phase2)
    val iconTint = lerp(Muted, Synapse, phase2)
    val hintAlpha = if (inPhase1) mix(1f, 0.4f, phase1) else mix(0.4f, 0f, phase2)
    val cursorAlpha = phase2
    val closeAlpha = phase2
    val trailingWidth = lerp(TRAILING_REST, TRAILING_EXPANDED, phase2)
    val metaShift = if (inPhase1) lerp(0.dp, 18.dp, phase1) else lerp(18.dp, 40.dp, phase2)
    val metaAlpha = if (inPhase1) mix(1f, 0.35f, phase1) else mix(0.35f, 1f, phase2)
    val listShift = if (inPhase1) lerp(0.dp, 12.dp, phase1) else lerp(12.dp, 28.dp, phase2)
}

private fun mix(start: Float, stop: Float, fraction: Float) = start + (stop - start) * fraction

private fun countLabel(count: Int, noun: String) = "$count ${if (count == 1) noun else noun + "S"}"

private const val HINT = "Search"
private const val EXPAND_MS = 400
private const val HANDOFF_MS = 150
private const val HANDOFF = 0.5f
private const val CLOSE_MS = 300

private val SynapseWarm = Synapse.copy(alpha = 0.6f)

private val PILL_HEIGHT = 40.dp
private val HEADER_HEIGHT = 44.dp
private val META_REST_Y = 12.dp
private val PILL_REST_MAX_WIDTH = 220.dp
private val TRAILING_REST = 17.dp
private val TRAILING_EXPANDED = 48.dp
// Puts ✕ 30dp in from the pill's right edge, as in the expanded frame.
private val CLOSE_GLYPH_INSET = 18.dp
// 13dp start + 16dp icon + 8dp gap + 17dp end.
private val PILL_CHROME_WIDTH = 54.dp
