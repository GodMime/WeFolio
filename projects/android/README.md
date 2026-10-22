# 映期Folio Android

使用微信官方多端框架编译独立 Android 应用。业务页面、组件、样式和工具从 `../miniapp` 同步；安卓差异在 `overrides/` 中维护。此工程使用微信开发者工具构建，不是 Android Studio / Gradle 工程。

```bash
npm run sync       # 生成 generated/，不修改原小程序
npm test           # 页面覆盖、登录、访客、支付隔离、分享和文件选择测试
npm run open       # 用微信开发者工具打开此工程
npm run build:apk  # 构建测试签名 APK，产物放入 dist/build-*/
```

当前源码覆盖原有 51 个页面中的 50 个，按要求移除充值页面，保留积分与充值记录；登录仅支持已有小程序账号，小程序注册时的手机号验证要求保持不变。支付和安卓自动注册均关闭。

**已生成测试 APK：** `dist/WeFolio-Android-0.1.0-debug.apk`（约 120 MiB，Android 7.0 及以上）。2026-10-22 已通过官方构建工具生成，包内含 50 页及安卓接口适配。此前的 `project not found` 是本地目录尾部斜杠导致的窗口匹配问题，已修复；用户已完成多端开启。当前尚无移动应用 AppID，微信登录需完成开放平台配置并部署新增后端接口后联调；尚未进行安卓真机验收。

首次配置、后端变量、签名说明和完整验证边界见本地 [安卓构建说明](../../docs/ANDROID_BUILD_GUIDE.md)。该说明遵循仓库约定，只保留本地。

公开配置放在 `android.config.json`，本地覆盖放在被忽略的 `android.local.json`；两者都不能放 AppSecret。`generated/` 是构建目录，不要手工修改；开发者工具生成的 `app.miniapp.json` 在再次同步时保留。`.local/android-debug.keystore` 是测试证书；正式证书与密码保存在 `.local/release-signing/`，需妥善备份。

`com.jxc.wefolio` 的正式签名证书已生成，备案填写值见本地 [APP 备案信息](../../docs/ANDROID_ICP_FILING.md) 和 `dist/icp-release/`。当前 `build:apk` 命令仍使用测试签名；正式打包时须配置上述包名与正式证书，再从实际 APK 核验备案信息一致。
