package io.github.iammaplezeng.voicecall.keepalive

import android.app.Application
import android.app.Instrumentation
import android.content.SharedPreferences
import android.os.Process
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

class LiveModule : XposedModule() {
    private var processName = ""
    private val installed = AtomicBoolean()

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        processName = param.processName
    }

    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        guard("power_bridge_install") { PowerReasonBridge(this).install(param.classLoader) }
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        val owner = param.packageName
        if (!param.isFirstPackage || owner !in ModuleSettings.targetPackages) return
        if (param.applicationInfo.uid != Process.myUid()) {
            framework("scope_skip package=$owner process=$processName reason=isolated_uid")
            return
        }
        if (!installed.compareAndSet(false, true)) return
        val initialized = AtomicBoolean()
        observe(Instrumentation::class.java, "callApplicationOnCreate", Application::class.java) { chain, _, error ->
            val app = chain.getArg(0) as? Application
            if (error == null && app?.packageName == owner && initialized.compareAndSet(false, true)) {
                val prefs = try {
                    getRemotePreferences(ModuleSettings.PREFS)
                } catch (error: Exception) {
                    framework("preferences_unavailable package=$owner error=${error.javaClass.simpleName}")
                    null
                }
                ProcessMonitor(this, app, processName, prefs).install()
            }
        }
    }

    internal fun observe(
        type: Class<*>,
        name: String,
        vararg parameters: Class<*>,
        after: (XposedInterface.Chain, Any?, Throwable?) -> Unit,
    ): Boolean = installObserver(type, name, parameters) { method ->
        observeMethod(method, after)
    }

    internal fun observeBefore(
        type: Class<*>,
        name: String,
        vararg parameters: Class<*>,
        before: (XposedInterface.Chain) -> Unit,
    ): Boolean = installObserver(type, name, parameters) { method ->
        hook(method).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
            guard(name) { before(chain) }
            chain.proceed()
        }
    }

    private fun installObserver(
        type: Class<*>,
        name: String,
        parameters: Array<out Class<*>>,
        install: (Method) -> Unit,
    ): Boolean {
        val signature = "${type.name}#$name(${parameters.joinToString(",") { it.simpleName }})"
        return try {
            install(type.getDeclaredMethod(name, *parameters))
            framework("hook_ok process=$processName method=$signature")
            true
        } catch (error: Exception) {
            framework("hook_unavailable process=$processName method=$signature error=${error.javaClass.simpleName}")
            false
        } catch (error: LinkageError) {
            framework("hook_unavailable process=$processName method=$signature error=${error.javaClass.simpleName}")
            false
        }
    }

    private fun observeMethod(method: Method, after: (XposedInterface.Chain, Any?, Throwable?) -> Unit) {
        hook(method).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
            val result = try {
                chain.proceed()
            } catch (original: Throwable) {
                guard(method.name) { after(chain, null, original) }
                throw original
            }
            guard(method.name) { after(chain, result, null) }
            result
        }
    }

    internal inline fun guard(label: String, action: () -> Unit) {
        try {
            action()
        } catch (error: Exception) {
            framework("observer_error source=$label error=${error.javaClass.simpleName}")
        } catch (error: LinkageError) {
            framework("observer_error source=$label error=${error.javaClass.simpleName}")
        }
    }

    internal fun framework(message: String) {
        Log.i(ModuleSettings.TAG, message)
        try {
            log(Log.INFO, ModuleSettings.TAG, message)
        } catch (_: RuntimeException) {
            // The local log remains available if the framework log sink is unavailable.
        }
    }
}

internal fun SharedPreferences?.enabled(key: String): Boolean =
    this?.getBoolean(key, false) == true
