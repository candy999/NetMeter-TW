## Pixel Meter TW v1.9.8-tw.1

基於 [Pixel Meter](https://github.com/Pixel-Tailor-CN/PixelMeter) v1.9.8 的台灣分支版本，可以和原版同時安裝（應用程式 ID：`io.github.candy999.netmeter`）。

### 新功能
- 新增台灣繁體中文介面，採用台灣用語。
- 支援三星 One UI 的 Now Bar 與狀態列膠囊（One UI 8.5 以上）。
- 設定頁新增「即時更新權限」狀態與系統設定捷徑。
- 三星裝置新增「Now Bar 相容模式」說明與「永不睡眠的應用程式」設定指南。

### 省電與效能
- 關閉螢幕 10 秒後停止取樣（原本為 2 分鐘）；在螢幕關閉時啟動服務也會停止取樣。
- 只有在內容改變時才更新通知，閒置時不再每秒重新整理。
- 浮動視窗隱藏或關閉時不再重繪。

### 錯誤修正
- 修正 Wi-Fi 與行動網路切換時網速瞬間暴衝的問題。
- 修正網速在門檻附近徘徊時，即時更新膠囊反覆出現又消失的問題。
- 修正快速開關浮動視窗時可能殘留多個視窗的問題。

### 安裝說明
- 下載下方的 `PixelMeterTW-v1.9.8-tw.1.apk` 安裝。
- 如果裝過 GitHub Actions 的測試版（debug 版），因為簽章不同，請先解除安裝測試版再安裝正式版。

---

## Pixel Meter TW v1.9.8-tw.1 (English)

A Taiwan fork based on [Pixel Meter](https://github.com/Pixel-Tailor-CN/PixelMeter) v1.9.8. It installs alongside the original app (application ID `io.github.candy999.netmeter`).

### New
- Traditional Chinese (Taiwan) translation written with Taiwan terminology.
- Samsung One UI Now Bar and status bar chip support for Live Update (One UI 8.5 or later).
- Live Update permission status with a shortcut to system settings.
- Samsung-only Now Bar hint and a "Never sleeping apps" guide.

### Battery and performance
- Sampling stops 10 seconds after the screen turns off (was 2 minutes), including when the service starts with the screen off.
- The notification is reposted only when its content changes.
- The Overlay no longer redraws while hidden or disabled.

### Fixes
- No more speed spike when switching between Wi-Fi and cellular.
- The Live Update chip no longer flickers when the speed hovers around the threshold.
- Quickly toggling the Overlay no longer leaves extra windows behind.

### Install
- Download `PixelMeterTW-v1.9.8-tw.1.apk` below.
- Uninstall any debug test build from GitHub Actions first; it uses a different signature.
