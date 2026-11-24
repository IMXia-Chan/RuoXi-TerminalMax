# -*- coding: utf-8 -*-
"""ConMarn 电脑端 App 的本地桥接层。

职责边界
--------
App(conmarn_app.py)与电脑端服务(server.py)是两个进程:

  * 服务端照旧运行(开机自启 = ``server.py --background``),配对、TOTP、
    DPAPI 存储、文件协议一行不改;
  * App 通过本模块经 ``127.0.0.1:9530`` 读取状态、发起**已有**动作;
  * 本模块不做任何密码学、不写 secret.json、不假设 tkinter 可用。

命令协议
--------
一条 TCP 连接 = 一条命令 + 一条 JSON 回复(UTF-8,以换行结尾)。
``SHOW`` 保持旧行为(由调用方处理),其余命令见 :func:`handle_line`。

三条硬约束(改动前先读)
----------------------
1. **不碰 tkinter**:所有函数都运行在 IPC 线程。需要触达界面时只能往
   ``gui_queue`` 投事件。
2. **不复制业务逻辑**:状态读 server 的现成全局量/函数,动作调现成函数。
3. **不 import server**:server.py 以 ``__main__`` 运行,再 import 一次会
   得到第二份全局状态(空的连接表、没有配对码)。必须由 server.py 调
   :func:`bind` 把正在运行的那个模块对象递进来。
"""

import base64
import json
import os
import subprocess
import threading
import time

#: 桥接协议版本。App 与本文件的字段约定变化时递增。
BRIDGE_VERSION = 1

#: 最近一次桥接命令在多少秒内算「App 在线」。
#: 服务端据此决定是否让出配对/媒体弹窗(见 server.py 的 BackgroundHost 与 _MediaPanelHost)。
ATTACH_TTL = 6.0

_MAX_EVENTS = 80
_MAX_LOG_LINES = 500
_MAX_LOG_BYTES = 256 * 1024

_S = None                     # 由 bind() 注入的、正在运行的 server 模块
_lock = threading.Lock()
_started_at = time.time()
_last_cmd_at = 0.0

#: 旁听 gui_queue 得到的最近状态(不改变事件的生产与消费)
_ev = {
    "last_addr": None,
    "last_conn_at": 0.0,
    "phl": None,              # {"payload": dict, "at": float}
    "setup": None,            # {"qr": str, "at": float}
    "notes": [],              # [{"kind","msg","at"}]
}


# ---------------------------------------------------------------------------
# 绑定与事件旁听
# ---------------------------------------------------------------------------
def bind(server_module):
    """把正在运行的 server 模块交给桥接层。可重复调用(幂等)。"""
    global _S
    _S = server_module
    try:
        _tap_gui_queue(getattr(server_module, "gui_queue", None))
    except Exception:
        pass


def is_attached():
    """App 是否在线(最近 ATTACH_TTL 秒内发过命令)。供 server 判断是否让出弹窗。"""
    with _lock:
        return (time.time() - _last_cmd_at) < ATTACH_TTL


def _tap_gui_queue(q):
    """包一层 put:只旁听事件,不改事件的消费方。

    直接替换实例上的 put 方法即可 —— server 里所有生产点都是
    ``gui_queue.put(...)`` 的全局名查找,消费方(Queue.get)完全不受影响。
    """
    if q is None or getattr(q, "_conmarn_tapped", False):
        return
    orig_put = q.put

    def put(item, *args, **kwargs):
        try:
            _note_event(item)
        except Exception:
            pass
        return orig_put(item, *args, **kwargs)

    q.put = put
    q._conmarn_tapped = True


def _note_event(item):
    if not isinstance(item, dict):
        return
    kind = item.get("kind")
    now = time.time()
    with _lock:
        if kind == "connected":
            _ev["last_addr"] = item.get("addr")
            _ev["last_conn_at"] = now
        elif kind == "setup":
            _ev["setup"] = {"qr": item.get("qr"), "at": now}
        elif kind == "phl_result":
            _ev["phl"] = {"payload": item.get("payload"), "at": now}
        elif kind in ("file_note", "upload_done"):
            msg = item.get("msg")
            if not msg and kind == "upload_done":
                msg = "手机上传完成: %s" % (item.get("name") or "")
            _ev["notes"].append({"kind": kind, "msg": msg or "", "at": now})
            if len(_ev["notes"]) > _MAX_EVENTS:
                del _ev["notes"][:-_MAX_EVENTS]


# ---------------------------------------------------------------------------
# 命令分发
# ---------------------------------------------------------------------------
def handle_line(line):
    """处理一条本地命令,返回一行 JSON 字符串;空命令返回 None。

    任何异常都转成 ``{"ok": false, "err": ...}``,绝不向上抛 —— 调用方是
    常驻监听线程,抛一次就会让整个本地命令口失联。
    """
    global _last_cmd_at
    line = (line or "").strip()
    if not line:
        return None
    _last_cmd_at = time.time()
    parts = line.split(" ", 2)
    cmd = parts[0].upper()
    try:
        if cmd == "STATUS":
            return _json(_status())
        if cmd == "LOG":
            n = _to_int(parts[1], 120) if len(parts) > 1 else 120
            return _json(_log(n))
        if cmd == "FRAME":
            return _json(_frame())
        if cmd == "LEVEL":
            return _json(_level())
        if cmd == "MEDIA":
            return _json(_media(parts))
        if cmd == "AUTOSTART":
            return _json(_autostart(parts))
        if cmd == "FILES":
            return _json(_files(parts))
        if cmd == "PAIR":
            return _json(_pair(parts))
        if cmd == "OPEN":
            return _json(_open(parts))
        if cmd == "SVC":
            return _json(_svc(parts))
        return _json({"ok": False, "err": "unknown command: %s" % cmd})
    except Exception as exc:  # noqa: BLE001 - 监听线程必须活下来
        return _json({"ok": False, "err": "%s: %s" % (type(exc).__name__, exc)})


# ---------------------------------------------------------------------------
# 只读状态
# ---------------------------------------------------------------------------
def _status():
    s = _S
    if s is None:
        return {"ok": False, "err": "bridge not bound"}

    now = time.time()
    pin, pin_left = None, 0
    try:
        with s._pin_lock:
            if s._current_pin and now <= s._current_pin_expiry:
                pin = s._current_pin
                pin_left = int(max(0, s._current_pin_expiry - now))
    except Exception:
        pass

    try:
        with s._ctrl_registry_lock:
            connected = len(s._ctrl_registry)
    except Exception:
        connected = 0

    cam = mic = False
    level = 0.0
    media = getattr(s, "media", None)
    if media is not None:
        try:
            cam, mic = media.media_status()
        except Exception:
            pass
        try:
            level = float(media.audio_level())
        except Exception:
            pass

    try:
        paired = s.get_secret() is not None
    except Exception:
        paired = False

    # 有没有设置过登录 PIN。App 的配对页据此决定显示「设置」还是「输入」。
    try:
        master = bool(s.master_exists())
    except Exception:
        master = False

    try:
        transfer_dir = s._file_base()
    except Exception:
        transfer_dir = ""

    try:
        lan_ip = s.get_lan_ip()
    except Exception:
        lan_ip = "127.0.0.1"

    try:
        elevated = bool(s._is_admin())
    except Exception:
        elevated = False

    with _lock:
        last_addr = _ev["last_addr"]
        last_conn_at = _ev["last_conn_at"]
        notes = list(_ev["notes"][-8:])
        setup = _ev["setup"]

    setup_pending = bool(setup and (now - setup.get("at", 0)) < 300)
    return {
        "ok": True,
        "bridge": BRIDGE_VERSION,
        "paired": paired,
        "master": master,
        "connected": connected,
        "last_addr": last_addr,
        "last_conn_at": last_conn_at,
        "pin": pin,
        "pin_left": pin_left,
        "pin_ttl": getattr(s, "PIN_TTL", 120),
        "cam": bool(cam),
        "mic": bool(mic),
        "level": max(0.0, min(1.0, level)),
        "port": getattr(s, "LISTEN_PORT_DEFAULT", 9527),
        "lan_ip": lan_ip,
        "autostart": _autostart_enabled(),
        "transfer_dir": transfer_dir,
        "uptime": int(now - _started_at),
        "elevated": elevated,
        "setup_pending": setup_pending,
        "notes": notes,
    }


def _log(n):
    s = _S
    n = max(1, min(int(n or 120), _MAX_LOG_LINES))
    path = getattr(s, "LOG_PATH", None) if s is not None else None
    if not path or not os.path.exists(path):
        return {"ok": True, "lines": [], "path": path}
    try:
        size = os.path.getsize(path)
        back = min(size, _MAX_LOG_BYTES)
        with open(path, "rb") as f:
            f.seek(size - back)
            data = f.read(back)
        lines = data.decode("utf-8", "replace").splitlines()
        if back < size and lines:
            lines = lines[1:]  # 第一行可能从半个字符开始
        return {"ok": True, "lines": lines[-n:], "path": path}
    except Exception as exc:
        return {"ok": False, "err": "%s: %s" % (type(exc).__name__, exc)}


def _frame():
    s = _S
    media = getattr(s, "media", None) if s is not None else None
    if media is None:
        return {"ok": False, "err": "media module disabled"}
    try:
        serial, jpeg = media.latest_video()
    except Exception as exc:
        return {"ok": False, "err": "%s: %s" % (type(exc).__name__, exc)}
    if not jpeg:
        return {"ok": True, "serial": serial, "jpeg": None, "bytes": 0}
    return {
        "ok": True,
        "serial": serial,
        "jpeg": base64.b64encode(jpeg).decode("ascii"),
        "bytes": len(jpeg),
    }


def _level():
    s = _S
    media = getattr(s, "media", None) if s is not None else None
    cam = mic = False
    level = 0.0
    if media is not None:
        try:
            cam, mic = media.media_status()
        except Exception:
            pass
        try:
            level = float(media.audio_level())
        except Exception:
            pass
    return {
        "ok": True,
        "cam": bool(cam),
        "mic": bool(mic),
        "level": max(0.0, min(1.0, level)),
    }


# ---------------------------------------------------------------------------
# 动作
# ---------------------------------------------------------------------------
def _media(parts):
    """``MEDIA CAM|MIC 0|1`` —— 与状态悬浮窗的两个图标走同一条命令。"""
    s = _S
    if s is None:
        return {"ok": False, "err": "bridge not bound"}
    if len(parts) < 3:
        return {"ok": False, "err": "usage: MEDIA CAM|MIC 0|1"}
    dev = parts[1].strip().upper()
    on = parts[2].strip()
    if dev not in ("CAM", "MIC") or on not in ("0", "1"):
        return {"ok": False, "err": "usage: MEDIA CAM|MIC 0|1"}
    sent = int(s.send_ctrl_line("CMD %s %s" % (dev, on)) or 0)
    return {"ok": True, "sent": sent, "delivered": sent > 0}


#: 这台机器上实际生效的开机自启是计划任务(登录后 20 秒、最高权限运行)。
#: 代码里原有的「启动」文件夹快捷方式只在没有该任务时作为退路。
AUTOSTART_TASK = "ConMarn-PCServer"

_autostart_cache = {"at": 0.0, "value": False, "source": "none", "detail": {}}
_autostart_lock = threading.Lock()


def _autostart(parts):
    arg = parts[1].strip().upper() if len(parts) > 1 else "GET"
    if arg in ("GET", ""):
        return _autostart_state(force=True)
    if arg not in ("ON", "OFF"):
        return {"ok": False, "err": "usage: AUTOSTART GET|ON|OFF"}
    return _set_autostart(arg == "ON")


def _autostart_enabled():
    """给 STATUS 用的快速读数:只读缓存,绝不在这里起 PowerShell。"""
    with _autostart_lock:
        return bool(_autostart_cache["value"])


def _autostart_state(force=False):
    """读开机自启的完整状态:计划任务优先,其次「启动」文件夹快捷方式。

    PowerShell 启动约 0.3~0.6 秒,结果缓存 15 秒。App 只在设置页和切换后
    主动刷新,STATUS 不会触发它。
    """
    s = _S
    if s is None:
        return {"ok": False, "err": "bridge not bound"}
    now = time.time()
    with _autostart_lock:
        if not force and (now - _autostart_cache["at"]) < 15.0:
            cached = dict(_autostart_cache["detail"])
            cached["ok"] = True
            return cached

    task = {"exists": False, "enabled": False, "state": ""}
    err = None
    try:
        ps = (
            "[Console]::OutputEncoding=[Text.Encoding]::UTF8;"
            "$t=Get-ScheduledTask -TaskName '%s' -ErrorAction SilentlyContinue;"
            "if($t){Write-Output ('TASK|'+$t.Settings.Enabled+'|'+$t.State)}"
            "else{Write-Output 'NONE'}" % AUTOSTART_TASK
        )
        out = subprocess.run(
            ["powershell", "-NoProfile", "-NonInteractive", "-Command", ps],
            capture_output=True, text=True, encoding="utf-8", errors="replace",
            timeout=15, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
        )
        line = (out.stdout or "").strip().splitlines()
        line = line[0].strip() if line else ""
        if line.startswith("TASK|"):
            _, enabled, state = (line.split("|", 2) + ["", ""])[:3]
            task = {
                "exists": True,
                "enabled": enabled.strip().lower() in ("true", "1"),
                "state": state.strip(),
            }
        elif line != "NONE":
            err = (out.stderr or out.stdout or "无法读取计划任务").strip()[:300]
    except Exception as exc:
        err = "%s: %s" % (type(exc).__name__, exc)

    try:
        lnk = os.path.exists(s._autostart_lnk_path())
    except Exception:
        lnk = False

    if task["exists"]:
        enabled, source = task["enabled"], "task"
    elif lnk:
        enabled, source = True, "lnk"
    else:
        enabled, source = False, "none"

    detail = {
        "ok": True,
        "enabled": enabled,
        "source": source,
        "task": task,
        "task_name": AUTOSTART_TASK,
        "lnk": lnk,
    }
    if err:
        detail["err"] = err
    with _autostart_lock:
        _autostart_cache.update({"at": now, "value": enabled,
                                 "source": source, "detail": detail})
    return detail


def _set_autostart(on):
    """开关开机自启。有计划任务就改计划任务,没有才增删启动快捷方式。

    注意语义:这里只影响**下次登录**。当前正在运行的服务不受影响。
    """
    s = _S
    if s is None:
        return {"ok": False, "err": "bridge not bound"}

    state = _autostart_state(force=True)
    if state.get("task", {}).get("exists"):
        verb = "Enable-ScheduledTask" if on else "Disable-ScheduledTask"
        try:
            ps = (
                "[Console]::OutputEncoding=[Text.Encoding]::UTF8;"
                "try{%s -TaskName '%s' -ErrorAction Stop | Out-Null;"
                "Write-Output 'OK'}catch{Write-Output ('ERR '+$_.Exception.Message)}"
                % (verb, AUTOSTART_TASK)
            )
            out = subprocess.run(
                ["powershell", "-NoProfile", "-NonInteractive", "-Command", ps],
                capture_output=True, text=True, encoding="utf-8", errors="replace",
                timeout=20, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
            )
            text = (out.stdout or "").strip()
            ok = text.startswith("OK")
            after = _autostart_state(force=True)
            result = {
                "ok": ok and bool(after.get("enabled")) == bool(on),
                "enabled": bool(after.get("enabled")),
                "source": "task",
                "task": after.get("task"),
            }
            if not result["ok"]:
                result["err"] = (text or out.stderr or "操作计划任务失败").strip()[:300]
            return result
        except Exception as exc:
            return {"ok": False, "err": "%s: %s" % (type(exc).__name__, exc)}

    try:
        lnk = s._autostart_lnk_path()
        if not on:
            if os.path.exists(lnk):
                os.remove(lnk)
            after = _autostart_state(force=True)
            return {"ok": not after.get("enabled"), "enabled": bool(after.get("enabled")),
                    "source": after.get("source")}
        exe = s._pythonw_path()
        script = os.path.abspath(getattr(s, "__file__", ""))
        if not os.path.exists(exe):
            return {"ok": False, "err": "找不到 pythonw: %s" % exe}
        if not script or not os.path.exists(script):
            return {"ok": False, "err": "找不到 server.py"}
        ps = (
            "$ws=New-Object -ComObject WScript.Shell;"
            "$sc=$ws.CreateShortcut('%s');" % lnk
            + "$sc.TargetPath='%s';" % exe
            + "$sc.Arguments='\"%s\" --background';" % script
            + "$sc.WorkingDirectory='%s';" % os.path.dirname(script)
            + "$sc.WindowStyle=7;"
            + "$sc.Save();"
            + "Write-Output 'created'"
        )
        subprocess.run(
            ["powershell", "-NoProfile", "-NonInteractive", "-Command", ps],
            capture_output=True, text=True, timeout=30,
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
        )
        after = _autostart_state(force=True)
        return {"ok": os.path.exists(lnk), "enabled": bool(after.get("enabled")),
                "source": after.get("source"), "lnk": lnk}
    except Exception as exc:
        return {"ok": False, "err": "%s: %s" % (type(exc).__name__, exc)}


def _files(parts):
    """文件互传:列出/刷新/取回/发送/打开,全部复用 server 的现成函数。"""
    s = _S
    if s is None:
        return {"ok": False, "err": "bridge not bound"}
    sub = parts[1].strip().upper() if len(parts) > 1 else "LOCAL"

    if sub == "LOCAL":
        rel = _b64d(parts[2]) if len(parts) > 2 else ""
        entries = s._list_entries(rel)
        if entries is None:
            return {"ok": False, "err": "目录不存在或无法访问", "path": rel}
        return {"ok": True, "path": rel, "entries": entries}

    if sub == "PHONE":
        with _lock:
            phl = _ev["phl"]
        if not phl:
            return {"ok": True, "entries": None, "path": "", "at": 0}
        payload = phl.get("payload") or {}
        return {
            "ok": True,
            "entries": payload.get("entries"),
            "path": payload.get("path", ""),
            "at": phl.get("at", 0),
        }

    if sub == "REFRESH":
        s.request_phone_list()
        return {"ok": True}

    if sub == "PULL":
        name = _b64d(parts[2]) if len(parts) > 2 else ""
        if not name:
            return {"ok": False, "err": "缺少文件名"}
        s.pull_phone_file(name)
        return {"ok": True}

    if sub in ("SEND", "SENDNAME"):
        if sub == "SEND":
            path = _b64d(parts[2]) if len(parts) > 2 else ""
        else:
            name = _b64d(parts[2]) if len(parts) > 2 else ""
            path = os.path.join(s._file_base(), name) if name else ""
        if not path or not os.path.isfile(path):
            return {"ok": False, "err": "文件不存在"}
        base = os.path.realpath(s._file_base())
        real = os.path.realpath(path)
        if not (real == base or real.startswith(base + os.sep)):
            return {"ok": False, "err": "只允许发送中转目录内的文件"}
        s.push_local_file_to_phone(real)
        return {"ok": True}

    if sub == "OPEN":
        s.open_transfer_dir()
        return {"ok": True}

    return {"ok": False, "err": "unknown FILES subcommand: %s" % sub}


def _pair(parts):
    """配对相关的**只读显示**与两个受限动作(验证登录 PIN、解除配对)。"""
    sub = parts[1].strip().upper() if len(parts) > 1 else "STATUS"
    if sub == "STATUS":
        return _status()
    if sub == "UNLOCK":
        return _unlock(parts[2].strip() if len(parts) > 2 else "", create=False)
    if sub == "SETPIN":
        return _unlock(parts[2].strip() if len(parts) > 2 else "", create=True)
    if sub == "FORGET":
        confirm = parts[2].strip().upper() if len(parts) > 2 else ""
        if confirm != "CONFIRM":
            return {"ok": False, "err": "需要 CONFIRM"}
        _S.forget_pair()
        with _lock:
            _ev["setup"] = None
        return {"ok": True, "paired": False}
    return {"ok": False, "err": "unknown PAIR subcommand: %s" % sub}


def _unlock(pin, create):
    """显示配对二维码前的身份校验。

    后台模式原有的闸门是 tkinter 的 PIN 窗口;App 在线时由 App 复现同一道闸门:
    二维码文本只在 PIN 校验通过后才从本函数返回,服务端本身不额外保存。
    """
    s = _S
    if s is None:
        return {"ok": False, "err": "bridge not bound"}
    if not (len(pin) == 6 and pin.isdigit()):
        return {"ok": False, "err": "登录 PIN 必须是 6 位数字"}
    try:
        if create:
            if s.master_exists():
                return {"ok": False, "err": "已经设置过登录 PIN"}
            s.set_master_pin(pin)
        else:
            if not s.master_exists():
                return {"ok": False, "err": "尚未设置登录 PIN", "need_set": True}
            if not s.verify_master_pin(pin):
                return {"ok": False, "err": "登录 PIN 不正确"}
    except Exception as exc:
        return {"ok": False, "err": "%s: %s" % (type(exc).__name__, exc)}

    with _lock:
        setup = _ev["setup"]
    out = {"ok": True, "qr": None, "png": None}
    if setup:
        qr = setup.get("qr")
        out["qr"] = qr
        try:
            out["png"] = s.make_qr_png_b64(qr)
        except Exception:
            out["png"] = None
    return out


def _open(parts):
    """唤出服务端已有的 tkinter 窗口(只能投事件,不能直接调 tkinter)。"""
    s = _S
    if s is None:
        return {"ok": False, "err": "bridge not bound"}
    target = parts[1].strip().upper() if len(parts) > 1 else ""
    if target == "MEDIA":
        s.gui_queue.put({"kind": "console_show"})
        return {"ok": True}
    if target == "TRANSFER":
        s.gui_queue.put({"kind": "transfer_toggle"})
        return {"ok": True}
    if target == "TRANSFER_DIR":
        s.open_transfer_dir()
        return {"ok": True}
    return {"ok": False, "err": "unknown OPEN target: %s" % target}


def _svc(parts):
    sub = parts[1].strip().upper() if len(parts) > 1 else "PING"
    if sub == "PING":
        return {"ok": True, "bridge": BRIDGE_VERSION}
    return {"ok": False, "err": "unknown SVC subcommand: %s" % sub}


# ---------------------------------------------------------------------------
# 小工具
# ---------------------------------------------------------------------------
def _json(obj):
    return json.dumps(obj, ensure_ascii=False, separators=(",", ":"))


def _b64d(text):
    try:
        return base64.b64decode(text.encode("ascii")).decode("utf-8")
    except Exception:
        return ""


def _to_int(text, default):
    try:
        return int(text)
    except Exception:
        return default
