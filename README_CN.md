# Pixel Meter TW

<p align="center">
  <img src="app/src/main/ic_launcher-playstore.png" width="180" alt="Pixel Meter TW 图标"/>
</p>

<p align="center">
  <strong>开着 VPN 也不会重复计算的实时网速监控，支持繁体中文（台湾）与三星 One UI。</strong>
</p>

<p align="center">
  <a href="README.md">English</a> · <a href="README_TW.md">繁體中文（台灣）</a> · 简体中文
</p>

Pixel Meter TW 是 Pixel Tailor CN 开发的 [Pixel Meter](https://github.com/Pixel-Tailor-CN/PixelMeter) 的分支版本，保留原版准确计速的做法，并增加：

- **繁体中文（台湾）界面**，按台湾用语翻译，不是简转繁。
- **三星 One UI 支持**：可在 Now Bar 和状态栏胶囊显示网速，并提供三星专属的设置引导。
- **省电与准确度改进**：熄屏后更快停止采样、减少通知刷新、切换 Wi-Fi 与移动网络时不再出现网速尖峰。
- **独立的应用 ID**（`io.github.candy999.netmeter`），可以**与原版同时安装**。

如果你需要原版（含 Google Play 版本），请前往 [Pixel Meter 原项目](https://github.com/Pixel-Tailor-CN/PixelMeter)。

## 下载

| 渠道 | 说明 |
|---|---|
| [Releases](https://github.com/candy999/NetMeter-TW/releases/latest) | 正式签名版 APK，推荐使用。 |
| [Actions › Build APK](https://github.com/candy999/NetMeter-TW/actions/workflows/build-apk.yml) | 每次提交自动构建的测试版 APK，在运行页面底部的 **Artifacts** 下载 `PixelMeterTW-apk`（需要登录 GitHub）。 |

正式版与测试版的签名可能不同。如果无法覆盖安装，请先卸载另一个版本，应用设置会被清除。

## 三星 One UI

One UI 用自己的 Now Bar 机制处理常驻通知，会忽略不在三星白名单中的应用发出的标准 Android 实时更新。在三星设备上，本应用会附加社区研究发现、三星系统会接受的 Now Bar 参数。

| One UI 版本 | 实时更新胶囊／Now Bar |
|---|---|
| 9.0（Android 17） | 预期可用 |
| 8.5 | 预期可用 |
| 8.0 及以下 | 不可用，请改用通知图标或悬浮窗 |

胶囊未出现时：

1. 确认应用内「设置 › 通知 › 启用实时更新」已打开。
2. 确认「实时更新权限」显示「已允许」；否则点击它打开系统设置。
3. 如果安装了 Good Lock 的 QuickStar，确认「Ongoing Chip」未关闭。

为避免监控被系统停止，请将应用加入「设置 › 电池 › 后台使用限制 › 永不休眠的应用」。

三星的这些参数并未公开，未来的 One UI 版本可能失效；失效时应用会回退到标准 Android 行为。

## 系统要求

- Android 12（API 31）及以上。
- Android 13 及以上需要通知权限才能使用通知网速和实时更新。
- 只有悬浮窗需要「显示在其他应用上层」权限。

## 构建

推送代码时，`build-apk.yml` 会自动构建 APK；推送 `v*` 标签时，`release.yml` 会创建 GitHub Release。正式签名需要两个仓库 Secrets：`SIGN_KEY_STORE_BASE64`（keystore 的 Base64）和 `SIGN_KEY_STORE_PASSWORD`。

本地构建需要 JDK 21 和带有 `android-37` 平台、`37.0.0` build-tools 的 Android SDK：

```bash
./gradlew :app:assembleDebug
./gradlew lint
```

更多说明见 [README.md](README.md) 和 [`docs/README.md`](docs/README.md)。

## 隐私

流量计数和设置只在设备本地处理，详见 [PRIVACY_POLICY_CN.md](PRIVACY_POLICY_CN.md)。

## 致谢与许可证

- 原始应用：Pixel Tailor CN 的 [Pixel Meter](https://github.com/Pixel-Tailor-CN/PixelMeter)，采用 Apache-2.0 许可证。
- 三星 Now Bar 研究：[tigerduck-app-android](https://github.com/tigerduck-app/tigerduck-app-android/pull/126)。
- 本分支同样采用 [Apache License 2.0](LICENSE)，与 Google、三星及原作者无关联。
