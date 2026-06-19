## 2024-05-18 - 优化 MetadataExtractor.kt 以减少冗余的 JNI 调用

**Learning:** 在 `MetadataExtractor.kt` 中，`MediaMetadataRetriever.extractMetadata` 涉及昂贵的 JNI 边界调用。为了提高性能，可以先尝试从 `MediaFormat`（通过 `MediaExtractor` 获得）中提取诸如宽度、高度、旋转角度、时长、比特率等轨道级别的属性。由于项目的 `minSdk` 是 26，且 `MediaFormat.containsKey()` 需要 API 29+，应当使用 `runCatching { format.getInteger(key) }.getOrNull()` 等标准获取方法来安全地跨平台提取数据，避免幻觉出不存在的扩展方法，或者引起低版本的 `NoSuchMethodError`。

**Action:** 在优化媒体提取代码时，优先使用 `MediaFormat` 而非 `MediaMetadataRetriever`；遇到版本兼容性问题时，使用 `runCatching` 封装标准 API 而不是依赖于或臆造未验证的自定义扩展。
