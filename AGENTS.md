# AGENTS.md - Agentic Coding Guidelines

This file provides guidance to agentic coding agents (e.g., Claude Code, GitHub Copilot) working in the NiumaStatusBar repository. It consolidates build/lint/test commands, code style guidelines, and project conventions from `docs/`, `src/`, and `src-tauri/`.

---

## Build / Lint / Test Commands

| Command | Description |
|---|---|
| `pnpm install` | Install frontend + Rust dependencies |
| `pnpm dev` | Vite dev server only (no Tauri shell; IPC calls will fail) |
| `pnpm tauri dev` | Launch Tauri with Vite + Rust HMR (full-stack dev) |
| `pnpm build` | `tsc` type-check + Vite production build (`dist/`) |
| `pnpm tauri android build --apk --debug --target aarch64` | Build Android debug APK (arm64). **Must** run after `. .\build_android.ps1` to set env vars (ANDROID_HOME, NDK_HOME, cargo linkers). |
| `pnpm tauri android build --apk --debug` | Build universal debug APK (4 architectures) |
| `cd src-tauri && cargo fmt && cargo clippy` | Rust formatting + linting |
| `cd src-tauri && cargo check` | Fast Rust type/borrow check (no binary) |
| `cargo test` | Run Rust unit tests (currently only `logging.rs` has 8 `#[test]`s) |
| `adb install -r <apk>` | Install APK on connected device |
| `adb shell am start -n "com.aimonitor.app/com.aimonitor.app.MainActivity"` | Launch app after install |

**No test framework** is configured (vitest/jest not used). Zustand is declared in `package.json` but **not imported anywhere** in the codebase; state management is via `useState` + callback props.

**Single-test equivalent**: There are no per-file test runners. To verify a Rust function, run `cargo test` in `src-tauri/`. For frontend behavior, manual testing via `pnpm tauri dev` and the UI is the workflow.

---

## Code Style Guidelines

### General Conventions
- **TypeScript**: `strict: true` in `tsconfig.json`; `moduleResolution: bundler`; `jsx: react-jsx`; `skipLibCheck: true`.
- **Rust**: `rust-version = "1.77.2"`; use `cargo fmt`/`cargo clippy`; clippy warnings should be resolved, not suppressed.
- **Naming**: 
  - TypeScript: camelCase for functions/variables, PascalCase for components, SCREAMING_SNAKE_CASE for constants.
  - Rust: snake_case for functions/variables; `CamelCase` for public structs/types; `UPPER_SNAKE_CASE` for constants.
  - Provider `provider` field (string) dispatches to parse logic; valid values: `openai/anthropic/deepseek/zhuget/moonshot/qwen/gemini/minimax_coding/minimax_token/volcengine_coding/volcengine_token/custom`.

### Imports
- **Frontend**: Group imports: React core, then `@tauri-apps/api`, then project-specific (`@/...`). Never use bare `import X from '...'` without checking existing imports first; prefer named imports from `@tauri-apps/api`.
  ```tsx
  import { useCallback, useEffect, useState } from 'react';
  import { listen } from '@tauri-apps/api/event';
  import { invoke } from '@tauri-apps/api/core';
  import { themeManager, type ThemeId } from './themes/ThemeManager';
  ```
- **Rust**: Use absolute paths from crate root when possible (`use providers::ProviderManager`). Import `serde::{Deserialize, Serialize}` together. Use `#[serde(rename_all = "camelCase")]` on `ProviderConfig`; `UsageStatus` keeps snake_case.

### Types / Interfaces
- **ProviderConfig** (Rust): `#[serde(rename_all = "camelCase")]` → IPC sends `baseUrl/apiKey/queryEndpoint` in camelCase.
- **UsageStatus**: No `rename_all`; stays snake_case. Backend reads `query_params.model`; model selection goes into `query_params` not a separate field.
- **LogLevel / LogCategory**: snake_case via `#[serde(rename_all = "lowercase")]` / enum; Display impl outputs lowercase.
- **LogQuery** (frontend): camelCase keys support `keyword/levels/categories/since/until/limit` (default 200, clamped 1–1000).
- **WidgetStatus** (api.ts): PascalCase struct; fields like `lastEventAt`, `lastServiceStartAt`.

### Error Handling
- **Rust**: I/O errors propagate via `anyhow`/`thiserror`. Fetch failures construct error state with `last_error`, emit `status-update` event. Exponential backoff on retry: 1s → 2s → 4s (3 attempts total). Minimum refresh interval: `max(10, ...)` seconds.
- **Frontend**: API errors caught in `.catch()`; error states stored in `useState` and displayed via `ConfirmDialog`/`CloseConfirmDialog`. API key redaction is recursive — `api_key/authorization/x-api-key` keys replaced with `***` before logging/persistence.
- **Android**: Gradle wrapper timeout fix: run `./fix-android-wrapper.sh` if downloads fail. Proxy config in `~/.gradle/gradle.properties` (JDK 17, `systemProp.https.protocols=TLSv1.2,TLSv1.3`).

### Error Redaction (Critical)
- Recursively replace values for keys `api_key`, `apiKey`, `authorization`, `Authorization`, `x-api-key`, `X-Api-Key` with `***` in JSON before storage or logging.
- `Bearer <token>` text masked in log output.

### File Structure Conventions
- **`src/app.tsx`**: Single-page shell; no routing; state held in `useState`; components receive data via callback props (e.g., `onProvidersUpdated`).
- **`src/api.ts`**: All IPC wrappers via `invoke()`; command list (20 commands) must match `lib.rs::invoke_handler!` definitions.
- **`src-tauri/src/lib.rs`**: `run()` injects `Arc<Database>`, `Arc<ProviderManager>`, `Arc<AppLogger>`, `Arc<Poller>` via `app.manage()`.
- **`src-tauri/src/catalog.rs`**: Hardcoded `ProviderPreset` templates (currently: MiniMax domestic `minimax_coding`, MiniMax overseas `minimax_token`, DeepSeek).
- **`src-tauri/src/providers.rs`**: `ProviderConfig` derives `#[serde(rename_all = "camelCase")]`; `fetch_usage` is single HTTP entry (timeout 15s).
- **Themes**: `data-theme` attribute + CSS variables in `src/index.css`. Three themes: `cyberpunk / wuxia / guoman`. Add new themes by: (1) adding CSS variables to `index.css`, (2) adding config to `src/themes/ThemeManager.ts`, (3) adding background decoration to `ThemedBackground.tsx`.
- **i18n**: `react-i18next` loads `src/i18n/locales` (zh/en dual language). Default `zh`; preference stored in `localStorage`.
- **Android widget**: Code in `src-tauri/gen/android/`. `WidgetDataReader.kt` reads `/data/data/com.aimonitor.app/ai-model-monitor/data.db` directly (requires WAL + `busy_timeout=5000`). Widget click → `MainActivity.kt` → `window.__NIUMA_SELECT_PROVIDER__('...')` → selects provider tab in frontend.

### Conditional Compilation
- `#[cfg(desktop)]` / `#[cfg(not(desktop))]` splits desktop vs mobile.
- `#[cfg(mobile)]` / `#[cfg(desktop)]` for Tauri mobile entry point.
- `tauri-plugin-global-shortcut` is unconditional dependency in `Cargo.toml`; mobile builds include it but do not call it.
- New desktop-only abilities follow the same `#[cfg(desktop)]` pattern.

### Provider Three Identities (Must Stay Consistent)
When adding/modifying a provider, ensure all three are aligned:
1. **Preset catalog** (`src-tauri/src/catalog.rs`): `ProviderPreset` template list.
2. **Runtime config** (`src-tauri/src/providers.rs` + `src/api.ts`): `ProviderConfig` instance via `enable_preset` command → SQLite `providers` table.
3. **Usage status** (`UsageStatus`): Polling-generated; contains `balance/balance_used/balance_limit`, `currency`, 3D quota `quota_*_ {total,used,remaining,remaining_percent}`, reset times `quota_*_reset_at`.

### Provider Type Dispatch
Only 5 types have custom parse branches:
- `deepseek` → `parse_deepseek_balance` (reads `balance_infos[0]`)
- `minimax_coding/minimax_token` → `parse_minimax_remains` (reads `body.model_remains[0]`, supports both domestic `current_interval_*` and overseas `interval_*/weekly_*` field names)
- `volcengine_coding/volcengine_token` → `parse_coding_plan_headers` (reads `X-RateLimit-*{-5H,Week,Month}` response headers)
- All others → `parse_generic_json` fallback (looks for `balance/used/limit/credit/hard_limit_usd` etc.)

### IPC Commands (must match `lib.rs::invoke_handler!` and `src/api.ts::api`)
- Provider CRUD: `get_providers / add_provider / update_provider / delete_provider / toggle_provider`
- Status/history: `fetch_provider_status` (manual pull, bypasses poller), `get_provider_status` (memory cache), `get_usage_history`
- Catalog: `get_provider_catalog / enable_preset / get_active_preset_ids` (returns provider type strings, not preset IDs)
- Config: `export_config` (JSON with plaintext api_key), `import_config`
- Close behavior: `get/set/reset_close_action`, `window_hide_to_tray`, `app_quit`
- Logs: `query_logs / clear_logs`

### Events
- `status-update`: `ProviderCard.tsx` filters by `provider_id` to update cards in real time; `HistoryChart` still polls `get_usage_history`.
- `close-requested`: Intercept close, emit; frontend shows `CloseConfirmDialog`; preference stored in `settings` table.
- `app-log`: Each new log pushed to `LogViewer`.

### Logging System (logging.rs)
- **No file writes**: stored in SQLite `app_logs` table + 200-condition in-memory ring buffer.
- `AppLogger::log` is non-blocking: sends via mpsc channel → background task writes to DB, pushes buffer, emits `app-log`.
- Levels: `debug/info/warn/error`; categories: `http/provider/poller/database/command/setup/tray/system`.
- `LogQuery` (camelCase) supports `keyword/levels/categories/since/until/limit` (default 200, clamp 1–1000).
- **Redaction**: recursively replaces `api_key/authorization/x-api-key` values with `***`; `Bearer <token>` masked in text.
- Retention: 7 days (`cleanup_old_logs(7)` at startup).
- Frontend `LogViewer.tsx` supports search/filter/realtime tail.

### Tray (desktop-only, `#[cfg(desktop)]`)
- Menu: "Show Main Window", "Exit".
- Left-click icon also shows window.
- Background task cycles enabled provider tooltip every 3s: name, balance, 5h/week remaining %, monthly usage, relative reset time (`formatRelativeReset`). Error shows `⚠`; empty state shows "No enabled providers".

### Android Widget (home-screen AppWidget)
- Code in `src-tauri/gen/android/`.
- `BaseUsageWidgetProvider` + Small/Medium/Large variants (3 separate receivers in manifest).
- `WidgetDataReader.kt` reads `/data/data/com.aimonitor.app/ai-model-monitor/data.db` directly (requires WAL + `busy_timeout=5000` for cross-process concurrent reads).
- Widget row click carries `provider_id` deep link → `MainActivity.kt` → `window.__NIUMA_SELECT_PROVIDER__('...')` → frontend selects provider tab in `src/App.tsx`.
- Resource strings: `widget_strings.xml` (dual-language), `widget_colors.xml` (day/night).
- MainActivity forces software rendering of WebView for MuMu emulator compatibility.

---

## When in Doubt
1. **First**: Check `docs/` (android-build.md, desktop-release.md) for environment/process guidance.
2. **Second**: Look at existing code in the relevant module (`src/`, `src-tauri/`) for patterns and conventions.
3. **Third**: Match the existing style — same import order, same `rename_all` derive, same conditional compilation guards.
4. **Fourth**: Run the appropriate build/lint command to verify changes compile and pass checks.