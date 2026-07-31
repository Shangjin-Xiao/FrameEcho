# Release Notes

## v1.2.0

> 定格你所爱的每一帧 · Freeze Every Frame You Love

此版本重点提升了动态照片音频兼容性，简化并精简了导出流程与 HDR 处理，同时增强了自定义路径权限持久化与发布流水线。

### 优化与改进

- **动态照片音频转码** — 引入音频解码与 AAC 自动转码机制，修复 Sony ZV-1 等相机录制的 LPCM（audio/raw）及非标音频在导出动态照片时无声的问题；增加音频丢弃提示与安全回退机制
- **导出流程精简** — 统一导出格式为高画质 JPEG，移除兼容性不佳的格式及过度设定的 HDR 策略，简化底栏与设置面板交互
- **路径与权限持久化** — 支持 SAF 自定义文件夹 URI 的持久化记忆与自动权限校验，提升导出路径切换体验
- **导出可靠性与安全性** — 优化导出取消响应与临时文件清理逻辑；精准反馈 EXIF 写入状态，完善权限异常提示
- **构建与发布自动化** — 重构 GitHub Actions 自动化发布流水线，完善版本号自动注入与签名安全机制

### 系统要求

- Android 8.0（API 26）及以上

---

## v1.2.0

> Freeze Every Frame You Love

This release brings enhanced audio compatibility for Motion Photos, simplified export options, persistent storage permissions, and improved release pipelines.

### Highlights

- **Motion Photo Audio Transcoding** — Introduced audio decoding and automatic AAC encoding to fix silent Motion Photo exports from cameras recording LPCM (e.g. Sony ZV-1) or non-standard audio; added user warnings when audio cannot be preserved.
- **Simplified Export Options** — Standardized output on high-quality JPEG and streamlined HDR processing, delivering a faster and cleaner user experience.
- **Persistent Storage Permissions** — Added full persistence and permission verification for SAF custom export folders across app launches.
- **Export Reliability & Security** — Enhanced export cancellation checkpoints and temporary file cleanup; added precise EXIF write status reporting and improved permission exception diagnostics.
- **Build & Release Automation** — Overhauled GitHub Actions release pipelines with automated version code injection and hardened release signing security.

### Requirements

- Android 8.0 (API 26) or higher

---

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
- **图像导出** — 高画质 JPEG 格式，可调整压缩质量与分辨率上限
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
- **Image export** — High-quality JPEG format with adjustable quality and optional resolution cap
- **Motion Photos** — Google MicroVideo spec compliant; exported photos come alive with a long press in your gallery
- **HDR-aware** — Detects HDR10, HDR10+, HLG, Dolby Vision; four tone-mapping strategies (Auto / SDR / Preserve HDR / System)
- **Lossless EXIF** — Capture time, GPS, device info, ISO, exposure time, focal length all preserved in the exported image
- **Flexible export** — Custom filenames, preset folders (Pictures / DCIM / Movies) or any folder via system file picker
- **Material 3 UI** — Dynamic Color (Android 12+), onboarding guide, bottom settings sheet; displayed as "帧迹" on Chinese systems

### Requirements

- Android 8.0 (API 26) or higher
