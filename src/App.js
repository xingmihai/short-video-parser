/**
 * 极简控制台：显示网关地址、启停开关、实时日志
 * 真正的代理逻辑在 src/proxy.js，与界面无关 —— 界面只是方便自测
 */

import React, {useEffect, useRef, useState} from 'react';
import {
  SafeAreaView,
  ScrollView,
  StatusBar,
  StyleSheet,
  Text,
  TouchableOpacity,
  View,
  Clipboard,
} from 'react-native';
import {startProxyServer, stopProxyServer, gatewayInfo, onLog} from './server';
import {DEFAULT_PORT} from './config';

export default function App() {
  const [running, setRunning] = useState(false);
  const [host, setHost] = useState('127.0.0.1');
  const [logs, setLogs] = useState([]);
  const scrollRef = useRef(null);

  useEffect(() => {
    const off = onLog((line) => {
      setLogs((prev) => [...prev.slice(-199), line]);
    });
    (async () => {
      const info = await gatewayInfo();
      setHost(info.host);
      const started = await startProxyServer(DEFAULT_PORT);
      if (started) {
        setHost(started.host);
        setRunning(true);
      }
    })();
    return off;
  }, []);

  useEffect(() => {
    scrollRef.current?.scrollToEnd({animated: true});
  }, [logs]);

  const baseUrl = `http://${host}:${DEFAULT_PORT}`;
  const demoUrl = `${baseUrl}/?proxyurl=${btoaSafe('https://example.com/a.mp4')}&mediatype=video&download=false`;

  const toggle = async () => {
    if (running) {
      await stopProxyServer();
      setRunning(false);
    } else {
      const info = await startProxyServer(DEFAULT_PORT);
      if (info) {
        setHost(info.host);
        setRunning(true);
      }
    }
  };

  return (
    <SafeAreaView style={styles.root}>
      <StatusBar barStyle="light-content" />
      <View style={styles.header}>
        <Text style={styles.title}>sv-proxy</Text>
        <Text style={styles.subtitle}>短视频直链代理 · 本地网关</Text>
      </View>

      <View style={styles.card}>
        <Text style={styles.label}>访问地址</Text>
        <TouchableOpacity onPress={() => Clipboard.setString(baseUrl)}>
          <Text style={styles.url}>{baseUrl}</Text>
        </TouchableOpacity>
        <Text style={styles.tip}>点地址复制。同一局域网内其他设备可直接访问</Text>
      </View>

      <TouchableOpacity
        style={[styles.btn, running ? styles.btnStop : styles.btnStart]}
        onPress={toggle}>
        <Text style={styles.btnText}>{running ? '停止网关' : '启动网关'}</Text>
      </TouchableOpacity>

      <View style={styles.card}>
        <Text style={styles.label}>调用示例</Text>
        <Text style={styles.code} numberOfLines={3}>
          {`${baseUrl}/?proxyurl=<Base64直链>&type=douyin&download=false`}
        </Text>
      </View>

      <ScrollView ref={scrollRef} style={styles.logBox}>
        {logs.map((l, i) => (
          <Text key={i} style={styles.logLine}>
            {l}
          </Text>
        ))}
      </ScrollView>
    </SafeAreaView>
  );
}

function btoaSafe(s) {
  // RN 环境不一定有 btoa，这里只用于示例展示
  try {
    return btoa(s);
  } catch (e) {
    return s;
  }
}

const styles = StyleSheet.create({
  root: {flex: 1, backgroundColor: '#0f1115', padding: 16},
  header: {marginBottom: 16},
  title: {color: '#fff', fontSize: 22, fontWeight: '700'},
  subtitle: {color: '#8b93a1', fontSize: 13, marginTop: 2},
  card: {
    backgroundColor: '#171b22',
    borderRadius: 10,
    padding: 14,
    marginBottom: 12,
    borderWidth: 1,
    borderColor: '#2a3038',
  },
  label: {color: '#8b93a1', fontSize: 12, marginBottom: 6},
  url: {color: '#60a5fa', fontSize: 16, fontWeight: '600'},
  tip: {color: '#6b7280', fontSize: 11, marginTop: 6},
  code: {color: '#a5b4fc', fontSize: 11, fontFamily: 'monospace'},
  btn: {padding: 14, borderRadius: 10, alignItems: 'center', marginBottom: 12},
  btnStart: {backgroundColor: '#2563eb'},
  btnStop: {backgroundColor: '#b91c1c'},
  btnText: {color: '#fff', fontSize: 15, fontWeight: '600'},
  logBox: {flex: 1, backgroundColor: '#000', borderRadius: 10, padding: 10},
  logLine: {color: '#9ca3af', fontSize: 11, fontFamily: 'monospace', marginBottom: 2},
});
