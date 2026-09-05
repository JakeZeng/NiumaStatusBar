# 用法：在 PowerShell 里 . .\env-desktop-windows.ps1 （注意前面的点和空格）
#
# 桌面端发布 / 开发环境加载脚本（Windows）。
# 对应 Linux/macOS 下的 ~/.cargo/env 或 rustup 注入到 PATH 的方式。
#
# 本仓库的 rustup 安装是放在 ~/.rustup/toolchains/<triple>/bin 的，
# 但 ~/.cargo/bin/ 那一层 proxy shim 目录没建好（常见于手动解压
# rustup-init 或 rustup 自更新失败后）。本脚本直接把 toolchain bin
# 加到 PATH，让 cargo / rustc / rustfmt / clippy 在当前 shell 里直接可用。
#
# 一键还原 proxy shim（可选，让任意新 shell 都能 cargo）：
#   iwr -useb https://win.rustup.rs/x86_64 | iex
#   rustup default stable-x86_64-pc-windows-msvc

$ErrorActionPreference = 'Stop'

# --- Rust toolchain ---------------------------------------------------------
# 已确认 stable-x86_64-pc-windows-msvc 装在 ~/.rustup/toolchains/。
$ToolchainBin = Join-Path $env:USERPROFILE '.rustup\toolchains\stable-x86_64-pc-windows-msvc\bin'
if (-not (Test-Path $ToolchainBin)) {
    Write-Host "FATAL: Rust toolchain bin not found at $ToolchainBin" -ForegroundColor Red
    Write-Host 'Run rustup-init to install Rust, or rerun the rustup default command.' -ForegroundColor Red
    throw "rust toolchain missing"
}

# --- w64devkit (mingw fallback for cargo on Windows) ------------------------
# 当前 toolchain 是 MSVC ABI，理论上需要 MSVC link.exe。但仓库 ~/.cargo/bin
# proxy 丢失期间，rustc 实测通过 w64devkit 的 GNU ld（被 MSVC Rust 工具链
# 当 link.exe 调到 PATH 上）也能链出可运行的 .exe。先把它放 PATH 兜底。
$W64Bin = 'D:\w64devkit\bin'
if (Test-Path $W64Bin) { $env:PATH = "$W64Bin;$env:PATH" }

# --- Node / pnpm (前端构建依赖) -------------------------------------------
# D:\nodejs 已带 node + pnpm。如果用户用的是 nvm-windows / fnm / volta，
# 自己改这里或先 nvm use 20 之类。本仓库 .nvmrc 没要求版本，README 要求 20+。
$NodeBin = 'D:\nodejs'
if (Test-Path $NodeBin) { $env:PATH = "$NodeBin;$env:PATH" }

# --- Rust toolchain bin 放到 PATH 最前 ------------------------------------
$env:PATH = "$ToolchainBin;$env:PATH"
$env:CARGO_HOME = Join-Path $env:USERPROFILE '.cargo'
$env:RUSTUP_HOME = Join-Path $env:USERPROFILE '.rustup'

# --- 验证 ------------------------------------------------------------------
Write-Host 'Desktop env loaded' -ForegroundColor Green
Write-Host "  Rust     = $(& rustc --version)" -ForegroundColor Cyan
Write-Host "  Cargo    = $(& cargo --version)" -ForegroundColor Cyan
Write-Host "  Node     = $(& node --version 2>$null)" -ForegroundColor Cyan
Write-Host "  pnpm     = $(& pnpm --version 2>$null)" -ForegroundColor Cyan
Write-Host "  WebView2 = $(Get-ItemProperty 'HKLM:\SOFTWARE\WOW6432Node\Microsoft\EdgeUpdate\Clients\{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}' -ErrorAction SilentlyContinue | Select-Object -ExpandProperty pv)" -ForegroundColor Cyan

# Tauri 2.x 自动下载 WiX 3.14 / NSIS 到 %LOCALAPPDATA%\tauri\。
# 不要把系统装的 WiX 7.x（默认装在 C:\Program Files\WiX Toolset v7.0\）放 PATH，
# 它用的 CLI 是 wix.exe / wixnative.exe，跟 Tauri 生成的 WiX 3.x .wxs
# 不兼容，会让 MSI bundling 失败。NSIS / WiX 3 都让 Tauri 自己下到缓存里。
Write-Host '  Tauri bundlers: auto-downloaded WiX 3.x + NSIS on first build' -ForegroundColor DarkGray
