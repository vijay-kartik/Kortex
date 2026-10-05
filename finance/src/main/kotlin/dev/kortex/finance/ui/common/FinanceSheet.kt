package dev.kortex.finance.ui.common

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.kortex.design.CircleButton
import dev.kortex.design.Edge
import dev.kortex.design.EdgeStrong
import dev.kortex.design.Ink
import dev.kortex.design.Panel
import dev.kortex.design.R as DesignR
import dev.kortex.design.Void
import dev.kortex.finance.R

/**
 * A Finance sheet (Figma: Add Expense, Add account, Recurring, Categories): rounded Panel over
 * Void, drag handle, header with back, title and close, scrolling [content], and the [footer]
 * buttons pinned at the bottom. Back and ✕ both close it.
 */
@Composable
fun FinanceSheet(
    title: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    headerAction: (@Composable () -> Unit)? = null,
    footer: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    BackHandler(onBack = onClose)
    Box(modifier.fillMaxSize().background(Void).windowInsetsPadding(WindowInsets.statusBars)) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(top = 8.dp)
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(Panel)
                .border(1.dp, Edge, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .imePadding(),
        ) {
            Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.width(40.dp).height(4.dp).clip(CircleShape).background(EdgeStrong))
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SheetButton(R.drawable.ic_fin_back, "Back", onClose)
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    color = Ink,
                    modifier = Modifier.weight(1f).padding(start = 4.dp).semantics { heading() },
                )
                headerAction?.invoke() ?: SheetButton(DesignR.drawable.ic_close, "Close", onClose)
            }
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                content = content,
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 16.dp),
            ) { footer() }
        }
    }
}

/** A 32dp Void circle in the sheet header. */
@Composable
fun SheetButton(icon: Int, contentDescription: String, onClick: () -> Unit, tint: androidx.compose.ui.graphics.Color = Ink) {
    CircleButton(icon = icon, contentDescription = contentDescription, onClick = onClick, tint = tint)
}

/**
 * A full screen pushed from a Finance tab (Pending payments, Monthly report, Categories): the
 * home bar's look with a back button in place of the menu, and no bottom bar.
 */
@Composable
fun FinancePushedScreen(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    action: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    BackHandler(onBack = onBack)
    Column(modifier.fillMaxSize().background(Void)) {
        Box(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).height(56.dp)) {
            CircleButton(
                icon = R.drawable.ic_fin_back,
                contentDescription = "Back",
                onClick = onBack,
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 16.dp),
            )
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = Ink,
                modifier = Modifier.align(Alignment.Center).semantics { heading() },
            )
            Box(Modifier.align(Alignment.CenterEnd).padding(end = 20.dp)) { action() }
        }
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }
}

/** A small icon in a rounded square: account rows, Add Expense / Add Income buttons. */
@Composable
fun IconTile(icon: Int, tint: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 40.dp) {
    Box(
        modifier.size(size).clip(RoundedCornerShape(12.dp)).background(Void).border(1.dp, Edge, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(size / 2))
    }
}
