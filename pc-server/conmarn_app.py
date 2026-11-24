# -*- coding: utf-8 -*-
"""ConMarn 电脑端 App。

结构
----
    pywebview 窗口 (WebView2)
        │  http://127.0.0.1:9611/
        ▼
    本文件内置的界面服务 (只绑回环)
        │  TCP 127.0.0.1:9530,一行命令一行 JSON
        ▼
    server.py (后台服务,开机自启)  ──  app_bridge.py 负责应答

边界
----
* 本程序不配对、不做密码学、不读写 secret.json / master.json;
* 所有动作都复用服务端已有函数(媒体开关、文件互传、解除配对);
* 关闭 App 不影响服务;服务未运行时可在界面里启动。

安全
----
* 界面服务只监听 127.0.0.1,局域网不可达;
* 所有写操作都是 POST,且必须带 ``X-ConMarn: 1`` 自定义头 ——
  普通网页发不出这种请求(会先触发被拒绝的 CORS 预检),用于阻断本机网页的 CSRF。

启动
----
    pythonw conmarn_app.py            # 正常窗口
    python conmarn_app.py --serve-only  # 只跑界面服务(调试/截图用)
"""

import argparse
import base64
import ctypes
import json
import os
import socket
import subprocess
import sys
import threading
import time
import urllib.parse
import webbrowser
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer

APP_NAME = "ConMarn"
APP_VERSION = "1.0.0"
HTTP_PORT = 9611
#: 正常固定为 9530;仅调试时可用环境变量指向另一端口(避免打扰正在运行的服务)。
BRIDGE_PORT = int(os.environ.get("CONMARN_BRIDGE_PORT") or 9530)
BASE_DIR = os.path.dirname(os.path.abspath(__file__))
WEB_DIR = os.path.join(BASE_DIR, "app_web")
ICON_PATH = os.path.join(BASE_DIR, "conmarn.ico")
SERVER_PY = os.path.join(BASE_DIR, "server.py")
_MAX_REPLY = 32 * 1024 * 1024


# ---------------------------------------------------------------------------
# 与服务端的本地桥接(127.0.0.1:9530)
# ---------------------------------------------------------------------------
def bridge(cmd, timeout=5.0):
    """发一条本地命令,返回解析后的 JSON。

    失败时返回 ``{"ok": False, "err": "service_down" | "bridge_missing" | ...}``,
    界面据此显示「服务未运行」,不会抛异常。
    """
    try:
        with socket.create_connection(("127.0.0.1", BRIDGE_PORT), timeout=timeout) as s:
            s.sendall(cmd.encode("utf-8") + b"\n")
            buf = b""
            while b"\n" not in buf and len(buf) < _MAX_REPLY:
                chunk = s.recv(65536)
                if not chunk:
                    break
                buf += chunk
    except ConnectionRefusedError:
        return {"ok": False, "err": "service_down"}
    except OSError as exc:
        return {"ok": False, "err": "service_down", "detail": str(exc)}
    line = buf.split(b"\n", 1)[0].strip()
    if not line:
        # 端口通、但没有应答:跑的是旧版服务(没有 app_bridge)
        return {"ok": False, "err": "bridge_missing"}
    try:
        return json.loads(line.decode("utf-8", "replace"))
    except Exception:
        return {"ok": False, "err": "bad_reply"}


# ---------------------------------------------------------------------------
# 辅助
# ---------------------------------------------------------------------------
def _b64(text):
    return base64.b64encode(text.encode("utf-8")).decode("ascii")


def _pythonw_path():
    exe = sys.executable or "pythonw.exe"
    if exe.lower().endswith("python.exe"):
        exe = exe[:-len("python.exe")] + "pythonw.exe"
    return exe


def _start_service():
    """以 --background 启动服务。若当前会话不是管理员,服务会自己弹一次 UAC。"""
    if not getattr(sys, "frozen", False) and not os.path.exists(SERVER_PY):
        return {"ok": False, "err": "找不到 server.py"}
    try:
        flags = 0
        if os.name == "nt":
            flags = 0x00000008 | 0x08000000  # DETACHED_PROCESS | CREATE_NO_WINDOW
        # ★★ 打包后没有 pythonw、也没有 server.py 这个文件：服务和窗口是**同一个 exe**
        #   的两个入口（见文件末尾的 __main__）。所以这里重启自己，带上 --background，
        #   那一支会直接进 server.main()，不会建窗口。
        if getattr(sys, "frozen", False):
            launcher = [sys.executable, "--background"]
        else:
            launcher = [_pythonw_path(), SERVER_PY, "--background"]
        subprocess.Popen(
            launcher,
            cwd=BASE_DIR,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            creationflags=flags,
            close_fds=True,
        )
    except Exception as exc:
        return {"ok": False, "err": "%s: %s" % (type(exc).__name__, exc)}

    deadline = time.time() + 8.0
    while time.time() < deadline:
        time.sleep(0.25)
        if bridge("SVC PING", timeout=0.8).get("ok"):
            return {"ok": True}
    return {
        "ok": False,
        "err": "服务未在 8 秒内就绪。若系统弹出 UAC 确认框,请在确认后点击重试。",
        "pending": True,
    }


def _transfer_dir():
    r = bridge("STATUS", timeout=3.0)
    return r.get("transfer_dir") or ""


def _unique_path(path):
    if not os.path.exists(path):
        return path
    root, ext = os.path.splitext(path)
    for i in range(1, 1000):
        cand = "%s (%d)%s" % (root, i, ext)
        if not os.path.exists(cand):
            return cand
    return path


def _set_app_id():
    """让任务栏把这个窗口归到 ConMarn 名下,图标用 conmarn.ico 而不是 python.exe 的。"""
    try:
        ctypes.windll.shell32.SetCurrentProcessExplicitAppUserModelID("ConMarn.Desktop")
    except Exception:
        pass


def _message_box(title, text):
    try:
        ctypes.windll.user32.MessageBoxW(None, text, title, 0x40)
        return
    except Exception:
        pass
    print("%s: %s" % (title, text))


# ---------------------------------------------------------------------------
# 界面服务
# ---------------------------------------------------------------------------
class AppHandler(SimpleHTTPRequestHandler):
    server_version = "ConMarnApp/" + APP_VERSION

    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=WEB_DIR, **kwargs)

    def log_message(self, fmt, *args):  # pythonw 下没有控制台,不刷屏
        pass

    # ---- 回复 ----
    def _json(self, obj, code=200):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        self.wfile.write(body)

    def _body(self, limit=8 * 1024 * 1024):
        try:
            n = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            n = 0
        if n <= 0:
            return b""
        return self.rfile.read(min(n, limit))

    def _body_json(self):
        try:
            return json.loads(self._body(2 * 1024 * 1024).decode("utf-8") or "{}")
        except Exception:
            return {}

    # ---- GET ----
    def do_GET(self):
        url = urllib.parse.urlsplit(self.path)
        if url.path.startswith("/api/"):
            return self._api_get(url.path, urllib.parse.parse_qs(url.query))
        return super().do_GET()

    def _api_get(self, path, qs):
        if path == "/api/status":
            return self._json(bridge("STATUS"))
        if path == "/api/log":
            try:
                n = max(1, min(int((qs.get("n") or ["160"])[0]), 500))
            except ValueError:
                n = 160
            return self._json(bridge("LOG %d" % n))
        if path == "/api/autostart":
            return self._json(bridge("AUTOSTART GET", timeout=20.0))
        if path == "/api/level":
            return self._json(bridge("LEVEL", timeout=2.0))
        if path == "/api/files":
            scope = (qs.get("scope") or ["local"])[0].lower()
            if scope == "phone":
                return self._json(bridge("FILES PHONE"))
            rel_b64 = (qs.get("path") or [""])[0]
            cmd = "FILES LOCAL" + ((" " + rel_b64) if rel_b64 else "")
            return self._json(bridge(cmd))
        if path == "/api/frame.jpg":
            return self._frame_jpeg()
        if path == "/api/service/ping":
            return self._json(bridge("SVC PING", timeout=2.0))
        return self._json({"ok": False, "err": "not found"}, 404)

    def _frame_jpeg(self):
        r = bridge("FRAME", timeout=5.0)
        jpeg = r.get("jpeg") if isinstance(r, dict) else None
        if not r.get("ok") or not jpeg:
            self.send_response(204)
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            return
        try:
            data = base64.b64decode(jpeg)
        except Exception:
            self.send_response(204)
            self.end_headers()
            return
        self.send_response(200)
        self.send_header("Content-Type", "image/jpeg")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        self.wfile.write(data)

    # ---- POST(一律要求自定义头,阻断本机网页的 CSRF) ----
    def do_POST(self):
        if self.headers.get("X-ConMarn") != "1":
            return self._json({"ok": False, "err": "forbidden"}, 403)
        url = urllib.parse.urlsplit(self.path)
        path = url.path
        try:
            if path == "/api/media":
                return self._post_media()
            if path == "/api/autostart":
                body = self._body_json()
                return self._json(bridge(
                    "AUTOSTART ON" if body.get("on") else "AUTOSTART OFF", timeout=25.0))
            if path == "/api/files/refresh":
                return self._json(bridge("FILES REFRESH"))
            if path == "/api/files/open":
                return self._json(bridge("FILES OPEN"))
            if path == "/api/files/pull":
                body = self._body_json()
                name = str(body.get("name") or "")
                if not name:
                    return self._json({"ok": False, "err": "缺少文件名"}, 400)
                return self._json(bridge("FILES PULL " + _b64(name)))
            if path == "/api/files/send":
                body = self._body_json()
                name = str(body.get("name") or "")
                if not name:
                    return self._json({"ok": False, "err": "缺少文件名"}, 400)
                return self._json(bridge("FILES SENDNAME " + _b64(name)))
            if path == "/api/files/upload":
                return self._post_upload()
            if path == "/api/pair/unlock":
                body = self._body_json()
                pin = str(body.get("pin") or "")
                return self._json(bridge("PAIR UNLOCK " + pin, timeout=20.0))
            if path == "/api/pair/setpin":
                body = self._body_json()
                pin = str(body.get("pin") or "")
                return self._json(bridge("PAIR SETPIN " + pin, timeout=20.0))
            if path == "/api/pair/forget":
                body = self._body_json()
                if body.get("confirm") != "FORGET":
                    return self._json({"ok": False, "err": "需要确认"}, 400)
                return self._json(bridge("PAIR FORGET CONFIRM"))
            if path == "/api/open":
                body = self._body_json()
                target = str(body.get("target") or "").upper()
                if target not in ("MEDIA", "TRANSFER", "TRANSFER_DIR"):
                    return self._json({"ok": False, "err": "未知目标"}, 400)
                return self._json(bridge("OPEN " + target))
            if path == "/api/service/start":
                return self._json(_start_service())
            return self._json({"ok": False, "err": "not found"}, 404)
        except Exception as exc:  # noqa: BLE001 - 单个请求失败不能拖垮界面
            return self._json({"ok": False, "err": "%s: %s" % (type(exc).__name__, exc)}, 500)

    def _post_media(self):
        body = self._body_json()
        dev = str(body.get("dev") or "").upper()
        on = bool(body.get("on"))
        if dev not in ("CAM", "MIC"):
            return self._json({"ok": False, "err": "未知设备"}, 400)
        return self._json(bridge("MEDIA %s %d" % (dev, 1 if on else 0)))

    def _post_upload(self):
        raw_name = self.headers.get("X-Filename") or ""
        try:
            name = base64.b64decode(raw_name).decode("utf-8") if raw_name else ""
        except Exception:
            name = ""
        name = os.path.basename(name.replace("\\", "/")).strip()
        if not name or name in (".", ".."):
            return self._json({"ok": False, "err": "缺少文件名"}, 400)
        td = _transfer_dir()
        if not td or not os.path.isdir(td):
            return self._json({"ok": False, "err": "服务未运行或中转目录不可用"}, 503)
        data = self._body(512 * 1024 * 1024)
        dest = _unique_path(os.path.join(td, name))
        tmp = dest + ".part"
        try:
            with open(tmp, "wb") as f:
                f.write(data)
            os.replace(tmp, dest)
        except Exception as exc:
            try:
                if os.path.exists(tmp):
                    os.remove(tmp)
            except Exception:
                pass
            return self._json({"ok": False, "err": "%s: %s" % (type(exc).__name__, exc)}, 500)
        return self._json({"ok": True, "name": os.path.basename(dest), "bytes": len(data)})


# ---------------------------------------------------------------------------
# 入口
# ---------------------------------------------------------------------------
def main():
    ap = argparse.ArgumentParser(description="ConMarn 电脑端 App")
    ap.add_argument("--port", type=int, default=HTTP_PORT, help="界面服务端口(仅回环)")
    ap.add_argument("--serve-only", action="store_true", help="只运行界面服务,不建窗口")
    ap.add_argument("--demo", action="store_true", help="以演示模式启动(界面显示示例数据)")
    ap.add_argument("--version", action="store_true")
    args = ap.parse_args()

    if args.version:
        print(APP_NAME, APP_VERSION)
        return 0

    if not os.path.isdir(WEB_DIR):
        _message_box(APP_NAME, "缺少界面文件目录:\n%s" % WEB_DIR)
        return 1

    try:
        httpd = ThreadingHTTPServer(("127.0.0.1", args.port), AppHandler)
    except OSError:
        _message_box(APP_NAME,
                     "ConMarn 已在运行,或端口 %d 被占用。\n\n"
                     "请检查任务栏是否已有 ConMarn 窗口。" % args.port)
        return 1

    httpd.daemon_threads = True
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    url = "http://127.0.0.1:%d/" % args.port
    if args.demo:
        url += "?demo=1"

    if args.serve_only:
        print("界面服务:", url)
        try:
            while True:
                time.sleep(3600)
        except KeyboardInterrupt:
            return 0

    try:
        import webview
    except Exception as exc:
        webbrowser.open(url)
        _message_box(APP_NAME, "无法加载 pywebview(%s),已在浏览器中打开界面。" % exc)
        return 0

    _set_app_id()

    try:
        webview.create_window(
            APP_NAME,
            url,
            width=1120,
            height=760,
            min_size=(960, 640),
            background_color="#08080A",
        )
        webview.start(icon=ICON_PATH if os.path.isfile(ICON_PATH) else None)
    except Exception as exc:
        webbrowser.open(url)
        _message_box(APP_NAME, "窗口创建失败(%s),已在浏览器中打开界面。" % exc)
    return 0


if __name__ == "__main__":
    # ★★ 打包版的两个入口：带 --background 的那一支是**后台服务**（原来是
    #   `pythonw server.py --background`），其余是**窗口**。
    #   必须在最前面分流：server.py 在 import 的时候就会改 sys.stderr、读配置，
    #   窗口这一支不能沾上。
    if getattr(sys, "frozen", False) and "--background" in sys.argv:
        import server
        server.main()
    else:
        sys.exit(main())
