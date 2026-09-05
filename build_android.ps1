# Android 构建环境（适配本机实际路径：NDK 在 D:\Android\SDK）
$env:JAVA_HOME = "D:\java\OpenJDK21U-jdk_x64_windows_hotspot_21.0.3_9\jdk-21.0.3+9"
$env:ANDROID_HOME = "D:\Android\SDK"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:NDK_HOME = "$env:ANDROID_HOME\ndk\27.0.12077973"
$env:GRADLE_USER_HOME = "E:\.gradle"

$NDK_BIN = "$env:NDK_HOME\toolchains\llvm\prebuilt\windows-x86_64\bin"
$env:Path = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:ANDROID_HOME\cmdline-tools\latest\bin;$env:ANDROID_HOME\emulator;$NDK_BIN;D:\.cargo\bin;$env:Path"

# cc-rs / ring 需要的 Android 工具链
$env:CC_aarch64_linux_android = "$NDK_BIN\aarch64-linux-android24-clang.cmd"
$env:CXX_aarch64_linux_android = "$NDK_BIN\aarch64-linux-android24-clang++.cmd"
$env:AR_aarch64_linux_android = "$NDK_BIN\llvm-ar"

$env:CC_armv7a_linux_androideabi = "$NDK_BIN\armv7a-linux-androideabi24-clang.cmd"
$env:CXX_armv7a_linux_androideabi = "$NDK_BIN\armv7a-linux-androideabi24-clang++.cmd"
$env:AR_armv7a_linux_androideabi = "$NDK_BIN\llvm-ar"

$env:CC_i686_linux_android = "$NDK_BIN\i686-linux-android24-clang.cmd"
$env:CXX_i686_linux_android = "$NDK_BIN\i686-linux-android24-clang++.cmd"
$env:AR_i686_linux_android = "$NDK_BIN\llvm-ar"

$env:CC_x86_64_linux_android = "$NDK_BIN\x86_64-linux-android24-clang.cmd"
$env:CXX_x86_64_linux_android = "$NDK_BIN\x86_64-linux-android24-clang++.cmd"
$env:AR_x86_64_linux_android = "$NDK_BIN\llvm-ar"

# 链接器
$env:CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER = "$NDK_BIN\aarch64-linux-android24-clang.cmd"
$env:CARGO_TARGET_ARMV7_LINUX_ANDROIDEABI_LINKER = "$NDK_BIN\armv7a-linux-androideabi24-clang.cmd"
$env:CARGO_TARGET_I686_LINUX_ANDROID_LINKER = "$NDK_BIN\i686-linux-android24-clang.cmd"
$env:CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER = "$NDK_BIN\x86_64-linux-android24-clang.cmd"

Write-Host "Android build env loaded (D:\Android\SDK)"
Write-Host "  ANDROID_HOME = $env:ANDROID_HOME"
Write-Host "  NDK_HOME     = $env:NDK_HOME"
Write-Host "  Java         = $(java -version 2>&1 | Select-Object -First 1)"
Write-Host "  Cargo        = $(cargo --version 2>&1)"
Write-Host "  adb devices  ="
& adb devices | Select-Object -First 5
