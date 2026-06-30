# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

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
```

Android（产物归 `release/`，CI 工作流见 `.github/workflows/android.yml`）：

```bash
source ./env-android.sh         # 需要 JDK 17 + Android SDK + NDK 27.x，路径写死在脚本里
pnpm tauri android init         # 首次生成 gen/android
pnpm tauri android dev
pnpm tauri android build
./fix-android-wrapper.sh        # 修复 gen/android Gradle wrapper 在新设备上的丢失/权限问题
```

CI/Release：tag 形如 `v0.x.y` 会触发 `.github/workflows/release.yml` 跨平台构建并自动发布到 GitHub Releases。

测试：仓库目前**没有任何测试套件**（前端无 vitest/jest，Rust 无 `#[test]`）。如需补测试要从零搭。

## Architecture

跨平台 Tauri 2.x 应用。**前端是渲染层，所有持久化、HTTP 调用、轮询、解析都在 Rust 侧**。前端与后端只通过 `invoke()` 命令 + `status-update` 事件通信。

### Provider 数据模型与三层身份

每个 Provider 同时存在三种"身份"，新增/修改时必须三者一致：

1. **预置目录（`src-tauri/src/catalog.rs`）** — `ProviderPreset`，硬编码厂商列表，含 `provider_type`、`base_url`、`query_endpoint`、`requires_body`、`default_model`。这是只读模板。
2. **运行配置（`ProviderConfig`，定义在 `src-tauri/src/providers.rs`，前端镜像在 `src/api.ts`）** — 用户启用预置后由 `enable_preset` 命令实例化，写入 SQLite `providers` 表。`provider` 字段（字符串）是后端解析逻辑的分发依据。
3. **运行状态（`UsageStatus`）** — 轮询产生，含 `balance/balance_used/balance_limit` 与 `quota_{5h,week,month}_{total,used,remaining}` 三维额度。同时被推回前端事件并落入 `usage_history` 表。

注意：`ProviderConfig` Rust 端用 `snake_case` 字段，序列化为 JSON 后被前端**保持 snake_case**消费（见 `src/api.ts` 类型）。后端解析逻辑会读 `query_params.model`，所以"模型选择"是塞进 `query_params` 而不是单独字段。

### Provider 类型与解析分发

`provider` 字符串集合：`openai / anthropic / deepseek / zhipu / moonshot / qwen / gemini / minimax_coding / minimax_token / volcengine_coding / volcengine_token / custom`。

`providers.rs::fetch_usage` 是单一 HTTP 入口，按顺序做三件事：

1. **请求构造**：`query_params` 里的 `model` 会被剥离出 URL（仅用于 body），其他键作为 query string 附加。
2. **Body 注入条件**：`POST` + (`provider == volcengine_*` **或** URL 含 `/chat/completions` **或** URL 命中 ark 域名/`/api/coding/v3`)。注入最小 chat 请求 `{model, messages:[{ping}], max_tokens:1, stream:false}` 以触发服务端的 `X-RateLimit-*` 响应头。**改 URL 匹配逻辑前先看 `is_ark_endpoint` 一组布尔判断，自定义 Provider 指向 Ark 时也必须命中。**
3. **响应解析**（多源叠加，不互斥）：
   - `parse_coding_plan_headers`：火山方舟从 `X-RateLimit-{Remaining,Limit}-{5H,Week,Month}` 响应头取三档额度
   - `parse_generic_json`：OpenAI 风格 `balance / used / limit` / `credit` / `hard_limit_usd`
   - `parse_minimax_remains`：仅当 `provider == minimax_*` 时解析 body 的 `model_remains[0]`（兼容国内 `current_interval_*` + 海外 `interval_*`、`weekly_*` + `week_*` 两套字段名）

新增 Provider 类型时通常需要：在 `catalog.rs` 加 preset + 在 `fetch_usage` 加专用解析分支（或拓展 `parse_generic_json` 的字段候选列表）。

### 轮询调度

`poller.rs::Poller` 在 `setup()` 中 `start_all()`，为**每个启用的 Provider** 单独 `tokio::spawn` 一个 task。每 tick 通过 `manager.get_providers()` 重新核对 `is_enabled`，禁用即 `break`。失败走指数退避（1s→2s→4s）共 3 次。**关键限制**：新建 Provider 不会自动 spawn——目前依赖应用重启或后续路径补齐。修改 `add_provider`/`toggle_provider` 时若希望即时生效，需要显式调用 `poller.spawn_poll()`。

刷新最小值 10 秒（`refresh_interval.max(10)`）。

### IPC 命令与状态共享

`lib.rs::run()` 用 `app.manage()` 注入两个共享句柄：`Arc<Database>` 和 `Arc<ProviderManager>`。所有命令通过 `tauri::State` 拿到它们。命令清单见 `lib.rs::invoke_handler!` 与 `src/api.ts::api`，二者必须一一对应。

事件流：后端 `app_handle.emit("status-update", &status)` → 前端目前**只在 ProviderCard / HistoryChart 里轮询拉**（`getProviderStatus` / `getUsageHistory`），事件订阅未完全接入；若需要实时刷新 UI，在前端 `listen('status-update', ...)`。

### 桌面 vs 移动

`#[cfg(desktop)]` / `#[cfg(not(desktop))]` 切分。仅桌面端：

- `tauri-plugin-global-shortcut`（`Ctrl+Shift+M` 切换主窗口可见性）

移动端共用 `lib::run()`，不注册快捷键插件——`Cargo.toml` 里 `tauri-plugin-global-shortcut` 是无条件依赖，移动构建会带上但不调用。新增桌面专属能力时按相同模式 `#[cfg(desktop)]` 包住。

### 前端架构

`App.tsx` 是单页主壳，**没有路由**，状态用 `useState` 直接持有 `providers` 列表 + 选中 ID。`ProviderHub` 弹层做预置目录选购，`ConfigModal` 做自定义/编辑。主题切换走 `data-theme` 属性 + CSS 变量（`src/index.css`），三套主题：`cyberpunk / wuxia / guoman`。i18n 通过 `react-i18next` 加载 `src/i18n/locales`，中英双语。

**新增主题**：`index.css` 加变量块 → `ThemeManager.ts` 加配置 → `ThemedBackground.tsx` 加背景装饰。

**新增 IPC 命令**：`commands.rs` 写处理函数 → `lib.rs::invoke_handler!` 注册 → `src/api.ts` 加 `invoke()` 封装与类型。

### 存储

SQLite 路径：`{app_data_dir}/ai-model-monitor/data.db`。两表：

- `providers` — 配置全量（含 **API Key 明文**）
- `usage_history` — 历史快照，**仅持久化 9 列**（`balance*`、`requests_today`、`error_rate`、`avg_latency`、`last_error`），**三维额度字段 `quota_*` 不入库**。所以 HistoryChart 只能画余额趋势，画不了 5h/week/month 额度趋势——要画的话先扩 `storage.rs` 的两个 SQL。

历史保留 7 天，启动时执行 `cleanup_old_history()`。
