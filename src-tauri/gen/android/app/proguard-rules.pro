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
# `activity.getId()` / `activity.setId(int)` by JNI name. The receiver
# is a MainActivity instance, but JNI's `GetMethodID` (called inside
# `Call_method` to resolve the method ID) only walks the receiver
# class's declared methods — it does NOT follow the vtable up to
# WryActivity. MainActivity must therefore have a DECLARED
# `getId()`/`setId(int)` of its own. The CI workflow makes this happen
# in two steps:
#
#   1. BuildTask.kt (after cargo build) patches
#      WryActivity's `var id: Int = 0` to `open var id: Int = 0` so
#      it can be overridden.
#   2. A separate step injects `override var id: Int = 0` into
#      MainActivity.kt, so MainActivity's own method table contains
#      `getId()` and `setId(int)`.
#
# Then this file keeps those methods with `-keep` (NOT
# `-keepclassmembers` — that one still lets R8 inline the method body
# into the call site and remove the declaration; `-keep` forbids
# both inlining and removal). WRY's own proguard-wry.pro keeps
# WryActivity.getId() but not MainActivity.getId(), which is why this
# rule has to live here. We keep both classes for belt-and-braces.
-keep class com.aimonitor.app.MainActivity {
    int getId();
    void setId(int);
}
-keep class com.aimonitor.app.WryActivity {
    int getId();
    void setId(int);
}
# Remove this block once we move off tauri 2.11.3 (which pins wry 0.55.1).