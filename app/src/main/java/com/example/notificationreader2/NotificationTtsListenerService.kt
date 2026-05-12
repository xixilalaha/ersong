package com.example.notificationreader2

import android.app.Notification
import android.util.Log
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class NotificationTtsListenerService : NotificationListenerService() {

    private var speaker: TtsSpeaker? = null
    private val recentSpoken = object : LinkedHashMap<String, Long>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean {
            return size > MAX_RECENT_SPOKEN
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        speaker = TtsSpeaker(this) { ready ->
            Log.d(TAG, "TTS ready=$ready")
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "onListenerConnected (notification access granted)")
        if (speaker == null) {
            speaker = TtsSpeaker(this) { ready ->
                Log.d(TAG, "TTS ready=$ready")
            }
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        speaker?.shutdown()
        speaker = null
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // 避免把本应用自身（前台服务通知等）再次当作要朗读的通知，造成循环触发
        if (sbn.packageName == packageName) return

        val pkg = sbn.packageName
        Log.d(
            TAG,
            "posted raw pkg=$pkg id=${sbn.id} key=${sbn.key.take(80)} ongoing=${sbn.isOngoing}"
        )
        // 记录“确实发过通知”的应用，用于管理列表数据源
        ReadAloudPrefs.rememberKnownPackage(applicationContext, pkg)

        // 默认关闭：仅当用户在列表里手动开启该应用时才朗读
        if (!ReadAloudPrefs.isReadEnabled(applicationContext, pkg)) {
            Log.d(TAG, "skip disabled pkg=$pkg")
            return
        }

        // 蓝牙模式：仅连接蓝牙音频输出时才朗读；正常模式不限制路由
        if (ReadAloudPrefs.getPlaybackRouteMode(applicationContext) == ReadAloudPrefs.PlaybackRouteMode.BLUETOOTH) {
            if (!AudioRouteUtils.isBluetoothHeadsetConnected(applicationContext)) {
                Log.d(TAG, "skip bluetooth mode without headset pkg=$pkg")
                return
            }
        }

        Log.d(TAG, "onNotificationPosted pkg=${pkg} id=${sbn.id} ongoing=${sbn.isOngoing}")
        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val announcements = extractAnnouncements(sbn, notification, title)

        Log.d(
            TAG,
            "parsed title='${title.take(80)}' announcements=${announcements.size} " +
                "preview='${announcements.joinToString(" | ").take(160)}'"
        )
        if (announcements.isEmpty()) {
            Log.d(TAG, "skip empty announcement pkg=$pkg category=${notification.category}")
            return
        }

        // MIUI/部分 ROM 会限制后台绑定 TTS，引导到前台服务朗读更稳定。
        // 同一条聚合通知快速更新时，用 notification key 合并尚未播报的旧版本。
        TtsForegroundService.start(applicationContext, announcements, sbn.key)
    }

    private fun extractAnnouncements(
        sbn: StatusBarNotification,
        notification: Notification,
        title: String
    ): List<String> {
        val extras = notification.extras ?: return emptyList()
        val result = mutableListOf<String>()

        val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (messages != null) {
            for (message in Notification.MessagingStyle.Message.getMessagesFromBundleArray(messages)) {
                val text = message.text?.toString()?.trim().orEmpty()
                if (text.isBlank()) continue
                val sender = title.ifBlank {
                    message.senderPerson?.name?.toString()
                        ?: message.sender?.toString()
                        ?: ""
                }
                result.add(joinSenderAndText(sender, text))
            }
        }

        val textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
        if (!textLines.isNullOrEmpty()) {
            for (line in textLines) {
                val text = line?.toString()?.trim().orEmpty()
                if (text.isNotBlank()) result.add(text)
            }
        }

        if (result.isEmpty()) {
            val text = (
                extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                    ?: extras.getCharSequence(Notification.EXTRA_TEXT)
            )?.toString()?.trim().orEmpty()
            if (text.isNotBlank()) {
                result.add(joinSenderAndText(title, text))
            }
        }

        return result
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .filter { rememberIfNew(sbn.key, it) }
    }

    private fun joinSenderAndText(sender: String, text: String): String {
        val cleanSender = sender.trim()
        return if (cleanSender.isBlank()) text else "$cleanSender：$text"
    }

    private fun rememberIfNew(notificationKey: String, text: String): Boolean {
        val now = System.currentTimeMillis()
        val cutoff = now - RECENT_TTL_MS
        val iterator = recentSpoken.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value < cutoff) iterator.remove()
        }

        val key = "$notificationKey|$text"
        if (recentSpoken.containsKey(key)) return false
        recentSpoken[key] = now
        return true
    }

    companion object {
        private const val TAG = "NotifTTS"
        private const val MAX_RECENT_SPOKEN = 200
        private const val RECENT_TTL_MS = 2 * 60 * 1000L
    }
}
