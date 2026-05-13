package com.example.notificationreader2

import android.app.Notification
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class NotificationTtsListenerService : NotificationListenerService() {

    private var speaker: TtsSpeaker? = null
    private val handler = Handler(Looper.getMainLooper())
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
        handler.postDelayed({ processRecentActiveNotifications() }, ACTIVE_SCAN_DELAY_MS)
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        handler.removeCallbacksAndMessages(null)
        speaker?.shutdown()
        speaker = null
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        processNotification(sbn, source = "posted")
    }

    private fun processRecentActiveNotifications() {
        val now = System.currentTimeMillis()
        val notifications = try {
            activeNotifications ?: emptyArray()
        } catch (t: Throwable) {
            Log.w(TAG, "active notification scan failed", t)
            return
        }

        Log.d(TAG, "active scan count=${notifications.size}")
        notifications
            .filter { now - it.postTime in 0..ACTIVE_NOTIFICATION_GRACE_MS }
            .forEach { processNotification(it, source = "active") }
    }

    private fun processNotification(sbn: StatusBarNotification, source: String) {
        // 避免把本应用自身（前台服务通知等）再次当作要朗读的通知，造成循环触发
        if (sbn.packageName == packageName) return

        val pkg = sbn.packageName
        Log.d(
            TAG,
            "$source raw pkg=$pkg id=${sbn.id} key=${sbn.key.take(80)} ongoing=${sbn.isOngoing} postTime=${sbn.postTime}"
        )
        // 记录“确实发过通知”的应用，用于管理列表数据源
        ReadAloudPrefs.rememberKnownPackage(applicationContext, pkg)
        // 已移除的应用，如果之后又真实发出通知，重新出现在底部“通知过的应用”区。
        ReadAloudPrefs.removeHiddenPackage(applicationContext, pkg)

        if (!ReadAloudPrefs.isMasterEnabled(applicationContext)) {
            Log.d(TAG, "skip master disabled pkg=$pkg")
            return
        }

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

        Log.d(TAG, "processNotification source=$source pkg=${pkg} id=${sbn.id} ongoing=${sbn.isOngoing}")
        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        val appName = appNameForPackage(pkg)
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val kind = resolveAnnouncementKind(sbn, notification, title)
        val announcements = extractAnnouncements(sbn, notification, appName, title, kind)

        Log.d(
            TAG,
            "parsed app='$appName' title='${title.take(80)}' kind=$kind announcements=${announcements.size} " +
                "preview='${announcements.joinToString(" | ").take(160)}'"
        )
        if (announcements.isEmpty()) {
            Log.d(TAG, "skip empty announcement pkg=$pkg category=${notification.category}")
            return
        }

        // MIUI/部分 ROM 会限制后台绑定 TTS，引导到前台服务朗读更稳定。
        // 同一条聚合通知快速更新时，用 notification key 合并尚未播报的旧版本。
        TtsForegroundService.start(applicationContext, announcements, sbn.key, sbn.packageName)
    }

    private fun extractAnnouncements(
        sbn: StatusBarNotification,
        notification: Notification,
        appName: String,
        title: String,
        kind: AnnouncementKind
    ): List<String> {
        val extras = notification.extras ?: return emptyList()

        if (kind == AnnouncementKind.APP_NEW_MESSAGE_ONLY) {
            return listOf(formatNewMessageOnly(appName))
                .filter { rememberIfNew(sbn.key, sbn.postTime, it) }
        }

        val result = mutableListOf<String>()

        val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (messages != null) {
            for (message in Notification.MessagingStyle.Message.getMessagesFromBundleArray(messages)) {
                val text = message.text?.toString()?.trim().orEmpty()
                if (text.isBlank()) continue
                result.add(formatDetailed(appName, title, text))
            }
        }

        val textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
        if (!textLines.isNullOrEmpty()) {
            for (line in textLines) {
                val text = line?.toString()?.trim().orEmpty()
                if (text.isNotBlank()) result.add(formatDetailed(appName, title, text))
            }
        }

        if (result.isEmpty()) {
            val text = (
                extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                    ?: extras.getCharSequence(Notification.EXTRA_TEXT)
            )?.toString()?.trim().orEmpty()
            if (text.isNotBlank()) {
                result.add(formatDetailed(appName, title, text))
            }
        }

        return result
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .filter { rememberIfNew(sbn.key, sbn.postTime, it) }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun resolveAnnouncementKind(
        sbn: StatusBarNotification,
        notification: Notification,
        title: String
    ): AnnouncementKind {
        return when (ReadAloudPrefs.getAnnouncementMode(applicationContext, sbn.packageName)) {
            ReadAloudPrefs.AnnouncementMode.TITLE_ONLY -> AnnouncementKind.APP_NEW_MESSAGE_ONLY
            ReadAloudPrefs.AnnouncementMode.DETAIL -> AnnouncementKind.APP_TITLE_DETAIL
        }
    }

    private fun formatDetailed(appName: String, title: String, detail: String): String {
        return joinNotBlank(appName, title, detail)
    }

    private fun formatNewMessageOnly(appName: String): String {
        return listOf(appName.trim(), "你有一条新消息")
            .filter { it.isNotBlank() }
            .joinToString(" ")
    }

    private fun joinNotBlank(vararg parts: String): String {
        return parts
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString("：")
    }

    private fun appNameForPackage(pkg: String): String {
        return try {
            val appInfo = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(appInfo).toString().trim().ifBlank { pkg }
        } catch (_: Throwable) {
            pkg
        }
    }

    private fun rememberIfNew(notificationKey: String, postTime: Long, text: String): Boolean {
        val now = System.currentTimeMillis()
        val cutoff = now - RECENT_TTL_MS
        val iterator = recentSpoken.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value < cutoff) iterator.remove()
        }

        // 微信等 MessagingStyle 通知常在同一摘要上连续多次 onNotificationPosted，且 postTime 每次刷新。
        // 若去重键含 postTime，同一句正文会被当成「新通知」重复入队。详情类播报用 key+正文 即可。
        // 「仅新消息」模式多条文案相同，仍须带 postTime 区分。
        val key = dedupeKey(notificationKey, postTime, text)
        if (recentSpoken.containsKey(key)) {
            Log.d(TAG, "dedupe skip key=${key.take(120)}")
            return false
        }
        recentSpoken[key] = now
        return true
    }

    /** 与 [formatNewMessageOnly] 一致：应用名 +「你有一条新消息」，不含详情里的「：」。 */
    private fun isGenericNewMessageOnlyAnnouncement(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        return t.contains("你有一条新消息") && !t.contains("：")
    }

    private fun dedupeKey(notificationKey: String, postTime: Long, text: String): String {
        return if (isGenericNewMessageOnlyAnnouncement(text)) {
            "$notificationKey|$postTime|$text"
        } else {
            "$notificationKey|$text"
        }
    }

    companion object {
        private const val TAG = "NotifTTS"
        private const val MAX_RECENT_SPOKEN = 200
        private const val RECENT_TTL_MS = 2 * 60 * 1000L
        private const val ACTIVE_SCAN_DELAY_MS = 500L
        private const val ACTIVE_NOTIFICATION_GRACE_MS = 2 * 60 * 1000L
    }

    private enum class AnnouncementKind {
        APP_TITLE_DETAIL,
        APP_NEW_MESSAGE_ONLY
    }
}
