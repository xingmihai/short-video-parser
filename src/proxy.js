/**
 * 核心代理逻辑 —— worker.js 的 Android 版移植
 *
 * 与 Cloudflare Worker 版的差异只有两处：
 *   1. 请求来源：Worker 由 CF 边缘触发，这里由原生 HttpGateway 通过事件送进来
 *   2. 回传方式：Worker 直接 return new Response(stream)，
 *      这里把上游响应流的 handleId 交给原生 pipe 回客户端（等价的流式转发，不落盘）
 * 参数、Referer 注入、302 手动跟随、白名单、错误码 全部保持一致。
 */

import {
  DESKTOP_UA,
  REQUEST_TIMEOUT,
  PROBE_TIMEOUT,
  MAX_REDIRECTS,
  ALLOWED_HOST_SUFFIXES,
  PLATFORM_PRESETS,
  MEDIA_PRESETS,
  CORS_HEADERS,
} from './config';
import {decodeProxyUrl, isHostAllowed, extFromContentType, headerGet} from './utils';
import {Gateway} from './native';

/**
 * 处理一个网关请求
 * @param {object} req {requestId, method, path, query, headers}
 * @param {function} log 日志回调
 */
export async function handleRequest(req, log = () => {}) {
  const {requestId} = req;

  // CORS 预检
  if (req.method === 'OPTIONS') {
    Gateway.respondText(requestId, 204, CORS_HEADERS, '');
    return;
  }
  if (req.method !== 'GET') {
    return json(requestId, 405, {code: 405, msg: '仅支持 GET 请求'});
  }

  const query = req.query || {};
  const proxyurl = String(query.proxyurl || '').trim();
  if (!proxyurl) {
    return json(requestId, 400, {code: 400, msg: 'proxyurl参数不能为空'});
  }

  const platformKey = query.type === 'weibo' ? 'weibo' : 'douyin';
  const download = query.download || 'true';

  const decodedUrl = decodeProxyUrl(proxyurl);
  if (!decodedUrl) {
    return json(requestId, 400, {
      code: 400,
      msg: 'URL解码失败',
      original_url: proxyurl,
    });
  }

  let mediaKey = query.mediatype;
  if (mediaKey !== 'image' && mediaKey !== 'video') {
    mediaKey = await getMediaType(decodedUrl, platformKey, log);
  }

  await proxyMedia(requestId, decodedUrl, platformKey, mediaKey, download, log);
}

/** 统一 JSON 输出 */
function json(requestId, status, payload) {
  Gateway.respondText(requestId, status, {
    'Content-Type': 'application/json; charset=utf-8',
    ...CORS_HEADERS,
  }, JSON.stringify(payload));
  return {status, payload};
}

/** 构造上游请求头（与 worker.js requestHeaders 一致） */
function buildRequestHeaders(platformKey, mediaKey, accept) {
  return {
    'User-Agent': DESKTOP_UA,
    Accept: accept,
    'Accept-Language': 'zh-CN,zh;q=0.9',
    Connection: 'keep-alive',
    Referer: PLATFORM_PRESETS[platformKey].referer,
  };
}

/**
 * HEAD 探测上游 Content-Type，自动区分视频/图片；失败默认按视频处理
 */
async function getMediaType(url, platformKey, log) {
  const headers = buildRequestHeaders(platformKey, 'video', '*/*');
  try {
    const res = await Gateway.openUpstream(url, 'HEAD', headers, PROBE_TIMEOUT);
    const contentType = String(headerGet(res.headers, 'content-type') || '').toLowerCase();
    Gateway.closeHandle(res.handleId);
    if (
      contentType.startsWith('image/') ||
      contentType.includes('image') ||
      contentType.includes('picture')
    ) {
      return 'image';
    }
    return 'video';
  } catch (e) {
    log('探测媒体类型失败: ' + (e?.message || e));
    return 'video';
  }
}

/**
 * 代理主流程：校验 → 手动跟随 302（每跳保持 Referer/UA）→ 流式回传
 */
async function proxyMedia(requestId, targetUrl, platformKey, mediaKey, download, log) {
  const platform = PLATFORM_PRESETS[platformKey];
  const media = MEDIA_PRESETS[mediaKey];

  if (!/^https?:\/\//i.test(targetUrl)) {
    return json(requestId, 400, {code: 400, msg: '无效的媒体URL'});
  }
  if (!isHostAllowed(targetUrl, ALLOWED_HOST_SUFFIXES)) {
    return json(requestId, 403, {code: 403, msg: '目标域名不在白名单内'});
  }

  const requestHeaders = buildRequestHeaders(platformKey, mediaKey, media.accept);

  try {
    let current = targetUrl;
    let res = await Gateway.openUpstream(current, 'GET', requestHeaders, REQUEST_TIMEOUT);

    // 手动跟随 302：标准 follow 模式跨域会丢 Referer，防盗链场景必须手动跟
    let redirects = 0;
    while (res.status >= 300 && res.status < 400 && redirects < MAX_REDIRECTS) {
      const location = headerGet(res.headers, 'location');
      Gateway.closeHandle(res.handleId);
      if (!location) break;
      try {
        current = new URL(location, current).toString();
      } catch (e) {
        current = location;
      }
      log(`跟随 302 → ${redirects + 1} 跳`);
      res = await Gateway.openUpstream(current, 'GET', requestHeaders, REQUEST_TIMEOUT);
      redirects++;
    }

    if (res.status !== 200) {
      Gateway.closeHandle(res.handleId);
      let msg = `请求失败，HTTP状态码: ${res.status}`;
      if (res.status === 403) {
        msg += '（可能需要更新Cookie或Referer头信息）';
      }
      return json(requestId, res.status, {code: res.status, msg});
    }

    const contentType = headerGet(res.headers, 'content-type') || media.fallbackType;
    const ext = extFromContentType(contentType, mediaKey, media.fallbackType);

    const responseHeaders = {
      'Content-Type': contentType,
      ...CORS_HEADERS,
      'X-Proxy': `${platform.label}-${mediaKey}-proxy`,
      ...(media.cache ? {'Cache-Control': media.cache} : {}),
    };

    if (download !== 'false') {
      const hash = await Gateway.sha256(targetUrl);
      const filename = `${platform.label}_${mediaKey}_${String(hash).slice(0, 16)}.${ext}`;
      responseHeaders['Content-Disposition'] = `attachment; filename="${filename}"`;
      Object.assign(responseHeaders, media.downloadExtra);
    }

    // 流式转发：不落盘、不进 JS 内存，不受响应体大小限制
    Gateway.pipeToClient(requestId, 200, responseHeaders, res.handleId);
    return {status: 200, streaming: true};
  } catch (e) {
    const reason = e?.message && /timeout|超时/i.test(e.message) ? '请求超时' : (e?.message || '未知错误');
    return json(requestId, 500, {code: 500, msg: '请求失败', error: reason});
  }
}
