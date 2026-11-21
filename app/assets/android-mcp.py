#!/usr/bin/env python3
"""Attc Code Android MCP server.

Exposes Android control tools to opencode through the attc-ctl Shizuku
bridge. Every pointer action is also appended to /root/attc/pointer so the
Attc Code overlay can animate a live cursor over the phone screen.
"""
import base64
import json
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

CTL = "/root/.local/bin/attc-ctl"
POINTER = "/root/attc/pointer"
SHOT_HOST = "/storage/emulated/0/ATTC OPENCODE/shot.png"
SHOT_GUEST = "/root/attc/shot.png"
MAX_SHOT = 4 * 1024 * 1024

last = {"x": 540, "y": 1200}


def log(text):
    try:
        with open("/root/attc/android-mcp.log", "a") as handle:
            handle.write(time.strftime("%H:%M:%S ") + str(text) + "\n")
    except Exception:
        pass


def ctl(command, timeout=30):
    try:
        result = subprocess.run([CTL, command], capture_output=True,
                                text=True, timeout=timeout)
        out = (result.stdout or "").strip()
        err = (result.stderr or "").strip()
        if err and not out:
            out = err
        match = re.match(r"timeout \d+ sh -c .*? -> -?\d+ ?", out, re.S)
        if match:
            out = out[match.end():].strip()
        return result.returncode, out
    except Exception as error:
        return 1, str(error)


def pointer(action, x=None, y=None, x2=None, y2=None, label=None, duration=300):
    try:
        os.makedirs(os.path.dirname(POINTER), exist_ok=True)
        if x is not None:
            last["x"] = int(x)
            last["y"] = int(y)
        event = {"action": action, "label": label or action}
        if x is not None:
            event["x"] = int(x)
            event["y"] = int(y)
        if x2 is not None:
            event["x2"] = int(x2)
            event["y2"] = int(y2)
        event["duration"] = int(duration)
        with open(POINTER, "a") as handle:
            handle.write(json.dumps(event) + "\n")
    except Exception as error:
        log("pointer failed " + str(error))


def safe_text(text):
    bad = chr(34) + chr(36) + chr(96) + chr(92)
    for char in bad:
        text = text.replace(char, "")
    return text


def input_text(text):
    clean = safe_text(str(text))[:200]
    encoded = clean.replace(" ", "%s")
    pointer("type", label="type " + clean[:28])
    code, out = ctl("input text " + json.dumps(encoded))
    return out or ("Typed: " + clean)


def packages():
    code, out = ctl("pm list packages -3")
    names = []
    for line in out.splitlines():
        line = line.strip()
        if line.startswith("package:"):
            names.append(line[8:])
    return names


def find_package(query):
    needle = query.lower().replace(" ", "")
    best = None
    for name in packages():
        short = name.split(".")[-1].lower()
        if needle == short or needle in name.lower():
            return name
        if needle in short and best is None:
            best = name
    return best


def open_target(target):
    target = str(target).strip()
    if target.startswith("http://") or target.startswith("https://") or target.startswith("www."):
        url = target if target.startswith("http") else "https://" + target
        pointer("open", label="open " + url[:40])
        code, out = ctl("am start -a android.intent.action.VIEW -d " + json.dumps(url))
        return out or ("Opened " + url)
    if "." in target and " " not in target:
        pointer("open", label="open " + target)
        code, out = ctl("monkey -p " + json.dumps(target) + " -c android.intent.category.LAUNCHER 1")
        return out or ("Launched " + target)
    package = find_package(target)
    if package:
        return open_target(package)
    pointer("open", label="open " + target[:40])
    code, out = ctl("am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER")
    return "No package matched " + target + ". " + out


def dump_ui():
    ctl("uiautomator dump /sdcard/attc-ui.xml >/dev/null 2>&1")
    code, out = ctl("cat /sdcard/attc-ui.xml")
    return out


def bounds_center(bounds):
    numbers = re.findall(r"[0-9]+", bounds or "")
    if len(numbers) >= 4:
        x1, y1, x2, y2 = [int(value) for value in numbers[:4]]
        return (x1 + x2) // 2, (y1 + y2) // 2
    return None


def compact_ui(raw):
    if not raw or not raw.startswith("<"):
        return raw[:4000] if raw else "UI dump empty"
    try:
        root = ET.fromstring(raw)
    except Exception as error:
        return "UI XML parse failed: " + str(error) + "\n" + raw[:2000]
    lines = []
    for node in root.iter("node"):
        text = node.get("text", "")
        desc = node.get("content-desc", "")
        rid = node.get("resource-id", "")
        cls = node.get("class", "")
        bounds = node.get("bounds", "")
        clickable = node.get("clickable", "false")
        if not text and not desc and clickable != "true":
            continue
        line = "text=" + json.dumps(text) + " desc=" + json.dumps(desc)
        line += " id=" + json.dumps(rid) + " class=" + json.dumps(cls)
        line += " clickable=" + clickable + " bounds=" + bounds
        lines.append(line)
        if len(lines) >= 120:
            break
    return "\n".join(lines) if lines else "No labelled nodes; use android_screenshot"


def tap_text(query):
    raw = dump_ui()
    try:
        root = ET.fromstring(raw)
    except Exception:
        return "UI dump failed"
    needle = str(query).lower()
    for node in root.iter("node"):
        text = (node.get("text", "") + " " + node.get("content-desc", "")).lower()
        if needle and needle in text:
            center = bounds_center(node.get("bounds", ""))
            if center:
                pointer("tap", center[0], center[1], label="tap " + str(query)[:30])
                ctl("input tap " + str(center[0]) + " " + str(center[1]))
                return "Tapped " + str(query) + " at " + str(center[0]) + "," + str(center[1])
    return "No node matched " + str(query)


def screenshot():
    ctl("screencap -p " + json.dumps(SHOT_HOST))
    try:
        size = os.path.getsize(SHOT_GUEST)
    except Exception as error:
        return None, "Screenshot failed: " + str(error)
    if size > MAX_SHOT:
        return None, "Screenshot too large: " + str(size)
    with open(SHOT_GUEST, "rb") as handle:
        data = base64.b64encode(handle.read()).decode("ascii")
    return data, "Screenshot " + str(size) + " bytes"


TOOLS = [
    {"name": "android_screen",
     "description": "Return the phone screen size, density and rotation.",
     "inputSchema": {"type": "object", "properties": {}}},
    {"name": "android_ui",
     "description": "Dump the current UI hierarchy as compact text with bounds. Use before tapping. Each line has text, desc, id, class, clickable and bounds.",
     "inputSchema": {"type": "object", "properties": {}}},
    {"name": "android_tap",
     "description": "Tap a screen coordinate. A live cursor shows the tap on the phone.",
     "inputSchema": {"type": "object", "properties": {"x": {"type": "integer"}, "y": {"type": "integer"}}, "required": ["x", "y"]}},
    {"name": "android_tap_text",
     "description": "Find a visible text or content description and tap its center. Best for buttons and list items.",
     "inputSchema": {"type": "object", "properties": {"text": {"type": "string"}}, "required": ["text"]}},
    {"name": "android_swipe",
     "description": "Swipe from one coordinate to another with a duration in milliseconds.",
     "inputSchema": {"type": "object", "properties": {"x": {"type": "integer"}, "y": {"type": "integer"}, "x2": {"type": "integer"}, "y2": {"type": "integer"}, "duration": {"type": "integer"}}, "required": ["x", "y", "x2", "y2"]}},
    {"name": "android_key",
     "description": "Send an Android key event. Accepts names such as BACK, HOME, ENTER, APP_SWITCH or a numeric keycode.",
     "inputSchema": {"type": "object", "properties": {"key": {"type": "string"}}, "required": ["key"]}},
    {"name": "android_type",
     "description": "Type text into the focused field through the Android input command. Best with ASCII text.",
     "inputSchema": {"type": "object", "properties": {"text": {"type": "string"}}, "required": ["text"]}},
    {"name": "android_open",
     "description": "Open an app package, an app name fragment or a URL. Examples: com.google.android.youtube, youtube, https://example.com.",
     "inputSchema": {"type": "object", "properties": {"target": {"type": "string"}}, "required": ["target"]}},
    {"name": "android_apps",
     "description": "List installed third-party package names.",
     "inputSchema": {"type": "object", "properties": {}}},
    {"name": "android_screenshot",
     "description": "Capture the current screen and return it as an image plus the saved path.",
     "inputSchema": {"type": "object", "properties": {}}},
    {"name": "android_cursor",
     "description": "Move the live cursor to a coordinate without tapping. Use it to show where you are looking before acting.",
     "inputSchema": {"type": "object", "properties": {"x": {"type": "integer"}, "y": {"type": "integer"}, "label": {"type": "string"}}, "required": ["x", "y"]}},
    {"name": "android_shell",
     "description": "Run an advanced Android shell command through Shizuku. Prefer the specific tools above.",
     "inputSchema": {"type": "object", "properties": {"command": {"type": "string"}}, "required": ["command"]}},
    {"name": "android_wait",
     "description": "Wait for the screen to settle. Max 10000 ms.",
     "inputSchema": {"type": "object", "properties": {"ms": {"type": "integer"}}, "required": ["ms"]}}
]


def text_result(text):
    return {"content": [{"type": "text", "text": text or "Done"}], "isError": False}


def call_tool(name, args):
    if name == "android_screen":
        code, out = ctl("wm size; wm density; dumpsys input | grep -m1 SurfaceOrientation")
        return text_result(out or "Unknown screen")
    if name == "android_ui":
        return text_result(compact_ui(dump_ui()))
    if name == "android_tap":
        x = int(args.get("x", 0))
        y = int(args.get("y", 0))
        pointer("tap", x, y)
        code, out = ctl("input tap " + str(x) + " " + str(y))
        return text_result(out or ("Tapped " + str(x) + "," + str(y)))
    if name == "android_tap_text":
        return text_result(tap_text(args.get("text", "")))
    if name == "android_swipe":
        x = int(args.get("x", 0))
        y = int(args.get("y", 0))
        x2 = int(args.get("x2", 0))
        y2 = int(args.get("y2", 0))
        duration = int(args.get("duration", 300))
        pointer("swipe", x, y, x2, y2, label="swipe", duration=duration)
        code, out = ctl("input swipe " + str(x) + " " + str(y) + " " + str(x2) + " " + str(y2) + " " + str(duration))
        return text_result(out or "Swiped")
    if name == "android_key":
        key = str(args.get("key", "")).strip()
        pointer("key", label="key " + key)
        code, out = ctl("input keyevent " + json.dumps(key))
        return text_result(out or ("Key " + key))
    if name == "android_type":
        return text_result(input_text(args.get("text", "")))
    if name == "android_open":
        return text_result(open_target(args.get("target", "")))
    if name == "android_apps":
        names = packages()
        return text_result("\n".join(names) if names else "No third-party packages")
    if name == "android_screenshot":
        data, message = screenshot()
        content = [{"type": "text", "text": message}]
        if data:
            content.append({"type": "image", "data": data, "mimeType": "image/png"})
        return {"content": content, "isError": data is None}
    if name == "android_cursor":
        x = int(args.get("x", last["x"]))
        y = int(args.get("y", last["y"]))
        pointer("cursor", x, y, label=args.get("label", "cursor"))
        return text_result("Cursor at " + str(x) + "," + str(y))
    if name == "android_shell":
        code, out = ctl(str(args.get("command", "")), timeout=60)
        return text_result(out[:6000] if out else "Exit " + str(code))
    if name == "android_wait":
        ms = max(0, min(10000, int(args.get("ms", 500))))
        pointer("wait", label="wait " + str(ms) + "ms")
        time.sleep(ms / 1000.0)
        return text_result("Waited " + str(ms) + "ms")
    return {"content": [{"type": "text", "text": "Unknown tool: " + str(name)}], "isError": True}


def send(message):
    sys.stdout.write(json.dumps(message, ensure_ascii=False) + "\n")
    sys.stdout.flush()


def main():
    log("mcp started")
    for raw in sys.stdin:
        raw = raw.strip()
        if not raw:
            continue
        try:
            message = json.loads(raw)
        except Exception as error:
            log("bad json " + str(error))
            continue
        method = message.get("method")
        mid = message.get("id")
        if method == "initialize":
            send({"jsonrpc": "2.0", "id": mid, "result": {
                "protocolVersion": "2024-11-05",
                "capabilities": {"tools": {}},
                "serverInfo": {"name": "attc-android", "version": "1.0.0"}}})
        elif method == "notifications/initialized":
            pass
        elif method == "tools/list":
            send({"jsonrpc": "2.0", "id": mid, "result": {"tools": TOOLS}})
        elif method == "tools/call":
            params = message.get("params") or {}
            name = params.get("name")
            args = params.get("arguments") or {}
            try:
                result = call_tool(name, args)
            except Exception as error:
                result = {"content": [{"type": "text", "text": "Error: " + str(error)}], "isError": True}
            send({"jsonrpc": "2.0", "id": mid, "result": result})
        elif method == "ping":
            send({"jsonrpc": "2.0", "id": mid, "result": {}})
        elif mid is not None:
            send({"jsonrpc": "2.0", "id": mid, "error": {"code": -32601, "message": "Method not found: " + str(method)}})


if __name__ == "__main__":
    main()
