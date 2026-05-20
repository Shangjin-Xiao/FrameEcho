## 2024-05-22 - Bilingual Strings Requirement
**Learning:** The app supports both English and Simplified Chinese (values-zh-rCN). All new string resources must be added to both `values/strings.xml` and `values-zh-rCN/strings.xml` to avoid lint errors and maintain UX.
**Action:** Always check `values-zh-rCN` when adding strings.
## 2024-05-15 - Compose Semantics Merge for Visual Headers
**Learning:** When using `Modifier.semantics { heading() }` in Jetpack Compose on a container (like a `Row`) that has multiple decorative or descriptive elements (like an `Icon` and `Text`), screen readers might not treat the entire row as a single cohesive heading by default unless `mergeDescendants = true` is explicitly applied.
**Action:** When making custom section header components accessible as headings, apply `Modifier.semantics(mergeDescendants = true) { heading() }` to the parent container so that assistive technologies announce the combined content accurately as one structural header.
