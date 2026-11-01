/**
 * 工具函数：纯 JS 实现，不依赖 Hermes 是否提供 atob / TextDecoder / crypto.subtle
 */

const B64_CHARS =
  'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';

const B64_LOOKUP = (() => {
  const t = new Int16Array(256).fill(-1);
  for (let i = 0; i < B64_CHARS.length; i++) {
    t[B64_CHARS.charCodeAt(i)] = i;
  }
  // 兼容 URL 传输中 + 被转成空格的情况
  t[32] = 62;
  return t;
})();

/** Base64 → Uint8Array */
export function base64ToBytes(input) {
  const clean = String(input).replace(/[\s=]/g, '');
  const len = clean.length;
  const outLen = Math.floor((len * 3) / 4);
  const bytes = new Uint8Array(outLen);
  let p = 0;
  for (let i = 0; i < len; i += 4) {
    const c0 = B64_LOOKUP[clean.charCodeAt(i)];
    const c1 = i + 1 < len ? B64_LOOKUP[clean.charCodeAt(i + 1)] : -1;
    const c2 = i + 2 < len ? B64_LOOKUP[clean.charCodeAt(i + 2)] : -1;
    const c3 = i + 3 < len ? B64_LOOKUP[clean.charCodeAt(i + 3)] : -1;
    if (c0 < 0 || c1 < 0) break;
    const n = (c0 << 18) | (c1 << 12) | ((c2 & 63) << 6) | (c3 & 63);
    if (p < outLen) bytes[p++] = (n >> 16) & 255;
    if (c2 >= 0 && p < outLen) bytes[p++] = (n >> 8) & 255;
    if (c3 >= 0 && p < outLen) bytes[p++] = n & 255;
  }
  return bytes.subarray(0, p);
}

/** Uint8Array → UTF-8 字符串（手写解码，避免依赖 TextDecoder） */
export function utf8ToString(bytes) {
  let out = '';
  let i = 0;
  while (i < bytes.length) {
    const b = bytes[i++];
    if (b < 0x80) {
      out += String.fromCharCode(b);
    } else if (b < 0xe0) {
      out += String.fromCharCode(((b & 0x1f) << 6) | (bytes[i++] & 0x3f));
    } else if (b < 0xf0) {
      out += String.fromCharCode(
        ((b & 0x0f) << 12) | ((bytes[i++] & 0x3f) << 6) | (bytes[i++] & 0x3f),
      );
    } else {
      const cp =
        ((b & 0x07) << 18) |
        ((bytes[i++] & 0x3f) << 12) |
        ((bytes[i++] & 0x3f) << 6) |
        (bytes[i++] & 0x3f);
      const v = cp - 0x10000;
      out += String.fromCharCode(0xd800 + (v >> 10), 0xdc00 + (v & 0x3ff));
    }
  }
  return out;
}

/**
 * 解码 proxyurl 参数：
 * 1) 明文 http(s) URL 直接使用
 * 2) Base64 解码（先把空格还原为 +，兼容 URL 传输中被转成空格）
 * 解码失败返回 null（与 worker.js decodeProxyUrl 行为一致）
 */
export function decodeProxyUrl(raw) {
  if (!raw) return null;
  const text = String(raw).trim();
  if (/^https?:\/\//i.test(text)) {
    return text;
  }
  try {
    const bytes = base64ToBytes(text.replace(/\s/g, '+'));
    if (!bytes.length) return null;
    const decoded = utf8ToString(bytes);
    return /^https?:\/\//i.test(decoded) ? decoded : null;
  } catch (e) {
    return null;
  }
}

/** 白名单校验：空配置放行；否则按后缀匹配目标域名 */
export function isHostAllowed(targetUrl, suffixes) {
  if (!suffixes || !suffixes.length) return true;
  try {
    const m = String(targetUrl).match(/^https?:\/\/([^/:?#]+)/i);
    if (!m) return false;
    const host = m[1].toLowerCase();
    return suffixes.some(
      (s) => host === String(s).toLowerCase() || host.endsWith('.' + String(s).toLowerCase()),
    );
  } catch (e) {
    return false;
  }
}

/** 从 Content-Type 推断扩展名 */
export function extFromContentType(contentType, mediaKey, fallbackType) {
  const ct = (contentType || fallbackType || '').toLowerCase();
  if (mediaKey === 'image') {
    const sub = (ct.split('/')[1] || 'jpg').split(';')[0].trim();
    return sub || 'jpg';
  }
  return 'mp4';
}

/** 简单 key → 小写查找，用于 header 读取（HTTP header 大小写不敏感） */
export function headerGet(headers, name) {
  if (!headers) return null;
  const target = name.toLowerCase();
  for (const k of Object.keys(headers)) {
    if (k.toLowerCase() === target) return headers[k];
  }
  return null;
}
