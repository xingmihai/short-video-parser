#!/usr/bin/env python3
"""
构建后校验：APK 内 native 库 + RN 0.76 合并库映射配置。

启动闪退的根因链（都指向同一个点）：
  1) `dlopen failed: library "libjscexecutor.so" not found`
  2) `dlopen failed: library "libhermes_executor.so" not found`
RN 0.76 引入 Native Library Merging，这些"逻辑库名"不再对应独立 .so：
  OpenSourceMergedSoMapping.mapLibName:
    hermes_executor / hermesinstancejni / jsijniprofiler -> hermestooling
    jscexecutor / jscruntime / jscinstance            -> jsctooling
    reactnativejni / fabricjni / yoga ...            -> reactnative
若 MainApplication 仍用旧写法 `SoLoader.init(this, false)`，SoLoader 不会做映射，
会直接去磁盘 dlopen 独立库 → 必然找不到 → UnsatisfiedLinkError 闪退。

本脚本同时校验：
  A. 源码里必须传入 OpenSourceMergedSoMapping
  B. APK 内 .so 全部 Stored（未压缩），否则无法从 base.apk!/lib/<abi>/ 直接 dlopen
  C. 存在合并映射的目标物理库（tooling / reactnative）与 JS 引擎库
  D. 含 arm64-v8a

用法：python3 scripts/check-apk-libs.py [apk路径] [MainApplication.kt路径]
"""
import sys
import zipfile
from pathlib import Path

APK = sys.argv[1] if len(sys.argv) > 1 else "dist/sv-proxy-android.apk"
MAIN_APP = Path(
    sys.argv[2]
    if len(sys.argv) > 2
    else "android/app/src/main/java/com/svproxy/MainApplication.kt"
)

ENGINE_LIBS = ("libhermes.so", "libjsc.so")
TOOLING_LIBS = ("libhermestooling.so", "libjsctooling.so")


def check_source():
    print("---- A. SoLoader 合并库映射配置 ----")
    if not MAIN_APP.exists():
        return [f"找不到 {MAIN_APP}"]
    src = MAIN_APP.read_text(encoding="utf-8")
    print(f"文件: {MAIN_APP}")
    problems = []
    if "OpenSourceMergedSoMapping" not in src:
        problems.append(
            "MainApplication.kt 未传入 OpenSourceMergedSoMapping；"
            "RN 0.76 必须用 SoLoader.init(this, OpenSourceMergedSoMapping)"
        )
    # 只检查非注释行，避免被说明性注释误伤
    code_lines = [
        ln for ln in src.splitlines()
        if not ln.lstrip().startswith(("//", "*", "/*"))
    ]
    code = "\n".join(code_lines)
    if "SoLoader.init(this, false)" in code or "SoLoader.init(this, true)" in code:
        problems.append(
            "仍是 RN ≤0.75 的 boolean 重载写法，缺少合并库映射，启动必崩"
        )
    if not problems:
        print("  ✅ 使用 OpenSourceMergedSoMapping")
    return problems


def check_apk():
    z = zipfile.ZipFile(APK)
    sos = [n for n in z.namelist() if n.startswith("lib/") and n.endswith(".so")]
    names = {n.rsplit("/", 1)[-1] for n in sos}
    abis = sorted({n.split("/")[1] for n in sos if len(n.split("/")) > 2})

    print("\n---- B/C/D. APK native 库 ----")
    print(f"APK: {APK}")
    print(f".so 总数: {len(sos)}   ABI: {abis}")
    for n in sorted(sos):
        i = z.getinfo(n)
        method = "Stored" if i.compress_type == zipfile.ZIP_STORED else f"DEFLATE({i.compress_type})"
        print(f"  {method:14} {i.file_size:>12,} B  {n}")

    problems = []
    for n in sos:
        if z.getinfo(n).compress_type != zipfile.ZIP_STORED:
            problems.append(f"{n} 是压缩存储，无法直接 dlopen")

    engine = [x for x in ENGINE_LIBS if x in names]
    tooling = [x for x in TOOLING_LIBS if x in names]
    print(f"JS 引擎库: {engine or '无'}")
    print(f"合并映射目标 tooling 库: {tooling or '无'}")
    print(f"合并目标 libreactnative.so: {'有' if 'libreactnative.so' in names else '无'}")

    if not engine:
        problems.append("未找到任何 JS 引擎库（libhermes.so / libjsc.so）")
    if not tooling:
        problems.append(
            "未找到合并映射目标库（libhermestooling.so / libjsctooling.so）；"
            "SoLoader 映射后会去加载它，缺了依然闪退"
        )
    if "libreactnative.so" not in names:
        problems.append("未找到 libreactnative.so（另一组合并目标）")
    if "arm64-v8a" not in abis:
        problems.append("缺少 arm64-v8a ABI")
    return problems


def main():
    problems = check_source() + check_apk()
    if problems:
        print("\n---- 校验失败 ----")
        for p in problems:
            print("  ❌", p)
        return 1
    print("\n---- 校验通过 ----")
    return 0


if __name__ == "__main__":
    sys.exit(main())
