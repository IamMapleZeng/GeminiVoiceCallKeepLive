package io.github.iammaplezeng.voicecall.keepalive

import android.Manifest
import android.app.Application
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import io.github.libxposed.api.XposedInterface
import java.lang.ref.WeakReference
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier
import java.util.concurrent.CancellationException

/**
 * Compatibility adapter for the on-device Google App build verified with apkanalyzer.
 * The application retains ownership of audio, networking, and the timer's coroutine.
 */
internal class InactivityGrace(
    private val module: LiveModule,
    private val app: Application,
    private val processName: String,
    private val prefs: SharedPreferences?,
    private val event: (String) -> Unit,
) {
    private val power = app.getSystemService(PowerManager::class.java)
    @Volatile private var screenOff: ScreenOff? = null
    @Volatile private var lastScreenOnAt = -1L
    @Volatile private var foreground: ForegroundLive? = null

    fun screenOn() {
        lastScreenOnAt = SystemClock.elapsedRealtime()
        screenOff = null
    }

    @Synchronized
    fun startLive(service: Service) {
        if (foreground?.service?.get() !== service) {
            foreground = ForegroundLive(WeakReference(service), SystemClock.elapsedRealtime())
        }
    }

    @Synchronized
    fun stopLive(service: Service) {
        if (foreground?.service?.get() === service) foreground = null
    }

    @Suppress("DEPRECATION")
    fun install(): Boolean {
        if (processName != ModuleSettings.GOOGLE_SEARCH || app.packageName != ModuleSettings.GOOGLE) return false
        val version = app.packageManager.getPackageInfo(app.packageName, 0)
        if (!ModuleSettings.isVerifiedDevice() || version.longVersionCode != ModuleSettings.VERIFIED_CODE ||
            version.versionName != ModuleSettings.VERIFIED_VERSION || prefs == null || power == null
        ) {
            module.framework("grace_unavailable process=$processName sdk=${Build.VERSION.SDK_INT} version_code=${version.longVersionCode}")
            return false
        }

        val loader = app.classLoader
        val timer = Class.forName("blnr", false, loader)
        val continuation = Class.forName("hiiq", false, loader)
        val helper = Class.forName("hitf", false, loader)
        val unitType = Class.forName("hifr", false, loader)
        val suspendedType = Class.forName("hija", false, loader)
        val label = timer.getDeclaredField("a").apply { isAccessible = true }
        val tick = timer.getDeclaredMethod("invokeSuspend", Any::class.java)
        val delay = helper.getDeclaredMethod("c", Long::class.javaPrimitiveType!!, continuation)
            .apply { isAccessible = true }
        val unit = unitType.getDeclaredField("a").apply { isAccessible = true }.get(null)
        val suspended = suspendedType.getDeclaredField("a").apply { isAccessible = true }.get(null)
        check(continuation.isAssignableFrom(timer) && label.type == Int::class.javaPrimitiveType &&
            tick.returnType == Any::class.java && delay.returnType == Any::class.java &&
            Modifier.isStatic(delay.modifiers) && unitType.isInstance(unit) &&
            (suspended as? Enum<*>)?.name == "COROUTINE_SUSPENDED")
        registerPowerReasonReceiver()

        module.hook(tick).setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept { chain ->
            val instance = chain.thisObject
            var deferred = false
            try {
                if (instance != null && chain.getArg(0) === unit && label.getInt(instance) == 1 &&
                    (prefs.enabled(ModuleSettings.MANUAL) || prefs.enabled(ModuleSettings.AUTOMATIC))
                ) {
                    val off = eligibleScreenOff()
                    if (off != null) {
                        val minutes = ModuleSettings.graceMinutes(prefs)
                        val remaining = minutes * 60_000L - (SystemClock.elapsedRealtime() - off.at)
                        if (remaining > 0L) {
                            val wait = minOf(remaining, CHECK_INTERVAL_MS)
                            // Same cancellable delay used by the original timer. Label 1 resumes after delay.
                            deferred = delay.invoke(null, wait, instance) === suspended
                            if (deferred) module.guard("grace_log") {
                                event("inactivity_timer_deferred reason=${off.kind} delay_ms=$wait window_minutes=$minutes")
                            }
                        }
                    }
                }
            } catch (failure: InvocationTargetException) {
                val cause = failure.targetException
                if (cause is CancellationException) throw cause
                module.framework("grace_error process=$processName error=${cause.javaClass.simpleName}")
            } catch (failure: Exception) {
                module.framework("grace_error process=$processName error=${failure.javaClass.simpleName}")
            } catch (failure: LinkageError) {
                module.framework("grace_error process=$processName error=${failure.javaClass.simpleName}")
            }
            if (deferred) suspended else chain.proceed()
        }
        module.framework("grace_ready process=$processName version_code=${version.longVersionCode} hook=blnr.invokeSuspend")
        return true
    }

    private fun registerPowerReasonReceiver() {
        app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != PowerReasonBridge.ACTION) return
                module.guard("power_reason_received") {
                    val kind = intent.getStringExtra(PowerReasonBridge.EXTRA_KIND).orEmpty()
                    val at = intent.getLongExtra(PowerReasonBridge.EXTRA_TIME, -1)
                    if (kind != PowerReasonBridge.MANUAL && kind != PowerReasonBridge.AUTOMATIC) return@guard
                    if (at <= lastScreenOnAt || SystemClock.elapsedRealtime() - at !in 0..5_000 ||
                        power?.isInteractive != false
                    ) return@guard
                    if (screenOff == null) screenOff = ScreenOff(at, kind)
                    event("live_sleep_reason kind=$kind")
                }
            }
        }, IntentFilter(PowerReasonBridge.ACTION), PowerReasonBridge.PERMISSION, null, Context.RECEIVER_EXPORTED)
    }

    private fun eligibleScreenOff(): ScreenOff? {
        val off = screenOff ?: return null
        val key = if (off.kind == PowerReasonBridge.MANUAL) ModuleSettings.MANUAL else ModuleSettings.AUTOMATIC
        if (!prefs.enabled(key) || power?.isInteractive != false) return null
        val live = foreground ?: return null
        val service = live.service.get() ?: return null
        if (off.at < live.startedAt ||
            app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
            service.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE == 0
        ) return null
        return off
    }

    private data class ScreenOff(val at: Long, val kind: String)

    private data class ForegroundLive(val service: WeakReference<Service>, val startedAt: Long)

    companion object {
        private const val CHECK_INTERVAL_MS = 30_000L
    }
}
