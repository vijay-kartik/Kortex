# Finances — implementation plan

Figma: `Kortex` › page **Kortex - Finances** (node 229:15) for the screens, and page
**Kortex - Finances (LLD)** (node 275:284) for this plan as boards 01–07. Sync follows
`docs/CLOUD_SYNC_PLAN.md` throughout; only what's different for finance is written here.

## Decisions

- **`:finance` feature module** (package `dev.kortex.finance`), Firebase-free like `:links` and
  `:topics`: `domain/` (models, use cases, calculators), `data/` (Room `FinanceDatabase`),
  `ui/` (MVI screens on `:mvi`). `FinanceSync` and `FinanceDocs` live in `:sync`; `:app` wires Hilt.
- **Money is `Long` minor units (paise) + ISO currency**, as topic Bills already do. INR only in
  v1; the currency field is kept. Rounding (₹396 for ₹395.50) and Indian digit grouping happen
  only when formatting.
- **Balances change only through entries.** A balance is never stored or set: it's the sum of the
  account's transactions, starting with an `OPENING` entry made when the account or card is added
  (Add account: *Opening balance* / *Outstanding today*). Nothing else sets a balance — not Edit
  account (balance is read-only there), not an SMS (*Avl Bal* / *Avl Lmt* are shown for
  reference), not sync. Two phones therefore can't overwrite each other's balance.
- **Everything shown is computed on the device** from Room: pending, cash flow, insights, pace,
  reports. None of it is stored or synced (board 04).
- **Deterministic ids** wherever two devices could create the same record, so duplicates collapse
  into one document: an SMS, a recurring occurrence, a card statement, a merchant (see *Identity*).
- **SMS and receipt parsing run on the phone** (bank regex packs first, the agent's LLM as
  fallback). Raw SMS text and receipt photos never leave the device; anything sent to an LLM has
  digit runs longer than 4 masked to their last 4.
- **Full card / account numbers are stored, encrypted.** Only the phone ever shows them. They're
  encrypted on the device (Tink AEAD, AES-256-GCM) with a per-user data key; only cipher text is
  synced. Lists show `••last4`; revealing a full number needs App lock. CVV is never stored.
- **Deletes never cascade into history.** Deleting an account or category keeps its transactions.
- **Out of scope in v1:** account aggregation / live bank sync, refunds and reversals (card credits
  other than bill payments are skipped), multi-currency maths, receipt photo sync (needs Cloud
  Storage on Blaze, like topic files), shared budgets.

## Data model

One Room table and one Firestore collection per entity, with the same field names. Every synced
entity also has `uid`, `createdAt`, `updatedAt`, `serverUpdatedAt`, `deleted`, and locally a
`dirty` counter — exactly like links and topics.

**Account** (`finAccounts`) — `kind` (BANK · CASH · WALLET · CREDIT_CARD · DEBIT_CARD), `name`,
`institution?`, `last4?`, `hasSecret`, `bankType?` (SAVINGS · CURRENT), `ifsc?`,
`linkedAccountUid?` (debit card → its bank account), `network?`, `expiry?`, `holder?`,
`creditLimitMinor?`, `statementDay?`, `dueDay?`, `colorToken?`, `archived`.

**Transaction** (`finTransactions`) — `type` (OPENING · EXPENSE · INCOME · TRANSFER ·
CARD_PAYMENT), `amountMinor` (> 0; direction comes from `type`), `currency`, `occurredAt` (millis),
`occurredOn` (`2026-09-28`, device zone at save; all day/month grouping uses it), `accountUid`,
`toAccountUid?` (TRANSFER, CARD_PAYMENT), `categoryUid?` (null = Uncategorised), `merchant?`,
`payeeKey?`, `note?`, `source` (MANUAL · SMS · RECEIPT · RECURRING · AGENT · API), `sourceRef?`
(bank / UPI ref), `recurringUid?` + `dueOn?`, `statementUid?`, `receipt?` (`itemCount`,
`items[{name, qty, amountMinor}]`, `taxMinor`, `photo{devicePath, mimeType}`).

**Category** (`finCategories`) — `name`, `kind` (EXPENSE · INCOME), `colorToken`, `builtIn`,
`sortOrder`. Food, Travel, Utilities and Salary are seeded on every device with fixed uids
(`food`, `travel`, `utilities`, `salary`) and never pushed, so two phones can't create two "Food"s.

**Recurring** (`finRecurring`) — `name`, `kind` (SUBSCRIPTION · FIXED), `amountMinor`, `currency`,
`frequency` (WEEKLY · MONTHLY · YEARLY), `interval`, `anchorDay` (clamped to short months),
`nextDueOn`, `accountUid`, `categoryUid?`, `remindDaysBefore` (0–7), `autoMarkPaid`, `paused`.

**CardStatement** (`finStatements`) — `cardUid`, `periodStart`, `statementOn`, `dueOn`,
`totalDueMinor`, `minDueMinor`, `source` (AUTO · SMS · MANUAL). Paid amount and status (DUE ·
PARTLY_PAID · PAID · OVERDUE) are derived from CARD_PAYMENTs.

**Merchant** (`finMerchants`) — `payeeKey` (normalised name or UPI id), `displayName`,
`categoryUid?` (last pick; drives suggestions).

**Secret** (`finSecrets/{accountUid}`) — `cipherText`, `keyVersion`. A separate collection so
lists and the extension never read it.

**Device only:** `sms_sources` (txUid, sender, body — for re-parsing and the underlines),
`receipts/{txUid}.jpg` (only if *Keep the receipt photo* is checked), sync state in DataStore,
versioned per-bank SMS packs as assets.

Room indexes: transactions `(occurredOn)`, `(accountUid, occurredAt)`, `(categoryUid, occurredOn)`,
`(sourceRef)`, `(recurringUid, dueOn)`; recurring `(nextDueOn)`; statements `(cardUid, statementOn)`.

### Identity

| Record | `uid` |
|---|---|
| account, category (yours), recurring, manual transaction | random UUID |
| built-in category | `food`, `travel`, `utilities`, `salary` |
| SMS transaction | `sms_` + hash(sender, body) |
| recurring occurrence | `rec_` + hash(recurringUid, dueOn) |
| card statement | `stmt_` + hash(cardUid, statementOn) |
| merchant | hash(payeeKey) |
| secret | the account's uid |

## Derived values

| On screen | From Room |
|---|---|
| Account balance | Σ OPENING + Σ INCOME in − Σ EXPENSE out − Σ TRANSFER / CARD_PAYMENT out + Σ TRANSFER in. Debit-card spends count on the linked bank account. |
| Card outstanding · available | Σ OPENING + Σ EXPENSE on the card − Σ CARD_PAYMENT to it · creditLimit − outstanding |
| Statement due · spent since | totalDue − Σ CARD_PAYMENT with that statementUid · Σ EXPENSE on the card after statementOn |
| Total balance | Σ balances of BANK, CASH, WALLET accounts not archived or deleted; cards never count |
| Pending · next 30 days | unpaid statements due ≤ today + 30, plus each recurring occurrence due ≤ today + 30 (not paused) |
| Left after pending | total balance − pending |
| Cash flow (6M) | per month In = Σ INCOME, Out = Σ EXPENSE; OPENING, TRANSFER and CARD_PAYMENT never count |
| "₹438 less than August by day 29" | Σ EXPENSE this month days 1…N − Σ EXPENSE last month days 1…min(N, its length) |
| "You kept 41% · 8 points more" | (In − Out) ÷ In for the month; points = this month − last month |
| Pace · month ends near | spent so far ÷ N × days in month |
| Where it went | Σ EXPENSE by category; top 4 named, the rest and Uncategorised as Other |
| Daily · Monthly · Yearly | EXPENSE on the day; Σ EXPENSE, Σ INCOME, savings and rate over the month / Jan 1…today |
| Recurring per month · over a year | weekly × 52 ÷ 12, yearly ÷ 12 · per month × 12 |

Calculators are pure Kotlin over DAO flows: unit-tested without Android, re-run when Room emits.

## Firestore

```
users/{uid}/finAccounts/{accountUid}
users/{uid}/finTransactions/{txUid}
users/{uid}/finCategories/{categoryUid}      your categories only
users/{uid}/finRecurring/{recurringUid}
users/{uid}/finStatements/{statementUid}
users/{uid}/finMerchants/{merchantUid}
users/{uid}/finSecrets/{accountUid}          cipher text only
financeKeys/{uid}                            KMS-wrapped data key; outside users/{uid}, functions only
```

- **Change tracking** as in `TopicSyncSchema`: `uid` + `dirty` columns, AFTER UPDATE / AFTER DELETE
  triggers gated by `sync_control.applying`, `sync_tombstones(kind, uid)`. Built-in categories are
  excluded from tracking.
- **Pull before push.** Pull order: categories → accounts → statements → recurring → merchants →
  transactions → secrets, so references resolve; a transaction whose account isn't here yet is
  held back and fetched again by id. Push: tombstones first, then the same order, batches of ≤ 500.
- **Last writer wins** per document on `updatedAt`. With balances derived only from entries, two
  phones only conflict when both edit the same transaction.
- **Multi-record changes** are one Room transaction and one push: Add account writes the account
  and its OPENING entry; Mark paid writes the occurrence and moves `nextDueOn`; Delete category
  moves its transactions to the chosen category, then soft-deletes it; Delete account is a soft
  delete, and recurring payments that used it are flagged "needs an account".
- **Live listeners** on every `fin*` collection while Finances is on screen; Settings › *Sync now*
  and onboarding's restore count include finance.
- **Rules:** the existing owner-only `users/{uid}/**` rule covers every path; `financeKeys` is
  outside it. No composite indexes — Firestore is only queried by `serverUpdatedAt`.

## How entries get in

Every path ends in the same use case (`AddTransaction`, `MarkPaid` or `PayCardBill`), so
validation, dedupe, merchant learning and sync behave the same whichever screen started it.

- **Paste SMS** (screens 01–09): clipboard read once on tap → classify (transaction · OTP · promo →
  07) → parse with the sender's bank pack, LLM fallback → `ParsedSms { direction, amount, last4,
  at, payee, ref, avlBal | avlLmt }` → match account by last4 (none → 08 / 09) → duplicate check:
  same amount + account within 10 min, or same ref (05) → merchant name and category, UPI-only
  payee asks once (06) → review 02 / 04 → commit `sms_…` + merchant → Undo snackbar (03). A
  "payment received" credit on a card is a CARD_PAYMENT against its open statement; other card
  credits are skipped. In 09, *Avl Lmt* pre-fills the new card's opening outstanding
  (limit − Avl Lmt).
- **Scan receipt** (01–06): ML Kit Document Scanner (edges, crop, Auto capture) → on-device text
  recognition → LLM structures merchant, date, total candidates, tax, items, last4 with confidence
  → unclear total (04) / unreadable (06) → card by last4 (same 08 / 09) → duplicate: same amount +
  account within 60 min → attach to it (05) → review 03 → commit with the `receipt` map.
- **Manual** Add expense / Add income: category suggested from the merchant's last pick, else the
  LLM; saving moves the balance by itself.
- **Recurring & statements engine**, on app open: a recurring with `nextDueOn ≤ today` and
  `autoMarkPaid` gets its `rec_…` occurrence and moves on; otherwise it shows in Pending (amber
  within 7 days, Alarm once overdue). Mark as paid writes the same occurrence; Skip only moves
  `nextDueOn`. Each credit card whose statement day passed gets an AUTO statement (`stmt_…`,
  totalDue = outstanding at statementOn, min due max(5 %, ₹200) until an SMS or edit sets it).
  Pay bill writes a CARD_PAYMENT with `statementUid`.
- **Reminders**: a daily WorkManager job posts "Netflix ₹649 due in 2 days". It never writes data.
- **Agent, extension, API**: `add_expense` etc. on the phone call the use cases; off the phone the
  `addTransaction` function writes Firestore and the phone pulls it live.

## Cloud Functions (asia-south1, same auth as `addLink`)

| Function | Request → response |
|---|---|
| `addTransaction` | `{ type, amountMinor, currency?, occurredAt?, account: uid \| last4 \| name, category?: uid \| name, merchant?, note? }` → `{ txUid }` |
| `listFinance` | → `{ accounts: [uid, name, kind, last4], categories: [uid, name, kind] }` |
| `financeKey` | signed in + App Check → this user's data key, created and KMS-wrapped on first call |
| `/api/…` | `addTransaction` and `listFinance` over personal API keys, like the Links endpoints |

The data key is cached on the phone in Android Keystore-backed storage; a new phone fetches it
after sign-in.

## Agent tools (`:core-agent`)

`add_expense`, `add_income` and `mark_paid` (`RiskLevel.MEDIUM`, confirm before saving);
`spending_summary { period, category? }`, `find_transactions { query, from?, to? }`,
`list_pending` (`LOW`). `analyze_statement` can later feed `add_expense` for statement import.

## Phases

1. **`:finance` module** — entities, DAOs, migrations, seeded built-in categories, OPENING-based
   balances and every calculator in *Derived values*, with unit tests.
2. **Screens on Room** — dashboard, expenses (daily / monthly / yearly), monthly report, accounts,
   cards, add / edit account, add expense / income, categories.
3. **Recurring & statement engine**, Pending payments, Mark as paid, Pay card bill, reminders.
4. **FinanceSync** — Firestore docs, live listeners, onboarding restore.
5. **Paste SMS** (bank packs + LLM fallback) and **Scan receipt** (ML Kit).
6. **Secrets** (Tink + `financeKey`), `addTransaction` / `listFinance` + API, agent tools.
