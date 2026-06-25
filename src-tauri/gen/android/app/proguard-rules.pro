# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# --- WRY v0.55.1 JNI keep rule (NiumaStatusBar workaround) ---
# WRY 0.55.1's `Rust.wryCreate` (wry/src/android/mod.rs:117) and
# `onWebviewDestroy` (wry/src/android/binding.rs:277) look up
# `activity.getId()` / `activity.setId(int)` by JNI name. WryActivity
# (wry/src/android/kotlin/WryActivity.kt:47) defines `var id: Int = 0`
# as a public final property; MainActivity inherits but does not
# redeclare it, so the JVM method table on `MainActivity` itself is
# empty for `getId`/`setId`. JNI's `GetMethodID` only searches the
# declared methods of the receiver class (no vtable walk), so the call
# fails with `NoSuchMethodError: Lcom/aimonitor/app/MainActivity;.getId()I`
# and crashes on launch.
#
# Fix: a CI step in .github/workflows/release.yml injects
# `var id: Int = 0` into MainActivity.kt (a Kotlin property shadow,
# which generates fresh `getId()` + `setId(int)` methods on
# MainActivity's own method table). Keep those methods here so R8
# doesn't strip them in release builds (where `isMinifyEnabled = true`).
# Without this rule R8 sees the methods as unused and removes them,
# reproducing the NoSuchMethodError.
-keepclassmembers class com.aimonitor.app.MainActivity {
    int getId();
    void setId(int);
}
# Also keep the WryActivity versions as a belt-and-braces measure in
# case the JNI lookup target is ever retargeted to the superclass.
-keepclassmembers class com.aimonitor.app.WryActivity {
    int getId();
    void setId(int);
}
# Remove this block once we move off tauri 2.11.3 (which pins wry 0.55.1).