/**
 * sv-proxy-android — React Native 入口
 *
 * 对应 Cloudflare Worker 版 cf_sv_proxy/worker.js 的 `export default { fetch }`。
 * 这里不是"收到 HTTP 请求再处理"，而是：
 *   原生 HTTP 网关（Kotlin）收到请求 → 通过事件把请求交给 JS → JS 走完整代理逻辑 → 回写响应
 * 逻辑全部在本文件与 src/ 下，最终被打进 index.android.bundle。
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
