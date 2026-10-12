#!/usr/bin/env python3
"""Screenshots of every LibreHU app on an Android 9 emulator (1280x720, 160 dpi, French), for their READMEs.

  shoot.py download   APKs of the latest releases (plan.json) into apks/ (GH_TOKEN, gh CLI)
  shoot.py capture    installs them on the running emulator and plays plan.json, PNGs in out/<app>/

Each "shot" step also saves the screen's UI tree (<name>.xml) to plan the taps of the next steps.
"""
import json
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
PLAN = json.load(open(os.path.join(HERE, "plan.json")))
APKS = os.path.join(HERE, "apks")
OUT = os.path.join(HERE, "out")


def sh(*cmd, check=True, **kw):
    print("+", " ".join(cmd), flush=True)
    return subprocess.run(cmd, check=check, text=True, capture_output=True, **kw)


def adb(*args, check=False):
    r = sh("adb", *args, check=check)
    if r.returncode:
        print(r.stdout, r.stderr, flush=True)
    return r.stdout


def download():
    os.makedirs(APKS, exist_ok=True)
    for app in PLAN["apps"]:
        sh("gh", "release", "download", app["tag"], "-R", app["repo"], "-p", "*.apk", "-O",
           os.path.join(APKS, app["id"] + ".apk"), "--clobber")


def wait_boot():
    for _ in range(120):
        if adb("shell", "pidof", "com.android.systemui").strip() and \
                adb("shell", "getprop", "sys.boot_completed").strip() == "1":
            return
        time.sleep(2)


def setup():
    adb("root")
    time.sleep(3)
    adb("wait-for-device")
    adb("shell", "wm", "size", "1280x720")
    adb("shell", "wm", "density", "160")
    adb("shell", "setprop", "persist.sys.locale", "fr-FR")
    adb("shell", "setprop", "persist.sys.timezone", "Europe/Paris")
    adb("shell", "setprop", "ctl.restart", "zygote")
    time.sleep(10)
    wait_boot()
    time.sleep(15)
    # Clean status bar: fixed clock, full battery and signal, no notification icons.
    adb("shell", "settings", "put", "global", "sysui_demo_allowed", "1")
    for extra in (["-e", "command", "enter"],
                  ["-e", "command", "clock", "-e", "hhmm", "1042"],
                  ["-e", "command", "battery", "-e", "level", "100", "-e", "plugged", "false"],
                  ["-e", "command", "network", "-e", "wifi", "show", "-e", "level", "4"],
                  ["-e", "command", "network", "-e", "mobile", "show", "-e", "datatype", "4g", "-e", "level", "4"],
                  ["-e", "command", "notifications", "-e", "visible", "false"]):
        adb("shell", "am", "broadcast", "-a", "com.android.systemui.demo", *extra)


def install():
    for app in PLAN["apps"]:
        apk = os.path.join(APKS, app["id"] + ".apk")
        print(adb("install", "-r", "-g", "-t", apk), flush=True)
        adb("shell", "appops", "set", app["pkg"], "SYSTEM_ALERT_WINDOW", "allow")
        for listener in app.get("listeners", []):
            adb("shell", "cmd", "notification", "allow_listener", listener)
    home = adb("shell", "cmd", "package", "resolve-activity", "--brief", "-a", "android.intent.action.MAIN",
               "-c", "android.intent.category.HOME", "org.librehu.launcher").strip().splitlines()
    if home and "/" in home[-1]:
        adb("shell", "cmd", "package", "set-home-activity", home[-1].strip())


def dump():
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    return adb("shell", "cat", "/sdcard/ui.xml")


def find(xml, pattern):
    try:
        root = ET.fromstring(xml)
    except ET.ParseError:
        return None
    rx = re.compile(pattern, re.I)
    for node in root.iter("node"):
        if rx.search(node.get("text", "")) or rx.search(node.get("content-desc", "")):
            m = re.findall(r"\d+", node.get("bounds", ""))
            if len(m) == 4:
                x1, y1, x2, y2 = map(int, m)
                return (x1 + x2) // 2, (y1 + y2) // 2
    return None


def play(app):
    d = os.path.join(OUT, app["id"])
    os.makedirs(d, exist_ok=True)
    pkg = app["pkg"]
    for step in app.get("steps", [["launch"], ["shot", "main"]]):
        op, args = step[0], step[1:]
        if op == "launch":
            adb("shell", "am", "force-stop", pkg)
            adb("shell", "monkey", "-p", pkg, "-c", "android.intent.category.LAUNCHER", "1")
            time.sleep(float(args[0]) if args else 5)
        elif op == "start":
            adb("shell", "am", "start", *args)
            time.sleep(4)
        elif op == "home":
            adb("shell", "input", "keyevent", "KEYCODE_HOME")
            time.sleep(4)
        elif op == "back":
            adb("shell", "input", "keyevent", "KEYCODE_BACK")
            time.sleep(2)
        elif op == "key":
            adb("shell", "input", "keyevent", args[0])
            time.sleep(2)
        elif op == "wait":
            time.sleep(float(args[0]))
        elif op == "tap":
            xy = find(dump(), args[0])
            if xy:
                adb("shell", "input", "tap", str(xy[0]), str(xy[1]))
                time.sleep(3)
            else:
                print(f"!! {app['id']}: nothing matches {args[0]!r}", flush=True)
        elif op == "tapxy":
            adb("shell", "input", "tap", str(args[0]), str(args[1]))
            time.sleep(3)
        elif op == "longpress":
            x, y = args[0], args[1]
            adb("shell", "input", "swipe", str(x), str(y), str(x), str(y), "1200")
            time.sleep(3)
        elif op == "swipe":
            adb("shell", "input", "swipe", *map(str, args[:4]), "400")
            time.sleep(2)
        elif op == "shot":
            open(os.path.join(d, args[0] + ".xml"), "w").write(dump())
            png = subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True).stdout
            open(os.path.join(d, args[0] + ".png"), "wb").write(png)
    crashes = adb("logcat", "-d", "-b", "crash")
    if crashes.strip():
        open(os.path.join(d, "crash.txt"), "w").write(crashes)
    adb("logcat", "-c", "-b", "crash")


def capture():
    os.makedirs(OUT, exist_ok=True)
    setup()
    install()
    only = sys.argv[2:]
    for app in PLAN["apps"]:
        if only and app["id"] not in only:
            continue
        try:
            play(app)
        except Exception as e:  # one app failing does not stop the others
            print(f"!! {app['id']}: {e}", flush=True)
    adb("shell", "input", "keyevent", "KEYCODE_HOME")


if __name__ == "__main__":
    {"download": download, "capture": capture}[sys.argv[1]]()
