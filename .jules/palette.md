## 2026-06-24 - Screen Reader Section Header Optimization
**Learning:** In Jetpack Compose, when multiple elements (like an Icon and Text) form a logical section header, standard TalkBack behavior reads them out sequentially as separate items, increasing friction for screen reader users.
**Action:** Always add `Modifier.semantics(mergeDescendants = true) { heading() }` to the container row of section headers to group the contents into a single semantic heading entity.
