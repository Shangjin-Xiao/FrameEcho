## 2026-02-24 - [Metadata Injection Prevention]
**Vulnerability:** User-controlled data (filenames, codecs) containing control characters or excessive length can be written to EXIF tags, potentially causing metadata corruption or parser issues.
**Learning:** `ExifInterface` does not automatically sanitize or truncate strings. Filenames from `VideoMetadata` are user-controlled and can contain garbage.
**Prevention:** Sanitize all string inputs to EXIF tags by removing control characters (including newlines) and enforcing a reasonable length limit before writing.
## 2026-03-24 - [PII Leakage in Logs]
**Vulnerability:** Full stack traces containing PII (like absolute file paths, URIs, or MediaStore data) are leaked into system logs on production builds when exceptions occur during frame extraction or export.
**Learning:** `android.util.Log` calls in core library modules unconditionally dump stack traces regardless of the application's debuggable state.
**Prevention:** Use a centralized `LogUtils` wrapper that checks `ApplicationInfo.FLAG_DEBUGGABLE` via a `Context` parameter. In release builds, log only the exception's class name to prevent sensitive data exposure.
## 2026-04-14 - Fix Path Traversal in Filename Sanitization
**Vulnerability:** The `sanitizeFileName` function in `FrameExporter.kt` used a simple string replacement `.replace("..", "_")` which is vulnerable to recursive path traversal attacks (e.g., `... `) and only stripped null bytes `\u0000` instead of all control characters.
**Learning:** String literal replacements for traversal sequences are often insufficient. Developers may only consider the most basic traversal payloads (`..`) without accounting for complex sequences or the full range of ASCII control characters.
**Prevention:** Always use regex-based sanitization that targets ranges (e.g., `[\x00-\x1F\x7F]` for control chars) and collapses recursive patterns (e.g., `Regex("\\.\\.+")` for multiple dots) when handling filesystem inputs.
