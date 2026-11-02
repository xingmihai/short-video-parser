# sv-douyin（纯 Kotlin · 全平台版）

**在 App 里粘贴任意平台的分享链接，自动识别，直接出无水印直链并播放 / 下载。**

**没有 React Native、没有 Hermes / JSC、没有 `index.android.bundle`、没有任何 `.js` / `.hbc` 资产。** 全部逻辑编译进 `classes.dex`，APK 里连一个原生 `.so` 都没有。不监听端口、不转发请求、不需要服务器，也不请求任何第三方解析 API。

解析逻辑移植自 [jiuhunwl/short_videos](https://github.com/jiuhunwl/short_videos) 的 `api/` 目录（原作者 JH-Ahua，MIT）——把里面的 PHP / Python / Workers 入口逐个翻成了 Kotlin。

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
2. 打开 App，点 **「粘贴并解析」**，或直接粘进输入框点「解析」
3. 顶部自动显示识别出的平台；结果页显示标题、作者、封面、画质 / 时长 / 大小和无水印直链
4. 点 **播放** / **下载** / **复制直链**；图集、实况、备选直链、BGM、互动数据一并列出
5. 多结果（B 站分 P、抖音主页、豆包多图多视频）用上方序号按钮切换

### 高级设置（可折叠）

| 项 | 用途 |
|---|---|
| Cookie | 抖音主页批量**必填**；B站 / 小红书 / 头条 / 快手 / 豆包对话 / 网易云可选填 |
| 网易云音质 | 标准 / 极高 / 无损 / Hires / 沉浸环绕 / 高清环绕 / 超清母带 |
| 主页条数 | 抖音主页批量拉取数量，默认 18 |

---

## 实现

| 文件 | 作用 |
|---|---|
| `Core.kt` | 统一 OkHttp 层、输出模型、平台识别、org.json 扩展 |
| `AggregateResolver.kt`（在 `ResolversExtra.kt` 内） | 聚合分发入口，一个调用走完所有平台 |
| `DouyinResolver.kt` | 抖音单作品（原画 302、画质选取、图集 / 实况 / BGM） |
| `ResolversCommon.kt` | 快手 / 小红书 / B站 / 微博 / 头条 / 皮皮虾 / 皮皮搞笑 / 最右 |
| `ResolversExtra.kt` | 豆包 AI 视频 / 豆包对话 / 即梦 / 网易云 / 抖音主页批量 / 聚合 |
| `MainActivity.kt` + `activity_main.xml` | 单 Activity 界面 |
| `Media.kt` | 带对应平台 Referer 下载 + 异步加载网络图片 |
| `scripts/check-no-bundle.py` | 构建期断言「零 JS 资产」 |

技术选型：OkHttp 4.12（网络）、`org.json`（Android 内置）、`BitmapFactory`（图片，不引 Glide/Coil）、`javax.crypto`（网易云 eapi）、`FileProvider`（下载后调起系统播放器）。Activity 是 `android.app.Activity` + 平台 `Theme.Material`，**不依赖 AppCompat**。

### 防盗链处理

各平台下载时由 `Media.refererFor(platform)` 注入对应 Referer，封面 / 图集加载也带同样的头：

```
抖音 → https://www.douyin.com/          快手 → https://www.kuaishou.com/
小红书 → https://www.xiaohongshu.com/     B站 → https://www.bilibili.com/
微博 → https://weibo.com/                网易云 → https://music.163.com/  …
```

---

## 跑起来

```bash
git clone https://github.com/xingmihai/sv-proxy-android.git
cd sv-proxy-android/android
./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

---

## CI 自动出安装包

`.github/workflows/build-apk.yml`：push `main` / PR / 手动触发 → 构建；打 `v*` 标签 → 发布 Release。

流程：JDK 17 → Android SDK 34 → `./gradlew assembleRelease` → `check-no-bundle.py` 断言零 JS 资产 / 零 JS 引擎 so / `lib/` 为空 / `classes.dex` 非空 → 签名校验 → 上传 / 发布。Release 只出一个 `sv-douyin.apk`。

---

## 几点提示

- 解析依赖各平台**未公开的网页 / 接口结构**，平台改版或加签名时会返回失败，App 直接显示错误码与原因，不会白屏。
- 抖音主页、B 站高画质、网易云 VIP 等需要登录态的功能，Cookie 由用户自己在高级设置里填，**App 不上传、不内置任何账号信息**。
- 直链带 `x-expires` 等时效参数，过期返回 404，实时解析即时使用，别缓存直链。
- 播放若被 CDN 防盗链拦截，点「下载」——那条走 OkHttp 带 Referer 拉取，最稳。
- 想加平台：在 `Platform.kt` 加关键词、写一个 Resolver、在 `AggregateResolver` 注册即可，界面不用动。

---

解析逻辑源自 [jiuhunwl/short_videos](https://github.com/jiuhunwl/short_videos) `api/` 目录，原作者 JH-Ahua（www.jiuhunwl.cn）。License: MIT。
