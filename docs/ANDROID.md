# Android 适配指南

## 概述

Tauri 2.x 已正式支持 Android。本项目通过条件编译适配多平台。

## 当前状态

- ✅ 桌面端 (Windows/macOS/Linux) 完整可用
- ⚠️ Android 适配需额外配置（如下）

## 环境要求

```bash
# 1. 安装 Rust Android targets
rustup target add aarch64-linux-android armv7-linux-androideabi

# 2. 安装 Android SDK 与 NDK
# 推荐使用 Android Studio 或命令行工具
export ANDROID_HOME=$HOME/Android/Sdk
export NDK_HOME=$ANDROID_HOME/ndk/<version>
```

## Tauri Android 配置

### 1. 修改 tauri.conf.json

```json
{
  "build": {
    "beforeDevCommand": "pnpm dev",
    "beforeBuildCommand": "pnpm build",
    "frontendDist": "../dist",
    "devUrl": "http://localhost:1420"
  },
  "bundle": {
    "android": {
      "minSdkVersion": 24
    }
  }
}
```

### 2. 在 main.rs 添加条件编译

```rust
#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        // ... 桌面端配置
        .setup(|app| {
            // 平台特定初始化
            #[cfg(mobile)]
            {
                // Android 特定配置
                init_android_specific(app)?;
            }
            
            #[cfg(desktop)]
            {
                // 桌面端配置
                init_desktop_specific(app)?;
            }
            Ok(())
        })
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
```

### 3. 启动 Android 开发

```bash
# 在 Android 设备或模拟器连接后执行
pnpm tauri android dev

# 构建 APK
pnpm tauri android build
```

## Android 平台差异处理

### 1. 系统托盘 → 通知
Android 没有系统托盘，需将轮询异常通过 `tauri-plugin-notification` 推送。

### 2. 全局快捷键
Android 没有全局快捷键，但可注册长按菜单或应用内快捷键。

### 3. SQLite 路径
Android 沙箱环境下，数据库存放于应用私有目录：

```rust
// storage.rs 中调整路径
#[cfg(mobile)]
fn get_db_path() -> Result<PathBuf> {
    let ctx = tauri::AppHandle::default();
    let path = ctx.path().app_data_dir()?;
    Ok(path.join("data.db"))
}

#[cfg(desktop)]
fn get_db_path() -> Result<PathBuf> {
    Ok(dirs::data_dir().unwrap().join("ai-model-monitor/data.db"))
}
```

### 4. 网络权限
Android 9+ 默认禁止明文 HTTP，需在 `AndroidManifest.xml` 配置：

```xml
<application android:usesCleartextTraffic="true">
```

或在 `network_security_config.xml` 中针对特定域名开启。

## UI 自适应

移动端使用响应式布局，已在 `App.tsx` 中实现：

```tsx
// grid 自动从 1 列（手机）→ 2 列（平板）→ 3 列（桌面）
<div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-5">
```

## 常见问题

| 问题 | 解决方案 |
|------|---------|
| NDK 未找到 | 设置 `NDK_HOME` 环境变量 |
| Gradle 编译失败 | 升级 Android Gradle Plugin 到 8.x |
| 权限被拒 | 在 AndroidManifest 声明 INTERNET 权限 |
| 应用启动崩溃 | 检查 `minSdkVersion >= 24` |

## 后续优化方向

- [ ] 添加移动端专属手势操作
- [ ] 集成 Capacitor 实现原生能力
- [ ] 添加 Widget 桌面小部件
- [ ] 优化小屏幕布局
