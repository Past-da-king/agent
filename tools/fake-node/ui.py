"""Drive an Android device over adb by on-screen text: python3 ui.py tap "Text" | text "words" | dump | shot name.png | key 4"""
import os, re, subprocess, sys, time
import xml.etree.ElementTree as ET

ADB = [os.path.expanduser("~/android-sdk/platform-tools/adb"), "-s", os.environ.get("SERIAL", "localhost:5555")]


def sh(*a):
    return subprocess.run(ADB + list(a), capture_output=True, text=True).stdout


def nodes():
    sh("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    xml = subprocess.run(ADB + ["exec-out", "cat", "/sdcard/ui.xml"], capture_output=True).stdout.decode("utf-8", "replace")
    root = ET.fromstring(xml[xml.index("<"):])
    for n in root.iter("node"):
        b = [int(x) for x in re.findall(r"\d+", n.get("bounds", "[0,0][0,0]"))]
        yield n.get("text", ""), n.get("content-desc", ""), b


def find(label):
    for _ in range(10):
        for t, d, b in nodes():
            if label in (t, d) or (label.startswith("~") and (label[1:] in t or label[1:] in d)):
                return (b[0] + b[2]) // 2, (b[1] + b[3]) // 2
        time.sleep(1)
    sys.exit(f"not found: {label}")


cmd = sys.argv[1]
if cmd == "tap":
    x, y = find(sys.argv[2]); sh("shell", "input", "tap", str(x), str(y)); print("tapped", sys.argv[2], x, y)
elif cmd == "text":
    sh("shell", "input", "text", sys.argv[2].replace(" ", "%s"))
elif cmd == "key":
    sh("shell", "input", "keyevent", sys.argv[2])
elif cmd == "dump":
    for t, d, b in nodes():
        if t or d: print(repr(t or d), b)
elif cmd == "shot":
    open(sys.argv[2], "wb").write(subprocess.run(ADB + ["exec-out", "screencap", "-p"], capture_output=True).stdout); print("saved", sys.argv[2])
