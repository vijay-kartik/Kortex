# Private categories — plan

Some spending shouldn't show to whoever is holding the phone: gifts, a doctor, money sent to
family. The user marks a **category** private. Every entry in a private category is then hidden
from the Finances screens. They show only after the user turns on **Show private categories** in
Settings, which asks for the phone's lock. Nothing on the Finances screens hints that anything is
hidden: no lock icon, no chip, no counts. Builds on [FINANCE_PLAN.md](FINANCE_PLAN.md) and
[SMS_AUTO_PLAN.md](SMS_AUTO_PLAN.md).

## Status

Plan only. Nothing is built. Revised 2026-10-12 after review:
- privacy belongs to categories, not to single entries;
- revealing happens from Settings, not from a lock chip on the Finances screens;
- private categories can't have a budget (Decision 4);
- no category is private by default, and categories are marked private only from Settings
  (Decisions 1 and 6).

## Problem

The Finances tab shows everything to anyone who has the phone in their hand and the app open. App
lock doesn't help here: once the phone is handed over, Kortex is already unlocked. The dashboard
insights, Where it went, Expenses › Daily, budgets and the "Saved ₹420 at …" notifications all
name merchants, categories and amounts.

**Who we're protecting against:** someone looking at the screen of an unlocked phone, briefly, such
as family, a friend or a colleague. **Who we're not protecting against:** someone with root, ADB, a
backup, the Firebase console or the phone's Messages app (the bank SMS is still sitting there). This
is a privacy screen, not a vault. The UI copy must not promise more than that.

## Goals

- Mark a category private, from Settings. Its past and future entries are hidden at once, whether they came in
  by hand, from an SMS, from a receipt, as a recurring payment or through the agent.
- While hidden (the default), the Finances screens show no trace of a private category: not its
  name, its entries, its amounts, or that anything is missing.
- The only way to see them: Settings › Finances › Show private categories, after fingerprint, face
  or PIN.

## Non-goals (v1)

- Marking single entries or merchants private. An entry is private because of its category,
  nothing else.
- Encrypting private rows at rest or in Firestore.
- Private accounts or cards, where the account itself and its balance are hidden.
- A separate Kortex PIN. The phone's lock is the only key, as for full card numbers today
  (`ScreenLock`).

## Decisions

1. **Nothing is private until the user says so.** Every category, built-in or the user's own,
   starts out visible, and Kortex never suggests or picks one. A category becomes private only when
   the user marks it in Settings. An entry is private if and only if its category is private.
   Nothing is stored on the entry; privacy is worked out from the category each time. So:
   - making a category private hides its old entries too, and making it visible shows them again;
   - moving an entry to a private category hides it;
   - Uncategorised entries are never private.
2. **Hidden means the app behaves as if those entries weren't there, except for balances.**
   Spending, income, cash flow, pace, insights, Where it went, Expenses, the Monthly report and
   Pending all leave them out. Budgets never include them anyway (Decision 4). Account balances, card outstanding and statements keep
   counting them, because those must match the bank, and a balance is the sum of *all* entries
   (FINANCE_PLAN › Decisions). Leaving them out of balances would show wrong balances, so it's
   rejected.
3. **A private category is hidden as a category too.** Its name gives it away ("Therapy"). While
   hidden it's missing from the Categories screen, every category picker and Where it went.
4. **Private categories never have a budget.** `Budgets.status` already leaves out spend in
   categories with no budget, and the overall budget is the sum of the category budgets. So budgets
   read exactly the same whether private categories are shown or hidden, and nothing has to be
   filtered there.
   - The Budget field isn't on a private category's form, and `SetBudget` turns one down
     (`BudgetSaveResult.Private`).
   - Making a budgeted category private removes its budget, after a confirm: "Gifts has a ₹3,000
     monthly budget. Private categories can't have one, so it will be removed." The budget is
     cleared in the same database transaction that makes the category private.
   - Making it visible again doesn't bring the budget back.
   - If sync ever brings a budget for a private category (one phone set a budget while the other
     made it private), `Budgets.status` ignores it, and the next `setCategoryPrivate` cleanup
     deletes it.
5. **Nothing on the Finances screens shows that private categories exist.** There's no icon, chip
   or "N hidden" anywhere. If every entry in a period is private, the period shows the normal empty
   state.
6. **Everything about privacy lives in Settings: Tools & Settings › FINANCES › Private
   categories.** Marking, unmarking and showing all happen there. The Finances screens, the
   category form included, have no privacy controls at all.
   - Marking a category private needs no lock: hiding something is always allowed.
   - "Show private categories" is off by default. Turning it on asks for the screen lock with
     `BIOMETRIC_WEAK | DEVICE_CREDENTIAL`, like `ScreenLock`. Turning it off needs nothing.
   - With no screen lock on the phone, the switch can't be turned on ("Set a screen lock to see
     private categories"). Categories can still be marked private.
   - This is separate from App lock: someone you've handed the unlocked app to still can't turn it
     on without your fingerprint or PIN.
7. **The switch turns itself off when Kortex goes to the background,** using the same
   process-lifecycle hook as `AppLock`, and on every cold start. It's never saved to disk. That way
   it can't be left on by mistake and found later. (If you'd rather it stay on until you turn it
   off, that's a one-line change. Say so.)
8. **While shown, entries look normal.** No lock glyphs, since there's no icon language for this
   feature anywhere else. The Finances screen is `FLAG_SECURE` while shown, so screenshots and the
   recents preview stay blank whatever App lock's Hide in recents is set to.
9. **Which categories are private is stored in its own synced table** (`private_categories`, keyed
   by `categoryUid`), not as a column on Category. Built-in categories are never synced
   (`FinanceSyncSchema` skips `builtIn = 1`), and Salary is a likely one to hide. A separate table
   lets Food or Salary be private on both phones.
10. **The agent never reads private entries,** even while they're shown. Chat history stays after
   the switch goes off. `add_entry` still saves into a private category if it's named, and replies
   "Saved." without amount or merchant.
11. **Writes always see everything.** Duplicate detection, the recurring and statement engine, and
    the SMS inbox read the repository directly. An SMS for a payment already saved in a private
    category is still recognised as a duplicate.

## UX

The new screens need Figma frames first, in a new board on **Kortex - Finances**.

- **Settings › FINANCES › Private categories** opens one screen. With nothing private yet
  (always the case to begin with), it shows:
  - one line of explanation: "Entries in a private category are hidden from Finances unless you
    show them here.";
  - **Make a category private**, which opens a sheet listing every visible category (expense and
    income), built-in ones included. Picking one:
    - first, if it has a budget, confirms that the budget will be removed (Decision 4);
    - marks it private straight away, with no lock;
    - shows the snackbar "Gifts is now private";
  - the **Show private categories** switch (asks for the screen lock).
- **While Show private categories is on,** the same screen also lists the private categories by
  name, each with **Make visible**. While it's off, the screen shows no names and no counts, just
  the line, the button and the switch. So making a category visible again always goes through the
  lock.
- **The category form doesn't change,** except that a private category's form (reachable only
  while shown) has no Budget field.
- **Adding an entry to a private category by hand.** The picker doesn't list private categories
  while hidden, so turn on Show private categories first. Entries that get their category on their
  own still land in the private category without it: SMS and receipts remember a merchant's
  category, and recurring payments have one. After saving, they quietly disappear from view.
- **Saved-entry snackbar:** while hidden, a private entry's snackbar reads "Saved" with Undo, and
  no merchant or amount.

## Every place an entry can show, and what it does while hidden

| Surface | While hidden |
|---|---|
| Dashboard: month net, flow chart, insights, pace, Where it went | Computed without private entries |
| Dashboard: Budgets card, budget insight | Unaffected: private categories have no budget, so they're never in it, shown or hidden |
| Dashboard / Accounts: total balance, account balances | **Includes** private entries (Decision 2) |
| Accounts: "Last transaction 2 days ago" | Counts only visible entries |
| Cards: outstanding, statement, unpaid | Includes them (bank figures) |
| Expenses Daily / Monthly / Yearly, Monthly report | Left out |
| Categories screen, category pickers | Private categories left out |
| Pending payments, Recurring list | Recurring payments in a private category are left out |
| Payment reminders | For a private category: "A payment is due tomorrow" |
| Auto-saved SMS notification | For a private category: "Saved an entry" with no amount, merchant or account. Undo still works. `VISIBILITY_PRIVATE` on the channel |
| Agent read tools | Always left out (Decision 10) |
| SMS To review | Unchanged: it's the raw SMS, which is in Messages anyway |
| Firestore / Mac | `private_categories` syncs. Kortex for Mac must filter the same way (follow-up in that repo) |

## Data

- New table `private_categories(categoryUid PRIMARY KEY, createdAt, updatedAt, dirty)`. Room
  migration 5 → 6.
  - Sync: tracked as `KIND_PRIVATE_CATEGORY = "finPrivateCategory"`, document id = categoryUid,
    so two phones marking the same category make one document. Making a category visible again
    deletes the row (a tombstone, as for budgets).
  - Deleting a category deletes its row too. Its entries move to the chosen category, and they're
    private only if that one is.
- `setCategoryPrivate(uid, true)` deletes the category's `budgets` row in the same database
  transaction (that row's tombstone syncs the removal).
- No change to `transactions` or `recurring`.

## Architecture

- **`PrivateView` (`:finance` domain)** owns:
  - `shown: StateFlow<Boolean>`, `show()`, `hide()` and `onAppBackgrounded()`;
  - in memory only. `KortexApp` feeds it the process lifecycle, next to `AppLock`.

  Only the Settings switch calls `show()`, after the prompt succeeds.
- **One filtering point.** `ObserveFinance` combines the repository with
  `observePrivateCategories()` and `PrivateView.shown`. It emits a `FinanceSnapshot` with:
  - `transactions`: the entries to show (all of them when shown, else those whose category isn't
    private);
  - `allTransactions`: always all of them, for `Balances`, `Statements` and card position only;
  - `categories` and `recurring`: private ones dropped while hidden.

  Every screen builder already takes a `FinanceSnapshot`, so most screens need no change. The audit
  covers the `Balances.*` / `Statements.*` / `cardPosition` call sites in `AccountsContract`, plus
  `DashboardUi`'s total, which switch to `allTransactions`. A unit test pins which calculators get
  which list.
- **`FinanceAgent`** builds its snapshot with `shown = false`, always.
- **Notifications** (`BankSmsNotifications`, `FinanceReminders`) check the entry's or recurring
  payment's category against the private set before writing text.
- **Budgets:** `SetBudget` returns `BudgetSaveResult.Private` for a private category, and
  `Budgets.status` takes the private set and skips budgets on those categories. That's a guard for
  sync races only; normally there are none.
- **Prompt:** reuses `ScreenLock.confirm`, with a `subtitle` parameter added for "Private categories
  show after your screen lock".

## Phases

1. **Data.**
   - `private_categories` table, migration 5 → 6 with a migration test;
   - sync schema, `FinanceDocs` and `FinanceSyncMerge` in `:sync`;
   - repository: `observePrivateCategories()` and `setCategoryPrivate(uid, Boolean)`, which also
     clears the budget;
   - `SetBudget` refusing private categories and the `Budgets.status` guard, with tests.
2. **Hide and show.**
   - `PrivateView` and the split `FinanceSnapshot`;
   - the balance call-site audit;
   - the Settings switch with its prompt, auto-off on background, and `FLAG_SECURE` while shown.

   Tests: `DashboardUi`, `ExpensesUi`, `AccountsUi`, `CardsUi` and `PendingUi` with private
   fixtures. Hidden must equal "as if absent", with balances unchanged.
3. **Mark.** The Settings › Private categories screen:
   - Make a category private (category sheet, remove-budget confirm, snackbar);
   - the list of private categories with Make visible, shown only while private categories are
     shown;
   - no Budget field on a private category's form.
4. **Notifications and agent.** Generic SMS and reminder text, `VISIBILITY_PRIVATE`, the generic
   saved-entry snackbar, and agent read tools leaving private categories out. Tests in
   `FinanceAgentTest` and `FinanceRemindersTest`.

Phase 1 can land alone. Phases 2 and 3 should land together: marking without hiding, or hiding
without a way to mark, makes no sense.

## Acceptance

- On a fresh install, and after sync on a new phone, no category is private and Settings ›
  Private categories lists none.
- The user marks Gifts private from Settings, and a ₹2,000 Gifts entry was made today.
- With the switch off:
  - Dashboard month spend, insights, pace, Where it went and Expenses › Daily match the same data
    without that entry;
  - total balance and the account's balance are unchanged;
  - the Budgets card is the same as with the switch on;
  - Gifts isn't listed anywhere in Finances;
  - nothing on any Finances screen hints that something is hidden.
- Turning the switch on after fingerprint shows the entry and Gifts everywhere, and totals include
  it. Leaving the app and coming back turns the switch off again. A screenshot while it's on is
  blocked.
- Making Gifts private hides its entries from last year too. If Gifts had a budget, the confirm
  shows first, and afterwards the budget is gone on both phones.
- A private category's form has no Budget field, and `SetBudget` on it returns `Private`.
- An SMS whose merchant is remembered as Gifts is saved into Gifts. Its notification reads "Saved
  an entry", and Undo removes it.
- Asking the agent "what did I spend on gifts?" never mentions the entry.
- A second phone, after sync, hides Gifts too.

## Open questions

1. **Income categories.** Should Salary and other income categories be allowed to be private?
   The current plan allows it: hiding income from family is a likely use.
2. **Mac.** Kortex for Mac needs the same filter before it shows entries. Is that a separate task
   in that repo?
