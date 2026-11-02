/**
 * sv-proxy-android — React Native 入口
 *
 * 把原仓库两个 Worker 都搬进 index.android.bundle：
 *   src/resolve.js  抖音分享链接解析（解析 Worker 移植）
 *   src/proxy.js    直链 403 防盗链代理（cf_sv_proxy Worker 移植）
 *
 * App 内：粘贴分享文案 → 解析出无水印直链 → 交给本地网关代理 → 播放/下载
 * 网关侧：原生 HTTP 网关收到请求 → 事件交给 JS → 走完整代理逻辑 → 回写响应
 */

import {AppRegistry} from 'react-native';
import App from './src/App';
import {startProxyServer, stopProxyServer} from './src/server';

AppRegistry.registerComponent('svproxy', () => App);

/**
 * 暴露给原生/调试用的手动启停（App 启动后由 src/App.js 自动调用）
 * 在 RN 里也可通过 global 访问：global.__svProxy.start(8080)
 */
global.__svProxy = {
  start: (port) => startProxyServer(port),
  stop: () => stopProxyServer(),
};
