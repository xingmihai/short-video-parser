#!/usr/bin/env python3
"""
构建后校验 APK 内的 native 库。

启动闪退 `dlopen failed: library "libjscexecutor.so" not found` 的根因通常有两种：
  1) .so 根本没打进 APK
  2) .so 被 deflate 压缩存储，系统/SoLoader 无法从 APK 内直接 dlopen

本脚本先打印完整 .so 清单，再断言，方便 CI 日志里直接定位。
用法：python3 scripts/check-apk-libs.py <apk路径>
"""
import sys
import zipfile

REQUIRED = ("libjscexecutor.so", "libjsc.so")


def main(apk_path):
    z = zipfile.ZipFile(apk_path)
    sos = [n for n in z.namelist() if n.startswith("lib/") and n.endswith(".so")]

    print(f"APK: {apk_path}")
    print(f"APK 内 .so 总数: {len(sos)}")
    abis = sorted({n.split("/")[1] for n in sos if len(n.split("/")) > 2})
    print("ABI:", abis)
    print("---- .so 清单 ----")
    for n in sorted(sos):
        i = z.getinfo(n)
        method = "Stored" if i.compress_type == zipfile.ZIP_STORED else f"DEFLATE({i.compress_type})"
        print(f"  {method:14} {i.file_size:>12,} B  {n}")

    problems = []
    for key in REQUIRED:
        hits = [n for n in sos if n.endswith("/" + key)]
        if not hits:
            problems.append(f"{key} 未打进 APK")
            continue
        for h in hits:
            if z.getinfo(h).compress_type != zipfile.ZIP_STORED:
                problems.append(f"{h} 是压缩存储，无法直接 dlopen")
            else:
                print(f"  OK {h}")

    if "arm64-v8a" not in abis:
        problems.append("缺少 arm64-v8a ABI")

    if problems:
        print("---- 校验失败 ----")
        for p in problems:
            print("  ❌", p)
        return 1
    print("---- 校验通过 ----")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else "dist/sv-proxy-android.apk"))
