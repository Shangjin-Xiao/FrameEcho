## 2025-05-15 - 移除 OnboardingManager 中的 runBlocking
**Learning:** 在 Android 开发中，使用 `runBlocking` 在主线程上同步执行 DataStore 操作（如 `edit {}` 或 `data.first()`）是一个严重的性能反模式，会导致 UI 掉帧甚至 ANR。DataStore 设计为异步 I/O，应当始终通过 `suspend` 函数访问。
**Action:** 始终将 DataStore 的读写操作封装在 `suspend` 函数中，并在 UI 层（如 Jetpack Compose）使用 `rememberCoroutineScope` 或 `LaunchedEffect` 来启动协程进行调用。
