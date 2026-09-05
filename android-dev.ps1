# 用法：在 PowerShell 里 . .\android-dev.ps1 （注意前面的点和空格）
# 或直接右键"用 PowerShell 运行"

$env:NDK_HOME = "D:\Android\SDK\ndk\27.0.12077973"
$env:ANDROID_HOME = "D:\Android\SDK"
$env:JAVA_HOME = "D:\java\OpenJDK21U-jdk_x64_windows_hotspot_21.0.3_9\jdk-21.0.3+9"

$NDK_BIN = "$env:NDK_HOME\toolchains\llvm\prebuilt\windows-x86_64\bin"
$env:PATH = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$NDK_BIN;$env:PATH"

$env:CC_aarch64_linux_android        = "$NDK_BIN\aarch64-linux-android24-clang.cmd"
$env:CXX_aarch64_linux_android       = "$NDK_BIN\aarch64-linux-android24-clang++.cmd"
$env:AR_aarch64_linux_android        = "$NDK_BIN\llvm-ar"
$env:CC_armv7a_linux_androideabi     = "$NDK_BIN\armv7a-linux-androideabi24-clang.cmd"
$env:CXX_armv7a_linux_androideabi    = "$NDK_BIN\armv7a-linux-androideabi24-clang++.cmd"
$env:AR_armv7a_linux_androideabi     = "$NDK_BIN\llvm-ar"
$env:CC_i686_linux_android           = "$NDK_BIN\i686-linux-android24-clang.cmd"
$env:CXX_i686_linux_android          = "$NDK_BIN\i686-linux-android24-clang++.cmd"
$env:AR_i686_linux_android           = "$NDK_BIN\llvm-ar"
$env:CC_x86_64_linux_android         = "$NDK_BIN\x86_64-linux-android24-clang.cmd"
$env:CXX_x86_64_linux_android        = "$NDK_BIN\x86_64-linux-android24-clang++.cmd"
$env:AR_x86_64_linux_android         = "$NDK_BIN\llvm-ar"
$env:CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER      = "$NDK_BIN\aarch64-linux-android24-clang.cmd"
$env:CARGO_TARGET_ARMV7_LINUX_ANDROIDEABI_LINKER    = "$NDK_BIN\armv7a-linux-androideabi24-clang.cmd"
$env:CARGO_TARGET_I686_LINUX_ANDROID_LINKER         = "$NDK_BIN\i686-linux-android24-clang.cmd"
$env:CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER       = "$NDK_BIN\x86_64-linux-android24-clang.cmd"

Set-Location $PSScriptRoot
Write-Host "NDK_HOME = $env:NDK_HOME" -ForegroundColor Green
Write-Host "Running: pnpm tauri android dev" -ForegroundColor Cyan
pnpm tauri android dev
