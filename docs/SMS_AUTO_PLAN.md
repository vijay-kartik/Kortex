# Automatic SMS entries — implementation plan

Bank SMS that arrive on the phone become entries without the user pasting them: the clear ones
are saved on their own with an Undo, the rest wait in a "To review" list that opens the existing
Paste SMS review. Builds on [FINANCE_PLAN.md](FINANCE_PLAN.md) › How entries get in (Paste SMS)
and phase 4 (FinanceSync).

## Status

All 9 phases are written on `feature/sms-auto-entries` (not yet built or run). Phase 9, reading
earlier SMS, was added after the first 8 at the user's request. Decision 1 is
settled: **A, `RECEIVE_SMS`**, because the app won't be published on Play.

Phase 1 notes:

- `PrepareSmsEntry` (`domain/usecase/PrepareSmsEntry.kt`) returns an `SmsEntryPlan`: account,
  type, merchant guess, date, time in millis, the SMS's uid and any duplicate.
  `SmsEntryViewModel.review` now only copies a plan into its state.
- `SmsEntryType` moved from the screen's contract to the domain, with its `transactionType`.
- `Clock.millisAt(day, time)` replaces the ViewModel's private `occurredAt`.

Phase 2 notes:

- `ReadSms(text, mode)`; `SmsMode.PASTED` is the default, so Paste SMS is unchanged.
- **Differs from Rules:** `SenderGate` also accepts the 6-character id without its prefix
  (`HDFCBK`), because some phones show it that way. The id must contain a letter, so numeric short
  codes don't pass.
- `SmsResult.NotAPayment` gained `certain`. It's false when only the LLM's silence says "not a
  payment", which is also what no key or no network looks like. Paste SMS ignores it.

Phase 3 notes:

- **Differs from Data:** no `attempts` column and no retries. `FinanceReader` returns null both
  for "not a payment" and for "unreachable", so retrying couldn't tell them apart. An SMS nothing
  could read (`certain = false`) goes straight to review as `UNREADABLE`. Opening it there reads
  it again, by which time the network is usually back.
- Two more review reasons: `UNREADABLE` (above) and `NOT_SAVED` (`AddTransaction` turned the
  read entry down, for example the account was archived in the meantime).
- An SMS whose entry already exists with its uid (it was pasted first) is marked `SAVED`
  against that entry, with no notification.
- "Exactly one account" counts unarchived accounts with those last 4 digits. A card and a bank
  account sharing them go to review even though `accountFor` would pick one.
- `LATE` compares the SMS's date with the day it was received, not with today.
- Retention runs at the end of each `ProcessSmsInbox` run. It deletes handled rows older than
  30 days; `REVIEW` and `PENDING` rows stay however old they are.
- `ProcessSmsInbox(autoSave)` takes the "Save clear ones without asking" switch as a
  parameter, so the domain doesn't read settings.

Phase 4 notes:

- **Where things live.** Only the receiver is in `:app` (`BankSmsReceiver`, root package, manifest
  `RECEIVE_SMS` plus a receiver guarded by `BROADCAST_SMS`). The rest is in `:finance`'s `sms/`
  package, next to the reminders job, because `:app` doesn't depend on WorkManager:
  - `BankSms.receive` parses the broadcast, joining a long SMS's parts per sender, calls
    `ReceiveSms`, and enqueues `BankSmsWorker`. The receiver keeps the process alive with
    `goAsync` until the rows are stored.
  - `BankSmsWorker` runs `ProcessSmsInbox`. It's expedited, with
    `RUN_AS_NON_EXPEDITED_WORK_REQUEST` when out of quota, and unique work with
    `APPEND_OR_REPLACE`, so an SMS stored while a run is going gets its own run afterwards.
    Errors retry up to 3 times; the rows stay `PENDING` either way.
  - Hilt dependencies reach both through `BankSmsEntryPoint`, as for the reminders job.
- **Settings arrived early.** Phase 8's two switches had to exist for phase 4 to be usable. They
  live in `BankSmsStore` (`:finance`, SharedPreferences, since the receiver reads them on the main
  thread) and Tools & Settings › FINANCES (`BankSmsSection`). Turning the feature on asks for
  `RECEIVE_SMS`. If the user turns that down for good, or revokes it later, the row says so and
  links to the app's Android settings. Phase 8 still adds the waiting count and "Clear SMS inbox".
- `KortexApp` calls `BankSms.catchUp`, which runs the worker if the feature is on, for rows a
  stopped job left `PENDING`.
- The received time is when the phone got the broadcast, not the SMS centre's timestamp, so
  `LATE` compares against the day the phone actually received it.
- Before Android 12, an expedited job runs as a foreground service. It shows a silent
  minimum-importance notification, "Reading a bank SMS", on its own channel.

Phase 5 notes:

- `BankSmsNotifications` (`:finance` `sms/`), on a "Bank SMS" channel:
  - One notification per entry saved on its own: "Saved ₹420.00 at Swiggy" · "HDFC ••4471 ·
    Food" (or "No category"). `AutoSaved` gained `categoryName` for this.
  - One "N bank SMS to review" notification, replaced as the count changes. It counts
    everything waiting, but is only posted again when a run adds new ones. Tapping it opens To
    review, which cancels it.
- **Undo** sends a broadcast to `BankSmsActionReceiver` (`:app` root package, not exported, so
  only the app's own notifications reach it). It calls `ResolveInboxSms.undoAutoSaved`, which
  deletes the entry and marks the SMS `DISMISSED`, then closes the notification. The
  `SMS_RECEIVED` receiver couldn't take this job: it requires `BROADCAST_SMS` from the sender,
  which the app itself doesn't hold.
- **Differs from Rules:** tapping a saved notification just opens the app; there's no Finance
  route for Expenses. A UPI id nobody has named yet is saved with the UPI id as merchant, and the
  notification doesn't offer "Who was this for?", because there's no edit-entry screen to name it
  in.
- Notification permission (Android 13+) is asked for together with `RECEIVE_SMS` when the
  feature is turned on. Turning down only that one hides the notifications; entries are still
  added.

Phase 6 notes:

- **Its own screen:** `FinanceRoute.SmsReview`, "Bank SMS to review" (`ui/read/SmsReview*`).
  Rows show the sender, the reason in words, the time it arrived and the first 3 lines of the
  text, newest first. The rows come from the pure `SmsReviewUi.build`, which is tested.
- **Tap** opens Paste SMS on that text. `FinanceRoute.PasteSms` gained `inboxId`, and saving
  there marks the SMS `SAVED` against the new entry. So does landing on `AlreadySaved`: the SMS's
  own entry already exists. Skip leaves it waiting.
- **Swipe** dismisses it, with Undo on the snackbar (`FinanceUndo.RestoreInboxSms` puts the row
  back as it was, text included).
- **Ways in:** the review notification, and a "N bank SMS to review" card at the top of the
  Dashboard while any are waiting. The card isn't shown before the first account exists; the
  Dashboard shows only the first-account prompt then.

Phase 7 notes:

- `BackgroundPush` (`:app` `data/sync/`) queues `BackgroundPushWorker`, which calls
  `CloudSync.pushPending()`. It only runs with a connection, retries with exponential backoff from
  30 s, and gives up after 8 tries; the next app open syncs anyway. Signed out, it does nothing.
  `:app` gained the WorkManager dependency for it; `:sync` still has none.
- **Unique work with `KEEP`, not a chain:** one queued push sends every unpushed change, whenever
  it was made, so a second request while one waits adds nothing.
- **Three things ask for it:**
  - `LiveSync`, when its push on leaving the screen fails. This also fixes the gap from
    before this plan, where an offline edit waited for the next launch.
  - `BankSmsWorker`, after a run that saved entries.
  - Undo on a notification, since the deleted entry may already be in the cloud.
- `:finance` reaches it through a new port, `BackgroundSync` (`domain/port/`), bound in
  `DataModule`, so `:finance` still knows nothing of sync.

Phase 8 notes:

- Tools & Settings › FINANCES gained an "SMS inbox" row. It's shown whenever any SMS are kept,
  even with the feature off, since what was kept stays until cleared. It says how many are
  waiting for review and has Clear, with a confirmation. Clearing removes every row, waiting ones
  included, keeps the entries they became, and closes the review notification.
- `SmsInboxRepository` gained `observeCount()` and `clear()`.
- Settings can't open To review: the Finance screens open from the Finances tab, not from
  Settings. So the row names where to find it ("Finances › Dashboard").

Phase 9 notes (earlier SMS):

- **Turning the feature on asks where to start:** "New SMS only" or "Earlier SMS too, since
  <day>". The day defaults to 30 days back and can go at most a year back. Once it's on,
  "Add earlier SMS" in the same section does the same for any day. Either way, earlier SMS need
  `READ_SMS`, which is only asked for then. Declining it still turns the feature on for new SMS,
  and the row says it was declined.
- **Same inbox, kept apart.** `ImportSms` reads `StoredSms` from the phone's SMS store
  (`Telephony.Sms.Inbox`, since the day) through the same `SenderGate`, as rows with
  `imported = true` (`finance.db` 3 → 4). Their received time is when the phone got them, so
  `LATE` works as for live ones. An SMS already kept, live or from an earlier import, isn't added
  twice.
- **Read in its own runs.** `BankSmsImportWorker` (unique work `bank-sms-import`):
  - Its first run reads the phone's store into the inbox.
  - Each later run reads 20 (`ProcessSmsInbox(imported = true, limit)`) and queues the next.
    That keeps every run well inside a job's 10-minute limit, even with model calls.
  - Live runs never read imported rows, and the import never reads live ones, so the two run
    side by side without double counting.
  - `BankSms.catchUp` resumes an import a stopped job left behind.
- **Told once.** No notification per entry. A silent progress notification ("12 of 85 read")
  while it runs, then one summary, "Added 42 entries from earlier SMS · 7 to review", which
  opens To review when any wait there. The cloud push runs once at the end.
- **Two more rules, from importing:**
  - `BEFORE_ACCOUNT` (all SMS): one dated before its account was added goes to review. Balances
    start from the opening balance entered then, which already counts that money. For a linked
    debit card, the bank account it spends from is the one that counts.
  - Imported only: an entry with the same amount, account and type on the same day sends it to
    review as `DUPLICATE`. Entries typed in by hand carry the time they were typed, not the time
    of payment, so the usual 10-minute window would miss them.
- Imported rows that are handled and more than 30 days old (by when they arrived) are deleted
  at the end of the run that handles them. Only their SMS text goes; the entries stay.

## Where we are

- **Paste SMS** works end to end: `SmsEntryViewModel` takes clipboard or shared text →
  `ReadSms` → review → `AddTransaction` with `source = SMS` and
  `uid = FinanceIds.smsTransaction("", text)`.
- **The review logic lives in the ViewModel.** `SmsEntryViewModel.review` matches the account
  (`EntryMatching.accountFor`), decides the entry type (expense / income / card payment / card
  refund), asks `SuggestMerchant`, and runs the duplicate check (`EntryMatching.duplicateOf`).
  A background job can't reuse any of that as it stands.
- **`ReadSms` is Jev-first:** every pasted SMS goes to the decision model, masked, before the
  patterns read it. Right for a message the user chose to paste; wrong for every message the
  phone receives (see Decisions).
- **Sharing from Messages** already routes a bank SMS to Paste SMS (`MainActivity.acceptShare`,
  patterns only).
- **No SMS permission**, no receiver, no notification listener. `FinanceNotices` is an in-app
  snackbar only; the one system notification channel is `FinanceReminderWorker`'s.
- **Sync only pushes while the app is on screen** (one attempt on going to the background, then
  nothing until the next launch). Entries saved by a background job would sit unsynced. Phase 7
  fixes this.
- `finance.db` is at version 2.

## Decisions

- **Decision 1 — how SMS reach the app. DECIDED: A.** Not going on Play, so the SMS permission
  policy doesn't apply.

  | | Pros | Cons |
  |---|---|---|
  | **A. `RECEIVE_SMS` broadcast receiver** | Every SMS, app closed or not; sender and full body | Play restricts SMS permissions. "SMS-based money management" has been a permitted exception, but it needs the Permissions Declaration form and review — confirm the current policy first. No issue if sideloaded. |
  | **B. `NotificationListenerService`** | No SMS permission; also sees bank and UPI app notifications | Misses silenced or grouped notifications; text can be cut off; sender is the conversation title, not the DLT header; notification access is sensitive too |

  Ingestion only calls `ReceiveSms`, so B can still be added later (for bank-app notifications)
  without touching the rest.
- **Never read an SMS twice by mistake, never miss one silently.** Every received SMS that passes
  the sender gate is written to a local inbox row *before* anything reads it, so a killed job or a
  failed model call leaves a row to retry, not a lost message.
- **Two paths in `ReadSms`.** Incoming SMS run **patterns first** (the order before Jev-first):
  OTPs, promos and readable transactions never leave the phone, and only bank-sender SMS the
  patterns can't place go to Jev and then the LLM. Pasted SMS keep Jev-first. One use case, a
  `mode` parameter, not two copies.
- **Hybrid saving.** Saved on its own only when every auto-save rule below holds; anything else
  goes to To review. A wrong automatic entry is worse than a review tap.
- **The inbox is local only.** Raw SMS text never syncs. Only the phone with the SIM reads SMS;
  other phones get the saved entries through FinanceSync as usual.
- **One switch, off by default.** Settings › Finances › "Add bank SMS automatically", with a
  second "Save clear ones without asking" (on by default once the first is on). Turning the first
  off stops reading; the inbox stays until cleared.

## Rules

- **Sender gate (local, before anything else).** Keep only SMS from alphanumeric DLT headers
  (`AX-HDFCBK`, `VM-ICICIT`, `JD-SBIINB-S`: optional two-letter prefix and dash, a 6-character id
  with at least one letter, optional `-S/-T/-P/-G` suffix). Anything from a phone number is dropped unread and never stored. A
  known-bank list decides nothing on its own — new senders still go through the patterns.
- **Patterns.** `SmsParser.classify`: OTP / PROMO → inbox row marked `NOT_PAYMENT`, body cleared,
  done. TRANSACTION → parsed on the phone. UNREADABLE → masked text to Jev; Jev sure it isn't a
  payment (≥ 0.85, as today) → `NOT_PAYMENT`; else the LLM reads it. The LLM gives nothing →
  review as `UNREADABLE` (it may have been unreachable).
- **Auto-save, all of these:**
  1. read by the patterns (`fromModel = false`);
  2. `accountFor` finds exactly one account for the last 4;
  3. type is EXPENSE or INCOME (card payments and refunds go to review);
  4. `duplicateOf` finds nothing, and no entry has the SMS's uid;
  5. the switch "Save clear ones without asking" is on.

  Category: remembered merchant, else Jev's pick at ≥ 0.5, else none (saved uncategorised; the
  notification says so). A UPI id nobody has named yet still saves, with the UPI id as merchant,
  and the notification offers "Who was this for?".
- **The same SMS is one entry however it arrives.** The automatic path uses the same uid as Paste:
  `FinanceIds.smsTransaction("", body)` — sender left empty on purpose, since Paste never knows it.
  Pasting an SMS that was already added automatically lands on `AlreadySaved`.
- **Notifications.** Channel "Bank SMS". Auto-saved: "Saved ₹420 at Swiggy · HDFC ••4471" with
  Undo (deletes the entry, marks the row `DISMISSED`); tapping it opens Expenses. No Edit: there's
  no edit-entry screen to open, and the row's text is gone once saved. Needs review: one grouped
  notification, "2 bank SMS to review", opening To review. Nothing for `NOT_PAYMENT`.
- **Retention.** A row's body is cleared once it's saved, dismissed or `NOT_PAYMENT`; rows older
  than 30 days are deleted. Pending rows keep their body until handled.
- **Late or out of order.** An SMS dated more than 3 days back (delayed delivery, phone was off)
  always goes to review, never auto-saves.

## Data

`finance.db` 2 → 3 adds a table with **no sync triggers** (it isn't in `FinanceSyncSchema.TRACKED`):

| Column | Type | Meaning |
|---|---|---|
| `id` | `TEXT PRIMARY KEY` | `FinanceIds.smsTransaction("", body)`, so the same SMS twice is one row |
| `sender` | `TEXT NOT NULL` | DLT header as received |
| `body` | `TEXT NULL` | Cleared once handled (Retention) |
| `receivedAtMillis` | `INTEGER NOT NULL` | |
| `status` | `TEXT NOT NULL` | `PENDING`, `REVIEW`, `SAVED`, `DISMISSED`, `NOT_PAYMENT` |
| `reason` | `TEXT NULL` | Why it needs review: `UNREADABLE`, `MODEL_READ`, `NO_ACCOUNT`, `DUPLICATE`, `CARD`, `LATE`, `AUTO_OFF`, `NOT_SAVED` |
| `transactionUid` | `TEXT NULL` | The entry it became, for Undo |

Index on `status`.

Domain (`:finance`):

- `SenderGate` — the DLT header check. Pure, tested with real headers.
- `PrepareSmsEntry` — what `SmsEntryViewModel.review` does today, moved out: account, type,
  merchant guess, duplicate, date. Returns an `SmsEntryPlan`. The ViewModel calls it too.
- `ReceiveSms(sender, body, receivedAtMillis)` — gate → inbox row (`PENDING`). The only entry
  point ingestion calls. Returns fast; reading happens in the job.
- `ProcessSmsInbox` — for each `PENDING` row: `ReadSms(mode = INCOMING)` → `PrepareSmsEntry` →
  auto-save rules → `AddTransaction` or `REVIEW` with a reason. Returns an `InboxRun` (entries
  saved on their own, count sent to review) for phase 5's notifications.
- `ReadSms` gains `mode: PASTED | INCOMING`.

## Phases

1. **Extract `PrepareSmsEntry`.** Move the review logic out of `SmsEntryViewModel` into a use case
   with tests (account, type, card payment detection, duplicate by uid / ref / time window). The
   Paste SMS screen behaves exactly as before. No new features; lands first.
2. **`ReadSms` modes + `SenderGate`.** `INCOMING` is patterns-first; `PASTED` stays Jev-first.
   Tests: an OTP and a personal message under `INCOMING` reach neither model (assert the fakes
   saw nothing); a DLT-header table for the gate.
3. **Inbox + processing, no Android.** Migration 2 → 3, `SmsInboxDao`, `ReceiveSms`,
   `ProcessSmsInbox`, retention. Unit tests per auto-save rule and per review reason, and the
   same SMS received twice, then pasted.
4. **Ingestion (Decision 1).** A: `SMS_RECEIVED` receiver in `:app` (manifest components stay in
   the root package) → `ReceiveSms` → enqueue an expedited `ProcessSmsWorker` (unique work,
   `APPEND_OR_REPLACE`). Joins multi-part SMS by sender before calling `ReceiveSms`. B: a
   `NotificationListenerService` that filters to the default SMS app's package and maps
   title → sender, text → body. The title can be a contact's name, and a 6-letter name
   ("Rajesh") passes `SenderGate`, so B needs the conversation's address, not just its title. Permission ask from the Settings switch, with a screen explaining
   what's read and what never leaves the phone.
5. **Notifications.** Channel, auto-saved notification with Undo, a single review notification. Undo works from the notification without
   opening the app.
6. **To review list.** Finances › Pending gains a "Bank SMS" section (or its own screen — check
   Figma first; there's no frame for it yet). Each row opens the Paste SMS review pre-filled from
   the inbox row; saving or dismissing updates the row. Swipe to dismiss.
7. **Background push.** When a push fails with the app off screen, enqueue a one-time
   `PushPendingWorker` with `NetworkType.CONNECTED` that calls `CloudSync.pushPending()`.
   `ProcessSmsWorker` enqueues it after saving. Lives in `:app` (`:sync` stays free of
   WorkManager), unique work so retries don't stack.
8. **Settings.** The two switches, a count of rows waiting, "Clear SMS inbox".

Phases 1–3 have no Android or UI and can land before Decision 1 is made. 4 needs 3. 5–6 need 4.
7 is independent and fixes the existing gap too; it can go any time.

## Before shipping

- Play policy: not needed while the app isn't on Play. If that changes, re-read the SMS and Call
  Log permissions policy and file the declaration. A privacy policy must say which SMS are read, what's sent to
  Jev / the LLM (masked text of bank SMS the patterns couldn't read), and that nothing else
  leaves the phone.
- Collect real SMS pairs that describe one payment (bank debit + UPI app, card alert + bank
  alert) and check `duplicateOf` catches them by ref or time window. Add them to the tests.
- Check on a device: a multi-part SMS, an SMS arriving while the phone is in Doze, the receiver
  after a reboot, and a dual-SIM phone. Also check that two SMS arriving close together both get
  read (expedited work chained with `APPEND_OR_REPLACE`), and that the receiver still fires after
  the app is force-stopped. Android stops delivering broadcasts to a force-stopped app until it's
  opened again.
- Room migration test for 2 → 3.

## Out of scope

- Bank and UPI app notifications (B could add them later behind the same `ReceiveSms`).
- Email alerts from banks.
