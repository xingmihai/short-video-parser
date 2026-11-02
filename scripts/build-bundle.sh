#!/usr/bin/env bash
# 生成 index.android.bundle
#
# 用法：
#   ./scripts/build-bundle.sh            # Hermes 字节码版（默认，与 gradle 打包一致）
#   ./scripts/build-bundle.sh plain      # 明文 JS 版（可直接文本编辑 / 反查）
#
# 产物放在 android/app/src/main/assets/，Gradle 打包时自动合入 APK。
# 明文 bundle 在 Hermes 下会以解释模式执行，同样可用（无需改 hermesEnabled）。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ASSETS="$ROOT/android/app/src/main/assets"
MODE="${1:-hermes}"

mkdir -p "$ASSETS"

echo "==> 打包 JS → index.android.bundle"
npx react-native bundle \
  --platform android \
  --dev false \
  --minify true \
  --entry-file index.js \
  --bundle-output "$ASSETS/index.android.bundle" \
  --assets-dest "$ROOT/android/app/src/main/res" \
  --sourcemap-output "$ASSETS/index.android.bundle.map"

if [ "$MODE" = "plain" ]; then
  echo "==> 明文 JS 模式，跳过 Hermes 编译（可文本编辑/替换）"
else
  HERMES=$(find "$ROOT/node_modules/react-native/sdks/hermesc" -type f -name hermesc 2>/dev/null | head -1)
  if [ -z "$HERMES" ]; then
    HERMES=$(command -v hermesc || true)
  fi
  if [ -n "$HERMES" ]; then
    echo "==> Hermes 编译：index.android.bundle → index.android.bundle.hbc"
    "$HERMES" -emit-binary -out "$ASSETS/index.android.bundle.hbc" "$ASSETS/index.android.bundle" -O
    mv "$ASSETS/index.android.bundle.hbc" "$ASSETS/index.android.bundle"
    echo "==> 已替换为字节码 bundle（体积更小、启动更快，但不可文本编辑）"
  else
    echo "==> 未找到 hermesc，保留明文 bundle"
  fi
fi

echo "==> 完成：$ASSETS/index.android.bundle"
ls -lh "$ASSETS/index.android.bundle"
