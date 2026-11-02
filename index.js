/**
 * sv-proxy-android — React Native 入口
 *
 * 抖音分享链接解析全部在 index.android.bundle 内完成：
 *   src/resolve.js  分享短链 → 无水印直链（解析 Worker 移植）
 *   src/App.js      输入 → 解析 → 播放 / 下载
 *
 * 无本地网关、无监听端口、无局域网地址。
 * 原生只负责发解析请求、带 Referer 下载媒体、剪贴板读写。
 */

import {AppRegistry} from 'react-native';
import App from './src/App';

AppRegistry.registerComponent('svproxy', () => App);
