
## 2025-02-17 - Compose String Resources in Semantics
**Learning:** In Jetpack Compose, the `semantics` modifier block does not provide a `@Composable` context. Attempting to call `stringResource(id)` directly within `Modifier.semantics { contentDescription = stringResource(id) }` will cause a compilation error.
**Action:** Always evaluate `stringResource` outside the modifier block and capture it in a local variable (e.g., `val desc = stringResource(R.string.id)`) before assigning it within the `semantics` closure.

## 2025-02-17 - TooltipWrapper on TopAppBar Actions
**Learning:** Wrapping `IconButton`s in the `TopAppBar` with a `TooltipWrapper` is a clean way to provide explicit text descriptions that screen readers combine into a proper group, while simultaneously giving visual users a helpful long-press hint. It serves as both an accessibility fix and a UI polish.
**Action:** Consistently use `TooltipWrapper` for any icon-only actions placed in `TopAppBar` to ensure discoverability and accessibility.

## 2026-05-06 - Semantic Headings in Settings Sheets
**Learning:** Visual section headers in complex forms or bottom sheets (like Export Settings) are often just styled `Row` or `Text` components. Without explicit semantic tagging, screen readers treat them as normal text, preventing users from quickly jumping between logical sections using heading navigation.
**Action:** Always append `Modifier.semantics { heading() }` to the container or text component of any visual section header to enable native screen reader heading navigation.
