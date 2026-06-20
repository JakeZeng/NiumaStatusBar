#!/bin/bash
# ============================================================
# Tauri Android 开发环境配置
# 自动加载：source ./env-android.sh
# ============================================================

# JDK 17（Android Gradle Plugin 兼容版本）
export JAVA_HOME=/root/.local/share/mise/installs/java/17.0.2

# Android SDK 路径
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"

# NDK（与 Tauri 2.x 兼容版本：27.x）
export NDK_HOME="$ANDROID_HOME/ndk/27.0.12077973"
NDK_BIN="$NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"

# 平台工具与构建工具加入 PATH（NDK clang 工具链放在最前）
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/build-tools/34.0.0:$NDK_BIN:$NDK_HOME:$PATH"

# 为 ring / cc-rs 等 crate 提供编译器变量（无 API level 后缀的 clang 名）
export CC_aarch64_linux_android="$NDK_BIN/aarch64-linux-android24-clang"
export CXX_aarch64_linux_android="$NDK_BIN/aarch64-linux-android24-clang++"
export AR_aarch64_linux_android="$NDK_BIN/llvm-ar"

export CC_armv7a_linux_androideabi="$NDK_BIN/armv7a-linux-androideabi24-clang"
export CXX_armv7a_linux_androideabi="$NDK_BIN/armv7a-linux-androideabi24-clang++"
export AR_armv7a_linux_androideabi="$NDK_BIN/llvm-ar"

export CC_i686_linux_android="$NDK_BIN/i686-linux-android24-clang"
export CXX_i686_linux_android="$NDK_BIN/i686-linux-android24-clang++"
export AR_i686_linux_android="$NDK_BIN/llvm-ar"

export CC_x86_64_linux_android="$NDK_BIN/x86_64-linux-android24-clang"
export CXX_x86_64_linux_android="$NDK_BIN/x86_64-linux-android24-clang++"
export AR_x86_64_linux_android="$NDK_BIN/llvm-ar"

# 链接器
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$NDK_BIN/aarch64-linux-android24-clang"
export CARGO_TARGET_ARMV7_LINUX_ANDROIDEABI_LINKER="$NDK_BIN/armv7a-linux-androideabi24-clang"
export CARGO_TARGET_I686_LINUX_ANDROID_LINKER="$NDK_BIN/i686-linux-android24-clang"
export CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER="$NDK_BIN/x86_64-linux-android24-clang"

# 验证
echo "✅ Android 环境已加载"
echo "   JAVA_HOME   = $JAVA_HOME"
echo "   ANDROID_HOME = $ANDROID_HOME"
echo "   NDK_HOME     = $NDK_HOME"
echo ""
java -version 2>&1 | head -1
echo "Platform Tools: $(ls $ANDROID_HOME/platform-tools 2>/dev/null | head -1)"
echo "Build Tools:    $(ls $ANDROID_HOME/build-tools 2>/dev/null | head -1)"
echo "NDK:            $(ls $ANDROID_HOME/ndk 2>/dev/null)"
