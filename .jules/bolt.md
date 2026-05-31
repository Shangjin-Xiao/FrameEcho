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

## 2024-05-18 - 优化 ColorSpace 实例化性能
**Learning:** `AndroidColorSpace.get()` 会在内部进行字典查找，在热路径（如图像处理或转换期间）频繁调用会导致不必要的 CPU 开销。同时，在 CI 或当前执行环境中，测试（如基于 `mockk` 或 Robolectric 的性能基准测试）可能会因超时或依赖项解析失败而难以运行。
**Action:** 使用 `by lazy` 将无状态、不可变的框架对象（如 `AndroidColorSpace`）缓存到伴生对象或单例中。如果某些属性依赖于较新的 SDK 版本（例如 `ColorSpace.Named.BT2020` 要求 API 34+），在 `lazy` 初始化块内使用 `Build.VERSION.SDK_INT` 进行检查并提供安全的回退（Fallback）。如果无法运行本地基准测试，需要在 PR 或文档中基于框架行为和最佳实践进行逻辑论证。
