# 桌面端发布环境指南（Windows）

本文档说明本地构建并发布桌面端（Windows MSI / NSIS）的环境配置与流程。Linux / macOS 由 GitHub Actions 在 `ubuntu-22.04` / `macos-latest` runner 上跨平台构建，本地不重复造轮子。

## 1. 一次性环境准备

桌面端构建需要四样东西（与 CI `release.yml` 在 windows-latest 上的依赖一致）：

| 组件 | 版本 | 安装方式 |
|------|------|----------|
| Rust | 1.85+（实测 1.98.0 stable） | rustup-init（见下） |
| Node.js | 20+（实测 22.12.0） | [nodejs.org](https://nodejs.org/) 或 nvm-windows |
| pnpm | 10+（实测 9.15.0 兼容 lockfile v9） | `npm i -g pnpm` 或 corepack |
| WebView2 Runtime | Win11 自带，Win10 早期需手装 | [WebView2 Runtime](https://developer.microsoft.com/microsoft-edge/webview2/) |

### 1.1 Rust

如果 `cargo --version` 在新 shell 里直接可用就跳过本节。如果报 `'cargo' is not recognized`，通常是 `~/.cargo/bin/` 那一层 proxy shim 丢了——这是手动解压 rustup-init 或 rustup 自更新失败后的常见症状。两种修法任选：

```powershell
# 方式 A（推荐）：重新跑 rustup-init，会自动重建 ~/.cargo/bin/ 的 shim
iwr -useb https://win.rustup.rs/x86_64 -OutFile rustup-init.exe
.\rustup-init.exe -y --default-toolchain stable-x86_64-pc-windows-msvc
# 删除下载文件
del rustup-init.exe
```

```powershell
# 方式 B：让 rustup 自更新（要求现存的 rustup.exe 还能跑到）
& "$env:USERPROFILE\.rustup\toolchains\stable-x86_64-pc-windows-msvc\bin\rustup.exe" self update
```

`cargo --version` 在任意新 shell 里能跑通后，环境就齐了。

### 1.2 Tauri bundlers（首次构建自动下载）

Tauri 2.x 会在首次 `pnpm tauri build` 时自动从 GitHub 下载：

- **WiX Toolset 3.14** 到 `%LOCALAPPDATA%\tauri\`（用于 MSI）
- **NSIS 3.x** 到同一目录（用于 Setup.exe）

不要把系统装的 WiX 7.x（默认路径 `C:\Program Files\WiX Toolset v7.0\`）放进 PATH——它用的 CLI 是 `wix.exe` / `wixnative.exe`，跟 Tauri 生成的 WiX 3.x `.wxs` 不兼容，会让 MSI bundling 失败。`env-desktop-windows.ps1` 已经避开这个坑。

## 2. 一键加载环境

```powershell
# 项目根目录下
. .\env-desktop-windows.ps1
```

预期输出：
```
Desktop env loaded
  Rust     = rustc 1.98.0 (...)
  Cargo    = cargo 1.98.0 (...)
  Node     = v22.12.0
  pnpm     = 9.15.0
  WebView2 = 1xx.x.xxx.xx
```

> PowerShell 5.1 / 7.x 都行。**每次新 shell 都要重新 dot-source 一次**——脚本只改当前 session 的 `$env:PATH`，不写注册表。

`env-desktop-windows.bat` 是 cmd.exe 版，用法：

```cmd
.\env-desktop-windows.bat
```

## 3. 开发模式

```powershell
. .\env-desktop-windows.ps1
pnpm tauri dev
```

或一步到位：

```powershell
.\desktop-dev.ps1        # 自动加载 env 再 pnpm tauri dev
.\desktop-dev.bat        # cmd.exe 版
```

## 4. 发布构建

### 4.1 一键脚本

```powershell
. .\env-desktop-windows.ps1          # 加载 Rust / Node / pnpm
.\desktop-release.ps1                # 用 package.json 现有版本号构建
.\desktop-release.ps1 -Version 0.1.56      # 先 bump 再构建
.\desktop-release.ps1 -SkipLint            # 跳过 cargo fmt / clippy
.\desktop-release.ps1 -BundleFormat msi    # 只产 MSI（默认 msi+nsis）
```

cmd.exe 入口：

```cmd
desktop-release.bat 0.1.56
desktop-release.bat 0.1.56 msi
```

### 4.2 脚本做了什么

1. **加载 env** — 同上
2. **可选版本号同步** — `-Version X.Y.Z` 时按 `release.yml` 的 sed 逻辑写入 `package.json` / `src-tauri/tauri.conf.json`（仅顶层 `"version"` 字段）
3. **`pnpm install --frozen-lockfile`** — 严格按 lockfile 安装
4. **`cargo fmt --check` + `cargo clippy`** — `clippy` 失败仅记录到 `release/<ver>/clippy.log`，不阻塞发版；`fmt` 失败直接报错要求 `cargo fmt`
5. **`pnpm build`** — `tsc` 类型检查 + Vite 产出到 `dist/`
6. **`pnpm tauri build --bundles msi,nsis`** — Tauri 生成 MSI + NSIS 安装包
7. **收集产物** — 拷贝到 `release/<version>/`，写 `SHA256SUMS.txt`

### 4.3 产物位置

构建成功后产物在两处：

```
# Tauri 原生输出（不要手动改这里）
src-tauri\target\x86_64-pc-windows-msvc\release\bundle\msi\*.msi
src-tauri\target\x86_64-pc-windows-msvc\release\bundle\nsis\*-Setup.exe

# 脚本归档的副本（便于分发 / 上传对象存储）
release\<version>\
  NiumaStatusBar_0.1.56_x64_en-US.msi
  NiumaStatusBar_0.1.56_x64-setup.exe
  SHA256SUMS.txt
  clippy.log           # 若有
```

### 4.4 不在脚本里的事

| 事情 | 为什么不做 |
|------|-----------|
| **代码签名** | Tauri 2.x Windows 签名需要 EV 证书 + signtool.exe，仓库没配。测试内部分发可以先不签；公开 Release 由 CI 的 tauri-action 处理（用 repo 的 `secrets.TAURI_SIGNING_PRIVATE_KEY`）。 |
| **跨平台**（macOS .dmg / Linux .deb） | 本机是 Windows，强行交叉编译到 macOS 需要 osxcross + Apple 证书链，CI 已经做了。Linux deb/AppImage 在 WSL 里跑更省心。 |
| **推到 GitHub Release** | `release.yml` 在 `v*` tag push 时自动跑；本地 Windows 产物主要给内测用。要正式发版：`git tag v0.1.56 && git push origin v0.1.56` 触发 CI。 |

## 5. 故障排查

| 症状 | 原因 | 修法 |
|------|------|------|
| `'cargo' is not recognized` | `~/.cargo/bin/` proxy shim 丢失 | 见 §1.1 重新装 rustup-init |
| `linker 'link.exe' not found` | Rust MSVC toolchain 缺 MSVC link.exe | 装 VS Build Tools C++ workload，或换 gnu toolchain（`rustup default stable-x86_64-pc-windows-gnu`） |
| `WiX Toolset v3 candle.exe not found` | WiX v7 抢占了 PATH（只有 `wix.exe` / `wixnative.exe`） | 删 `WiX 7` 路径上的引用，让 Tauri 自动下 WiX 3 |
| `asset not found :index.html` | 用了 `gradlew assembleRelease` 直接构建（Android 坑） | 桌面端用 `pnpm tauri build`，不要绕开 |
| `tauri build` 在 `cargo build --release` 卡住 | 首次编译，cargo 在拉取+编译所有依赖 | 等；下一次有 swatinem/rust-cache 加速 |
| 安装后双击闪退 | WebView2 缺失 | 装 [WebView2 Runtime](https://developer.microsoft.com/microsoft-edge/webview2/) |
| `tauri-cli 2.x` 警告升级 | 项目锁的 tauri 版本（见 `src-tauri/Cargo.toml`） | 按 release notes 决定要不要升 |

## 6. 文件清单

桌面端发布相关的新增 / 修改文件：

- `env-desktop-windows.ps1` / `.bat` — 加载 Rust / Node / pnpm 到当前 session
- `desktop-dev.ps1` / `.bat` — 开发模式启动器（对应 android-dev）
- `desktop-release.ps1` / `.bat` — 发布构建编排（版本号、lint、bundle、归档、SHA256）
- `docs/desktop-release.md` — 本文档

CI 端 `.github/workflows/release.yml` 的 windows-latest job 负责在 tag push 时跨平台构建并发布 draft GitHub Release，本地脚本的产物可作为 tag 触发前的本地 smoke test。
