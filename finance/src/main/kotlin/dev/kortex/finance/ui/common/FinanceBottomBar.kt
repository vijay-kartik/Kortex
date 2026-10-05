package dev.kortex.finance.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.kortex.design.Edge
import dev.kortex.design.Muted
import dev.kortex.design.Panel
import dev.kortex.design.Synapse
import dev.kortex.design.SynapseDim
import dev.kortex.design.Void
import dev.kortex.design.anim.EmphasizedDecelerate
import dev.kortex.design.anim.StandardEasing
import dev.kortex.finance.ui.FinanceSection

/**
 * Floating toolbar (Figma: Finances/BottomBar, Bottom bar — notes): at least 252×56, centred, 24dp
 * above the gesture bar, over a Void scrim that fades content out beneath it. The active tab is a
 * SynapseDim capsule with icon and label; the rest are 44dp icons. It grows to fit the active label
 * rather than squeezing the last icon. Scrolling down hides it; any upward scroll brings it back
 * ([visible]).
 */
@Composable
fun FinanceBottomBar(
    selected: FinanceSection,
    onSelect: (FinanceSection) -> Unit,
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(tween(200, easing = EmphasizedDecelerate)) { it },
        exit = slideOutVertically(tween(200, easing = StandardEasing)) { it },
        modifier = modifier,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Void.copy(alpha = 0.92f), Void))),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Row(
                Modifier
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(top = 44.dp, bottom = 24.dp)
                    .widthIn(min = 252.dp)
                    .height(56.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(Panel.copy(alpha = 0.94f))
                    .border(1.dp, Edge, RoundedCornerShape(28.dp))
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
            ) {
                FinanceSection.entries.forEach { section ->
                    BarTab(section, section == selected) { onSelect(section) }
                }
            }
        }
    }
}

@Composable
private fun BarTab(section: FinanceSection, active: Boolean, onClick: () -> Unit) {
    val tint by animateColorAsState(if (active) Synapse else Muted, tween(200), label = "tab tint")
    Row(
        Modifier
            .height(44.dp)
            .widthIn(min = 44.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(if (active) SynapseDim else Color.Transparent)
            .selectable(selected = active, role = Role.Tab, onClick = onClick)
            .padding(horizontal = if (active) 14.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(painterResource(section.icon), contentDescription = section.label, tint = tint, modifier = Modifier.size(20.dp))
        AnimatedVisibility(active, enter = expandHorizontally(tween(200)), exit = shrinkHorizontally(tween(200))) {
            Row {
                Spacer(Modifier.width(8.dp))
                Text(section.label, style = MaterialTheme.typography.labelLarge, color = Synapse)
            }
        }
    }
}
