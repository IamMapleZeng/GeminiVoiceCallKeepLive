package io.github.iammaplezeng.voicecall.keepalive

import android.app.Application
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

class SettingsApplication : Application(), XposedServiceHelper.OnServiceListener {
    @Volatile
    internal var framework: XposedService? = null
        private set

    internal val worker = Executors.newSingleThreadExecutor()
    internal val listeners = CopyOnWriteArraySet<() -> Unit>()

    override fun onCreate() {
        super.onCreate()
        XposedServiceHelper.registerListener(this)
    }

    @Synchronized
    override fun onServiceBind(service: XposedService) {
        framework = service
        listeners.forEach { it() }
    }

    @Synchronized
    override fun onServiceDied(service: XposedService) {
        if (framework !== service) return
        framework = null
        listeners.forEach { it() }
    }
}
