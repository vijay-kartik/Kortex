package dev.kortex.finance.ui.accounts

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kortex.design.Alarm
import dev.kortex.design.Amber
import dev.kortex.design.Edge
import dev.kortex.design.EdgeStrong
import dev.kortex.design.Ink
import dev.kortex.design.InkSoft
import dev.kortex.design.Muted
import dev.kortex.design.Panel
import dev.kortex.design.R as DesignR
import dev.kortex.design.Sunken
import dev.kortex.design.Synapse
import dev.kortex.design.SynapseDim
import dev.kortex.design.Well
import dev.kortex.design.dashedBorder
import dev.kortex.finance.R
import dev.kortex.finance.domain.model.AccountKind
import dev.kortex.finance.domain.model.StatementStatus
import dev.kortex.finance.ui.FinanceRoute
import dev.kortex.finance.ui.common.AmountLarge
import dev.kortex.finance.ui.common.AmountMedium
import dev.kortex.finance.ui.common.AmountMono
import dev.kortex.finance.ui.common.BottomBarClearance
import dev.kortex.finance.ui.common.FieldList
import dev.kortex.finance.ui.common.FieldRow
import dev.kortex.finance.ui.common.FinanceCard
import dev.kortex.finance.ui.common.FinanceFormat
import dev.kortex.finance.ui.common.IconTile
import dev.kortex.finance.ui.common.PrimaryButton
import dev.kortex.finance.ui.common.ProgressTrack
import dev.kortex.finance.ui.common.ScreenLock
import dev.kortex.finance.ui.common.SecondaryButton
import dev.kortex.finance.ui.common.SectionLabel
import dev.kortex.finance.ui.common.SheetButton
import dev.kortex.mvi.ObserveEffects
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@Composable
fun AccountsRoute(onNavigate: (FinanceRoute) -> Unit, viewModel: AccountsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ObserveEffects(viewModel.effects) { effect ->
        when (effect) {
            is AccountsEffect.Navigate -> onNavigate(effect.route)
        }
    }
    AccountsScreen(state, viewModel::onIntent)
}

@Composable
fun AccountsScreen(state: AccountsState, onIntent: (AccountsIntent) -> Unit, modifier: Modifier = Modifier) {
    if (state.loading) return
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = BottomBarClearance),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            FinanceCard(padding = 20.dp) {
                SectionLabel("Total balance")
                Spacer(Modifier.height(10.dp))
                Text(FinanceFormat.rupees(state.totalMinor), style = AmountLarge, color = Ink)
                Spacer(Modifier.height(8.dp))
                Text("Updated from your entries, SMS and receipts", style = MaterialTheme.typography.bodySmall, color = Muted)
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Your accounts", style = MaterialTheme.typography.titleMedium, color = Ink, modifier = Modifier.weight(1f))
                Text(
                    "+ Add",
                    style = MaterialTheme.typography.labelLarge,
                    color = Synapse,
                    modifier = Modifier.clickableRole { onIntent(AccountsIntent.Add) },
                )
            }
        }
        if (state.rows.isEmpty()) {
            item {
                FinanceCard {
                    Text("No accounts yet", style = MaterialTheme.typography.bodyLarge, color = Ink)
                    Spacer(Modifier.height(4.dp))
                    Text("Add a bank account, cash or wallet with its balance today.", style = MaterialTheme.typography.bodySmall, color = Muted)
                    Spacer(Modifier.height(14.dp))
                    PrimaryButton("Add account", { onIntent(AccountsIntent.Add) })
                }
            }
        }
        items(state.rows, key = { it.uid }) { row ->
            FinanceCard(onClick = { onIntent(AccountsIntent.Open(row.uid)) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconTile(
                        icon = when (row.kind) {
                            AccountKind.CASH -> R.drawable.ic_fin_cash
                            AccountKind.WALLET -> R.drawable.ic_fin_wallet
                            AccountKind.DEBIT_CARD -> R.drawable.ic_fin_card
                            else -> R.drawable.ic_fin_bank
                        },
                        tint = Ink,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(row.name, style = MaterialTheme.typography.bodyLarge, color = Ink)
                        Text(row.subtitle, style = MaterialTheme.typography.bodySmall, color = Muted)
                    }
                    Text(FinanceFormat.rupees(row.balanceMinor), style = AmountMono, color = if (row.balanceMinor < 0) Alarm else Ink)
                }
                Spacer(Modifier.height(12.dp))
                Text(row.lastActivity, style = MaterialTheme.typography.bodySmall, color = Muted)
            }
        }
    }
}

@Composable
fun CardsRoute(onNavigate: (FinanceRoute) -> Unit, viewModel: CardsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ObserveEffects(viewModel.effects) { effect ->
        when (effect) {
            is CardsEffect.Navigate -> onNavigate(effect.route)
        }
    }
    CardsScreen(state, viewModel::onIntent)
}

/**
 * One card: the card, its overview and details, as before. Two or more (Figma: Many cards): what's
 * due across them, a swipeable rail of cards ending in an Add card page, and the overview and
 * details of the card in front. All cards opens a sheet sorted by what's due first.
 */
@Composable
fun CardsScreen(state: CardsState, onIntent: (CardsIntent) -> Unit, modifier: Modifier = Modifier) {
    if (state.loading) return
    val cards = state.cards
    val rail = cards.size > 1
    val pager = rememberPagerState(initialPage = state.frontIndex) { cards.size + 1 }
    val scope = rememberCoroutineScope()
    var showAll by rememberSaveable { mutableStateOf(false) }
    val currentCards by rememberUpdatedState(cards)
    val currentOnIntent by rememberUpdatedState(onIntent)
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collect { page ->
            currentCards.getOrNull(page)?.let { currentOnIntent(CardsIntent.Front(it.uid)) }
        }
    }
    // Past the last card is the Add card page, where there's no card in front.
    val front = if (rail) cards.getOrNull(pager.currentPage) else cards.firstOrNull()
    val addCard = { onIntent(CardsIntent.AddCard) }

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = BottomBarClearance),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (cards.isEmpty()) {
            item { AddCardPrompt("Credit cards", "No credit cards yet", addCard) }
            return@LazyColumn
        }
        if (rail) {
            item(key = "due") { DueAcrossCards(state, onAllCards = { showAll = true }) }
            item(key = "rail") {
                CardRail(
                    cards = cards,
                    pager = pager,
                    onCard = { page ->
                        if (page == pager.currentPage) onIntent(CardsIntent.Edit(cards[page].uid))
                        else scope.launch { pager.animateScrollToPage(page) }
                    },
                    onAdd = addCard,
                )
            }
        } else {
            item(key = cards[0].uid) { CardVisual(cards[0]) { onIntent(CardsIntent.Edit(cards[0].uid)) } }
        }
        if (front == null) {
            item(key = "add") { AddCardPrompt("New card", "Add another card", addCard) }
        } else {
            item(key = "${front.uid}/overview") { CardOverview(front, named = rail, onPayBill = { onIntent(CardsIntent.PayBill(it)) }) }
            item(key = "${front.uid}/details") { CardDetails(front, state.revealed[front.uid], onIntent) }
        }
        if (!rail) {
            item {
                Text(
                    "+ Add another card",
                    style = MaterialTheme.typography.labelLarge,
                    color = Synapse,
                    modifier = Modifier.clickableRole(addCard),
                )
            }
        }
    }

    if (showAll && rail) {
        AllCardsSheet(
            state = state,
            inViewUid = front?.uid,
            onDismiss = { showAll = false },
            onPick = { uid -> scope.launch { pager.animateScrollToPage(cards.indexOfFirst { it.uid == uid }.coerceAtLeast(0)) } },
            onAdd = addCard,
        )
    }
}

/** The empty screen, and the Add card page's prompt. */
@Composable
private fun AddCardPrompt(label: String, title: String, onAdd: () -> Unit) {
    FinanceCard(highlighted = true) {
        SectionLabel(label, color = Synapse)
        Spacer(Modifier.height(8.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, color = Ink)
        Spacer(Modifier.height(6.dp))
        Text(
            "Add a card with its limit, statement and due dates, and what you owe on it today.",
            style = MaterialTheme.typography.bodyMedium,
            color = InkSoft,
        )
        Spacer(Modifier.height(16.dp))
        PrimaryButton("Add card", onAdd)
    }
}

/** Figma: Many cards › Due across cards. Unpaid statement amounts only; spent-since isn't billed yet. */
@Composable
private fun DueAcrossCards(state: CardsState, onAllCards: () -> Unit) {
    FinanceCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                SectionLabel("Due across ${state.cards.size} cards")
                Spacer(Modifier.height(6.dp))
                Text(FinanceFormat.rupees(state.dueMinor), style = AmountMedium, color = Ink)
                Spacer(Modifier.height(2.dp))
                val next = state.nextDueLabel
                Text(
                    buildAnnotatedString {
                        if (state.overdueCount > 0) {
                            withStyle(SpanStyle(color = Alarm)) { append("${state.overdueCount} overdue") }
                            if (next != null) append(" · next due $next")
                        } else {
                            append(next?.let { "Next due $it" } ?: "Nothing due")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
            }
            Spacer(Modifier.width(12.dp))
            Row(
                Modifier
                    .clip(CircleShape)
                    .background(Well)
                    .border(1.dp, EdgeStrong, CircleShape)
                    .clickable(role = Role.Button, onClick = onAllCards)
                    .padding(start = 10.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Three cards fanned out.
                Box(Modifier.size(width = 24.dp, height = 18.dp)) {
                    repeat(3) { i ->
                        Box(
                            Modifier
                                .offset(x = (i * 4).dp, y = (6 - i * 3).dp)
                                .size(width = 16.dp, height = 11.dp)
                                .clip(RoundedCornerShape(2.5.dp))
                                .background(SynapseDim)
                                .border(1.dp, Synapse, RoundedCornerShape(2.5.dp)),
                        )
                    }
                }
                Text("All cards", style = MaterialTheme.typography.labelLarge, color = Ink)
            }
        }
    }
}

/** Neighbouring cards peek 20dp at each edge; the dots below end in a "+" for the Add card page. */
@Composable
private fun CardRail(cards: List<CardUi>, pager: PagerState, onCard: (Int) -> Unit, onAdd: () -> Unit) {
    Column(Modifier.bleed(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HorizontalPager(
            state = pager,
            contentPadding = PaddingValues(horizontal = 32.dp),
            pageSpacing = 12.dp,
            verticalAlignment = Alignment.Top,
            key = { cards.getOrNull(it)?.uid ?: "add" },
        ) { page ->
            val card = cards.getOrNull(page)
            if (card != null) CardVisual(card) { onCard(page) } else AddCardPage(onAdd)
        }
        val current = pager.currentPage
        Row(
            Modifier
                .fillMaxWidth()
                .clearAndSetSemantics {
                    contentDescription = if (current < cards.size) "Card ${current + 1} of ${cards.size}" else "Add card"
                },
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(cards.size) { i ->
                val width by animateDpAsState(if (i == current) 18.dp else 6.dp, label = "dot")
                Box(Modifier.size(width = width, height = 6.dp).clip(CircleShape).background(if (i == current) Synapse else EdgeStrong))
            }
            Text("+", style = MaterialTheme.typography.labelSmall, color = if (current == cards.size) Synapse else Muted)
        }
    }
}

@Composable
private fun AddCardPage(onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 164.dp)
            .clip(shape)
            .dashedBorder(EdgeStrong, 16.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(Well).border(1.dp, EdgeStrong, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("+", style = MaterialTheme.typography.titleLarge, color = Synapse)
        }
        Spacer(Modifier.height(10.dp))
        Text("Add card", style = MaterialTheme.typography.bodyLarge, color = Ink)
        Text("Limit, statement and due dates", style = MaterialTheme.typography.bodySmall, color = Muted)
    }
}

/** Figma: Many cards › All cards. Tapping a row brings that card to the front of the rail. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AllCardsSheet(state: CardsState, inViewUid: String?, onDismiss: () -> Unit, onPick: (String) -> Unit, onAdd: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    // Slide the sheet away before acting, so the rail moves once it's gone.
    fun hideThen(action: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            onDismiss()
            action()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Panel,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = {
            Box(Modifier.padding(top = 10.dp, bottom = 8.dp).size(width = 40.dp, height = 4.dp).clip(CircleShape).background(EdgeStrong))
        },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("All cards", style = MaterialTheme.typography.titleLarge, color = Ink, modifier = Modifier.semantics { heading() })
                    Text(
                        "${state.cards.size} cards · ${FinanceFormat.rupees(state.dueMinor)} due · overdue first",
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted,
                    )
                }
                SheetButton(DesignR.drawable.ic_close, "Close", { hideThen {} })
            }
            val shape = RoundedCornerShape(16.dp)
            Column(Modifier.fillMaxWidth().clip(shape).background(Well).border(1.dp, Edge, shape)) {
                state.cards.byUrgency().forEachIndexed { index, card ->
                    if (index > 0) HorizontalDivider(color = Edge, thickness = 1.dp)
                    CardRow(card, inView = card.uid == inViewUid) { hideThen { onPick(card.uid) } }
                }
            }
            SecondaryButton("+ Add card", { hideThen(onAdd) })
        }
    }
}

@Composable
private fun CardRow(card: CardUi, inView: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (inView) Sunken else Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = 14.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(width = 40.dp, height = 28.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(SynapseDim)
                .border(1.dp, Synapse.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                .padding(6.dp),
        ) {
            Box(Modifier.size(width = 8.dp, height = 6.dp).clip(RoundedCornerShape(1.5.dp)).background(Synapse.copy(alpha = 0.8f)))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    card.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (inView) {
                    Spacer(Modifier.width(6.dp))
                    SectionLabel(
                        "In view",
                        color = Synapse,
                        modifier = Modifier.clip(CircleShape).background(Synapse.copy(alpha = 0.16f)).padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            listOfNotNull(card.last4?.let { "••$it" }, card.utilisationPercent?.let { "${it.roundToInt()}% used" })
                .takeIf { it.isNotEmpty() }
                ?.let { Text(it.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Muted) }
        }
        Spacer(Modifier.width(12.dp))
        val statement = card.statement
        val unpaidMinor = statement?.takeIf { it.status != StatementStatus.PAID }?.unpaidMinor ?: 0
        val (status, statusColor) = when {
            statement == null -> "No bill yet" to Muted
            statement.status == StatementStatus.PAID -> "Bill paid" to Muted
            statement.status == StatementStatus.OVERDUE -> "Overdue · was due ${statement.dueShort}" to Alarm
            else -> "Due ${statement.dueLabel}" to if (statement.dueSoon) Amber else Muted
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(FinanceFormat.rupees(unpaidMinor), style = AmountMono, color = if (unpaidMinor > 0) Ink else Muted)
            Text(status, style = MaterialTheme.typography.bodySmall, color = statusColor)
        }
    }
}

/** Lets the rail run past the list's gutters to the screen edges, so neighbouring cards can peek. */
private fun Modifier.bleed(horizontal: Dp): Modifier = layout { measurable, constraints ->
    val inset = horizontal.roundToPx()
    val placeable = measurable.measure(
        constraints.copy(minWidth = constraints.minWidth + inset * 2, maxWidth = constraints.maxWidth + inset * 2),
    )
    layout(constraints.maxWidth, placeable.height) { placeable.place(-inset, 0) }
}

@Composable
private fun CardVisual(card: CardUi, onClick: () -> Unit) {
    FinanceCard(highlighted = true, onClick = onClick, padding = 20.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(card.name, style = MaterialTheme.typography.titleMedium, color = Ink, modifier = Modifier.weight(1f))
            androidx.compose.material3.Icon(
                androidx.compose.ui.res.painterResource(R.drawable.ic_fin_card),
                contentDescription = null,
                tint = Ink,
            )
        }
        Spacer(Modifier.height(28.dp))
        Text("••••  ••••  ••••  ${card.last4 ?: "····"}", style = MaterialTheme.typography.titleLarge, color = Ink)
        Spacer(Modifier.height(12.dp))
        Row {
            Column(Modifier.weight(1f)) {
                SectionLabel("Card holder", color = Synapse)
                Text(card.holder ?: "—", style = MaterialTheme.typography.bodyMedium, color = Ink)
            }
            Column(Modifier.weight(1f)) {
                SectionLabel("Expires", color = Synapse)
                Text(card.expiry ?: "—", style = MaterialTheme.typography.bodyMedium, color = Ink)
            }
        }
    }
}

/** [named]: with several cards, the subtitle says which card these numbers are for. */
@Composable
private fun CardOverview(card: CardUi, named: Boolean, onPayBill: (String) -> Unit) {
    val statement = card.statement
    FinanceCard(borderColor = if (statement?.status == StatementStatus.OVERDUE) Alarm.copy(alpha = 0.45f) else null) {
        Text("Credit limit overview", style = MaterialTheme.typography.titleMedium, color = Ink)
        val limit = card.limitMinor?.let { FinanceFormat.rupees(it) }
        val subtitle = if (named) {
            listOfNotNull(listOfNotNull(card.name, card.last4?.let { "••$it" }).joinToString(" "), limit?.let { "Limit $it" }).joinToString(" · ")
        } else {
            limit?.let { "Total credit limit: $it" }
        }
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Muted) }
        Spacer(Modifier.height(16.dp))
        if (statement != null && statement.status != StatementStatus.PAID) {
            Text(
                if (statement.status == StatementStatus.OVERDUE) "Statement overdue · was due ${statement.dueLabel}" else "Statement due · by ${statement.dueLabel}",
                style = MaterialTheme.typography.bodySmall,
                color = if (statement.status == StatementStatus.OVERDUE) Alarm else Muted,
            )
            Text(FinanceFormat.rupees(statement.unpaidMinor), style = AmountLarge, color = Ink)
            Text("Minimum due ${FinanceFormat.rupees(statement.minDueMinor, paise = false)}", style = MaterialTheme.typography.bodySmall, color = Amber)
            Spacer(Modifier.height(12.dp))
            Text("Spent since ${statement.statementOn} statement", style = MaterialTheme.typography.bodySmall, color = Muted)
            Text(FinanceFormat.rupees(card.spentSinceMinor), style = AmountMono, color = Ink)
            Spacer(Modifier.height(14.dp))
            PrimaryButton("Pay bill", { onPayBill(statement.statementUid) })
        } else {
            Text(if (statement == null) "Outstanding · no statement yet" else "Statement paid · spent since", style = MaterialTheme.typography.bodySmall, color = Muted)
            Text(FinanceFormat.rupees(if (statement == null) card.outstandingMinor else card.spentSinceMinor), style = AmountLarge, color = Ink)
        }
        card.availableMinor?.let {
            Spacer(Modifier.height(12.dp))
            Text("Available limit", style = MaterialTheme.typography.bodySmall, color = Muted)
            Text(FinanceFormat.rupees(it), style = AmountMono, color = Ink)
        }
        card.utilisationPercent?.let { percent ->
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProgressTrack((percent / 100).toFloat(), if (percent > 80) Alarm else Synapse, Modifier.weight(1f))
                Spacer(Modifier.width(12.dp))
                Text("$percent% used", style = MaterialTheme.typography.bodySmall, color = Ink)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Outstanding ${FinanceFormat.rupees(card.outstandingMinor)}" +
                    (card.statement?.takeIf { it.status != StatementStatus.PAID }?.let { ": the bill plus ${FinanceFormat.rupees(card.spentSinceMinor)} spent since." } ?: "."),
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
            )
        }
    }
}

@Composable
private fun CardDetails(card: CardUi, revealed: String?, onIntent: (CardsIntent) -> Unit) {
    val context = LocalContext.current
    var problem by remember { mutableStateOf<String?>(null) }
    Column {
        Text("Card details", style = MaterialTheme.typography.titleMedium, color = Ink)
        Spacer(Modifier.height(12.dp))
        FieldList(
            listOfNotNull(
                FieldRow(
                    "Card number",
                    revealed?.let(ScreenLock::grouped) ?: ("•••• •••• •••• ${card.last4 ?: "····"}" + if (card.hasSecret) "  · Show" else ""),
                    valueColor = if (card.hasSecret && revealed == null) Synapse else null,
                    onClick = if (!card.hasSecret) null else {
                        {
                            problem = null
                            if (revealed != null) {
                                onIntent(CardsIntent.Hide(card.uid))
                            } else {
                                ScreenLock.confirm(context, "Show ${card.name}’s number", { onIntent(CardsIntent.Reveal(card.uid)) }, { problem = it })
                            }
                        }
                    },
                ),
                card.holder?.let { FieldRow("Card holder", it) },
                card.expiry?.let { FieldRow("Expiry", it) },
                card.network?.let { FieldRow("Card network", it) },
                card.billingCycle?.let { FieldRow("Billing cycle", it) },
                card.dueDay?.let { FieldRow("Payment due date", it) },
            ),
        )
        problem?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = Amber)
        }
    }
}

/** A text button: clickable with the button role. */
internal fun Modifier.clickableRole(onClick: () -> Unit): Modifier = clickable(role = Role.Button, onClick = onClick)
