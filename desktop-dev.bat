@echo off
REM 桌面端开发模式启动器（cmd.exe 版），对应 android-dev.bat。
REM 双击运行即可。

setlocal
set "SCRIPT_DIR=%~dp0"
call "%SCRIPT_DIR%env-desktop-windows.bat"

echo Running: pnpm tauri dev
cd /d "%SCRIPT_DIR%"
pnpm tauri dev
pause
