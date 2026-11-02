#!/usr/bin/env python3
"""
构建后校验 APK 内的 native 库，防止启动闪退回归。

典型故障：`dlopen failed: library "libjscexecutor.so" not found`
  - RN 0.76 + hermesEnabled=false（JSC 旧架构）会加载 libjscexecutor.so，
    但该库已不在 react-android AAR 中 → 必然崩溃
  - 另外 .so 若被 deflate 压缩存储，系统/SoLoader 无法从 APK 内直接 dlopen

本脚本先打印完整 .so 清单，再断言，CI 日志里可直接定位。
用法：python3 scripts/check-apk-libs.py <apk路径>
"""
import sys
import zipfile


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

    names = {n.rsplit("/", 1)[-1] for n in sos}
    problems = []

    # 1) 所有 .so 必须未压缩，才能从 base.apk!/lib/<abi>/ 直接加载
    for n in sos:
        if z.getinfo(n).compress_type != zipfile.ZIP_STORED:
            problems.append(f"{n} 是压缩存储，无法直接 dlopen")

    # 2) 必须存在 JS 引擎运行时
    if "libhermes.so" in names:
        print("JS 引擎: Hermes（RN 0.76 官方路径）")
    elif "libjsc.so" in names:
        print("JS 引擎: JSC（旧架构）")
        if "libjscexecutor.so" not in names:
            problems.append(
                "JSC 旧架构需要 libjscexecutor.so，但 APK 内不存在 → 启动必崩；"
                "请把 gradle.properties 的 hermesEnabled 改为 true"
            )
    else:
        problems.append("未找到任何 JS 引擎库（libhermes.so / libjsc.so）")

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
