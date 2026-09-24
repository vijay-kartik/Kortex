# Topics — implementation plan

Figma: `Kortex` › page **Topics** (node 134:2), frames 1a–1g. Topics is a second leaf under the
**My Info** category, next to Links.

## Decisions

- **Tab bar stays one row.** My Info expands to exactly two leaves: **Links**, **Topics**. There is
  no Files tab (the Files label in the mocks is ignored).
- **Links are shared, never duplicated.** Saving a link into a topic reuses the saved link with the
  same address (`linkUrlKey`); only a new address creates a row in the Links tab. The topic stores a
  reference (`linkId`), so the two tabs can't drift.
- **Suggested topics are in scope** (1g, first run): clusters of saved links by tag embedding.
- **`:topics` feature module** (package `dev.kortex.myinfo.topics`) with `domain/` (pure Kotlin:
  models, repository interfaces, use cases), `data/` (Room), `ui/` (MVI screens). It does not depend on `:links` or `:core-agent`:
  - `LinkCatalog` (domain port) — find-or-save a link, observe titles/thumbnails, deletions. Bound in
    `:app` to `LinksRepository`.
  - `TopicSummarizer` (domain port) — agent summary. Bound in `:app` to the LLM.
  - Suggested topics need no port of their own: `SavedLink` carries its Links tags (assigned by
    embedding similarity), and the domain groups links by tag.
- **MVI base in `:mvi`**: `MviViewModel<State, Intent, Effect>` — one `onIntent` entry, immutable
  `StateFlow<State>`, one-shot `Flow<Effect>`; `ObserveEffects` collects effects in Compose.
  Every screen gets `XxxState`, `XxxIntent`, `XxxEffect`; state changes go through pure reducers.
- **Navigation**: the new-topic form and topic detail are full-screen in the mocks, so the host
  shows them as `RootState` overlays (as it does `CreateLink`); `TopicsScreen` reports
  `onCreateTopic` / `onOpenTopic`. Each overlay route wraps itself in `ScopedViewModelStore` (`:mvi`)
  so its ViewModel is fresh per opening yet survives rotation. The quick-capture sheet is a
  `ModalBottomSheet`.

## Phases

0. **Groundwork** ✅ — `:mvi` and `:topics` modules; `KortexTab.Topics` under My Info; `CategoryTabBar`
   lays out several My Info leaves on one row; `RootState` remembers the last My Info leaf; Topics
   tab shows a placeholder first-run screen.
1. **Domain + data** ✅ — `Topic`, sealed `TopicItem` (`Note`, `Link`, `Article`, `Video`, `Doc`,
   `Image`, `Bill`), `NewItem`, `TopicOverview` / `TopicDetail`; `TopicsRepository` on Room
   (`topics.db`: `topics`, `topic_items`); use cases (`ObserveTopics`, `ObserveTopic`,
   `CreateTopic`, `UpdateTopic`, `SetTopicPinned`, `DeleteTopic`, `AddItem`, `MoveItems`,
   `DeleteItems`, `DetectItemType`); `LinkCatalog` bound in `:app` (`LinksLinkCatalog`); unit tests.
   The FTS table moves to phase 8 and the summary port to phase 9, where its shape is known.
2. **Topics list (1a) + first-run (1g right)** ✅ — `TopicsListViewModel` (MVI), Recent / Pinned /
   A–Z, cards (previews, reading progress, file badges, bills total, pinned), long-press tray (Open,
   Pin, Delete with undo), suggested topics (a Links tag on ≥ 3 links; accepting creates the topic
   with those links). The search pill waits for phase 8; the empty-topic block of 1g belongs to the
   detail screen (phase 4).
3. **New topic (1g left)** ✅ — `NewTopicViewModel` (MVI) + `Overlay.NewTopic`; name (blank / taken
   errors), purpose, sections, pin. Creating returns to the list until phase 4 can open the topic.
   **Sections picker removed later:** 1g preselected Notes, Images and Docs, which was the
   sample topic's choice rather than a sensible default, and a topic's chips come from what it
   holds anyway. New topics now start with no sections; ones made before keep theirs.
4. **Topic detail feed (1b)** ✅ — `TopicDetailViewModel` (assisted topic id) + `Overlay.Topic`;
   header, "+ Add" / Share, type chips with counts (chosen sections included), feed cards for every
   type (links open in the browser), ⋯ menu (pin, delete with confirm), empty-topic state (1g).
   Opening from the list, a suggestion and the new-topic form all land here. Summarise waits for
   phase 9 and the bills card for phase 6; "+ Add" waits for phase 5.
5. **Quick capture sheet (1d)** ✅ — `QuickCaptureViewModel` (MVI, assisted topic id) in a
   `ModalBottomSheet` opened from the topic detail ("+ Add", FAB, empty state). Paste or type;
   type detected with override (link / article / video / note — docs, images and bills need phase
   6's fields, so a file address starts as a link); title pre-filled from Links or the page
   (`LinkCatalog.lookUp`); topic chips + inline "+ New topic"; `CaptureItem` checks the item before
   creating a topic. Links reuse Links by address. Saving elsewhere shows "Saved to …". Opening it
   from `ShareToKortexActivity` is still to do.
6. **Rich item types (1b, 1d)** ✅ — `FileVault` port on `TopicFileStore` (files copied into
   `files/topic-files`, PDF pages counted, orphans swept, a FileProvider so a tapped doc opens
   elsewhere); quick capture grows a file picker and a photo picker, and bill fields (amount in the
   currency's minor unit via `MoneyAmount`, currency, due date, paid); `SetItemDone` ticks an
   article read, a video watched or a bill paid from its card; `BillSummary` + bills card above the
   feed. Deleting an item or a topic takes its files with it.
7. **Timeline, type-filtered view, multi-select (1c, 1e)** ✅ — `TopicViewMode` (Feed / Timeline /
   By type) with `TopicFeed.sections` grouping in the domain: Timeline cuts by calendar day (today,
   yesterday, this week, this month, earlier), By type puts the busiest type first, and pinned items
   lead in every mode. Long-press picks an item out; the top bar becomes a count with "all" and the
   bottom bar offers Move (a sheet of the other topics), Pin/Unpin and Delete (confirmed). Items
   gained a `pinned` column — `topics.db` is at version 2, migrated in place.
8. **Search across topics (1f)** ✅ — a search pill on the list opens `Overlay.TopicSearch`.
   `SearchCorpus.search` matches every word of the query, ignoring case, over note text, link
   titles and addresses, doc and bill titles, image captions and currency codes — plus topic
   names, which surface a topic even when none of its items match. Scope chips (Everything /
   Notes / Links / Files / Bills) narrow the items, never the names; results are grouped by topic
   (name matches first, then busiest, then most recently changed) with every occurrence
   highlighted. Typing is debounced 220ms; a scope tap answers at once.

   **Not FTS, deliberately.** A topic's link-backed items keep only a `linkId`; their titles and
   addresses live in the Links library, in another database. An FTS index over `topic_items`
   would therefore miss every link title — and a second matcher for those would give two
   different notions of "matches". Search reads the same resolved items the topics list already
   loads, so one matcher covers everything and there is no index to keep in step with the rows.
   If the corpus ever outgrows memory, the fix is to page the corpus, not to split the matcher.
9. **Agent summary (1b)** ✅ — `TopicSummarizer` port in `:topics`, bound in `:app` to
   `LlmTopicSummarizer`: one plain completion on the user's active provider and model (the same
   ones the chat uses), no tools, items fenced as data rather than instructions, `<think>` blocks
   stripped. `SummaryDigest` turns a topic into bounded text (pinned then newest, 60 items, lines
   clipped to 240 chars) and fingerprints it; `SummarizeTopic` stamps each summary with that
   fingerprint. Cached in `topic_summaries` (`topics.db` v3, cascades with the topic).

   **Invalidation is by fingerprint, not by hooks.** A summary is out of date when the topic's
   current fingerprint differs from the one it was written from — so an item added, removed,
   ticked off, pinned or edited, or the topic renamed or its purpose changed, all invalidate it
   with no write path having to remember to. Pinning the topic or its updated time don't.
   An out-of-date summary is shown dimmed with "Topic changed since" and a Refresh action; the
   model is only called when the user taps Summarise / Refresh / Try again, never on its own.
11. **Emails (added after the Figma set)** — a topic can hold an email. `EmailDirectory` is a
   domain port in `:topics`, bound in `:app` to `GmailEmailDirectory`, which reads the mailbox
   connected in Settings over the same OAuth token and `GmailApi` the `gmail_search` tool uses.
   Quick capture gains "+ Email": search the mailbox from inside the sheet and pick one. Nothing
   is copied but what it takes to show the mail and find it again — Gmail's message and thread
   ids, the subject, sender, snippet, sent time, and the RFC822 `Message-ID` header. Tapping the
   card opens it in an in-app reader (`ui/email`): what the topic kept shows at once, and
   `EmailDirectory.read` fetches the rest — recipients, body, attachment names — from the mailbox.
   An HTML body is shown in a WebView, as the sender laid it out, whenever the mail has one — even
   alongside a plain-text part (`GmailApi` now keeps both). Images the mail carries (`cid:`
   references, collected by `GmailApi` as `inlineImages`) are fetched and inlined as data URIs,
   up to 8 MB a mail, and drop out of the attachment list; remote images load from the web as
   they do in Gmail — which, as there, lets a sender's tracking pixel see the mail was opened.
   The WebView runs no scripts and has no file access. Mail with only plain text is shown as
   selectable text. Attachments are listed with their sizes; tapping one downloads it
   (`FetchEmailAttachment`: `EmailDirectory.download` into `AttachmentCache`, which is
   `cache/email-attachments`, cleared after a day and served by the topics FileProvider) and
   opens it in whichever app handles its type, the way a kept doc opens. "Open Gmail"
   launches Gmail to its inbox. An email kept from another account than the one connected, or one
   deleted from the mailbox, says so rather than failing. `topics.db` is at version 4.

   **Why not share-from-Gmail:** Gmail on Android shares plain text, with no id in it, so a
   shared mail can't be pointed back at. Reading the mailbox directly is what makes the link
   durable. The `Message-ID` is kept as insurance: it identifies the mail anywhere, so it can
   still be found by search if the provider id ever fails.

   **Why a reader rather than opening Gmail** (tested on device, Gmail 2026.09): nothing public
   opens one message in the Gmail app. `mail.google.com` links resolve to the browser, and the
   app drops the fragment that names the message even when it takes them. `ACTION_SEARCH`
   reaches Gmail but it finishes at once ("Missing account extra") — the extra is Gmail's own
   parcelable account, which no other app can build. The Assistant's `SEARCH_ACTION` needs
   `READ_GMAIL`, a signature permission, and `VIEW_MESSAGE_DEEPLINK` is undocumented. Reading
   the mail in the app needs none of them, and works without Gmail installed.

10. **Polish** — partly done.
   - **Accessibility** ✅ — every tap target in Topics is at least 48dp
     (`minimumInteractiveComponentSize`, with padding trimmed where that would have grown the
     visible gaps). Picks report their state: filter, view-mode, scope and sort chips are
     selectable tabs in a `selectableGroup`; type, currency and topic chips are radio buttons;
     done chips, "paid" and the pin row are real toggles (`toggleable`), not
     `clickable` with a role. Glyph buttons (‹ ⋯ ✕ ⌕) and type badges are read by name. Topic
     titles, feed groups, search groups and card titles are headings. The selection count, the
     search result count and the summary's status are live regions.
   - **Motion** ✅ — Topics overlays push in from the end and fall back the same way; one Topics
     screen replacing another crossfades (other overlays still switch instantly). Selection bars
     fade and slide, holding their last selection while they leave; the add button scales out
     under selection; the summary crossfades; search results move with `animateItem`.
   - **Tests** ✅ — `TopicsListViewModel` (undo window on virtual time, accepting suggestions,
     navigation); `:app` gains a unit-test setup, covering `LlmTopicSummarizer`'s prompt and
     output cleaning.
   - **Deferred:** previews per state, and Room migration tests (which need schema export).

## My Info intro (removed)

The Links-era first-run screen (Figma: Links › Tabs / 1 First run, 84:2) said Links was My
Info's only feature, with a "Soon" card and an "Open Links" button. With Topics built, My Info
opens straight onto its tabs, as 1a draws it; Links and Topics each introduce themselves with
their own empty states.
