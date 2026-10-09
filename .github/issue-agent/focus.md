# Daily issue agent — focus areas

The scheduled Claude agent (`.github/workflows/claude-daily-issues.yml`) reads this file
on every run. Edit the list below to steer what it looks for; the change takes effect
on the next run once it is on `main`.

The agent runs every hour (24 times a day, on the hour in IST) and files one issue
per run. Each run is assigned the next area in this list, wrapping back to 1 after the
last, so with 11 areas every area comes up once every 11 hours. If a run finds nothing
worth filing in its area, it moves on to the next area in the list.

Keep each area on the form `N. **Title** — description`: the workflow reads the bold
titles to build the rotation and the `area:<title>` labels, so renaming a title starts a
new label. The focus areas are written to apply to any Android project; everything
specific to this project lives under **Project context** below.

## Project context

- **Design source:** Figma file Kortex:
  https://www.figma.com/design/8CBjHcKRkTl4AroVOQlhL4/Kortex?m=auto&t=9HVYWMYh5Rh0d1pZ-6
  (the workflow exports the first Figma link in this file for the design-system area).
  The `:design` module holds the theme tokens and shared components.
- **Module layering:** `:design` holds UI components and `:links` the graph tools; `:app`
  is split into `di`/`data`/`domain`/`ui` packages, with manifest components in the root
  package. `:wa` ships as an SDK and must not depend on the app or core-agent modules.
- **State management:** feature modules follow MVI.
- **Sensitive data:** bank SMS messages, financial entries, Gmail content and account
  tokens.

## Focus areas

1. **Bugs and crashes** — code that crashes or does the wrong thing. Null safety: `!!`,
   unchecked casts, `lateinit` read before it is set, nullable platform types from Java
   APIs treated as non-null. Exceptions: I/O, parsing, database and network calls with
   no handling at the boundary, and empty `catch` blocks that hide failures. Coroutines:
   `CancellationException` caught and not rethrown, work launched in a scope that dies
   before it finishes or outlives its caller, `async` results never awaited, `runBlocking`
   in app code. Races: shared mutable state written from several coroutines or threads
   without a `Mutex` or atomic, check-then-act on `StateFlow.value` instead of `update {}`.
   Lifecycle: state lost on configuration change or process death, `LaunchedEffect` and
   `remember` keyed wrongly so they re-run or capture stale values. Edge cases: empty and
   single-item lists, off-by-one errors, time zones and date boundaries, locale-dependent
   parsing and formatting.
2. **Security and privacy** — sensitive user data (see Project context) and the attack
   surface of the app. Data handling: sensitive values written to logs, crash reports or
   analytics; stored unencrypted; kept after it is no longer needed; included in backups
   (`allowBackup`, backup rules). Secrets in source: API keys, tokens and signing details
   committed to the repo or baked into `BuildConfig`. Storage: tokens in plain
   SharedPreferences or files, data on external storage, world-readable files. Components:
   activities, services, receivers and providers exported without a permission; deep links
   and intent extras trusted without validation; implicit intents carrying sensitive data;
   `PendingIntent`s without `FLAG_IMMUTABLE`. Network and WebView: cleartext traffic,
   custom `TrustManager`s or disabled certificate checks, `addJavascriptInterface` or file
   access on untrusted content. Permissions that are requested but unused, or broader than
   the feature needs. SQL built by string concatenation from input.
3. **Architecture** — module- and package-level structure (see Project context for this
   project's rules). Dependencies that break the documented layering: feature modules
   depending on each other directly instead of through a shared contract, UI depending on
   data-layer implementations, library or SDK modules depending on the host app, circular
   module dependencies. Code in the wrong module or package for its layer. Inconsistent
   state-management patterns across features: state, events and side effects shaped
   differently from one feature to the next, state mutated outside the reducer or
   ViewModel, one-off events modelled as persistent state. Dependency injection: bindings
   in the wrong module or scope, objects constructed by hand where they should be injected.
4. **Performance** — work that makes the app slow or janky. Main thread: disk, database,
   network, heavy parsing or image work on `Dispatchers.Main`. Compose: unstable
   parameters (plain `List`s, unstable lambdas or classes) that stop skipping, fast-changing
   state read high in the tree instead of deferred into lambda modifiers, lazy lists without
   stable `key`s, expensive calculations not wrapped in `remember` or `derivedStateOf`.
   Data: unbounded queries, N+1 query loops, missing indices on columns used for filtering,
   joining or sorting, whole rows loaded where a projection would do, the same Flow
   collected several times without `stateIn`/`shareIn`. Startup: heavy work in
   `Application.onCreate`, eagerly created singletons, and no baseline profile.
5. **Test coverage** — important logic with no tests, or tests that do not protect it.
   Prioritise code where a bug costs the most and a test is cheap: parsers, mappers,
   reducers and state machines, repositories with caching or merge logic, use cases with
   branching, money and date calculations, database migrations. Also report tests that
   assert nothing meaningful, are disabled, or only test their own mocks. Name the specific
   cases to cover (including edge cases) and where the test should live; prefer JVM unit
   tests over instrumented tests where the code allows it.
6. **Design system** — UI must follow the project's design source and theme (see Project
   context). Hardcoded colours, dimensions, text sizes, shapes and typography where theme
   tokens exist; components re-implemented locally instead of using the shared ones;
   screens whose spacing, hierarchy or component choice diverges from the design; loading,
   empty and error states the design defines but the code lacks; dark theme breakages.
   Accessibility: missing content descriptions, touch targets under 48dp, insufficient
   contrast, layouts that break at large font scales.
7. **Clean architecture** — class- and function-level design. Layer boundaries: domain
   code depending on Android framework or data-layer types, business logic sitting in
   Composables, ViewModels or repositories instead of the domain layer. SOLID: classes with
   several unrelated responsibilities, `when` chains over types that must be edited for
   every new case where polymorphism fits, interfaces that force clients to depend on
   methods they do not use, concrete dependencies where an abstraction is needed for
   testing or substitution. Clean code: long functions, deep nesting, unclear names, boolean
   flag parameters, magic numbers, comments that no longer match the code.
8. **Code duplication** — logic copied across files or modules with small differences:
   mappers, validation, date and currency formatting, error handling, Composables that
   differ only by a parameter, repository boilerplate, build configuration repeated across
   module build files (a convention plugin would do). Name every copy. Only report
   duplication whose removal keeps the code at least as readable, and leave alone code that
   merely looks alike but changes for different reasons.
9. **New Features** — incremental improvements to existing features and new features that
   make the product better. Gaps in existing flows: missing edit, undo, search, sort or
   filter; empty states with no next step; actions that give no feedback; flows that take
   more steps than they need. Half-built features and things the docs promise that the
   code does not do. Ground each proposal in the current code with file references and
   keep it small enough for one pull request.
10. **Memory management** — memory leaks and memory the app holds longer or in larger
   amounts than it needs. Leaks: Activity/View/Context references kept by singletons,
   companion objects or long-lived Hilt bindings (Application context should be used
   instead); listeners, callbacks, BroadcastReceivers and ContentObservers that are never
   unregistered; `callbackFlow` without `awaitClose`; `GlobalScope` or hand-made
   `CoroutineScope`s that are never cancelled; Flows collected without lifecycle awareness
   (`collectAsState` where `collectAsStateWithLifecycle` belongs); players, WebViews and
   other native resources that are never released; Cursors and streams not closed with
   `use {}`. Inefficient use: bitmaps decoded at full size instead of downsampled, whole
   tables or lists loaded into memory where paging or a bounded query would do, in-memory
   caches and maps with no size limit, and large objects held in `remember`, ViewModel
   state or `StateFlow`s after the screen no longer needs them.
11. **App size** — files, resources and dependencies left behind by refactors and
   changed or removed features. Unused: drawables, layouts, strings, colours, dimens,
   raw files and assets; Kotlin files, classes and Composables nothing calls; Gradle
   dependencies and version-catalog entries no module uses; empty or orphaned modules.
   Oversized: large PNG/JPEG resources that could be vector drawables or WebP, assets
   shipped in every density when one would do, and release builds without
   `isMinifyEnabled`/`isShrinkResources`. Work through the project slowly and
   incrementally: each issue covers one small, self-contained batch (one module or one
   resource type), not a project-wide sweep. Before calling anything unused, Grep for
   every way it can be referenced — `R.<type>.<name>`, `@<type>/<name>` in XML and the
   manifest, `painterResource`/`stringResource`, `getIdentifier` and other name-based
   lookups, reflection, and other modules — and leave out anything that might be loaded
   dynamically.
