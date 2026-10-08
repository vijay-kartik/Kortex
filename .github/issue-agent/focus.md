# Daily issue agent — focus areas

The scheduled Claude agent (`.github/workflows/claude-daily-issues.yml`) reads this file
on every run. Edit the list below to steer what it looks for; the change takes effect
on the next run once it is on `main`.

The agent runs every hour (24 times a day, on the hour in IST) and files one issue
per run. Each run is assigned the next area in this list, wrapping back to 1 after the
last, so with 10 areas every area comes up once every 10 hours. If a run finds nothing
worth filing in its area, it moves on to the next area in the list.

Keep each area on the form `N. **Title** — description`: the workflow reads the bold
titles to build the rotation and the `area:<title>` labels.

## Focus areas

1. **Bugs and crashes** — null-safety holes, unhandled exceptions, coroutine scope/
   cancellation mistakes, race conditions, lifecycle leaks in ViewModels and Composables.
2. **Security and privacy** — SMS/finance data handling, secrets in source, insecure
   storage, exported Android components, permissions that are broader than needed.
3. **Architecture** — violations of the module layering (e.g. the `:design`/`:links`
   split; `:app` uses di/data/domain/ui), MVI contract inconsistencies across feature
   modules.
4. **Performance** — work on the main thread, unnecessary recompositions, unbounded
   queries or lists, missing indices.
5. **Test coverage** — important logic (parsers, reducers, repositories) with no tests.
6. **Design system** — the code must follow design guide as mentioned in the figma project. Figma project link Kortex file: https://www.figma.com/design/8CBjHcKRkTl4AroVOQlhL4/Kortex?m=auto&t=9HVYWMYh5Rh0d1pZ-6
7. **Clean architecture** — Code must follow clean architecture everywhere. Clean code principles and SOLID principles as well.
8. **Code duplication** — Any duplication in code if it can be reduced, must be reduced. Though not at the cost of code readability.
9. **New Features** — Suggest incremental improvements in the existing features, and also request for new features with the mindset of bettering this product.
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


## Out of scope

- Pure style or formatting nits.
- Anything already described as planned in `docs/*_PLAN.md` (unless the implementation
  diverges from the plan in a way that causes a bug).
