@echo off
REM 桌面端发布构建脚本（cmd.exe 版）。
REM 用法：
REM   desktop-release.bat
REM   desktop-release.bat 0.1.56
REM   desktop-release.bat 0.1.56 msi
REM
REM 与 desktop-release.ps1 行为对齐；只在 PowerShell 不可用的 cmd 环境用。
REM 复杂功能（--SkipLint 等）请用 PowerShell 版。

setlocal EnableDelayedExpansion
set "SCRIPT_DIR=%~dp0"

REM 把参数透传给 powershell 版。
set "PS_ARGS=-ExecutionPolicy Bypass -File %SCRIPT_DIR%desktop-release.ps1"
:parse_args
if "%~1"=="" goto :done_parse
set "PS_ARGS=%PS_ARGS% %1"
shift
goto :parse_args
:done_parse

powershell %PS_ARGS%
exit /b %ERRORLEVEL%
