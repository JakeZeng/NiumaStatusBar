#!/bin/bash
# Windows 下的 Android 开发环境（Git Bash 用 . 或 source 加载）
# 对应 Linux 的 env-android.sh

export JAVA_HOME="D:/java/OpenJDK21U-jdk_x64_windows_hotspot_21.0.3_9/jdk-21.0.3+9"
export ANDROID_HOME="D:/Android/SDK"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export NDK_HOME="$ANDROID_HOME/ndk/27.0.12077973"

NDK_BIN="$(cygpath -u "$NDK_HOME/toolchains/llvm/prebuilt/windows-x86_64/bin")"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/emulator:$NDK_BIN:$PATH"

# cc-rs / ring 需要的 Android 工具链（API 24）——Windows 下必须用 .cmd 包装
export CC_aarch64_linux_android="$NDK_BIN/aarch64-linux-android24-clang.cmd"
export CXX_aarch64_linux_android="$NDK_BIN/aarch64-linux-android24-clang++.cmd"
export AR_aarch64_linux_android="$NDK_BIN/llvm-ar"

export CC_armv7a_linux_androideabi="$NDK_BIN/armv7a-linux-androideabi24-clang.cmd"
export CXX_armv7a_linux_androideabi="$NDK_BIN/armv7a-linux-androideabi24-clang++.cmd"
export AR_armv7a_linux_androideabi="$NDK_BIN/llvm-ar"

export CC_i686_linux_android="$NDK_BIN/i686-linux-android24-clang.cmd"
export CXX_i686_linux_android="$NDK_BIN/i686-linux-android24-clang++.cmd"
export AR_i686_linux_android="$NDK_BIN/llvm-ar"

export CC_x86_64_linux_android="$NDK_BIN/x86_64-linux-android24-clang.cmd"
export CXX_x86_64_linux_android="$NDK_BIN/x86_64-linux-android24-clang++.cmd"
export AR_x86_64_linux_android="$NDK_BIN/llvm-ar"

# 链接器
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$NDK_BIN/aarch64-linux-android24-clang.cmd"
export CARGO_TARGET_ARMV7_LINUX_ANDROIDEABI_LINKER="$NDK_BIN/armv7a-linux-androideabi24-clang.cmd"
export CARGO_TARGET_I686_LINUX_ANDROID_LINKER="$NDK_BIN/i686-linux-android24-clang.cmd"
export CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER="$NDK_BIN/x86_64-linux-android24-clang.cmd"

echo "Android env loaded (Windows)"
echo "  ANDROID_HOME = $ANDROID_HOME"
echo "  NDK_HOME     = $NDK_HOME"
echo "  Java         = $(java -version 2>&1 | head -1)"