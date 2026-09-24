package dev.kortex.design

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import dev.kortex.design.anim.EmphasizedAccelerate
import dev.kortex.design.anim.EmphasizedDecelerate
import dev.kortex.design.anim.StandardEasing
import kotlin.math.roundToInt

/**
 * A screen's search: the top bar draws the field, the screen below filters by [query] and hides its
 * + while [expanded]. Hold one per searchable screen with [rememberTopBarSearch].
 */
@Stable
class TopBarSearch(query: String = "", expanded: Boolean = false, searchable: Boolean = false) {
    var query by mutableStateOf(query)
    var expanded by mutableStateOf(expanded)

    /** Whether the screen has anything to search. The bar only offers search while this is true. */
    var searchable by mutableStateOf(searchable)
        private set

    /** Called by the screen as its content loads and changes. With nothing left, the field closes and the query goes. */
    fun updateSearchable(hasItems: Boolean) {
        searchable = hasItems
        if (!hasItems) {
            expanded = false
            query = ""
        }
    }

    companion object {
        val Saver: Saver<TopBarSearch, Any> = listSaver(
            save = { listOf(it.query, it.expanded, it.searchable) },
            restore = { TopBarSearch(query = it[0] as String, expanded = it[1] as Boolean, searchable = it[2] as Boolean) },
        )
    }
}

@Composable
fun rememberTopBarSearch(): TopBarSearch = rememberSaveable(saver = TopBarSearch.Saver) { TopBarSearch() }

/**
 * Home top bar (Figma: Home Navigation 01–06): menu, centred title, and on the right either the
 * screen's [search] or its [action]. 56dp tall; buttons are 40dp circles in 48dp targets, 20dp in.
 *
 * Search grows out of its circle to the full bar width, 400ms (timings from Links · Search anim / Spec):
 *
 * 1. 0–150ms, emphasized-accelerate: title and menu fade, the circle's left edge travels left,
 *    the border warms to Synapse 60%.
 * 2. 150–400ms, emphasized-decelerate: full width with a Synapse border and icon; hint, cursor and ✕
 *    fade in.
 *
 * ✕ clears the query and reverses in one 300ms standard step. Back collapses but keeps the query;
 * the icon stays Synapse so the filtered list still reads as searched.
 */
@Composable
fun KortexTopBar(
    title: String,
    onMenuClick: () -> Unit,
    modifier: Modifier = Modifier,
    search: TopBarSearch? = null,
    searchHint: String = "Search",
    /** Draws the hairline once content has scrolled under the bar. */
    scrolledUnder: Boolean = false,
    action: @Composable () -> Unit = {},
) {
    val expanded = search?.expanded == true
    val progress = remember(search) { Animatable(if (expanded) 1f else 0f) }
    LaunchedEffect(search, expanded) {
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
    val divider by animateColorAsState(
        targetValue = if (scrolledUnder) Edge else Edge.copy(alpha = 0f),
        animationSpec = tween(DIVIDER_MS, easing = StandardEasing),
        label = "divider",
    )

    Box(
        modifier
            .fillMaxWidth()
            .background(Void)
            .windowInsetsPadding(WindowInsets.statusBars)
            .height(BAR_HEIGHT)
            .drawBehind {
                val stroke = 1.dp.toPx()
                drawRect(divider, topLeft = Offset(0f, size.height - stroke), size = Size(size.width, stroke))
            },
    ) {
        CircleButton(
            icon = R.drawable.ic_menu,
            contentDescription = "Open menu",
            onClick = onMenuClick,
            enabled = !expanded,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = TARGET_INSET)
                .graphicsLayer { alpha = 1f - phase1(progress.value) },
        )
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = Ink,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = TITLE_INSET)
                .graphicsLayer {
                    val p = phase1(progress.value)
                    alpha = 1f - p
                    translationY = -TITLE_LIFT.toPx() * p
                }
                .semantics { heading() },
        )
        if (search != null) {
            // Only once there's something to search; an empty screen shows no search at all.
            AnimatedVisibility(
                visible = search.searchable,
                enter = fadeIn(tween(SEARCH_FADE_MS, easing = StandardEasing)),
                exit = fadeOut(tween(SEARCH_FADE_MS, easing = StandardEasing)),
                modifier = Modifier.align(Alignment.CenterEnd),
                label = "search",
            ) {
                SearchField(search, searchHint, progress)
            }
        } else {
            Box(Modifier.align(Alignment.CenterEnd).padding(end = TARGET_INSET)) { action() }
        }
    }
}

/** The top bar's button: a 40dp Panel circle in a 48dp touch target. */
@Composable
fun CircleButton(
    @DrawableRes icon: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = Ink,
) {
    Box(
        modifier
            .size(TARGET)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(CIRCLE).background(Panel, CircleShape), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun SearchField(
    search: TopBarSearch,
    hint: String,
    progress: Animatable<Float, AnimationVector1D>,
    modifier: Modifier = Modifier,
) {
    val expanded = search.expanded
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    BackHandler(enabled = expanded) {
        focusManager.clearFocus()
        search.expanded = false
    }
    LaunchedEffect(expanded) {
        if (expanded) {
            // The field is only enabled from this composition on; focus it once it's laid out.
            withFrameNanos { }
            focusRequester.requestFocus()
        }
    }

    val p = progress.value
    val p1 = phase1(p)
    val p2 = phase2(p)
    val border = if (p <= HANDOFF) lerp(Color.Transparent, SynapseWarm, p1) else lerp(SynapseWarm, Synapse, p2)
    val restTint = if (search.query.isBlank()) Ink else Synapse
    val iconTint = lerp(restTint, Synapse, p2)
    val shape = RoundedCornerShape(CIRCLE / 2)

    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = TARGET_INSET)
            // Grows from the resting 48dp target to the bar's full width, anchored at the end.
            .layout { measurable, constraints ->
                val rest = TARGET.roundToPx()
                val width = (rest + (constraints.maxWidth - rest) * p).roundToInt()
                val placeable = measurable.measure(Constraints.fixed(width, TARGET.roundToPx()))
                layout(constraints.maxWidth, placeable.height) { placeable.place(constraints.maxWidth - width, 0) }
            }
            .clip(CircleShape)
            .clickable(enabled = !expanded, role = Role.Button, onClickLabel = "Search") { search.expanded = true }
            .semantics { if (!expanded) contentDescription = hint }
            .padding((TARGET - CIRCLE) / 2),
    ) {
        Row(
            Modifier
                .fillMaxSize()
                .clip(shape)
                .background(Panel)
                .border(1.dp, border, shape)
                .padding(start = ICON_INSET),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.ic_search), contentDescription = null, tint = iconTint, modifier = Modifier.size(ICON))
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f).graphicsLayer { alpha = p2 }) {
                if (search.query.isEmpty()) {
                    Text(hint, style = MaterialTheme.typography.labelLarge, color = Muted, maxLines = 1)
                }
                BasicTextField(
                    value = search.query,
                    onValueChange = { search.query = it },
                    enabled = expanded,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.labelLarge.copy(color = Ink),
                    cursorBrush = SolidColor(Synapse),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                )
            }
            // Widens into the ✕ slot as the pill opens; nothing to tap while it's a circle.
            Box(
                Modifier
                    .width(lerp(0.dp, CIRCLE, p2))
                    .fillMaxHeight()
                    .clickable(enabled = expanded, onClickLabel = "Close search") {
                        search.query = ""
                        focusManager.clearFocus()
                        search.expanded = false
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(R.drawable.ic_close),
                    contentDescription = if (expanded) "Close search" else null,
                    tint = Muted,
                    modifier = Modifier.size(ICON).graphicsLayer { alpha = p2 },
                )
            }
        }
    }
}

/** 0 → 1 across the first 150ms; [HANDOFF] is where the progress stands at that boundary. */
private fun phase1(progress: Float) = (progress / HANDOFF).coerceIn(0f, 1f)

private fun phase2(progress: Float) = ((progress - HANDOFF) / (1f - HANDOFF)).coerceIn(0f, 1f)

private const val EXPAND_MS = 400
private const val HANDOFF_MS = 150
private const val HANDOFF = 0.5f
private const val CLOSE_MS = 300
private const val DIVIDER_MS = 200
private const val SEARCH_FADE_MS = 150

private val SynapseWarm = Synapse.copy(alpha = 0.6f)

private val BAR_HEIGHT = 56.dp
private val TARGET = 48.dp
private val CIRCLE = 40.dp
// A 48dp target 16dp in puts its 40dp circle on the 20dp margin.
private val TARGET_INSET = 16.dp
// Clears both buttons, so a long title ellipsizes instead of running under them.
private val TITLE_INSET = 72.dp
private val TITLE_LIFT = 4.dp
private val ICON = 18.dp
// Centres the icon in the resting 40dp circle: (40 - 18) / 2.
private val ICON_INSET = 11.dp
