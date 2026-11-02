# BiliMax 助手（BiliMaxSync）

<p align="center">
  <strong>BiliMax 的 Android 手机伴侣端</strong><br>
  让 B 站内容在 Xiaomi Vela 智能手表上「下载 · 转码 · 推送」一气呵成
</p>

---

## 📖 项目简介

BiliMax 是运行在 Xiaomi Vela 手表上的哔哩哔哩客户端（快应用 / RPK）。
本仓库 **BiliMaxSync** 是它的 **Android 手机伴侣 APK**（包名 `moe.mcns.BiliMax`，应用名「BiliMax 助手」）。

手表本身算力、网络与存储有限，无法直接完成视频下载与音频转码。手机端伴侣通过
**Xiaomi Wearable SDK（XMS）消息通道**与手表建立连接，代手表发起网络请求、下载 B 站音视频、
用 FFmpeg 转码为手表可播放的格式，再回传给手表，实现：

- 📥 **视频/音频下载**：代手表请求 B 站接口，下载视频与音频流
- 🔄 **FFmpeg 转码**：基于 ffmpeg-kit，将音频转码为手表兼容格式（mp3 等）
- 🌐 **本机转码服务器**：在手机本地运行 HTTP 服务（`http://127.0.0.1:6732`），
  手表请求经 XMS 通道桥接回环访问，前台服务保活
- ⌚ **设备配对**：发现并注册配套手表，校验配套服务指纹
- 🎬 **影视推送 / 历史记录**：向手表推送内容、同步观看历史
- 🐛 **崩溃日志分享**：崩溃日志收集、文本分享（FileProvider）

> 手表端 RPK 源码见配套仓库 [MingChengNetworkStudio/BiliMax](https://github.com/MingChengNetworkStudio/BiliMax)。

---

## 🧩 技术栈

| 类别 | 选型 |
|------|------|
| 语言 | Kotlin |
| UI | Jetpack Compose + [Miuix](https://github.com/miuix-kotlin-multiplatform/miuix)（HyperOS 风格组件库） |
| 最低 / 目标 SDK | minSdk 26 / targetSdk 36 / compileSdk 37 |
| 转码 | ffmpeg-kit-audio 8.1.7（16KB 页对齐，含 libmp3lame） |
| 手表互联 | XMS Wearable SDK 净室实现（vendor 预构建 AAR） |
| JDK | 17 |

### 关于 XMS Wearable SDK

官方小米穿戴 SDK 未发布到 Maven Central，本项目使用净室实现版本
`OrPudding/XMS_Wearable_SDK_CleanRoom`（公共包名 `com.xiaomi.xms.wearable.*` 与官方一致），
以预构建 AAR 形式 vendor 在 `app/libs/` 下，**需随仓库一起保留**。
连接后端优先 OronBox 配套服务，回退小米运动健康 / 小米穿戴。

---

## 📁 项目结构

```
apk/
├── app/
│   ├── libs/
│   │   └── xms-wearable-lib-1.4_oronbox.1-release.aar   # XMS 净室 SDK（必须入库）
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── res/                                          # 图标、主题、FileProvider 配置
│       └── java/moe/mcns/BiliMax/
│           ├── App.kt                # Application
│           ├── AppPrefs.kt           # 偏好设置
│           ├── MainActivity.kt       # Compose UI（首页 / 设置 / 关于）
│           ├── crash/
│           │   └── CrashReporter.kt  # 崩溃日志收集与分享
│           ├── transcode/
│           │   └── Transcoder.kt     # FFmpeg 转码封装
│           └── sync/
│               ├── BiliDownloader.kt   # B 站音视频下载
│               ├── TranscodeServer.kt  # 本机 HTTP 转码服务器
│               ├── SyncService.kt      # 前台保活服务
│               ├── SfBridge.kt         # SimpleFetch 协议桥接
│               ├── WearTransport.kt    # XMS 通道传输
│               ├── DeviceRegistry.kt   # 手表配对 / 注册
│               ├── FilmPush.kt         # 影视推送
│               ├── HistoryStore.kt     # 历史记录
│               ├── NetInfo.kt          # 网络信息
│               ├── SyncHolder.kt       # 同步状态持有
│               └── SyncLog.kt          # 同步日志
├── keystore/               # 签名证书（不入库，见下节）
├── build.gradle.kts
├── settings.gradle.kts
└── gradle.properties
```

---

## 🔨 构建

### 环境要求

- Android Studio（自带 JBR 17）或本机 JDK 17
- Android SDK，compileSdk 37
- 首次构建前在 `apk/local.properties` 中配置 `sdk.dir`（Android Studio 会自动生成）

### 命令行构建

```bash
# Debug APK
./gradlew :app:assembleDebug

# Release APK
./gradlew :app:assembleRelease
```

产物位于 `app/build/outputs/apk/`。

---

## 🔑 签名说明（重要）

APK 与手表 RPK **必须使用同一签名证书**（`CN=ResonaUI`），
否则 XMS `@system.interconnect` 互联的配套服务指纹校验不通过，手机与手表无法通信。

签名证书属敏感信息，**不随仓库分发**。构建脚本会在证书存在时自动启用配套签名，
不存在时回退到 Android 默认 debug 签名（不影响日常开发调试，但无法与正式 RPK 互联）。

如需启用配套签名，请在 `apk/` 下准备：

1. 证书文件 `keystore/resona.p12`（由 RPK 的 `sign/certificate.pem` + 私钥导出）；
2. 密码文件 `keystore.properties`（**已被 .gitignore 排除，请勿提交**）：

   ```properties
   storePassword=你的证书库密码
   keyAlias=resona
   keyPassword=你的密钥密码
   ```

   也可不建该文件，改用环境变量 `RESONA_STORE_PASSWORD` / `RESONA_KEY_PASSWORD`。

---

## ⌚ 与手表端的关系

| 端 | 仓库 | 形态 |
|----|------|------|
| 手机伴侣 | MingChengNetworkStudio/BiliMaxSync（本仓库） | Android APK |
| 手表客户端 | [MingChengNetworkStudio/BiliMax](https://github.com/MingChengNetworkStudio/BiliMax) | Xiaomi Vela 快应用 RPK |

两端包名均为 `moe.mcns.BiliMax`，且需同证书签名。手表端发起的下载 / 转码请求
经 XMS 消息通道送达手机端，由手机端完成重活后回传结果。

---

## 📄 许可证

本项目源码以开源协议发布。转码能力依赖 FFmpeg（LGPL/GPL），详见各依赖项目说明。
