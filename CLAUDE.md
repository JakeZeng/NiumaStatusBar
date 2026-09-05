# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 工作流规则

收到任何需求或任务时，**第一步**必须先到项目根目录下的 `docs/` 查阅可用信息（`docs/android-build.md`、`docs/desktop-release.md` 等），把与当前任务相关的文档内容纳入考量，然后再开始分析代码或动手实现。

## Commands

包管理器固定使用 **pnpm**（仓库提供 `pnpm-lock.yaml`，`package-lock.json` 仅为遗留产物）。

```bash
pnpm install                # 安装前端依赖
pnpm tauri dev              # 联合启动 Vite + Rust，含 HMR 与 Rust 重新编译
pnpm dev                    # 仅 Vite（无 Tauri 壳，IPC 调用会失败）
pnpm build                  # tsc 类型检查 + Vite 产物到 dist/
pnpm tauri build            # 当前平台桌面打包
pnpm tauri build --target <triple> --bundles <fmt>   # 指定目标
```

Rust 侧：

```bash
cd src-tauri && cargo fmt && cargo clippy
cd src-tauri && cargo check     # 不出二进制时的快速类型/借用检查
cd src-tauri && cargo test      # 运行 Rust 单元测试（目前仅 logging.rs 有 8 个 #[test]）
```

前端**没有**测试套件（无 vitest/jest）。`zustand` 在 package.json 中声明但**全仓库无人 import**——状态管理实际是 `useState` + 回调 props，不要被依赖和 README 误导。

Android（产物归 `release/`，CI 工作流见 `.github/workflows/android.yml`）：

```bash
source ./env-android.sh         # 需要 JDK 21 + Android SDK + NDK 27.0.12077973，路径写死在脚本里
                                # Windows 用 env-android-windows.sh / android-dev.bat / android-dev.ps1
pnpm tauri android init         # 首次生成 gen/android
pnpm tauri android dev
pnpm tauri android build
./fix-android-wrapper.sh        # 修复 gen/android Gradle wrapper 在新设备/CI 上的丢失/下载超时问题
```

CI/Release（`.github/workflows/` 三个工作流）：
- `ci.yml`：push/PR 到 main/master，跑 `pnpm build` + `cargo check`（ubuntu + windows）。
- `release.yml`：tag 形如 `v0.x.y` 触发，tauri-action 跨平台构建并发布 **draft** GitHub Release。
- `android.yml`：仅手动 `workflow_dispatch`，构建全部 5 个 APK 但只上传 **arm64**；签名读可选的 `gen/android/app/keystore.properties`，缺失则回退 debug 签名。

## Architecture

跨平台 Tauri 2.x 应用。**前端是渲染层，所有持久化、HTTP 调用、轮询、解析都在 Rust 侧**。前端与后端只通过 `invoke()` 命令 + 事件通信。

### Provider 数据模型与三层身份

每个 Provider 同时存在三种"身份"，新增/修改时必须三者一致：

1. **预置目录（`src-tauri/src/catalog.rs`）** — `ProviderPreset`，硬编码厂商列表模板。**目前实际只内置 3 个 preset**：MiniMax 国内（`minimax_coding`）、MiniMax 海外（`minimax_token`）、DeepSeek；火山方舟/openai/anthropic 等在文件注释中明确标注"待端到端验证后开放"。
2. **运行配置（`ProviderConfig`，定义在 `src-tauri/src/providers.rs`，前端镜像在 `src/api.ts`）** — 用户启用预置后由 `enable_preset` 命令实例化，写入 SQLite `providers` 表（12 列）。`provider` 字段（字符串）是后端解析逻辑的分发依据。**注意：`ProviderConfig` 派生 `#[serde(rename_all = "camelCase")]`，IPC 上是 `baseUrl/apiKey/queryEndpoint` 等 camelCase**；而 `UsageStatus` 没有 rename，保持 snake_case。后端解析逻辑会读 `query_params.model`，所以"模型选择"塞进 `query_params` 而不是单独字段。
3. **运行状态（`UsageStatus`）** — 轮询产生，含 `balance/balance_used/balance_limit`、`currency`、三维额度 `quota_{5h,week,month}_{total,used,remaining,remaining_percent}` 与三档重置时间 `quota_{5h,week,month}_reset_at`。推给前端事件并落入 `usage_history` 表。

### Provider 类型与解析分发

`provider` 字符串集合：`openai / anthropic / deepseek / zhipu / moonshot / qwen / gemini / minimax_coding / minimax_token / volcengine_coding / volcengine_token / custom`。

**只有 5 个类型有真实解析分支**：`deepseek`（`parse_deepseek_balance`，读 `balance_infos[0]`）、`minimax_coding/minimax_token`（`parse_minimax_remains`，读 body 的 `model_remains[0]`，兼容国内 `current_interval_*` + 海外 `interval_*/weekly_*` 两套字段名）、`volcengine_coding/volcengine_token`（`parse_coding_plan_headers`，从 `X-RateLimit-{Remaining,Limit,Reset}-{5H,Week,Month}` 响应头取额度与重置时间）。其余类型只走 `parse_generic_json` 兜底（balance/used/limit/credit/hard_limit_usd 等候选字段）。

`providers.rs::fetch_usage` 是单一 HTTP 入口（超时 15s）：

1. **请求构造**：`query_params` 里的 `model` 被剥离出 URL（仅用于 body），其他键作为 query string。
2. **Body 注入条件**（`needs_body`）：`POST` + (`provider == volcengine_*` **或** URL 含 `/chat/completions` **或** 命中 `is_ark_endpoint`——host 含 `ark.cn-beijing.volces.com`/`ark.volces.com`，或 URL 含 `volces.com/api/`、`/api/coding/v3`）。注入最小 chat 请求 `{model, messages:[{ping}], max_tokens:1, stream:false}` 触发服务端 `X-RateLimit-*` 响应头。**改 URL 匹配逻辑前先看 `is_ark_endpoint` 一组布尔判断，自定义 Provider 指向 Ark 时也必须命中。**
3. **模型动态解析**：Ark coding 套餐的模型名由 `resolve_coding_model` GET `{base_url}/models` 取 `data[0].id`，按 provider 缓存（`coding_model_cache`），遇模型相关 4xx 失效；兜底 `ark-code-latest`。遗留默认名 `doubao-seed-code-1-0-260215` 视为未设置。

新增 Provider 类型时通常需要：在 `catalog.rs` 加 preset + 在 `fetch_usage` 加专用解析分支（或拓展 `parse_generic_json` 的字段候选列表）。

### 轮询调度

`poller.rs::Poller` 持有 `handles: Mutex<HashMap<String, Arc<Notify>>>`——每个启用 Provider 一个 task，`Notify` 作停止信号。`spawn_poll()` 是 pub 的，**先 `stop_poll` 再 spawn（替换语义）**，task 启动时立即 tick 一次，之后按 `tokio::time::interval`（`MissedTickBehavior::Delay`）轮询，每 tick 重新核对 `is_enabled`，禁用或收到停止信号即退出。

**即时生效已接通**：`add_provider`、`update_provider`、`enable_preset`、`toggle_provider` 都会在写库后立即 `spawn_poll`/`stop_poll`，无需重启。**唯一例外是 `import_config`：它只写库 + `manager.set_providers()`，不协调 poller——导入的 Provider 要重启后才开始轮询。** 失败走指数退避（1s→2s→4s）共 3 次。刷新最小值 10 秒（`refresh_interval.max(10)`）。

成功 tick：写 `usage_history`、更新内存状态、emit `status-update`；失败：构造带 `last_error` 的错误状态，同样 emit。

### IPC 命令、状态共享与事件

`lib.rs::run()` 用 `app.manage()` 注入四个共享句柄：`Arc<Database>`、`Arc<ProviderManager>`、`Arc<AppLogger>`（同时注入 manager）、`Arc<Poller>`；桌面端另有 `CloseActionCache`。命令清单（20 个）见 `lib.rs::invoke_handler!` 与 `src/api.ts::api`，二者必须一一对应：

- Provider CRUD：`get_providers / add_provider / update_provider / delete_provider / toggle_provider`
- 状态/历史：`fetch_provider_status`（手动一次性拉取，绕过 poller）、`get_provider_status`（读内存缓存）、`get_usage_history`
- 目录：`get_provider_catalog / enable_preset / get_active_preset_ids`（注意：后者名字误导，实际返回的是 provider **类型字符串**列表，不是 preset id）
- 配置：`export_config`（导出 JSON **含明文 api_key**）、`import_config`
- 关闭行为：`get/set/reset_close_action`、`window_hide_to_tray`、`app_quit`
- 日志：`query_logs`、`clear_logs`

事件流（前端均已 `listen` 接入）：
- `status-update`：`ProviderCard.tsx` 按 `provider_id` 过滤后实时更新卡片；`HistoryChart` 仍靠轮询 `get_usage_history`。
- `close-requested`：桌面端关闭按钮且未设置偏好时，后端拦截关闭并 emit，前端弹 `CloseConfirmDialog`（可选"记住选择"→ `set_close_action`，偏好存 `settings` 表）。
- `app-log`：每条新日志实时推给 `LogViewer`。

### 日志系统（`logging.rs`，CLAUDE.md 旧版完全未覆盖）

日志**不写文件**，存 SQLite `app_logs` 表（同库）+ 200 条内存环形缓冲。`AppLogger::log` 非阻塞：经 mpsc channel 发到后台 task 落库、推缓冲、emit `app-log`。级别 debug/info/warn/error；分类 http/provider/poller/database/command/setup/tray/system。`LogQuery`（camelCase）支持 keyword/levels/categories/since/until/limit（默认 200，夹 1–1000）。**脱敏**：`api_key/authorization/x-api-key` 等键值递归替换为 `***`，`Bearer <token>` 在文本中掩码。保留 7 天（启动时 `cleanup_old_logs(7)`）。前端 `LogViewer.tsx` 支持搜索/过滤/实时 tail。

### 系统托盘（`tray.rs`，桌面端专属，`#![cfg(desktop)]`）

菜单两项：显示主窗口、退出；左键图标也显示窗口。后台任务每 **3 秒**轮播启用 Provider 的 tooltip：名称、余额、5h/周剩余百分比、月用量、相对重置时间（`format_relative_reset`），错误显示 `⚠`，空态显示"暂无启用供应商"。

### 桌面 vs 移动

`#[cfg(desktop)]` / `#[cfg(not(desktop))]` 切分。仅桌面端：`tauri-plugin-global-shortcut`（`Ctrl+Shift+M` 切换主窗口可见性）、tray 模块、窗口关闭事件拦截（`exit` 放行 / `minimize_to_tray` 隐藏 / 默认 emit `close-requested`）。`tauri-plugin-shell` 和 `tauri-plugin-notification` 双端都注册。`Cargo.toml` 里 `tauri-plugin-global-shortcut` 是无条件依赖，移动构建带上但不调用。新增桌面专属能力按相同模式 `#[cfg(desktop)]` 包住。

### Android 桌面小部件（home-screen AppWidget）

代码在 `src-tauri/gen/android/`（**已提交进仓库**，非生成产物）：

- `app/src/main/java/com/aimonitor/app/widget/`：`BaseUsageWidgetProvider` + Small/Medium/Large 三个尺寸变体（manifest 中三个独立 receiver，启动器显示 3 个 widget 条目）；大尺寸用 `UsageRemoteViewsService/Factory` 做列表。
- **数据读取方式**：`WidgetDataReader.kt` 以只读方式直接打开 `/data/data/com.aimonitor.app/ai-model-monitor/data.db` 查 `usage_history` 最新行——依赖 Rust 侧启用 WAL + `busy_timeout=5000` 才能跨进程并发读，**改 storage.rs 的 PRAGMA 时要小心**。
- 点击 widget 行携带 `provider_id` 深链 → `MainActivity.kt` 用 `evaluateJavascript` 调 `window.__NIUMA_SELECT_PROVIDER__('...')` → 前端钩子在 `src/App.tsx`（选中对应 Provider tab）。改这个 JS 函数名要三端同步。
- 资源含中英双语 `widget_strings.xml`、昼夜 `widget_colors.xml`。MainActivity 还强制软件渲染 WebView 以兼容 MuMu 模拟器。

### 前端架构

`App.tsx` 是单页主壳，**没有路由**，状态用 `useState` 直接持有 providers 列表 + 选中 ID + 各弹层开关，跨组件更新走回调 props（如 `onProvidersUpdated`）。`ProviderHub` 弹层做预置目录选购，`ConfigModal` 做自定义/编辑；`ImportExport` 做配置 JSON 导入导出；`MobileMenu` 是移动端（`<sm`）"更多"下拉，收纳主题/语言/日志/导入导出入口；`ConfirmDialog` 是通用确认弹窗（删除 Provider 用），`CloseConfirmDialog` 处理关闭选择；`LanguageSwitcher` 切换中英。

主题切换走 `data-theme` 属性 + CSS 变量（`src/index.css`），三套主题：`cyberpunk / wuxia / guoman`。i18n 通过 `react-i18next` 加载 `src/i18n/locales`，中英双语，默认 zh，偏好存 `localStorage`。

工具函数：`src/lib/format.ts`（`formatRelativeReset` 秒级时间戳→"XdYh 后重置"）、`src/lib/currency.ts`（货币符号映射 + 按 provider 类型给默认货币，优先级：后端 currency → provider 默认 → USD）。

**新增主题**：`index.css` 加变量块 → `themes/ThemeManager.ts` 加配置 → `ThemedBackground.tsx` 加背景装饰。

**新增 IPC 命令**：`commands.rs` 写处理函数 → `lib.rs::invoke_handler!` 注册 → `src/api.ts` 加 `invoke()` 封装与类型。

### 存储

SQLite 路径：`{app_data_dir}/ai-model-monitor/data.db`，WAL 模式、`busy_timeout=5000`。四表：

- `providers` — 配置全量 12 列（含 **API Key 明文**、`query_headers`/`query_params` JSON）。
- `usage_history` — 余额 3 列 + `requests_today/error_rate/avg_latency/last_error`，**外加 11 个 `quota_*` 列（通过幂等 `ALTER TABLE` 迁移补齐，老库自动升级）**，三维额度趋势可画。**仍不入库**：`currency` 和三个 `quota_*_reset_at`。
- `settings` — KV 偏好表（目前存关闭行为 `close_action`）。
- `app_logs` — 日志，带 timestamp 与 (level, category) 索引。

历史与日志均保留 7 天，启动时清理。删除 Provider 时手动级联删历史。
