#!/bin/bash
# ============================================================
# 修复 Android Gradle Wrapper 使用本地 Gradle 8.14.4
# 解决 wrapper 下载 8.14.3-bin.zip 超时的问题
# ============================================================

set -e

WORKSPACE_DIR="${1:-/workspace}"
WRAPPER_PROPS="$WORKSPACE_DIR/src-tauri/gen/android/gradle/wrapper/gradle-wrapper.properties"
LOCAL_GRADLE_ZIP="/tmp/gradle-8.14.4-bin.zip"
LOCAL_GRADLE_SRC="/root/.local/share/mise/installs/gradle/8.14.4/gradle-8.14.4"

echo "🔧 修复 Android Gradle Wrapper（使用本地 Gradle 8.14.4）"

# 1. 准备本地 zip（如不存在）
if [ ! -f "$LOCAL_GRADLE_ZIP" ]; then
    if [ ! -d "$LOCAL_GRADLE_SRC" ]; then
        echo "❌ 本地 Gradle 8.14.4 不存在: $LOCAL_GRADLE_SRC"
        exit 1
    fi
    echo "   打包本地 Gradle 为 zip..."
    (cd "$(dirname "$LOCAL_GRADLE_SRC")" && zip -qr "$LOCAL_GRADLE_ZIP" "$(basename "$LOCAL_GRADLE_SRC")/")
fi

# 2. 清理不完整的下载
rm -rf /root/.gradle/wrapper/dists/gradle-8.14.3-bin/

# 3. 修改 wrapper distributionUrl
if [ -f "$WRAPPER_PROPS" ]; then
    sed -i "s|^distributionUrl=.*|distributionUrl=file:$LOCAL_GRADLE_ZIP|" "$WRAPPER_PROPS"
    echo "   ✅ 已修改 wrapper 指向本地 zip"
    cat "$WRAPPER_PROPS"
else
    echo "⚠️  未找到 $WRAPPER_PROPS，请先执行 pnpm tauri android init"
fi

echo ""
echo "✅ Wrapper 修复完成"
