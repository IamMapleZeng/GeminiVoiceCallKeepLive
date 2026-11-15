# Gemini Voice Call Keep Live

中文（默认） · [English](README.en.md)

Kotlin · Material 3 · LSPosed Modern Xposed API · `io.github.iammaplezeng.voicecall.keepalive`

## 真机诊断

测试机为小米 15 Ultra（`xuanyuan`），HyperOS `OS4.0.3.0.XOACNXM`，Android 17（API 37），使用 SukiSU Ultra 和 LSPosed 2.2.1（7912）。

| 组件 | 真机识别结果 |
| --- | --- |
| Gemini | `com.google.android.apps.bard`，`1.0.995038376`（345），独立 UID 及进程 |
| Google App | `com.google.android.googlequicksearchbox`，`17.72.17.ve.arm64`（301831138），观察到 `:search`、`:interactor` 和 `:googleapp` |
| Live 界面 | Google App 的 `com.google.android.apps.search.assistant.surfaces.voice.robin.main.MainActivity` |
| Live 音频采集 | 修复版的 `AudioRecord` 状态回调在 Google App 的 `:interactor` 进程收到，系统音频策略将录音归属给 Google App。 |
| Live 前台服务 | Google App 的 `…robin.ui.conversationmode.service.LiveService`，宿主为 `:search`，FGS 类型 `0xc0`（camera + microphone） |
| Google Play 服务 | `com.google.android.gms`，独立 UID。仅用 ADB 观察相关进程。 |

2026-11-15（北京时间）在 USB 供电条件下取得的原始行为基线：

- 手动锁屏后可以听到 Gemini 回复。ADB 同时看到 LiveService 保持前台，Google App 录音的 `silenced=false`，仍持有音频焦点。
- 第二次按电源键后，熄屏日志时间为 **07:17:43.641**。**07:20:43.172**，Google App 的 Live 主任务报告 `blne`，异常栈指向 `blnr.invokeSuspend`。随后录音停止、焦点释放，前台服务以 `STOP_SERVICE` 退出。前后 `:search` 的 PID 都是 9947。
- 从手机读取该版本的 APK，并使用 `apkanalyzer dex code` 核对。异常栈上的 `blnr.invokeSuspend(Object): Object` 属于无活动计时器，其等待到期分支抛出 `blne`。应用另有 `blns.g(hiiq): hija` 隐私计时器，抛出的是不同的异常 `blnf`。
- HyperOS 日志中也出现 Google App 向冻结的 Google Play 服务发起 Binder 调用。该现象在会话仍能运行时已出现，现有证据未将它与这次退出关联。USB 供电基线也无法覆盖拔线后的 Doze 或 HyperOS 省电场景。

Google 官方要求开启「锁屏时使用 Gemini」与 Live 通知。Android 17 对背景播放及申请音频焦点有额外的前台服务和使用期间能力检查。模块使用 Google App 已经启动的 LiveService。 [Gemini Live 帮助](https://support.google.com/gemini/answer/15274899?hl=zh-Hans) · [Android 17 后台音频限制](https://developer.android.com/about/versions/17/changes/bg-audio) · [麦克风前台服务规则](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)

## 实现和适用范围

两个保活开关默认关闭。已验证的 ROM、设备与 Google App 版本匹配后，行为 Hook 只在 Google App 的 `:search` 中安装。真实镜像的 `PowerManagerService` 要求 `DEVICE_POWER` 才能读取熄屏原因，因此「系统框架」作用域仅安装一个引导阶段观察 Hook 并注册系统熄屏广播接收器。它通过需要 `DEVICE_POWER` 发送权限的定向广播传送系统判定的电源键或息屏超时原因。未知原因、权限拒绝和签名缺失走应用原来的路径。

熄屏前已启动的 LiveService 仍处于 microphone 前台服务、麦克风权限仍有效且相应开关已打开时，`blnr.invokeSuspend` 的成功续体回调可用 Google App 同一套可取消的 `hitf.c(long, hiiq)` 延后一次，最多 30 秒。到达下一次回调再次检查状态，累计窗口从本次熄屏起算，取设置中的「无活动延后上限」。原始调度按系统允许的时机运行。应用主动结束和隐私超时仍通过应用自身的协程取消流程。保活成功时 Google App 自己负责麦克风、音频路由和网络。

版本、类、方法、字段或系统熄屏观察不可用时，保活拒绝介入。系统省电、进程冻结、音频权限、麦克风隐私指示和屏幕锁均遵循系统行为。如果中断来自服务端限制、网络失效、进程死亡、麦克风硬件开关或独立隐私计时器，本实现无法让原会话继续。 [Android 休眠与待机](https://developer.android.com/training/monitoring-device-state/doze-standby) · [libxposed API](https://github.com/libxposed/api)

## 无活动延后上限

在模块设置的「保活」中点击「无活动延后上限」，选择 **1–20 分钟**中的任意整数，然后保存。默认值为 **20 分钟**，从 0.1.0 升级时沿用此默认值和已有开关状态。手动锁屏与自动息屏共用这个值。

例如设为 10 分钟后，模块只在熄屏后的前 10 分钟内延后已到期的无活动计时器。窗口到期后，Gemini 恢复自己的无活动判断，正在正常交谈的会话可以继续。正在运行的新版 Hook 会在下一次计时检查读取改动，已经调度的一次检查最多等待 30 秒。

## 构建和安装

需要 JDK 17、Android SDK Platform 37.0、SDK Build Tools 36.0.0，以及联网解析 Google Maven 和 Maven Central。项目包含 Gradle Wrapper 9.5.0。

```sh
./gradlew :app:assembleDebug :app:lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

1. 在 LSPosed 管理器启用 **Gemini Voice Call Keep Live**。保活需要勾选「系统框架」（虚拟包 `system`）和「Google」。可同时勾选「Gemini」以记录入口应用。Google Play 服务和其他应用无需勾选。
2. 重启手机以加载系统侧熄屏原因观察器。首次启动后解锁一次并打开模块设置，确认框架服务已连接及作用域正确。覆盖安装 0.1.1 后重启一次，让已注入的进程加载新版 Hook。
3. 在 Gemini 设置中开启「锁屏时使用 Gemini」，在系统通知设置中允许 Google 的 Live 通知。在模块界面分别开启需要的保活开关。收集问题时打开「调试日志」和「音频状态监控」。
4. 打开 Gemini，让其在解锁状态下进入 Live。日志中核对 `power_reason_observer_ready`、`grace_ready` 和 `:search` 的 `monitor_ready`。设置页「LSPosed Hook 状态」显示最近一次进程上报。真机设置页成功保存 Google App 各进程的状态。独立 Gemini 入口包受应用可见性限制，该包的注入状态从 Logcat 或 LSPosed 模块日志查阅。

## 日志和故障恢复

```sh
adb logcat -v threadtime -s GeminiLiveKeep
python3 scripts/diagnose.py live_unlocked
python3 scripts/diagnose.py --samples 8 --interval 30 manual_lock
python3 scripts/diagnose.py after_failure
```

模块日志只记录进程、时间、系统或 Hook 状态、异常类型、观察到的组件名、音频路由类型与布尔状态。它不拷贝声音、对话、通知内容、Intent 参数、网络地址或账户数据。录音使用 `AudioRecord` 已有实例的状态回调。原生媒体实现可能绕过 Java 音频 Hook，可与 ADB 的音频策略快照互证。脚本只将指定字段输出到终端；建议在分享前一并审阅自行采集的系统日志。

排障顺序：

1. **有 `live_sleep_reason`，无 `inactivity_timer_deferred`**。比对同一 PID 下的 `live_service`，核对前台服务是否早于熄屏且仍包含 microphone 类型。若应用采用了其他结束路径，用系统日志确认故障时刻与对应异常。
2. **无 `live_sleep_reason`**。看是否出现 `power_reason_observer_ready`，确认系统作用域和重启状态。手动电源键与自动息屏的测试各自使用对应开关，ADB 的 `mLastSleepReason` 可辅助确认。
3. **`silenced=true` 或出现 `AudioHardening`**。查看录音、前台服务类型以及服务进程的 while-in-use 能力。应用在可见状态下启动 Live，避免锁屏后才尝试重建服务。
4. **PID 变化或网络先断开**。用 `dumpsys activity exit-info com.google.android.googlequicksearchbox` 和系统的 `am_proc_died` 核对进程退出，用 `network_blocked`、`network_lost` 和各传输状态排查网络。

关闭任一开关会在下一次计时检查停止对应的延后。完整撤销时，在 LSPosed 禁用模块并重启手机，再卸载 APK。应用若出现异常，也可先关闭功能开关，再重新启动 Google App。

## 验收

每次开始都在解锁且 Gemini 可见时启动 Live，分别记录电源、音频路由、网络、最后一次有效对话的时间和应用版本。保持屏幕暗、用相同测试话术，不要把话术写入日志。每个用例重复运行一次，并保留有开关和无开关的对照。

| 用例 | 验收信号 |
| --- | --- |
| 解锁正常对话 | 能听见回答；Google LiveService 前台与录音正常。 |
| 电源键锁屏，1 分钟 | 原会话继续，屏幕关闭，可完成一轮对话。 |
| 电源键锁屏，5 / 15 分钟 | 各时点完成多轮对话；需要延后时出现 `reason=power_button`。 |
| 自动关闭屏幕，1 / 5 / 15 分钟 | 开始 Live 后返回桌面，让系统息屏。Android 确认 `timeout`，需要延后时出现 `reason=timeout`。 |
| 锁屏中持续多轮，随后解锁 | 对话继续；解锁后用 Live 通知返回同一会话。 |
| Wi‑Fi 与移动数据互切 | 记录新传输及验证状态；分别核对链路切换期间和切换完成后的回答。 |
| 蓝牙耳机通话 | 锁屏后核对耳机的收音、播放和路由；另测扬声器。 |
| 用户主动退出 | 使用界面或通知的「结束」；服务、焦点和录音释放，无重新启动。 |
| 关闭保活开关 / 熄屏窗口到期 | 下次计时回调回到应用的正常超时行为。 |
| 拔掉 USB，省电环境复测 | 用系统日志和稀疏快照确认真实设备空闲行为。 |

0.1.0 在同一台真机通过 Gradle `:app:assembleDebug`、`:app:lintDebug`、APK v2 签名校验、ADB 安装和两端 Hook 加载。当时交付的 0.1.0 APK 与真机安装文件的 SHA-256 一致。

修复版电源键锁屏复测从 **07:38:04** 开始。ADB 在锁屏约 1、5、15 分钟均确认同一个 `:search` 进程及 LiveService 保持前台，Google App 录音未被静音且持有音频焦点。**07:39:02** 首次观察到计时器延后。**07:41:33** 的系统日志和用户确认均表明熄屏时扬声器能播出答复。**07:53:17** 仍处于息屏，录音与前台服务继续运行。该轮测试使用 USB 供电与 Wi‑Fi／VPN。

**07:53:47**，同一会话在锁屏超过 15 分钟后再次从扬声器播出答复。用户随后解锁，确认回到原会话并主动结束。**07:53:58** 的模块日志依次记录服务结束、前台服务撤销和音频采集停止，随后的系统快照确认已无该 LiveService 与活动录音。

蓝牙耳机已验证亮屏时的 A2DP 播放（路由类型 8），当时录音仍使用手机麦克风。全屏 Live 测试中，系统的 15 秒息屏时限到达后屏幕仍亮着，Live 在自动息屏前结束。之后已验证系统可识别 `timeout` 原因。后台 Live 自动息屏后的保活和蓝牙对话仍待端到端复测。系统息屏时间已恢复到原来的 5 分钟。该次真机未提供可用的移动数据，Wi‑Fi／移动数据切换及拔线后的省电场景也待测。

后续蓝牙复测由用户在 Live 开始后返回桌面，系统在 Live 仍运行的一分钟内保持亮屏。Live 结束约 16 秒后才出现屏幕关闭事件。按用户决定停止该项复测，恢复 5 分钟息屏时间，继续采用已经验证的电源键锁屏模式。0.1.1 将延后窗口改为可配置，并保留 20 分钟的默认值。

0.1.1 已通过构建、Android Lint 和 APK 签名校验。配置读取验证覆盖了 1–20 分钟的全部选项、旧版配置和无效值回退。上面的长时间锁屏结果来自 0.1.0。
