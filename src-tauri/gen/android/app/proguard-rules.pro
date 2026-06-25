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
# Then this file keeps those methods. Two rules are needed because
# R8 can vertical-merge an override that only forwards to super
# (which is what `override var id: Int = 0` does in MainActivity —
# the getter returns super.getId() and the setter calls super.setId()).
# The `@Keep` annotation on the property is the primary defense
# (R8 honors @Keep even when merging); the `-keep` rule on the
# specific method signatures is a belt-and-braces measure so the
# bytecode survives even if the annotation gets stripped by
# R8's annotation processing. WRY's own proguard-wry.pro keeps
# WryActivity.getId() but not MainActivity.getId(), which is why
# this rule has to live here. We keep both classes for belt-and-braces.
-keep class com.aimonitor.app.MainActivity {
    int getId();
    void setId(int);
}
-keep class com.aimonitor.app.WryActivity {
    int getId();
    void setId(int);
}
# Belt-and-braces: keep ALL members of these two classes (including
# the @Keep-annotated MainActivity.id property's accessors) so R8
# cannot inline + remove them in the vertical-merge pass.
-keep class com.aimonitor.app.MainActivity { *; }
-keep class com.aimonitor.app.WryActivity { *; }
# Remove this block once we move off tauri 2.11.3 (which pins wry 0.55.1).