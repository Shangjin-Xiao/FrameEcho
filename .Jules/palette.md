## 2024-05-24 - Tap Gesture Consumption for Accessibility
**Learning:** In Jetpack Compose, using `Modifier.clickable` simply to consume clicks (e.g., on a background overlay to prevent click-throughs) introduces accessibility noise. Screen readers will announce the element as a button (Role.Button) because `clickable` automatically adds this semantic role.
**Action:** When blocking click propagation without adding interactive semantics, use `Modifier.pointerInput(Unit) { detectTapGestures { } }` instead of `Modifier.clickable`.
