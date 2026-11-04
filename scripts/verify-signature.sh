#!/usr/bin/env bash
# 校验 APK 签名证书与注入的环境密钥一致（不硬编码任何指纹）
set -euo pipefail
APK="${1:-dist/short-video-parser.apk}"
KS="${SIGNING_KEYSTORE:-}"
SDK="${ANDROID_HOME:-/usr/local/lib/android/sdk}"

if [ -z "$KS" ] || [ ! -f "$KS" ] || [ -z "${SIGNING_STORE_PASSWORD:-}" ]; then
  echo "未注入 SIGNING_* 密钥，跳过指纹比对（本地 debug.keystore 回退）"
  exit 0
fi

APKSIGNER="$(find "$SDK/build-tools" -name apksigner -type f 2>/dev/null | head -1)"
if [ -z "$APKSIGNER" ]; then
  echo "apksigner 未找到，跳过指纹比对"
  exit 0
fi

"$APKSIGNER" verify --print-certs "$APK" | head -8

EXPECTED="$(keytool -list -v -keystore "$KS" -storetype JKS \
  -storepass "$SIGNING_STORE_PASSWORD" -alias "$SIGNING_KEY_ALIAS" 2>/dev/null \
  | grep -m1 'SHA256:' | sed 's/.*SHA256: *//' | tr -d ': ' | tr 'a-f' 'A-F')"
ACTUAL="$("$APKSIGNER" verify --print-certs "$APK" \
  | grep -m1 'SHA-256 digest:' | sed 's/.*digest: *//' | tr -d ': ' | tr 'a-f' 'A-F')"

if [ -n "$EXPECTED" ] && [ -n "$ACTUAL" ] && [ "$EXPECTED" != "$ACTUAL" ]; then
  echo "!! APK 签名证书与环境密钥不一致: keystore=$EXPECTED apk=$ACTUAL"
  exit 1
fi
echo "签名证书校验通过: $ACTUAL"
