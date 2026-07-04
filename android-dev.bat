@echo off
REM 双击运行这个 .bat 启动 Android dev 模式
set "NDK_HOME=D:\android_repos\android\sdk\ndk\27.0.12077973"
set "ANDROID_HOME=D:\android_repos\android\sdk"
set "JAVA_HOME=D:\Program Files\Eclipse Adoptium\jdk-21.0.8.9-hotspot"

set "NDK_BIN=%NDK_HOME%\toolchains\llvm\prebuilt\windows-x86_64\bin"
set "PATH=%JAVA_HOME%\bin;%ANDROID_HOME%\platform-tools;%NDK_BIN%;%PATH%"

set "CC_aarch64_linux_android=%NDK_BIN%\aarch64-linux-android24-clang.cmd"
set "CXX_aarch64_linux_android=%NDK_BIN%\aarch64-linux-android24-clang++.cmd"
set "AR_aarch64_linux_android=%NDK_BIN%\llvm-ar"
set "CC_armv7a_linux_androideabi=%NDK_BIN%\armv7a-linux-androideabi24-clang.cmd"
set "CXX_armv7a_linux_androideabi=%NDK_BIN%\armv7a-linux-androideabi24-clang++.cmd"
set "AR_armv7a_linux_androideabi=%NDK_BIN%\llvm-ar"
set "CC_i686_linux_android=%NDK_BIN%\i686-linux-android24-clang.cmd"
set "CXX_i686_linux_android=%NDK_BIN%\i686-linux-android24-clang++.cmd"
set "AR_i686_linux_android=%NDK_BIN%\llvm-ar"
set "CC_x86_64_linux_android=%NDK_BIN%\x86_64-linux-android24-clang.cmd"
set "CXX_x86_64_linux_android=%NDK_BIN%\x86_64-linux-android24-clang++.cmd"
set "AR_x86_64_linux_android=%NDK_BIN%\llvm-ar"
set "CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER=%NDK_BIN%\aarch64-linux-android24-clang.cmd"
set "CARGO_TARGET_ARMV7_LINUX_ANDROIDEABI_LINKER=%NDK_BIN%\armv7a-linux-androideabi24-clang.cmd"
set "CARGO_TARGET_I686_LINUX_ANDROID_LINKER=%NDK_BIN%\i686-linux-android24-clang.cmd"
set "CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER=%NDK_BIN%\x86_64-linux-android24-clang.cmd"

cd /d "%~dp0"
echo NDK_HOME=%NDK_HOME%
echo Running: pnpm tauri android dev
pnpm tauri android dev
pause
