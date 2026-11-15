package io.github.iammaplezeng.voicecall.keepalive

import android.content.SharedPreferences
import android.os.Build

internal object ModuleSettings {
    const val SYSTEM = "system"
    const val GEMINI = "com.google.android.apps.bard"
    const val GOOGLE = "com.google.android.googlequicksearchbox"
    const val LIVE_SERVICE =
        "com.google.android.apps.search.assistant.surfaces.voice.robin.ui.conversationmode.service.LiveService"
    const val GOOGLE_SEARCH = "$GOOGLE:search"
    const val VERIFIED_CODE = 301831138L
    const val VERIFIED_VERSION = "17.72.17.ve.arm64"
    val targetPackages = setOf(GEMINI, GOOGLE)

    const val TAG = "GeminiLiveKeep"
    const val PREFS = "diagnostics"
    const val MANUAL = "power_button_grace"
    const val AUTOMATIC = "screen_timeout_grace"
    const val GRACE_MINUTES = "inactivity_grace_minutes"
    val graceMinuteOptions = 1..20
    const val DEFAULT_GRACE_MINUTES = 20
    const val AUDIO = "audio_monitor"
    const val DEBUG = "debug_logs"
    const val STATUS_AUTHORITY = "io.github.iammaplezeng.voicecall.keepalive.hookstatus"
    const val REPORT_HOOKS = "report_hooks"

    fun graceMinutes(preferences: SharedPreferences): Int {
        val stored = preferences.all[GRACE_MINUTES] as? Int
        return stored?.takeIf { it in graceMinuteOptions } ?: DEFAULT_GRACE_MINUTES
    }

    fun isVerifiedDevice(): Boolean = Build.VERSION.SDK_INT == 37 && Build.DEVICE == "xuanyuan" &&
        Build.VERSION.INCREMENTAL == "OS4.0.3.0.XOACNXM"
}
