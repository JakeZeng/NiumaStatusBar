# 用法：PowerShell 里 . .\desktop-dev.ps1
# 或直接右键"用 PowerShell 运行"
#
# 桌面端开发模式启动器，对应 android-dev.ps1。
# 先加载 env，再 pnpm tauri dev（含 Vite HMR + Rust 热重载）。

$ErrorActionPreference = 'Stop'

# dot-source 同一目录的 env 脚本（如果失败给出明确报错）。
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
. (Join-Path $ScriptDir 'env-desktop-windows.ps1')

Set-Location $ScriptRoot
Write-Host 'Running: pnpm tauri dev' -ForegroundColor Cyan
pnpm tauri dev
