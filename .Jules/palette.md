## 2024-05-24 - Tap Gesture Consumption for Accessibility
**Learning:** In Jetpack Compose, using `Modifier.clickable` simply to consume clicks (e.g., on a background overlay to prevent click-throughs) introduces accessibility noise. Screen readers will announce the element as a button (Role.Button) because `clickable` automatically adds this semantic role.
**Action:** When blocking click propagation without adding interactive semantics, use `Modifier.pointerInput(Unit) { detectTapGestures { } }` instead of `Modifier.clickable`.
## 2024-05-25 - Dynamic UI State Accessibility
**Learning:** In Jetpack Compose, when dynamic UI states cause a button's inner child components to swap (e.g., exchanging an `Icon` for a `CircularProgressIndicator` during a loading state inside a `FloatingActionButton`), placing `contentDescription` on the inner `Icon` means the button loses its accessibility label entirely during the loading state.
**Action:** Apply the `contentDescription` via `Modifier.semantics` to the parent container (e.g., the `FloatingActionButton`) and set the inner `Icon`'s `contentDescription` to `null`. This ensures the semantic meaning is preserved regardless of the child state.

## 2026-08-26 - Modifier.pointerInput Stale Closure Bug
**Learning:** When using `Modifier.pointerInput(Unit)` to handle gestures (e.g., in a video player surface for tap-to-play), any state variables or callbacks (like `onTogglePlayPause` or `exoPlayer.isPlaying`) referenced inside the block must be wrapped in `rememberUpdatedState`. Because `pointerInput(Unit)` memoizes its block for the lifetime of the component, capturing raw variables results in a stale closure bug if the parent recomposes and provides new instances.
**Action:** Always wrap lambda callbacks and changing state references in `rememberUpdatedState` before accessing them inside `pointerInput(Unit)` to ensure the gesture detector executes with the freshest context.

## 2023-10-24 - Screen Reader Labels for Dynamic Progress Indicators
**Learning:** In Jetpack Compose, an indeterminate `CircularProgressIndicator` does not natively announce its purpose to screen readers unless explicitly given semantic meaning. When used as a full-screen loading overlay (e.g., buffering or exporting states), it can create a "silent" blocking UI for visually impaired users. Furthermore, calling `stringResource()` directly inside the `Modifier.semantics { ... }` block fails compilation because the lambda doesn't provide a `@Composable` context.
**Action:** When adding indeterminate progress indicators that convey critical state changes (like loading or saving), always extract the localized string into a local variable *before* applying it via `modifier = Modifier.semantics { contentDescription = extractedLabel }`.

## 2024-10-07 - Use clearAndSetSemantics for Text-based IconButtons
**Learning:** When using `IconButton` that contains a text node (like "‹‹" or "-5s") instead of an SVG icon, applying `Modifier.semantics { contentDescription = ... }` does not override the inner text. Screen readers will read both the text label AND the custom description, creating a confusing and redundant accessibility experience.
**Action:** Use `Modifier.clearAndSetSemantics { contentDescription = ... }` on these types of buttons. This completely clears out the semantic properties of the descendants (the raw text) and replaces it with the explicitly provided localized description.
