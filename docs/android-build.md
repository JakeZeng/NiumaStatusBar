# Android 打包环境配置指南

本项目使用 Tauri 2.x 构建跨平台应用。Android 端 APK 的打包需要完整的工具链配置，本文档记录所需组件、版本、环境变量以及构建步骤。

## 1. 工具链概览

| 组件 | 版本 | 用途 |
|------|------|------|
| JDK | 17 | Android Gradle Plugin 兼容版本 |
| Android SDK | Platform 34 / Build-Tools 34.0.0 | 编译 / 打包 Android 应用 |
| Android NDK | 27.0.12077973 | 编译 Rust → Android native 代码 |
| Android cmdline-tools | 13114758 | `sdkmanager` 安装其它组件 |
| Gradle | 8.14.4 | 构建系统 |
| Rust | 1.92.0 + Android targets | 编译 Tauri 业务逻辑 |
| Node.js | 24.x | 前端构建 |

## 2. 一次性安装步骤

### 2.1 安装 JDK 17
```bash
apt-get update && apt-get install -y openjdk-17-jdk-headless
```

### 2.2 安装 Android cmdline-tools
```bash
mkdir -p $HOME/Android/Sdk/cmdline-tools
cd $HOME/Android/Sdk/cmdline-tools
wget https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip
unzip commandlinetools-linux-13114758_latest.zip
mv cmdline-tools latest
```

### 2.3 接受许可 + 安装组件
```bash
export ANDROID_HOME=$HOME/Android/Sdk
export PATH=$ANDROID_HOME/cmdline-tools/latest/bin:$PATH
yes | sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0" "ndk;27.0.12077973"
```

### 2.4 安装 Rust Android 目标
```bash
rustup target add aarch64-linux-android
rustup target add armv7-linux-androideabi
rustup target add i686-linux-android
rustup target add x86_64-linux-android
```

### 2.5 创建符号链接
NDK 自带的 clang 带 API level 后缀（如 `aarch64-linux-android24-clang`）。Rust 的 cc/ring crate 会查找不带后缀的 `aarch64-linux-android-clang`，需手动建符号链接：

```bash
NDK_BIN=$ANDROID_HOME/ndk/27.0.12077973/toolchains/llvm/prebuilt/linux-x86_64/bin
cd $NDK_BIN
ln -sf aarch64-linux-android24-clang aarch64-linux-android-clang
ln -sf aarch64-linux-android24-clang++ aarch64-linux-android-clang++
ln -sf armv7a-linux-androideabi24-clang armv7a-linux-androideabi-clang
ln -sf armv7a-linux-androideabi24-clang++ armv7a-linux-androideabi-clang++
ln -sf i686-linux-android24-clang i686-linux-android-clang
ln -sf i686-linux-android24-clang++ i686-linux-android-clang++
ln -sf x86_64-linux-android24-clang x86_64-linux-android-clang
ln -sf x86_64-linux-android24-clang++ x86_64-linux-android-clang++
```

## 3. 环境变量

### 3.1 `env-android.sh`（已提供）
项目根目录的 `env-android.sh` 一键加载所有环境变量：

```bash
source ./env-android.sh
```

它会设置：
- `JAVA_HOME` = JDK 17
- `ANDROID_HOME` / `ANDROID_SDK_ROOT` = SDK 路径
- `NDK_HOME` = NDK 路径
- `PATH` 包含 JDK、SDK、NDK 工具
- 各 target 的 `CC_*` / `CXX_*` / `AR_*` / `CARGO_TARGET_*_LINKER` 变量

### 3.2 `~/.gradle/gradle.properties`（已提供）
Tauri buildSrc 内部用 `kotlin-dsl` Gradle 插件，且容器内有 HTTPS 代理，需要在 `~/.gradle/gradle.properties` 中显式声明：

```properties
# 强制使用 JDK 17（容器默认是 Java 25）
org.gradle.java.home=/usr/lib/jvm/java-17-openjdk-amd64

# 代理（视实际网络情况调整）
systemProp.http.proxyHost=127.0.0.1
systemProp.http.proxyPort=18080
systemProp.https.proxyHost=127.0.0.1
systemProp.https.proxyPort=18080
systemProp.http.nonProxyHosts=localhost|127.*|[::1]|10.*|192.168.*|*.svc|*.cluster.local
systemProp.https.nonProxyHosts=localhost|127.*|[::1]|10.*|192.168.*|*.svc|*.cluster.local

# 强制 TLS 协议（解决 Google Maven TLS 握手失败）
systemProp.https.protocols=TLSv1.2,TLSv1.3
systemProp.http.protocols=TLSv1.2,TLSv1.3
systemProp.jdk.tls.client.protocols=TLSv1.2,TLSv1.3

org.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8 --enable-native-access=ALL-UNNAMED
```

## 4. 初始化 Android 工程

首次构建前需执行一次 `tauri android init` 生成 Android 项目骨架：

```bash
source env-android.sh
pnpm tauri android init
```

这会在 `src-tauri/gen/android/` 下生成完整 Android 工程，包含：
- `app/` — Android app module
- `app/src/main/AndroidManifest.xml` — 权限声明（已添加网络/通知/前台服务）
- `app/src/main/java/com/aimonitor/app/` — Java/Kotlin 入口
- `buildSrc/` — Rust 调用 Gradle 的桥梁插件

## 5. Wrapper 修复（一次）

Tauri 生成的 `gradle-wrapper.properties` 默认从 `services.gradle.org` 下载 `gradle-8.14.3-bin.zip`，但在受限网络下会超时。使用本地已有的 `8.14.4` 替换：

```bash
./fix-android-wrapper.sh
```

该脚本会：
1. 打包本地 `/root/.local/share/mise/installs/gradle/8.14.4/gradle-8.14.4` 为 zip
2. 清理未完成的下载
3. 修改 `src-tauri/gen/android/gradle/wrapper/gradle-wrapper.properties`，让 wrapper 从本地 zip 加载

## 6. 构建 Debug APK

```bash
source env-android.sh
pnpm tauri android build --apk --debug
```

构建产物：
```
src-tauri/gen/android/app/build/outputs/apk/universal/debug/app-universal-debug.apk
```

universal APK 同时包含 4 个架构：
- `arm64-v8a`（生产推荐）
- `armeabi-v7a`（旧设备）
- `x86`（模拟器）
- `x86_64`（模拟器）

> 💡 输出文件通常 600MB+（含 4 个架构 + debug symbols），如需分发小体积 APK，可加 `--target aarch64` 只构建 arm64。

## 7. 构建 Release APK

```bash
source env-android.sh
pnpm tauri android build --apk
```

Release 构建会启用 R8/ProGuard 优化（`isMinifyEnabled = true`），并要求 keystore 签名。详见 Tauri 官方文档关于 Android 签名的章节。

## 8. 故障排查

| 症状 | 原因 | 解决 |
|------|------|------|
| `Could not find tool "aarch64-linux-android-clang"` | NDK 自带 clang 带 API level 后缀 | 创建 2.5 节中的符号链接 |
| `error: no library targets found in package ai-model-monitor` | 缺 `[lib]` section | 确认 `src-tauri/Cargo.toml` 包含 `[lib] crate-type = ["staticlib", "cdylib", "rlib"]` |
| `unresolved import tauri_plugin_global_shortcut::*` | Android 不支持全局快捷键 | 用 `#[cfg(desktop)]` 包裹该插件相关代码 |
| Gradle Wrapper 下载超时 | 受限网络无法访问 `services.gradle.org` | 执行 `./fix-android-wrapper.sh` |
| `Plugin [id: 'org.gradle.kotlin.kotlin-dsl', version: '5.2.0'] was not found` | Gradle 走代理失败 | 配置 `~/.gradle/gradle.properties` 代理 + JDK 17 + TLS 协议 |
| `Remote host terminated the handshake`（Google Maven） | TLS 协议协商失败 | 加 `systemProp.https.protocols=TLSv1.2,TLSv1.3` |
| `Network is unreachable` for `plugins.gradle.org` | Gradle 未走代理 | 用 `systemProp.http.proxyHost` 而非 `HTTPS_PROXY` 环境变量 |

## 9. 文件清单

本环境配置涉及的文件：
- `env-android.sh` — 加载环境变量（一键 source）
- `fix-android-wrapper.sh` — 修复 Gradle Wrapper 使用本地 zip
- `~/.gradle/gradle.properties` — Gradle 代理 / JDK / TLS 配置
- `src-tauri/Cargo.toml` — 添加 `[lib]` 段 + `rustls-tls`
- `src-tauri/src/lib.rs` — 跨平台 `run()` 入口 + `#[cfg(desktop)]` 桌面端代码
- `src-tauri/src/main.rs` — 简化为 `ai_model_monitor_lib::run()`
- `src-tauri/gen/android/app/src/main/AndroidManifest.xml` — 添加通知/网络权限
- `src-tauri/gen/android/gradle/wrapper/gradle-wrapper.properties` — 由 `fix-android-wrapper.sh` 自动修复
