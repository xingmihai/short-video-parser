const {getDefaultConfig, mergeConfig} = require('@react-native/metro-config');

/**
 * 入口固定为 index.js —— 打包产物即 index.android.bundle
 * 所有代理逻辑（src/proxy.js）都会被打进这个 bundle
 */
const config = {
  projectRoot: __dirname,
};

module.exports = mergeConfig(getDefaultConfig(__dirname), config);
