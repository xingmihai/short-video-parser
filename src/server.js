/**
 * 本地 HTTP 网关：把原生送进来的请求交给 src/proxy.js 处理
 * 这一层等价于 Cloudflare 把请求路由到 Worker；在这里，"Worker"就是 index.android.bundle
 */

import {NativeEventEmitter, NativeModules} from 'react-native';
import {handleRequest} from './proxy';
import {DEFAULT_PORT} from './config';

const Native = NativeModules.SvProxyGateway;

let emitter = null;
let requestSub = null;
let started = false;

const listeners = new Set();

/** 订阅日志（App 界面用） */
export function onLog(fn) {
  listeners.add(fn);
  return () => listeners.delete(fn);
}

function log(text) {
  const line = `[${new Date().toLocaleTimeString()}] ${text}`;
  listeners.forEach((fn) => {
    try {
      fn(line);
    } catch (e) {}
  });
}

export async function startProxyServer(port = DEFAULT_PORT) {
  if (!Native) {
    log('原生模块缺失，无法启动');
    return null;
  }
  if (started) {
    log(`网关已在运行`);
    return null;
  }

  if (!emitter) {
    emitter = new NativeEventEmitter(Native);
    requestSub = emitter.addListener('SvProxyRequest', async (req) => {
      log(`${req.method} ${req.path}`);
      try {
        await handleRequest(req, log);
      } catch (e) {
        log('处理异常: ' + (e?.message || e));
        try {
          Native.respondText(req.requestId, 500, {'Content-Type': 'application/json'}, JSON.stringify({code: 500, msg: '内部错误'}));
        } catch (_) {}
      }
    });
  }

  try {
    const info = await Native.start(port);
    started = true;
    log(`网关已启动 http://${info.host}:${info.port}`);
    return info;
  } catch (e) {
    log('启动失败: ' + (e?.message || e));
    throw e;
  }
}

export async function stopProxyServer() {
  if (!Native) return;
  await Native.stop();
  started = false;
  log('网关已停止');
}

export async function gatewayInfo() {
  if (!Native) return {running: false, host: '127.0.0.1', port: DEFAULT_PORT};
  const running = await Native.isRunning();
  const host = await Native.getLocalIp();
  return {running, host, port: DEFAULT_PORT};
}
