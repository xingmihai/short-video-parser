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
3. **结果先显示再加载**：解析一返回就把平台、标题、作者、直链和按钮渲染出来，封面、缩略图、文件大小随后异步补齐，期间显示「资源加载中…」
4. 点 **播放** / **下载** / **复制直链**；图集、实况动图、备选直链、BGM、互动数据一并列出
5. 多结果（B 站分 P、抖音主页、豆包多图多视频）用上方 Chip 标签切换
6. 高级设置里可填 Token / Cookie、选网易云音质、设主页批量条数

### 结果页与下载

- **抖音实况动图**：主按钮「下载全部动图（视频）」下载的是 Live Photo 的 `.mp4`，不再只下静态图；每组还有独立的「动图 N · 下载视频」按钮，缩略图点击仍可单独下载图片
- **图集**：主按钮下载全部图片；**BGM** 单独下载音频
- **下载目录**：`/storage/emulated/0/Download/`（Android 10+ 走 `MediaStore.Downloads`，Android 8/9 直写并申请 `WRITE_EXTERNAL_STORAGE`），文件出现在系统「下载」里，不再存 App 私有目录
- **Token 分平台保存**：高级设置里「保存 Token」「清除」按当前所选平台隔离存储；切换平台自动回填；解析成功时也会按实际识别出的平台自动保存一份

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
```

GitHub Actions 分两条工作流，互不重复：

| 工作流 | 触发 | 做什么 |
|---|---|---|
| `build-apk.yml` | push `v*` 标签 / PR | 只构建 + 签名 + 上传产物，**绝不发行** |
| `build-apk.yml` | 手动 `Run workflow` | 构建 + 签名 + **自动发布正式发行版**（`prerelease: false`） |
| `prerelease.yml` | push `main` / 手动 | 自动预发行：先删光所有旧预发行（连 tag），再创建唯一一个 prerelease |

也就是说：**推送不发行，手动构建才发行正式版**。手动触发时 Release 的 tag / 标题同样自动算出（`release-<run>-<sha>` / `build-<run>-<sha>`），发行说明只取本次提交信息；`release-` 前缀不命中 `v*` 标签规则，创建 tag 不会递归触发本工作流。

`prerelease.yml` 用 `concurrency` 串行化，每次先删光旧预发行再建新的，仓库里始终只留一个。预发行 tag / 版本名 / 版本号都由 `github.run_number` 与提交短 SHA 自动算出，**源码与工作流里不硬编码任何版本号**；`versionCode` 用 run number，`versionName` 形如 `pre-<run>-<sha>`，通过 `APP_VERSION_CODE` / `APP_VERSION_NAME` 注入 `build.gradle`。

签名密钥放在仓库环境密钥（Actions Secrets）里，构建时以环境变量注入，不进仓库：

- `SIGNING_KEYSTORE_BASE64`：JKS 的 Base64
- `SIGNING_STORE_PASSWORD` / `SIGNING_KEY_PASSWORD`
- `SIGNING_KEY_ALIAS`

`build.gradle` 检测到 `SIGNING_KEYSTORE` 才用该 JKS 签名，本地无密钥时回退 `debug.keystore`。

环境：JDK 17 + Android SDK Platform 35 / 37 + Gradle 8.9 + AGP 8.7.3 + Kotlin 2.2.0。

GitHub Actions 的 Node 20 已 EOL，runner 从 2026-06-16 起默认 Node 24；工作流里的 action 全部使用声明 `runs.using: node24` 的版本，不再出现「forced to run on Node.js 24」警告：

| action | 版本 | 运行时 |
|---|---|---|
| `actions/checkout` | v5 | node24 |
| `actions/setup-java` | v5 | node24 |
| `gradle/actions/setup-gradle` | v5 | node24 |
| `actions/upload-artifact` | v6 | node24（v5 仍是 node20，所以跳过 v5） |
| `softprops/action-gh-release` | v3 | node24 |

`FORCE_JAVASCRIPT_ACTIONS_TO_NODE24` / `ACTIONS_ALLOW_USE_UNSECURE_NODE_VERSION` 都不需要设置。

`compileSdk = 37`、`targetSdk = 37`（Android 17 / API 37），`minSdk = 26`（Android 8.0）。AGP 8.7.3 官方只测到 API 35，由 `android/gradle.properties` 的 `android.suppressUnsupportedCompileSdk=37,37.0` 放行。CI 装 API 37 平台时会依次试 channel 0–3，再由 `scripts/prepare-sdk37.py` 把 `android-37.2-beta3` 这类预览包目录整理成 AGP 认得的唯一 `platforms/android-37`（归一 `source.properties` / `package.xml` 的包路径、ApiLevel、codename 与 SDK XML schema），装不到时还会从 Google 仓库直接下载。同时关闭了 `lintVitalRelease`，预览平台不必再跑 lint。

---

## 说明

解析依赖各平台未公开的网页 / 接口结构，平台改版或加签名时可能返回失败，App 会直接显示错误码，不会白屏。需要 Cookie 的功能由使用者自己填写，App 不内置、不上传任何账号信息。

本项目仅供学习研究，请勿用于侵犯版权或违反平台条款的用途。

## 授权

MIT。解析逻辑源自 [jiuhunwl/short_videos](https://github.com/jiuhunwl/short_videos)（MIT）。
