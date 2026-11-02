/**
 * App 主界面：粘贴抖音分享链接 → 解析 → 播放 / 下载
 *
 * 无本地网关、无 8080 端口、无局域网地址。
 * 链路全部在 index.android.bundle 内：
 *   src/resolve.js  解析分享短链，拿无水印直链
 *   ↓
 *   Image 带 Referer 头直接加载封面/图集
 *   播放：系统播放器打开直链
 *   下载：原生 OkHttp 带 Referer 下载到私有目录并调起系统播放器
 */
import React, {useState} from 'react';
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
import {parseShareText} from './resolve';
import {Gateway} from './native';

/** 抖音 CDN 防盗链校验的 Referer */
const REFERER = 'https://www.douyin.com/';
const IMG_HEADERS = {Referer: REFERER, 'User-Agent': 'Mozilla/5.0'};

const TYPE_LABEL = {
  video: '视频',
  image: '图集',
  live: '实况',
  unknown: '未知',
};

function safeName(title, fallback) {
  const base = (title || '').replace(/[\\/:*?"<>|\r\n#]/g, '_').trim().slice(0, 40);
  return base || fallback || 'sv-video';
}

export default function App() {
  const [input, setInput] = useState('');
  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [result, setResult] = useState(null);
  const [toast, setToast] = useState('');

  const flash = (msg) => {
    setToast(msg);
    setTimeout(() => setToast(''), 2000);
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

  /** 播放：把直链交给系统播放器/浏览器 */
  const play = async (url) => {
    if (!url) return;
    try {
      await Linking.openURL(url);
    } catch (e) {
      flash('无法打开，试试「下载」');
    }
  };

  /** 下载：原生带 Referer 拉取，完成后调起系统播放器 */
  const save = async (url, name, mime) => {
    if (!url || busy) return;
    setBusy(true);
    try {
      const r = await Gateway.download(url, REFERER, name, mime);
      flash(`已保存 ${(r.size / 1048576).toFixed(1)} MB，正在打开`);
    } catch (e) {
      flash('下载失败：' + (e?.message || String(e)));
    } finally {
      setBusy(false);
    }
  };

  const images = result?.images || [];
  const backups = result?.video_backup || [];
  const livePhotos = result?.live_photo || [];
  const videoName = safeName(result?.title, result?.video_id);

  return (
    <SafeAreaView style={s.root}>
      <StatusBar barStyle="light-content" />
      <ScrollView contentContainerStyle={s.container} keyboardShouldPersistTaps="handled">
        <Text style={s.title}>短视频解析</Text>
        <Text style={s.sub}>粘贴抖音分享链接，直接出无水印直链</Text>

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
          <Text style={s.tip}>支持分享口令、v.douyin.com 短链、网页版链接；视频 / 图集 / 实况</Text>
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

            {result.title ? (
              <Text style={s.resultTitle} numberOfLines={2}>{result.title}</Text>
            ) : null}
            {result.author?.name ? (
              <Text style={s.author}>@{result.author.name}</Text>
            ) : null}
            {result.publish_time ? <Text style={s.meta}>{result.publish_time}</Text> : null}

            {result.cover ? (
              <Image
                style={s.cover}
                source={{uri: result.cover, headers: IMG_HEADERS}}
                resizeMode="cover"
              />
            ) : null}

            {result.url ? (
              <>
                <Text style={s.label}>无水印直链</Text>
                <Text style={s.link} numberOfLines={3}>{result.url}</Text>
                <View style={s.btnRow}>
                  <TouchableOpacity style={[s.btn, s.btnPrimary]} onPress={() => play(result.url)}>
                    <Text style={s.btnText}>播放</Text>
                  </TouchableOpacity>
                  <TouchableOpacity
                    style={[s.btn, s.btnGhost]}
                    onPress={() => save(result.url, videoName, 'video/mp4')}
                    disabled={busy}>
                    <Text style={s.btnGhostText}>{busy ? '下载中…' : '下载'}</Text>
                  </TouchableOpacity>
                </View>
                <TouchableOpacity style={[s.btn, s.btnSmall]} onPress={() => copy(result.url, '直链')}>
                  <Text style={s.smallText}>复制直链</Text>
                </TouchableOpacity>
                <Text style={s.tip}>播放若被防盗链拦截，用「下载」（带 Referer 拉取）</Text>
              </>
            ) : null}

            {images.length ? (
              <>
                <Text style={s.label}>图集 {images.length} 张（点击下载）</Text>
                <ScrollView horizontal style={s.thumbs}>
                  {images.map((u, i) => (
                    <TouchableOpacity
                      key={i}
                      onPress={() => save(u, `${videoName}_${i + 1}`, 'image/jpeg')}>
                      <Image
                        style={s.thumb}
                        source={{uri: u, headers: IMG_HEADERS}}
                      />
                    </TouchableOpacity>
                  ))}
                </ScrollView>
              </>
            ) : null}

            {livePhotos.length ? (
              <Text style={s.tip}>实况 Live Photo：{livePhotos.length} 组（图片 + 视频）</Text>
            ) : null}

            {backups.length ? (
              <View style={s.backupBox}>
                <Text style={s.label}>备选直链 {backups.length} 条（点击复制）</Text>
                {backups.slice(0, 3).map((u, i) => (
                  <TouchableOpacity key={i} onPress={() => copy(u, '备选直链')}>
                    <Text style={s.backup} numberOfLines={1}>{u}</Text>
                  </TouchableOpacity>
                ))}
              </View>
            ) : null}

            {result.music?.url ? (
              <TouchableOpacity
                style={s.musicRow}
                onPress={() => save(result.music.url, `${videoName}_bgm`, 'audio/mpeg')}>
                <Text style={s.music} numberOfLines={1}>
                  ♪ {result.music.title || '背景音乐'} · 点击下载
                </Text>
              </TouchableOpacity>
            ) : null}
          </View>
        ) : null}

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
  sub: {color: '#8b93a1', fontSize: 13, marginTop: 3, marginBottom: 14},
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
  tip: {color: '#6b7280', fontSize: 11, marginTop: 6, lineHeight: 16},
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
  foot: {color: '#4b5563', fontSize: 10, textAlign: 'center', marginTop: 14},
  toast: {position: 'absolute', bottom: 28, left: 0, right: 0, alignItems: 'center'},
  toastText: {
    color: '#fff', backgroundColor: '#374151', paddingHorizontal: 16,
    paddingVertical: 9, borderRadius: 20, fontSize: 12, overflow: 'hidden',
  },
});
