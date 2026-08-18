# 耳诵（NotificationReader2）

一个面向 Android 的通知朗读应用。用户选择需要播报的应用后，系统收到通知时，应用会在本地解析通知内容，并通过 Android Text-to-Speech（TTS）朗读出来。

## 功能

- 监听系统通知，并朗读应用名、通知标题和正文。
- 支持“详细内容”和“新消息提醒”两种播报方式。
- 可按应用单独开启或关闭朗读。
- 支持蓝牙耳机模式和系统默认音频输出模式。
- 可设置仅在锁屏或息屏时播报。
- 通话期间自动暂停或跳过播报。
- 支持耳机/媒体暂停控制，停止当前语音并清空排队内容。
- 对重复通知和快速更新的聚合通知进行去重与合并。
- 支持选择系统中可用的 TTS 引擎，并兼容中文语音。

## 工作流程

```text
系统通知
  -> NotificationTtsListenerService
  -> 读取用户的应用和播放设置
  -> 解析通知文本并去重
  -> TtsForegroundService 排队
  -> Android TextToSpeech 播放
```

## 环境要求

- Android 10（API 29）或更高版本
- Android Studio / JDK 8+
- 已安装并启用一个可用的 Android TTS 引擎
- 编译 SDK 34

## 构建

```bash
git clone https://github.com/xixilalaha/ersong.git
cd ersong

# 运行单元测试
./gradlew test

# 构建 Debug APK
./gradlew assembleDebug
```

生成的 Debug APK 位于：

```text
app/build/outputs/apk/debug/app-debug.apk
```

Release 签名配置不包含在仓库中。需要签名构建时，请在本地准备 `keystore.properties` 和密钥库文件；这些文件已被 `.gitignore` 排除。

## 首次使用

1. 安装并打开应用。
2. 在系统设置中开启“通知使用权”。
3. 在系统中启用并准备中文 TTS 语音数据。
4. 在“管理朗读应用”中选择需要播报的应用。
5. 打开“开启通知播报”，按需要选择蓝牙模式或正常模式。

部分手机的省电策略会限制后台服务。若息屏时无法播报，请在系统设置或厂商手机管家中允许本应用后台活动或自启动。

## 权限说明

| 权限/能力 | 用途 |
| --- | --- |
| 通知使用权 | 读取用户授权范围内的系统通知并转换为语音 |
| `POST_NOTIFICATIONS` | Android 13+ 显示前台服务通知 |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | 在后台稳定运行朗读服务 |
| `QUERY_ALL_PACKAGES` | 展示设备上可供用户选择的应用列表 |
| `READ_PHONE_STATE` | 检测通话状态，通话中暂停或跳过播报 |

## 隐私说明

- 本项目没有声明 `INTERNET` 权限，也没有内置服务器、统计 SDK 或网络上传逻辑。
- 通知解析、去重、播报队列和用户开关都在设备本地完成。
- 应用设置只保存开关、播报模式和应用包名等配置；通知正文不写入 `SharedPreferences`，播报队列和去重信息只在运行期间保存在内存中。
- 开启通知使用权后，应用能够读取授权范围内的通知内容，这是本应用实现朗读功能所必需的。请仅为你信任的应用开启该权限。
- 开发调试日志可能包含通知标题或正文的短预览。分享 `logcat` 前请先检查并脱敏其中的个人信息。

## 项目结构

- `app/src/main/java/com/example/notificationreader2/NotificationTtsListenerService.kt`：接收并解析通知。
- `app/src/main/java/com/example/notificationreader2/TtsForegroundService.kt`：维护后台朗读服务和播放队列。
- `app/src/main/java/com/example/notificationreader2/TtsSpeaker.kt`：封装 Android TTS 引擎。
- `app/src/main/java/com/example/notificationreader2/ReadAloudPrefs.kt`：保存本地开关和应用选择。
- `app/src/main/java/com/example/notificationreader2/SpeechTextNormalizer.kt`：处理孤立数字和符号的播报文本。
- `PROJECT_MAP.md`：更完整的代码导航和模块说明。
- `CHANGELOG.md`：版本更新记录。

## 已知限制

- 应用只能读取通知中由其他应用暴露出来的标题、正文或消息列表，无法读取聊天应用内部数据库。
- 不同 Android 厂商的后台限制、TTS 引擎和通知格式存在差异，实际行为可能不同。
- 目前仓库未附带开源许可证文件；如需在公开仓库基础上再分发，请先补充合适的许可证和版权说明。
