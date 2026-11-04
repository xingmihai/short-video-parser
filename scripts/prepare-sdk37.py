#!/usr/bin/env python3
"""
把 Android 17 / API 37 平台整理成 AGP 8.x 认得的 platforms/android-37。

背景（CI 实测）：
  - sdkmanager 稳定/preview channel 给的包 id 可能是
    platforms;android-37.2-beta3，目录 android-37.2-beta3
  - AGP 按 hash "android-37" 找平台；若目录名与 source.properties 的
    Pkg.Path / package.xml 的 localPackage path 不一致，会打印
    "Observed package id ... in inconsistent location" 并跳过该平台，
    最终报 Failed to find Platform SDK with path: platforms;android-37
  - Google 新平台包的 package.xml 用 SDK XML schema v4，AGP 8.x 只认到 v3
  - 预览包里的 codename 会让 AGP 把它当 preview 平台（hash 变成 codename）

本脚本只保留一个 platforms/android-37 目录，并把所有元数据归一到 "37"：
  1. 已有 android-37.* 就移动（不是复制）成 android-37，其余 37* 目录删掉
  2. 一个都没有时，从 dl.google.com 的 repository XML 找 platform 37 zip 下载
  3. source.properties: Pkg.Path / ApiLevel / CodeName 归一
  4. package.xml: localPackage path / api-level / codename / schema 归一
  5. 校验 android.jar 存在

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


def download_platform(platforms: Path) -> None:
    """sdkmanager 装不到时，直接从 Google 仓库取 platform 37 的 zip。"""
    for xml_url in REPO_XMLS:
        try:
            with urllib.request.urlopen(xml_url, timeout=90) as r:
                data = r.read().decode("utf-8", errors="replace")
        except Exception as e:
            print("仓库列表读取失败", xml_url, str(e)[:120])
            continue
        m = re.search(
            r'<remotePackage[^>]*path="platforms;android-37[^"]*"[^>]*>(.*?)</remotePackage>',
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
            src = next((p.parent for p in Path(tmp).rglob("android.jar")), None)
            if src is None:
                print("zip 内没有 android.jar")
                continue
            dst = platforms / "android-37.0"
            if dst.exists():
                shutil.rmtree(dst)
            shutil.copytree(src, dst)
            print("已解压到", dst)
            return
    raise RuntimeError("sdkmanager 与 Google 仓库都拿不到 API 37 平台；请查看 CI 里 sdkmanager --list 的 37 条目")


def normalize(platforms: Path, target: Path) -> None:
    sp = target / "source.properties"
    if sp.is_file():
        s = sp.read_text(encoding="utf-8", errors="replace")
        # 包 id 必须与目录名一致，否则 AGP 报 inconsistent location
        s = re.sub(r"(?m)^Pkg\.Path=.*$", "Pkg.Path=platforms;android-37", s)
        s = re.sub(r"(?m)^AndroidVersion\.ApiLevel=.*$", "AndroidVersion.ApiLevel=37", s)
        s = re.sub(r"(?m)^AndroidVersion\.CodeName=.*$", "AndroidVersion.CodeName=REL", s)
        # 预览包特有的次版本号 / codename 行一并去掉
        s = re.sub(r"(?m)^AndroidVersion\.ExtensionLevel=.*\n?", "", s)
        s = re.sub(r"(?m)^AndroidVersion\.IsBaseSdk=.*\n?", "", s)
        sp.write_text(s, encoding="utf-8")
        print("source.properties -> Pkg.Path/ApiLevel/CodeName 归一")

    px = target / "package.xml"
    if px.is_file():
        s = px.read_text(encoding="utf-8", errors="replace")
        # localPackage path 必须正好是 platforms;android-37
        s = re.sub(r'path="platforms;android-37[^"]*"', 'path="platforms;android-37"', s)
        # AGP 8.x 只解析到 repository2/03
        s = re.sub(r"repository2/0[12456789]", "repository2/03", s)
        s = re.sub(r"repository2/1[0-9]", "repository2/03", s)
        # 预览包的 codename 会让 AGP 把它当 preview 平台
        s = re.sub(r"<codename>[^<]*</codename>", "", s)
        s = re.sub(r"<api-level>[^<]*</api-level>", "<api-level>37</api-level>", s)
        px.write_text(s, encoding="utf-8")
        print("package.xml -> path=platforms;android-37 / schema v3 / 无 codename")


def main(sdk_root: str) -> int:
    platforms = Path(sdk_root) / "platforms"
    platforms.mkdir(parents=True, exist_ok=True)
    target = platforms / "android-37"

    cands = sorted(d.name for d in platforms.iterdir()
                   if d.is_dir() and d.name.startswith("android-37") and d.name != "android-37")
    print("其它 37 平台目录:", cands)

    if target.is_dir():
        print("platforms/android-37 已存在")
    elif cands:
        # 移动而非复制：避免同一包 id 出现在两个目录里被 AGP 判为不一致
        shutil.move(str(platforms / cands[-1]), str(target))
        print("已从", cands[-1], "移动为 android-37")
        cands = cands[:-1]
    else:
        download_platform(platforms)
        cands = sorted(d.name for d in platforms.iterdir()
                       if d.is_dir() and d.name.startswith("android-37") and d.name != "android-37")
        shutil.move(str(platforms / cands[-1]), str(target))
        print("已从下载包移动为 android-37")
        cands = cands[:-1]

    # 其余 37* 目录删掉，保证 SDK 里只有一个 API 37 平台且 id 一致
    for d in cands:
        shutil.rmtree(platforms / d)
        print("移除重复目录", d)

    normalize(platforms, target)

    jar = target / "android.jar"
    if not jar.is_file():
        print("!! android.jar 缺失:", jar, file=sys.stderr)
        return 1
    print("OK:", jar, jar.stat().st_size, "bytes")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else os.environ.get("ANDROID_HOME", "")))
