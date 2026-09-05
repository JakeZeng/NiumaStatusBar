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
- `src-tauri/gen/android/buildSrc/src/main/java/com/aimonitor/app/kotlin/RustPlugin.kt` + `BuildTask.kt` — 自研 Gradle 插件：`rustBuild<Arch><Profile>` 任务跳过 tauri-cli 直接 cargo build（绕开 Windows symlink）；改完必须 `--stop` + `--rerun-tasks`
- `src-tauri/gen/android/gradle.properties` — `abiList` / `archList` / `targetList` 控制三个 ABI
- `src-tauri/tauri.conf.json` + `package.json` — **保持 UTF-8 无 BOM**，否则 tauri-build / Vite 双双解析失败（见 §10.10）

---

## 10. Windows 真机调试实战（PowerShell 5.1）

> 适用于：本地 Windows 11 + Android 真机 / 模拟器（如 MuMu）。与第 2-5 节的 Linux 容器 CI 流程不同。

### 10.1 路径约定

| 组件 | 实际路径 |
|------|----------|
| JDK 21 | `D:\java\OpenJDK21U-jdk_x64_windows_hotspot_21.0.3_9` |
| Android SDK | `D:\Android\SDK` |
| NDK | `D:\Android\SDK\ndk\27.0.12077973` |
| cargo | `D:\.cargo\bin\cargo.exe`（用户级）或 `C:\Users\NiWinHao\.cargo\bin\cargo.exe` |
| Gradle 缓存 | `E:\.gradle\`（用户本地，含 wrapper 缓存） |
| 项目根 | `E:\Works\solidsugar_repos\NiumaStatusBar` |

### 10.2 环境加载（`build_android.ps1`）

项目根目录下已提供 `build_android.ps1`，**新 PowerShell 每次都要 dot-source 一次**：

```powershell
. .\build_android.ps1
# 预期输出：
#   ANDROID_HOME = D:\Android\SDK
#   NDK_HOME     = D:\Android\SDK\ndk\27.0.12077973
#   Java         = openjdk version "21.0.3"
#   Cargo        = cargo 1.98.0
#   adb devices  = <device-id> device
```

> ⚠️ 必须在项目根目录运行，否则 cargo / PATH 找不到。脚本同时设置 `JAVA_HOME`、`ANDROID_HOME`、`NDK_HOME` 和跨盘符 cargo junction（见 10.4）。

### 10.3 国内 Maven 镜像（`E:\.gradle\init.d\`）

新建 `E:\.gradle\init.d\aliyun-mirror.gradle`：

```groovy
allprojects {
    buildscript {
        repositories {
            maven { url "https://maven.aliyun.com/repository/google" }
            maven { url "https://maven.aliyun.com/repository/gradle-plugin" }
            maven { url "https://maven.aliyun.com/repository/central" }
            maven { url "https://maven.aliyun.com/repository/public" }
        }
    }
    repositories {
        maven { url "https://maven.aliyun.com/repository/google" }
        maven { url "https://maven.aliyun.com/repository/gradle-plugin" }
        maven { url "https://maven.aliyun.com/repository/central" }
        maven { url "https://maven.aliyun.com/repository/public" }
    }
}

settingsEvaluated { settings ->
    settings.pluginManagement {
        repositories {
            maven { url "https://maven.aliyun.com/repository/gradle-plugin" }
            maven { url "https://maven.aliyun.com/repository/google" }
            maven { url "https://maven.aliyun.com/repository/public" }
        }
    }
}
```

`wrapper distributionUrl` 不走 init.d（保留 `services.gradle.org` 命中本地缓存 `E:\.gradle\wrapper\dists\gradle-8.14.4-bin\...`）。

`buildSrc/build.gradle.kts` 也需补镜像：

```kotlin
repositories {
    maven { url = uri("https://maven.aliyun.com/repository/google") }
    maven { url = uri("https://maven.aliyun.com/repository/central") }
    maven { url = uri("https://maven.aliyun.com/repository/public") }
    maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
    google()
    mavenCentral()
    gradlePluginPortal()
}
```

### 10.4 跨盘符 cargo junction

cargo 注册表默认在 `C:\Users\NiWinHao\.cargo\registry\src\`，工程在 `E:\`，Kotlin daemon 跨盘符增量编译会抛 `IllegalArgumentException: this and base files have different roots`。建软链接归一：

```powershell
New-Item -ItemType Junction -Path "E:\.cargo" -Target "C:\Users\NiWinHao\.cargo"
```

### 10.5 Windows 开发者模式（一次性）

Tauri 在 `rustBuildArm64Release` 之后用 symlink 把 `.so` 链进 `jniLibs/arm64-v8a/`，Windows 默认禁止普通用户创建 symlink：

```powershell
# 管理员 PowerShell 一次：
reg add "HKEY_LOCAL_MACHINE\SOFTWARE\Microsoft\Windows\CurrentVersion\AppModelUnlock" /t REG_DWORD /f /v "AllowDevelopmentWithoutDevLicense" /d "1"
```

或：设置 → 隐私和安全 → 开发者选项 → 打开 **开发人员模式**。

### 10.6 构建命令（正确写法）

```powershell
. .\build_android.ps1
pnpm tauri android build --apk --debug        # debug APK（开发自测）
pnpm tauri android build --apk                 # release APK（需签名）
```

产物：
- debug：`src-tauri\gen\android\app\build\outputs\apk\arm64\debug\app-arm64-debug.apk`
- release：`src-tauri\gen\android\app\build\outputs\apk\arm64\release\app-arm64-v8a-release.apk`

> 💡 `gradle.properties` 已经配好 `targetList=aarch64-linux-android,armv7-linux-androideabi,x86_64-linux-android`（含 `arm64-v8a`/`armeabi-v7a`/`x86_64` 三个 ABI），不需要 CLI 再传 `--target`。**想只出 arm64 APK**，跳过 `pnpm tauri` 直接跑：
>
> ```powershell
> gradlew.bat --project-dir src-tauri\gen\android --no-daemon assembleArm64Debug
> ```

❌ `--target aarch64`（会被 tauri-cli 转成短名 `-PtargetList=aarch64`，BuildTask 直接 cargo 时找不到 target 规格）
❌ `--target aarch64-linux-android`（cargo 完整 triple，tauri-cli 不收）
❌ `--no-bundle`（该参数属于 `tauri build`，不属于 `tauri android build`）
❌ 直接跑 `gradlew assembleRelease`（跳过 tauri_build 嵌入 `dist/`，运行后白屏报 `asset not found :index.html`）

### 10.7 签名（首次构建需要）

```powershell
# 在 src-tauri\gen\android\app\ 目录下：
keytool -genkey -v -keystore niuma.keystore -alias niuma -keyalg RSA -keysize 2048 -validity 10000

# 新建 keystore.properties（同目录）：
#   storeFile=niuma.keystore
#   storePassword=<你设置的密码>
#   keyAlias=niuma
#   keyPassword=<你设置的密码>
```

缺失时构建报：`SigningConfig "release" is missing required property "storeFile"`。

### 10.8 安装与日志

```powershell
adb devices                                                       # 确认 <device-id> device
adb install -r src-tauri\gen\android\app\build\outputs\apk\arm64\release\app-arm64-v8a-release.apk
adb logcat -s MainActivity:* UsageWidgetCarouselService:* RingWidgetCarouselService:* WidgetDataReader:*
```

桌面长按 → Widgets → "AI Monitor · 2x2 Ring" → 拖到桌面，30s 内应见 5H/周/月三环或余额单环轮播。

### 10.9 本次踩坑回顾（v0.1.55 前）

| 报错 | 原因 | 修法 |
|------|------|------|
| `JAVA_HOME is set to an invalid directory` | `android-dev.ps1` / `env-android-windows.sh` 路径写错 | 改成实际路径 `D:\java\OpenJDK21U-jdk_x64_windows_hotspot_21.0.3_9` |
| `Failed to install Android NDK` | tauri 自动装 NDK，没用本地 | 手动设 `NDK_HOME=D:\Android\SDK\ndk\27.0.12077973` |
| `cargo metadata: program not found` | cargo 不在 PATH | `build_android.ps1` 里加 `C:\Users\NiWinHao\.cargo\bin` |
| Kotlin daemon 跨盘符报错 | C 盘 cargo registry 与 E 盘工程跨盘 | junction `E:\.cargo` → `C:\Users\NiWinHao\.cargo` |
| `asset not found :index.html` | 直接跑 `gradlew assembleRelease` 跳过资源嵌入 | 必须走 `pnpm tauri android build`（自动跑 `pnpm build` 把 dist/ 塞进 .so） |
| `--no-bundle` 不被识别 | 属于 `tauri build` 而非 `tauri android build` | 删除 |
| `Creation symbolic link is not allowed` | Windows 默认禁 symlink | 开启开发者模式 |

### 10.10 v0.1.56 实战补充（2026-09-05）

#### BOM 让 tauri-build / Vite 双双解析失败

`src-tauri\tauri.conf.json` 和根目录 `package.json` 不应有 UTF-8 BOM（`EF BB BF`），但提交时混进去了。两端报错：

| 文件 | 报错 |
|------|------|
| `src-tauri\tauri.conf.json` | `unable to parse JSON Tauri config file ... expected value at line 1 column 1`（tauri build.rs 卡这里，cargo 退出 101） |
| `package.json` | `vite v6.4.3 ... error during build: SyntaxError: Unexpected token '﻿', "﻿{` |

修法（一次性，PowerShell 5.1）：

```powershell
foreach ($p in @('src-tauri\tauri.conf.json','package.json')) {
  $b = [System.IO.File]::ReadAllBytes($p)
  if ($b[0] -eq 0xEF -and $b[1] -eq 0xBB -and $b[2] -eq 0xBF) {
    [System.IO.File]::WriteAllBytes($p, $b[3..($b.Length-1)])
    Write-Output "BOM stripped: $p"
  }
}
```

> 后续用支持 BOM 的编辑器（VS Code、Notepad++）保存，避免再次带 BOM。

#### Gradle daemon 缓存 buildSrc 旧 jar

`src-tauri\gen\android\buildSrc\build\libs\buildSrc.jar` 是 15:49:57 编出来的——早于我后来改 `RustPlugin.kt` 的时间。daemon 启动时只 `UP-TO-DATE` 检查（按文件 mtime 比较）就锁死，**改了 buildSrc 源码不重 build**的现象：

```
> Task :buildSrc:compileKotlin UP-TO-DATE
> Task :buildSrc:jar UP-TO-DATE
```

但实际跑 `:app:rustBuildArm64Debug` 时还是报旧 bug（`could not find specification for target "aarch64"`），证明它根本没用上新代码。

修法：

```powershell
cd src-tauri\gen\android
.\gradlew.bat --stop                       # 停 daemon
Remove-Item buildSrc\build\libs\buildSrc.jar -ErrorAction SilentlyContinue
# 或者：--rerun-tasks 强制重跑整个 task 图
.\gradlew.bat --no-daemon --rerun-tasks assembleArm64Debug
```

> 改 `buildSrc/**/*.kt` 后**必须** `--stop` + `--rerun-tasks`，否则新代码不生效。

#### PowerShell 5.1 捕获 Gradle 输出的坑

| 用法 | 现象 |
|------|------|
| `... \| Tee-Object -FilePath build.log` | Gradle 子进程 stderr 进不去，log 只到 Vite 输出就截断 |
| `... 2>$null` | 即使构建成功 PowerShell 也返回 exit 2（stderr 被吞但 stderr 重定向本身算失败） |
| `cmd 2>&1 \| Out-File -Encoding utf8 xxx.log` | ✅ 正确写法，能拿到全部输出 |

后台运行则建议：

```powershell
& "src-tauri\gen\android\gradlew.bat" --project-dir "src-tauri\gen\android" --console=plain --no-daemon assembleArm64Debug 2>&1 | Out-File -Encoding utf8 "$env:TEMP\build.log"
```

#### 单独打 arm64 APK（v0.1.56 后推荐）

`gradle.properties` 配的 `targetList` 包含三个 ABI，但**只想 arm64** 时跑整 `assembleDebug` 会顺带编 armv7 + x86_64（首次 5–10 分钟）。跳过 Rust 直接出 APK：

```powershell
cd src-tauri\gen\android
.\gradlew.bat --project-dir . --no-daemon assembleArm64Debug
```

产物：`app\build\outputs\apk\arm64\debug\app-arm64-debug.apk`，再 `adb install -r` 到真机（参见 §10.8）。
