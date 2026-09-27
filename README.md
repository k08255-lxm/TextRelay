# 文字互传 (TextRelay)

同一局域网内电脑 / 手机 / 平板互发文字。Material 3 · Release APK 约 1.2MB · 无广告无服务端。

## 功能

- **自动发现**：同网段设备免配置互连；电脑也作为设备出现在手机列表里
- **网页收发**：电脑运行 `pc` 工具后，浏览器只开 `http://127.0.0.1:24680`
- **离线必达**：对方不在线时消息暂存本地，上线自动补齐（电脑离线发送同样暂存）
- **保留 1 天**：自动清理（上限 1000 条）；支持单条删除与一键清空
- **检查更新**：APP 内手动 / 自动（24h 节流），失败时显示具体原因
- **系统分享**：任意应用「分享 → 文字互传」直接发送

## 快速开始

**手机**：到 [Releases](https://github.com/k08255-lxm/TextRelay/releases) 下载 APK 安装，打开即可（同一 Wi-Fi）。

**电脑**（需 Python 3）：双击 `pc/启动文字互传.bat`，浏览器自动打开 `127.0.0.1:24680`。

- UDP 被防火墙拦截时自动扫描网段发现手机（十几秒）；也可手动指定：`python textrelay_pc.py --phone 手机IP`
- 不装 Python 也行：浏览器直接开手机通知栏里的 `http://手机IP:24680`

## 构建与发版

```bash
./gradlew assembleRelease   # 产物：app/build/outputs/apk/release/
```

发版流程（本地编译 + 草稿直发 / 云端编译）见 [RELEASE.md](RELEASE.md)。

> 签名密钥（`app/textrelay.keystore` + `keystore.properties`）不入库，只在本机——**请自行备份**，丢失后升级需卸载重装。克隆者请自备签名或使用调试签名。

## 原理

UDP 信标发现设备 → HTTP 推送 + 每 20 秒对账同步 → 消息本地存储（1 天 / 1000 条，按 id 去重）→ 前台服务保活 + 开机自启。

## 限制

- 需同一局域网；AP 隔离 / 访客网络会阻断互访（可在「设备」对话框手动添加 IP）
- 设备间时钟差需 ≤ 10 分钟
- 部分厂商激进杀后台 → 请允许本应用自启动 / 无限制耗电
- 电脑网页为 `http` 非安全源，「粘贴」按钮可能被浏览器拦截 → 输入框内 `Ctrl+V`

## 目录结构

```
app/                     Android 应用（Compose + Material 3）
  src/main/assets/web/   手机内置网页（与 pc/index.html 同源，改动需同步两份）
pc/                      电脑端：textrelay_pc.py(发现+代理+暂存) / outbox_store.py / index.html / 启动bat
```
