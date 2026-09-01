# widget-patches/

## 背景

`tauri-cli 2.11.x` 的 `tauri android init --ci` 命令在 CI 中会先 `rm -rf
src-tauri/gen/android/` 然后重新生成整个脚手架。**所有直接放在 `gen/android/`
下的 widget 改动都会被 init 覆盖**。

为了让 widget 改动能稳定地进入 CI 产物，我们把 widget 的"自定义部分"
放在本目录（`widget-patches/`），CI 在 `tauri android init` **之后**统一
`cp -r widget-patches/* gen/android/`，覆盖默认生成的内容。

## 包含内容

完整覆盖 v0.1.37 widget 架构（1x2 横条），位于 `widget-patches/app/src/main/`：

```
app/src/main/
├── java/com/aimonitor/app/widget/
│   ├── UsageWidgetProvider.kt      # 唯一 receiver
│   ├── WidgetLayoutBuilder.kt      # 1x2 渲染逻辑（无 Size 枚举）
│   └── WidgetTheme.kt              # 配色（apply() 无 size 参数）
└── res/
    ├── layout/
    │   └── widget_1x2.xml          # 1 行高 × 2 列宽 横条 layout
    ├── xml/
    │   └── widget_provider_info_1x2.xml  # 1x2 manifest
    └── values/, values-zh/
        └── widget_strings.xml       # widget_label_one / widget_desc_one
```

旧版（v0.1.18–v0.1.36）的 2x2 / 2x3 / 2x4 三 receiver 架构已删除：
- `UsageWidgetProviderSmall/Medium/Large.kt` → 不再需要
- `BaseUsageWidgetProvider.kt` → 合并到单类
- `UsageRemoteViewsService / Factory` → 大尺寸列表型不再发布
- `widget_small/medium/large.xml` + 三个 `widget_provider_info_*.xml` → 删除

## CI 同步

CI 在 `tauri android init --ci` 之后（`release.yml` / `android.yml` 中）执行：

```bash
cp -r src-tauri/widget-patches/* src-tauri/gen/android/
```

加在现有 `pnpm tauri android init` 步骤之后、`Disable app-module BuildConfig` 之前。

## AndroidManifest.xml 处理

`AndroidManifest.xml` 走 sed patch 而非整文件 cp（避免与 init 生成的其他
receiver / application 节点冲突）。详见 release.yml/android.yml 中
`Apply 1x2 widget patches` 步骤：

1. `cp -r widget-patches/* gen/android/`
2. sed 删除 `UsageWidgetProviderSmall/Medium/Large` 三个 receiver
3. sed 删除 `UsageRemoteViewsService` service 节点
4. sed 替换 `UsageWidgetProviderSmall/Medium/Large` 引用为 `UsageWidgetProvider`
5. 替换 `widget_label_*` / `widget_desc_*` strings 引用

## 添加新 widget 时的步骤

1. 在 `widget-patches/app/src/main/` 下新增文件（与 `gen/android/` 同样目录结构）
2. 同步把改动也写到 `gen/android/`（**让本地开发能直接 build**）
3. 如果新增/删了 receiver，更新本 README 的 CI sed patch 清单
4. PR 时 diff 会同时显示 `widget-patches/` 和 `gen/android/` 改动，reviewer 看到两处一致即可
