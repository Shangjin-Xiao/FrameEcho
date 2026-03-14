# FrameEcho ProGuard Rules

# Keep model classes
-keep class com.shangjin.frameecho.core.model.** { *; }

# Media3
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Coroutines
-keepnames class kotlinx.coroutines.** { *; }
# Required for coroutines to work: volatile fields used for lock-free atomic updates
-keepclassmembernames class kotlinx.coroutines.** {
    volatile <fields>;
}
-keepclassmembernames class kotlin.coroutines.** {
    volatile <fields>;
}

# --- CRITICAL RELEASE FIXES ---

# Keep Application and Activity entry points
-keep class com.shangjin.frameecho.app.FrameEchoApp { *; }
-keep class com.shangjin.frameecho.app.MainActivity { *; }

# Keep generic Android components as a fallback
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider

# Keep attributes required for runtime reflection (common in libraries)
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Keep Kotlin Metadata (needed for Kotlin reflection)
-keep class kotlin.Metadata { *; }

# Keep Coil (Image Loading) — dontwarn only; Coil ships its own consumer rules
-dontwarn coil.**

# Keep ViewModels — keep class name and constructors so the ViewModel factory can instantiate them
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>();
    <init>(android.app.Application);
}

# Keep Material Components for Android — dontwarn only; library ships its own consumer rules
-dontwarn com.google.android.material.**

# Keep Navigation Compose — dontwarn only; library ships its own consumer rules
-dontwarn androidx.navigation.**

# Keep Compose runtime — dontwarn only; library ships its own consumer rules
-dontwarn androidx.compose.**

# --- SECURITY: Strip verbose logging in release builds ---
# Remove Log.v and Log.d calls entirely (they compile to no-ops)
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
# Remove Log.i/w/e to prevent information leakage in production.
# If you need crash-level logging, comment out Log.e below and use a
# crash-reporting SDK (e.g. Firebase Crashlytics) instead.
-assumenosideeffects class android.util.Log {
    public static int i(...);
    public static int w(...);
    public static int e(...);
}
