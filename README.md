# sv-proxy-android

**在 App 里粘贴抖音分享链接，直接出无水印直链并播放 / 下载。**

不用服务器、不用 Cloudflare 账号、**不用本地网关**——不监听端口、不转发请求、没有 `8080` 和局域网地址。整个解析链路都在 `index.android.bundle` 里。

解析逻辑源自 [jiuhunwl/short_videos](https://github.com/jiuhunwl/short_videos) 的 `api/douyin-cloudflare workers/workers.js`（抖音分享链接解析，原作者 JH-Ahua，MIT）。

---

## App 里怎么用

1. 抖音点「分享 → 复制链接」
2. 打开 App，点 **「粘贴并解析」**（自动读剪贴板），或把整段分享文案粘进输入框点「解析」
3. 结果页显示标题、作者、封面、画质 / 时长 / 大小，以及**无水印直链**
4. 点 **播放**（系统播放器）/ **下载** / **复制直链**；图集、实况、备选直链、BGM 也一并列出

支持：分享口令里的链接、`v.douyin.com` 短链、`www.douyin.com/video/...` 网页版链接；视频、图集、Live Photo 实况。

---

## 没有网关之后，能力怎么落地

| 原来（本地 8080 网关） | 现在 |
|---|---|
| `HttpGateway.kt` 监听端口、接收 HTTP 请求 | 已删除 |
| `src/proxy.js` 代理 CDN 直链、注入 Referer | 已删除 |
| `src/server.js` 事件路由 / `src/config.js` 端口配置 | 已删除 |
| 播放地址是 `http://127.0.0.1:8080/?proxyurl=...` | 直接用解析出的 CDN 直链 |

替代方式（都不需要监听端口）：

- **封面 / 图集**：RN `Image` 的 `source.headers` 直接带 `Referer: https://www.douyin.com/`
- **播放**：`Linking.openURL(直链)` 交给系统播放器 / 浏览器
- **下载**：原生 `Gateway.download()` 用 OkHttp 带 `Referer` + UA 拉流，写入 App 私有外部目录，再通过 `FileProvider` 生成 `content://` 调起系统播放器。这条最稳，能绕开 CDN 防盗链 403
- **解析阶段**：`Gateway.requestText()` 用 OkHttp 发请求，支持手动跟 302 拿 `Location`、自动跟 302 拿详情 JSON，不受浏览器 CORS 限制

原生现在只剩四件事：发解析请求、带 Referer 下载、读剪贴板、写剪贴板。业务与界面全在 `index.android.bundle`，改逻辑只需改 JS 重新打包，不用动 Kotlin。

---

## 跑起来

```bash
git clone https://github.com/xingmihai/sv-proxy-android.git
cd sv-proxy-android
npm install
npx react-native run-android      # 手机/模拟器连着 adb
```

### 只重新打 bundle

```bash
npm run bundle            # Hermes 字节码版
npm run bundle plain      # 明文 JS 版（可文本编辑）
```

`android/gradle.properties` 默认 `hermesEnabled=true`（RN 0.76 官方默认）。无论选哪个引擎，`MainApplication.kt` 都必须用 `SoLoader.init(this, OpenSourceMergedSoMapping)`，详见下方「启动闪退根因」。

关于「替换 bundle」：Gradle 打包时会用 `hermesc` 把 APK 内的 bundle 编译成字节码，但 Release 里附带的是**明文 JS 版** `index.android.bundle`。把明文替换进 APK 后，Hermes 会以解释模式执行它，功能一致、启动略慢，仍可直接文本修改。

---

## 目录

```
index.js                 入口，注册 App
src/
  App.js                 主界面：粘贴 → 解析 → 播放 / 下载
  resolve.js             ★ 抖音分享链接解析（解析 Worker 移植）
  native.js              原生桥：requestText / download / 剪贴板
  utils.js               headerGet 等纯 JS 工具
android/app/src/main/
  AndroidManifest.xml    INTERNET + FileProvider
  res/xml/file_paths.xml FileProvider 路径
  java/com/svproxy/
    MainActivity.kt / MainApplication.kt
    proxy/UpstreamPool.kt   OkHttp 请求池（解析 + 带 Referer 下载）
    proxy/SvProxyModule.kt  JS 可见的 SvProxyGateway
    proxy/SvProxyPackage.kt 模块注册
scripts/check-apk-libs.py 构建期静态校验
```

---

## CI 自动出安装包

`.github/workflows/build-apk.yml`：push `main` / PR / 手动触发 → 构建；打 `v*` 标签 → 发布 GitHub Release。

流程：`npm ci` → 生成 `index.android.bundle`（明文、已 minify）→ `./gradlew assembleRelease` → 静态校验 → 打包上传。

### 压缩策略（Release 默认全开）

| 层 | 开关 | 效果 |
|---|---|---|
| JS bundle | `--dev false --minify true` | 压缩成单行、变量短名、去注释 |
| Java/Kotlin | `minifyEnabled true` + `proguard-android-optimize.txt` | R8 代码压缩、优化、混淆 |
| 资源 | `shrinkResources true` | 移除未引用的 res 资源 |
| ABI 裁剪 | `abiFilters 'arm64-v8a','armeabi-v7a'` | 去掉 x86/x86_64 冗余 .so |
| 分发 | `zip -9` | 三件套打成 `sv-proxy-android-release.zip` |

### 启动闪退根因（RN 0.76 Native Library Merging）

RN 0.76 把一批 JNI 库合并进少数物理 `.so`，逻辑库名不再等于文件名：

| 代码里 loadLibrary 的名字 | 实际映射到 |
|---|---|
| `hermes_executor` / `hermesinstancejni` | `libhermestooling.so` |
| `jscexecutor` / `jscruntime` / `jscinstance` | `libjsctooling.so` |
| `reactnativejni` / `fabricjni` / `yoga` … | `libreactnative.so` |

沿用 RN ≤0.75 的 `SoLoader.init(this, false)` 会直接 `dlopen` 独立库 → 找不到 → 启动即 `UnsatisfiedLinkError`。修法：

```kotlin
import com.facebook.react.soloader.OpenSourceMergedSoMapping
// ...
SoLoader.init(this, OpenSourceMergedSoMapping)
```

### 启动闪退根因之二：Activity 主题

`ReactActivity` 继承 `AppCompatActivity`，主题 parent 必须是 `Theme.AppCompat` 系。用 `android:Theme.Material.*` 会在 `setContentView` 抛 `IllegalStateException`。修法：

```xml
<style name="AppTheme" parent="Theme.AppCompat.Light.NoActionBar">
```

并显式依赖 `androidx.appcompat:appcompat`。

**native .so 必须保持未压缩**：`useLegacyPackaging = false` + `extractNativeLibs="false"`，否则系统无法从 `base.apk!/lib/<abi>/` 直接 dlopen。

`scripts/check-apk-libs.py` 构建期统一校验：SoLoader mapping、AppCompat 主题、APK 内 `.so` 全部 Stored、存在 JS 引擎库 + tooling 合并目标库 + `libreactnative.so`、含 arm64-v8a。这类问题编译不会报错，只能靠静态校验拦住。

产物（Actions → Artifacts 或 Releases 页）：`sv-proxy-android.apk`（已签名，直接装）、`index.android.bundle`、`index.android.bundle.map`、`sv-proxy-android-release.zip`。

---

## 几点提示

- 解析依赖抖音免 Cookie 详情接口，平台改接口或加签名时会返回失败，App 会直接显示错误码与原因，不会白屏。
- 直链带 `x-expires`，过期返回 404，实时解析即时使用，别缓存直链。
- 下载的文件在 `Android/data/com.svproxy/files/Download/sv/`，用「下载」按钮会顺带调起系统播放器打开。
- `assets/` 下的 `index.android.bundle` 不受 `shrinkResources` 影响，仍可整份替换后重打包。

---

解析逻辑源自 [jiuhunwl/short_videos](https://github.com/jiuhunwl/short_videos) 的 `api/douyin-cloudflare workers/workers.js`，原作者 JH-Ahua（www.jiuhunwl.cn）。License: MIT。
