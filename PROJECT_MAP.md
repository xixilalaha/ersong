# NotificationReader2 项目地图

这份文档给刚接触项目的人快速定位用：先知道“每个文件大概干什么”，再按常见需求找到应该改哪里。

## 一句话概览

这个应用的目标是：用户选择要朗读通知的应用后，系统通知到达时，由通知监听服务解析通知内容，再交给前台服务用 TTS 朗读。

大致流程是：

```text
系统通知
  -> NotificationTtsListenerService 收到通知
  -> ReadAloudPrefs 判断这个应用是否开启朗读、是否满足蓝牙模式
  -> 解析通知标题/正文/消息列表
  -> TtsForegroundService 排队
  -> TtsSpeaker 调 Android TextToSpeech 播放
```

## 核心文件作用

### app/src/main/java/com/example/notificationreader2/MainActivity.kt

主界面入口，负责用户能看见和能点的东西。

它主要做这些事：

- 请求 Android 13+ 的通知权限 `POST_NOTIFICATIONS`。
- 引导用户打开“通知使用权”设置。
- 引导用户打开系统 TTS 设置。
- 选择 TTS 引擎，并保存到 SharedPreferences。
- 显示蓝牙/普通播放模式切换。
- 显示通知权限、蓝牙状态、TTS 引擎状态。
- 打开“管理应用”底部弹窗。
- 扫描可选应用、已知通知来源、常见通讯包，生成可管理列表。

重点函数：

- `onCreate()`：绑定主界面按钮、初始化播放模式和状态。
- `updateStatusUi()`：刷新主界面状态文字。
- `isNotificationListenerEnabled()`：判断通知监听权限是否已开启。
- `buildTtsEngineStatusText()`：生成当前 TTS 引擎显示文字。
- `showManageAppsBottomSheet()`：管理应用列表的核心逻辑。
- `getVisibleLauncherPackages()`：找出可以展示/选择的应用包名来源。
- `refresh()`：刷新管理列表。
- `openManualAddDialog()`：打开“添加应用”选择弹窗。

### app/src/main/java/com/example/notificationreader2/NotificationTtsListenerService.kt

通知监听服务。系统通知变化时，Android 会回调这里。

它主要做这些事：

- 接收 `onNotificationPosted()` 通知事件。
- 忽略本应用自己的前台服务通知，避免循环朗读。
- 记录真实发过通知的应用包名，方便之后出现在管理列表里。
- 检查这个包名是否已开启朗读。
- 检查播放模式：如果是蓝牙模式，就要求蓝牙音频设备已连接。
- 从通知 extras 中提取可朗读文本。
- 去重，避免同一条通知短时间重复朗读。
- 把解析出的内容交给 `TtsForegroundService`。

重点函数：

- `onNotificationPosted(sbn)`：通知入口，所有通知先到这里。
- `extractAnnouncements(sbn, notification, title)`：解析通知内容。
- `joinSenderAndText(sender, text)`：把发送者和正文拼成朗读文本。
- `rememberIfNew(notificationKey, text)`：短时间去重。

### app/src/main/java/com/example/notificationreader2/TtsForegroundService.kt

前台朗读服务。它夹在通知监听和 TTS 引擎之间，负责排队和保活。

为什么需要它：

- 部分 ROM 会限制后台服务直接绑定 TTS。
- 前台服务更稳定，不容易在朗读中被系统回收。
- 可以统一控制队列、重试、看门狗、停止服务时机。

它主要做这些事：

- 收到要朗读的文本。
- 维护待播队列。
- 同一条聚合通知快速更新时，合并尚未朗读的旧内容。
- 确保 TTS speaker 已初始化。
- 一条播完后再播下一条。
- 如果 TTS 没有回调完成，用看门狗避免队列卡死。
- 空闲一段时间后停止服务。

重点函数：

- `onCreate()`：创建服务和 TTS speaker。
- `onStartCommand(intent, flags, startId)`：接收新的朗读任务或重载 TTS 引擎。
- `enqueue(collapseKey, texts)`：把新文本放入队列，并合并/限制队列。
- `rebuildSpeakerIfNeeded(desired, force)`：按用户选择的 TTS 引擎创建 speaker。
- `speakNextIfIdle()`：如果当前没在播，就取下一条朗读。
- `scheduleStop()`：空闲后延迟停止服务。
- `start(context, text)`：外部传入单条朗读任务。
- `start(context, texts, collapseKey)`：外部传入多条朗读任务，并支持同通知合并。
- `reloadEngine(context, enginePkg)`：用户切换 TTS 引擎后重建 speaker。

### app/src/main/java/com/example/notificationreader2/TtsSpeaker.kt

TTS 引擎封装。它直接和 Android `TextToSpeech` API 打交道。

它主要做这些事：

- 初始化 TextToSpeech。
- 选择用户设置的 TTS 引擎，或系统默认引擎。
- 设置中文语言。
- 注册 utterance 完成/错误回调。
- 调用 `engine.speak()` 播放文本。
- 为尾音加一点保护延迟。
- 播放前重置语速和音调。
- 关闭 TTS 引擎。

重点函数：

- `startInit(reason)`：初始化 TTS 引擎。
- `onInit(status)`：TTS 初始化完成回调。
- `isReady()`：判断 TTS 是否可用。
- `speak(text, onDone)`：真正提交一条文本给 TTS。
- `withTerminalPauseMarker()`：给文本补句尾停顿。
- `tailGuardDelayMs()`：根据文本长度估算尾音保护时间。
- `shutdown()`：释放 TTS。

### app/src/main/java/com/example/notificationreader2/ReadAloudPrefs.kt

本地数据保存中心。项目里和“用户选择/开关/列表状态”有关的数据，大多存在这里。

保存位置是 Android 的 `SharedPreferences`，文件名是 `prefs`。

它主要保存：

- 哪些包名开启了朗读。
- 哪些包名曾经真实发过通知。
- 播放路由模式：蓝牙模式或普通模式。
- 用户手动加入管理列表的包名。
- 用户从管理列表隐藏的包名。

重点函数：

- `isReadEnabled(context, pkg)`：某个应用是否开启朗读。
- `setReadEnabled(context, pkg, enabled)`：设置某个应用是否朗读。
- `getEnabledPackages(context)`：获取已开启朗读的包名集合。
- `rememberKnownPackage(context, pkg)`：记录真实发过通知的包名。
- `getKnownPackages(context)`：获取已知通知来源包名。
- `getManualIncludedPackages(context)`：获取手动加入列表的包名。
- `addManualIncludedPackages(context, pkgs)`：批量加入管理列表。
- `removeManualIncludedPackage(context, pkg)`：从手动加入列表移除。
- `getHiddenPackages(context)`：获取隐藏包名。
- `addHiddenPackage(context, pkg)`：把包名加入隐藏名单。
- `getPlaybackRouteMode(context)`：读取播放模式。
- `setPlaybackRouteMode(context, mode)`：保存播放模式。

### app/src/main/java/com/example/notificationreader2/AudioRouteUtils.kt

音频路由工具类。

目前只有一个职责：判断是否连接了蓝牙音频输出设备。

重点函数：

- `isBluetoothHeadsetConnected(context)`：检查 A2DP、SCO、BLE headset 等蓝牙音频设备。

### app/src/main/java/com/example/notificationreader2/AppToggleAdapter.kt

管理列表的 RecyclerView Adapter。

它负责把一个个应用展示成“图标 + 应用名 + 包名 + 开关”的行。

重点函数：

- `submit(list)`：提交新的列表数据。
- `onBindViewHolder()`：绑定每一行。
- `VH.bind(item)`：设置图标、名称、包名、开关状态、点击/长按事件。

### app/src/main/java/com/example/notificationreader2/PickAppAdapter.kt

“添加应用”弹窗里的 RecyclerView Adapter。

它负责：

- 展示可添加应用。
- 搜索过滤。
- 记录用户勾选了哪些应用。
- 异步加载应用图标。

重点函数：

- `getSelectedPackages()`：返回用户勾选的包名。
- `submitItems(newItems)`：更新可选应用列表。
- `filter(query)`：按应用名/包名搜索。
- `applyFilter()`：执行过滤并刷新列表。
- `VH.bind(item, isChecked, onChecked)`：绑定单行勾选状态。

### app/src/main/java/com/example/notificationreader2/AppToggleItem.kt

管理列表单行数据模型。

字段：

- `packageName`：应用包名。
- `appName`：应用显示名。
- `icon`：应用图标。
- `enabled`：是否开启朗读。

### app/src/main/java/com/example/notificationreader2/PickAppItem.kt

添加应用弹窗里的单行数据模型。

字段：

- `packageName`：应用包名。
- `appName`：应用显示名。

## 资源文件作用

### app/src/main/AndroidManifest.xml

Android 清单文件，声明权限、Activity、Service。

重点内容：

- `FOREGROUND_SERVICE`：允许使用前台服务。
- `FOREGROUND_SERVICE_MEDIA_PLAYBACK`：声明媒体播放类型前台服务。
- `POST_NOTIFICATIONS`：Android 13+ 前台服务通知需要。
- `QUERY_ALL_PACKAGES`：读取已安装应用列表。
- `NotificationTtsListenerService`：声明通知监听服务，并绑定 `BIND_NOTIFICATION_LISTENER_SERVICE` 权限。
- `TtsForegroundService`：声明前台 TTS 服务。
- `<queries>`：允许查询桌面应用和 TTS 服务。

### app/src/main/res/layout/activity_main.xml

主界面布局。

包含：

- 打开通知权限设置按钮。
- 打开 TTS 设置按钮。
- 选择 TTS 引擎按钮。
- 管理应用按钮。
- 蓝牙/普通模式切换。
- 状态文字。

### app/src/main/res/layout/bottom_sheet_manage_apps.xml

“管理应用”底部弹窗布局。

包含：

- 添加应用按钮。
- 关闭按钮。
- 空列表提示。
- 应用管理 RecyclerView。

### app/src/main/res/layout/dialog_pick_apps.xml

“添加应用”弹窗布局。

包含：

- 搜索框。
- 可选应用 RecyclerView。
- 加载提示。

### app/src/main/res/layout/item_app_toggle.xml

管理列表的单行布局。

包含：

- 应用图标。
- 应用名称。
- 包名。
- 是否朗读开关。

### app/src/main/res/layout/item_pick_app.xml

添加应用弹窗里的单行布局。

包含：

- 应用图标。
- 应用名称。
- 勾选框。

### app/src/main/res/values/strings.xml

界面文字资源。

如果只是想改按钮文字、提示文字、状态文案，一般先看这里。

### app/src/main/res/values/themes.xml、app/src/main/res/values-night/themes.xml、app/src/main/res/values/colors.xml

主题和颜色资源。

如果想改整体颜色、深色模式、Material 主题样式，可以从这里开始。

## 按职责划分

### 哪些负责 UI

- `MainActivity.kt`
- `AppToggleAdapter.kt`
- `PickAppAdapter.kt`
- `AppToggleItem.kt`
- `PickAppItem.kt`
- `activity_main.xml`
- `bottom_sheet_manage_apps.xml`
- `dialog_pick_apps.xml`
- `item_app_toggle.xml`
- `item_pick_app.xml`
- `strings.xml`
- `themes.xml`
- `colors.xml`

### 哪些负责通知监听

- `NotificationTtsListenerService.kt`
- `AndroidManifest.xml` 中的 `NotificationTtsListenerService` 声明

### 哪些负责 TTS

- `TtsForegroundService.kt`
- `TtsSpeaker.kt`
- `MainActivity.kt` 中选择 TTS 引擎、打开 TTS 设置的部分
- `AndroidManifest.xml` 中的 `TtsForegroundService` 声明

### 哪些负责权限

- `MainActivity.kt`
  - 请求 `POST_NOTIFICATIONS`
  - 打开通知监听设置
  - 判断通知监听是否开启
- `AndroidManifest.xml`
  - 声明前台服务、通知、查询应用、通知监听服务权限

### 哪些负责数据保存

- `ReadAloudPrefs.kt`
  - 朗读开关
  - 已知通知来源
  - 手动加入列表
  - 隐藏列表
  - 播放模式
- `MainActivity.kt`
  - 保存用户选择的 TTS 引擎，key 是 `tts_engine`

## 常见改动要去哪改

| 需求 | 文件 | 函数名 / 位置 |
| --- | --- | --- |
| 想改通知解析策略，比如优先读 `EXTRA_MESSAGES` 还是 `EXTRA_TEXT_LINES` | `NotificationTtsListenerService.kt` | `extractAnnouncements()` |
| 想改“发送者：正文”的拼接格式 | `NotificationTtsListenerService.kt` | `joinSenderAndText()` |
| 想改通知去重时间，比如 2 分钟太长/太短 | `NotificationTtsListenerService.kt` | `RECENT_TTL_MS`、`rememberIfNew()` |
| 想改哪些通知不朗读，比如过滤群名、过滤空内容 | `NotificationTtsListenerService.kt` | `onNotificationPosted()`、`extractAnnouncements()` |
| 想改蓝牙模式的判断条件 | `AudioRouteUtils.kt` | `isBluetoothHeadsetConnected()` |
| 想改“只有蓝牙时播报/普通模式”的逻辑 | `NotificationTtsListenerService.kt`、`ReadAloudPrefs.kt` | `onNotificationPosted()`、`getPlaybackRouteMode()` |
| 想改 TTS 队列合并策略，比如是否按通知 key 覆盖旧内容 | `TtsForegroundService.kt` | `enqueue()` |
| 想改 TTS 队列最大长度 | `TtsForegroundService.kt` | `MAX_QUEUE_ITEMS` |
| 想改一条播完后多久播下一条 | `TtsSpeaker.kt` | `tailGuardDelayMs()` |
| 想改 TTS 句尾停顿处理 | `TtsSpeaker.kt` | `withTerminalPauseMarker()` |
| 想改 TTS 语速或音调 | `TtsSpeaker.kt` | `speak()` 里 `setSpeechRate()`、`setPitch()` |
| 想改 TTS 初始化语言 | `TtsSpeaker.kt` | `onInit()` 里的 `candidates` |
| 想改 TTS 引擎选择弹窗 | `MainActivity.kt` | `pickTtsEngineButton.setOnClickListener { ... }` |
| 想改切换 TTS 引擎后的重载逻辑 | `TtsForegroundService.kt` | `reloadEngine()`、`rebuildSpeakerIfNeeded()` |
| 想改前台服务通知标题/内容 | `TtsForegroundService.kt` | `buildNotification()` |
| 想改前台服务空闲多久停止 | `TtsForegroundService.kt` | `scheduleStop()` |
| 想改管理列表的数据来源 | `MainActivity.kt` | `getVisibleLauncherPackages()`、`refresh()` |
| 想改默认加入的常见通讯包 | `MainActivity.kt` | `commonNotificationPkgs` |
| 想改系统应用是否展示 | `MainActivity.kt` | `refresh()` 中 `isSystem` 过滤逻辑 |
| 想改添加应用弹窗的搜索规则 | `PickAppAdapter.kt` | `filter()`、`applyFilter()` |
| 想改添加应用弹窗可选项来源 | `MainActivity.kt` | `openManualAddDialog()` |
| 想改管理列表单行点击/长按行为 | `AppToggleAdapter.kt` | `VH.bind()` |
| 想改应用开关保存逻辑 | `ReadAloudPrefs.kt` | `setReadEnabled()`、`isReadEnabled()` |
| 想改“隐藏应用/移除应用”的保存逻辑 | `ReadAloudPrefs.kt`、`MainActivity.kt` | `addHiddenPackage()`、`removeManualIncludedPackage()`、`onLongPress` |
| 想改主界面按钮文字或提示文字 | `strings.xml` | 对应字符串资源 |
| 想改主界面布局 | `activity_main.xml` | 对应控件 id |
| 想改管理弹窗布局 | `bottom_sheet_manage_apps.xml` | 对应控件 id |
| 想改添加应用弹窗布局 | `dialog_pick_apps.xml` | 对应控件 id |

## 新人阅读建议

如果你刚接手这个项目，建议按这个顺序读：

1. 先读 `MainActivity.kt`，知道用户界面有哪些开关。
2. 再读 `ReadAloudPrefs.kt`，知道这些开关保存在哪里。
3. 再读 `NotificationTtsListenerService.kt`，知道通知如何被解析成朗读文本。
4. 再读 `TtsForegroundService.kt`，知道朗读任务如何排队。
5. 最后读 `TtsSpeaker.kt`，知道 Android TTS 是怎么被调用的。

看代码时可以记住一条线：

```text
用户选择应用
  -> ReadAloudPrefs 保存包名
  -> 通知到达
  -> NotificationTtsListenerService 检查包名和播放模式
  -> extractAnnouncements 解析文本
  -> TtsForegroundService 排队
  -> TtsSpeaker 朗读
```

