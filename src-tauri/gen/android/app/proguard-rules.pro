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
# hide the original source file name:
#-renamesourcefileattribute SourceFile

# --- NiumaStatusBar minify config (WRY 0.55.1 + MIUI workaround) ---
#
# Disable R8's name-obfuscation pass. Two reasons:
#
# 1. WRY 0.55.1's JNI code (`Rust.wryCreate`, `onWebviewDestroy`)
#    calls `activity.getId()` / `activity.setId(int)` by string
#    name; JNI's `GetMethodID` only walks the receiver class's
#    declared methods, so the method names MUST survive R8.
#    R8 8.x (AGP 8.x default) runs in full-mode where the
#    `proguard-android.txt` and `proguard-android-optimize.txt`
#    defaults are slightly stricter than the old ProGuard and
#    even explicit `-keep class X { *; }` plus `@Keep`
#    annotations are not enough to keep WryActivity / MainActivity
#    intact through vertical method merging and class repackaging.
#    `-dontobfuscate` short-circuits the entire obfuscation pass;
#    names are preserved 1:1, JNI lookups work, and the rules
#    below are belt-and-braces.
#
# 2. Xiaomi MIUI 14+ / HyperOS inspects app dex and shows a
#    "NiumaStatusBar 使用的加固技术还没有适配当前安卓系统版本"
#    toast / app-info banner when it sees short obfuscated
#    class/method names (it mistakes R8 output for 3rd-party
#    packer output). Disabling obfuscation here also keeps MIUI
#    quiet.
#
# We still let R8 do tree-shaking (removes unreachable code,
# shrinks APK) — that's controlled by `isMinifyEnabled = true`
# in build.gradle.kts and is independent of obfuscation.
-dontobfuscate

# --- WRY v0.55.1 JNI keep rule (NiumaStatusBar workaround) ---
# WRY 0.55.1's `Rust.wryCreate` (wry/src/android/mod.rs:117) and
# `onWebviewDestroy` (wry/src/android/binding.rs:277) look up
# `activity.getId()` / `activity.setId(int)` by JNI name. The receiver
# is a MainActivity instance, but JNI's `GetMethodID` (called inside
# `Call_method` to resolve the method ID) only walks the receiver
# class's declared methods — it does NOT follow the vtable up to
# WryActivity. MainActivity must therefore have a DECLARED
# `getId()`/`setId(int)` of its own. This is done in two steps:
#
#   1. MainActivity.kt declares `override var id: Int = 0` (with @Keep).
#   2. BuildTask.kt (after cargo build) patches the generated
#      WryActivity.kt so its `var id: Int = 0` becomes `open var id: Int = 0`,
#      allowing MainActivity to override it.
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