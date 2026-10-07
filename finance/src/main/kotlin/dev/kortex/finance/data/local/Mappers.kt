package dev.kortex.finance.data.local

import dev.kortex.finance.domain.model.Account
import dev.kortex.finance.domain.model.AccountKind
import dev.kortex.finance.domain.model.BankType
import dev.kortex.finance.domain.model.Budget
import dev.kortex.finance.domain.model.CardStatement
import dev.kortex.finance.domain.model.Category
import dev.kortex.finance.domain.model.CategoryKind
import dev.kortex.finance.domain.model.Frequency
import dev.kortex.finance.domain.model.Merchant
import dev.kortex.finance.domain.model.Receipt
import dev.kortex.finance.domain.model.ReceiptItem
import dev.kortex.finance.domain.model.ReceiptPhoto
import dev.kortex.finance.domain.model.Recurring
import dev.kortex.finance.domain.model.RecurringKind
import dev.kortex.finance.domain.model.StatementSource
import dev.kortex.finance.domain.model.Transaction
import dev.kortex.finance.domain.model.TransactionSource
import dev.kortex.finance.domain.model.TransactionType
import java.time.LocalDate
import org.json.JSONArray
import org.json.JSONObject

internal fun AccountEntity.toDomain() = Account(
    uid = uid,
    kind = AccountKind.valueOf(kind),
    name = name,
    institution = institution,
    last4 = last4,
    hasSecret = hasSecret,
    bankType = bankType?.let(BankType::valueOf),
    ifsc = ifsc,
    linkedAccountUid = linkedAccountUid,
    network = network,
    expiry = expiry,
    holder = holder,
    creditLimitMinor = creditLimitMinor,
    statementDay = statementDay,
    dueDay = dueDay,
    colorToken = colorToken,
    archived = archived,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
)

internal fun Account.toEntity() = AccountEntity(
    uid = uid,
    kind = kind.name,
    name = name,
    institution = institution,
    last4 = last4,
    hasSecret = hasSecret,
    bankType = bankType?.name,
    ifsc = ifsc,
    linkedAccountUid = linkedAccountUid,
    network = network,
    expiry = expiry,
    holder = holder,
    creditLimitMinor = creditLimitMinor,
    statementDay = statementDay,
    dueDay = dueDay,
    colorToken = colorToken,
    archived = archived,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
)

internal fun TransactionEntity.toDomain() = Transaction(
    uid = uid,
    type = TransactionType.valueOf(type),
    amountMinor = amountMinor,
    currency = currency,
    occurredAtMillis = occurredAtMillis,
    occurredOn = LocalDate.parse(occurredOn),
    accountUid = accountUid,
    toAccountUid = toAccountUid,
    categoryUid = categoryUid,
    merchant = merchant,
    payeeKey = payeeKey,
    note = note,
    source = TransactionSource.valueOf(source),
    sourceRef = sourceRef,
    recurringUid = recurringUid,
    dueOn = dueOn?.let(LocalDate::parse),
    statementUid = statementUid,
    receipt = receiptItemCount?.let { count ->
        Receipt(
            itemCount = count,
            items = receiptItems?.let(::decodeReceiptItems).orEmpty(),
            taxMinor = receiptTaxMinor,
            photo = receiptPhotoPath?.let { ReceiptPhoto(it, receiptPhotoMime ?: "image/jpeg") },
        )
    },
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
)

internal fun Transaction.toEntity() = TransactionEntity(
    uid = uid,
    type = type.name,
    amountMinor = amountMinor,
    currency = currency,
    occurredAtMillis = occurredAtMillis,
    occurredOn = occurredOn.toString(),
    accountUid = accountUid,
    toAccountUid = toAccountUid,
    categoryUid = categoryUid,
    merchant = merchant,
    payeeKey = payeeKey,
    note = note,
    source = source.name,
    sourceRef = sourceRef,
    recurringUid = recurringUid,
    dueOn = dueOn?.toString(),
    statementUid = statementUid,
    receiptItemCount = receipt?.itemCount,
    receiptItems = receipt?.items?.takeIf { it.isNotEmpty() }?.let(::encodeReceiptItems),
    receiptTaxMinor = receipt?.taxMinor,
    receiptPhotoPath = receipt?.photo?.devicePath,
    receiptPhotoMime = receipt?.photo?.mimeType,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
)

internal fun CategoryEntity.toDomain() = Category(
    uid = uid,
    name = name,
    kind = CategoryKind.valueOf(kind),
    colorToken = colorToken,
    builtIn = builtIn,
    sortOrder = sortOrder,
)

internal fun Category.toEntity(nowMillis: Long, createdAtMillis: Long = nowMillis) = CategoryEntity(
    uid = uid,
    name = name,
    kind = kind.name,
    colorToken = colorToken,
    builtIn = builtIn,
    sortOrder = sortOrder,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = nowMillis,
)

internal fun RecurringEntity.toDomain() = Recurring(
    uid = uid,
    name = name,
    kind = RecurringKind.valueOf(kind),
    amountMinor = amountMinor,
    currency = currency,
    frequency = Frequency.valueOf(frequency),
    interval = interval,
    anchorDay = anchorDay,
    nextDueOn = LocalDate.parse(nextDueOn),
    accountUid = accountUid,
    categoryUid = categoryUid,
    remindDaysBefore = remindDaysBefore,
    autoMarkPaid = autoMarkPaid,
    paused = paused,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
)

internal fun Recurring.toEntity() = RecurringEntity(
    uid = uid,
    name = name,
    kind = kind.name,
    amountMinor = amountMinor,
    currency = currency,
    frequency = frequency.name,
    interval = interval,
    anchorDay = anchorDay,
    nextDueOn = nextDueOn.toString(),
    accountUid = accountUid,
    categoryUid = categoryUid,
    remindDaysBefore = remindDaysBefore,
    autoMarkPaid = autoMarkPaid,
    paused = paused,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
)

internal fun CardStatementEntity.toDomain() = CardStatement(
    uid = uid,
    cardUid = cardUid,
    periodStart = LocalDate.parse(periodStart),
    statementOn = LocalDate.parse(statementOn),
    dueOn = LocalDate.parse(dueOn),
    totalDueMinor = totalDueMinor,
    minDueMinor = minDueMinor,
    source = StatementSource.valueOf(source),
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
)

internal fun CardStatement.toEntity() = CardStatementEntity(
    uid = uid,
    cardUid = cardUid,
    periodStart = periodStart.toString(),
    statementOn = statementOn.toString(),
    dueOn = dueOn.toString(),
    totalDueMinor = totalDueMinor,
    minDueMinor = minDueMinor,
    source = source.name,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
)

internal fun MerchantEntity.toDomain() = Merchant(uid, payeeKey, displayName, categoryUid, updatedAtMillis)

internal fun Merchant.toEntity() = MerchantEntity(uid, payeeKey, displayName, categoryUid, updatedAtMillis)

internal fun BudgetEntity.toDomain() = Budget(categoryUid = uid, amountMinor = amountMinor)

/** [nowMillis] is the creation time too; saving an existing budget keeps its own. */
internal fun Budget.toEntity(nowMillis: Long) =
    BudgetEntity(uid = categoryUid, amountMinor = amountMinor, createdAtMillis = nowMillis, updatedAtMillis = nowMillis)

private fun encodeReceiptItems(items: List<ReceiptItem>): String =
    JSONArray(
        items.map { JSONObject().put("name", it.name).put("quantity", it.quantity).put("amountMinor", it.amountMinor) },
    ).toString()

private fun decodeReceiptItems(json: String): List<ReceiptItem> {
    val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
    return (0 until array.length()).mapNotNull { i ->
        val item = array.optJSONObject(i) ?: return@mapNotNull null
        ReceiptItem(item.optString("name"), item.optInt("quantity", 1), item.optLong("amountMinor"))
    }
}
