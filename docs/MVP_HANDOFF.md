# FrameEcho MVP 收尾交接文档

> 更新时间:2026-07-26。本文档记录一次 MVP 瘦身审查的结论:已经完成的修改、以及**留给接手人的待办**(按优先级排序,每项标注难度和入口文件)。

## 一、本次已完成的修改(供了解背景)

1. **移除 HEIF/AVIF 导出**(设备兼容性差、始终无法稳定工作):
   - `ExportFormat` 只保留 JPEG / PNG / WEBP;
   - `FrameExporter` 删除了 HeifWriter/AvifWriter 探测和编码路径;
   - 删除 `androidx.heifwriter` 依赖、manifest 中的 `tools:overrideLibrary`、相关字符串(3 个语言)、README/fastlane 描述、相关测试。
2. **移除 HDR 色调映射策略设置**:HEIF/AVIF 移除后剩余格式全部是 SDR,四种策略(AUTO/FORCE_SDR/PRESERVE_HDR/SYSTEM)行为完全一致,该设置成为摆设。现在 HDR 源一律 tone-map 到 sRGB,`HdrToneMapStrategy` 枚举、`ExportConfig.hdrToneMap`、设置面板中的选择器和 8×3 条字符串均已删除。
3. **修复发布流水线三个致命问题**:
   - `versionCode` 原先硬编码为 1(每次发版都是 1,F-Droid/Play/覆盖安装永远不认为有更新)。现在从 `-PVERSION_CODE` 读取,release workflow 传 `github.run_number`;
   - `actions/upload-artifact@v7` 不存在(该 action 最新为 v4),CI 和 release 的 APK 上传步骤必然失败。已改回 v4 并删掉无效的 `archive: false` 输入;
   - README 的 CI 徽章指向不存在的 `android-ci.yml`,已改为 `ci.yml`。CI 同时加上了 `pull_request` 触发、push 限定 main 分支。
4. **删除测试目录里的调试残留**:`MetadataExtractorBenchmark.kt`(无断言的 println 微基准,每次 CI 都跑)和根目录 `benchmark_rationale.md`。

---

## 二、待办事项与完成记录(按优先级排序)

### P0 — 发布与可观测性

#### 1. Release workflow 不创建 GitHub Release,而应用内更新器依赖它 【已完成】
- `.github/workflows/release.yml` 已添加 `softprops/action-gh-release@v2` 自动创建 release 并上传 APK,添加 `contents: write` 权限,并在构建结束后清理临时 keystore 文件 `$RUNNER_TEMP/frameecho-release.jks`。
- `app/build.gradle.kts`: 在 assembleRelease / bundleRelease 构建缺签名属性时配置抛出 `GradleException` 终止构建,避免静默回退 debug 签名。

#### 2. Release 构建零日志、零崩溃上报 【已完成】
- `app/proguard-rules.pro`: 停止剥离 `Log.w` / `Log.e` 异常日志,以便在线上保留诊断信息(`LogUtils` 已做 PII 隐私清洗)。

### P1 — 用户可感知的功能缺陷

#### 3. "记住快捷设置"只记住 3 项,自定义文件夹每次重启丢失 【已完成】
- `PlayerPreferencesStore.kt` & `PlayerViewModel.kt`: 扩展了 DataStore 持久化 Schema,增加了 format, quality, preserveMetadata, customFileName, exportDirectory 以及 customExportTreeUri。
- 在 ViewModel 初始化与恢复设置时,通过 `contentResolver.persistedUriPermissions` 校验 SAF URI 权限后再加载,解决了重启后自定义导出文件夹及导出参数重置的问题。

#### 4. 导出"取消"按钮实际取消不了 【已完成】
- `FrameExporter.kt`: 在 muxer 的 sample 提取 while 循环里添加了 `currentCoroutineContext().ensureActive()` 检查点;
- 在 `exportStaticFrame` 与 `exportMotionPhoto` 中添加了 `CancellationException` 专属捕获逻辑,取消时自动调用 `cleanupFailedOutput()` 清理未完成的半成品 Uri 文件;
- `PlayerViewModel.kt`: `cancelExport()` 增加对 `isExporting = false` UI 状态同步重置。

#### 5. metadataPreserved 汇报的是"请求"而不是"结果" 【已完成】
- `FrameExporter.kt`: `writeMetadataToUri` 与 `writeExifToJpegBytes` 返回实际写入布尔结果,`ExportResult.Success` 中传入真实 `metadataPreserved` 写入状态而非配置变量。

#### 6. 错误信息不区分失败原因 【已完成(最小版)】
- `FrameExtractor.kt`: `useRetriever` 区分并透传 `SecurityException`(权限被收回);其余异常仍返回默认值。
- `PlayerViewModel` 新增 `PlayerError.PermissionDenied`,`PlayerScreen` 显示专属提示(新字符串 `error_permission_denied`,3 个语言)。损坏视频/不支持编解码器仍显示通用"截取失败",进一步细分留待后续。

### P2 — 死代码清理

| 项 | 位置 | 状态 |
|---|---|---|
| Deletions of dead methods `captureFrame()` / `exportStatic()` / `exportMotionPhoto()` & `captureJob` (~150 行) | `PlayerViewModel.kt` | 已完成清理 |
| `FrameExtractor.extractFrameRange()` 无引用清理 | `FrameExtractor.kt` | 已完成清理 |
| `FileUtils.getFileName/getFileSize/isVideoUri` 仅自测引用清理 | `core/common/FileUtils.kt` | 已完成清理 |
| `turbine` 在 catalog 声明但未使用; 无用的 `androidTest` 依赖配置清理 | `libs.versions.toml`, `app/build.gradle.kts` | 已完成清理 |
| ProGuard 里 `-dontwarn coil.**` 无用规则清理 | `app/proguard-rules.pro` | 已完成清理 |
| 未使用字符串 `mit_license`(3 个语言) | `values*/strings.xml` | 已完成清理 |
| `.jules/` 和 `.Jules/` 大小写重复目录合并 | 仓库根目录 | 已合并删除 `.Jules` |
| `.claude/settings.local.json` | 仓库根目录 | 本就未被 git 跟踪,已加入 `.gitignore` 防止误提交 |
| `PlayerUiState.seekIntervalLabel` 未显示字段清理 | `PlayerViewModel.kt` | 已完成清理 |
| `ExportConfig.maxResolution` | `ExportConfig.kt`, `ExportSettingsSheet.kt` | 导出与缩放逻辑正常保持,补全单元测试 |

### P3 — 需要真机验证的存疑子系统

#### 7. HDR 检测/色调映射存疑 【需要 HDR 真机+素材验证】
- 帧是用普通 `MediaMetadataRetriever.getFrameAtTime` 取的,需在真机验证是否偏色。

#### 8. 关键导出路径单元测试 【已完成第一步】
- 为 `FrameExporter.sanitizeFileName` 补全了纯函数单元测试(`FrameExporterTest.kt`),覆盖路径穿越防范、非法字符清洗与默认值回退逻辑。

### P4 — 仓库卫生与细节

#### 9. 营销网站流水线拆分 【待后续独立仓库处理】
- `package.json` / `build.js` / `docs/` 站点等。

#### 10. 其他细节优化 【已完成关键项】
- `app/build.gradle.kts`: 启用了 release 构建 `isShrinkResources = true`;
- 统一并规范了数据结构和架构交互。

