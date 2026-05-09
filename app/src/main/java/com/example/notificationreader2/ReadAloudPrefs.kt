package com.example.notificationreader2

import android.content.Context

object ReadAloudPrefs {
    private const val PREFS = "prefs"
    private const val KEY_ENABLED = "read_aloud_enabled"
    private const val KEY_KNOWN = "known_notification_apps"
    private const val KEY_PLAYBACK_ROUTE = "read_aloud_playback_route"
    private const val KEY_MANUAL_INCLUDED = "read_aloud_manual_included"
    private const val KEY_HIDDEN = "read_aloud_hidden"

    enum class PlaybackRouteMode {
        /** 仅已连接蓝牙耳机等蓝牙音频设备时播报 */
        BLUETOOTH,
        /** 不按蓝牙限制，走系统默认音频输出（扬声器/有线等） */
        NORMAL
    }
    // 旧版本遗留：默认全开/禁用名单；升级后统一迁移到 enabled 名单
    private const val KEY_DISABLED_LEGACY = "read_aloud_disabled"

    fun isReadEnabled(context: Context, pkg: String): Boolean {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        migrateIfNeeded(sp)
        val enabled = sp.getStringSet(KEY_ENABLED, emptySet()).orEmpty()
        // 新逻辑：默认关闭；只有在 enabled 集合里才朗读
        return enabled.contains(pkg)
    }

    fun setReadEnabled(context: Context, pkg: String, enabled: Boolean) {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        migrateIfNeeded(sp)
        val set = sp.getStringSet(KEY_ENABLED, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (enabled) set.add(pkg) else set.remove(pkg)
        sp.edit().putStringSet(KEY_ENABLED, set).apply()
    }

    fun getEnabledPackages(context: Context): Set<String> {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        migrateIfNeeded(sp)
        return sp.getStringSet(KEY_ENABLED, emptySet()).orEmpty()
    }

    fun rememberKnownPackage(context: Context, pkg: String) {
        if (pkg.isBlank()) return
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        migrateIfNeeded(sp)
        val set = sp.getStringSet(KEY_KNOWN, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (set.add(pkg)) {
            sp.edit().putStringSet(KEY_KNOWN, set).apply()
        }
    }

    fun getKnownPackages(context: Context): Set<String> {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        migrateIfNeeded(sp)
        return sp.getStringSet(KEY_KNOWN, emptySet()).orEmpty()
    }

    fun getManualIncludedPackages(context: Context): Set<String> {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        migrateIfNeeded(sp)
        return sp.getStringSet(KEY_MANUAL_INCLUDED, emptySet()).orEmpty()
    }

    fun addManualIncludedPackages(context: Context, pkgs: Collection<String>) {
        if (pkgs.isEmpty()) return
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        migrateIfNeeded(sp)
        val set = sp.getStringSet(KEY_MANUAL_INCLUDED, emptySet())?.toMutableSet() ?: mutableSetOf()
        val toAdd = pkgs.filter { it.isNotBlank() }
        val changed = set.addAll(toAdd)
        val hidden = sp.getStringSet(KEY_HIDDEN, emptySet())?.toMutableSet() ?: mutableSetOf()
        val unhideChanged = hidden.removeAll(toAdd.toSet())
        if (changed || unhideChanged) {
            sp.edit()
                .putStringSet(KEY_MANUAL_INCLUDED, set)
                .putStringSet(KEY_HIDDEN, hidden)
                .apply()
        }
    }

    fun removeManualIncludedPackage(context: Context, pkg: String) {
        if (pkg.isBlank()) return
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        migrateIfNeeded(sp)
        val set = sp.getStringSet(KEY_MANUAL_INCLUDED, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (set.remove(pkg)) sp.edit().putStringSet(KEY_MANUAL_INCLUDED, set).apply()
    }

    fun getHiddenPackages(context: Context): Set<String> {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        migrateIfNeeded(sp)
        return sp.getStringSet(KEY_HIDDEN, emptySet()).orEmpty()
    }

    fun addHiddenPackage(context: Context, pkg: String) {
        if (pkg.isBlank()) return
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        migrateIfNeeded(sp)
        val set = sp.getStringSet(KEY_HIDDEN, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (set.add(pkg)) sp.edit().putStringSet(KEY_HIDDEN, set).apply()
    }

    fun getPlaybackRouteMode(context: Context): PlaybackRouteMode {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return when (sp.getString(KEY_PLAYBACK_ROUTE, null)) {
            "normal" -> PlaybackRouteMode.NORMAL
            else -> PlaybackRouteMode.BLUETOOTH
        }
    }

    fun setPlaybackRouteMode(context: Context, mode: PlaybackRouteMode) {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val value = when (mode) {
            PlaybackRouteMode.BLUETOOTH -> "bluetooth"
            PlaybackRouteMode.NORMAL -> "normal"
        }
        sp.edit().putString(KEY_PLAYBACK_ROUTE, value).apply()
    }

    private fun migrateIfNeeded(sp: android.content.SharedPreferences) {
        if (sp.contains(KEY_ENABLED)) return
        // 默认关闭：迁移时不把旧“默认开启”状态带过来，避免用户意外播报
        sp.edit()
            .putStringSet(KEY_ENABLED, emptySet())
            .putStringSet(KEY_KNOWN, emptySet())
            .putStringSet(KEY_MANUAL_INCLUDED, emptySet())
            .putStringSet(KEY_HIDDEN, emptySet())
            .remove(KEY_DISABLED_LEGACY)
            .apply()
    }
}

