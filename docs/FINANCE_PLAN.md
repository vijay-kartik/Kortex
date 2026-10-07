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
lists and the extension never read it. Locally the `secrets` table (finance.db v2).

**Budget** (`finBudgets/{categoryUid}`) — `amountMinor`. See *Budgets*. Locally the `budgets` table
(finance.db v5), keyed by the category's uid.

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
| budget | the category's uid |

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
users/{uid}/finBudgets/{categoryUid}         built-in categories' budgets too
financeKeys/{uid}                            KMS-wrapped data key; outside users/{uid}, functions only
```

- **Change tracking** as in `TopicSyncSchema`: `uid` + `dirty` columns, AFTER UPDATE / AFTER DELETE
  triggers gated by `sync_control.applying`, `sync_tombstones(kind, uid)`. Built-in categories are
  excluded from tracking; their budgets are not.
- **Pull before push.** Pull order: categories → budgets → accounts → statements → recurring →
  merchants → transactions → secrets. Nothing is held back: rows are keyed by document id with no foreign
  keys, so a transaction pulled before its account simply names the account's uid until it
  lands. Push: tombstones first, then the same order, batches of ≤ 500.
- **Last writer wins** per document on `updatedAt`. With balances derived only from entries, two
  phones only conflict when both edit the same transaction.
- **Multi-record changes** are one Room transaction and one push: Add account writes the account
  and its OPENING entry; Mark paid writes the occurrence and moves `nextDueOn`; Delete category
  moves its transactions to the chosen category, then soft-deletes it; Delete account is a soft
  delete, and recurring payments that used it are flagged "needs an account".
- **Live listeners** on every `fin*` collection while the app is on screen, as for links and
  topics; Settings › *Sync now* and onboarding's restore include finance.
- **Rules:** the existing owner-only `users/{uid}/**` rule covers every path; `financeKeys` is
  outside it. No composite indexes — Firestore is only queried by `serverUpdatedAt`.

## Budgets

Decided in #14; storage and sync in #16.

- **One standing monthly amount per expense category**, built-in or yours. No row means the
  category isn't budgeted. Income categories can't have one (`SetBudget` refuses them).
- **Stored in the separate `finBudgets` collection**, keyed by the category's uid, so built-in
  categories stay unsynced and read-only while their budgets sync like any other record. Clearing
  a budget deletes the row and pushes a tombstone; deleting your category deletes its budget too.
- **The overall monthly budget is the sum of the category budgets.** If no category has a budget,
  the month is not budgeted.
- **Notices go through the existing daily reminder channel** (the Reminders job above).
- **The agent gets a separate `budget_status` tool.** It's in #21 (see *Agent tools*).

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

## Agent tools (`:app`, on `:finance`'s `FinanceAgent`)

`add_expense`, `add_income` and `mark_paid` (`RiskLevel.MEDIUM`, confirm before saving);
`spending_summary { period, category? }`, `find_transactions { query, from?, to? }`,
`list_pending`, `budget_status { category? }` (`LOW`). `analyze_statement` can later feed `add_expense` for statement import.

## Phases

1. **`:finance` module** — entities, DAOs, migrations, seeded built-in categories, OPENING-based
   balances and every calculator in *Derived values*, with unit tests. *Done:* domain models,
   `FinanceIds`, calculators (`Balances`, `Statements`, `RecurringSchedule`, `Pending`,
   `Spending`), use cases (`AddAccount`, `AddTransaction`, `AddCategory`, `UpdateCategory`,
   `DeleteCategory`, `ObserveFinance`), Room `FinanceDatabase` v1 with change-tracking triggers,
   `RoomFinanceRepository` and `FinanceModule`. Unit tests use the Figma screens' numbers.
2. **Screens on Room** — dashboard, expenses (daily / monthly / yearly), monthly report, accounts,
   cards, add / edit account, add expense / income, categories. *Done:* a Finances tab under
   My Info, whose floating bottom bar switches between Dashboard, Expenses, Accounts and Cards.
   The Finance sheets and pushed screens open as one `Overlay.Finance` stack, saved across
   process death through `FinanceRoute.encode` / `decode`. The screen state comes from pure
   builders (`DashboardUi`, `ExpensesUi`, `MonthlyReportUi`, `AccountsUi`, `CardsUi`,
   `CategoriesUi`), and each has tests. A saved entry shows a snackbar with Undo
   (`FinanceNotices`). `UpdateAccount`, `DeleteAccount` and `DeleteTransaction` were added.
   Still to come: the Pending tile opens Pending payments in Phase 3, and Paste SMS and Scan
   receipt arrive in Phase 5. Export PDF on the Monthly report hasn't been started. Card and
   account numbers keep only their last 4 digits until Phase 6 encrypts the full number.
3. **Recurring & statement engine**, Pending payments, Mark as paid, Pay card bill, reminders.
   *Done:*
   - **Engine.** `RunFinanceEngine` runs each time the Finances tab opens:
     - it writes AUTO statements, and never replaces one an SMS or an edit already set;
     - it records auto-debits on their due day;
     - it steps a recurring payment past occurrences that another phone already paid.
   - **Use cases.** `MarkPaid`, `SkipOccurrence` and `UndoOccurrence` save the occurrence and the
     next due date together, through `saveRecurringChange`. `SaveRecurring` keeps a 31st anchor
     through short months. Also added: `DeleteRecurring` and `PayCardBill`, and `AddTransaction`
     now validates and saves in separate steps (`prepare`, then save).
   - **Pending.** Only each card's latest statement counts, since it already includes whatever the
     previous one left unpaid.
   - **Screens.** Pending payments (filters, swipe to mark paid, Undo), Recurring payments, Add /
     Edit recurring (pause, delete), Mark as paid / Skip, and Pay card bill (full, minimum or
     another amount). The Dashboard's Pending tile and the Credit Cards › Pay bill button open them.
   - **Reminders.** `FinanceReminderWorker` is a WorkManager job that runs daily at about 9:00.
     - It reminds you about recurring payments the chosen number of days before they're due.
     - It reminds you about unpaid card bills 3 days before they're due and again on the day.
     - Tapping a reminder opens Pending payments.
     - The app asks for notification permission when a payment is saved with a reminder.
     - `remindDaysBefore` = −1 means no reminder.
4. **FinanceSync** — Firestore docs, live listeners, onboarding restore. *Done:*
   - **Local side.** `FinanceSyncDao` in `:finance` returns the dirty rows and the tombstones, and
     marks a row pushed only when its change count is still the one it read. It applies pulled
     rows with the triggers off; last writer wins (`decideMerge`), and an unpushed delete counts
     as a dirty version.
   - **Categories.** A category name that both phones added becomes "Name (2)" and goes back up.
     Built-in uids are never applied.
   - **Saves keep the change count.** Repository saves keep the row's stored count (`saveAccount`
     and the others) instead of resetting it to 1, so an edit made while a push is in flight
     can't be marked as pushed.
   - **Remote side.** `FinanceDocs` and `FinanceSync` live in `:sync`. There are six collections,
     pulled and pushed in the order above. Documents carry every field (`deleted`, `updatedAt`,
     `serverUpdatedAt`). A document with an enum value this app doesn't know is skipped rather
     than crashing.
   - **App.** `CloudSync` counts finance in sync progress, in live listeners, in push-on-change,
     and in the other-account and keep / remove choices. Onboarding's restore has a Finances row
     and reports "N accounts · M entries".
5. **Paste SMS** (bank packs + LLM fallback) and **Scan receipt** (ML Kit). *Done:*
   - **Reading SMS.** `SmsParser` in `domain/read` reads amounts, debit or credit, the last 4
     digits (card or account), date and time, the payee or UPI id, the reference, and Avl Bal /
     Avl Lmt. It records where each field was found, so the review can underline it.
     - The patterns cover the common formats of the major Indian banks. One shared set replaces
       the per-bank "packs" in the plan.
     - OTPs and offers are turned away (07) and never sent anywhere.
   - **Reading receipts.** `ReceiptParser` finds the total and the other amounts it could be, the
     GST, the items, the date and the card's last 4 digits.
   - **The model.** `FinanceReader` is the LLM port, implemented in `:app` as `LlmFinanceReader`
     on the model chosen in Settings. Everything it's sent goes through `maskForModel` first.
     - `ReadSms` and `ReadReceipt` ask the model only for what the patterns missed.
     - `SuggestMerchant` checks remembered merchants first, then asks the model for a tidy name
       and a category, and otherwise just tidies the name.
   - **Matching.** `EntryMatching` matches the last 4 digits to an account and spots
     duplicates: the same reference, or the same amount on the same account within 10 minutes
     for an SMS and 60 minutes for a receipt.
   - **Saving.**
     - A UPI id named once is remembered by the id (`TransactionDraft.payeeKey`).
     - The same SMS saved twice is one entry (`sms_…`).
     - A card's "payment received" becomes a card payment; any other card credit is turned away,
       since refunds aren't supported.
   - **Paste SMS screens (01–09).**
     - The clipboard is read once, when Paste SMS opens.
     - Read parts of the SMS are underlined. The review covers duplicates (Skip or Add anyway),
       a UPI id with no name yet ("Who was this for?") and a card the phone doesn't know (pick
       an account, or Add card filled in from the SMS).
     - A card added from an SMS works out what's owed today as the limit minus Avl Lmt.
     - Sharing a bank SMS to Kortex from Messages opens straight at its review.
   - **Scan receipt screens (03–06).** Google's document scanner (ML Kit, up to 2 pages, gallery
     allowed) and on-device text recognition; the text is rebuilt into rows by position.
     - The flow covers choosing a total, attaching the receipt to an existing expense, and a
       photo that couldn't be read (Retake).
     - The photo is kept in `files/receipts/` only when "Keep the receipt photo" is checked.
   - **Entry points.** Paste SMS and Scan Receipt tiles sit at the top of Add Expense.
6. **Secrets** (Tink + `financeKey`), `addTransaction` / `listFinance` + API, agent tools. *Done:*
   - **Full numbers.** Add / Edit account take the full number (8–19 digits), and the account keeps
     only its last 4 digits.
     - `KeystoreSecretBox` seals the number with Tink AES-256-GCM, using the account uid as
       associated data, under the user's data key. The key is fetched once from `financeKey`
       (`FinanceKeyClient` in `:sync`) and cached on the phone, wrapped by an Android Keystore key.
     - Room v2 adds the `secrets` table (migration 1 → 2), with change tracking. It syncs as
       `finSecrets`: `cipherText`, `keyVersion` and nothing else. Deleting an account deletes its
       secret too.
     - Without a key (signed out, or offline the first time) the account still saves, and the
       app says the number wasn't kept.
   - **Reveal.** Credit Cards › Card details and Edit account have a Show button. It asks for the
     phone's screen lock (biometrics or PIN), shows the number grouped by 4 for 30 seconds, then
     hides it. A phone with no screen lock can't reveal numbers.
   - **Functions** (`functions/src/finance.ts`, `financeDocs.ts`, `financeKey.ts`):
     - `addTransaction` and `listFinance` are callables and are also on `/api/…` with an API key.
       An entry's account is given by uid, last 4 digits or name, and its category by uid or name.
       Its day is taken in `timeZone`, which defaults to Asia/Kolkata. A remembered merchant uses
       the same `mer_` id as on the phone.
     - `financeKey` is callable only, with App Check enforced. It creates a 32-byte key per user,
       wraps it with Cloud KMS bound to the user's uid, and stores it in `financeKeys/{uid}`.
       When two phones ask at once, both get the same key.
     - Node tests cover parsing, name matching, merchant ids and the document fields.
   - **App Check.** Debug builds use the debug provider; release builds use Play Integrity.
   - **Agent tools.** `FinanceAgent` lives in `:finance` (its logic is tested), and the tools are
     defined in `:app` (`financeTools`). They're there rather than in `:core-agent`, which doesn't
     depend on features.
     - `add_expense`, `add_income` and `mark_paid` are MEDIUM risk, so they're confirmed before
       anything is saved.
     - `spending_summary`, `find_transactions`, `list_pending` and `budget_status` are LOW risk.
   - **Setup before deploying:**
     1. Create a Cloud KMS key, e.g. `projects/{project}/locations/asia-south1/keyRings/kortex/cryptoKeys/finance-keys`.
     2. Give the functions' service account *Cloud KMS CryptoKey Encrypter/Decrypter* on that key.
     3. Set `FINANCE_KMS_KEY` to the key's name (in `functions/.env`, or when deploy prompts for it).
     4. Register the Android app in App Check with Play Integrity, and add the debug token a debug
        build logs on its first run.
