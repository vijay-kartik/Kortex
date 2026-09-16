package dev.kortex.app.ui.appbar

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kortex.app.ui.Edge
import dev.kortex.app.ui.EmphasizedAccelerate
import dev.kortex.app.ui.EmphasizedDecelerate
import dev.kortex.app.ui.Ink
import dev.kortex.app.ui.Mono
import dev.kortex.app.ui.Muted
import dev.kortex.app.ui.Panel
import dev.kortex.app.ui.StandardEasing
import dev.kortex.app.ui.Synapse
import dev.kortex.app.ui.SynapseDim
import dev.kortex.app.ui.screens.home.KortexTab
import dev.kortex.app.ui.screens.home.TabCategory

/**
 * Category tab strip (Figma: Tabs / Spec), 420ms to expand:
 *
 * 1. 0–120ms, emphasized-accelerate: the Agent chip fades, its leaves lift off the chip's own
 *    left edge, the My Info group starts travelling right.
 * 2. 120–420ms, emphasized-decelerate: leaves settle into their slots, My Info right-aligns.
 *
 * Folding back is a single 360ms standard-easing reverse. Both groups always occupy the layout,
 * so only `translationX` and `alpha` change — nothing re-measures mid-animation.
 */
@Composable
fun CategoryTabBar(
    selected: KortexTab,
    expanded: TabCategory,
    onboarding: Boolean,
    onCategorySelected: (TabCategory) -> Unit,
    onTabSelected: (KortexTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val agentTabs = remember { KortexTab.of(TabCategory.Agent) }
    val agentExpanded = expanded == TabCategory.Agent
    // My Info shows as a category chip while it is collapsed — that is, whenever Agent is open,
    // and during onboarding, when it has not yet been unfolded into Links for the first time.
    val myInfoAsChip = agentExpanded || onboarding

    val progress = remember { Animatable(if (agentExpanded) 1f else 0f) }
    val chipCrossfade = remember { Animatable(if (myInfoAsChip) 1f else 0f) }

    LaunchedEffect(agentExpanded) {
        if (agentExpanded) {
            progress.animateTo(
                1f,
                keyframes {
                    durationMillis = EXPAND_MS
                    // A keyframe's easing applies to the segment starting at it.
                    0f at 0 using EmphasizedAccelerate
                    PHASE_1_SHARE at PHASE_1_MS using EmphasizedDecelerate
                },
            )
        } else {
            progress.animateTo(0f, tween(COLLAPSE_MS, easing = StandardEasing))
        }
    }
    LaunchedEffect(myInfoAsChip) {
        chipCrossfade.animateTo(if (myInfoAsChip) 1f else 0f, tween(CROSSFADE_MS, easing = StandardEasing))
    }

    Layout(
        modifier = modifier
            .fillMaxWidth()
            .height(BarHeight)
            .drawBehind {
                val stroke = 1.dp.toPx()
                drawRect(
                    color = Edge,
                    topLeft = Offset(0f, size.height - stroke),
                    size = Size(size.width, stroke),
                )
            },
        content = {
            CategoryChip(
                label = TabCategory.Agent.label,
                badge = agentTabs.size.toString(),
                selected = false,
                enabled = !agentExpanded,
                onClick = { onCategorySelected(TabCategory.Agent) },
            )
            agentTabs.forEach { tab ->
                LeafTab(
                    label = tab.label,
                    selected = agentExpanded && selected == tab,
                    enabled = agentExpanded,
                    onClick = { onTabSelected(tab) },
                )
            }
            CategoryChip(
                label = TabCategory.MyInfo.label,
                badge = null,
                selected = onboarding,
                enabled = myInfoAsChip,
                onClick = { onCategorySelected(TabCategory.MyInfo) },
            )
            LeafTab(
                label = KortexTab.Links.label,
                selected = !myInfoAsChip && selected == KortexTab.Links,
                enabled = !myInfoAsChip,
                onClick = { onTabSelected(KortexTab.Links) },
            )
        },
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(Constraints()) }
        val agent = placeables.first()
        val leaves = placeables.subList(1, 1 + agentTabs.size)
        val info = placeables[placeables.lastIndex - 1]
        val links = placeables.last()

        val pad = HorizontalPadding.roundToPx()
        val gap = Gap.roundToPx()
        val barWidth = constraints.maxWidth
        val barHeight = BarHeight.roundToPx()

        // Collapsed: Agent chip, then whichever My Info face is showing, both hard left.
        val collapsedSecondX = pad + agent.width + gap
        // Expanded: leaves run from the left edge, the My Info chip right-aligns.
        var cursor = pad
        val leafX = leaves.map { leaf ->
            val x = cursor
            cursor += leaf.width + gap
            x
        }
        val leavesEnd = cursor - gap
        // On a 412dp screen the leaves end well clear of the right-aligned chip. Narrower
        // screens run out of room — clamping keeps the two groups from overlapping, but the
        // real fix is the open decision in Tabs / Spec: cap the group or let it scroll.
        fun rightSlot(p: Placeable) = maxOf(barWidth - pad - p.width, leavesEnd + gap)

        fun centreY(p: Placeable) = (barHeight - p.height) / 2

        // Hidden items stay in the layout for the animation, so they still sit under the
        // pointer. Z-order, not alpha, decides who gets the tap: whichever face of a group is
        // currently live is raised above the one it replaced.
        val front = 1f
        val back = 0f

        layout(barWidth, barHeight) {
            // Placing at the collapsed position and moving with translationX keeps the whole
            // animation in the draw phase; reading progress here would relayout every frame.
            agent.placeWithLayer(pad, centreY(agent), if (agentExpanded) back else front) {
                alpha = (1f - progress.value * AGENT_FADE_RATE).coerceIn(0f, 1f)
            }
            leaves.forEachIndexed { i, leaf ->
                val travel = (leafX[i] - pad).toFloat()
                leaf.placeWithLayer(pad, centreY(leaf), if (agentExpanded) front else back) {
                    translationX = travel * progress.value
                    alpha = (progress.value * LEAF_FADE_RATE).coerceIn(0f, 1f)
                }
            }
            val infoTravel = (rightSlot(info) - collapsedSecondX).toFloat()
            info.placeWithLayer(collapsedSecondX, centreY(info), if (myInfoAsChip) front else back) {
                translationX = infoTravel * progress.value
                alpha = chipCrossfade.value
            }
            val linksTravel = (rightSlot(links) - collapsedSecondX).toFloat()
            links.placeWithLayer(collapsedSecondX, centreY(links), if (myInfoAsChip) back else front) {
                translationX = linksTravel * progress.value
                alpha = 1f - chipCrossfade.value
            }
        }
    }
}

/** A collapsed category: a pill, because it behaves differently from a leaf tab and must look it. */
@Composable
private fun CategoryChip(
    label: String,
    badge: String?,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(9.dp)
    val interactionSource = remember { MutableInteractionSource() }
    // The tap target fills the bar so the strips above and below the pill aren't dead, but the
    // ripple is drawn by the pill itself — otherwise it floods the full-height hit rectangle.
    Box(
        modifier = Modifier
            .height(BarHeight)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .clip(shape)
                .background(if (selected) SynapseDim else Panel)
                .border(1.dp, if (selected) Synapse else Edge, shape)
                .indication(interactionSource, ripple())
                .padding(horizontal = 13.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) Synapse else Ink)
            if (badge != null) {
                Text(
                    badge,
                    style = TextStyle(fontFamily = Mono, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.6.sp),
                    color = if (selected) Synapse else Muted,
                )
            }
        }
    }
}

/** A leaf tab: full bar height so its indicator can sit on the strip's bottom edge. */
@Composable
private fun LeafTab(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .height(BarHeight)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 11.dp)
            .drawBehind {
                if (!selected) return@drawBehind
                val height = IndicatorHeight.toPx()
                drawRect(
                    color = Synapse,
                    topLeft = Offset(0f, size.height - height),
                    size = Size(size.width, height),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) Synapse else Muted,
        )
    }
}

private val BarHeight = 56.dp
private val HorizontalPadding = 12.dp
private val Gap = 6.dp
private val IndicatorHeight = 3.dp

private const val EXPAND_MS = 420
private const val PHASE_1_MS = 120
private const val COLLAPSE_MS = 360
private const val CROSSFADE_MS = 240
private const val PHASE_1_SHARE = 120f / 420f
/** The chip clears before the leaves arrive; the leaves are legible before they land. */
private const val AGENT_FADE_RATE = 1.85f
private const val LEAF_FADE_RATE = 1.4f
