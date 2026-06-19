## 2024-05-18 - 优化 MetadataExtractor.kt 以减少冗余的 JNI 调用

**Learning:** 在 `MetadataExtractor.kt` 中，`MediaMetadataRetriever.extractMetadata` 涉及昂贵的 JNI 边界调用。为了提高性能，可以先尝试从 `MediaFormat`（通过 `MediaExtractor` 获得）中提取诸如宽度、高度、旋转角度、时长、比特率等轨道级别的属性。由于项目的 `minSdk` 是 26，且 `MediaFormat.containsKey()` 需要 API 29+，应当使用 `runCatching { format.getInteger(key) }.getOrNull()` 等标准获取方法来安全地跨平台提取数据，避免幻觉出不存在的扩展方法，或者引起低版本的 `NoSuchMethodError`。

**Action:** 在优化媒体提取代码时，优先使用 `MediaFormat` 而非 `MediaMetadataRetriever`；遇到版本兼容性问题时，使用 `runCatching` 封装标准 API 而不是依赖于或臆造未验证的自定义扩展。

## 2026-05-07 - InputStream.copyTo over manual buffer loops
**Learning:** Writing manual `while` loops with pre-allocated `ByteArray` buffers to copy data from an `InputStream` to an `OutputStream` is an anti-pattern in Kotlin. It clutters the codebase and may not leverage internal optimizations.
**Action:** Use the Kotlin standard library extension `InputStream.copyTo(out: OutputStream, bufferSize: Int)`. It handles the buffer allocation and looping efficiently and more idiomatically. Specify a large buffer size (like 64KB) for large media files to reduce IO overhead.

## 2025-05-15 - 移除 OnboardingManager 中的 runBlocking
**Learning:** 在 Android 开发中，使用 `runBlocking` 在主线程上同步执行 DataStore 操作（如 `edit {}` 或 `data.first()`）是一个严重的性能反模式，会导致 UI 掉帧甚至 ANR。DataStore 设计为异步 I/O，应当始终通过 `suspend` 函数访问。
**Action:** 始终将 DataStore 的读写操作封装在 `suspend` 函数中，并在 UI 层（如 Jetpack Compose）使用 `rememberCoroutineScope` 或 `LaunchedEffect` 来启动协程进行调用。

## 2026-05-15 - 避免 DataStore 导致的主线程阻塞
**Learning:** 使用 `runBlocking` 同步执行 DataStore 的 `edit` 或读取操作会阻塞调用它的线程（例如在 ViewModel 初始化或设置修改时如果在主线程调用，会导致主线程阻塞）。DataStore 访问应当全部重构为挂起函数（`suspend`）以实现异步非阻塞操作。
**Action:** 将 DataStore 的读写方法改为 `suspend`，并在 ViewModel 中通过 `viewModelScope.launch` 异步调用，在 UI 层通过 `rememberCoroutineScope` 异步调用，消除主线程的 `runBlocking` 同步等待。

## 2026-05-18 - Jetpack Compose Color Allocation Optimization
**Learning:** In Jetpack Compose components that recompose frequently (such as a `VideoSurface` responding to gesture events), continuously calling inline `.copy(alpha = ...)` on `MaterialTheme.colorScheme` colors inside the Composable function body creates unnecessary, repeated color object allocations.
**Action:** Extract static color derivations to the top of the Composable using `remember(colorScheme.baseColor) { colorScheme.baseColor.copy(...) }`. This memoizes the allocated color object, improving performance and reducing memory pressure during UI recomposition.

## 2024-05-18 - 优化 ColorSpace 实例化性能
**Learning:** `AndroidColorSpace.get()` 会在内部进行字典查找，在热路径（如图像处理或转换期间）频繁调用会导致不必要的 CPU 开销。
**Action:** 使用 `by lazy` 将无状态、不可变的框架对象（如 `AndroidColorSpace`）缓存到伴生对象或单例中。如果某些属性依赖于较新的 SDK 版本（例如 `ColorSpace.Named.BT2020` 要求 API 34+），在 `lazy` 初始化块内使用 `Build.VERSION.SDK_INT` 进行检查并提供安全的回退（Fallback）。
