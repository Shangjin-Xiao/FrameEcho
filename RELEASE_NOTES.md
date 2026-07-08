# Release Notes

## v1.1.0

> 定格你所爱的每一帧 · Freeze Every Frame You Love

此版本包含多项无障碍（A11y）适配、安全加固及性能重组优化。

### 优化与改进

- **无障碍体验 (A11y)** — 优化段落标题焦点合并，重构引导页与播放控制栏，提升 TalkBack 屏幕阅读器适配；为纯图标按钮补充 Tooltip 提示
- **安全性与隐私** — 强化自定义文件名过滤防止路径穿越；引入日志脱敏机制杜绝 PII（个人隐私）泄漏风险；修复隐式 Intent 劫持及更新检测中的 OOM 隐患
- **性能与重组优化** — 优化视频元数据提取逻辑，大幅减少 JNI 跨边界调用；采用 LazyRow 与图形层 lambda 重组优化，使得拖动进度条时更流畅
- **格式兼容增强** — 完善原生 HEIF 和 AVIF 编码器支持与导出检测；修复并保留动态照片导出视频片段的方向信息

### 系统要求

- Android 8.0（API 26）及以上

---

## v1.1.0

> Freeze Every Frame You Love

This release brings accessibility (A11y) improvements, security hardening, and performance recomposition optimizations.

### Highlights

- **Accessibility (A11y)** — Merged section heading semantics and refactored onboarding overlay & playback bar for screen readers; added Tooltip wrappers for icon-only actions.
- **Security & Privacy** — Hardened custom filename sanitization to prevent path traversal; introduced log sanitization to prevent PII leakage; patched implicit intent hijacking and update check OOM vulnerabilities.
- **Performance & Rendering** — Optimized metadata extraction by querying MediaFormat first (reducing JNI overhead); optimized Composable recompositions (LazyRow, graphicsLayer) for smoother seek scrubbing.
- **Media Formats** — Enhanced native HEIF & AVIF export detection; preserved correct video orientation in exported Motion Photos.

### Requirements

- Android 8.0 (API 26) or higher

---

## v1.0.0

> 定格你所爱的每一帧 · Freeze Every Frame You Love

首个正式发布版本。

### 主要功能

- **精准抽帧** — 始终使用 `OPTION_CLOSEST` 精确定位帧，不回退至关键帧；拖动进度条时实时渲染缩略图时间线
- **精细调节模式** — 拖动进度条时向上滑动，进入逐帧精细控制模式，毫秒级精度定位
- **多格式导出** — 支持 JPEG、PNG、WebP、HEIF、AVIF，可调整压缩质量与分辨率上限
- **动态照片** — 遵循 Google MicroVideo 规范，将帧前后的视频片段嵌入静态图片，在相册中长按即可播放
- **HDR 支持** — 自动检测 HDR10、HDR10+、HLG、杜比视界，提供四种色调映射策略（自动 / SDR / 保留 HDR / 系统）
- **无损 EXIF 保留** — 将源视频的拍摄时间、GPS、设备信息、ISO、曝光时间、焦距完整写入导出图片
- **灵活导出配置** — 支持自定义文件名、预设目录（图片 / DCIM / 电影）或通过系统文件选择器指定任意路径
- **Material 3 界面** — 动态取色（Android 12+）、新用户引导、底部设置面板，中文系统显示应用名「帧迹」

### 系统要求

- Android 8.0（API 26）及以上

---

## v1.0.0

> Freeze Every Frame You Love

First stable release.

### Highlights

- **Frame-precise extraction** — Always uses `OPTION_CLOSEST`, never falls back to keyframe seeking; real-time thumbnail timeline while scrubbing
- **Fine-scrubbing mode** — Swipe up on the seek bar to enter frame-by-frame precision control
- **Multi-format export** — JPEG, PNG, WebP, HEIF, AVIF with adjustable quality and optional resolution cap
- **Motion Photos** — Google MicroVideo spec compliant; exported photos come alive with a long press in your gallery
- **HDR-aware** — Detects HDR10, HDR10+, HLG, Dolby Vision; four tone-mapping strategies (Auto / SDR / Preserve HDR / System)
- **Lossless EXIF** — Capture time, GPS, device info, ISO, exposure time, focal length all preserved in the exported image
- **Flexible export** — Custom filenames, preset folders (Pictures / DCIM / Movies) or any folder via system file picker
- **Material 3 UI** — Dynamic Color (Android 12+), onboarding guide, bottom settings sheet; displayed as "帧迹" on Chinese systems

### Requirements

- Android 8.0 (API 26) or higher
