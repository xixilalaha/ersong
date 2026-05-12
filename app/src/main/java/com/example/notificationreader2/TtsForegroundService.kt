package com.example.notificationreader2

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

class TtsForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    private var speaker: TtsSpeaker? = null
    private val queue: ArrayDeque<QueueItem> = ArrayDeque()
    private var isSpeaking: Boolean = false
    private val handler by lazy { android.os.Handler(mainLooper) }
    private var stopRunnable: Runnable? = null
    private var speakWatchdog: Runnable? = null
    private var retryRunnable: Runnable? = null
    private var lastStartId: Int = 0
    private var currentEnginePkg: String? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        ensureChannel()
        rebuildSpeakerIfNeeded(desired = desiredEngineFromPrefs(), force = true)
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        stopRunnable?.let { handler.removeCallbacks(it) }
        stopRunnable = null
        speakWatchdog?.let { handler.removeCallbacks(it) }
        speakWatchdog = null
        retryRunnable?.let { handler.removeCallbacks(it) }
        retryRunnable = null
        queue.clear()
        isSpeaking = false
        // MIUI TTS 的完成回调可能早于真实音频尾音，销毁时再多留一点缓冲。
        val sp = speaker
        speaker = null
        if (sp != null) {
            handler.postDelayed({ sp.shutdown() }, 3000)
        }
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        val action = intent?.action
        val text = intent?.getStringExtra(EXTRA_TEXT).orEmpty()
        val texts = intent?.getStringArrayListExtra(EXTRA_TEXTS).orEmpty()
        val collapseKey = intent?.getStringExtra(EXTRA_COLLAPSE_KEY)
        val incoming = when {
            texts.isNotEmpty() -> texts.filter { it.isNotBlank() }
            text.isNotBlank() -> listOf(text)
            else -> emptyList()
        }
        Log.d(TAG, "onStartCommand texts=${incoming.size} textLen=${incoming.sumOf { it.length }}")

        if (action == ACTION_STOP_ALL) {
            Log.d(TAG, "stop all requested")
            stopAllPlaybackAndSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIF_ID, buildNotification())

        if (action == ACTION_RELOAD_ENGINE) {
            Log.d(TAG, "reload engine requested")
            val desired = intent.getStringExtra(EXTRA_ENGINE) ?: desiredEngineFromPrefs()
            rebuildSpeakerIfNeeded(desired = desired, force = true)
            // 不清空队列：继续按队列播报
            isSpeaking = false
            speakNextIfIdle()
            return START_NOT_STICKY
        }

        if (!ReadAloudPrefs.isMasterEnabled(applicationContext)) {
            Log.d(TAG, "skip incoming because master switch is off")
            stopAllPlaybackAndSelf()
            return START_NOT_STICKY
        }

        // 兜底：即使没有显式 reload，也确保当前 speaker 使用最新选择的引擎
        rebuildSpeakerIfNeeded(desired = desiredEngineFromPrefs(), force = false)

        if (incoming.isNotEmpty()) {
            enqueue(collapseKey, incoming)
        }

        // 有新内容进来就取消停止计划
        stopRunnable?.let { handler.removeCallbacks(it) }
        stopRunnable = null

        speakNextIfIdle()
        return START_NOT_STICKY
    }

    private fun stopAllPlaybackAndSelf() {
        stopRunnable?.let { handler.removeCallbacks(it) }
        stopRunnable = null
        speakWatchdog?.let { handler.removeCallbacks(it) }
        speakWatchdog = null
        retryRunnable?.let { handler.removeCallbacks(it) }
        retryRunnable = null
        queue.clear()
        isSpeaking = false
        speaker?.stopNow()
        stopSelf()
    }

    private fun enqueue(collapseKey: String?, texts: List<String>) {
        if (!collapseKey.isNullOrBlank()) {
            queue.removeAll { it.collapseKey == collapseKey }
        }

        for (text in texts) {
            queue.addLast(QueueItem(collapseKey = collapseKey, text = text))
        }

        while (queue.size > MAX_QUEUE_ITEMS) {
            val dropped = queue.removeFirst()
            Log.d(TAG, "drop old pending utterance collapseKey=${dropped.collapseKey}")
        }
    }

    private fun desiredEngineFromPrefs(): String? {
        return getSharedPreferences("prefs", MODE_PRIVATE).getString("tts_engine", null)
    }

    private fun rebuildSpeakerIfNeeded(desired: String?, force: Boolean) {
        val normalized = desired?.takeIf { it.isNotBlank() }
        if (!force && speaker != null && normalized == currentEnginePkg) return

        val old = speaker
        speaker = TtsSpeaker(this, normalized) { ready ->
            Log.d(TAG, "TTS ready=$ready engine=$normalized")
            if (ready) handler.post { speakNextIfIdle() }
        }
        currentEnginePkg = normalized
        old?.shutdown()
    }

    private fun speakNextIfIdle() {
        if (isSpeaking) return
        val next = queue.firstOrNull() ?: run {
            scheduleStop()
            return
        }
        val sp = speaker ?: run {
            scheduleStop()
            return
        }

        // TTS 还没 ready：不要出队、不要进入 speaking 状态，稍后重试
        if (!sp.isReady()) {
            if (retryRunnable == null) {
                val r = Runnable {
                    retryRunnable = null
                    speakNextIfIdle()
                }
                retryRunnable = r
                handler.postDelayed(r, 300)
            }
            return
        }

        // 真正开始播报前再出队
        queue.removeFirst()
        isSpeaking = true

        // 看门狗：避免某些引擎不回调 onDone 导致队列卡死
        speakWatchdog?.let { handler.removeCallbacks(it) }
        val wd = Runnable {
            Log.w(TAG, "speak watchdog timeout, force next")
            isSpeaking = false
            speakWatchdog = null
            speakNextIfIdle()
        }
        speakWatchdog = wd
        handler.postDelayed(wd, 30000)

        // 部分机型在 startForeground / TTS onInit 刚结束立刻 speak 会首句失败（无声但走 onDone），
        // 短延迟再提交合成，避免用户只听到第二条。
        val textToSpeak = next.text
        handler.postDelayed({
            val spNow = speaker
            if (spNow == null || !spNow.isReady()) {
                queue.addFirst(QueueItem(collapseKey = next.collapseKey, text = textToSpeak))
                isSpeaking = false
                speakWatchdog?.let { handler.removeCallbacks(it) }
                speakWatchdog = null
                speakNextIfIdle()
                return@postDelayed
            }
            spNow.speak(textToSpeak) {
                handler.post {
                    isSpeaking = false
                    speakWatchdog?.let { handler.removeCallbacks(it) }
                    speakWatchdog = null
                    speakNextIfIdle()
                }
            }
        }, 80L)
    }

    private fun scheduleStop() {
        if (stopRunnable != null) return
        val r = Runnable {
            stopRunnable = null
            stopSelfResult(lastStartId)
        }
        stopRunnable = r
        // 给连续通知和短句尾音留足时间，避免服务刚空闲就回收。
        handler.postDelayed(r, 15000)
    }

    private fun buildNotification(): android.app.Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val piFlags = if (Build.VERSION.SDK_INT >= 23) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val contentIntent = PendingIntent.getActivity(this, 0, openIntent, piFlags)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("通知朗读中")
            .setContentText("正在播报最新通知")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val existing = nm.getNotificationChannel(CHANNEL_ID)
        if (existing != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "通知朗读",
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    companion object {
        private const val TAG = "NotifTTS_FG"
        private const val CHANNEL_ID = "notif_tts"
        private const val NOTIF_ID = 1001
        private const val MAX_QUEUE_ITEMS = 8
        private const val ACTION_RELOAD_ENGINE = "com.example.notificationreader2.action.RELOAD_ENGINE"
        private const val ACTION_STOP_ALL = "com.example.notificationreader2.action.STOP_ALL"
        private const val EXTRA_ENGINE = "extra_engine"
        private const val EXTRA_TEXTS = "extra_texts"
        private const val EXTRA_COLLAPSE_KEY = "extra_collapse_key"
        const val EXTRA_TEXT = "extra_text"

        fun start(context: Context, text: String) {
            val i = Intent(context, TtsForegroundService::class.java).apply {
                putExtra(EXTRA_TEXT, text)
            }
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun start(context: Context, texts: List<String>, collapseKey: String?) {
            if (texts.isEmpty()) return
            val i = Intent(context, TtsForegroundService::class.java).apply {
                putStringArrayListExtra(EXTRA_TEXTS, ArrayList(texts))
                putExtra(EXTRA_COLLAPSE_KEY, collapseKey)
            }
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun reloadEngine(context: Context, enginePkg: String?) {
            val i = Intent(context, TtsForegroundService::class.java).apply {
                action = ACTION_RELOAD_ENGINE
                putExtra(EXTRA_ENGINE, enginePkg)
            }
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun stopAll(context: Context) {
            val i = Intent(context, TtsForegroundService::class.java).apply {
                action = ACTION_STOP_ALL
            }
            context.startService(i)
        }
    }

    private data class QueueItem(
        val collapseKey: String?,
        val text: String
    )
}
