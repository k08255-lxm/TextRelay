# 文字互传 (TextRelay)

局域网文字互传 Android 应用 · Material 3 · 轻量级

在同一个 Wi-Fi / 局域网内，电脑、手机、平板之间互发文字：

- **手机 ↔ 手机**：双方都安装本 APP，自动发现、自动收发
- **电脑 → 手机 / 手机 → 电脑**：电脑运行 `pc` 目录的小工具，浏览器永远只开 `http://127.0.0.1:24680`，无需找手机 IP
- **离线补收**：发送时对方不在线也没关系，消息保存在双方本地，设备上线后自动对账补齐
- **电脑离线也能发**：手机不在线时，电脑发出的消息暂存在本机（`~/.textrelay/outbox`），手机一上线自动送达，网页会显示「已暂存」状态
- **方便复制**：每条消息一键复制；支持从任意应用的「分享」菜单把文字直接发出去
- **固定签名**：debug/release 共用一把密钥，升级直接覆盖安装，无需卸载重装

## 下载 APK（自动发布）

- **最新构建**：main 分支有 **APP 相关改动**（`app/**`、Gradle 配置等）时，GitHub Actions 自动编译并刷新 [latest 预发布](https://github.com/k08255-lxm/TextRelay/releases/tag/latest)，下载入口固定不变；只改 `pc/` 脚本或文档不会触发编译
- **正式版本**：打 `v*` 标签自动发布对应 Release：`git tag v1.0.1 && git push origin v1.0.1`（发布前记得递增 `versionCode`）
- CI 产出的 APK 与本机构建**使用同一把签名密钥**（密钥经仓库 Secrets `KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` 注入，不进代码库），可直接互相覆盖安装
- 工作流：[build-latest.yml](.github/workflows/build-latest.yml)（latest）/ [release.yml](.github/workflows/release.yml)（正式版），均支持在 Actions 页面手动触发

## 构建与安装

需要 JDK 17+ 与 Android SDK（或直接用 Android Studio）。

```bash
# 命令行构建（Windows 用 gradlew.bat）
./gradlew assembleDebug      # 调试包
./gradlew assembleRelease    # 发布包（R8 裁剪，约 1.2MB）
# 产物：app/build/outputs/apk/{debug,release}/app-*.apk
```

或者用 Android Studio 打开本目录，直接 Run。

> **签名说明**：debug/release 共用同一把固定签名，升级只需 `versionCode` 递增即可覆盖安装。
> 签名材料**不进入本仓库**：`app/textrelay.keystore` 与 `keystore.properties`（存密码）都在
> `.gitignore` 中，只保留在构建机本地；构建脚本从 `keystore.properties` 或环境变量
> `TEXTRELAY_STORE_PASSWORD` / `TEXTRELAY_KEY_PASSWORD` 读取密码。克隆本仓库构建会使用
> 默认调试签名（或换成你自己的 keystore，在 `app/build.gradle.kts` 的 `signingConfigs` 里配置）。
>
> ⚠️ 这两个文件是升级安装的前提，**请自行备份**；一旦同时丢失，只能卸载重装。

## 使用方法

1. 所有设备连入同一 Wi-Fi，打开 APP（通知栏会显示本机网址）
2. 手机之间：输入文字 → 发送，局域网内所有打开 APP 的设备自动收到
3. **电脑（推荐）**：双击 `pc/启动文字互传.bat`（需已安装 Python），会自动发现手机并打开 `http://127.0.0.1:24680`；手机不在线时发送会自动暂存，上线后送达。若 UDP 被防火墙/AP 隔离拦截，可手动指定手机 IP：`python textrelay_pc.py --phone 192.168.3.40`；也可以不装任何东西，直接用浏览器打开通知栏显示的 `http://<手机IP>:24680`
4. 顶部「设备」图标可查看在线设备、重命名本机、手动添加对方 IP（路由器开了 AP 隔离导致广播被禁时使用）
5. 任意应用里「分享 → 文字互传」可把分享的文字直接填入发送框

## 工作原理

| 组件 | 说明 |
|---|---|
| UDP 信标 | 每 3 秒向 `255.255.255.255` / 子网广播 / 组播 `239.255.246.80:24681` 广播设备名、HTTP 端口、最新消息时间戳 |
| HTTP 服务 | NanoHTTPD，端口 `24680`，同时服务网页版与 APP 间同步接口 |
| 消息同步 | 收到信标后对比双方最新时间戳，缺的一方拉取 / 多的一方推送；每 20 秒强制对账一次 |
| 离线补收 | 每台设备把见过的消息都存进本地（JSONL 文件，保留 7 天 / 1000 条，按 id 去重），上线后经信标对账自动补齐 |
| 常驻 | 前台服务（dataSync 类型）保活；开机自启 |

```
App A 发送 → 落库 → 逐个 POST /push 给在线设备
                ↘ 离线设备上线后广播信标(带latest) → 邻居发现它落后 → 推送缺失消息
```

## 已知限制

- 必须同一局域网（无公网中继）；路由器开启「AP 隔离 / 访客网络」会阻断互访，可在「设备」对话框手动添加 IP
- 设备间时钟差要求在 10 分钟以内（同步按时间戳 + 重叠窗口对账，按消息 id 去重）
- 消息保留 7 天、最多 1000 条，超限自动清理最旧的
- 部分厂商的后台限制可能杀掉常驻服务；如需长期可靠接收，请在系统设置里允许本应用自启动 / 无限制耗电
- 电脑网页在 `http://` 非安全源下，「粘贴」按钮可能被浏览器拦截，直接在输入框 `Ctrl+V` 即可

## 目录结构

```
app/src/main/java/com/textrelay/app/
├─ data/        Message / MessageStore(JSONL 存储+去重) / Prefs
├─ relay/       Protocol / Discovery(UDP) / HttpApi(HTTP服务) / RelayEngine(同步引擎)
│               PeerRegistry / RelayService(前台服务) / NetworkUtils / Http
├─ ui/          MainScreen(M3 界面) / Theme(动态取色)
├─ MainActivity.kt / MainViewModel.kt / BootReceiver.kt
├─ assets/web/index.html   电脑端网页版（零依赖单文件）
└─ textrelay.keystore      固定签名（不入库，本机保留）

pc/
├─ textrelay_pc.py         PC 端：UDP 自动发现 + 127.0.0.1 反向代理 + 离线暂存（纯标准库）
├─ outbox_store.py         离线发件箱持久化（dbm 键值库，~/.textrelay/outbox）
└─ 启动文字互传.bat         双击启动（支持透传参数，如 --phone IP）
```
