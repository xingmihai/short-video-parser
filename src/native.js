/**
 * 原生桥接封装：所有能力由 android/app/src/main/java/com/svproxy/proxy 提供
 *
 * 原生只做三件"JS 干不了/干不好"的事：
 *   1. 监听 TCP 端口，收 HTTP 请求（HttpGateway.kt）
 *   2. 发上游请求但不读 body（UpstreamPool.kt，OkHttp，followRedirects=false）
 *   3. 把上游响应流 pipe 回客户端 socket（零拷贝、不落盘、不占 JS 内存）
 * 其余所有决策逻辑都在 JS 里，即都在 index.android.bundle 里。
 */

import {NativeModules, NativeEventEmitter} from 'react-native';

const Native = NativeModules.SvProxyGateway;

if (!Native) {
  console.warn('[sv-proxy] 原生模块 SvProxyGateway 未找到，请确认已编译进 APK');
}

export const Gateway = {
  /** 启动本地网关：Promise<{port, host}> */
  start: (port) => (Native ? Native.start(port) : Promise.reject(new Error('no native'))),
  /** 停止网关 */
  stop: () => (Native ? Native.stop() : Promise.resolve()),
  /** 是否运行中 */
  isRunning: () => (Native ? Native.isRunning() : Promise.resolve(false)),
  /** 局域网 IP，用于展示访问地址 */
  getLocalIp: () => (Native ? Native.getLocalIp() : Promise.resolve('127.0.0.1')),

  /**
   * 发上游请求，只拿状态行与响应头，不读 body
   * @returns Promise<{handleId: string, status: number, headers: object}>
   */
  openUpstream: (url, method, headers, timeoutMs) =>
    Native
      ? Native.openUpstream(url, method || 'GET', headers || {}, timeoutMs || 30000)
      : Promise.reject(new Error('no native')),

  /** 把 handleId 对应的上游响应流，以指定状态/头回写给客户端 */
  pipeToClient: (requestId, status, headers, handleId) =>
    Native && Native.pipeToClient(requestId, status, headers || {}, handleId),

  /** 直接回写小响应体（JSON 错误、204 预检等），body 为字符串 */
  respondText: (requestId, status, headers, body) =>
    Native && Native.respondText(requestId, status, headers || {}, body || ''),

  /** 释放上游 handle（探测用、或请求失败时） */
  closeHandle: (handleId) => Native && Native.closeHandle(handleId),

  /** SHA-256（原生 MessageDigest），用于生成下载文件名 */
  sha256: (text) => (Native ? Native.sha256(text) : Promise.resolve(String(Date.now()))),
};

/** 请求事件与状态事件监听 */
export function createGatewayEmitter() {
  if (!Native) return null;
  return new NativeEventEmitter(Native);
}
