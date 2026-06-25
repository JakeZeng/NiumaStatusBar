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
# `onWebviewDestroy` (wry/src/android/binding.rs:277) call
# `activity.getId()` / `activity.setId()` by name. Those accessors are
# auto-generated from `WryActivity.var id: Int = 0`. R8 inlines the
# property accessor in release builds and strips the getter, which makes
# the JNI lookup fail with `NoSuchMethodError` and crashes the app on
# launch. Keep the JNI-bound methods explicitly. Remove this block
# once we move off tauri 2.11.3 (which pins wry 0.55.1).
-keepclassmembers class com.aimonitor.app.WryActivity {
    int getId();
    void setId(int);
}