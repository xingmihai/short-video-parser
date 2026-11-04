#!/usr/bin/env python3
"""
把 Android 17 / API 37 平台整理成 AGP 8.x 认得的 platforms/android-37。

背景：
  - sdkmanager 装出来的目录常叫 android-37.0 / android-37.1，而 AGP 按
    hash "android-37" 找平台，会报 Failed to find Platform SDK with path: platforms;android-37
  - Google 新平台包的 package.xml 用 SDK XML schema v4，AGP 8.x 只认到 v3

本脚本：
  1. 若 platforms/ 下已有 android-37.*，复制一份为 android-37
  2. 若连 37.* 都没有，从 dl.google.com 的 repository XML 里找出 platform 37 的 zip 下载解压
  3. 修正 source.properties（ApiLevel=37）与 package.xml（path / schema / api-level）
  4. 校验 android.jar 存在，否则失败并给出诊断

用法：python3 scripts/prepare-sdk37.py <ANDROID_HOME>
"""
import os
import re
import shutil
import sys
import tempfile
import urllib.request
import zipfile
from pathlib import Path

REPO_XMLS = [
    "https://dl.google.com/android/repository/repository2-1.xml",
    "https://dl.google.com/android/repository/repository2-2.xml",
    "https://dl.google.com/android/repository/repository2-3.xml",
]


def download_platform(platforms: Path) -> Path:
    """sdkmanager 装不到时，直接从 Google 仓库取 platform 37 的 zip。"""
    for xml_url in REPO_XMLS:
        try:
            with urllib.request.urlopen(xml_url, timeout=90) as r:
                data = r.read().decode("utf-8", errors="replace")
        except Exception as e:
            print("仓库列表读取失败", xml_url, str(e)[:120])
            continue
        # 匹配 platforms;android-37 或 platforms;android-37.0
        m = re.search(
            r'<remotePackage[^>]*path="platforms;android-37(?:\.\d+)?"[^>]*>(.*?)</remotePackage>',
            data, re.S,
        )
        if not m:
            continue
        z = re.search(r"<url>([^<]+\.zip)</url>", m.group(1), re.S)
        if not z:
            continue
        zip_url = "https://dl.google.com/android/repository/" + z.group(1).strip()
        print("下载", zip_url)
        with tempfile.TemporaryDirectory() as tmp:
            zp = Path(tmp) / "p.zip"
            with urllib.request.urlopen(zip_url, timeout=180) as r, open(zp, "wb") as f:
                shutil.copyfileobj(r, f)
            with zipfile.ZipFile(zp) as zf:
                zf.extractall(tmp)
            # 找到含 android.jar 的目录
            src = None
            for p in Path(tmp).rglob("android.jar"):
                src = p.parent
                break
            if src is None:
                print("zip 内没有 android.jar")
                continue
            dst = platforms / "android-37.0"
            if dst.exists():
                shutil.rmtree(dst)
            shutil.copytree(src, dst)
            print("已解压到", dst)
            return dst
    raise RuntimeError("sdkmanager 与 Google 仓库都拿不到 API 37 平台；请查看 CI 里 sdkmanager --list 的 37 条目")


def main(sdk_root: str) -> int:
    platforms = Path(sdk_root) / "platforms"
    platforms.mkdir(parents=True, exist_ok=True)
    target = platforms / "android-37"

    cands = sorted(d.name for d in platforms.iterdir() if d.is_dir() and d.name.startswith("android-37"))
    print("已有 37 平台目录:", cands)

    if target.is_dir():
        print("platforms/android-37 已存在，只修正元数据")
    elif cands:
        src = platforms / cands[-1]
        shutil.copytree(src, target)
        print("已从", cands[-1], "复制为 android-37")
    else:
        download_platform(platforms)
        shutil.copytree(platforms / "android-37.0", target)
        print("已从下载包复制为 android-37")

    sp = target / "source.properties"
    if sp.is_file():
        s = sp.read_text(encoding="utf-8", errors="replace")
        s = re.sub(r"(?m)^AndroidVersion\.ApiLevel=.*$", "AndroidVersion.ApiLevel=37", s)
        if re.search(r"(?m)^AndroidVersion\.CodeName=", s):
            s = re.sub(r"(?m)^AndroidVersion\.CodeName=.*$", "AndroidVersion.CodeName=REL", s)
        sp.write_text(s, encoding="utf-8")
        print("source.properties -> ApiLevel=37")

    px = target / "package.xml"
    if px.is_file():
        s = px.read_text(encoding="utf-8", errors="replace")
        s = re.sub(r'path="platforms;android-37(?:\.\d+)?"', 'path="platforms;android-37"', s)
        # AGP 8.x 只解析到 repository2/03
        s = re.sub(r"repository2/0[1245]", "repository2/03", s)
        s = re.sub(r"<api-level>37(?:\.\d+)?</api-level>", "<api-level>37</api-level>", s)
        px.write_text(s, encoding="utf-8")
        print("package.xml -> path=platforms;android-37 / schema v3")

    jar = target / "android.jar"
    if not jar.is_file():
        print("!! android.jar 缺失:", jar, file=sys.stderr)
        return 1
    print("OK:", jar, jar.stat().st_size, "bytes")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else os.environ.get("ANDROID_HOME", "")))
