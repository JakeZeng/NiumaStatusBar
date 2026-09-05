---
name: desktop-release
description: 打包 NiumaStatusBar 桌面端（Windows MSI + NSIS 安装包）。当用户说"桌面端打包"/"打 release"/"桌面端发布"/"构建 MSI"/"打安装包"/"desktop release"/"build installer"时加载。仅修改版本号 + 触发构建脚本，不动业务代码。
---

# 桌面端发布构建

本仓库的 Windows 桌面端安装包（MSI + NSIS）一键打包流程。Linux / macOS 走 `.github/workflows/release.yml` 的 ubuntu/macos runner 跨平台构建，本 skill 不重复造轮子。

## 何时使用

用户消息命中下列意图之一时使用本 skill：

- 「打 release」「桌面端打包」「桌面端发布」「构建 MSI」「打安装包」「desktop release」「build installer」
- 「构建 v0.x.y 版本」「出 v0.x.y 安装包」
- 不修改业务代码、只产出一份新版本安装包

**不要**因为以下场景触发本 skill（那是别的活）：

- 修改 Rust / TS 源码 → 走普通代码修改流程
- 修 bug、加功能 → 同上
- Android APK 打包 → 见 `docs/android-build.md`
- macOS / Linux 桌面端打包 → CI 跨平台做，本地跳过

## 前置：加载环境（必须）

任何步骤开始前先在项目根目录 PowerShell 里 dot-source：

```powershell
. .\env-desktop-windows.ps1
```

预期输出：

```
Desktop env loaded
  Rust     = rustc 1.98.x
  Cargo    = cargo 1.98.x
  Node     = v2x.x.x
  pnpm     = 9.x 或 10.x
  WebView2 = xxx
```

任一项缺失（`is not recognized`）立即停下，提示用户按 `docs/desktop-release.md` §1.1 修 rustup shim 或装 WebView2。

## 前置：跟用户确认两件事

不要闷头构建，开始前向用户确认：

1. **目标版本号**（默认读 `package.json` 里的 version）
2. **bundle 类型**（默认 msi+nsis；可只 msi）

如果用户没指定版本号，先 `Get-Content package.json | Select-String '"version"'` 读出来回显，请确认。

## 执行：发布构建

```powershell
. .\env-desktop-windows.ps1
.\desktop-release.ps1 -Version <VERSION> [-SkipLint] [-BundleFormat msi] [-NoSha256]
```

参数说明：

- `-Version X.Y.Z`（必需，除非用户明确说"用现成版本号"）：bump `package.json` + `src-tauri/tauri.conf.json` 的顶层 `version` 字段后构建
- `-SkipLint`：跳过 `cargo fmt --check` + `cargo clippy`。紧急发版时用；正常发版不要加
- `-BundleFormat msi`：只产 MSI，跳过 NSIS
- `-NoSha256`：跳过 SHA256SUMS.txt 计算

脚本会按顺序做：

1. 把版本号同步到 `package.json` + `src-tauri/tauri.conf.json`（如果传了 `-Version`）
2. `pnpm install --frozen-lockfile`
3. `cargo fmt --all -- --check`（失败抛错，要求 `cargo fmt` 后重跑）
4. `cargo clippy --all-targets --no-deps -- -D warnings`（失败仅 WARN，日志到 `release/<ver>/clippy.log`）
5. `pnpm build`（tsc + vite）
6. `pnpm tauri build --bundles msi,nsis`
7. 拷贝产物到 `release/<version>/`，写 `SHA256SUMS.txt`

**首次构建 10–15 分钟**（cargo 拉 + 编译 100+ Tauri 依赖）。增量构建几秒到几分钟。

## 后置：验收产物

跑完打开 `release\<version>\` 确认四个产物都在：

```
release\<version>\
  NiumaStatusBar_<ver>_x64_en-US.msi         (~5 MB)
  NiumaStatusBar_<ver>_x64-setup.exe        (~4 MB)
  SHA256SUMS.txt
  clippy.log                                # 若 lint 跑过
```

任意文件缺失或大小异常，看脚本尾部输出或 `release\<version>\clippy.log` 末尾。

## 后置：询问下一步

不要自动 push tag 或 commit 任何东西。给用户如下提示并等待指令：

```
构建完成。产物在 release\<version>\。

要正式发版（推到 GitHub Release）请手动：
  git add -A
  git commit -m "release: v<VERSION>"
  git tag v<VERSION>
  git push origin v<VERSION> --follow-tags

CI (.github/workflows/release.yml) 会跨平台构建 macOS + Linux + Windows，
并发布 draft GitHub Release。本地 Windows 产物主要给内测分发用。
```

## 故障排查速查

| 症状 | 修法 |
|------|------|
| `'cargo' is not recognized` | `iwr -useb https://win.rustup.rs/x86_64 -OutFile rustup-init.exe; .\rustup-init.exe -y --default-toolchain stable-x86_64-pc-windows-msvc` |
| `cargo fmt --check failed` | `cd src-tauri && cargo fmt && cd ..`，再重跑脚本 |
| `WiX Toolset v3 candle.exe not found` | 删 PATH 里的 WiX 7.x，让 Tauri 自动下载 WiX 3 |
| `linker 'link.exe' not found` | 装 VS Build Tools C++ workload，或换 gnu toolchain |
| `pnpm tauri build` 在 `cargo build` 卡很久 | 首次编译正常；后续增量会快很多 |
| 安装包双击闪退 | 缺 WebView2 Runtime：https://developer.microsoft.com/microsoft-edge/webview2/ |
| `pnpm install` lockfile 不一致 | 不要加 `--no-frozen-lockfile`；先 `pnpm install` 在干净环境解冲突 |
| `[vite:css] Failed to load PostCSS config: Unexpected token ﻿{...}` | `package.json` 或 `tauri.conf.json` 顶层带 UTF-8 BOM。脚本里 `[pre] BOM self-check` 会自动剥；手工剥：`[System.IO.File]::WriteAllText('package.json', [System.IO.File]::ReadAllText('package.json'), (New-Object System.Text.UTF8Encoding $false))` |
| `node.exe : Generated an empty chunk: "react-vendor"` 误报失败 | PowerShell 5.1 把 native stderr 行当 ErrorRecord 抛。脚本已用 `try/finally` 切 `$ErrorActionPreference = 'Continue'` 绕过；这是无害的 rollup warning |

完整排查见 `docs/desktop-release.md` §5。

## 文件位置速查

- `env-desktop-windows.ps1` / `.bat` — 加载环境
- `desktop-release.ps1` / `.bat` — 发布编排（这就是你直接调用的入口）
- `docs/desktop-release.md` — 完整指南
- `.github/workflows/release.yml` — CI 跨平台构建

## skill 边界

- **本 skill 只调脚本**，不自己写 PowerShell 拼装 cargo / pnpm 命令。脚本里有什么 bug，修了脚本而不是绕过它。
- **不要修改源码**。如果用户夹带 bug 修复请求，先分开处理再回来跑发版。
- **不要 push tag**。版本号 bump 写在本地文件里，commit / tag / push 全都留给用户。
