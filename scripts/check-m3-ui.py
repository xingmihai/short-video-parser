#!/usr/bin/env python3
"""
构建前静态校验：界面确实使用 Material 3 Expressive（MDC-Android Views），
且发行版文件名与仓库名一致。编译期不报错的 UI 回退，在这里拦住。
"""
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
RES = ROOT / "android/app/src/main/res"

themes_path = RES / "values/themes.xml"
layout_path = RES / "layout/activity_main.xml"
gradle_path = ROOT / "android/app/build.gradle"
old_styles = RES / "values/styles.xml"

problems = []

def read(p):
    return p.read_text(encoding="utf-8") if p.exists() else ""

themes = read(themes_path)
layout = read(layout_path)
gradle = read(gradle_path)

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
