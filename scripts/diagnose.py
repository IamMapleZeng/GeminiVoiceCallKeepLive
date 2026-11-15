#!/usr/bin/env python3
"""Read-only ADB snapshots for Gemini Live, with allowlisted output fields."""
import argparse
import datetime
import os
import re
import subprocess
import sys
import time

GEMINI = "com.google.android.apps.bard"
GOOGLE = "com.google.android.googlequicksearchbox"
PLAY_SERVICES = "com.google.android.gms"
PACKAGES = (GEMINI, GOOGLE, PLAY_SERVICES)
LIVE_SERVICE = (
    "com.google.android.apps.search.assistant.surfaces.voice.robin.ui."
    "conversationmode.service.LiveService"
)

def adb_output(adb, *arguments):
    return subprocess.run(
        [adb, *arguments], capture_output=True, text=True, check=True, timeout=15
    ).stdout

def shell(adb, *arguments):
    return adb_output(adb, "shell", *arguments)

def matching(pattern, text):
    return re.findall(pattern, text, re.MULTILINE)

def snapshot(adb, label):
    print(f"\n[{datetime.datetime.now(datetime.timezone.utc).isoformat(timespec='seconds')}] {label}", flush=True)
    power = shell(adb, "dumpsys", "power")
    keys = r"mWakefulness|mIsPowered|mLastSleepReason|mHoldingWakeLockSuspendBlocker"
    for key, value in dict(reversed(matching(rf"^\s*({keys})=(\w+)", power))).items():
        print(f"{key}={value}")
    for state in dict.fromkeys(matching(r"Display Power: state=([A-Z_]+)", power)):
        print(f"display={state}")

    idle = shell(adb, "dumpsys", "deviceidle")
    for key, value in matching(r"(mState|mLightState|mCharging|mScreenOn)=([A-Z_]+|true|false)", idle):
        print(f"{key}={value}")

    processes = shell(adb, "ps", "-A", "-o", "PID,UID,NAME")
    for pid, uid, name in matching(r"^\s*(\d+)\s+(\d+)\s+([\w.:-]+)$", processes):
        if any(name == pkg or name.startswith((pkg + ":", pkg + ".")) for pkg in PACKAGES):
            print(f"process={name} pid={pid} uid={uid}")

    services = shell(adb, "dumpsys", "activity", "services", GOOGLE)
    live = next((block for block in services.split("ServiceRecord{") if LIVE_SERVICE in block), "")
    foreground = re.search(r"isForeground=(true|false)\b.*?\btypes=(0x[0-9a-fA-F]+)", live)
    service_process = re.search(r"\bapp=ProcessRecord\{\w+ (\d+):([\w.:-]+)/", live)
    print(f"live_service_present={bool(live)}")
    if service_process:
        print(f"live_service_process={service_process[2]} pid={service_process[1]}")
    if foreground:
        mask = int(foreground[2], 16)
        print(f"live_service_foreground={foreground[1]} fgs_type={foreground[2]} microphone_type={bool(mask & 128)}")
    elif live:
        print("live_service_foreground=not_reported")

    audio = shell(adb, "dumpsys", "audio")
    capture = matching(
        rf"^\s*session:\d+.*?-- pack:({re.escape(GOOGLE)}|{re.escape(GEMINI)})\s+.*?-- silenced:(true|false)\b",
        audio,
    )
    print(f"attributed_recordings={len(capture)}")
    for package, muted in capture:
        print(f"recording_package={package} silenced={muted}")
    focus = audio.split("Audio Focus stack entries", 1)[-1].split("\n\n", 1)[0]
    for package, loss in matching(
        rf" -- pack:\s+({re.escape(GOOGLE)}|{re.escape(GEMINI)})\s+.*? -- loss:\s+([A-Za-z_]\w*)", focus
    ):
        print(f"focus_package={package} loss={loss}")
    sys.stdout.flush()

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default=os.environ.get("ADB", "adb"))
    parser.add_argument("--samples", type=int, default=1)
    parser.add_argument("--interval", type=float, default=5)
    parser.add_argument("label", help="Short test label, with no personal information")
    args = parser.parse_args()
    if not 1 <= args.samples <= 200 or not 2 <= args.interval <= 300:
        parser.error("samples must be 1–200 and interval 2–300 seconds")
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,48}", args.label):
        parser.error("label must be 1–48 ASCII letters, digits, underscores or hyphens")
    try:
        for index in range(args.samples):
            if index:
                time.sleep(args.interval)
            snapshot(args.adb, args.label)
    except (OSError, subprocess.CalledProcessError, subprocess.TimeoutExpired) as error:
        print(f"ADB snapshot unavailable ({type(error).__name__}). Check adb devices.", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        return 130
    return 0

if __name__ == "__main__":
    sys.exit(main())
