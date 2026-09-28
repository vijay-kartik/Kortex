# Links — MVI refactor plan

Bring `:links` in line with `:topics`: a pure `domain/` layer (models, ports, repository interface, use
cases), a `data/` layer that implements it, and screens built on `:mvi` (`MviViewModel<State, Intent,
Effect>`, `ObserveEffects`, `ScopedViewModelStore`). Each phase below ends with the app building and
behaving exactly as before. Phases 4 and 6 are the risky ones. **Behaviours to preserve** near the
end is the regression checklist to walk through after each of them.

> Before starting: `links/ui/LinksScreen.kt` has an uncommitted change (the hint `Crossfade` is removed,
> imports sorted). Commit or drop it first. If you keep it, `HintStyle` is now unused.

---

## 1. What "like Topics" means (the conventions to copy)

| Convention | Where to look in `:topics` |
|---|---|
| One contract file per screen: `XxxState` (data class), `XxxIntent` (sealed), `XxxEffect` (sealed) | `ui/list/TopicsListContract.kt` |
| State holds **inputs**. Everything derived from them is a `val` in the state's body, so it can be tested without a ViewModel | `TopicsListState.visibleTopics`, `openOptionsTopicId` |
| ViewModel extends `MviViewModel`, changes state only with `setState { copy(...) }`, turns flows into state with `reduceInto` | `TopicsListViewModel` |
| Navigation, clipboard, share and toasts are **effects**. The screen handles them in `ObserveEffects` | `TopicDetailEffect.OpenUrl / CopyText / ShareText` |
| Split `XxxRoute` (gets the ViewModel, collects state, handles effects) from a stateless `XxxScreen(state, onIntent)` that previews can render | `TopicsListRoute` / `TopicsListScreen` |
| Typed text stays in the screen (`rememberSaveable`) and reaches the ViewModel inside an intent, so typing never lags behind a state round-trip | `NewTopicContract` doc comment, `QuickCaptureIntent.Save` |
| Full-screen overlays wrap their route in `ScopedViewModelStore(key)`, so each opening gets a fresh ViewModel | `NewTopicRoute`, `TopicDetailRoute` |
| Domain and data classes carry no DI annotations. They are built in `di/XxxModule` with `@Provides` | `TopicsModule` |
| Wall-clock time goes through a `Clock` port, so the undo window and timestamps are testable | `domain/port/Clock.kt` |
| `android.util.Log` only in `data/`. Domain and ViewModels stay JVM-testable | topics `data/files/*` |
| Tests: pure state/derivation tests, plus ViewModel tests with fakes on `StandardTestDispatcher` | `TopicsListUiTest`, `TopicsListViewModelTest`, `domain/Fakes.kt` |

---

## 2. Where Links is today

| File | Lines | What's wrong for MVI |
|---|---|---|
| `ui/LinksViewModel.kt` | 111 | Plain `ViewModel` with one method per action. State comes from a 5-way `combine(...).stateIn(WhileSubscribed)`. Talks to the concrete `LinksRepository`. Uses `System.currentTimeMillis()`. |
| `ui/LinksScreenUiState.kt` | 30 | Sealed `Loading / Empty / Links`. Filtering and counting happen inside the VM's `combine` lambda, where they can't be tested. |
| `ui/LinksScreen.kt` | 915 | About 10 pieces of real state live in the composable: `optionsLinkId`, `editingTags`, `draftTags`, `addingTag`, `newTagName`, the "is the tray still valid" logic, save-on-close, and the stored-spelling rule for new tags. Clipboard, open and share are called straight from the UI. |
| `ui/CreateLinkViewModel.kt` | 231 | Plain `ViewModel`. Reactive pipeline (debounce → `collectLatest` analyse; `flatMapLatest` duplicate check). `save(..., onSaved: () -> Unit)` takes a callback instead of sending an effect. Uses `Log`. Depends on three concrete classes (`PageMetadataFetcher`, `LinkImageStore`, `LinksRepository`). |
| `ui/CreateLinkScreen.kt` | 637 | Holds selected tags, the adding-tag flag, `imageHidden` (keyed on `analyzedUrl`), the `saved` flag, title autofill, "is the analysis current", the duplicate-match check and the tag-creation rule. |
| `ui/CreateLinkPreview.kt` | 301 | "Showing a failed image means retry" is decided in the UI (`setHidden`). |
| `data/LinksRepository.kt` | 84 | Concrete `@Singleton` class, not an interface. Returns Room entities (`LinkWithTags`, `LinkEntity`) that end up in the UI. Uses `System.currentTimeMillis()`. |
| `linkDomain()` | — | A pure helper declared in `ui/CreateLinkScreen.kt`, yet `:app`'s `MainActivity` and the ViewModel both use it. |

Consumers outside the module (these must keep compiling):

- `:app` `RootScreen`: `LinksScreen(search, onCreateLink)`, `CreateLinkScreen(onBack, initialUrl)`
- `:app` `MainActivity`: `linkDomain`
- `:app` `data/topics/LinksLinkCatalog` + `di/DataModule`: `LinksRepository`, `LinkWithTags`, `PageMetadata`, `PageMetadataFetcher`, `TagSuggester`
- `:app` `di/AppModule`, `:sync` (`CloudSync`, `LinkSync`, `LinkDocs`, `TopicSync`, `LinkDocsTest`): `LinkSyncDao`, `LinkImageStore`, `LinkImageSource`, `LinkWithTags`, `LinkEntity`, `RemoteLink`

**Decision: leave `:sync` on the data types.** Sync moves data from Room to Firestore and back. It
belongs next to `data/` and should not go through the domain. So `LinkSyncDao`, `LinkWithTags`,
`LinkEntity` and `RemoteLink` keep their packages. The only `:sync` edit in the whole plan is one
import, when `LinkImageSource` moves.

---

## 3. Decisions

1. **Domain model instead of Room entities in UI.** `Link(id, url, title, createdAtMillis, thumbnailPath, tags)`.
   `thumbnailPath` is already resolved as `imagePath.takeUnless { imageHidden }`. That is the only way
   the list uses those two columns, and it matches Topics' `SavedLink`.
2. **`LinksRepository` becomes an interface** in `domain/repository`. The Room implementation becomes `data/RoomLinksRepository`.
3. **Ports** (in `domain/port`) for everything the ViewModels need that touches Android or the network:
   `Clock`, `PageReader`, `TagSuggester` (already an interface, it just moves), `ImageDownloads`.
   `Clock` is copied from Topics, not shared: the two modules are deliberately independent, and it is 10 lines.
4. **Flat state instead of a sealed state.** `LinksState(loading = true, …)` with derived `empty`, as
   `TopicsListState` has `loading` + `firstRun`.
5. **The options tray and tag-editor draft move into the ViewModel** (Topics moved `optionsTopicId` the same
   way). The new-tag *text* stays in the screen, following the typed-text rule.
6. **`CreateLinkRoute` gets `ScopedViewModelStore`.** This removes the "view model outlives a single form"
   problem at its root. The URL-match checks stay anyway, because the user can edit the URL.
7. **Keep flow operators inside the ViewModel.** MVI doesn't forbid `debounce` or `flatMapLatest`: intents
   push into a private input flow, and the pipeline ends in `reduceInto`. Rewriting the CreateLink
   pipeline with manual `Job`s would subtly change its cancellation behaviour (see C4).
8. **Package moves outside `ui/` and `domain/` are optional** (phase 7). `data/`, `images/` and `tagging/`
   are imported by `:app` and `:sync`, so moving them adds churn without adding value. Only the pure
   types move out of them.
9. **Behaviour change you're accepting:** `stateIn(WhileSubscribed(5_000))` becomes collection for the
   ViewModel's whole lifetime, as in Topics. Room keeps the Links query open while the activity lives. That is harmless.
10. **Behaviour change you're accepting:** state that was in `rememberSaveable` (selected tags in the form,
    the tag draft in the tray) now survives rotation through the ViewModel, but no longer survives process
    death. Topics accepted the same trade. If you want it back, the ViewModel can take a `SavedStateHandle`.

---

## 4. Target layout

```
dev.kortex.links
├── domain
│   ├── model        Link.kt (Link, TagCount) · LinkDraft.kt · LinkImage.kt (LinkImageSource, LinkImageState)
│   │                PageMetadata.kt · LinkAnalysis.kt (LinkAnalysis, PageReadPhase, AlreadySavedLink)
│   │                LinkAddress.kt (linkDomain) · LinkSearch.kt (Link.matches)
│   ├── port         Clock.kt · PageReader.kt · TagSuggester.kt (+ TagSuggestion) · ImageDownloads.kt
│   ├── repository   LinksRepository.kt
│   └── usecase      ObserveLinks.kt · LinkActions.kt (DeleteLink, SetLinkTags, CreateTag)
│                    SaveLink.kt · ObserveDuplicate.kt · AnalyzeLink.kt · TagCandidates.kt
├── data             RoomLinksRepository.kt · Mappers.kt · (existing Room files stay where they are)
├── images           LinkImageStore.kt           (implements ImageDownloads)
├── tagging          PageMetadataFetcher.kt (implements PageReader) · EmbeddingTagSuggester · LinkEmbedder
├── di               LinksModule.kt
└── ui
    ├── LinksScreen.kt              public entry the host calls; delegates to LinksRoute
    ├── list        LinksContract.kt · LinksViewModel.kt · LinksListScreen.kt
    │               components/ LinkCard.kt · DeletedLinkRow.kt · TagFilters.kt · LinkTagEditor.kt
    ├── create      CreateLinkContract.kt · CreateLinkViewModel.kt · CreateLinkScreen.kt · CreateLinkPreview.kt
    └── common      LinkThumbnail.kt · LinkCopyAnimation.kt · LinkFormat.kt (relativeAge, displayTitle)
                    components/TagChip.kt
```

---

## 5. Phases

### Phase 0: Groundwork

- `links/build.gradle.kts`: add `implementation(project(":mvi"))` and `testImplementation(libs.coroutines.test)`.
  `:mvi` already exposes lifecycle-viewmodel-compose, lifecycle-runtime-compose and coroutines through `api`,
  so you can drop those two lines from `:links` (optional).
- Create empty packages `domain/{model,port,repository,usecase}`.

**Done when:** it builds. Nothing else has changed.

### Phase 1: Domain model and repository interface

1. `domain/model/Link.kt`:
   ```kotlin
   /** A saved link, newest-first order is the repository's. */
   data class Link(
       val id: Long,
       val url: String,
       val title: String,
       val createdAtMillis: Long,
       /** Local thumbnail; null until one has downloaded, or when the user chose the link icon. */
       val thumbnailPath: String?,
       val tags: List<String>,
   )

   data class TagCount(val name: String, val linkCount: Int)
   ```
2. Move `LinkImageSource` and `LinkImageState` out of `images/LinkImageStore.kt` into `domain/model/LinkImage.kt`.
   Fix the import in `:sync` `LinkSync.kt`.
3. Move `linkDomain()` from `ui/CreateLinkScreen.kt` to `domain/model/LinkAddress.kt`. Fix the imports in
   `MainActivity`, `CreateLinkViewModel`, `CreateLinkScreen` and `LinksScreen`.
4. `domain/model/LinkDraft.kt`:
   `data class LinkDraft(val url: String, val title: String, val tags: List<String>, val image: LinkImageSource = Unknown, val imageHidden: Boolean = false)`.
5. `domain/repository/LinksRepository.kt`, an interface that mirrors today's class but in domain types:
   ```kotlin
   interface LinksRepository {
       /** Newest first. */
       fun observeLinks(): Flow<List<Link>>
       /** Every tag, unused ones too (count 0), by name. */
       fun observeTagCounts(): Flow<List<TagCount>>
       fun observeTagNames(): Flow<List<String>>
       /** The saved link [url] is an address of (by linkUrlKey), or null; follows saves as they happen. */
       fun observeSavedLink(url: String): Flow<Link?>
       /** No-op when the name exists, ignoring case. */
       suspend fun createTag(name: String)
       suspend fun setLinkTags(linkId: Long, tagNames: List<String>)
       suspend fun deleteLink(id: Long)
       suspend fun saveLink(draft: LinkDraft, nowMillis: Long): SaveLinkResult
   }

   sealed interface SaveLinkResult {
       data class Saved(val linkId: Long) : SaveLinkResult
       /** The address was saved between the form's live check and the insert. */
       data object AlreadySaved : SaveLinkResult
   }
   ```
   `observeSavedLink` returning a `Link` with tags needs one new DAO query:
   `@Transaction @Query("SELECT * FROM links WHERE urlKey = :urlKey LIMIT 1") fun observeWithTagsByUrlKey(urlKey: String): Flow<LinkWithTags?>`.
   This is not a schema change. It also lets `LinksLinkCatalog` drop its `tagsOf()` scan over every link.
6. Rename `data/LinksRepository.kt` to `data/RoomLinksRepository.kt` (implements the interface; drop `@Inject`/`@Singleton`).
   Add `data/Mappers.kt` with `LinkWithTags.toDomain()` and `TagLinkCount.toDomain()`. Replace
   `System.currentTimeMillis()` with the `nowMillis` parameter.
7. `LinksModule`: `@Provides @Singleton fun provideRepository(linkDao, tagDao, imageStore): LinksRepository = RoomLinksRepository(...)`.
8. `:app` `LinksLinkCatalog`: switch to the interface and `Link` (`it.link.id` → `it.id`, `toSavedLink()` gets simpler).
   `findOrSave` treats `SaveLinkResult.AlreadySaved` the way it treats `false` today.
9. Mechanical swap in the old ViewModels and screens: `LinkWithTags` → `Link`, `link.link.x` → `link.x`,
   `tagNames` → `tags`, `LinkEntity` → `Link`, `TagLinkCount` → `TagCount`. `LinkCardThumbnail` reads `link.thumbnailPath`.

**Done when:** the app behaves identically. `:sync` still compiles (it never touched the repository).

### Phase 2: Ports and use cases (+ tests)

- `domain/port/Clock.kt`: copy Topics'.
- `domain/port/PageReader.kt`: `interface PageReader { suspend fun read(url: String): PageMetadata }`.
  Move the `PageMetadata` data class (and its `internal fun urlWords`) to `domain/model`. `PageMetadataFetcher : PageReader`.
- `domain/port/TagSuggester.kt`: move the interface and `TagSuggestion`. **Contract change:** "never throws;
  returns empty when it can't suggest". Move the `try/catch + Log.w` from `CreateLinkViewModel` **and** from
  `LinksLinkCatalog.suggestTags` into `EmbeddingTagSuggester`. That removes a duplicate, and `Log` leaves the ViewModel.
- `domain/port/ImageDownloads.kt`: `fun image(imageUrl: String): Flow<LinkImageState>` and `fun retry(imageUrl: String)`.
  `LinkImageStore : ImageDownloads`. `attachWhenReady` and `deleteImages` stay on the concrete class. Only `RoomLinksRepository` calls them.
- Move `tagCandidates()` to `domain/usecase/TagCandidates.kt` (it's pure).
- Use cases (plain classes, `operator fun invoke`, built in `LinksModule`):
  `ObserveLinks`, `ObserveTagCounts`, `ObserveTagNames`, `DeleteLink`, `SetLinkTags`, `CreateTag`,
  `SaveLink(repository, clock)`, `ObserveDuplicate(repository)`, `AnalyzeLink(pageReader, tagSuggester, imageDownloads)`.
  See **C4** for `AnalyzeLink` and **C7** for `SaveLink`.
- `di/LinksModule`: bind the ports (`PageReader` ← `PageMetadataFetcher`, `ImageDownloads` ← `LinkImageStore`,
  `Clock` ← `Clock.System`) and add `@Provides` for each use case. `:app` `DataModule.provideLinkCatalog` now takes `PageReader`.
- Tests: `test/.../domain/Fakes.kt` (`FakeLinksRepository` on `MutableStateFlow`s like `FakeTopicsRepository`,
  `FakePageReader`, `FakeTagSuggester`, `FakeImageDownloads`), `AnalyzeLinkTest`, `SaveLinkTest`, `ObserveDuplicateTest`.

**Done when:** use-case tests pass and the old ViewModels have switched to use cases. The UI is unchanged.

### Phase 3: Links list contract (pure, no UI change yet)

Write `ui/list/LinksContract.kt` (sketch in **C1/C2**) and `LinksStateTest` for every derived value.
Nothing uses it yet. Doing it first means the tricky derivations are pinned down before the screen moves.

### Phase 4: Links list on MVI ⚠

- `LinksViewModel : MviViewModel<LinksState, LinksIntent, LinksEffect>`. Port the undo window (**C3**), the tray and
  draft (**C2**), and the clipboard, open and share effects.
- Split `LinksScreen.kt`:
  - `ui/LinksScreen.kt` keeps the public signature `LinksScreen(search, modifier, onCreateLink)` so `RootScreen`
    doesn't change, and delegates to `LinksRoute`.
  - `LinksRoute` gets `hiltViewModel()`, collects state, forwards `search.query` as `QueryChanged`, calls
    `search.updateSearchable(...)` from state, and handles effects (clipboard, `openLink`, `shareLink`, `onCreateLink`).
    The `Context.openLink` and `Context.shareLink` helpers move here.
  - `LinksListScreen(state, query, onIntent)` is stateless apart from pure UI bits: `copyTap`, `nowMillis`,
    tap-outside bounds, bring-into-view, the new-tag text field and focus.
  - `LinkCard`, `DeletedLinkRow`, `TagFilters` and `LinkTagEditor` go to `ui/list/components/` and take plain lambdas,
    as Topics' `TopicCard` does.
- Delete `LinksScreenUiState.kt` and the old `PendingDeletion` (now `PendingLinkDeletion` in the contract).
- Tests: `LinksViewModelTest` (copy the structure of `TopicsListViewModelTest`).

**Done when:** you have walked the list half of the regression checklist.

### Phase 5: CreateLink contract (pure)

`ui/create/CreateLinkContract.kt` (sketch in **C5**) plus `CreateLinkStateTest`. Move `PageReadPhase`, `AlreadySavedLink`
and the analysis type to `domain/model/LinkAnalysis.kt`. `PreviewImage` stays in the UI contract; it is derived.

### Phase 6: CreateLink on MVI ⚠

- `CreateLinkViewModel : MviViewModel<CreateLinkState, CreateLinkIntent, CreateLinkEffect>`. Pipeline in **C4**,
  field/state split in **C5**, lifetime in **C6**, save in **C7**.
- `CreateLinkRoute(onBack, initialUrl)` wraps `ScopedViewModelStore(key = "create-link:$initialUrl")`. Keep the
  public `CreateLinkScreen(onBack, modifier, initialUrl)` as a thin alias that calls the route, so `RootScreen` needn't change.
  You can also rename it and update the one call site.
- Stateless `CreateLinkScreen(state, url, title, newTagName, …, onIntent)`. `CreateLinkContent`'s long parameter list mostly
  collapses into `state` + `onIntent`.
- `LinkPreviewSection`: `setHidden(false)` no longer calls `onRetryImage()` itself. It sends `SetImageHidden(false)`, and the
  ViewModel decides to retry. The haptic stays in the UI.
- Tests: `CreateLinkViewModelTest` (debounce, cancellation, save guard, effects, tag rules, image-hidden rules).

**Done when:** you have walked the create half of the regression checklist.

### Phase 7: Tidy up

- Move shared UI into `ui/common/` (`LinkThumbnail`, `LinkCopyAnimation`, `TagChip`, `LinkFormat.kt` with `relativeAge` and `displayTitle`).
  `relativeAge` is pure; give it a test like Topics' `updatedLabel` test.
- Optional: `OptionsCardBounds` + the `pointerInput` block duplicate Topics' `ui/list/TapOutsideToClose.kt`. Hoist one copy into `:design`.
- Optional: move `images/` and `tagging/` under `data/` using Android Studio's *Move* refactoring, which updates `:app` and `:sync`.
- Update `docs/` or the module KDoc (a comment on top of `links/build.gradle.kts`, as `:topics` has).

---

## 6. The complex parts

### C1. Turning the `combine` lambda into state + derived values

Today, one `combine(links, tagCounts, query, selectedTags, deletions)` lambda does all the work. In MVI, each
source is stored in the state (DB flows via `reduceInto`, query and tags via intents). The lambda's body turns into `val`s:

```kotlin
data class LinksState(
    /** Before the first read, so the empty state doesn't flash for users who have links. */
    val loading: Boolean = true,
    /** Every saved link, newest first, minus ones already deleted. The one in its undo window is still here. */
    val links: List<Link> = emptyList(),
    val tags: List<TagCount> = emptyList(),
    /** The top bar's search text, as last reported. */
    val query: String = "",
    val selectedTags: Set<String> = emptySet(),
    val pendingDeletion: PendingLinkDeletion? = null,
    /** The card whose tray is open. Read [openOptionsLinkId] instead. */
    val optionsLinkId: Long? = null,
    /** Non-null while the open card edits its tags. */
    val tagDraft: TagDraft? = null,
) {
    val empty: Boolean = !loading && links.isEmpty()

    /** The undo row only shows while its link is still there to show. */
    private val pendingLink: Link? = pendingDeletion?.let { p -> links.firstOrNull { it.id == p.linkId } }
    val visiblePendingDeletion: PendingLinkDeletion? = pendingDeletion?.takeIf { pendingLink != null }

    /** Selections for tags that no longer exist are ignored, so they can't hide every link. */
    val activeTags: Set<String> = selectedTags.filterTo(mutableSetOf()) { name -> tags.any { it.name == name } }

    val visibleLinks: List<Link> = links.filter { it.matches(query.trim()) && it.tags.containsAll(activeTags) }

    /** Counts leave out the link in its undo window. */
    val tagCounts: List<TagCount> = tags.map { if (pendingLink != null && it.name in pendingLink.tags) it.copy(linkCount = it.linkCount - 1) else it }
    val linkCount: Int = links.size - if (pendingLink != null) 1 else 0

    /** The tray closes when its link is filtered out or deleted; see C2 for "for good". */
    val openOptionsLinkId: Long? = optionsLinkId?.takeIf { id -> id != pendingDeletion?.linkId && visibleLinks.any { it.id == id } }
}
```

Watch-outs:
- `empty` checks `links` (after the committed filter) **before** subtracting the pending link. Today, when the only
  link is in its undo window, the list shows its undo row, not the empty screen. Keep that.
- `visiblePendingDeletion` reproduces today's `pending = deletions.pending?.let { saved.firstOrNull … }`: if the
  pending link vanishes from the DB some other way (sync), the undo row goes and the count stops subtracting.
- Every `copy()` recomputes the filter. That is fine at these sizes, and Topics does the same.
- `Link.matches(query)` (title, url or any tag contains it, ignoring case) moves to `domain/model/LinkSearch.kt` and gets its own test.

The query comes from `TopBarSearch`, which the host owns. The route forwards it:
`LaunchedEffect(search) { snapshotFlow { search.query }.collect { onIntent(LinksIntent.QueryChanged(it)) } }`.
`search.updateSearchable(...)` becomes `LaunchedEffect(state.loading, state.linkCount) { if (!state.loading) search.updateSearchable(state.linkCount > 0) }`, as in Topics.

### C2. Options tray and tag-editor draft: moving remembered UI state into the ViewModel

This is the hardest part of the list screen. The current behaviour is spread over `rememberSaveable`s, `closeCard()`,
`draftWithNewTag()` and a `LaunchedEffect(openLinkId)`.

Contract:

```kotlin
/** The open card's tag edits; nothing is written until the tray closes. */
data class TagDraft(
    /** Stored spellings, plus names not saved as tags yet. */
    val tags: List<String>,
    /** The "+ new tag" field is open. Its text lives in the screen. */
    val adding: Boolean = false,
)

sealed interface LinksIntent {
    data class QueryChanged(val query: String) : LinksIntent
    data class ToggleTagFilter(val tag: String) : LinksIntent
    data class Copy(val linkId: Long) : LinksIntent
    data class ShowOptions(val linkId: Long) : LinksIntent
    /** Tap outside, back or Done. Saves the draft, including a name still in the new-tag field. */
    data class CloseOptions(val typedTagName: String = "") : LinksIntent
    data class Open(val linkId: Long) : LinksIntent
    data class Share(val linkId: Long) : LinksIntent
    data class Delete(val linkId: Long) : LinksIntent
    data object UndoDelete : LinksIntent
    data object EditTags : LinksIntent
    data class ToggleDraftTag(val tag: String) : LinksIntent
    data object StartNewTag : LinksIntent
    data class AddDraftTag(val name: String) : LinksIntent
    data object CreateLink : LinksIntent
}

sealed interface LinksEffect {
    data class CopyUrl(val url: String) : LinksEffect
    data class OpenUrl(val url: String) : LinksEffect
    data class Share(val url: String, val title: String) : LinksEffect
    data object OpenCreateLink : LinksEffect
}
```

Intents carry **ids**, not `Link` objects. The ViewModel looks the link up in `currentState`, so a stale object captured by a lambda can't be acted on.

Rules to move into the ViewModel (each gets a test):
- `ShowOptions(id)` does nothing when `id == openOptionsLinkId`. Today, long-pressing the open card again must not drop the draft.
- `Open`, `Share` and `Delete` clear `optionsLinkId` first. Today the card's lambdas do this.
- `EditTags` sets `tagDraft = TagDraft(link.tags)`.
- `AddDraftTag(name)` and `CloseOptions(typed)` share one pure helper. It trims, reuses the stored spelling of a
  known or draft tag (ignoring case), and appends the name unless it is already there. That is today's `draftWithNewTag()`.
  Make it a function on `LinksState` or `TagDraft` so it can be tested.
- `CloseOptions`: if a draft exists and `draft.toSet() != link.tags.toSet()`, call `SetLinkTags`. Then clear
  `optionsLinkId` and `tagDraft`.
- `editorTags` (known tags + draft names that aren't tags yet) becomes a derived `val`.

**"Closes for good"** is the subtle one. Today, `LaunchedEffect(openLinkId) { if (null) optionsLinkId = null; editingTags = false }`
drops the tray *and its draft* as soon as the link is filtered out, so it doesn't pop back open when the filter is
undone. Topics only needs the derived `openOptionsTopicId`, because its topics never un-hide. **Links can un-hide**
(clear the query or untoggle a tag), so derivation alone would bring the tray back. Normalise after every
state change instead:

```kotlin
/** A tray whose link is no longer on screen is closed, and its draft dropped, not just hidden. */
private fun LinksState.normalized(): LinksState =
    if (optionsLinkId != null && openOptionsLinkId == null) copy(optionsLinkId = null, tagDraft = null) else this

private fun update(reducer: LinksState.() -> LinksState) = setState { reducer().normalized() }
```

Use `update` everywhere, including in the `reduceInto` for links (write it as
`observeLinks().reduceInto { ... }.normalized()`, or add a small `reduceIntoNormalized` helper).

What stays in the screen:
- The new-tag text: `var newTagName by rememberSaveable(state.openOptionsLinkId, state.tagDraft?.adding) { mutableStateOf("") }`.
  The keys reproduce today's resets (opening the editor, adding a tag, closing the field).
- `focusManager.clearFocus()`: call it in the screen's close handler before `onIntent(CloseOptions(newTagName))`
  when `state.tagDraft != null`, so the keyboard drops with the tray.
- `BackHandler(enabled = state.openOptionsLinkId != null)` and the initial-pass `pointerInput` both send `CloseOptions(newTagName)`.
  Remember `rememberUpdatedState` for the lambda the `pointerInput(Unit)` block calls.
- `copyTap` (the copy animation tick) stays local and starts on tap. The clipboard write arrives a moment later through
  `LinksEffect.CopyUrl`, which the eye won't notice. If you'd rather keep the write synchronous with the tap, keeping
  clipboard in the UI is a defensible exception. Pick one and note it in the contract's KDoc.

### C3. Undo-delete window

This is a near-literal port of `TopicsListViewModel.startDeletion / commitPendingDeletion / onCleared`, which was
written from this very code:
- `private val committedDeletions = mutableSetOf<Long>()` applied in the links `reduceInto`
  (`links.filterNot { it.id in committedDeletions }`), because "a deleted link can outlive its row by one emission".
- `startDeletion` commits any previous pending one first, then uses `clock.nowMillis()` (not `System.currentTimeMillis()`)
  for `startedAtMillis` and `deadlineMillis`.
- `commitPendingDeletion` also removes the link from `state.links` right away, as Topics does. Today's code relies on
  the committed filter at the next emission. Doing both is the safer choice.
- `onCleared` + `GlobalScope` stays, with the same comment. (An injected `@LinksScope` app scope, like `@TopicsScope`, would be
  cleaner, but Topics also uses `GlobalScope` here. Stay consistent or change both.)
- Test with `StandardTestDispatcher`, `advanceTimeBy(UNDO_MS - 1)` / `advanceTimeBy(2)` and a fixed `Clock`. Copy
  `TopicsListViewModelTest`'s first three tests nearly verbatim.

### C4. CreateLink's reactive pipeline inside `MviViewModel`

Today there are two pipelines on the typed URL:
- **analysis**: `debounce(600) → trim → distinctUntilChanged → collectLatest { analyze(it) }`. `analyze` sets phases
  on a private `MutableStateFlow`, launches a child that keeps collecting the image download (so a retry later shows up),
  and runs the tag suggester.
- **duplicate check**: `trim → distinctUntilChanged → flatMapLatest { observeSavedLink }`. It is not debounced, because
  Save's enabled state must keep up with typing.

Keep both as flows. Move the analysis into a use case that *returns a flow*, so the phases and the image handling can be tested without the ViewModel:

```kotlin
/** Reads [url]'s page in phases; stays open while the image download can still change (retry). */
class AnalyzeLink(private val pages: PageReader, private val suggester: TagSuggester, private val images: ImageDownloads) {
    operator fun invoke(url: String): Flow<LinkAnalysis> = flow {
        if (linkDomain(url) == null) return@flow emit(LinkAnalysis(url))
        emit(LinkAnalysis(url, phase = PageReadPhase.ReadingPage))
        val page = pages.read(url)
        val read = LinkAnalysis(
            url = url,
            suggestedTitle = page.title,
            candidateTags = tagCandidates(page),
            phase = PageReadPhase.SuggestingTags,
            imageUrl = page.imageUrl,
        )
        // Null until the suggester answers; the image flow never completes, which keeps retries visible.
        val tags: Flow<List<String>?> = flow { emit(null); emit(suggester.suggest(page).map { it.tagName }) }
        val image: Flow<LinkImageState?> = page.imageUrl?.let(images::image) ?: flowOf(null)
        emitAll(combine(tags, image) { t, i ->
            read.copy(suggestedTags = t.orEmpty(), phase = if (t == null) PageReadPhase.SuggestingTags else PageReadPhase.Done, image = i)
        })
    }
}
```

In the ViewModel:

```kotlin
private val typedUrl = MutableStateFlow("")

init {
    observeTagNames().reduceInto { copy(tags = it) }
    typedUrl.map { it.trim() }.distinctUntilChanged()
        .flatMapLatest { observeDuplicate(it) }
        .reduceInto { copy(alreadySaved = it) }
    typedUrl.debounce(URL_DEBOUNCE_MS).map { it.trim() }.distinctUntilChanged()
        .flatMapLatest { analyzeLink(it) }                      // a newer URL cancels the older read
        .reduceInto { a -> copy(analysis = a, imageHidden = imageHidden && a.url == analysis.url) }
}

// CreateLinkIntent.UrlChanged -> typedUrl.value = intent.url
```

Why not a `Job` + `delay` debounce like `TopicSearchViewModel`? With `debounce + distinctUntilChanged + flatMapLatest`,
typing a character and deleting it within 600 ms changes nothing, and the page read already in flight continues.
A hand-written `analysisJob?.cancel()` on every keystroke would cancel it and read the page again. Keep the operators.

Other watch-outs:
- `flatMapLatest` cancels **and joins** the previous inner flow before starting the next. No stale emission from an old URL can
  land after a new one. The `imageHidden && a.url == analysis.url` reducer replaces `rememberSaveable(uiState.analyzedUrl)`:
  the hide choice belongs to one page's image.
- `imageHidden` has to be in state now, because Save reads it. It used to be passed into `save(...)`.
- `candidateTags` filtering ("minus tags the user already has, first 3") becomes a derived `val` on the state. Today it's in
  the `combine`. A candidate disappears the moment it's created as a tag, because `tags` re-emits.
- `PreviewImage` (Unknown / None / Loading / Ready / Failed) becomes a derived function of `analysis.phase`, `analysis.imageUrl`
  and `analysis.image`. Today `analyze()` writes it directly. Mapping: Idle or ReadingPage → Unknown; no `imageUrl` → None;
  `image == null` → `Loading(null)`; otherwise map the `LinkImageState`.
- The two `@OptIn`s (`FlowPreview` for `debounce`, `ExperimentalCoroutinesApi` for `flatMapLatest`) stay.
- Test with a `FakePageReader` that suspends on a `CompletableDeferred` per URL. You can then assert that typing a second URL
  cancels the first read, and that `advanceTimeBy(599)` doesn't start one.

### C5. Field text vs ViewModel state

`url`, `title` and the new-tag name are `BasicTextField`s. Following the Topics rule, **they stay in the screen**
(`rememberSaveable`). The ViewModel hears each URL change (`UrlChanged`) and receives the title only on `Save`.

The trap is state derived by comparing ViewModel state with the *live* field. That comparison has to happen against the
field's current text, not against a copy stored in state. `collectAsStateWithLifecycle` delivers a frame later, so a
stored copy would lag. Make those comparisons **functions on the state that take the field text**:

```kotlin
data class CreateLinkState(
    val tags: List<String> = emptyList(),
    val analysis: LinkAnalysis = LinkAnalysis(),
    val alreadySaved: AlreadySavedLink? = null,
    val selectedTags: List<String> = emptyList(),
    val addingTag: Boolean = false,
    val imageHidden: Boolean = false,
    val saving: Boolean = false,
    /** Saved: the form is on its way out, so the link it just saved mustn't flash up as a duplicate. */
    val saved: Boolean = false,
) {
    val candidateTags: List<String> = …                       // C4
    fun isAnalysisFor(fieldUrl: String) = analysis.url == fieldUrl.trim()
    fun phaseFor(fieldUrl: String) = if (isAnalysisFor(fieldUrl)) analysis.phase else PageReadPhase.Idle
    fun imageFor(fieldUrl: String): PreviewImage = if (isAnalysisFor(fieldUrl)) previewImage else PreviewImage.Unknown
    fun duplicateOf(fieldUrl: String): AlreadySavedLink? = alreadySaved?.takeIf { !saved && it.url == fieldUrl.trim() }
    /** The page's own title, once it belongs to what's in the field. */
    fun titleSuggestionFor(fieldUrl: String): String? = analysis.suggestedTitle?.takeIf { isAnalysisFor(fieldUrl) }
    fun canSave(fieldUrl: String) = linkDomain(fieldUrl) != null && duplicateOf(fieldUrl) == null && !saving
}
```

Title autofill stays a `LaunchedEffect` in the screen, because it writes to a field the screen owns:
`LaunchedEffect(state.analysis) { state.titleSuggestionFor(url)?.let { if (title.isBlank()) title = it } }`.

Send `UrlChanged` directly from `onValueChange`, plus `LaunchedEffect(Unit) { onIntent(UrlChanged(url)) }`, so a
pre-filled or restored address is analysed. Today `LaunchedEffect(url)` covers both. Either works. The direct call avoids a one-frame delay.

Tag intents:
- `ToggleTag(tag)`, `StartNewTag`, `CancelNewTag` (the second `BackHandler`), `AddTag(name)`.
- `AddTag(name)`: trim; if blank, only close the field. Otherwise reuse the stored spelling (`tags.firstOrNull { equals(ignoreCase) }`),
  and if the tag is new, call `CreateTag` **right away**. That is today's behaviour: it makes the candidate chip disappear, and it
  means a tag can exist before the link is saved. Then select it if it isn't selected, and close the field.
- The "registered after the screen-level handler" ordering of the two `BackHandler`s stays in the screen as it is.

### C6. ViewModel lifetime for the CreateLink overlay

`RootScreen` shows `CreateLinkScreen` as an overlay under `key(overlay.url)`, with `hiltViewModel()` on the **activity's**
store. So one `CreateLinkViewModel` serves every opening of the form. That is why the comments say "the view model outlives
a single form", and why the screen has to guard against a leftover title and analysis.

Fix it the way Topics' overlays do:

```kotlin
@Composable
fun CreateLinkRoute(onBack: () -> Unit, initialUrl: String, modifier: Modifier = Modifier) {
    // Keyed on the address as well as RootScreen's key(): see below.
    ScopedViewModelStore(key = "create-link:$initialUrl") {
        CreateLinkForm(onBack, initialUrl, modifier, viewModel = hiltViewModel())
    }
}
```

**Put the URL in the key.** When a new share replaces a half-filled form, `key(overlay.url)` removes the old
`ScopedViewModelStore` and composes the new one in the same pass. The new one fetches its store during composition. The old
one's `onDispose` (which clears its key's store) runs after that. With a constant key like `"create-link"`, the old form's
dispose would clear the store the new form had just started using. With distinct keys, each form clears only its own store.

### C7. Save: callback to effect

Today: `save(url, title, tags, imageHidden, onSaved)` with an `isSaving` field, a `try/catch` that logs, and `onSaved()`
only when the insert succeeded.

MVI version:

```kotlin
private fun save(url: String, title: String) {
    val state = currentState
    if (!state.canSave(url)) return                          // also covers a second tap while saving
    setState { copy(saving = true) }
    val address = url.trim()
    viewModelScope.launch {
        val draft = LinkDraft(address, title.trim(), state.selectedTags, state.analysis.imageSourceFor(address), state.imageHidden)
        when (saveLink(draft)) {
            // Stays saving: the form is on its way out, and a second tap mustn't save twice.
            is SaveLinkResult.Saved -> { setState { copy(saved = true) }; sendEffect(CreateLinkEffect.Saved) }
            // The live check lagged the field; alreadySaved now shows why.
            SaveLinkResult.AlreadySaved -> setState { copy(saving = false) }
        }
    }
}
```

- `LinkAnalysis.imageSourceFor(url)` is today's `when` expression, moved to `domain/model` as a pure function with tests:
  a different URL, Idle or ReadingPage → `Unknown`; a known `imageUrl` → `Known`; otherwise `None`.
- Errors: to keep `Log` out of the ViewModel, either add `SaveLinkResult.Failed`, with `RoomLinksRepository` catching and logging,
  or catch in the ViewModel without logging. Unexpected exceptions used to be logged, so the first option keeps that. Whichever you
  pick, rethrow `CancellationException`. Don't use `runCatching` for this.
- Route: `ObserveEffects(viewModel.effects) { when (it) { CreateLinkEffect.Saved -> onBack() } }`.
- `ObserveEffects` only delivers while the screen is STARTED. If the user backgrounds the app mid-save, the form closes when
  they return, which is correct.

### C8. Cross-module edits, in the order they'll bite

| Phase | File | Change |
|---|---|---|
| 1 | `:sync` `links/LinkSync.kt` | `LinkImageSource` import → `dev.kortex.links.domain.model` |
| 1 | `:app` `MainActivity.kt` | `linkDomain` import → `dev.kortex.links.domain.model` |
| 1 | `:app` `data/topics/LinksLinkCatalog.kt` | Repository interface, `Link`, `SaveLinkResult`. `tagsOf()` goes (`observeSavedLink` has tags now) |
| 2 | `:app` `data/topics/LinksLinkCatalog.kt` | `PageMetadataFetcher` → `PageReader`. Drop its `suggestTags` try/catch (the adapter owns it now) |
| 2 | `:app` `di/DataModule.kt` | `provideLinkCatalog(links: LinksRepository, pages: PageReader, tagSuggester: TagSuggester)` |
| 4/6 | `:app` `RootScreen.kt` | None, if you keep `LinksScreen(...)` and `CreateLinkScreen(...)` as the public entry points |

`AppModule` and `CloudSync` use `LinkSyncDao` and `LinkImageStore` and are untouched.

### C9. JVM tests

- `android.util.Log` throws "Method … not mocked" in plain unit tests. After this plan, `Log` exists only in `data/`, `images/`
  and `tagging/`, so ViewModel and domain tests are safe. Don't add `unitTests.isReturnDefaultValues = true` to hide a leak.
- `LinkImageStore` and `PageMetadataFetcher` never appear in tests. Only their ports do.
- `Dispatchers.setMain(StandardTestDispatcher())` for the timing tests (undo, debounce). `UnconfinedTestDispatcher` is fine for simple intent → state tests (see `NewTopicViewModelTest`).
- `effects` is a `Channel`: `viewModel.effects.first()` reads one. Assert "no effect" with
  `withTimeoutOrNull(1) { effects.first() } == null` under the test dispatcher, or collect into a list in `backgroundScope`.

---

## 7. Behaviours to preserve (regression checklist)

**Links list**
- [ ] No empty-state flash on launch (loading renders nothing).
- [ ] The empty state appears only with no links at all. Deleting the only link shows its undo row, not the empty state.
- [ ] Search matches title, URL or any tag name, ignoring case, with the query trimmed.
- [ ] Tag filters AND together. A selected tag that stops existing no longer filters.
- [ ] Header shows `N LINKS · M TAGS`, or `resultsLabel` while searching. The count and tag counts leave out the link in its undo window.
- [ ] The top-bar search button hides when there are 0 links.
- [ ] Tap copies with the animation. Tapping again restarts it. A card scrolled back into view doesn't replay it.
- [ ] Long-press opens the tray: the card border, the neighbours dim, then the header and tags dim. Long-pressing the open card keeps the draft.
- [ ] A tap outside closes the tray without pressing or scrolling what's under it. Back closes the tray before the search collapses.
- [ ] Open / Share / Delete close the tray. Open adds `https://` to scheme-less URLs. "No app can open this link" shows as a toast.
- [ ] Delete → undo row with countdown → really deleted after 5 s. Undo restores it. A second delete ends the first window early. Leaving the screen commits the pending delete.
- [ ] A deleted link doesn't flash back for one emission.
- [ ] Tag editor: toggles, "+ new tag" in place, typed names reuse stored spellings, Done/back/tap outside save **only if changed**, and a name left in the field is included. The keyboard drops with the tray.
- [ ] Filtering out the open card closes the tray and drops the draft, and it stays closed when the filter is undone.
- [ ] Thumbnails crossfade in when a download finishes after the save. Hidden images show the glyph.

**Create link**
- [ ] A pre-filled address (share intent) is analysed at once. A new share replaces a half-filled form.
- [ ] Analysis waits 600 ms after typing stops. A newer URL cancels the older read. Typing and undoing within 600 ms doesn't re-read.
- [ ] Phases: Idle → ReadingPage → SuggestingTags → Done, and the preview treats the page as unread until the analysis matches the field.
- [ ] The title autofills only while the field is blank, and never from a different URL.
- [ ] The duplicate warning follows the field with no debounce, and blocks Save. The link just saved doesn't flash up as a duplicate while the form closes.
- [ ] Suggested tags come first in the tag list. Candidates exclude existing tags (ignoring case), at most 3. A candidate disappears once created.
- [ ] A new tag typed in another case selects the existing chip. A brand-new tag is created immediately. Back closes the new-tag field before the form.
- [ ] The hide-image choice resets when the analysed address changes. Showing a failed image retries it. Retry works after the download ended.
- [ ] Double-tapping Save saves once. Saving doesn't wait for the page or image, and the thumbnail attaches later.

---

## 8. Test inventory

| Test | Covers |
|---|---|
| `domain/LinkSearchTest` | `Link.matches` |
| `domain/AnalyzeLinkTest` | invalid URL → Idle; phase order; no image → `image == null` and `imageUrl == null`; the image flow keeps it open; suggester empty → Done |
| `domain/SaveLinkTest` | trims, uses the clock, maps a constraint clash to `AlreadySaved` (in the fake) |
| `domain/ObserveDuplicateTest` | no domain → null without hitting the repository; saved address → `AlreadySavedLink(typed, title)` |
| `domain/LinkAnalysisTest` | `imageSourceFor` |
| `ui/LinksStateTest` | everything in C1; `openOptionsLinkId`; the `draftWithNewTag` helper |
| `ui/LinksViewModelTest` | undo window (C3); tray rules and close-for-good (C2); effects for copy, open and share |
| `ui/CreateLinkStateTest` | `duplicateOf`, `phaseFor`, `imageFor`, `candidateTags`, `canSave`, `titleSuggestionFor` |
| `ui/CreateLinkViewModelTest` | debounce and cancellation (C4); `AddTag` spelling and create; `imageHidden` reset and retry-on-show; save guard, `Saved` effect, `AlreadySaved` → no effect |
| `ui/LinkFormatTest` | `relativeAge` |

The existing `LinkUidTest`, `LinkUrlKeyTest` and `SyncMergeTest` are untouched.

---

## 9. Out of scope

- Moving `:sync` onto domain types.
- Sharing `Clock` / tap-outside / `TagChip` between `:links` and `:topics` through a common module. Worth doing once a third feature needs them.
- `SavedStateHandle` for process-death restore of ViewModel-held state (decision 10).
- Topics' `LinkCatalog` adapter in `:app` beyond the type changes in C8.
