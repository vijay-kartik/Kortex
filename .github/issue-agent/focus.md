# Daily issue agent — focus areas

The scheduled Claude agent (`.github/workflows/claude-daily-issues.yml`) reads this file
on every run. Edit the list below to steer what it looks for; the change takes effect
on the next run once it is on `main`.

Each run files at least 5 new issues, spread across these areas. Areas are listed
in priority order.

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


## Out of scope

- Pure style or formatting nits.
- Anything already described as planned in `docs/*_PLAN.md` (unless the implementation
  diverges from the plan in a way that causes a bug).
