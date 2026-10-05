package dev.kortex.sync.finance

import dev.kortex.finance.data.local.AccountEntity
import dev.kortex.finance.data.local.CardStatementEntity
import dev.kortex.finance.data.local.CategoryEntity
import dev.kortex.finance.data.local.MerchantEntity
import dev.kortex.finance.data.local.RecurringEntity
import dev.kortex.finance.data.local.RemoteRow
import dev.kortex.finance.data.local.SecretEntity
import dev.kortex.finance.data.local.TransactionEntity
import dev.kortex.finance.domain.model.AccountKind
import dev.kortex.finance.domain.model.BankType
import dev.kortex.finance.domain.model.CategoryKind
import dev.kortex.finance.domain.model.Frequency
import dev.kortex.finance.domain.model.RecurringKind
import dev.kortex.finance.domain.model.StatementSource
import dev.kortex.finance.domain.model.TransactionSource
import dev.kortex.finance.domain.model.TransactionType
import dev.kortex.sync.SERVER_UPDATED_AT
import java.time.LocalDate
import org.json.JSONArray
import org.json.JSONObject

/**
 * Finance documents under `users/{uid}/fin*` (docs/FINANCE_PLAN.md › Data model). Fields are named
 * as the Room columns, except the times: `createdAt`, `updatedAt` and a transaction's `occurredAt`
 * are millis, dates are `yyyy-MM-dd`. Every field is written, null or not, so a merge clears what a
 * record no longer has. Balances are never stored: they're summed from the entries.
 */
internal object FinFields {
    const val CREATED_AT = "createdAt"
    const val UPDATED_AT = "updatedAt"
    const val DELETED = "deleted"
    const val OCCURRED_AT = "occurredAt"
    /** A map of [RECEIPT_ITEM_COUNT], [RECEIPT_ITEMS], [RECEIPT_TAX_MINOR] and [RECEIPT_PHOTO], or null. */
    const val RECEIPT = "receipt"
    const val RECEIPT_ITEM_COUNT = "itemCount"
    /** A list of `{name, qty, amountMinor}`. */
    const val RECEIPT_ITEMS = "items"
    const val RECEIPT_TAX_MINOR = "taxMinor"
    /** `{devicePath, mimeType}`: the photo stays on the phone that scanned it. */
    const val RECEIPT_PHOTO = "photo"
}

// ── Writing ──────────────────────────────────────────────────────────

/** [serverTime] is `FieldValue.serverTimestamp()`; a parameter so these stay testable. */
internal fun accountDoc(a: AccountEntity, serverTime: Any): Map<String, Any?> = mapOf(
    "kind" to a.kind,
    "name" to a.name,
    "institution" to a.institution,
    "last4" to a.last4,
    "hasSecret" to a.hasSecret,
    "bankType" to a.bankType,
    "ifsc" to a.ifsc,
    "linkedAccountUid" to a.linkedAccountUid,
    "network" to a.network,
    "expiry" to a.expiry,
    "holder" to a.holder,
    "creditLimitMinor" to a.creditLimitMinor,
    "statementDay" to a.statementDay,
    "dueDay" to a.dueDay,
    "colorToken" to a.colorToken,
    "archived" to a.archived,
) + meta(a.createdAtMillis, a.updatedAtMillis, serverTime)

internal fun transactionDoc(t: TransactionEntity, serverTime: Any): Map<String, Any?> = mapOf(
    "type" to t.type,
    "amountMinor" to t.amountMinor,
    "currency" to t.currency,
    FinFields.OCCURRED_AT to t.occurredAtMillis,
    "occurredOn" to t.occurredOn,
    "accountUid" to t.accountUid,
    "toAccountUid" to t.toAccountUid,
    "categoryUid" to t.categoryUid,
    "merchant" to t.merchant,
    "payeeKey" to t.payeeKey,
    "note" to t.note,
    "source" to t.source,
    "sourceRef" to t.sourceRef,
    "recurringUid" to t.recurringUid,
    "dueOn" to t.dueOn,
    "statementUid" to t.statementUid,
    FinFields.RECEIPT to t.receiptItemCount?.let { count ->
        mapOf(
            FinFields.RECEIPT_ITEM_COUNT to count,
            FinFields.RECEIPT_ITEMS to receiptItemsToList(t.receiptItems),
            FinFields.RECEIPT_TAX_MINOR to t.receiptTaxMinor,
            FinFields.RECEIPT_PHOTO to t.receiptPhotoPath?.let { mapOf("devicePath" to it, "mimeType" to t.receiptPhotoMime) },
        )
    },
) + meta(t.createdAtMillis, t.updatedAtMillis, serverTime)

internal fun categoryDoc(c: CategoryEntity, serverTime: Any): Map<String, Any?> = mapOf(
    "name" to c.name,
    "kind" to c.kind,
    "colorToken" to c.colorToken,
    "sortOrder" to c.sortOrder,
) + meta(c.createdAtMillis, c.updatedAtMillis, serverTime)

internal fun recurringDoc(r: RecurringEntity, serverTime: Any): Map<String, Any?> = mapOf(
    "name" to r.name,
    "kind" to r.kind,
    "amountMinor" to r.amountMinor,
    "currency" to r.currency,
    "frequency" to r.frequency,
    "interval" to r.interval,
    "anchorDay" to r.anchorDay,
    "nextDueOn" to r.nextDueOn,
    "accountUid" to r.accountUid,
    "categoryUid" to r.categoryUid,
    "remindDaysBefore" to r.remindDaysBefore,
    "autoMarkPaid" to r.autoMarkPaid,
    "paused" to r.paused,
) + meta(r.createdAtMillis, r.updatedAtMillis, serverTime)

internal fun statementDoc(s: CardStatementEntity, serverTime: Any): Map<String, Any?> = mapOf(
    "cardUid" to s.cardUid,
    "periodStart" to s.periodStart,
    "statementOn" to s.statementOn,
    "dueOn" to s.dueOn,
    "totalDueMinor" to s.totalDueMinor,
    "minDueMinor" to s.minDueMinor,
    "source" to s.source,
) + meta(s.createdAtMillis, s.updatedAtMillis, serverTime)

internal fun merchantDoc(m: MerchantEntity, serverTime: Any): Map<String, Any?> = mapOf(
    "payeeKey" to m.payeeKey,
    "displayName" to m.displayName,
    "categoryUid" to m.categoryUid,
    FinFields.UPDATED_AT to m.updatedAtMillis,
    SERVER_UPDATED_AT to serverTime,
    FinFields.DELETED to false,
)

/**
 * An account's full number: cipher text and the key version that sealed it, nothing else. A
 * separate collection, so lists and the browser extension never read it.
 */
internal fun secretDoc(s: SecretEntity, serverTime: Any): Map<String, Any?> = mapOf(
    "cipherText" to s.cipherText,
    "keyVersion" to s.keyVersion,
    FinFields.UPDATED_AT to s.updatedAtMillis,
    SERVER_UPDATED_AT to serverTime,
    FinFields.DELETED to false,
)

/** Merged over any finance document; the rest of its fields stay for whoever still has it. */
internal fun finDeletedDoc(deletedAtMillis: Long, serverTime: Any): Map<String, Any?> = mapOf(
    FinFields.UPDATED_AT to deletedAtMillis,
    SERVER_UPDATED_AT to serverTime,
    FinFields.DELETED to true,
)

private fun meta(createdAt: Long, updatedAt: Long, serverTime: Any): Map<String, Any?> = mapOf(
    FinFields.CREATED_AT to createdAt,
    FinFields.UPDATED_AT to updatedAt,
    SERVER_UPDATED_AT to serverTime,
    FinFields.DELETED to false,
)

private fun receiptItemsToList(json: String?): List<Map<String, Any?>> {
    val array = json?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return emptyList()
    return (0 until array.length()).mapNotNull { i ->
        val item = array.optJSONObject(i) ?: return@mapNotNull null
        mapOf("name" to item.optString("name"), "qty" to item.optInt("quantity", 1), "amountMinor" to item.optLong("amountMinor"))
    }
}

// ── Reading ──────────────────────────────────────────────────────────

/**
 * Each reads a pulled document, or returns null when it can't be applied (no `updatedAt`, or a live
 * record missing what it needs). A deleted one needs only its uid and `updatedAt`.
 */
internal fun remoteAccount(uid: String, d: Map<String, Any?>): RemoteRow<AccountEntity>? = read(uid, d) { updatedAt ->
    AccountEntity(
        uid = uid,
        kind = d.enumName("kind", AccountKind.entries) ?: return@read null,
        name = d.text("name") ?: return@read null,
        institution = d.text("institution"),
        last4 = d.text("last4"),
        hasSecret = d.bool("hasSecret"),
        bankType = d.enumName("bankType", BankType.entries),
        ifsc = d.text("ifsc"),
        linkedAccountUid = d.text("linkedAccountUid"),
        network = d.text("network"),
        expiry = d.text("expiry"),
        holder = d.text("holder"),
        creditLimitMinor = d.long("creditLimitMinor"),
        statementDay = d.long("statementDay")?.toInt(),
        dueDay = d.long("dueDay")?.toInt(),
        colorToken = d.text("colorToken"),
        archived = d.bool("archived"),
        createdAtMillis = d.long(FinFields.CREATED_AT) ?: updatedAt,
        updatedAtMillis = updatedAt,
        dirty = 0,
    )
}

internal fun remoteTransaction(uid: String, d: Map<String, Any?>): RemoteRow<TransactionEntity>? = read(uid, d) { updatedAt ->
    val receipt = d[FinFields.RECEIPT] as? Map<*, *>
    val photo = receipt?.get(FinFields.RECEIPT_PHOTO) as? Map<*, *>
    val amount = d.long("amountMinor")?.takeIf { it > 0 } ?: return@read null
    TransactionEntity(
        uid = uid,
        type = d.enumName("type", TransactionType.entries) ?: return@read null,
        amountMinor = amount,
        currency = d.text("currency") ?: "INR",
        occurredAtMillis = d.long(FinFields.OCCURRED_AT) ?: return@read null,
        occurredOn = d.date("occurredOn") ?: return@read null,
        accountUid = d.text("accountUid") ?: return@read null,
        toAccountUid = d.text("toAccountUid"),
        categoryUid = d.text("categoryUid"),
        merchant = d.text("merchant"),
        payeeKey = d.text("payeeKey"),
        note = d.text("note"),
        source = d.enumName("source", TransactionSource.entries) ?: TransactionSource.MANUAL.name,
        sourceRef = d.text("sourceRef"),
        recurringUid = d.text("recurringUid"),
        dueOn = d.date("dueOn"),
        statementUid = d.text("statementUid"),
        receiptItemCount = (receipt?.get(FinFields.RECEIPT_ITEM_COUNT) as? Number)?.toInt(),
        receiptItems = (receipt?.get(FinFields.RECEIPT_ITEMS) as? List<*>)?.let(::receiptItemsToJson),
        receiptTaxMinor = (receipt?.get(FinFields.RECEIPT_TAX_MINOR) as? Number)?.toLong(),
        // Another phone's path; the photo itself is never synced (docs/FINANCE_PLAN.md › Out of scope).
        receiptPhotoPath = (photo?.get("devicePath") as? String)?.takeIf { it.isNotBlank() },
        receiptPhotoMime = (photo?.get("mimeType") as? String)?.takeIf { it.isNotBlank() },
        createdAtMillis = d.long(FinFields.CREATED_AT) ?: updatedAt,
        updatedAtMillis = updatedAt,
        dirty = 0,
    )
}

internal fun remoteCategory(uid: String, d: Map<String, Any?>): RemoteRow<CategoryEntity>? = read(uid, d) { updatedAt ->
    CategoryEntity(
        uid = uid,
        name = d.text("name") ?: return@read null,
        kind = d.enumName("kind", CategoryKind.entries) ?: return@read null,
        colorToken = d.text("colorToken") ?: "Synapse",
        builtIn = false,
        sortOrder = d.long("sortOrder")?.toInt() ?: 0,
        createdAtMillis = d.long(FinFields.CREATED_AT) ?: updatedAt,
        updatedAtMillis = updatedAt,
        dirty = 0,
    )
}

internal fun remoteRecurring(uid: String, d: Map<String, Any?>): RemoteRow<RecurringEntity>? = read(uid, d) { updatedAt ->
    RecurringEntity(
        uid = uid,
        name = d.text("name") ?: return@read null,
        kind = d.enumName("kind", RecurringKind.entries) ?: return@read null,
        amountMinor = d.long("amountMinor")?.takeIf { it > 0 } ?: return@read null,
        currency = d.text("currency") ?: "INR",
        frequency = d.enumName("frequency", Frequency.entries) ?: return@read null,
        interval = d.long("interval")?.toInt()?.coerceAtLeast(1) ?: 1,
        anchorDay = d.long("anchorDay")?.toInt() ?: return@read null,
        nextDueOn = d.date("nextDueOn") ?: return@read null,
        accountUid = d.text("accountUid") ?: return@read null,
        categoryUid = d.text("categoryUid"),
        remindDaysBefore = d.long("remindDaysBefore")?.toInt() ?: -1,
        autoMarkPaid = d.bool("autoMarkPaid"),
        paused = d.bool("paused"),
        createdAtMillis = d.long(FinFields.CREATED_AT) ?: updatedAt,
        updatedAtMillis = updatedAt,
        dirty = 0,
    )
}

internal fun remoteStatement(uid: String, d: Map<String, Any?>): RemoteRow<CardStatementEntity>? = read(uid, d) { updatedAt ->
    CardStatementEntity(
        uid = uid,
        cardUid = d.text("cardUid") ?: return@read null,
        periodStart = d.date("periodStart") ?: return@read null,
        statementOn = d.date("statementOn") ?: return@read null,
        dueOn = d.date("dueOn") ?: return@read null,
        totalDueMinor = d.long("totalDueMinor") ?: return@read null,
        minDueMinor = d.long("minDueMinor") ?: 0,
        source = d.enumName("source", StatementSource.entries) ?: StatementSource.AUTO.name,
        createdAtMillis = d.long(FinFields.CREATED_AT) ?: updatedAt,
        updatedAtMillis = updatedAt,
        dirty = 0,
    )
}

internal fun remoteMerchant(uid: String, d: Map<String, Any?>): RemoteRow<MerchantEntity>? = read(uid, d) { updatedAt ->
    MerchantEntity(
        uid = uid,
        payeeKey = d.text("payeeKey") ?: return@read null,
        displayName = d.text("displayName") ?: return@read null,
        categoryUid = d.text("categoryUid"),
        updatedAtMillis = updatedAt,
        dirty = 0,
    )
}

internal fun remoteSecret(uid: String, d: Map<String, Any?>): RemoteRow<SecretEntity>? = read(uid, d) { updatedAt ->
    SecretEntity(
        uid = uid,
        cipherText = d.text("cipherText") ?: return@read null,
        keyVersion = d.long("keyVersion")?.toInt() ?: return@read null,
        updatedAtMillis = updatedAt,
        dirty = 0,
    )
}

/** A delete needs nothing but `updatedAt`; a live record is built by [build], null when malformed. */
private inline fun <T> read(uid: String, d: Map<String, Any?>, build: (updatedAt: Long) -> T?): RemoteRow<T>? {
    val updatedAt = d.long(FinFields.UPDATED_AT) ?: return null
    if (d[FinFields.DELETED] as? Boolean == true) return RemoteRow(uid, updatedAt, deleted = true, row = null)
    val row = build(updatedAt) ?: return null
    return RemoteRow(uid, updatedAt, deleted = false, row = row)
}

private fun receiptItemsToJson(items: List<*>): String = JSONArray(
    items.filterIsInstance<Map<*, *>>().map { item ->
        JSONObject()
            .put("name", item["name"] as? String ?: "")
            .put("quantity", (item["qty"] as? Number)?.toInt() ?: 1)
            .put("amountMinor", (item["amountMinor"] as? Number)?.toLong() ?: 0L)
    },
).toString()

// Numbers written from JavaScript can arrive as doubles.
private fun Map<String, Any?>.long(key: String): Long? = (this[key] as? Number)?.toLong()

private fun Map<String, Any?>.text(key: String): String? = (this[key] as? String)?.takeIf { it.isNotBlank() }

private fun Map<String, Any?>.bool(key: String): Boolean = this[key] as? Boolean ?: false

/** One of [values]' names, else null: a value only a newer app knows can't be applied here. */
private fun Map<String, Any?>.enumName(key: String, values: List<Enum<*>>): String? = text(key)?.takeIf { name -> values.any { it.name == name } }

/** A `yyyy-MM-dd` date that parses. */
private fun Map<String, Any?>.date(key: String): String? = text(key)?.takeIf { runCatching { LocalDate.parse(it) }.isSuccess }
