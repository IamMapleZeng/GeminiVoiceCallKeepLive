package io.github.iammaplezeng.voicecall.keepalive

import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textview.MaterialTextView
import io.github.libxposed.service.XposedService
import java.text.DateFormat
import java.util.Date

class SettingsActivity : AppCompatActivity() {
    private val app get() = application as SettingsApplication
    private lateinit var root: LinearLayout
    private lateinit var runtime: TextView
    private lateinit var packages: TextView
    private lateinit var injection: TextView
    private lateinit var manual: MaterialSwitch
    private lateinit var automatic: MaterialSwitch
    private lateinit var graceWindow: MaterialButton
    private var graceMinutes = ModuleSettings.DEFAULT_GRACE_MINUTES
    private lateinit var floatyLock: MaterialSwitch
    private lateinit var awake: MaterialSwitch
    private lateinit var awakeWindow: MaterialButton
    private var awakeMinutes = ModuleSettings.DEFAULT_GRACE_MINUTES
    private lateinit var audio: MaterialSwitch
    private lateinit var debug: MaterialSwitch
    private var drawing = false
    private var refreshId = 0
    private val frameworkChanged: () -> Unit = { runOnUiThread { if (!isDestroyed) refresh() } }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        }
        setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { _, insets ->
            val padding = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            root.setPadding(dp(24) + padding.left, dp(20) + padding.top, dp(24) + padding.right, dp(24) + padding.bottom)
            insets
        }

        label(getString(R.string.app_name), 28f, top = 0)
        label("保活", 20f)
        manual = toggle("Gemini Live 锁屏保活")
        automatic = toggle("自动息屏保活")
        manual.isEnabled = false
        automatic.isEnabled = false
        manual.setOnCheckedChangeListener { _, checked -> save(ModuleSettings.MANUAL, checked) }
        automatic.setOnCheckedChangeListener { _, checked -> save(ModuleSettings.AUTOMATIC, checked) }
        graceWindow = button(getString(R.string.grace_window_value, graceMinutes)) {
            editGraceWindow(R.string.grace_window_title, graceWindow, graceMinutes, ModuleSettings.GRACE_MINUTES)
        }
        graceWindow.isEnabled = false
        label(getString(R.string.grace_window_description), 14f, top = 0)
        floatyLock = toggle(getString(R.string.floaty_lock))
        floatyLock.isEnabled = false
        floatyLock.setOnCheckedChangeListener { _, checked -> save(ModuleSettings.FLOATY_LOCK, checked) }
        awake = toggle(getString(R.string.awake_grace))
        awake.isEnabled = false
        awake.setOnCheckedChangeListener { _, checked -> save(ModuleSettings.AWAKE, checked) }
        awakeWindow = button(getString(R.string.awake_grace_window_value, awakeMinutes)) {
            editGraceWindow(R.string.awake_grace_window_title, awakeWindow, awakeMinutes, ModuleSettings.AWAKE_GRACE_MINUTES)
        }
        awakeWindow.isEnabled = false
        label(getString(R.string.awake_grace_window_description), 14f, top = 0)

        label("诊断", 20f)
        debug = toggle("调试日志")
        audio = toggle("音频状态监控")
        debug.isEnabled = false
        audio.isEnabled = false
        debug.setOnCheckedChangeListener { _, checked -> save(ModuleSettings.DEBUG, checked) }
        audio.setOnCheckedChangeListener { _, checked -> save(ModuleSettings.AUDIO, checked) }
        label("音频监控需要同时开启调试日志。", 14f, top = 0)

        label("模块运行状态", 20f)
        runtime = label("正在读取…", 14f, top = 6)
        label("检测到的应用版本", 20f)
        packages = label("正在读取…", 14f, top = 6)
        label("LSPosed Hook 状态", 20f)
        injection = label("正在读取…", 14f, top = 6)

        button("刷新状态") { refresh() }
        button("Gemini 官方锁屏设置说明") {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://support.google.com/gemini/answer/15274899?hl=zh-Hans")))
            } catch (_: android.content.ActivityNotFoundException) {
                message("找不到可打开帮助页面的浏览器")
            }
        }
        app.listeners.add(frameworkChanged)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        app.listeners.remove(frameworkChanged)
        super.onDestroy()
    }

    private fun refresh() {
        val request = ++refreshId
        app.worker.execute {
            val service = app.framework
            val settings = runCatching { readFramework(service) }.getOrElse {
                FrameworkState("LSPosed 服务读取失败（${it.javaClass.simpleName}）")
            }
            val versions = ModuleSettings.targetPackages.joinToString("\n\n", transform = ::packageSummary)
            val compatible = versionSupported()
            val reports = HookReport.read(app).joinToString("\n\n") {
                "${it.process} · PID ${it.pid}\n" +
                    "${it.hooks} 个 Hook · ${it.errors} 个观察器初始化异常 · 配置${if (it.prefs) "已读取" else "不可用"}\n" +
                    (if (it.process == ModuleSettings.GOOGLE_SEARCH) {
                        "保活 Hook · ${if (it.grace) "已安装" else "未安装"}\n"
                    } else "") +
                    DateFormat.getDateTimeInstance().format(Date(it.timestamp))
            }
            runOnUiThread {
                if (isDestroyed || request != refreshId) return@runOnUiThread
                drawing = true
                runtime.text = "${settings.description}\nAndroid ${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}\n" +
                    if (compatible) "Google App 版本匹配" else "当前系统或 Google App 版本尚未验证"
                packages.text = versions
                injection.text = if (reports.isEmpty()) {
                    "尚未收到进程上报。启用作用域后重启应用进程。可在 LSPosed 日志中查找 monitor_ready。"
                } else {
                    "最近上报（历史快照）\n\n$reports"
                }
                manual.isChecked = compatible && settings.manual
                automatic.isChecked = compatible && settings.automatic
                manual.isEnabled = compatible && settings.canEdit && settings.requiredScopes
                automatic.isEnabled = manual.isEnabled
                graceMinutes = settings.graceMinutes
                graceWindow.text = getString(R.string.grace_window_value, graceMinutes)
                graceWindow.isEnabled = manual.isEnabled
                awake.isEnabled = compatible && settings.canEdit && settings.googleScoped
                floatyLock.isEnabled = awake.isEnabled
                floatyLock.isChecked = compatible && settings.floatyLock
                awake.isChecked = compatible && settings.awake
                awakeMinutes = settings.awakeMinutes
                awakeWindow.text = getString(R.string.awake_grace_window_value, awakeMinutes)
                awakeWindow.isEnabled = awake.isEnabled
                debug.isChecked = settings.debug
                audio.isChecked = settings.audio
                debug.isEnabled = settings.canEdit
                audio.isEnabled = settings.canEdit
                drawing = false
            }
        }
    }

    private fun readFramework(service: XposedService?): FrameworkState {
        service ?: return FrameworkState("等待 LSPosed 服务。请在管理器中启用模块。")
        val selected = service.scope
        val scope = selected.filter { it in ModuleSettings.targetPackages || it == ModuleSettings.SYSTEM }
            .joinToString {
                when (it) {
                    ModuleSettings.GEMINI -> "Gemini"
                    ModuleSettings.GOOGLE -> "Google App"
                    else -> "系统框架"
                }
            }
            .ifEmpty { "无匹配应用" }
        val description = "${service.frameworkName} ${service.frameworkVersion} · Xposed API ${service.apiVersion}\n作用域 · $scope"
        if (service.frameworkProperties and XposedService.PROP_CAP_REMOTE == 0L) {
            return FrameworkState("$description\n框架未提供远程配置。")
        }
        val preferences = service.getRemotePreferences(ModuleSettings.PREFS)
        return FrameworkState(
            description = description,
            canEdit = true,
            googleScoped = ModuleSettings.GOOGLE in selected,
            requiredScopes = ModuleSettings.GOOGLE in selected && ModuleSettings.SYSTEM in selected,
            manual = preferences.enabled(ModuleSettings.MANUAL),
            automatic = preferences.enabled(ModuleSettings.AUTOMATIC),
            graceMinutes = ModuleSettings.graceMinutes(preferences, ModuleSettings.GRACE_MINUTES),
            floatyLock = preferences.enabled(ModuleSettings.FLOATY_LOCK),
            awake = preferences.enabled(ModuleSettings.AWAKE),
            awakeMinutes = ModuleSettings.graceMinutes(preferences, ModuleSettings.AWAKE_GRACE_MINUTES),
            debug = preferences.enabled(ModuleSettings.DEBUG),
            audio = preferences.enabled(ModuleSettings.AUDIO),
        )
    }

    private fun editGraceWindow(title: Int, button: MaterialButton, current: Int, key: String) {
        val minutes = ModuleSettings.graceMinuteOptions
        val options = minutes.map { getString(R.string.duration_minutes, it) }.toTypedArray()
        var selected = current
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setSingleChoiceItems(options, selected - minutes.first) { _, index ->
                selected = minutes.first + index
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.grace_window_save) { _, _ ->
                if (button.isEnabled && selected != current) {
                    savePreferences { putInt(key, selected) }
                }
            }
            .show()
    }

    private fun save(key: String, checked: Boolean) {
        if (!drawing) savePreferences { putBoolean(key, checked) }
    }

    private fun savePreferences(update: SharedPreferences.Editor.() -> Unit) {
        refreshId++
        manual.isEnabled = false
        automatic.isEnabled = false
        graceWindow.isEnabled = false
        floatyLock.isEnabled = false
        awake.isEnabled = false
        awakeWindow.isEnabled = false
        debug.isEnabled = false
        audio.isEnabled = false
        app.worker.execute {
            val saved = runCatching {
                app.framework?.getRemotePreferences(ModuleSettings.PREFS)?.edit()
                    ?.apply(update)?.commit() == true
            }.getOrDefault(false)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (!saved) message("未能保存设置，请刷新后重试")
                refresh()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun versionSupported(): Boolean {
        if (!ModuleSettings.isVerifiedDevice()) return false
        return try {
            val version = packageManager.getPackageInfo(ModuleSettings.GOOGLE, 0)
            version.longVersionCode == ModuleSettings.VERIFIED_CODE &&
                version.versionName == ModuleSettings.VERIFIED_VERSION
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun packageSummary(owner: String): String {
        val title = if (owner == ModuleSettings.GEMINI) "Gemini" else "Google App"
        return try {
            val info = packageManager.getPackageInfo(owner, PackageManager.GET_SERVICES)
            val microphone = info.services.orEmpty().count {
                it.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE != 0
            }
            "$title · ${info.versionName} (${info.longVersionCode})\n$owner\n默认进程 · ${info.applicationInfo?.processName}\n" +
                "声明 microphone 前台服务 · $microphone"
        } catch (_: PackageManager.NameNotFoundException) {
            "$title · 当前用户未安装\n$owner"
        }
    }

    private fun label(text: String, size: Float, top: Int = 20) = MaterialTextView(this).apply {
        this.text = text
        textSize = size
        root.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) })
    }

    private fun toggle(text: String) = MaterialSwitch(this).apply {
        this.text = text
        minimumHeight = dp(48)
        root.addView(this, LinearLayout.LayoutParams(-1, -2))
    }

    private fun button(text: String, action: (View) -> Unit) = MaterialButton(this, null,
        com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
        this.text = text
        setOnClickListener(action)
        root.addView(this, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
    }

    private fun message(text: String) = Snackbar.make(root, text, Snackbar.LENGTH_LONG).show()
    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

    private data class FrameworkState(
        val description: String,
        val canEdit: Boolean = false,
        val googleScoped: Boolean = false,
        val requiredScopes: Boolean = false,
        val manual: Boolean = false,
        val automatic: Boolean = false,
        val graceMinutes: Int = ModuleSettings.DEFAULT_GRACE_MINUTES,
        val floatyLock: Boolean = false,
        val awake: Boolean = false,
        val awakeMinutes: Int = ModuleSettings.DEFAULT_GRACE_MINUTES,
        val debug: Boolean = false,
        val audio: Boolean = false,
    )
}
