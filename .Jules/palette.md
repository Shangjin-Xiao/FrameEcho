
## 2025-02-17 - Compose String Resources in Semantics
**Learning:** In Jetpack Compose, the `semantics` modifier block does not provide a `@Composable` context. Attempting to call `stringResource(id)` directly within `Modifier.semantics { contentDescription = stringResource(id) }` will cause a compilation error.
**Action:** Always evaluate `stringResource` outside the modifier block and capture it in a local variable (e.g., `val desc = stringResource(R.string.id)`) before assigning it within the `semantics` closure.

## 2025-02-17 - TooltipWrapper on TopAppBar Actions
**Learning:** Wrapping `IconButton`s in the `TopAppBar` with a `TooltipWrapper` is a clean way to provide explicit text descriptions that screen readers combine into a proper group, while simultaneously giving visual users a helpful long-press hint. It serves as both an accessibility fix and a UI polish.
**Action:** Consistently use `TooltipWrapper` for any icon-only actions placed in `TopAppBar` to ensure discoverability and accessibility.
## $(date +%Y-%m-%d) - Added semantics(mergeDescendants) to compose section headers
**Learning:** In Jetpack Compose, visually distinct section headers (e.g., Row containing an Icon and Text) are read by screen readers as individual elements if not grouped. Adding `semantics(mergeDescendants = true) { heading() }` allows screen reader users to jump between sections using heading navigation, improving structural accessibility significantly.
**Action:** Always add `semantics(mergeDescendants = true) { heading() }` to visual section header containers in settings, lists, or forms when they contain multiple elements (like an icon alongside the title text).
