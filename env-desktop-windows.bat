@echo off
REM 用法：cmd.exe 里 .\env-desktop-windows.bat
REM 或在 PowerShell 里 & cmd /c ".\env-desktop-windows.bat && <cmd>"
REM
REM 桌面端发布 / 开发环境加载脚本（Windows cmd.exe 版）。
REM 与 env-desktop-windows.ps1 行为对齐。

setlocal

set "TOOLCHAIN_BIN=%USERPROFILE%\.rustup\toolchains\stable-x86_64-pc-windows-msvc\bin"
if not exist "%TOOLCHAIN_BIN%" (
    echo FATAL: Rust toolchain bin not found at %TOOLCHAIN_BIN%
    echo Run rustup-init to install Rust first.
    exit /b 1
)

if exist "D:\w64devkit\bin" set "PATH=D:\w64devkit\bin;%PATH%"
if exist "D:\nodejs" set "PATH=D:\nodejs;%PATH%"

set "PATH=%TOOLCHAIN_BIN%;%PATH%"
set "CARGO_HOME=%USERPROFILE%\.cargo"
set "RUSTUP_HOME=%USERPROFILE%\.rustup"

echo Desktop env loaded
rustc --version
cargo --version
node --version 2>nul
pnpm --version 2>nul

REM Tauri bundlers 走自动下载，不要把系统装的 WiX 7.x 加进 PATH。
endlocal & set "PATH=%PATH%" & set "CARGO_HOME=%CARGO_HOME%" & set "RUSTUP_HOME=%RUSTUP_HOME%"
