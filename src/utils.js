/**
 * 纯 JS 小工具。
 *
 * headerGet：OkHttp 返回的响应头 key 大小写不固定，做一次不区分大小写的查找。
 */
export function headerGet(headers, name) {
  if (!headers) return null;
  const lower = String(name).toLowerCase();
  for (const k of Object.keys(headers)) {
    if (k.toLowerCase() === lower) return headers[k];
  }
  return null;
}
