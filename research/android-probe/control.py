#!/usr/bin/env python3
"""USB control restricted to this probe's buttons and non-identifying report.
Never dumps other app text to stdout, reads health stores or handles raw frames.
"""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

ADB = os.environ.get("PROBE_ADB", "/opt/homebrew/share/android-commandlinetools/platform-tools/adb")
PACKAGE = "org.whoopbp.probe"
BUTTONS = {
    "scan": "1. НАЙТИ WHOOP 5",
    "connect": "ПОДКЛЮЧИТЬ WHOOP · 1",
    "survey": "2. ПРОЧИТАТЬ СВЕДЕНИЯ И ФЛАГИ",
    "raw": "3. LIVE RAW · 45 СЕКУНД",
    "history": "4. ФОРМА R26 · 2 МИНУТЫ",
    "archive": "СОХРАНИТЬ ОКНА ЛОКАЛЬНО",
    "stop": "ОСТАНОВИТЬ / ОТКЛЮЧИТЬСЯ",
}

def adb(*args):
    return subprocess.run([ADB, *args], check=True, capture_output=True, text=True, timeout=20).stdout

def status():
    return json.loads(adb("exec-out", "run-as", PACKAGE, "cat", "files/report.json"))

def tap(key):
    path = "/data/local/tmp/whoop-probe-ui.xml"
    adb("shell", "uiautomator", "dump", path)
    root = ET.fromstring(adb("exec-out", "cat", path))
    buttons = [n for n in root.iter("node") if n.get("package") == PACKAGE and n.get("class") == "android.widget.Button"]
    if key == "connect" and sum(n.get("text", "").startswith("ПОДКЛЮЧИТЬ WHOOP") for n in buttons) != 1:
        raise SystemExit("Need exactly one discovered WHOOP; select your strap on the phone.")
    matches = [n for n in buttons if n.get("text") == BUTTONS[key] and n.get("enabled") == "true"]
    if len(matches) != 1:
        raise SystemExit(f"Expected unique probe button: {key}; no tap sent.")
    bounds = list(map(int, re.findall(r"\d+", matches[0].get("bounds", ""))))
    if len(bounds) != 4 or bounds[2] <= bounds[0] or bounds[3] <= bounds[1]:
        raise SystemExit("Invalid button bounds; no tap sent.")
    adb("shell", "input", "tap", str((bounds[0]+bounds[2])//2), str((bounds[1]+bounds[3])//2))
    print(f"Tapped probe button: {key}", flush=True)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=[*BUTTONS, "status", "save", "wave", "wait"])
    parser.add_argument("--seconds", type=int, default=5)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if args.action in BUTTONS:
        tap(args.action)
    elif args.action == "status":
        print(json.dumps(status(), indent=2, ensure_ascii=False))
    elif args.action in ("save", "wave"):
        if not args.output:
            parser.error("save needs --output")
        report=status()
        if args.action == "wave":
            local=json.loads(adb("exec-out", "run-as", PACKAGE, "cat", "files/historical-windows.json"))
            if local.get("capture_id") != report.get("capture_id") or not local.get("windows"):
                raise SystemExit("No saved waveform for this session; stale data rejected.")
        else:
            local=report
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(local, indent=2, ensure_ascii=False)+"\n")
        print(f"Saved local {args.action}: {args.output}; windows={len(local.get('windows', []))}")
    elif args.action == "wait":
        if not 1 <= args.seconds <= 50:
            parser.error("wait must be 1..50 seconds")
        end = time.monotonic()+args.seconds
        while time.monotonic() < end:
            time.sleep(min(5, end-time.monotonic()))
            s = status()
            print(json.dumps({k:s.get(k) for k in ["phase", "error", "hello_succeeded", "last_hello_worn", "last_hello_age_seconds", "valid_frames", "historical_versions", "historical_windows_saved"]}), flush=True)

if __name__ == "__main__":
    main()
