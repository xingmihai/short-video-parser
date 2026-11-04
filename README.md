# short-video-parser

**在 App 里粘贴任意平台的分享链接，自动识别，直接出无水印直链并播放 / 下载。**

界面使用 **Material 3 Expressive**（MDC-Android Views 1.14.0）：动态色、非对称形状、Toolbar + FAB、Exposed Dropdown、Button group、Chip、波形实时进度与展开过渡动效，浅色 / 深色跟随系统。

**没有 React Native、没有 Hermes / JSC、没有 `index.android.bundle`、没有任何 `.js` / `.hbc` 资产。** 全部逻辑编译进 `classes.dex`，APK 里连一个原生 `.so` 都没有。不监听端口、不转发请求、不需要服务器，也不请求任何第三方解析 API。

解析逻辑移植自 [jiuhunwl/short_videos](https://github.com/jiuhunwl/short_videos) 的 `api/` 目录（原作者 JH-Ahua，MIT）——把里面的 PHP / Python / Workers 入口逐个翻成了 Kotlin。

---

## 下载

[Releases](https://github.com/xingmihai/short-video-parser/releases/latest) 里的 **`short-video-parser.apk`** 已签名，直接安装。

---

## 支持的平台

| 平台 | 免 Cookie | 说明 |
|---|:---:|---|
| 抖音 单作品 | ✅ | 视频 / 图集 / 实况、原画直链、BGM、备选直链 |
| 抖音 主页批量 | ❌ | 用户作品列表，需网页版登录 Cookie |
| 快手 | ✅ | 视频 / 图集 / 单图，INIT_STATE + APOLLO_STATE 双解析 |
| 小红书 | ✅ | 视频去水印、图集原图、Live Photo，含 token 兜底 |
| 哔哩哔哩 | ✅ | 分 P 列表与每 P 直链；填 SESSDATA 可解锁高画质 |
| 微博 | ✅ | 多画质（2K / 1080P / 720P…）、互动数据 |
| 今日头条 | ✅ | 视频去水印、封面、BGM |
| 皮皮虾 | ✅ | 视频去水印、图集 |
| 皮皮搞笑 | ✅ | 视频去水印、封面 |
| 最右 | ✅ | 视频去水印、作者信息 |
| 豆包 AI 视频 | ✅ | share_id + video_id → 无水印直链 |
| 豆包 对话图文 | ✅ | 分享页 `data-fn-args` → 无水印图片 / 生成视频 |
| 即梦 AI | ✅ | item_id → 原画视频、多档转码、统计信息 |
| 网易云音乐 | ✅ | eapi AES-ECB 加密，歌曲直链 / 封面 / 音质 / 大小 |

平台识别移植自原仓库 `api/sv.php` 的关键词分发表；App 里也能在「平台」下拉里手动指定（短链识别不出来时用）。

---

## App 里怎么用

1. 复制任意平台的分享文案或链接
2. 打开 App，点 **「粘贴并解析」**（或右下角 FAB），或直接粘进输入框点「解析」
3. 结果卡显示识别出的平台、标题、作者、封面、画质 / 时长 / 大小和无水印直链
4. 点 **播放** / **下载** / **复制直链**；图集、实况、备选直链、BGM、互动数据一并列出
5. 多结果（B 站分 P、抖音主页、豆包多图多视频）用上方 Chip 标签切换
6. 高级设置里可填 Cookie、选网易云音质、设主页批量条数

播放若被防盗链拦截，用「下载」——由原生 OkHttp 带对应平台 Referer 拉取，不经过任何代理。

---

## UI（Material 3 Expressive）

| 设计点 | 落地 |
|---|---|
| Vibrant colors / 动态色 | `Theme.Material3Expressive.DayNight.NoActionBar` + `DynamicColors`（Android 12+ 跟随壁纸，低版本回落 M3 baseline 紫） |
| Contrasting shapes | 输入卡 / 结果卡用反向非对称圆角 `ShapeAppearance.App.Card*` |
| Toolbars + FAB | 顶部 `MaterialToolbar`（标题 + 副标题 + 清空按钮），右下角 `FloatingActionButton` 一键粘贴解析 |
| Adaptive components | `TextInputLayout`  outlined box、`MaterialAutoCompleteTextView` exposed dropdown 选平台 / 音质 |
| Button groups | `MaterialButtonToggleGroup` 组织「播放 / 下载」相关动作 |
| Waveform progress | 自绘 `WavyProgressView`：双相位正弦波，颜色取自主题 |
| Flexible typography | 全量使用 `textAppearanceHeadline*` / `Title*` / `Body*` / `Label*` token |
| Intuitive motion | 高级设置展开、结果卡出现走 `TransitionManager` 过渡 |

MDC-Android Views 1.14.0 是官方最后一个稳定 Views 版本，Compose 是后续方向；本项目保持纯 Kotlin + XML，不引入 Compose 运行时。

---

## 构建

```bash
cd android
./gradlew assembleRelease     # 产出 app/build/outputs/apk/release/short-video-parser.apk
python3 ../scripts/check-m3-ui.py
python3 ../scripts/check-no-bundle.py app/build/outputs/apk/release/short-video-parser.apk
```

GitHub Actions（`.github/workflows/build-apk.yml`）**只在打 `v*` 标签时构建一次**（另有 PR 与手动触发）；push 到 `main` 不再触发，避免 main + tag 两次重复构建。环境：JDK 17 + Android SDK Platform 35 / 37 + Gradle 8.9 + AGP 8.7.3 + Kotlin 2.2.0，打标签即发布 Release。

`compileSdk = 37`（Android 17 / API 37），`targetSdk` 保持 35。AGP 8.7.3 官方只测到 API 35，由 `android/gradle.properties` 的 `android.suppressUnsupportedCompileSdk=37` 放行；AGP 缺 android-37 平台时会自动补下载。

CI 有三道硬断言：

- `check-m3-ui.py`：主题继承 `Theme.Material3Expressive`、组件齐全、无旧手写资源
- `check-no-bundle.py`：APK 内无 JS 资产 / JS 引擎 `.so` / `lib/` 下有 `.so`，且文件名必须是 `short-video-parser.apk`
- `apksigner verify`：签名有效

---

## 说明

解析依赖各平台未公开的网页 / 接口结构，平台改版或加签名时可能返回失败，App 会直接显示错误码，不会白屏。需要 Cookie 的功能由使用者自己填写，App 不内置、不上传任何账号信息。

本项目仅供学习研究，请勿用于侵犯版权或违反平台条款的用途。

## 授权

MIT。解析逻辑源自 [jiuhunwl/short_videos](https://github.com/jiuhunwl/short_videos)（MIT）。
