package com.example.notificationreader2

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.provider.Settings
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class TtsSpeaker(
    context: Context,
    private val engineOverride: String? = null,
    private val onReady: (Boolean) -> Unit = {}
) : TextToSpeech.OnInitListener {

    private val tag = "NotifTTS"
    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = null
    @Volatile private var ready: Boolean = false
    @Volatile private var pendingText: String? = null
    private var createdAtMs: Long = 0L
    private var initAttempts: Int = 0
    private val doneCallbacks: ConcurrentHashMap<String, () -> Unit> = ConcurrentHashMap()
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var listenerInstalled: Boolean = false

    fun isReady(): Boolean = ready

    init {
        startInit("init")
    }

    private fun startInit(reason: String) {
        initAttempts += 1
        createdAtMs = System.currentTimeMillis()
        val override = engineOverride?.takeIf { it.isNotBlank() }
        val prefEngine = try {
            appContext.getSharedPreferences("prefs", Context.MODE_PRIVATE)
                .getString("tts_engine", null)
        } catch (_: Throwable) {
            null
        }
        val defaultEngine = try {
            Settings.Secure.getString(appContext.contentResolver, "tts_default_synth")
        } catch (_: Throwable) {
            null
        }
        val engineToUse = override ?: prefEngine ?: defaultEngine
        Log.d(tag, "TTS startInit attempt=$initAttempts reason=$reason engine=$engineToUse pref=$prefEngine default=$defaultEngine")

        // 在部分 MIUI 机型上，显式指定默认引擎更稳定
        tts = try {
            if (!engineToUse.isNullOrBlank()) {
                TextToSpeech(appContext, this, engineToUse)
            } else {
                TextToSpeech(appContext, this)
            }
        } catch (_: Throwable) {
            TextToSpeech(appContext, this)
        }
    }

    override fun onInit(status: Int) {
        Log.d(tag, "TTS onInit status=$status")
        val engine = tts ?: run {
            ready = false
            onReady(false)
            return
        }

        if (status != TextToSpeech.SUCCESS) {
            ready = false
            onReady(false)
            return
        }

        if (!listenerInstalled) {
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}

                override fun onDone(utteranceId: String?) {
                    if (utteranceId == null) return
                    doneCallbacks.remove(utteranceId)?.invoke()
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    if (utteranceId == null) return
                    doneCallbacks.remove(utteranceId)?.invoke()
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    if (utteranceId == null) return
                    doneCallbacks.remove(utteranceId)?.invoke()
                }
            })
            listenerInstalled = true
        }

        val candidates = listOf(
            Locale.SIMPLIFIED_CHINESE,
            Locale.CHINA,
            Locale("zh", "CN")
        )
        var ok = false
        var lastResult = TextToSpeech.LANG_NOT_SUPPORTED
        for (loc in candidates) {
            val r = engine.setLanguage(loc)
            lastResult = r
            if (r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED) {
                ok = true
                break
            }
        }
        ready = ok
        Log.d(tag, "TTS setLanguage zh result=$lastResult ready=$ready")
        onReady(ready)

        val toSpeak = pendingText
        if (ready && !toSpeak.isNullOrBlank()) {
            pendingText = null
            speak(toSpeak, onDone = null)
        }
    }

    fun speak(text: String) {
        speak(text, onDone = null)
    }

    fun speak(text: String, onDone: (() -> Unit)?) {
        val engine = tts ?: return
        if (!ready) {
            pendingText = text
            val now = System.currentTimeMillis()
            val ageMs = now - createdAtMs
            Log.d(tag, "speak queued: not ready (len=${text.length}) ageMs=$ageMs attempts=$initAttempts")

            if (ageMs > 5000 && initAttempts < 3) {
                Log.d(tag, "TTS not ready too long, retry init")
                try {
                    engine.shutdown()
                } catch (_: Throwable) {
                }
                tts = null
                ready = false
                startInit("retry_after_${ageMs}ms")
            }
            return
        }
        if (text.isBlank()) {
            Log.d(tag, "speak ignored: blank text")
            return
        }

        val safeText = text.withTerminalPauseMarker()
        val utteranceId = "notif_${System.nanoTime()}"
        Log.d(tag, "speak utteranceId=$utteranceId text='${text.take(140)}'")

        try {
            engine.setSpeechRate(1.0f)
            engine.setPitch(1.0f)
        } catch (t: Throwable) {
            Log.w(tag, "reset voice params failed", t)
        }

        if (onDone != null) {
            doneCallbacks[utteranceId] = {
                handler.postDelayed(onDone, text.tailGuardDelayMs())
            }
        }
        // 不打断：依次播报
        val result = engine.speak(safeText, TextToSpeech.QUEUE_ADD, null, utteranceId)
        if (result != TextToSpeech.SUCCESS) {
            Log.w(tag, "speak failed result=$result utteranceId=$utteranceId")
            doneCallbacks.remove(utteranceId)?.invoke()
        }
    }

    private fun String.withTerminalPauseMarker(): String {
        val trimmed = trim()
        if (trimmed.isEmpty()) return trimmed
        val last = trimmed.last()
        val hasTerminalPunctuation = last in "。！？!?."
        if (hasTerminalPunctuation) return trimmed

        val withoutHangingPause = trimmed.trimEnd('，', ',', '、', '；', ';', '：', ':')
        return if (withoutHangingPause.isEmpty()) trimmed else "$withoutHangingPause。"
    }

    private fun String.tailGuardDelayMs(): Long {
        val trimmed = trim()
        val lastSegment = trimmed.substringAfterLast('：').trim()
        return when {
            lastSegment.length <= 1 -> 1200L
            trimmed.length <= 8 -> 900L
            else -> 500L
        }
    }

    fun shutdown() {
        val engine = tts ?: return
        tts = null
        ready = false
        pendingText = null
        doneCallbacks.clear()
        listenerInstalled = false
        Log.d(tag, "TTS shutdown")
        // 部分引擎的 onDone 回调早于真实音频结束；此处不要 stop()，避免切断尾音
        engine.shutdown()
    }

    fun stopNow() {
        val engine = tts ?: return
        pendingText = null
        doneCallbacks.clear()
        try {
            engine.stop()
        } catch (t: Throwable) {
            Log.w(tag, "TTS stop failed", t)
        }
    }
}
