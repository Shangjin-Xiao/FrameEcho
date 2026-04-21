## 2026-02-24 - [Metadata Injection Prevention]
**Vulnerability:** User-controlled data (filenames, codecs) containing control characters or excessive length can be written to EXIF tags, potentially causing metadata corruption or parser issues.
**Learning:** `ExifInterface` does not automatically sanitize or truncate strings. Filenames from `VideoMetadata` are user-controlled and can contain garbage.
**Prevention:** Sanitize all string inputs to EXIF tags by removing control characters (including newlines) and enforcing a reasonable length limit before writing.
## 2026-03-24 - [PII Leakage in Logs]
**Vulnerability:** Full stack traces containing PII (like absolute file paths, URIs, or MediaStore data) are leaked into system logs on production builds when exceptions occur during frame extraction or export.
**Learning:** `android.util.Log` calls in core library modules unconditionally dump stack traces regardless of the application's debuggable state.
**Prevention:** Use a centralized `LogUtils` wrapper that checks `ApplicationInfo.FLAG_DEBUGGABLE` via a `Context` parameter. In release builds, log only the exception's class name to prevent sensitive data exposure.
## 2025-01-20 - 修复Intent劫持与路径遍历漏洞
**Vulnerability:** 导出文件查看使用隐式 Intent (ACTION_VIEW) 且附带读取权限，可能被恶意应用劫持从而导致 URI 泄漏；文件导出使用简单的 `..` 替换，可能被 `...` 等序列绕过实现路径遍历。
**Learning:** 仅进行简单的字符串替换来防止路径遍历是不够的，需通过正则表达式处理所有的控制字符及各种遍历形式。在 Android 开发中，附带敏感权限（如 URI 读取）的隐式 Intent，若未经 `Intent.createChooser()` 包装，可能无意间将数据泄露给设置了默认打开方式的其他应用。
**Prevention:** 所有针对文件系统的保存逻辑都需要严格的正则表达式（如 `[\x00-\x1F\x7F]` 与 `\\.\\.+`）清洗控制字符和跨目录标记。所有用于打开、分享文件的 `ACTION_VIEW` 等隐式 Intent 必须使用 `Intent.createChooser()` 进行保护。
