/**
 * 原生桥接封装。
 *
 * 本地 HTTP 网关已移除 —— 不监听端口、不转发请求、没有局域网地址。
 * 原生只提供三件 JS 干不了的事：
 *   1. 发解析请求并读回文本（OkHttp，可手动跟 302，不受浏览器限制）
 *   2. 带 Referer 下载媒体直链到私有目录并调起系统播放器
 *   3. 剪贴板读写（一键粘贴抖音分享文案）
 * 其余业务逻辑与界面全在 index.android.bundle 里。
 */

import {NativeModules} from 'react-native';

const Native = NativeModules.SvProxyGateway;

if (!Native) {
  console.warn('[sv] 原生模块 SvProxyGateway 未找到，请确认已编译进 APK');
}

export const Gateway = {
  /**
   * 解析用请求
   * @returns Promise<{status: number, headers: object, body: string}>
   */
  requestText: (url, method, headers, timeoutMs, followRedirects) =>
    Native
      ? Native.requestText(
          url,
          method || 'GET',
          headers || {},
          timeoutMs || 20000,
          followRedirects !== false,
        )
      : Promise.reject(new Error('no native')),

  /** 带 Referer 下载并调起系统播放器/图片查看器 */
  download: (url, referer, fileName, mime) =>
    Native
      ? Native.download(url, referer || '', fileName || 'sv', mime || 'video/mp4')
      : Promise.reject(new Error('no native')),

  /** 复制直链 */
  copyText: (text) => (Native ? Native.copyText(text) : Promise.resolve(false)),

  /** 读取剪贴板 */
  readText: () => (Native ? Native.readText() : Promise.resolve('')),
};
