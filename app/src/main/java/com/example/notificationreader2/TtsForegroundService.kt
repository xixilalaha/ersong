package com.example.notificationreader2

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

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
    private var mediaSession: MediaSession? = null
    private val externalMediaMonitors = mutableMapOf<MediaSession.Token, ExternalMediaMonitor>()
    private var externalMediaMonitoringRegistered: Boolean = false
    private var externalPauseDetectionArmed: Boolean = false
    private var armExternalPauseDetectionRunnable: Runnable? = null
    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private val mediaSessionManager by lazy {
        getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    }
    private val speechAudioAttributes by lazy {
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }
    private val duckingFocusRequest by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(speechAudioAttributes)
            .setAcceptsDelayedFocusGain(false)
            .setForceDucking(true)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener({ change ->
                Log.d(TAG, "audio focus change=$change")
            }, handler)
            .build()
    }
    private var hasDuckingAudioFocus: Boolean = false
    private val mediaSessionCallback = object : MediaSession.Callback() {
        override fun onPause() {
            stopFromMediaControl("pause")
        }

        override fun onStop() {
            stopFromMediaControl("stop")
        }
    }
    private val activeSessionsChangedListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            syncExternalMediaControllers(controllers.orEmpty())
        }
    /** 当前正在朗读的条目所属通知包名（用于接听电话后取消「电话播报」） */
    private var currentlySpeakingSourcePackage: String? = null
    /** API 31+ 为 [TelephonyCallback]；否则为 [PhoneStateListener] */
    private var callStateListenerHolder: Any? = null
    /** 用户解锁后清空待播队列（当前句仍播完），见 [ReadAloudPrefs.isOnlyLockedOrScreenOffEnabled] */
    private var userUnlockReceiver: BroadcastReceiver? = null

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    private val legacyCallStateListener: PhoneStateListener = object : PhoneStateListener() {
        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
            handler.post { onCallStateChangedInternal(state) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        ensureChannel()
        registerUserUnlockReceiver()
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
        currentlySpeakingSourcePackage = null
        stopExternalMediaPauseMonitoring()
        releaseMediaSession()
        abandonDuckingAudioFocus()
        unregisterUserUnlockReceiver()
        unregisterCallStateListener()
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
        val sourcePackage = intent?.getStringExtra(EXTRA_SOURCE_PACKAGE)
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

        if (CallStateUtils.isCallActive(applicationContext)) {
            Log.d(TAG, "skip incoming because phone call is active")
            stopAllPlaybackAndSelf()
            return START_NOT_STICKY
        }

        // 兜底：即使没有显式 reload，也确保当前 speaker 使用最新选择的引擎
        rebuildSpeakerIfNeeded(desired = desiredEngineFromPrefs(), force = false)

        if (incoming.isNotEmpty()) {
            if (ReadAloudPrefs.isOnlyLockedOrScreenOffEnabled(applicationContext) &&
                !PlaybackEnvironmentGate.shouldAcceptNewPlayback(applicationContext)
            ) {
                Log.d(TAG, "skip enqueue: screen on and unlocked")
            } else {
                enqueue(collapseKey, incoming, sourcePackage)
            }
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
        currentlySpeakingSourcePackage = null
        deactivateMediaSession()
        speaker?.stopNow()
        abandonDuckingAudioFocus()
        unregisterUserUnlockReceiver()
        unregisterCallStateListener()
        stopSelf()
    }

    private fun registerUserUnlockReceiver() {
        if (userUnlockReceiver != null) return
        userUnlockReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                handler.post { onUserUnlockBroadcast(intent?.action) }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                addAction(Intent.ACTION_USER_UNLOCKED)
            }
        }
        ContextCompat.registerReceiver(
            this,
            userUnlockReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun unregisterUserUnlockReceiver() {
        val r = userUnlockReceiver ?: return
        try {
            unregisterReceiver(r)
        } catch (_: Throwable) {
        }
        userUnlockReceiver = null
    }

    private fun onUserUnlockBroadcast(action: String?) {
        if (!ReadAloudPrefs.isOnlyLockedOrScreenOffEnabled(applicationContext)) return
        val ok = when (action) {
            Intent.ACTION_USER_PRESENT -> true
            Intent.ACTION_USER_UNLOCKED -> true
            else -> false
        }
        if (!ok) return
        drainPendingQueueDueToUserUnlock()
    }

    /** 解锁后不中断当前句，仅丢弃队列中尚未开始的条目 */
    private fun drainPendingQueueDueToUserUnlock() {
        val n = queue.size
        if (n == 0) return
        queue.clear()
        Log.d(TAG, "user unlocked: cleared pending queue (dropped $n)")
        syncCallStateListenerRegistration()
    }

    private fun enqueue(collapseKey: String?, texts: List<String>, sourcePackage: String?) {
        if (!collapseKey.isNullOrBlank()) {
            queue.removeAll { it.collapseKey == collapseKey }
        }

        for (text in texts) {
            queue.addLast(QueueItem(collapseKey = collapseKey, text = text, sourcePackage = sourcePackage))
        }

        while (queue.size > MAX_QUEUE_ITEMS) {
            val dropped = queue.removeFirst()
            Log.d(TAG, "drop old pending utterance collapseKey=${dropped.collapseKey}")
        }
        syncCallStateListenerRegistration()
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
        syncCallStateListenerRegistration()
        if (isSpeaking) return
        if (CallStateUtils.isCallActive(applicationContext)) {
            Log.d(TAG, "cancel playback because phone call is active before speak")
            stopAllPlaybackAndSelf()
            return
        }
        val next = queue.firstOrNull() ?: run {
            deactivateMediaSession()
            abandonDuckingAudioFocus()
            scheduleStop()
            return
        }
        val sp = speaker ?: run {
            deactivateMediaSession()
            abandonDuckingAudioFocus()
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
        currentlySpeakingSourcePackage = next.sourcePackage

        // 看门狗：避免某些引擎不回调 onDone 导致队列卡死
        speakWatchdog?.let { handler.removeCallbacks(it) }
        val wd = Runnable {
            Log.w(TAG, "speak watchdog timeout, force next")
            isSpeaking = false
            currentlySpeakingSourcePackage = null
            speakWatchdog = null
            abandonDuckingAudioFocus()
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
                queue.addFirst(QueueItem(collapseKey = next.collapseKey, text = textToSpeak, sourcePackage = next.sourcePackage))
                isSpeaking = false
                currentlySpeakingSourcePackage = null
                speakWatchdog?.let { handler.removeCallbacks(it) }
                speakWatchdog = null
                deactivateMediaSession()
                abandonDuckingAudioFocus()
                speakNextIfIdle()
                return@postDelayed
            }
            activateMediaSessionForPlayback()
            requestDuckingAudioFocus()
            spNow.speak(textToSpeak) {
                handler.post {
                    val shouldRefreshSpeaker = spNow === speaker && spNow.shouldRefreshAfterSpeak()
                    isSpeaking = false
                    currentlySpeakingSourcePackage = null
                    speakWatchdog?.let { handler.removeCallbacks(it) }
                    speakWatchdog = null
                    if (queue.isEmpty()) {
                        deactivateMediaSession()
                        abandonDuckingAudioFocus()
                    }
                    if (shouldRefreshSpeaker) {
                        Log.d(TAG, "refresh TTS speaker after stable session limit")
                        rebuildSpeakerIfNeeded(desired = desiredEngineFromPrefs(), force = true)
                    }
                    speakNextIfIdle()
                }
            }
        }, 80L)
    }

    /**
     * 播报期间临时对外声明为「正在播放」，让耳机暂停键优先交给本服务。
     * 它不会暂停原来的音乐；停止 TTS 并释放 duck 焦点后，音乐会恢复正常音量。
     */
    private fun activateMediaSessionForPlayback() {
        startExternalMediaPauseMonitoring()
        val session = mediaSession ?: MediaSession(this, MEDIA_SESSION_TAG).also {
            it.setCallback(mediaSessionCallback, handler)
            it.setPlaybackToLocal(speechAudioAttributes)
            mediaSession = it
        }
        session.setPlaybackState(
            PlaybackState.Builder()
                .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .setActions(
                    PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_STOP
                )
                .build()
        )
        session.isActive = true
        Log.d(TAG, "media session activated for TTS")
    }

    private fun deactivateMediaSession() {
        stopExternalMediaPauseMonitoring()
        val session = mediaSession ?: return
        try {
            session.setPlaybackState(
                PlaybackState.Builder()
                    .setState(PlaybackState.STATE_STOPPED, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 0f)
                    .setActions(0L)
                    .build()
            )
            session.isActive = false
            Log.d(TAG, "media session deactivated")
        } catch (t: Throwable) {
            Log.w(TAG, "deactivate media session failed", t)
        }
    }

    private fun releaseMediaSession() {
        val session = mediaSession ?: return
        mediaSession = null
        try {
            session.setCallback(null)
            session.isActive = false
            session.release()
            Log.d(TAG, "media session released")
        } catch (t: Throwable) {
            Log.w(TAG, "release media session failed", t)
        }
    }

    private fun stopFromMediaControl(command: String) {
        if (!hasPlaybackWork()) {
            deactivateMediaSession()
            return
        }
        Log.d(TAG, "media control=$command: stop current TTS and clear pending=${queue.size}")
        stopAllPlaybackAndSelf()
    }

    /**
     * 部分定制系统会固定把蓝牙暂停键发给原音乐应用，即使 TTS 的会话已激活。
     * 此时通过已授权的 NotificationListener 监听原媒体会话：若它在播报期间
     * 从 PLAYING 变为 PAUSED/STOPPED，就按用户的「停止所有播报」意图处理。
     */
    private fun startExternalMediaPauseMonitoring() {
        if (externalMediaMonitoringRegistered) return
        val listenerComponent = ComponentName(this, NotificationTtsListenerService::class.java)
        try {
            mediaSessionManager.addOnActiveSessionsChangedListener(
                activeSessionsChangedListener,
                listenerComponent,
                handler
            )
            externalMediaMonitoringRegistered = true
            syncExternalMediaControllers(mediaSessionManager.getActiveSessions(listenerComponent))

            externalPauseDetectionArmed = false
            val arm = Runnable {
                armExternalPauseDetectionRunnable = null
                externalPauseDetectionArmed = hasPlaybackWork()
                Log.d(TAG, "external media pause detection armed=$externalPauseDetectionArmed")
            }
            armExternalPauseDetectionRunnable = arm
            handler.postDelayed(arm, EXTERNAL_PAUSE_ARM_DELAY_MS)
        } catch (t: Throwable) {
            Log.w(TAG, "start external media pause monitoring failed", t)
            stopExternalMediaPauseMonitoring()
        }
    }

    private fun syncExternalMediaControllers(controllers: List<MediaController>) {
        if (!externalMediaMonitoringRegistered) return
        val externalControllers = controllers
            .filter { it.packageName != packageName }
            .associateBy { it.sessionToken }

        val removedTokens = externalMediaMonitors.keys - externalControllers.keys
        for (token in removedTokens) {
            externalMediaMonitors.remove(token)?.let { monitor ->
                try {
                    monitor.controller.unregisterCallback(monitor.callback)
                } catch (_: Throwable) {
                }
            }
        }

        for ((token, controller) in externalControllers) {
            if (externalMediaMonitors.containsKey(token)) continue
            val callback = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    onExternalPlaybackStateChanged(token, state?.state ?: PlaybackState.STATE_NONE)
                }
            }
            val monitor = ExternalMediaMonitor(
                controller = controller,
                callback = callback,
                lastPlaybackState = controller.playbackState?.state ?: PlaybackState.STATE_NONE
            )
            externalMediaMonitors[token] = monitor
            try {
                controller.registerCallback(callback, handler)
                Log.d(
                    TAG,
                    "monitor external media pkg=${controller.packageName} state=${monitor.lastPlaybackState}"
                )
            } catch (t: Throwable) {
                externalMediaMonitors.remove(token)
                Log.w(TAG, "register external media callback failed pkg=${controller.packageName}", t)
            }
        }
    }

    private fun onExternalPlaybackStateChanged(token: MediaSession.Token, newState: Int) {
        val monitor = externalMediaMonitors[token] ?: return
        val oldState = monitor.lastPlaybackState
        monitor.lastPlaybackState = newState
        Log.d(
            TAG,
            "external media state pkg=${monitor.controller.packageName} $oldState->$newState " +
                "armed=$externalPauseDetectionArmed"
        )

        val stoppedByMediaControl =
            oldState == PlaybackState.STATE_PLAYING &&
                (newState == PlaybackState.STATE_PAUSED || newState == PlaybackState.STATE_STOPPED)
        if (externalPauseDetectionArmed && stoppedByMediaControl && hasPlaybackWork()) {
            Log.d(TAG, "external media paused during TTS: stop current and clear pending=${queue.size}")
            stopAllPlaybackAndSelf()
        }
    }

    private fun stopExternalMediaPauseMonitoring() {
        armExternalPauseDetectionRunnable?.let { handler.removeCallbacks(it) }
        armExternalPauseDetectionRunnable = null
        externalPauseDetectionArmed = false

        if (externalMediaMonitoringRegistered) {
            try {
                mediaSessionManager.removeOnActiveSessionsChangedListener(activeSessionsChangedListener)
            } catch (t: Throwable) {
                Log.w(TAG, "remove active media sessions listener failed", t)
            }
        }
        externalMediaMonitoringRegistered = false

        for (monitor in externalMediaMonitors.values) {
            try {
                monitor.controller.unregisterCallback(monitor.callback)
            } catch (_: Throwable) {
            }
        }
        externalMediaMonitors.clear()
    }

    private fun requestDuckingAudioFocus() {
        if (hasDuckingAudioFocus) return
        val result = try {
            audioManager.requestAudioFocus(duckingFocusRequest)
        } catch (t: Throwable) {
            Log.w(TAG, "request audio focus failed", t)
            AudioManager.AUDIOFOCUS_REQUEST_FAILED
        }
        hasDuckingAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (hasDuckingAudioFocus) {
            Log.d(TAG, "ducking audio focus granted")
        } else {
            Log.w(TAG, "ducking audio focus not granted result=$result")
        }
    }

    private fun abandonDuckingAudioFocus() {
        if (!hasDuckingAudioFocus) return
        try {
            audioManager.abandonAudioFocusRequest(duckingFocusRequest)
        } catch (t: Throwable) {
            Log.w(TAG, "abandon audio focus failed", t)
        }
        hasDuckingAudioFocus = false
        Log.d(TAG, "ducking audio focus abandoned")
    }

    private fun hasPlaybackWork(): Boolean {
        return isSpeaking || queue.isNotEmpty()
    }

    private fun syncCallStateListenerRegistration() {
        if (hasPlaybackWork()) {
            registerCallStateListenerIfPossible()
        } else {
            unregisterCallStateListener()
        }
    }

    private fun registerCallStateListenerIfPossible() {
        if (callStateListenerHolder != null) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val tm = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) {
                        handler.post { onCallStateChangedInternal(state) }
                    }
                }
                tm.registerTelephonyCallback(mainExecutor, cb)
                callStateListenerHolder = cb
            } else {
                @Suppress("DEPRECATION")
                tm.listen(legacyCallStateListener, PhoneStateListener.LISTEN_CALL_STATE)
                callStateListenerHolder = legacyCallStateListener
            }
            Log.d(TAG, "telephony call-state listener registered")
        } catch (t: Throwable) {
            Log.w(TAG, "register telephony listener failed", t)
        }
    }

    private fun unregisterCallStateListener() {
        val holder = callStateListenerHolder ?: return
        callStateListenerHolder = null
        val tm = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        try {
            when (holder) {
                is TelephonyCallback -> tm.unregisterTelephonyCallback(holder)
                is PhoneStateListener -> {
                    @Suppress("DEPRECATION")
                    tm.listen(holder, PhoneStateListener.LISTEN_NONE)
                }
            }
            Log.d(TAG, "telephony call-state listener unregistered")
        } catch (t: Throwable) {
            Log.w(TAG, "unregister telephony listener failed", t)
        }
    }

    private fun onCallStateChangedInternal(state: Int) {
        if (!CallStateUtils.isActiveState(state)) return
        if (!hasPlaybackWork()) return
        Log.d(TAG, "phone call state=$state: cancel TTS playback")
        stopAllPlaybackAndSelf()
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
        private const val MEDIA_SESSION_TAG = "NotificationTtsPlayback"
        private const val EXTERNAL_PAUSE_ARM_DELAY_MS = 400L
        private const val NOTIF_ID = 1001
        private const val MAX_QUEUE_ITEMS = 8
        private const val ACTION_RELOAD_ENGINE = "com.example.notificationreader2.action.RELOAD_ENGINE"
        private const val ACTION_STOP_ALL = "com.example.notificationreader2.action.STOP_ALL"
        private const val EXTRA_ENGINE = "extra_engine"
        private const val EXTRA_TEXTS = "extra_texts"
        private const val EXTRA_COLLAPSE_KEY = "extra_collapse_key"
        private const val EXTRA_SOURCE_PACKAGE = "extra_source_pkg"
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

        fun start(context: Context, texts: List<String>, collapseKey: String?, sourcePackage: String?) {
            if (texts.isEmpty()) return
            val i = Intent(context, TtsForegroundService::class.java).apply {
                putStringArrayListExtra(EXTRA_TEXTS, ArrayList(texts))
                putExtra(EXTRA_COLLAPSE_KEY, collapseKey)
                putExtra(EXTRA_SOURCE_PACKAGE, sourcePackage)
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
        val text: String,
        val sourcePackage: String?
    )

    private data class ExternalMediaMonitor(
        val controller: MediaController,
        val callback: MediaController.Callback,
        var lastPlaybackState: Int
    )
}
