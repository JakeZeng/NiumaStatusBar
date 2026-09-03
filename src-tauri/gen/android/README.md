# widget-patches/

## 背景

`tauri-cli 2.11.x` 的 `tauri android init --ci` 命令在 CI 中会先 `rm -rf
src-tauri/gen/android/` 然后重新生成整个脚手架。**所有直接放在 `gen/android/`
下的 widget 改动都会被 init 覆盖**。

为了让 widget 改动能稳定地进入 CI 产物，我们把 widget 的"自定义部分"
放在本目录（`widget-patches/`），CI 在 `tauri android init` **之后**统一
`cp -r widget-patches/* gen/android/`，覆盖默认生成的内容。

## 包含内容

完整覆盖当前唯一在用的 1x2 横条 widget 架构（含 v0.1.39 起的轮播），位于
`widget-patches/app/src/main/`：

```
app/src/main/
├── AndroidManifest.xml                       # 含 UsageWidgetProvider receiver
│                                             # + UsageWidgetCarouselService
│                                             # + FOREGROUND_SERVICE_SPECIAL_USE
├── java/com/aimonitor/app/widget/
│   ├── UsageWidgetProvider.kt                # 唯一 receiver（拉起 carousel service）
│   ├── UsageWidgetCarouselService.kt         # 前台服务，每 5 秒轮播所有 provider
│   ├── WidgetLayoutBuilder.kt                # 1x2 渲染逻辑（带 index 轮播参数）
│   ├── WidgetTheme.kt                        # 配色（apply() 无 size 参数）
│   ├── UsageSnapshot.kt                      # 数据快照（git tracked，本目录不收）
│   └── WidgetDataReader.kt                   # SQLite 直读（git tracked，本目录不收）
└── res/
    ├── drawable/
    │   ├── widget_background.xml             # 1x2 背景
    │   ├── widget_background_{cyberpunk,guoman,wuxia}.xml
    │   ├── widget_status_dot{,_disabled,_error}.xml
    │   └── ic_widget_notify.xml              # 前台服务通知图标
    ├── layout/
    │   └── widget_1x2.xml                    # 含轮播页码 widget_page_index
    ├── xml/
    │   └── widget_provider_info_1x2.xml      # 1x2 manifest
    └── values/, values-zh/
        └── widget_strings.xml                # 含 4 个 carousel 通知相关字符串
```

**不在本目录但 init --ci 后会从 git 还原的文件**（见 CI `Restore home-screen
widget sources after init` 步骤）：

- `widget/UsageSnapshot.kt` + `widget/WidgetDataReader.kt` —— 被 Provider /
  LayoutBuilder / CarouselService 三处引用，v0.1.37 时没搬进本目录，init 清空后
  从 git HEAD 取回。

## v0.1.39 起删除的代码

之前 v0.1.18–v0.1.36 发布的 2x3 卡片 widget（`StatusWidgetProvider` /
`StatusWidgetService` + `widget_status.xml` / `widget_loading.xml` /
`widget_quota_item.xml` / `widget_bg.xml` / `widget_progress_*.xml` /
`widget_refresh_button.xml`）已全部删除：

- v0.1.37 移除了 `AndroidManifest` 里的 receiver
- v0.1.39 删除 git 跟踪的所有死代码、layout、drawable、xml metadata、Rust 端
  `src-tauri/src/widget_snapshot.rs`（旧的 JSON 快照写入路径无人调用）
- CI workflow 不再 `git checkout` 还原这些文件

Rust 侧 `widget_snapshot.rs` 删除后，1x2 轮播 service **直接读 SQLite**
（`WidgetDataReader.latestForEnabledProviders`），跨进程并发安全由
`storage.rs` 的 WAL + `busy_timeout=5000` 保证。

## CI 同步

CI 在 `tauri android init --ci` 之后（`release.yml` / `android.yml` 中）执行：

```bash
cp -r src-tauri/widget-patches/* src-tauri/gen/android/
```

加在现有 `pnpm tauri android init` 步骤之后、`Disable app-module BuildConfig` 之前。

## AndroidManifest.xml 处理

`AndroidManifest.xml` 走整文件 cp 覆盖（v0.1.37 后 init 生成的内容没有 receiver
会与本目录冲突）。如果未来 Tauri 模板新增 receiver，可改回 sed patch。

## 添加新 widget 时的步骤

1. 在 `widget-patches/app/src/main/` 下新增文件（与 `gen/android/` 同样目录结构）
2. 同步把改动也写到 `gen/android/`（**让本地开发能直接 build**）
3. PR 时 diff 会同时显示 `widget-patches/` 和 `gen/android/` 改动，reviewer 看到两处一致即可
4. 若新增的 widget 文件名不在本目录覆盖范围，确保 CI workflow 的 `git checkout`
   列表也包含它
