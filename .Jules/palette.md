## 2024-05-24 - Tap Gesture Consumption for Accessibility
**Learning:** In Jetpack Compose, using `Modifier.clickable` simply to consume clicks (e.g., on a background overlay to prevent click-throughs) introduces accessibility noise. Screen readers will announce the element as a button (Role.Button) because `clickable` automatically adds this semantic role.
**Action:** When blocking click propagation without adding interactive semantics, use `Modifier.pointerInput(Unit) { detectTapGestures { } }` instead of `Modifier.clickable`.

## 2024-05-25 - Dynamic UI State Accessibility
**Learning:** In Jetpack Compose, when dynamic UI states cause a button's inner child components to swap (e.g., exchanging an `Icon` for a `CircularProgressIndicator` during a loading state inside a `FloatingActionButton`), placing `contentDescription` on the inner `Icon` means the button loses its accessibility label entirely during the loading state.
**Action:** Apply the `contentDescription` via `Modifier.semantics` to the parent container (e.g., the `FloatingActionButton`) and set the inner `Icon`'s `contentDescription` to `null`. This ensures the semantic meaning is preserved regardless of the child state.
