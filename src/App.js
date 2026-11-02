/**
 * App 主界面：在软件里粘贴抖音分享链接 → 解析 → 播放/下载
 *
 * 链路（全部在 index.android.bundle 内）：
 *   src/resolve.js   解析分享短链，拿无水印直链
 *   ↓
 *   src/proxy.js     本地网关代理，注入 Referer 解决 403 防盗链
 *   ↓
 *   系统播放器 / 浏览器 / Image 预览
 */
import React, {useEffect, useRef, useState} from 'react';
import {
  ActivityIndicator,
  Image,
  Linking,
  SafeAreaView,
  ScrollView,
  StatusBar,
  StyleSheet,
  Text,
  TextInput,
  TouchableOpacity,
  View,
} from 'react-native';
import {gatewayInfo, onLog, startProxyServer} from './server';
import {DEFAULT_PORT} from './config';
import {parseShareText} from './resolve';
import {toBase64} from './utils';
import {Gateway} from './native';

/** App 内访问本机网关用 127.0.0.1 最稳；局域网地址只用于展示给其他设备 */
const LOCAL_BASE = `http://127.0.0.1:${DEFAULT_PORT}`;

/**
 * 把直链包成本地代理地址。
 * proxyurl 用 Base64 + encodeURIComponent，避免 & ? + 被查询串截断。
 */
function buildProxy(raw, mediatype, download = false) {
  if (!raw) return '';
  let u =
    `${LOCAL_BASE}/?proxyurl=${encodeURIComponent(toBase64(raw))}` +
    `&type=douyin&download=${download ? 'true' : 'false'}`;
  if (mediatype) u += `&mediatype=${mediatype}`;
  return u;
}

const TYPE_LABEL = {
  video: '视频',
  image: '图集',
  live: '实况',
  unknown: '未知',
};

export default function App() {
  const [input, setInput] = useState('');
  const [running, setRunning] = useState(false);
  const [lanUrl, setLanUrl] = useState(`http://127.0.0.1:${DEFAULT_PORT}`);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [result, setResult] = useState(null);
  const [logs, setLogs] = useState([]);
  const [toast, setToast] = useState('');
  const scrollRef = useRef(null);

  useEffect(() => {
    const off = onLog((line) => setLogs((prev) => [...prev.slice(-99), line]));
    (async () => {
      const info = await gatewayInfo();
      setLanUrl(`http://${info.host}:${DEFAULT_PORT}`);
      const started = await startProxyServer(DEFAULT_PORT);
      if (started) {
        setLanUrl(`http://${started.host}:${DEFAULT_PORT}`);
        setRunning(true);
      }
    })();
    return off;
  }, []);

  useEffect(() => {
    scrollRef.current?.scrollToEnd({animated: true});
  }, [logs]);

  const flash = (msg) => {
    setToast(msg);
    setTimeout(() => setToast(''), 1800);
  };

  const doParse = async (text) => {
    const value = (text ?? input).trim();
    if (!value) {
      setError('请先粘贴抖音分享链接或整段分享文案');
      return;
    }
    setInput(value);
    setLoading(true);
    setError('');
    setResult(null);
    try {
      const r = await parseShareText(value);
      if (r.code !== 200 || !r.data) {
        setError(`[${r.code}] ${r.msg}`);
      } else {
        setResult(r.data);
      }
    } catch (e) {
      setError('解析异常：' + (e?.message || String(e)));
    } finally {
      setLoading(false);
    }
  };

  const pasteAndParse = async () => {
    const text = await Gateway.readText();
    if (!text) {
      flash('剪贴板是空的');
      return;
    }
    await doParse(text);
  };

  const copy = async (text, label) => {
    if (!text) return;
    await Gateway.copyText(text);
    flash(label + '已复制');
  };

  const open = async (url) => {
    if (!url) return;
    try {
      await Linking.openURL(url);
    } catch (e) {
      flash('无法打开：' + (e?.message || e));
    }
  };

  const coverProxy = result?.cover ? buildProxy(result.cover, 'image') : '';
  const videoProxy = result?.url ? buildProxy(result.url, 'video') : '';
  const videoDownload = result?.url ? buildProxy(result.url, 'video', true) : '';
  const images = result?.images || [];
  const backups = result?.video_backup || [];
  const livePhotos = result?.live_photo || [];

  return (
    <SafeAreaView style={s.root}>
      <StatusBar barStyle="light-content" />
      <ScrollView contentContainerStyle={s.container} keyboardShouldPersistTaps="handled">
        <Text style={s.title}>短视频解析 · 代理</Text>
        <Text style={s.sub}>粘贴抖音分享链接，自动出无水印直链</Text>

        <View style={[s.badge, running ? s.badgeOn : s.badgeOff]}>
          <Text style={s.badgeText}>
            {running ? '● 网关运行中' : '○ 网关未启动'}  ·  {lanUrl}
          </Text>
        </View>

        <View style={s.card}>
          <Text style={s.label}>抖音分享链接 / 整段文案</Text>
          <TextInput
            style={s.input}
            multiline
            numberOfLines={4}
            placeholder="7.79 复制打开抖音，看看 https://v.douyin.com/xxxx/ ..."
            placeholderTextColor="#5b6472"
            value={input}
            onChangeText={setInput}
            autoCapitalize="none"
            autoCorrect={false}
          />
          <View style={s.btnRow}>
            <TouchableOpacity style={[s.btn, s.btnGhost]} onPress={pasteAndParse}>
              <Text style={s.btnGhostText}>粘贴并解析</Text>
            </TouchableOpacity>
            <TouchableOpacity
              style={[s.btn, s.btnPrimary]}
              onPress={() => doParse()}
              disabled={loading}>
              {loading ? (
                <ActivityIndicator color="#fff" size="small" />
              ) : (
                <Text style={s.btnText}>解 析</Text>
              )}
            </TouchableOpacity>
          </View>
          <Text style={s.tip}>支持分享口令里的链接、短链 v.douyin.com、网页版链接</Text>
        </View>

        {error ? (
          <View style={s.errBox}>
            <Text style={s.errText}>{error}</Text>
          </View>
        ) : null}

        {result ? (
          <View style={s.card}>
            <View style={s.metaRow}>
              <Text style={s.typeTag}>{TYPE_LABEL[result.type] || result.type}</Text>
              {result.quality ? <Text style={s.meta}>{result.quality}</Text> : null}
              {result.duration ? <Text style={s.meta}>{result.duration}s</Text> : null}
              {result.size_label ? <Text style={s.meta}>{result.size_label}</Text> : null}
            </View>

            {result.title ? <Text style={s.resultTitle} numberOfLines={2}>{result.title}</Text> : null}
            {result.author?.name ? (
              <Text style={s.author}>@{result.author.name}</Text>
            ) : null}
            {result.publish_time ? <Text style={s.meta}>{result.publish_time}</Text> : null}

            {coverProxy ? (
              <Image
                style={s.cover}
                source={{uri: coverProxy}}
                resizeMode="cover"
              />
            ) : null}

            {result.url ? (
              <>
                <Text style={s.label}>无水印直链（已走本地代理）</Text>
                <Text style={s.link} numberOfLines={2}>{videoProxy}</Text>
                <View style={s.btnRow}>
                  <TouchableOpacity style={[s.btn, s.btnPrimary]} onPress={() => open(videoProxy)}>
                    <Text style={s.btnText}>播放</Text>
                  </TouchableOpacity>
                  <TouchableOpacity style={[s.btn, s.btnGhost]} onPress={() => open(videoDownload)}>
                    <Text style={s.btnGhostText}>下载</Text>
                  </TouchableOpacity>
                </View>
                <View style={s.btnRow}>
                  <TouchableOpacity style={[s.btn, s.btnSmall]} onPress={() => copy(result.url, '直链')}>
                    <Text style={s.smallText}>复制直链</Text>
                  </TouchableOpacity>
                  <TouchableOpacity style={[s.btn, s.btnSmall]} onPress={() => copy(videoProxy, '代理链接')}>
                    <Text style={s.smallText}>复制代理链接</Text>
                  </TouchableOpacity>
                </View>
              </>
            ) : null}

            {images.length ? (
              <>
                <Text style={s.label}>图集 {images.length} 张（点击下载）</Text>
                <ScrollView horizontal style={s.thumbs}>
                  {images.map((u, i) => (
                    <TouchableOpacity key={i} onPress={() => open(buildProxy(u, 'image', true))}>
                      <Image style={s.thumb} source={{uri: buildProxy(u, 'image')}} />
                    </TouchableOpacity>
                  ))}
                </ScrollView>
              </>
            ) : null}

            {livePhotos.length ? (
              <Text style={s.tip}>实况 Live Photo：{livePhotos.length} 组（图片+视频）</Text>
            ) : null}

            {backups.length ? (
              <View style={s.backupBox}>
                <Text style={s.label}>备选直链 {backups.length} 条</Text>
                {backups.slice(0, 3).map((u, i) => (
                  <TouchableOpacity key={i} onPress={() => copy(u, '备选直链')}>
                    <Text style={s.backup} numberOfLines={1}>{u}</Text>
                  </TouchableOpacity>
                ))}
              </View>
            ) : null}

            {result.music?.url ? (
              <TouchableOpacity style={s.musicRow} onPress={() => open(buildProxy(result.music.url, 'video', true))}>
                <Text style={s.music} numberOfLines={1}>♪ {result.music.title || '背景音乐'} · 点击下载</Text>
              </TouchableOpacity>
            ) : null}
          </View>
        ) : null}

        <View style={s.logCard}>
          <Text style={s.label}>运行日志</Text>
          <ScrollView ref={scrollRef} style={s.logBox} nestedScrollEnabled>
            {logs.map((l, i) => (
              <Text key={i} style={s.logLine}>{l}</Text>
            ))}
          </ScrollView>
        </View>

        <Text style={s.foot}>
          解析逻辑源自 jiuhunwl/short_videos · 抖音解析 Workers 版，MIT
        </Text>
      </ScrollView>

      {toast ? (
        <View style={s.toast}>
          <Text style={s.toastText}>{toast}</Text>
        </View>
      ) : null}
    </SafeAreaView>
  );
}

const s = StyleSheet.create({
  root: {flex: 1, backgroundColor: '#0f1115'},
  container: {padding: 16, paddingBottom: 32},
  title: {color: '#fff', fontSize: 21, fontWeight: '700'},
  sub: {color: '#8b93a1', fontSize: 13, marginTop: 3, marginBottom: 12},
  badge: {padding: 8, borderRadius: 8, marginBottom: 12, borderWidth: 1},
  badgeOn: {backgroundColor: '#0d2b1a', borderColor: '#166534'},
  badgeOff: {backgroundColor: '#2b1414', borderColor: '#7f1d1d'},
  badgeText: {color: '#9ca3af', fontSize: 11, fontFamily: 'monospace'},
  card: {
    backgroundColor: '#171b22', borderRadius: 10, padding: 14, marginBottom: 12,
    borderWidth: 1, borderColor: '#2a3038',
  },
  label: {color: '#8b93a1', fontSize: 12, marginBottom: 8},
  input: {
    backgroundColor: '#0f1115', borderWidth: 1, borderColor: '#2a3038',
    borderRadius: 8, color: '#e6e8ec', fontSize: 13, padding: 10,
    minHeight: 84, textAlignVertical: 'top', marginBottom: 12,
  },
  btnRow: {flexDirection: 'row', gap: 8, marginBottom: 8},
  btn: {flex: 1, padding: 12, borderRadius: 8, alignItems: 'center', justifyContent: 'center'},
  btnPrimary: {backgroundColor: '#2563eb'},
  btnGhost: {backgroundColor: '#1f2937', borderWidth: 1, borderColor: '#374151'},
  btnSmall: {backgroundColor: '#111827', borderWidth: 1, borderColor: '#2a3038', padding: 9},
  btnText: {color: '#fff', fontSize: 14, fontWeight: '600'},
  btnGhostText: {color: '#d1d5db', fontSize: 14, fontWeight: '600'},
  smallText: {color: '#9ca3af', fontSize: 12},
  tip: {color: '#6b7280', fontSize: 11, marginTop: 4, lineHeight: 16},
  errBox: {backgroundColor: '#2b1414', borderRadius: 8, padding: 12, marginBottom: 12},
  errText: {color: '#f87171', fontSize: 13, lineHeight: 19},
  metaRow: {flexDirection: 'row', alignItems: 'center', flexWrap: 'wrap', gap: 8, marginBottom: 8},
  typeTag: {
    color: '#fff', backgroundColor: '#2563eb', fontSize: 11,
    paddingHorizontal: 8, paddingVertical: 3, borderRadius: 5, overflow: 'hidden',
  },
  meta: {color: '#9ca3af', fontSize: 11},
  resultTitle: {color: '#e6e8ec', fontSize: 15, fontWeight: '600', lineHeight: 21, marginBottom: 4},
  author: {color: '#60a5fa', fontSize: 12, marginBottom: 6},
  cover: {width: '100%', height: 190, borderRadius: 8, backgroundColor: '#000', marginBottom: 12},
  link: {color: '#a5b4fc', fontSize: 10, fontFamily: 'monospace', lineHeight: 15, marginBottom: 10},
  thumbs: {marginBottom: 10},
  thumb: {width: 76, height: 100, borderRadius: 6, marginRight: 8, backgroundColor: '#000'},
  backupBox: {marginTop: 8, paddingTop: 10, borderTopWidth: 1, borderTopColor: '#2a3038'},
  backup: {color: '#6b7280', fontSize: 10, fontFamily: 'monospace', marginBottom: 5},
  musicRow: {marginTop: 10, padding: 10, backgroundColor: '#0f1115', borderRadius: 8},
  music: {color: '#a5b4fc', fontSize: 12},
  logCard: {backgroundColor: '#171b22', borderRadius: 10, padding: 12, borderWidth: 1, borderColor: '#2a3038'},
  logBox: {height: 130, backgroundColor: '#000', borderRadius: 6, padding: 8},
  logLine: {color: '#9ca3af', fontSize: 10, fontFamily: 'monospace', marginBottom: 2},
  foot: {color: '#4b5563', fontSize: 10, textAlign: 'center', marginTop: 14},
  toast: {
    position: 'absolute', bottom: 28, left: 0, right: 0, alignItems: 'center',
  },
  toastText: {
    color: '#fff', backgroundColor: '#374151', paddingHorizontal: 16,
    paddingVertical: 9, borderRadius: 20, fontSize: 12, overflow: 'hidden',
  },
});
