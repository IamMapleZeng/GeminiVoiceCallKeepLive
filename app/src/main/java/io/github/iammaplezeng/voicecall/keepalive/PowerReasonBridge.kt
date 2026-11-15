package io.github.iammaplezeng.voicecall.keepalive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Read-only system-side observer. Android protects its sleep-reason getter with DEVICE_POWER.
 * A package-directed broadcast carries only the classified reason and a monotonic timestamp.
 */
internal class PowerReasonBridge(private val module: LiveModule) {
    private val registered = AtomicBoolean()

    fun install(loader: ClassLoader) {
        if (!ModuleSettings.isVerifiedDevice() || Process.myUid() != SYSTEM_UID) return
        // Verified in /system/framework/services.jar on OS4.0.3.0.XOACNXM.
        val type = Class.forName("com.android.server.power.PowerManagerService", false, loader)
        val contextField = type.getDeclaredField("mContext").apply { isAccessible = true }
        val getReason = type.getDeclaredMethod("getLastSleepReasonInternal").apply { isAccessible = true }
        check(getReason.returnType == Int::class.javaPrimitiveType)
        val bootCompleted = type.superclass!!.getField("PHASE_BOOT_COMPLETED").getInt(null)
        module.observe(type, "onBootPhase", Int::class.javaPrimitiveType!!) { chain, _, failure ->
            if (failure == null && chain.getArg(0) == bootCompleted && registered.compareAndSet(false, true)) {
                val service = chain.thisObject
                val context = contextField.get(service) as Context
                register(context) { getReason.invoke(service) as Int }
            }
        }
    }

    private fun register(context: Context, readReason: () -> Int) {
        val power = context.getSystemService(PowerManager::class.java) ?: return
        val prefs = module.getRemotePreferences(ModuleSettings.PREFS)

        context.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action != Intent.ACTION_SCREEN_OFF) return
                module.guard("sleep_reason") {
                    if (!(prefs.enabled(ModuleSettings.MANUAL) || prefs.enabled(ModuleSettings.AUTOMATIC)) ||
                        power.isInteractive
                    ) return@guard
                    val kind = when (readReason()) {
                        POWER_BUTTON -> if (prefs.enabled(ModuleSettings.MANUAL)) MANUAL else null
                        SCREEN_TIMEOUT -> if (prefs.enabled(ModuleSettings.AUTOMATIC)) AUTOMATIC else null
                        else -> null
                    } ?: return@guard
                    c.sendBroadcast(Intent(ACTION).setPackage(ModuleSettings.GOOGLE)
                        .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY)
                        .putExtra(EXTRA_KIND, kind)
                        .putExtra(EXTRA_TIME, SystemClock.elapsedRealtime()))
                    if (prefs.enabled(ModuleSettings.DEBUG)) module.framework("power_reason kind=$kind")
                }
            }
        }, IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_NOT_EXPORTED)
        module.framework("power_reason_observer_ready")
    }

    companion object {
        const val ACTION = "io.github.iammaplezeng.voicecall.keepalive.action.SLEEP_REASON"
        const val PERMISSION = "android.permission.DEVICE_POWER"
        const val EXTRA_KIND = "kind"
        const val EXTRA_TIME = "time"
        const val MANUAL = "power_button"
        const val AUTOMATIC = "timeout"
        // PowerManager.GO_TO_SLEEP_REASON_* values on the verified Android build.
        private const val SCREEN_TIMEOUT = 2
        private const val POWER_BUTTON = 4
        private const val SYSTEM_UID = 1000
    }
}
