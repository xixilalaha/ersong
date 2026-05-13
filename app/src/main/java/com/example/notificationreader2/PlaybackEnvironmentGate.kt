package com.example.notificationreader2

import android.app.KeyguardManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.PowerManager
import android.view.Display

/**
 * 用于「仅在锁屏或息屏时播报」：亮屏且已解锁时不接受新的朗读任务。
 *
 * 判定顺序（避免 OEM 在黑屏/省电息屏下仍上报「亮屏 + 未锁屏」导致误拦）：
 * 1. [PowerManager.isInteractive] 为 false → 允许。
 * 2. 默认显示器状态不是 [Display.STATE_ON] → 允许（含 [Display.STATE_OFF]、[Display.STATE_DOZE]、
 *    [Display.STATE_DOZE_SUSPEND] 等；黑屏常表现为 DOZE 而非 OFF）。
 * 3. 否则视为「正常亮屏交互」→ 仅当 [KeyguardManager.isKeyguardLocked] 为 true（锁屏界面）时允许。
 */
object PlaybackEnvironmentGate {

    fun shouldAcceptNewPlayback(context: Context): Boolean {
        val app = context.applicationContext

        val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isInteractive) {
            return true
        }

        val dm = app.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = dm.getDisplay(Display.DEFAULT_DISPLAY)
        if (display.state != Display.STATE_ON) {
            return true
        }

        val km = app.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        return km.isKeyguardLocked
    }
}
