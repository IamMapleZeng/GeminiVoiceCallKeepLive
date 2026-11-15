package io.github.iammaplezeng.voicecall.keepalive

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.app.KeyguardManager
import android.app.Notification
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log

internal class ProcessMonitor(
    private val module: LiveModule,
    private val app: Application,
    private val processName: String,
    private val prefs: SharedPreferences?,
) {
    private val prefix = "package=${app.packageName} process=$processName pid=${Process.myPid()}"
    private val intType = Int::class.javaPrimitiveType!!
    private var hooks = 1 // Instrumentation entry reached this Application.
    private var monitorErrors = 0
    private var graceReady = false
    private val grace = InactivityGrace(module, app, processName, prefs, ::event)

    fun install() {
        initialize("activity") { installActivityObserver() }
        initialize("screen") { installScreenObserver() }
        initialize("network") { installNetworkObserver() }
        initialize("service_api") { installServiceHooks() }
        initialize("service_dispatch") { installServiceDispatchHooks() }
        initialize("audio") {
            hooks += AudioMonitor(module, app, prefs, ::event).install()
        }
        initialize("inactivity_grace") {
            graceReady = grace.install()
            if (graceReady) hooks++
        }
        val summary = "monitor_ready $prefix sdk=${Build.VERSION.SDK_INT} hooks=$hooks observer_errors=$monitorErrors grace=$graceReady prefs=${prefs != null}"
        module.framework(summary)
        Thread({
            module.guard("hook_status") { reportStatus() }
            module.guard("previous_exit") { logPreviousExit() }
        }, "gemini-live-diagnostics").start()
    }

    private inline fun initialize(name: String, action: () -> Unit) {
        module.guard(name) {
            monitorErrors++
            action()
            monitorErrors--
        }
    }

    private fun reportStatus() {
        val authority = Uri.parse("content://${ModuleSettings.STATUS_AUTHORITY}")
        val result = app.contentResolver.call(authority, ModuleSettings.REPORT_HOOKS, null, Bundle().apply {
            putString("package", app.packageName)
            putString("process", processName)
            putInt("hooks", hooks)
            putInt("errors", monitorErrors)
            putBoolean("prefs", prefs != null)
            putBoolean("grace", graceReady)
        })
        if (result?.getBoolean("accepted") != true) {
            module.framework("status_delivery_unavailable $prefix")
        }
    }

    private fun logPreviousExit() {
        if (!prefs.enabled(ModuleSettings.DEBUG)) return
        val manager = app.getSystemService(ActivityManager::class.java) ?: return
        val exit = manager.getHistoricalProcessExitReasons(app.packageName, 0, 16)
            .firstOrNull { it.processName == processName } ?: return
        event("previous_exit pid=${exit.pid} reason=${exit.reason} importance=${exit.importance} status=${exit.status} at_ms=${exit.timestamp}")
    }

    private fun installActivityObserver() {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            private var visible = 0

            override fun onActivityStarted(activity: Activity) {
                if (visible++ == 0) event("app_foreground activity=${activity.javaClass.name}")
            }
            override fun onActivityStopped(activity: Activity) {
                visible = (visible - 1).coerceAtLeast(0)
                if (visible == 0) event("app_background activity=${activity.javaClass.name}")
            }
            override fun onActivityResumed(activity: Activity) = event("activity_resumed activity=${activity.javaClass.name}")
            override fun onActivityPaused(activity: Activity) = event("activity_paused activity=${activity.javaClass.name}")
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    private fun installScreenObserver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                module.guard("screen_state") {
                    if (intent.action == Intent.ACTION_SCREEN_ON) grace.screenOn()
                    powerSnapshot(intent.action?.substringAfterLast('.') ?: "unknown")
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            app.registerReceiver(receiver, filter)
        }
        powerSnapshot("initial")
    }

    private fun powerSnapshot(source: String) {
        if (!prefs.enabled(ModuleSettings.DEBUG)) return
        val power = app.getSystemService(PowerManager::class.java) ?: return
        val keyguard = app.getSystemService(KeyguardManager::class.java)
        val microphoneAllowed = app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        event("power source=$source interactive=${power.isInteractive} keyguard=${keyguard?.isKeyguardLocked} " +
            "device_locked=${keyguard?.isDeviceLocked} idle=${power.isDeviceIdleMode} saver=${power.isPowerSaveMode} " +
            "battery_exempt=${power.isIgnoringBatteryOptimizations(app.packageName)} record_permission=$microphoneAllowed")
    }

    // The callback uses the scoped host's permission, checked here before registration.
    @SuppressLint("MissingPermission")
    private fun installNetworkObserver() {
        if (app.checkSelfPermission(Manifest.permission.ACCESS_NETWORK_STATE) != PackageManager.PERMISSION_GRANTED) {
            module.framework("network_unavailable $prefix reason=permission")
            return
        }
        val manager = app.getSystemService(ConnectivityManager::class.java) ?: return
        manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                module.guard("network_capabilities") {
                    event("network wifi=${caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)} " +
                        "cellular=${caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)} " +
                        "vpn=${caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)} " +
                        "validated=${caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)}")
                }
            }
            override fun onLost(network: Network) = event("network_lost")
            override fun onBlockedStatusChanged(network: Network, blocked: Boolean) = event("network_blocked value=$blocked")
        })
    }

    private fun installServiceHooks() {
        foregroundHook("startForeground", intType, Notification::class.java)
        foregroundHook("startForeground", intType, Notification::class.java, intType)
        foregroundHook("stopForeground", intType)
        foregroundHook("stopForeground", Boolean::class.javaPrimitiveType!!)
        foregroundHook("stopSelf")
        foregroundHook("stopSelf", intType)
        foregroundHook("stopSelfResult", intType)
    }

    private fun liveSignal(className: String, signal: String) {
        if (app.packageName == ModuleSettings.GOOGLE && className == ModuleSettings.LIVE_SERVICE) {
            event("live_service signal=$signal")
        }
    }

    private fun foregroundHook(name: String, vararg arguments: Class<*>) {
        if (module.observe(Service::class.java, name, *arguments) { chain, result, error ->
                val service = chain.thisObject as? Service
                if (service?.packageName == app.packageName &&
                    service.javaClass.name == ModuleSettings.LIVE_SERVICE && error == null && result != false
                ) {
                    if (name == "startForeground") grace.startLive(service) else grace.stopLive(service)
                }
                if (service?.packageName == app.packageName && prefs.enabled(ModuleSettings.DEBUG)) {
                    val type = if (error == null) service.foregroundServiceType else null
                    event("service action=$name class=${service.javaClass.name} foreground_type=$type " +
                        "result=${result as? Boolean} error=${error?.javaClass?.simpleName ?: "none"}")
                    if (error == null && result != false) liveSignal(service.javaClass.name, name)
                }
            }) hooks++
    }

    // AOSP ActivityThread dispatch signatures. The checks below also cover OEM changes.
    private fun installServiceDispatchHooks() {
        val dispatcher = Class.forName("android.app.ActivityThread")
        val createData = Class.forName("android.app.ActivityThread\$CreateServiceData")
        val info = createData.getDeclaredField("info").apply { isAccessible = true }
        val services = dispatcher.getDeclaredField("mServices").apply { isAccessible = true }
        if (module.observe(dispatcher, "handleCreateService", createData) { chain, _, error ->
                if (prefs.enabled(ModuleSettings.DEBUG)) {
                    val service = info.get(chain.getArg(0)) as? android.content.pm.ServiceInfo
                    if (service?.packageName == app.packageName) {
                        event("service action=create_dispatch class=${service.name} " +
                            "declared_type=${service.foregroundServiceType} error=${error?.javaClass?.simpleName ?: "none"}")
                        if (error == null) liveSignal(service.name, "create_dispatch_complete")
                    }
                }
            }) hooks++
        mapOf("handleBindService" to "BindServiceData", "handleUnbindService" to "BindServiceData",
            "handleServiceArgs" to "ServiceArgsData", "handleStopService" to null).forEach { (method, dataName) ->
            initialize(method) {
                val data = dataName?.let { Class.forName("android.app.ActivityThread\$$it") } ?: IBinder::class.java
                val tokenField = dataName?.let { data.getDeclaredField("token").apply { isAccessible = true } }
                if (module.observeBefore(dispatcher, method, data) { chain ->
                        val diagnostic = prefs.enabled(ModuleSettings.DEBUG)
                        if (diagnostic || method == "handleStopService" && graceReady) {
                            val argument = chain.getArg(0)
                            val token = if (tokenField != null) tokenField.get(argument) else argument
                            val service = (services.get(chain.thisObject) as? Map<*, *>)?.get(token) as? Service
                            if (service?.packageName == app.packageName) {
                                if (method == "handleStopService") grace.stopLive(service)
                                if (diagnostic) {
                                    event("service action=$method class=${service.javaClass.name}")
                                    liveSignal(service.javaClass.name, method)
                                }
                            }
                        }
                    }) hooks++
            }
        }
    }

    private fun event(message: String) {
        if (prefs.enabled(ModuleSettings.DEBUG)) {
            Log.i(ModuleSettings.TAG, "$prefix elapsed_ms=${SystemClock.elapsedRealtime()} $message")
        }
    }
}
