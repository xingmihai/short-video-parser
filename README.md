# sv-proxy-android

把 Cloudflare Worker [`cf_sv_proxy/worker.js`](https://github.com/jiuhunwl/short_videos/blob/main/cf_sv_proxy/worker.js) **搬进 Android App**：短视频（抖音/微博）直链 403 防盗链代理，核心逻辑写在 **JS 层，打进 `index.android.bundle`**，不用服务器、不用 Cloudflare 账号。

App 启动后在本机 `8080` 端口起一个 HTTP 网关，手机自己或同一局域网内的设备直接访问：

```
http://手机IP:8080/?proxyurl=<直链Base64>&type=douyin&mediatype=video&download=false
```

---

## 它和 Worker 版的对应关系

| Cloudflare Worker 版 | 本 Android 版 |
|---|---|
| CF 边缘节点收到 HTTP 请求 | `HttpGateway.kt` 监听 8080，收到请求 |
| `export default { fetch(request) }` | `sv-proxy` 事件 → `src/proxy.js` 的 `handleRequest()` |
| `fetch` + `redirect: "manual"` 手动跟 302 | `UpstreamPool.kt` 发请求（关掉自动跟随），**跟随策略由 JS 循环控制** |
| `return new Response(stream)` | JS 把上游 body 的 `handleId` 交回原生，`pipeStream` 流式写回 socket |
| `crypto.subtle` / `atob` | 原生 `sha256` / `src/utils.js` 手写 Base64 解码 |

**边界很清楚**：原生只干三件 JS 干不了的活——监听端口、发上游请求但不读 body、把流 pipe 回 socket。参数解析、URL 解码、白名单、Referer 注入、302 跟随、错误码，**全在 JS 里**，也就是全在 `index.android.bundle` 里。改逻辑只需改 JS 重新打包，不用动 Kotlin。

---

## 跑起来

```bash
git clone https://github.com/xingmihai/sv-proxy-android.git
cd sv-proxy-android
npm install
npx react-native run-android      # 手机/模拟器连着 adb
```

App 打开即自动启动网关，首页显示 `http://192.168.x.x:8080`。

### 只重新打 bundle（不重编译原生）

```bash
npm run bundle            # Hermes 字节码版
npm run bundle plain      # 明文 JS 版（可文本编辑）
```

`android/gradle.properties` 默认 `hermesEnabled=true`（RN 0.76 官方路径）。**不要改成 `false`**：旧架构 + JSC 会加载已不存在的 `libjscexecutor.so`，启动必崩。

关于「替换 bundle」：Gradle 打包时会用 `hermesc` 把 APK 内的 bundle 编译成字节码，但 Release 里附带的是**明文 JS 版** `index.android.bundle`。把明文替换进 APK 后，Hermes 会以解释模式执行它，功能一致、启动略慢，仍可直接文本修改。

---

## CI 自动出安装包

仓库内置 `.github/workflows/build-apk.yml`：

- `push` 到 `main` / PR / 手动触发 → 自动构建
- 打 `v*` 标签 → 额外发布 GitHub Release，附可直接安装的 APK
- 流程：`npm ci` → 生成 `index.android.bundle`（明文、已 minify）→ `./gradlew assembleRelease` → 打包上传

### 压缩策略（Release 默认全开）

| 层 | 开关 | 效果 |
|---|---|---|
| JS bundle | `--dev false --minify true` | 压缩成单行、变量短名、去注释 |
| Java/Kotlin | `minifyEnabled true` + `proguard-android-optimize.txt` | R8 代码压缩、优化、混淆 |
| 资源 | `shrinkResources true` | 移除未引用的 res 资源 |
| ABI 裁剪 | `abiFilters 'arm64-v8a','armeabi-v7a'` | 去掉 x86/x86_64 冗余 .so，替代 deflate 压缩的体积收益 |
| 分发 | `zip -9` | 三件套打成 `sv-proxy-android-release.zip` |

**native .so 必须保持未压缩**：`useLegacyPackaging = false` + `extractNativeLibs="false"`。
若改成 `true` 把 .so deflate 压进 APK，系统/SoLoader 无法从 `base.apk!/lib/<abi>/` 直接 dlopen。

**JS 引擎必须是 Hermes**（`hermesEnabled=true`）。设成 JSC 时，旧架构的 `JSCExecutor` 会 `System.loadLibrary("jscexecutor")`，而 RN 0.76 的 `react-android` AAR 已不含 `libjscexecutor.so`，启动即 `UnsatisfiedLinkError` 闪退。

CI 有 `scripts/check-apk-libs.py` 校验：打印 APK 内全部 .so，断言均为 Stored、存在 JS 引擎库、含 arm64-v8a；若走 JSC 还额外要求 `libjscexecutor.so`。从构建侧堵住这类回归。

`assets/` 下的 `index.android.bundle` 不受 `shrinkResources` 影响，仍可整份替换后重打包。
配套 keep 规则在 `android/app/proguard-rules.pro`，保护 RN 反射、`@ReactMethod` 与自定义原生模块不被裁掉。

产物（Actions → Artifacts，或 Releases 页下载）：

| 文件 | 说明 |
|---|---|
| `sv-proxy-android.apk` | 已签名的 Release 包，直接装 |
| `index.android.bundle` | 明文 JS（已 minify），可自行替换后重打包 |
| `index.android.bundle.map` | sourcemap，定位源码用 |
| `sv-proxy-android-release.zip` | 三件套打包，deflate -9 |

手动触发：仓库 **Actions → 构建 Android 安装包 → Run workflow**，勾选「同时发布 GitHub Release」即可生成带 APK 直链的版本。

---

## 接口

和 Worker 版**完全一致**，改个域名就能平替：

```plaintext
GET http://手机IP:8080/?proxyurl={媒体直链}&type={平台}&mediatype={媒体类型}&download={是否下载}
```

| 参数 | 必填 | 说明 |
|------|:---:|------|
| `proxyurl` | ✅ | 媒体直链，支持 **Base64**（推荐）或明文 http(s) |
| `type` | ❌ | `douyin`（默认）/ `weibo`，决定 Referer 与文件名前缀 |
| `mediatype` | ❌ | `video` / `image`；不传自动 HEAD 探测 |
| `download` | ❌ | `true`（默认）触发下载；`false` 内联播放/显示 |

响应头：`Content-Type` 透传、`X-Proxy: douyin-video-proxy`、`Content-Disposition`（download 时）、CORS 全开放。
失败返回 JSON：`{"code":403,"msg":"请求失败，HTTP状态码: 403（可能需要更新Cookie或Referer头信息）"}`

```bash
# 手机同一 WiFi 下，电脑直接请求
curl -L -o v.mp4 "http://192.168.1.20:8080/?proxyurl=aHR0cHM6Ly9hd2VtZS5zbnNzZGsuY29tLy4uLnw&download=false"

# 浏览器直接打开就是播放；<img src="..."> 也能直接引用
```

---

## 目录

```
index.js                 入口，注册 App + 全局 __svProxy
src/
  config.js              常量（UA、超时、白名单、平台/媒体预设）
  utils.js               Base64 解码、白名单校验、扩展名推断
  native.js              原生桥接封装
  proxy.js               ★ 代理核心，worker.js 的移植
  server.js              事件路由：原生请求 → handleRequest
  App.js                 控制台界面（地址、启停、日志）
android/app/src/main/java/com/svproxy/
  MainActivity.kt / MainApplication.kt
  proxy/HttpGateway.kt   极简 HTTP/1.1 服务器
  proxy/UpstreamPool.kt  OkHttp 上游请求池（不读 body）
  proxy/SvProxyModule.kt JS 可见的 SvProxyGateway
```

## 改配置

改 `src/config.js`：

| 常量 | 默认 | 说明 |
|------|------|------|
| `ALLOWED_HOST_SUFFIXES` | `[]` | 目标域白名单，建议填 `['douyinvod.com','douyinpic.com','snssdk.com','weibo.com','sinaimg.cn']` |
| `REQUEST_TIMEOUT` | `30000` | 拉取超时（302 每跳独立计时） |
| `MAX_REDIRECTS` | `10` | 302 最大跟随跳数 |
| `DEFAULT_PORT` | `8080` | 网关端口 |

---

## 几点使用提示

- 保持 App 在前台（或加进系统电池白名单），后台被回收后网关就停了。
- 直链带 `x-expires`，过期返回 404，实时解析即时代理，别缓存直链。
- 大视频走流式 pipe，不进 JS 内存，几百 MB 也没问题。

---

代码逻辑源自 [jiuhunwl/short_videos](https://github.com/jiuhunwl/short_videos) 的 `cf_sv_proxy/worker.js`，原作者 JH-Ahua。License: MIT。
