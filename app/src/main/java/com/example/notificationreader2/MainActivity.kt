package com.example.notificationreader2

import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.textfield.TextInputEditText
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup

class MainActivity : AppCompatActivity() {
    private val requestPostNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private lateinit var adapter: AppToggleAdapter
    private var audioManager: AudioManager? = null
    private var deviceCallback: AudioDeviceCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (android.os.Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                requestPostNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        findViewById<MaterialButton>(R.id.openAccessButton).setOnClickListener {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        }

        findViewById<MaterialButton>(R.id.openTtsButton).setOnClickListener {
            val candidates = listOf(
                Intent("com.android.settings.TTS_SETTINGS"),
                Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA),
                Intent(Settings.ACTION_SETTINGS)
            )
            for (i in candidates) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    startActivity(i)
                    break
                } catch (_: Throwable) {
                }
            }
        }

        findViewById<MaterialButton>(R.id.pickTtsEngineButton).setOnClickListener {
            val pm = packageManager
            val services = pm.queryIntentServices(
                Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE),
                0
            )
            val items = services
                .mapNotNull { it.serviceInfo?.packageName }
                .distinct()
                .map { pkg ->
                    val label = try {
                        val ai = packageManager.getApplicationInfo(pkg, 0)
                        packageManager.getApplicationLabel(ai).toString()
                    } catch (_: Throwable) {
                        pkg
                    }
                    label to pkg
                }
                .sortedBy { it.first }

            if (items.isEmpty()) {
                Toast.makeText(
                    this,
                    "未找到可用的 TTS 引擎，请先安装一个（例如 Google 文字转语音）后再试。",
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }

            val current = getSharedPreferences("prefs", MODE_PRIVATE).getString("tts_engine", null)
                ?: Settings.Secure.getString(contentResolver, "tts_default_synth")
            val labels = items.map { (label, pkg) -> "$label\n$pkg" }.toTypedArray()
            var selected = items.indexOfFirst { it.second == current }.coerceAtLeast(0)

            MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.pick_tts_engine))
                .setSingleChoiceItems(labels, selected) { _, which ->
                    selected = which
                }
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val enginePkg = items[selected].second
                    getSharedPreferences("prefs", MODE_PRIVATE)
                        .edit()
                        .putString("tts_engine", enginePkg)
                        .commit()
                    TtsForegroundService.reloadEngine(this, enginePkg)
                    updateStatusUi()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        findViewById<MaterialButton>(R.id.manageAppsButton).setOnClickListener {
            showManageAppsBottomSheet()
        }

        val playbackToggle = findViewById<MaterialButtonToggleGroup>(R.id.playbackModeToggleGroup)
        when (ReadAloudPrefs.getPlaybackRouteMode(this)) {
            ReadAloudPrefs.PlaybackRouteMode.BLUETOOTH -> playbackToggle.check(R.id.modeBluetoothButton)
            ReadAloudPrefs.PlaybackRouteMode.NORMAL -> playbackToggle.check(R.id.modeNormalButton)
        }
        playbackToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            when (checkedId) {
                R.id.modeBluetoothButton ->
                    ReadAloudPrefs.setPlaybackRouteMode(this, ReadAloudPrefs.PlaybackRouteMode.BLUETOOTH)
                R.id.modeNormalButton ->
                    ReadAloudPrefs.setPlaybackRouteMode(this, ReadAloudPrefs.PlaybackRouteMode.NORMAL)
                else -> return@addOnButtonCheckedListener
            }
            updateStatusUi()
        }

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        updateStatusUi()
    }

    override fun onStart() {
        super.onStart()
        val am = audioManager ?: return
        val cb = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                updateStatusUi()
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                updateStatusUi()
            }
        }
        deviceCallback = cb
        am.registerAudioDeviceCallback(cb, null)
        updateStatusUi()
    }

    override fun onStop() {
        val am = audioManager
        val cb = deviceCallback
        if (am != null && cb != null) {
            am.unregisterAudioDeviceCallback(cb)
        }
        deviceCallback = null
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        updateStatusUi()
    }

    private fun updateStatusUi() {
        val tv = findViewById<com.google.android.material.textview.MaterialTextView>(R.id.headsetStatusText)
        when (ReadAloudPrefs.getPlaybackRouteMode(this)) {
            ReadAloudPrefs.PlaybackRouteMode.BLUETOOTH -> {
                val connected = AudioRouteUtils.isBluetoothHeadsetConnected(this)
                tv.setText(if (connected) R.string.bt_headset_connected else R.string.bt_headset_disconnected)
            }
            ReadAloudPrefs.PlaybackRouteMode.NORMAL -> tv.setText(R.string.normal_mode_broadcast_status)
        }

        val notifTv = findViewById<com.google.android.material.textview.MaterialTextView>(R.id.notifAccessStatusText)
        notifTv.setText(if (isNotificationListenerEnabled()) R.string.notif_access_enabled else R.string.notif_access_disabled)

        val ttsTv = findViewById<com.google.android.material.textview.MaterialTextView>(R.id.ttsEngineStatusText)
        ttsTv.text = buildTtsEngineStatusText()
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        val me = "$packageName/${NotificationTtsListenerService::class.java.name}"
        return enabled.split(":").any { it.equals(me, ignoreCase = true) }
    }

    private fun buildTtsEngineStatusText(): String {
        val prefEngine = getSharedPreferences("prefs", MODE_PRIVATE).getString("tts_engine", null)
        val defaultEngine = Settings.Secure.getString(contentResolver, "tts_default_synth")
        val pkg = prefEngine ?: defaultEngine
        if (pkg.isNullOrBlank()) return getString(R.string.tts_engine_unknown)

        val label = try {
            val ai = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(ai).toString()
        } catch (_: Throwable) {
            pkg
        }
        return "${getString(R.string.tts_engine_label)}$label ($pkg)"
    }

    private fun showManageAppsBottomSheet() {
        val view = LayoutInflater.from(this).inflate(R.layout.bottom_sheet_manage_apps, null, false)
        val emptyText = view.findViewById<com.google.android.material.textview.MaterialTextView>(R.id.emptyText)
        val list = view.findViewById<RecyclerView>(R.id.appList)
        val addBtn = view.findViewById<MaterialButton>(R.id.addAppButton)
        val closeBtn = view.findViewById<MaterialButton>(R.id.closeButton)

        lateinit var adapter: AppToggleAdapter

        fun refresh() {
            val hiddenPkgs = ReadAloudPrefs.getHiddenPackages(this)
            val manualIncluded = ReadAloudPrefs.getManualIncludedPackages(this)
            val enabledPkgs = ReadAloudPrefs.getEnabledPackages(this)

            // 以 Launcher “可见应用”作为“允许展示白名单”：
            // - 被隐藏/无桌面入口的应用永远不展示（即使被手动添加/曾经开启）
            val launcherPkgs = packageManager
                .queryIntentActivities(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                    0
                )
                .mapNotNull { it.activityInfo?.packageName }
                .asSequence()
                .filter { it.isNotBlank() }
                .filter { it != packageName } // 防止把自己加进来
                .distinct()
                .toList()

            val launcherSet = launcherPkgs.toSet()

            // 无主列表预制包：仅在「手动添加」后出现；顺带保留已在 enabled 名单里的旧数据以免列表里失联
            val candidatePkgs = (manualIncluded + enabledPkgs)
                .asSequence()
                .filter { it.isNotBlank() }
                .filter { it in launcherSet }
                .filter { it !in hiddenPkgs }
                .distinct()
                .toList()

            val items = candidatePkgs.mapNotNull { p ->
                val ai = try {
                    packageManager.getApplicationInfo(p, 0)
                } catch (_: Throwable) {
                    // 可能是瞬态包/卸载中，直接跳过
                    null
                } ?: return@mapNotNull null
                if (!ai.enabled) return@mapNotNull null

                val enabled = ReadAloudPrefs.isReadEnabled(this, p)
                // 默认隐藏系统应用/系统组件（列表更聚焦）；但如果用户已手动开启朗读，则保留展示，避免“开了却找不到”
                val isSystem = (ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                val isUpdatedSystemApp =
                    (ai.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                val manuallyIncluded = manualIncluded.contains(p)
                if (isSystem && !isUpdatedSystemApp && !enabled && !manuallyIncluded) return@mapNotNull null

                val appName = try {
                    packageManager.getApplicationLabel(ai).toString()
                } catch (_: Throwable) {
                    p
                }

                val icon = try {
                    packageManager.getApplicationIcon(p)
                } catch (_: Throwable) {
                    null
                }

                AppToggleItem(
                    packageName = p,
                    appName = appName,
                    icon = icon,
                    enabled = enabled
                )
            }.sortedWith { a, b ->
                if (a.enabled != b.enabled) return@sortedWith if (a.enabled) -1 else 1
                a.appName.compareTo(b.appName)
            }

            emptyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            adapter.submit(items)
        }

        adapter = AppToggleAdapter(
            onToggle = { pkg, enabled ->
                ReadAloudPrefs.setReadEnabled(this, pkg, enabled)
            },
            onLongPress = { pkg ->
                MaterialAlertDialogBuilder(this)
                    .setTitle("移除应用")
                    .setMessage("确定将该应用从列表中移除吗？（将加入隐藏名单；如已开启朗读，也会同时关闭）")
                    .setPositiveButton("移除") { _, _ ->
                        ReadAloudPrefs.removeManualIncludedPackage(this, pkg)
                        ReadAloudPrefs.setReadEnabled(this, pkg, false)
                        ReadAloudPrefs.addHiddenPackage(this, pkg)
                        refresh()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        )
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        fun openManualAddDialog() {
            val manualIncluded = ReadAloudPrefs.getManualIncludedPackages(this)
            val hiddenPkgs = ReadAloudPrefs.getHiddenPackages(this)

            val launcherPkgs = packageManager
                .queryIntentActivities(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                    0
                )
                .mapNotNull { it.activityInfo?.packageName }
                .asSequence()
                .filter { it.isNotBlank() }
                .filter { it != packageName }
                .distinct()
                .toList()

            val hiddenSet = hiddenPkgs.toSet()
            val candidates = launcherPkgs
                .asSequence()
                .filter { pkg -> pkg !in hiddenSet }
                .filter { it !in manualIncluded }
                .mapNotNull { p ->
                    val ai = try {
                        packageManager.getApplicationInfo(p, PackageManager.GET_META_DATA)
                    } catch (_: Throwable) {
                        null
                    } ?: return@mapNotNull null
                    // 已通过「设置」停用等：一般不应再出现在可操作列表（部分 ROM 仍可 query 到 launcher）
                    if (!ai.enabled) return@mapNotNull null
                    val label = try {
                        packageManager.getApplicationLabel(ai).toString()
                    } catch (_: Throwable) {
                        p
                    }
                    Triple(p, label, ai)
                }
                .sortedBy { it.second }
                .toList()

            if (candidates.isEmpty()) {
                Toast.makeText(this, "没有可添加的应用（已全部在列表或被过滤）。", Toast.LENGTH_SHORT).show()
                return
            }

            val manualDialogPickView =
                LayoutInflater.from(this).inflate(R.layout.dialog_pick_apps, null, false)
            val rv = manualDialogPickView.findViewById<RecyclerView>(R.id.pickAppList)
            val searchEdit =
                manualDialogPickView.findViewById<TextInputEditText>(R.id.pickAppSearchEdit)
            val lm = LinearLayoutManager(this)
            rv.layoutManager = lm

            val pickItems = candidates.map { (pkg, label, _) ->
                val icon = try {
                    packageManager.getApplicationIcon(pkg)
                } catch (_: Throwable) {
                    null
                }
                PickAppItem(packageName = pkg, appName = label, icon = icon)
            }

            val pickAdapter = PickAppAdapter(pickItems)
            rv.adapter = pickAdapter

            fun scrollToFirstMatch(query: String) {
                val q = query.trim()
                if (q.isEmpty()) {
                    lm.scrollToPositionWithOffset(0, 0)
                    return
                }
                val idx = pickItems.indexOfFirst { item ->
                    item.appName.contains(q, ignoreCase = true)
                        || item.packageName.contains(q, ignoreCase = true)
                }
                if (idx >= 0) {
                    lm.scrollToPositionWithOffset(idx, 0)
                }
            }

            searchEdit.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    scrollToFirstMatch(s?.toString().orEmpty())
                }
            })

            MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.pick_apps_to_add))
                .setView(manualDialogPickView)
                .setPositiveButton(getString(R.string.add_selected)) { _, _ ->
                    val selected = pickAdapter.getSelectedPackages()
                    ReadAloudPrefs.addManualIncludedPackages(this, selected)
                    refresh()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        addBtn.setOnClickListener { openManualAddDialog() }
        refresh()

        val dialog = BottomSheetDialog(this)
        dialog.setContentView(view)
        closeBtn.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }
}