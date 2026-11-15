package io.github.iammaplezeng.voicecall.keepalive

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import org.json.JSONObject

class HookStatusProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != ModuleSettings.REPORT_HOOKS || extras == null) return null
        val app = context ?: return null
        val caller = callingPackage ?: return null
        if (caller !in ModuleSettings.targetPackages ||
            caller !in app.packageManager.getPackagesForUid(Binder.getCallingUid()).orEmpty()
        ) return null

        val accepted = try {
            val process = extras.getString("process").orEmpty()
            val hooks = extras.getInt("hooks")
            val errors = extras.getInt("errors")
            if (extras.getString("package") != caller || !PROCESS.matches(process) ||
                hooks !in 1..100 || errors !in 0..100
            ) return null
            val record = HookReport(caller, process, Binder.getCallingPid(), hooks, errors,
                extras.getBoolean("prefs"), extras.getBoolean("grace"), System.currentTimeMillis())
            HookReport.save(app, record)
            true
        } catch (_: RuntimeException) {
            false
        }
        return Bundle().apply { putBoolean("accepted", accepted) }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int = 0
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = 0

    companion object {
        private val PROCESS = Regex("[A-Za-z0-9_.:-]{1,180}")
    }
}

internal data class HookReport(
    val owner: String,
    val process: String,
    val pid: Int,
    val hooks: Int,
    val errors: Int,
    val prefs: Boolean,
    val grace: Boolean,
    val timestamp: Long,
) {
    private fun toJson() = JSONObject().apply {
        put("owner", owner)
        put("process", process)
        put("pid", pid)
        put("hooks", hooks)
        put("errors", errors)
        put("prefs", prefs)
        put("grace", grace)
        put("timestamp", timestamp)
    }.toString()

    companion object {
        private const val LIMIT = 16

        private fun store(context: Context) = context.createDeviceProtectedStorageContext()
            .getSharedPreferences("hook_reports", Context.MODE_PRIVATE)

        @Synchronized
        fun save(context: Context, report: HookReport) {
            val recent = (listOf(report) + read(context).filter {
                it.owner != report.owner || it.process != report.process
            }).take(LIMIT)
            val update = store(context).edit().clear()
            recent.forEach { update.putString("${it.owner}#${it.process}", it.toJson()) }
            update.apply()
        }

        fun read(context: Context): List<HookReport> =
            store(context).all.values.mapNotNull { value ->
                try {
                    val json = JSONObject(value as String)
                    HookReport(json.getString("owner"), json.getString("process"), json.getInt("pid"),
                        json.getInt("hooks"), json.getInt("errors"), json.getBoolean("prefs"),
                        json.optBoolean("grace"), json.getLong("timestamp"))
                } catch (_: Exception) {
                    null
                }
            }.sortedByDescending { it.timestamp }
    }
}
