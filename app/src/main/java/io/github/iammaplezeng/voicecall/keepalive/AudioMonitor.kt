package io.github.iammaplezeng.voicecall.keepalive

import android.annotation.SuppressLint
import android.app.Application
import android.content.SharedPreferences
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.AudioTrack
import android.media.MediaSyncEvent
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicInteger

internal class AudioMonitor(
    private val module: LiveModule,
    private val app: Application,
    private val prefs: SharedPreferences?,
    private val event: (String) -> Unit,
) {
    private val recordings = WeakHashMap<AudioRecord, Recording>()
    private val nextRecording = AtomicInteger()
    private val intType = Int::class.javaPrimitiveType!!

    fun install(): Int {
        var hooks = 0
        if (recordingHook("startRecording")) hooks++
        if (recordingHook("startRecording", MediaSyncEvent::class.java)) hooks++
        if (recordingHook("stop")) hooks++
        if (recordingHook("release")) hooks++
        if (focusHook("requestAudioFocus", AudioFocusRequest::class.java)) hooks++
        if (focusHook("requestAudioFocus", AudioManager.OnAudioFocusChangeListener::class.java, intType, intType)) hooks++
        if (focusHook("abandonAudioFocusRequest", AudioFocusRequest::class.java)) hooks++
        if (focusHook("abandonAudioFocus", AudioManager.OnAudioFocusChangeListener::class.java)) hooks++
        module.guard("audio_focus_dispatcher") {
            if (installFocusDispatcher()) hooks++
        }
        for (action in listOf("play", "pause", "stop")) {
            if (module.observe(AudioTrack::class.java, action) { chain, _, error ->
                    if (audioEnabled()) {
                        val track = chain.thisObject as AudioTrack
                        event("playback action=$action route_type=${track.routedDevice?.type} " +
                            "error=${error?.javaClass?.simpleName ?: "none"}")
                    }
                }) hooks++
        }
        return hooks
    }

    // Optional LSPosed observer. Reflection failure leaves the public focus hooks installed.
    @SuppressLint("SoonBlockedPrivateApi")
    private fun installFocusDispatcher(): Boolean {
        val manager = app.getSystemService(AudioManager::class.java)
        val field = AudioManager::class.java.getDeclaredField("mAudioFocusDispatcher").apply { isAccessible = true }
        val dispatcher = field.get(manager) ?: return false
        return module.observe(dispatcher.javaClass, "dispatchAudioFocusChange", intType, String::class.java) { chain, _, error ->
            if (audioEnabled()) event("audio_focus dispatch=${chain.getArg(0)} error=${error?.javaClass?.simpleName ?: "none"}")
        }
    }

    private fun recordingHook(name: String, vararg parameters: Class<*>): Boolean =
        module.observe(AudioRecord::class.java, name, *parameters) { chain, _, error ->
            val record = chain.thisObject as AudioRecord
            if (error != null) {
                if (audioEnabled()) event("recording action=$name error=${error.javaClass.simpleName}")
            } else if (name == "startRecording") {
                if (audioEnabled()) onStart(record)
            } else {
                onEnd(record, name)
            }
        }

    private fun onStart(record: AudioRecord) {
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            event("recording action=start state=${record.recordingState}")
            return
        }
        synchronized(recordings) {
            if (recordings.containsKey(record)) return
            val observer = Recording(nextRecording.incrementAndGet())
            recordings[record] = observer
            event("recording action=start stream=${observer.id} source=${record.audioSource} session_signal=capture_started")
            module.guard("recording_callback") {
                record.registerAudioRecordingCallback(app.mainExecutor, observer)
                observer.report(record.activeRecordingConfiguration, "initial")
            }
        }
    }

    private fun onEnd(record: AudioRecord, action: String) {
        synchronized(recordings) {
            val observer = recordings.remove(record) ?: return
            observer.close()
            if (audioEnabled()) event("recording action=$action stream=${observer.id} session_signal=capture_ended")
            module.guard("recording_unregister") { record.unregisterAudioRecordingCallback(observer) }
        }
    }

    private inner class Recording(val id: Int) : AudioManager.AudioRecordingCallback() {
        private var lastState: String? = null
        private var closed = false

        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
            module.guard("recording_config") { report(configs.firstOrNull(), "callback") }
        }

        @Synchronized
        fun close() {
            closed = true
        }

        @Synchronized
        fun report(config: AudioRecordingConfiguration?, source: String) {
            if (closed || !audioEnabled()) return
            val state = if (config == null) "active=false" else {
                "active=true silenced=${config.isClientSilenced} route_type=${config.audioDevice?.type}"
            }
            if (lastState != state) {
                lastState = state
                event("recording_config stream=$id source=$source $state")
            }
        }
    }

    private fun focusHook(action: String, vararg parameters: Class<*>): Boolean =
        module.observe(AudioManager::class.java, action, *parameters) { _, result, error ->
            if (audioEnabled()) event("audio_focus action=$action result=$result error=${error?.javaClass?.simpleName ?: "none"}")
        }

    private fun audioEnabled(): Boolean =
        prefs.enabled(ModuleSettings.DEBUG) && prefs.enabled(ModuleSettings.AUDIO)
}
