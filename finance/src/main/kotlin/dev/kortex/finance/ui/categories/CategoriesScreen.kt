package dev.kortex.finance.ui.categories

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kortex.design.Alarm
import dev.kortex.design.Amber
import dev.kortex.design.Edge
import dev.kortex.design.Ink
import dev.kortex.design.Muted
import dev.kortex.design.Panel
import dev.kortex.design.R as DesignR
import dev.kortex.design.Synapse
import dev.kortex.design.Well
import dev.kortex.finance.R
import dev.kortex.finance.domain.model.CategoryKind
import dev.kortex.finance.ui.FinanceRoute
import dev.kortex.finance.ui.common.BudgetLevel
import dev.kortex.finance.ui.common.BudgetProgressUi
import dev.kortex.finance.ui.common.FinanceChip
import dev.kortex.finance.ui.common.FinanceColors
import dev.kortex.finance.ui.common.FinancePushedScreen
import dev.kortex.finance.ui.common.FinanceSheet
import dev.kortex.finance.ui.common.InputCard
import dev.kortex.finance.ui.common.NoticeCard
import dev.kortex.finance.ui.common.PrimaryButton
import dev.kortex.finance.ui.common.ProgressTrack
import dev.kortex.finance.ui.common.RadioRow
import dev.kortex.finance.ui.common.SecondaryButton
import dev.kortex.finance.ui.common.SectionLabel
import dev.kortex.finance.ui.common.Segmented
import dev.kortex.finance.ui.common.SheetButton
import dev.kortex.mvi.ObserveEffects

private val GroupShape = RoundedCornerShape(16.dp)

@Composable
fun CategoriesRoute(onNavigate: (FinanceRoute) -> Unit, onBack: () -> Unit, viewModel: CategoriesViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ObserveEffects(viewModel.effects) { effect ->
        when (effect) {
            is CategoriesEffect.Navigate -> onNavigate(effect.route)
        }
    }
    CategoriesScreen(state, viewModel::onIntent, onBack)
}

@Composable
fun CategoriesScreen(state: CategoriesState, onIntent: (CategoriesIntent) -> Unit, onBack: () -> Unit) {
    FinancePushedScreen(
        title = "Categories",
        onBack = onBack,
        action = {
            Text(
                "+ New",
                style = MaterialTheme.typography.labelLarge,
                color = Synapse,
                modifier = Modifier.clickable(role = Role.Button) { onIntent(CategoriesIntent.New) },
            )
        },
    ) {
        if (state.loading) return@FinancePushedScreen
        CategoryGroup("Expense · built in", state.expenseBuiltIn, onIntent)
        CategoryGroup("Expense · yours", state.expenseYours, onIntent)
        CategoryGroup("Income", state.income, onIntent)
        Text(
            "Tap an expense category to set its monthly budget. Built-in categories can’t be renamed or removed.",
            style = MaterialTheme.typography.labelSmall,
            color = Muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun CategoryGroup(label: String, rows: List<CategoryRowUi>, onIntent: (CategoriesIntent) -> Unit) {
    if (rows.isEmpty()) return
    Column {
        SectionLabel(label)
        Spacer(Modifier.height(8.dp))
        Column(Modifier.fillMaxWidth().clip(GroupShape).background(Panel).border(1.dp, Edge, GroupShape)) {
            rows.forEachIndexed { index, row ->
                if (index > 0) HorizontalDivider(color = Edge, modifier = Modifier.padding(horizontal = 16.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .then(if (row.opens) Modifier.clickable(role = Role.Button) { onIntent(CategoriesIntent.Open(row.uid)) } else Modifier)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(10.dp).background(FinanceColors.of(row.colorToken), CircleShape))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(row.name, style = MaterialTheme.typography.bodyLarge, color = Ink)
                        val budget = row.budget
                        if (budget == null) {
                            Text(row.subtitle, style = MaterialTheme.typography.bodySmall, color = Muted)
                        } else {
                            BudgetLine(budget, " this month")
                            Spacer(Modifier.height(6.dp))
                            ProgressTrack(budget.fraction, budget.level.color)
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Icon(
                        painterResource(if (row.opens) R.drawable.ic_fin_chevron_right else R.drawable.ic_fin_lock),
                        contentDescription = if (row.opens) null else "Built in",
                        tint = Muted,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

/** "₹5,400 of ₹8,000[suffix]", the spent amount Amber from 80 % and Alarm over budget. */
@Composable
private fun BudgetLine(budget: BudgetProgressUi, suffix: String, style: TextStyle = MaterialTheme.typography.bodySmall) {
    val spentColor = if (budget.level == BudgetLevel.UNDER) Muted else budget.level.color
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = spentColor)) { append(budget.spent) }
            append(" of ${budget.budget}$suffix")
        },
        style = style,
        color = Muted,
    )
}

@Composable
fun CategoryFormRoute(
    uid: String?,
    kind: CategoryKind,
    onClose: () -> Unit,
    viewModel: CategoryFormViewModel = hiltViewModel(),
) {
    LaunchedEffect(uid, kind) { viewModel.start(uid, kind) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    ObserveEffects(viewModel.effects) { effect ->
        when (effect) {
            CategoryFormEffect.Close -> onClose()
        }
    }
    val deleting = state.deleting
    if (deleting != null) {
        DeleteCategorySheet(state, deleting, viewModel::onIntent)
    } else {
        CategoryFormScreen(state, viewModel::onIntent, onClose)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CategoryFormScreen(state: CategoryFormState, onIntent: (CategoryFormIntent) -> Unit, onClose: () -> Unit) {
    FinanceSheet(
        title = state.title,
        onClose = onClose,
        headerAction = if (state.editing && !state.builtIn) {
            { SheetButton(DesignR.drawable.ic_trash, "Delete category", { onIntent(CategoryFormIntent.AskDelete) }, tint = Alarm) }
        } else {
            null
        },
        footer = {
            state.error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = Alarm)
                Spacer(Modifier.height(8.dp))
            }
            if (state.budgetable && state.savedBudgetMinor != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SecondaryButton("Remove budget", { onIntent(CategoryFormIntent.RemoveBudget) }, Modifier.weight(1f))
                    PrimaryButton(state.saveLabel, { onIntent(CategoryFormIntent.Save) }, Modifier.weight(1f), enabled = state.canSave)
                }
            } else {
                PrimaryButton(state.saveLabel, { onIntent(CategoryFormIntent.Save) }, enabled = state.canSave)
            }
        },
    ) {
        if (state.loading) return@FinanceSheet
        if (state.builtIn) {
            Text("Built in · only the budget can be changed", style = MaterialTheme.typography.bodySmall, color = Muted)
            BudgetFields(state, onIntent)
            return@FinanceSheet
        }
        if (!state.editing) {
            Column {
                SectionLabel("Type")
                Spacer(Modifier.height(8.dp))
                Segmented(
                    listOf("Expense", "Income"),
                    if (state.kind == CategoryKind.EXPENSE) 0 else 1,
                    { onIntent(CategoryFormIntent.SelectKind(if (it == 0) CategoryKind.EXPENSE else CategoryKind.INCOME)) },
                )
                Spacer(Modifier.height(6.dp))
                Text(state.kindHint, style = MaterialTheme.typography.bodySmall, color = Muted)
            }
        }
        InputCard(
            label = "Name",
            value = state.name,
            onValueChange = { onIntent(CategoryFormIntent.Name(it)) },
            placeholder = if (state.kind == CategoryKind.EXPENSE) "e.g. Groceries" else "e.g. Freelance",
            textStyle = MaterialTheme.typography.headlineSmall,
        )
        Column {
            SectionLabel("Colour")
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FinanceColors.categoryChoices.forEach { token ->
                    val selected = token == state.colorToken
                    Box(
                        Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(FinanceColors.of(token))
                            .then(if (selected) Modifier.border(3.dp, Ink, CircleShape) else Modifier)
                            .clickable(role = Role.RadioButton) { onIntent(CategoryFormIntent.Color(token)) }
                            .semantics { contentDescription = if (selected) "$token, selected" else token },
                    )
                }
            }
        }
        if (state.budgetable) {
            // Budgets · 03 puts the budget where the chip preview was.
            BudgetFields(state, onIntent)
            return@FinanceSheet
        }
        Column {
            SectionLabel("Preview")
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FinanceChip(state.name.trim().ifEmpty { "Name" }, selected = true, onClick = {}, dot = FinanceColors.of(state.colorToken))
                state.siblings.take(3).forEach { sibling ->
                    FinanceChip(sibling.name, selected = false, onClick = {}, dot = FinanceColors.of(sibling.colorToken))
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                if (state.kind == CategoryKind.EXPENSE) {
                    "Kortex suggests it for merchants you tag with it."
                } else {
                    "Kortex suggests it for payers you tag with it."
                },
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
            )
        }
    }
}

/** The Monthly budget field and the This month card (Figma: Budgets · 02–04). */
@Composable
private fun BudgetFields(state: CategoryFormState, onIntent: (CategoryFormIntent) -> Unit) {
    InputCard(
        label = "Monthly budget",
        value = state.budgetText,
        onValueChange = { onIntent(CategoryFormIntent.Budget(it)) },
        placeholder = "₹0",
        helper = state.budgetHelper,
        keyboardType = KeyboardType.Decimal,
        textStyle = MaterialTheme.typography.headlineSmall,
        error = state.budgetInvalid,
    )
    val preview = state.preview ?: return
    Column(Modifier.fillMaxWidth().clip(GroupShape).background(Panel).border(1.dp, Edge, GroupShape).padding(16.dp)) {
        SectionLabel("This month")
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { BudgetLine(preview.progress, "", MaterialTheme.typography.bodyLarge) }
            Text("${preview.percentUsed}%", style = MaterialTheme.typography.labelLarge, color = preview.progress.level.color)
        }
        Spacer(Modifier.height(8.dp))
        ProgressTrack(preview.progress.fraction, preview.progress.level.color)
        Spacer(Modifier.height(8.dp))
        Row {
            Text(
                preview.left,
                style = MaterialTheme.typography.bodySmall,
                color = if (preview.progress.level == BudgetLevel.OVER) Alarm else Muted,
                modifier = Modifier.weight(1f),
            )
            Text(preview.projection, style = MaterialTheme.typography.bodySmall, color = Muted)
        }
    }
    state.previewNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Muted) }
}

@Composable
private fun DeleteCategorySheet(state: CategoryFormState, deleting: DeleteCategoryUi, onIntent: (CategoryFormIntent) -> Unit) {
    val noun = if (state.kind == CategoryKind.EXPENSE) "expenses" else "income entries"
    FinanceSheet(
        title = "Delete category",
        onClose = { onIntent(CategoryFormIntent.CancelDelete) },
        footer = {
            state.error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = Alarm)
                Spacer(Modifier.height(8.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton("Cancel", { onIntent(CategoryFormIntent.CancelDelete) }, Modifier.weight(1f))
                PrimaryButton("Delete category", { onIntent(CategoryFormIntent.ConfirmDelete) }, Modifier.weight(1f), enabled = !state.saving, color = Alarm)
            }
        },
    ) {
        NoticeCard(
            label = "${state.name} · ${deleting.entryCount} $noun",
            body = if (deleting.entryCount == 0) {
                "Nothing is filed under it yet."
            } else {
                "Deleting a category doesn’t delete its $noun. Choose where they go."
            },
            accent = Amber,
        )
        if (deleting.entryCount > 0) {
            Column(Modifier.fillMaxWidth().clip(GroupShape).background(Well).border(1.dp, Edge, GroupShape)) {
                deleting.targets.forEachIndexed { index, target ->
                    if (index > 0) HorizontalDivider(color = Edge)
                    RadioRow(target.title, target.subtitle, target.uid == deleting.selected, { onIntent(CategoryFormIntent.PickTarget(target.uid)) })
                }
            }
        }
    }
}
