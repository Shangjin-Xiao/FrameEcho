
## 2025-02-17 - Compose String Resources in Semantics
**Learning:** In Jetpack Compose, the `semantics` modifier block does not provide a `@Composable` context. Attempting to call `stringResource(id)` directly within `Modifier.semantics { contentDescription = stringResource(id) }` will cause a compilation error.
**Action:** Always evaluate `stringResource` outside the modifier block and capture it in a local variable (e.g., `val desc = stringResource(R.string.id)`) before assigning it within the `semantics` closure.
