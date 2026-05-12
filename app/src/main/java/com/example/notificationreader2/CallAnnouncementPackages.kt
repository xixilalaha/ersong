package com.example.notificationreader2

/**
 * 与系统来电/通话通知相关的包名；用于识别「电话播报」并在用户接听后停止 TTS。
 */
object CallAnnouncementPackages {
    private val PACKAGES = setOf(
        "com.android.incallui",
        "com.android.server.telecom",
        "com.android.phone",
        "com.android.contacts",
        "com.google.android.dialer",
        "com.miui.contacts",
        "com.miui.telecom",
        "com.samsung.android.incallui",
        "com.huawei.android.incallui",
        "com.oplus.incallui"
    )

    fun isCallAnnouncementPackage(pkg: String?): Boolean {
        if (pkg.isNullOrBlank()) return false
        return pkg in PACKAGES
    }
}
