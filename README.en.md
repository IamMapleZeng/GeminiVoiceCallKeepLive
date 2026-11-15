# Gemini Voice Call Keep Live

[简体中文（默认）](README.md) · English

Kotlin · Material 3 · Modern Xposed API · `io.github.iammaplezeng.voicecall.keepalive`

## Device diagnosis

The test device was a Xiaomi 15 Ultra (`xuanyuan`) running HyperOS `OS4.0.3.0.XOACNXM`, Android 17 (API 37), SukiSU Ultra, and LSPosed 2.2.1 (7912).

| Component | Observed on the device |
| --- | --- |
| Gemini | `com.google.android.apps.bard`, `1.0.995038376` (345), with its own UID and process |
| Google app | `com.google.android.googlequicksearchbox`, `17.72.17.ve.arm64` (301831138), with `:search`, `:interactor`, and `:googleapp` processes |
| Live activity | `com.google.android.apps.search.assistant.surfaces.voice.robin.main.MainActivity` in the Google app |
| Live recording | The Google app's `:interactor` process received the `AudioRecord` callback. Android audio policy attributed the recording to the Google app. |
| Live foreground service | `…robin.ui.conversationmode.service.LiveService` in the Google app's `:search` process, with foreground service type `0xc0` (camera and microphone) |
| Google Play services | `com.google.android.gms`, with a separate UID. Observed through ADB only. |

The baseline was captured on November 15, 2026, Beijing time, with USB power connected.

- After a manual lock, Gemini could still reply. LiveService remained in the foreground, recording reported `silenced=false`, and the Google app retained audio focus.
- After the second press of the power button, the display went off at **07:17:43.641**. At **07:20:43.172**, the Live job reported `blne` from `blnr.invokeSuspend`. Recording then stopped, audio focus was released, and the foreground service reported `STOP_SERVICE`. The `:search` PID stayed at 9947.
- Inspection of the phone's APK with `apkanalyzer dex code` confirmed that `blnr.invokeSuspend(Object): Object` throws `blne` when its inactivity timer expires. A separate privacy timer, `blns.g(hiiq): hija`, throws `blnf`.
- HyperOS also logged Google app Binder calls to frozen Google Play services while Live was still functioning. The available evidence does not link them to this exit. The USB-powered test did not exercise unplugged Doze or HyperOS power saving.

Enable Gemini on the lock screen and allow Live notifications, as specified by Google. Android 17 also checks the app's foreground service and while-in-use capability for background playback and audio focus. The module relies on the LiveService already started by the Google app. [Gemini Live help](https://support.google.com/gemini/answer/15274899?hl=en) · [Android 17 background audio](https://developer.android.com/about/versions/17/changes/bg-audio) · [Microphone foreground service restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)

## Implementation and compatibility

The keep-live switches default to off. The behavior hook loads in the Google app's `:search` process on the verified device, ROM, and Google app build. HyperOS restricts the sleep-reason query to `DEVICE_POWER`. In the system framework scope, a boot observer registers a receiver and forwards power-button and display-timeout events through a package-directed broadcast that requires `DEVICE_POWER` at the sender. An unknown reason, denied permission, or incompatible method signature leaves the app's normal flow in effect.

LiveService must already be running when the display turns off. It must remain a microphone foreground service, microphone permission must still be granted, and the corresponding switch must be on. When these conditions hold, the successful continuation of `blnr.invokeSuspend` can use the Google app's cancellable `hitf.c(long, hiiq)` delay for up to 30 seconds at a time. Each callback checks the conditions and the remaining time again. User-initiated ending and the privacy timer retain the app's normal cancellation flow. The Google app manages audio, routing, and networking.

System power saving, process freezing, microphone permissions and privacy indicators, and the lock screen remain under Android control. A server-side session limit, network loss, process exit, hardware microphone mute, or the separate privacy timer can still end or interrupt the call. [Android Doze and App Standby](https://developer.android.com/training/monitoring-device-state/doze-standby) · [libxposed API](https://github.com/libxposed/api)

## Inactivity deferral limit

Open the module settings and select **无活动延后上限** under **保活**. Pick any whole number from **1 to 20 minutes**, then save. The default is **20 minutes**. An upgrade from 0.1.0 retains existing switch settings and uses that default until you choose a value. The same limit applies to manual lock and automatic display timeout.

For example, setting 10 minutes lets the module defer an expired inactivity timer during the first 10 minutes after the display goes off. Gemini resumes its usual inactivity handling after the window ends. An active conversation can continue beyond the limit. A running copy of the updated hook reads changes on the next timer check. An already scheduled check can take up to 30 seconds.

## Quick-entry screen-off keep-live

On the test phone, the Gemini floating task was removed when the display turned off, followed immediately by a LiveService stop. Version 0.1.3 adds **快捷入口熄屏保活**, which defaults to off. If enabled, the hook selects the Google app's existing floaty-task removal branch for an active microphone foreground LiveService while the display is off. The manual lock and automatic timeout switches still control inactivity deferral. Debug logs expose `floaty_task_removed` and `floaty_task_preserved`.

The user verified that replies continued after starting Live from the floating entry and locking the target phone. Android events show the same service running through display-off and display-on.

## Screen-on keep-live

Turn on **亮屏保活** and configure **亮屏延后上限** separately. The limit ranges from **1 to 20 minutes** and defaults to **20 minutes**. The window starts at the later of Live starting or the display waking. It applies while the phone is unlocked and the display is on, both in Gemini and with another app in the foreground. Gemini's regular inactivity handling resumes when the window ends.

The switch defaults to off and uses the Google app scope. The existing screen-off switches and their limit apply after the display goes off. A deferral while the screen is on logs `reason=screen_on`.

## Build and install

Use JDK 17, Android SDK Platform 37.0, and SDK Build Tools 36.0.0. Dependency resolution uses Google Maven and Maven Central. The project includes Gradle Wrapper 9.5.0.

```sh
./gradlew :app:assembleDebug :app:lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

1. Enable **Gemini Voice Call Keep Live** in LSPosed. Select **System Framework** (virtual package `system`) and **Google**. Optionally scope **Gemini** to log the entry app. Google Play services can remain outside the scope.
2. Reboot to load the system observer. Unlock and check the framework connection and scope on the settings screen. Reboot once after upgrading so previously injected processes load the updated hook.
3. Enable Gemini on the lock screen, allow Google Live notifications, and enable the module switches you use. Turn on debug logging and audio monitoring when collecting diagnostics.
4. Start Live while unlocked. Look for `power_reason_observer_ready`, `grace_ready`, and `monitor_ready` for `:search`. The settings screen shows the latest in-process Hook reports. Google app process reports were available on the test device. Check Logcat or the LSPosed module log for the standalone Gemini app if Android package visibility blocks its report.

## Diagnostics and recovery

```sh
adb logcat -v threadtime -s GeminiLiveKeep
python3 scripts/diagnose.py live_unlocked
python3 scripts/diagnose.py --samples 8 --interval 30 manual_lock
python3 scripts/diagnose.py after_failure
```

The module records process, time, system and hook states, exception types, component names, audio route types, and status flags. Voice recordings, conversation text, notifications, Intent payloads, network addresses, and account data are excluded. Recording diagnostics use status callbacks on existing `AudioRecord` instances. Native media code can bypass the Java hooks. Use ADB audio-policy snapshots for corroboration, and review any system logs before sharing them.

1. If `live_sleep_reason` appears without `inactivity_timer_deferred`, check that `live_service` has the same PID and that LiveService was already a microphone foreground service before sleep. Check the failure time and exception for other termination paths.
2. If `live_sleep_reason` is absent, check `power_reason_observer_ready`, the system framework scope, and whether the device has rebooted. Use the corresponding switch for each sleep reason. ADB's `mLastSleepReason` helps verify it.
3. If `silenced=true` or `AudioHardening` appears, inspect the recording, foreground service type, and the process's while-in-use capability. Start Live with the app visible.
4. If the PID changes or networking drops first, check `dumpsys activity exit-info com.google.android.googlequicksearchbox` and `am_proc_died` for process exits. For networking, check `network_blocked`, `network_lost`, and transport state.

Turning off a switch stops the corresponding deferral at the next check. To remove the module, disable it in LSPosed, reboot, then uninstall the app. For app-level issues, turn off the feature and restart the Google app.

## Acceptance tests

Start each call while the phone is unlocked and Gemini is visible. Record power, audio route, network, last successful reply time, and app version. Use consistent prompts without storing them in logs. Repeat with the relevant switch enabled and disabled.

| Scenario | Expected result |
| --- | --- |
| Unlocked conversation | Audible response, active LiveService, and audio recording. |
| Manual lock at 1 minute | The same session can exchange a turn with the display off. |
| Manual lock at 5 and 15 minutes | The same session exchanges multiple turns. Deferral, when needed, reports `reason=power_button`. |
| Automatic timeout at 1, 5, and 15 minutes | Start Live, return Home, and let the system turn off the display. Confirm `timeout` and look for `reason=timeout` if deferral is needed. |
| Unlock after several turns | The Live notification returns to the same conversation. |
| Switch between Wi-Fi and mobile data | Note the validated transport and check replies during and after the handoff. |
| Bluetooth headset | Check microphone, playback, and route with the display off, then check the speaker separately. |
| End the call | The app or notification End action releases recording, audio focus, and the service. |
| Disable deferral or reach its limit | The next timer callback resumes normal app timeout behavior. |
| Disconnect USB | Check the actual power-saving scenario using sparse snapshots and system logs. |

On the Xiaomi device, 0.1.0 passed Gradle assembly and lint, APK v2 signature verification, ADB installation, and both Hook load checks. The delivered 0.1.0 APK matched the device APK's SHA-256.

With USB power and Wi-Fi/VPN, the same session retained its foreground service, unsilenced recording, and audio focus at roughly 1, 5, and 15 minutes after manual lock. The first deferral appeared at **07:39:02**. Audio routing and the user confirmed a speaker reply at **07:41:33** with the display off, and another at **07:53:47** after more than 15 minutes. Unlocking returned to the same session. The user ended it, and at **07:53:58** the service and recording stopped.

Bluetooth A2DP playback (route type 8) was verified with the screen on, with the phone microphone still recording. The screen stayed on past the 15-second system timeout in fullscreen Live. A follow-up test returned Home immediately after starting Live. The screen went off about 16 seconds after that call had already ended. Automatic screen-off deferral and Bluetooth playback during a locked call remain unverified end to end. The original five-minute system timeout was restored. Mobile-data handoff and unplugged power saving remain untested.

Version 0.1.1 passed assembly, Android Lint, and APK signature verification. Preference checks covered all 20 choices, legacy configurations, and invalid-value fallback. The long-duration device results above are from 0.1.0.
