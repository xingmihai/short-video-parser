# sv-douyin（纯 Kotlin 版）

**在 App 里粘贴抖音分享链接，直接出无水印直链并播放 / 下载。**

**没有 React Native、没有 Hermes / JSC、没有 `index.android.bundle`、没有任何 `.js` / `.hbc` 资产。** 全部逻辑编译进 `classes.dex`，APK 里连一个原生 `.so` 都没有。

解析逻辑移植自 [jiuhunwl/short_videos](https://github.com/jiuhunwl/short_videos) 的 `api/douyin-cloudflare workers/workers.js`（原作者 JH-Ahua，MIT）。

---

## 为什么改成纯 Kotlin

之前的版本把解析 Worker 塞进 `index.android.bundle`（先是明文 JS，后来编成 Hermes 字节码）。但解析本身只是：正则提 URL、手动跟 302、解析详情 JSON、拼字段——没有一样需要 JS 引擎。删掉 RN 之后：

| | RN 版 v1.3.0 | 纯 Kotlin v2.0.0 |
|---|---|---|
| JS 引擎 | Hermes（多个 `.so`） | 无 |
| `index.android.bundle` | HBC 字节码 | **不存在** |
| 依赖 | react-android / Hermes / SoLoader / appcompat | OkHttp + androidx.core |
| 主题坑 | 必须 AppCompat + 合并库映射 | 平台 `Theme.Material`，无坑 |
| 产物 | APK 20.70 MB | 显著更小 |
| 改逻辑 | 改 JS → 打 bundle → 塞 APK | 改 Kotlin → 直接编译 |

CI 里的 `scripts/check-no-bundle.py` 会断言 APK 内不存在任何 `*.bundle` / `*.hbc` / `*.js` / `*.map` 资产、不存在 Hermes/JSC 的 `.so`、`lib/` 为空，出现即构建失败。

---

## App 里怎么用

1. 抖音点「分享 → 复制链接」
2. 打开 App，点 **「粘贴并解析」**（自动读剪贴板），或把整段分享文案粘进输入框点「解析」
3. 结果页显示标题、作者、封面、画质 / 时长 / 大小，以及**无水印直链**
4. 点 **播放**（系统播放器）/ **下载** / **复制直链**；图集、实况、备选直链、BGM 一并列出

支持：分享口令里的链接、`v.douyin.com` 短链、`www.douyin.com/video/...` 网页链接；视频、图集、Live Photo 实况。

---

## 实现

| 文件 | 作用 |
|---|---|
| `android/app/src/main/java/com/svproxy/DouyinResolver.kt` | 解析核心：短链 302、详情接口、原画 302、画质选取、图集/实况、HEAD 探测 |
| `android/app/src/main/java/com/svproxy/MainActivity.kt` | 单 Activity 界面，输入 → 解析 → 渲染 |
| `android/app/src/main/java/com/svproxy/Media.kt` | 带 Referer 下载 + 异步加载封面/图集 |
| `android/app/src/main/res/layout/activity_main.xml` | 界面布局 |
| `scripts/check-no-bundle.py` | 构建期断言「零 JS 资产」 |

- 网络：OkHttp 4.12（手动 / 自动跟随 302 双 client）
- JSON：`org.json`（Android 内置，无第三方库）
- 图片：OkHttp + `BitmapFactory`，不引 Glide/Coil
- 下载：写入 `Android/data/com.svproxy/files/Download/sv/`，`FileProvider` 生成 `content://` 调起系统播放器
- 封面 / 图集请求带 `Referer: https://www.douyin.com/`，绕开防盗链
- Activity 是 `android.app.Activity` + 平台 `Theme.Material.NoActionBar`，**不依赖 AppCompat**

---

## 跑起来

```bash
git clone https://github.com/xingmihai/sv-proxy-android.git
cd sv-proxy-android/android
./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

或用 Android Studio 打开 `android/` 目录直接 Run。

---

## CI 自动出安装包

`.github/workflows/build-apk.yml`：push `main` / PR / 手动触发 → 构建；打 `v*` 标签 → 发布 GitHub Release。

流程：JDK 17 → Android SDK 34 → `./gradlew assembleRelease` → `check-no-bundle.py` 断言零 JS 资产 → 签名校验 → 上传 / 发布。

Release 只有一个文件：`sv-douyin.apk`。

---

## 几点提示

- 解析依赖抖音免 Cookie 详情接口（`api.amemv.com`），平台改接口或加签名时会返回失败，App 会显示错误码与原因，不会白屏。
- 直链带 `x-expires`，过期返回 404，实时解析即时使用，别缓存直链。
- 播放若被 CDN 防盗链拦截，点「下载」——那条走 OkHttp 带 Referer 拉取，最稳。
- 想加平台（快手、微博等）：在 `DouyinResolver.kt` 里加对应的 host 识别与详情解析分支即可，界面不用动。

---

解析逻辑源自 [jiuhunwl/short_videos](https://github.com/jiuhunwl/short_videos) 的 `api/douyin-cloudflare workers/workers.js`，原作者 JH-Ahua（www.jiuhunwl.cn）。License: MIT。
