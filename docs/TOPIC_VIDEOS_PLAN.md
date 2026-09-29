# Topic videos — implementation plan

Figma: `Kortex` › page **Kortex - v1 (Screens)** › section **05 Topic videos** (below 04 Topics),
frames 2a–2l. It replaces 1e's layout and adds the in-app player. Topics phases 0–11 are in
[TOPICS_PLAN.md](TOPICS_PLAN.md); this builds on phase 6 (done flag), 7 (type filter, selection)
and 9 (summary fingerprint).

| Frame | What it shows |
|---|---|
| 2a | Topic feed: video cards unwatched / in progress / watched |
| 2b | Videos type-filtered view (replaces 1e) |
| 2c | Videos + UNWATCHED chip |
| 2d | Selecting videos — Mark watched joins Move / Pin / Delete |
| 2e | Player, first play |
| 2f | Player reopened — resumes, "Resuming at … · START OVER" under the player |
| 2g | Player finished — marked watched on its own, Next unwatched card, Undo |
| 2h | Can't play here — uploader blocks embedding |
| 2i | Offline |
| 2j | Full screen (landscape) |
| 2k | Every state of the video card and row |
| 2l | Behaviour notes (the rules below, in design form) |

## Status

Phases 1–9 are written (not yet built or run). Phase 3 notes:

- The player is `ui/player/` (`VideoPlayerRoute`, `VideoPlayerViewModel`, `VideoProgressText`),
  opened from topic detail through `TopicDetailState.playing`. `TopicItem.Video.playsInApp`
  decides between the player and opening the link.
- Library `android-youtube-player` **13.0.0**: it sends the embed `origin` YouTube now checks,
  which 12.x doesn't. It brings lifecycle-runtime 2.9.4, which Gradle resolves app-wide over the
  catalog's 2.8.7.
- Offline comes from a `Connectivity` port checked when the player opens, on Try again, and when
  the player reports an error. The library's own network handling is off.
- Phase 4's error mapping, `MarkEmbedBlocked`, the 2h panel and notice, and direct-to-YouTube taps
  are in. Left for phase 4: nothing, unless the device check turns up more error codes.
- Rotation used to rebuild the activity, and with it the embed; see phase 5.

Phase 5 notes:

- `MainActivity` now declares `configChanges` for orientation and screen size, so rotating no
  longer rebuilds the activity. That's app-wide, but the UI is all Compose and there are no
  `-land` resources. Without it the WebView, and playback, would restart on every turn.
- Full screen is a layout, not YouTube's full-screen mode: in landscape the same player view fills
  the screen, letterboxed, with the system bars hidden and nothing of Kortex's showing. The library
  can't enter YouTube's own full screen from code, so its button is off (`fullscreen(0)`). A
  listener declines it if the page asks anyway.
- **Differs from 2j:** there's no in-player full-screen button, so entering it from portrait is a
  "FULL SCREEN ⤢" control at the end of the meta line under the title. Back leaves full screen
  before it leaves the player.
- `PlayerOrientation`: the button forces landscape and back forces portrait, each until the phone is
  physically held that way, then the sensor takes over again. With auto-rotate off, the button
  holds landscape until back. Leaving the player always releases it.
- The screen stays on while a video plays (`VideoPlayerState.playing`), including through buffering.
- To check on a device: a notch in landscape, and gesture back while the system bars are hidden.

Phase 6 notes:

- `ui/common/VideoThumbnail.kt` draws a video's picture everywhere it appears: the feed card
  header, the player's Up next and Next unwatched, and the phase 7 rows to come. It adds the length
  badge (only once known), the resume bar along the foot, dimming once watched, and a ▶ + length
  tile when there's no picture.
- A video counts as in progress (bar and "m:ss LEFT") only when `VideoRecord.startSeconds()` > 0,
  so the card and the player agree. It doesn't count for the first 3 s, past 95%, once watched, or
  with the length unknown.
- Feed card meta: "VIDEO · 8:42 LEFT" in progress, "VIDEO · OPENS YOUTUBE ↗" once embedding is
  blocked. The bar reads to TalkBack as "38% watched, 8 minutes 42 seconds left".
- The watched chip keeps the look it shares with read articles and paid bills, rather than 2a's
  outlined "✓ WATCHED".

Phase 7 notes:

- Picking any type chip opens that type's own view (`TopicDetailState.typeView`), not only Videos.
  - Its name is the title, and the totals line comes from `typeMetaLine` (for example "5 ITEMS ·
    1H 12M TOTAL · 39M LEFT TO WATCH").
  - The topic's name moves to the top bar.
  - The chips are ALL, the type and the status chip.
  - Add / Share, the summary, the bills card, the view-mode switch and the + button wait for ALL.
  - Back, and the bar's ‹, return to ALL before they leave the topic.
- Status chips: UNWATCHED, UNREAD and UNPAID for the types that can be done with (`doneStatus`).
  Each view opens with the chip off. With it on, "N watched videos hidden · SHOW" sits under the
  list, and "All videos watched." shows if nothing is left.
- A type's view is always one plain list, pinned first, whatever the topic's Feed / Timeline /
  By type mode.
- Compact rows are for videos only (`VideoRow`, on the shared `VideoThumbnail`). Other types keep
  their feed cards in their view, because the mocks only cover videos. The row's line reads "8:42
  LEFT · 2H AGO", "UNWATCHED · 1D AGO", "✓ WATCHED · 1W AGO" or "OPENS YOUTUBE ↗ · …".
- The hint bar's SELECT sets `selectionMode`: selection with nothing picked. Pin and Delete stay
  disabled until something is picked, and unpicking the last item keeps the mode.
- Tap, long-press and selection behaviour moved into `Modifier.itemGestures`, which cards and rows
  share.
- The selection bar's Mark watched (2d) is phase 8.

Phase 8 notes:

- Mark watched sits in the selection bar with the ✓ icon (`ic_check`). It appears whenever every
  picked item is of one type that can be done with, not only videos, so it also reads "Mark read"
  for articles and "Mark paid" for bills (`TopicDetailState.selectionDoneStatus`). It works under
  ALL too, as long as the selection isn't mixed.
- It flips the selection the way Pin does: it marks every item done unless all of them already
  are, in which case it reads "Mark unwatched" and takes them back. It uses the manual
  `SetItemDone`, so the player's progress is kept either way.
- "Mark unwatched" may wrap to two lines in a four-button bar on a narrow phone; check on a device.

Phase 9 notes:

- Tapping a search result for a video that plays in the app (`TopicItem.Video.playsInApp`) opens
  its topic with the player already on it. Every other result opens the topic, as before.
  - The request is carried by `TopicSearchIntent.OpenHit` and `TopicSearchEffect.OpenTopic(playing)`.
  - From there `TopicsScreen` / `TopicsListRoute` call `onPlayVideo(topicId, itemId)`, then
    `RootState.openVideo` → `Overlay.Topic(topicId, playing)` → `TopicDetailRoute(playing = …)`.
  - The detail ViewModel starts with `TopicDetailState.playing` set.
- `onPlayVideo` defaults to opening the topic, so a host that doesn't pass it loses nothing.
- Back from the player lands on the topic's feed, and back again leaves the topic.
- `RootState` saves the overlay as "topicId/itemId", so after the process is killed and
  restored, the topic reopens with the player. That holds even if the player was closed before
  the app went to the background; closing the player doesn't clear it from the overlay.
- Fixed from phase 3: `VideoPlayerViewModel`'s two `Long` assisted parameters now have names
  (`@Assisted("topicId")`, `@Assisted("itemId")`). Dagger rejects two unnamed assisted parameters of
  the same type. `TopicDetailViewModel` names its two parameters the same way.

All nine phases are written. What's left is the "Before shipping" list below and a first build.

Differences from the text below, from phases 1–2:

- The repository has one `updateVideo(itemId, now, change)` instead of `saveVideoProgress` and
  `setEmbedBlocked`. It reads and writes the row in one Room transaction, so two saves close
  together can't drop each other's stretches. The rules themselves are pure functions on
  `VideoRecord`: `withPlayback(report, now)` and `startSeconds()` in `domain/model/VideoProgress.kt`.
- `YouTubeVideoId` also reads `/live/`, `/embed/`, `music.` and `youtube-nocookie.com`, which
  `DetectItemType` already calls videos. It only takes 11-character ids, so a malformed link opens
  outside the app.
- Progress rows sync as they're saved, so live sync pushes about every 15 s while a video plays.
  The push debounce already groups these. If it costs too many writes, leave out
  `resumeSeconds` / `lastPlayedAtMillis` from the trigger's columns and send them with the next
  change that does sync.

## Where we are

- A video is `TopicItem.Video(link, durationSeconds, watched, pinned)`. Tapping it sends
  `TopicDetailEffect.OpenUrl`, which hands the link to YouTube / the browser
  (`TopicDetailViewModel.open`). Nothing plays in the app.
- `watched` (`topic_items.done`) is only set by hand, from the card's chip.
- `durationSeconds` is often empty — nothing fills it after capture.
- The type filter lives inside the full topic detail: header, Add/Share, summary, bills and the
  Feed / Timeline / By type switch all stay on screen, and there is no status chip. 1e's layout was
  never built.
- `topics.db` was at version 5 (now 6, phase 1).

## Decisions

- **Official embed only.** Play through YouTube's IFrame Player API in a WebView, via
  `com.pierfrancescosoffritti.androidyoutubeplayer:core` (it wraps the IFrame API and hands us state,
  current second and duration as Kotlin callbacks). Not the deprecated YouTube Android Player API,
  and never stream extraction into ExoPlayer — that breaks YouTube's terms.
- **Nothing is drawn over the player.** YouTube's required minimum functionality forbids overlays on
  the embed. Kortex UI sits above or below it; full screen shows YouTube's controls only. The player
  stays at least 200 × 200 dp (16:9 at full width is 225 dp tall).
- **YouTube links only.** `DetectItemType` only calls YouTube addresses videos today; anything else
  that ends up as a Video, and any link whose id can't be read, keeps opening externally.
- **The player is a layer of topic detail**, like the email reader: `TopicDetailState.playing`
  plus a `VideoPlayerLayer` with its own `VideoPlayerViewModel` (MVI, assisted item id). Back
  returns to the feed at the same scroll position.
- **Two numbers per video.** *Resume point* (where they stopped) drives the thumbnail bar, "time
  left" and resuming. *Seen stretches* (merged second ranges) drive "N% WATCHED" and auto-watched,
  so rewatching or skipping ahead can't inflate it. Total time spent is not kept.
- **Watched on its own** when the player reports ENDED, or when seen coverage *crosses* 90% during a
  session. Crossing, not being above: a user who unticks a finished video isn't overruled on the
  next save. Unticking keeps the progress.
- **No autoplay of the next video.** When one ends, a "Next unwatched" card offers it (2g).
- **Summary is unaffected by progress.** `SummaryDigest`'s fingerprint keeps using the watched flag
  only, so watching doesn't mark the summary stale every 15 s.

## Rules (what 2l says, for the code)

- **Resume:** start at `max(0, resume − 3 s)`. If resume ≥ 95% of the length, or the video is
  watched, start at 0. Show "Resuming at m:ss · START OVER" under the player for 4 s.
- **Counting:** while state is PLAYING, each `onCurrentSecond` tick extends the open stretch when
  the step is in `(0, 2.5 s × playback rate]`; any other jump (seek, ad, buffering gap) closes it and
  starts a new one at the new second. Ads don't advance the video's own clock, so they don't count —
  confirm on device.
- **Saving:** flush on PAUSED, ENDED, leaving the player (dispose), backgrounding, and every 15 s
  while playing. Never per tick.
- **Length:** take `onVideoDuration` on first play when `durationSeconds` is null, or when it
  differs by more than 2 s.
- **Can't embed:** on `PlayerError.VIDEO_NOT_PLAYABLE_IN_EMBEDDED_PLAYER` (IFrame errors 101 / 150)
  set `embedBlocked`. From then on the row shows OPENS YOUTUBE ↗ and taps go straight to YouTube
  (2h, 2k). Nothing is tracked there — the notice says to tick Mark watched by hand.
- **Offline:** no network when the player opens, or a network error from the player → offline
  state (2i) with Try again. Progress, Mark watched and Share still work.

## Data

`topic_items` gains (migration 5 → 6, all nullable or defaulted, existing rows untouched):

| Column | Type | Meaning |
|---|---|---|
| `resumeSeconds` | `INTEGER NULL` | Last playback position |
| `seenRanges` | `TEXT NULL` | Merged seen stretches, `"0-190,300-320"` (seconds) |
| `lastPlayedAtMillis` | `INTEGER NULL` | For "LAST WATCHED YESTERDAY" |
| `embedBlocked` | `INTEGER NOT NULL DEFAULT 0` | Uploader disallows embedding |

Domain:

- `TopicItem.Video` gains `progress: VideoProgress?` (`resumeSeconds`, `seen: SeenRanges`,
  `lastPlayedAtMillis`) and `embedBlocked: Boolean`.
- `SeenRanges` — value type: `plus(range)` merges overlaps and neighbours within 1 s,
  `coveredSeconds`, `fraction(length)`, `encode()` / `decode()`. Capped at 64 ranges (merge the
  closest pair when over) so the column stays small.
- `YouTubeVideoId.parse(url)` next to `DetectItemType` — `watch?v=`, `youtu.be/`, `/shorts/`,
  `m.` and `www.` hosts; `null` otherwise.
- Use cases: `RecordVideoProgress(itemId, resume, newRanges, lengthSeconds, ended)` — merges, fills
  the length, sets `done` on ENDED or on crossing 90%, returns whether it just marked it watched
  (for the 2g snackbar); `MarkEmbedBlocked(itemId)`. `SetItemDone` stays for the manual toggle.
- `TopicsRepository.saveVideoProgress(...)` and `setEmbedBlocked(...)`.

Sync: add the four columns to the item column list in `TopicSyncSchema` (line 43), the remote
record in `TopicSyncDao` and the Firestore mapping. Conflicts follow whatever the item record
already does. A union of `seenRanges` would be nicer, but isn't worth a special case for one user
on two phones.

## Phases

1. **Domain + data** — `SeenRanges`, `YouTubeVideoId`, `VideoProgress`, the two use cases,
   repository methods, migration 5 → 6, sync columns. Unit tests: range merging and capping, id
   parsing (every URL form in `DetectItemTypeTest`), the 90%-crossing rule, and Undo restoring the
   previous `done`.
2. **Watch session** — a pure `WatchSession` class that takes `(state, second, rate)` ticks and emits
   ranges and flush points. No Android types, so it's unit-tested with scripted tick sequences:
   seek forward and back, rate 2×, pause/resume, ended, an ad gap.
3. **Player layer (2e–2g, 2i)** — `ui/player/VideoPlayerScreen` + `VideoPlayerViewModel`;
   `YouTubePlayerView` in `AndroidView`, lifecycle-bound, released on leave. Title, meta, progress
   card (seen stretches + resume tick), Mark watched / Open in YouTube / Share, Up next (unwatched in
   the same topic, newest first), Next unwatched card and "Marked watched · UNDO" snackbar.
   `TopicDetailViewModel.open` sends Video items here unless `embedBlocked` or no id parses.
4. **Can't embed (2h)** — error mapping, `MarkEmbedBlocked`, direct-to-YouTube taps afterwards,
   the notice.
5. **Full screen (2j)** — the library's full-screen callback swaps the view into a full-screen
   container and hides the system bars. Rotation enters and leaves it; back exits it before leaving
   the player. Playback survives rotation (the player view lives in the layer, not recreated).
6. **Cards and rows (2a, 2k)** — progress bar on thumbnails, "m:ss LEFT" in Synapse while in
   progress, dimmed thumbnail when watched, length badge only when known, no-thumbnail tile,
   OPENS YOUTUBE ↗ tag. Accessibility: the bar gets a state description ("38% watched, 8 minutes
   42 left").
7. **Videos view (2b, 2c) — the 1e gaps.** When a type is chosen:
   - Header shows the type ("Videos") and its totals — `5 ITEMS · 1H 12M TOTAL · 39M LEFT TO WATCH`.
     The top bar shows the topic name.
   - Hide + Add / Share, summary, bills card, the Feed / Timeline / By type switch and the FAB until
     ALL is picked again.
   - One status chip for types with a done state: UNWATCHED / UNREAD / UNPAID with its count. It
     toggles independently of the type chip; "N watched videos hidden · SHOW" under the list.
   - Compact rows (thumbnail left, title, state · age) instead of feed cards.
   - Bottom hint "Long-press to move, pin or delete · SELECT"; SELECT starts selection with nothing
     picked.
8. **Selection (2d)** — Mark watched in the selection bar when every picked item is a video.
9. **Search hits** — a video hit opens its topic with the player already open (today every hit only
   opens the topic).

Phases 1–2 have no UI and can land first. 3 needs 1–2. 4–6 need 3. 7–8 only need 1 (they read
`progress`) and can go in parallel with 3.

## Before shipping

- Read YouTube API Services Developer Policies and Required Minimum Functionality once more,
  especially the limit on keeping data from the API. The length we store comes from the player —
  if it counts, refresh it on every play (we already read it) and don't show a stale one.
- Check on a device how ads report state and seconds, so ad time really doesn't count.
- Room migration test for 5 → 6 (TOPICS_PLAN polish still defers these; this migration is where
  they start paying off).

## Out of scope

- Knowing about watching done in the YouTube app or browser — YouTube has no API for a user's
  watch history.
- Other video hosts (Vimeo, Instagram, etc.).
- Picture-in-picture and background audio. The embed stops with the view, and YouTube's rules
  don't allow background play through it.
- Time-spent statistics.
