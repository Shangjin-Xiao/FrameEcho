## 2024-05-24 - MetadataExtractor loop key exception reduction
**Learning:** Checking for keys in `MediaFormat` inside loops using an explicit `try-catch` pattern that triggers on exception for fallback is an antipattern, leading to high overhead due to exception generation. Relying on `containsKey` prior to accessing the value avoids exception generation costs. If exceptions are still expected, `runCatching { format.getInteger(key) }.getOrNull()` is a cleaner and more idiomatic Kotlin approach to handle errors without disrupting normal control flow.
**Action:** Use `containsKey` and `runCatching` in scenarios where missing keys or schema mismatches are expected, rather than depending on raw `try-catch` blocks inside iterative paths to control standard flow.

## 2024-05-25 - DateTimeFormatter sequential try-catch performance bottleneck
**Learning:** Sequential `try-catch` blocks around `DateTimeFormatter.parse()` for multiple formats cause a massive performance penalty when processing lists of dates due to the overhead of instantiating `DateTimeParseException`s for every mismatch.
**Action:** Implement fast-path character checks (e.g., `hasZ`, `hasT`, `hasSpace`) to safely skip formatters that will definitely throw, and selectively apply only the subset of formatters that match the string's basic structure before attempting to parse.

## 2026-03-19 - ParsePosition for fast date parsing
**Learning:** Using exceptions for control flow when parsing dates (e.g. `DateTimeParseException`) is extremely slow due to stack trace generation overhead. Sequential `try-catch` blocks for multiple formats cause massive performance degradation.
**Action:** Use `java.text.ParsePosition` and `formatter.parseUnresolved()` to cheaply verify formatting without throwing exceptions, reserving actual `try-catch` resolution for semantically invalid dates.

## 2026-04-30 - Compose modifier .graphicsLayer optimization
**Learning:** Calling `Modifier.graphicsLayer(...)` by passing state variables directly as arguments triggers a full recomposition of the composable every time the state changes.
**Action:** Use the lambda version `Modifier.graphicsLayer { ... }` which defers reading of state variables to the drawing phase. This prevents full recompositions during high-frequency gesture events like zoom or pan, improving CPU usage and UI framerate.

## $(date +%Y-%m-%d) - Jetpack Compose Color Allocation Optimization
**Learning:** In Jetpack Compose components that recompose frequently (such as a `VideoSurface` responding to gesture events), continuously calling inline `.copy(alpha = ...)` on `MaterialTheme.colorScheme` colors inside the Composable function body creates unnecessary, repeated color object allocations.
**Action:** Always extract static color derivations to the top of the Composable using `remember(colorScheme.baseColor) { colorScheme.baseColor.copy(...) }`. This memoizes the allocated color object, improving performance and reducing memory pressure during UI recomposition, while still allowing the color to update correctly if the base theme color changes.
