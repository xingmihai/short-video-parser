#!/usr/bin/env python3
"""
构建前静态校验：界面确实使用 Material 3 Expressive（MDC-Android Views），
compileSdk / targetSdk = 37、minSdk = 26，发行版文件名与仓库名一致，
且工作流不会重复触发构建。编译期不报错的回退，在这里拦住。
"""
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
RES = ROOT / "android/app/src/main/res"

themes_path = RES / "values/themes.xml"
layout_path = RES / "layout/activity_main.xml"
gradle_path = ROOT / "android/app/build.gradle"
props_path = ROOT / "android/gradle.properties"
workflow_path = ROOT / ".github/workflows/build-apk.yml"
old_styles = RES / "values/styles.xml"

problems = []

def read(p):
    return p.read_text(encoding="utf-8") if p.exists() else ""

themes = read(themes_path)
layout = read(layout_path)
gradle = read(gradle_path)
props = read(props_path)
workflow = read(workflow_path)

if "compileSdk 37" not in gradle:
    problems.append("compileSdk 不是 37")
if "targetSdk 37" not in gradle:
    problems.append("targetSdk 不是 37")
if "minSdk 26" not in gradle:
    problems.append("minSdk 不是 26")
if "android.suppressUnsupportedCompileSdk=37" not in props:
    problems.append("gradle.properties 缺少 android.suppressUnsupportedCompileSdk=37")
if "platforms;android-37" not in workflow:
    problems.append("CI 未安装 Android 17 platform")
if "prepare-sdk37.py" not in workflow:
    problems.append("CI 未调用 scripts/prepare-sdk37.py 整理 API 37 平台目录")
if not (ROOT / "scripts/prepare-sdk37.py").exists():
    problems.append("scripts/prepare-sdk37.py 缺失")
# 只允许 tag push / PR / 手动触发构建，push 到 main 不应再触发一次
push_block = workflow.split("pull_request:", 1)[0]
if "branches:" in push_block:
    problems.append("workflow 仍监听 main push，会与 tag push 重复构建")

if "Theme.Material3Expressive" not in themes:
    problems.append("主题未继承 Theme.Material3Expressive.*")

components = [
    "MaterialToolbar", "MaterialCardView", "MaterialButtonToggleGroup",
    "ShapeableImageView", "FloatingActionButton", "WavyProgressView",
    "MaterialAutoCompleteTextView", "TextInputLayout", "MaterialButton", "Chip",
]
for c in components:
    if c not in layout:
        problems.append(f"布局缺少 M3 组件: {c}")

for token in ["ShapeAppearance.App.Card", "colorSurfaceContainer", "textAppearanceTitleLarge"]:
    if token not in layout:
        problems.append(f"布局缺少 M3 token/shape: {token}")

if "com.google.android.material:material:1.14.0" not in gradle:
    problems.append("未依赖 MDC-Android 1.14.0")
if "short-video-parser.apk" not in gradle:
    problems.append("Gradle 输出文件名不是 short-video-parser.apk")

for old in ["bg_btn_primary", "bg_btn_ghost", "bg_card", "bg_input", "bg_tag", "@style/BtnPrimary"]:
    if old in layout:
        problems.append(f"布局仍引用旧手写资源: {old}")
if old_styles.exists():
    problems.append("旧 values/styles.xml（手写按钮样式）未删除")

if problems:
    print("---- M3 UI 校验失败 ----")
    for p in problems:
        print("  x", p)
    sys.exit(1)

print("---- M3 UI 校验通过 ----")
print("主题: Theme.Material3Expressive.DayNight.NoActionBar")
print("组件:", ", ".join(components))
