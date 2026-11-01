/**
 * 全局配置：与 worker.js 顶部常量一一对应
 * 改这里，重新 `npx react-native bundle` 打进 index.android.bundle 即可生效
 */

/** 桌面 UA，与 worker.js DESKTOP_UA 一致 */
export const DESKTOP_UA =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36';

/** 媒体拉取与 HEAD 探测超时（毫秒，每跳独立计时） */
export const REQUEST_TIMEOUT = 30000;
export const PROBE_TIMEOUT = 15000;

/** 302 手动跟随的最大跳数 */
export const MAX_REDIRECTS = 10;

/**
 * 目标域白名单（后缀匹配）。留空 = 不限制。
 * 建议填写：['douyinvod.com','douyinpic.com','snssdk.com','weibo.com','sinaimg.cn']
 */
export const ALLOWED_HOST_SUFFIXES = [];

/** 平台预设：防盗链 Referer 与下载文件名前缀 */
export const PLATFORM_PRESETS = {
  douyin: {referer: 'https://douyin.com/', label: 'douyin'},
  weibo: {referer: 'https://weibo.com/', label: 'weibo'},
};

/** 媒体预设：Accept、默认 Content-Type、下载附加头、缓存策略 */
export const MEDIA_PRESETS = {
  video: {
    accept: 'video/mp4,video/x-m4v,video/*;q=0.9,*/*;q=0.8',
    fallbackType: 'application/octet-stream',
    cache: null,
    downloadExtra: {
      'Content-Transfer-Encoding': 'binary',
      Expires: '0',
      'Cache-Control': 'must-revalidate',
      Pragma: 'public',
    },
  },
  image: {
    accept: 'image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8',
    fallbackType: 'image/jpeg',
    cache: 'public, max-age=86400',
    downloadExtra: {},
  },
};

/** CORS 全开放，与 worker.js CORS_HEADERS 一致 */
export const CORS_HEADERS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
  'Access-Control-Allow-Headers': 'Content-Type',
};

/** 本地网关默认监听端口 */
export const DEFAULT_PORT = 8080;

/** 单个请求等待 JS 决策的最大时长（毫秒），超时由网关直接返回 504 */
export const GATEWAY_TIMEOUT = 60000;
