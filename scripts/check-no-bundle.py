#!/usr/bin/env python3
"""
构建后校验：APK 是纯 Kotlin 产物，不含任何 JS 引擎 / bundle / JS 资产。

断言：
  - 存在 classes*.dex（Kotlin/Java 代码编译产物）
  - 不存在 index.android.bundle、*.hbc、*.js、*.bundle 等资产
  - 不存在 libhermes.so / libjsc.so / libreactnative.so / libjscexecutor.so
  - lib/ 下无 .so（本项目不依赖任何原生库）
  - 附一份 APK 内容摘要，便于确认体积构成

用法：python3 scripts/check-no-bundle.py <apk路径>
"""
import sys
import zipfile
from collections import Counter

JS_ENGINE_SO = {
    "libhermes.so", "libhermestooling.so", "libjsc.so", "libjsctooling.so",
    "libjscexecutor.so", "libreactnative.so", "libreactnativejni.so",
    "libhermes_executor.so", "libhermes-executor-release.so",
}
JS_ASSET_SUFFIX = (".js", ".bundle", ".hbc", ".jsx", ".ts", ".map")
JS_ASSET_EXACT = {"index.android.bundle", "index.android.bundle.map"}


def main(apk_path: str) -> int:
    z = zipfile.ZipFile(apk_path)
    names = z.namelist()
    infos = {n: z.getinfo(n) for n in names}

    dex = [n for n in names if n.endswith(".dex")]
    sos = [n for n in names if n.startswith("lib/") and n.endswith(".so")]
    so_names = {n.rsplit("/", 1)[-1] for n in sos}

    js_assets = [
        n for n in names
        if n in JS_ASSET_EXACT
        or n.lower().endswith(JS_ASSET_SUFFIX)
        or "index.android.bundle" in n
    ]
    js_sos = sorted(so_names & JS_ENGINE_SO)

    total = sum(i.file_size for i in infos.values())
    compressed = sum(i.compress_size for i in infos.values())
    groups = Counter()
    for n, i in infos.items():
        key = n.split("/")[0] if "/" in n else n.split(".")[-1][:12]
        groups[key] += i.compress_size

    print(f"APK: {apk_path}")
    print(f"条目数: {len(names)}  未压缩总计: {total:,} B  APK 内占用: {compressed:,} B")
    print(f"classes*.dex: {len(dex)} 个")
    print("体积构成（按顶层目录，compress_size）:")
    for k, v in groups.most_common(10):
        print(f"  {k:20} {v / 1048576:>7.2f} MB")

    problems = []
    if not dex:
        problems.append("未找到 classes.dex，产物不是有效的 Android 应用")
    if js_assets:
        problems.append(f"发现 JS/bundle 资产: {js_assets[:10]}")
    if js_sos:
        problems.append(f"发现 JS 引擎原生库: {js_sos}")
    if sos:
        problems.append(f"lib/ 下存在 .so（本纯 Kotlin 项目不应有）: {sos[:10]}")

    if problems:
        print("\n---- 校验失败 ----")
        for p in problems:
            print("  ❌", p)
        return 1
    print("\n---- 校验通过：纯 Kotlin，无 JS 引擎 / 无 bundle ----")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else "dist/sv-douyin.apk"))
