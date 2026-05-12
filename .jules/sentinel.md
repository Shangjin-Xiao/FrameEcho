## 2026-02-24 - [Metadata Injection Prevention]
**Vulnerability:** User-controlled data (filenames, codecs) containing control characters or excessive length can be written to EXIF tags, potentially causing metadata corruption or parser issues.
**Learning:** `ExifInterface` does not automatically sanitize or truncate strings. Filenames from `VideoMetadata` are user-controlled and can contain garbage.
**Prevention:** Sanitize all string inputs to EXIF tags by removing control characters (including newlines) and enforcing a reasonable length limit before writing.
## 2026-03-24 - [PII Leakage in Logs]
**Vulnerability:** Full stack traces containing PII (like absolute file paths, URIs, or MediaStore data) are leaked into system logs on production builds when exceptions occur during frame extraction or export.
**Learning:** `android.util.Log` calls in core library modules unconditionally dump stack traces regardless of the application's debuggable state.
**Prevention:** Use a centralized `LogUtils` wrapper that checks `ApplicationInfo.FLAG_DEBUGGABLE` via a `Context` parameter. In release builds, log only the exception's class name to prevent sensitive data exposure.
## 2026-04-28 - [Implicit Intent Hijacking]
**Vulnerability:** Launching an implicit `ACTION_VIEW` intent with sensitive content (like exported file URIs) without user interaction can lead to intent hijacking. A malicious app can register as the default handler and silently intercept the URI and data.
**Learning:** `Intent.ACTION_VIEW` relies on the Android system to resolve the appropriate application. If a default app is already set, or if a malicious app claims to handle the intent, sensitive data might be leaked.
**Prevention:** Wrap implicit intents for sensitive actions (like opening files or sharing) in `Intent.createChooser()`. This forces the system to display an app selection dialog, requiring explicit user action to choose the handling application.
## 2026-06-15 - [Path Traversal and Control Character Injection via Filenames]
**Vulnerability:** Filenames constructed from user input (like custom export folder names) could contain control characters, illegal filesystem characters, or path traversal sequences (`..`), which were not correctly neutralized when writing files. The previous replace approach for `..` could be bypassed by formats like `...`.
**Learning:** Simple string replacement (`replace("..", "_")`) is insufficient to prevent path traversal because it leaves behind traversal structures if stacked (e.g. `...` becomes `._`). Also, null-byte stripping (`replace('\u0000', '_')`) misses other dangerous control characters.
**Prevention:** Use comprehensive regular expressions to sanitize filenames. Strip all control characters using `[\\x00-\\x1F\\x7F]`. Collapse consecutive dots using `\\.\\.+`. Trim any resulting leading/trailing filesystem artifacts (like `.` or `_`) to ensure a safe, bounded filename.
