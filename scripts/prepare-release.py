#!/usr/bin/env python3
"""
从构建好的 APK 中抽出 Hermes 字节码 bundle，并整理 Release 资产。

不产出、不留存任何明文 JS bundle：
  - dist/index.android.bundle 直接取自 APK 内 assets/，与运行时加载的完全一致
  - 断言它是二进制字节码（Hermes HBC），若是可读 JS 文本则直接失败
  - sourcemap 剥掉 sourcesContent，避免 Release 里夹带源码明文

用法：python3 scripts/prepare-release.py <apk路径> <dist目录>
"""
import json
import sys
import zipfile
from pathlib import Path

HERMES_MAGIC = bytes.fromhex("c61fbc03c103191f")
ASSET = "assets/index.android.bundle"


def is_text(data):
    """
    判断是否为可读文本（明文 JS）。

    字节码（HBC/ELF 等）前 512 字节必然含 C0 控制字节（0x00~0x08 / 0x0e~0x1f）；
    minify 后的 JS 只有可打印 ASCII + UTF-8 中文 + \\t\\n\\r，不会有这些控制字节。
    """
    head = data[:512]
    ctrl = sum(1 for b in head if b < 9 or 14 <= b <= 31)
    return ctrl == 0


def main(apk_path, dist_dir):
    dist = Path(dist_dir)
    dist.mkdir(parents=True, exist_ok=True)

    with zipfile.ZipFile(apk_path) as z:
        names = z.namelist()
        asset = ASSET if ASSET in names else next(
            (n for n in names if n.endswith("index.android.bundle")), None
        )
        if not asset:
            print(f"❌ APK 内找不到 {ASSET}")
            return 1
        data = z.read(asset)

    print(f"asset 路径: {asset}")

    print(f"APK 内 {ASSET}: {len(data):,} B")
    print(f"前 16 字节: {data[:16].hex(' ')}")

    if is_text(data):
        print("❌ 该 bundle 是明文 JS（可读文本），不是字节码")
        return 1

    if data.startswith(HERMES_MAGIC):
        print("  ✅ Hermes HBC magic 匹配")
    else:
        # 不同 Hermes 版本 magic 可能变化，但只要是二进制就不是明文
        print("  ✅ 二进制字节码（非 Hermes 标准 magic，按非文本判定通过）")

    (dist / "index.android.bundle").write_bytes(data)

    # sourcemap：保留行映射，移除源码正文
    map_file = dist / "index.android.bundle.map"
    if map_file.exists():
        try:
            m = json.loads(map_file.read_text(encoding="utf-8"))
            had = "sourcesContent" in m
            m.pop("sourcesContent", None)
            map_file.write_text(
                json.dumps(m, ensure_ascii=False, separators=(",", ":")),
                encoding="utf-8",
            )
            print(f"  ✅ sourcemap 已剥离 sourcesContent（原有: {had}）")
        except Exception as e:
            print(f"  ⚠️ sourcemap 处理失败（不影响 bundle）: {e}")

    # 兜底：dist 里不允许存在任何明文 .bundle
    for p in dist.glob("*.bundle"):
        if is_text(p.read_bytes()):
            print(f"❌ dist 里发现明文 bundle: {p.name}")
            return 1

    print("---- 产物校验通过：无明文 JS ----")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else "dist"))
