#!/usr/bin/env bash
# 生成 index.android.bundle —— Hermes 字节码（HBC）
#
# 用法：
#   ./scripts/build-bundle.sh
#
# 明文 JS 只作为 hermesc 的输入落在临时目录，编译完立即删除；
# 产物 android/app/src/main/assets/index.android.bundle 是二进制字节码，
# 不可直接文本编辑。要改逻辑请改 src/ 下的源码后重新运行本脚本。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ASSETS="$ROOT/android/app/src/main/assets"
TMP="$ROOT/.bundle-tmp"

mkdir -p "$ASSETS" "$TMP"

echo "==> 打包 JS → 临时明文（minify）"
npx react-native bundle \
  --platform android \
  --dev false \
  --minify true \
  --entry-file index.js \
  --bundle-output "$TMP/plain.js.bundle" \
  --assets-dest "$ROOT/android/app/src/main/res" \
  --sourcemap-output "$ASSETS/index.android.bundle.map"

HERMESC=$(find "$ROOT/node_modules/react-native/sdks/hermesc" -type f -name hermesc 2>/dev/null | head -1)
if [ -z "$HERMESC" ]; then
  HERMESC=$(command -v hermesc || true)
fi

if [ -z "$HERMESC" ]; then
  echo "❌ 未找到 hermesc，无法生成字节码；已删除临时明文，未产出 bundle" >&2
  rm -rf "$TMP"
  exit 1
fi

echo "==> Hermes 编译 → index.android.bundle（HBC）"
"$HERMESC" -emit-binary -O \
  -out "$ASSETS/index.android.bundle" "$TMP/plain.js.bundle"

rm -rf "$TMP"
echo "==> 临时明文已删除"
echo "==> 完成：$ASSETS/index.android.bundle"
file "$ASSETS/index.android.bundle" || true
ls -lh "$ASSETS/index.android.bundle"
