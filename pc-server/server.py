#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
手机触控板 —— 电脑端服务 (Wi-Fi 方案, 带图形前端)

认证(每次连接都完整重新认证):
  1. 动态配对码:手机连上后,电脑前端/弹窗显示一个 6 位随机码(每次连接都变),
     手机端输入它,用来「选对这台电脑」。
  2. TOTP 动态码:配对码通过后,双方用同一把密钥 + 当前时间各自算出 6 位动态码,
     手机端自动发送,电脑端校验。密钥由电脑生成,首次配对以「二维码」带外传给手机
     (手机扫码,不再手动抄 32 位)。

安全增强:
  - 种子与一次性恢复码都用 Windows DPAPI 加密后落盘(secret.json),不存明文。
  - 一次性恢复码:首次配对时显示一次,手机丢失/卸载后凭它重新配对,用一次即作废。
  - 防暴力破解:连续 3 次 TOTP 码错误 -> 锁定,锁定时长指数翻倍(30s/60s/120s/240s…)。
  - 空闲断开已关闭:连接保持,直到手机主动断开或网络中断。

控制:ctypes 调 Win32 API 移动/点击鼠标。
依赖:Python 标准库 + segno(纯 Python 二维码库, 仅用于生成配对二维码)。
用法:
  python server.py [端口]        # 图形前端(GUI)
  python server.py [端口] --no-gui   # 无图形环境回退(纯命令行 + 弹窗)
"""

import base64
import ctypes
import hashlib
import hmac
import io
import json
import os
import secrets
import socket
import struct
import subprocess
import sys
import threading
import time

# 媒体模块(手机摄像头/麦克风 -> 电脑虚拟设备)。可选依赖,缺了只影响媒体功能,不影响鼠标控制。
try:
    import media
except Exception:
    media = None

# pythonw.exe 无控制台,sys.stdout/stderr 为 None;任何 print 都会崩溃。
# 这里重定向到 devnull,避免无窗口运行时崩溃(配对码仍通过 MessageBox 弹窗显示)。
if sys.stdout is None:
    sys.stdout = open(os.devnull, "w", encoding="utf-8")
if sys.stderr is None:
    sys.stderr = open(os.devnull, "w", encoding="utf-8")

IS_WINDOWS = sys.platform == "win32"

# PyInstaller 单文件打包后 __file__ 指向临时解包目录(退出即删),持久化文件(密钥/日志)
# 必须落在 exe 所在目录;用 sys.frozen 区分,否则每次启动都会丢密钥、重新配对。
if getattr(sys, "frozen", False):
    HERE = os.path.dirname(os.path.abspath(sys.executable))
else:
    HERE = os.path.dirname(os.path.abspath(__file__))
SECRET_PATH = os.path.join(HERE, "secret.json")

PIN_TTL = 120          # 动态配对码有效期(秒)
AUTH_TIMEOUT = 180     # 认证阶段总超时(秒)
IDLE_TIMEOUT = None    # 关闭空闲断开:None 表示永不因空闲超时断连(用户要求)
TOTP_PERIOD = 30       # TOTP 时间步长(秒)
TOTP_DIGITS = 6        # 动态码位数
TOTP_WINDOW = 1        # 校验时允许前后 ±1 个时间步(容忍时钟漂移/网络延迟)
MAX_TOTP_FAILS = 3     # 连续错误次数阈值
LOCK_BASE_SEC = 30     # 第一次锁定 30 秒,之后每次翻倍
LISTEN_PORT_DEFAULT = 9527
DISCOVERY_PORT = 9528     # UDP 自动发现广播端口(手机扫描电脑用)
BEACON_INTERVAL = 2.0     # 广播间隔(秒)
MEDIA_IPC_PORT = 9530     # 仅 127.0.0.1 的回环命令口:让「手机媒体面板.bat」能把面板唤出来

# 诊断日志:所有连接事件/指令都追加到 server.log,方便排查「已连接又断开」
LOG_PATH = os.path.join(HERE, "server.log")
_log_lock = threading.Lock()


def _log_line(msg):
    try:
        with _log_lock:
            with open(LOG_PATH, "a", encoding="utf-8") as fp:
                fp.write("%s %s\n" % (time.strftime("%H:%M:%S"), msg))
    except Exception:
        pass

GUI_MODE = "--no-gui" not in sys.argv
# 后台静默模式:开机自启、无登录无主面板、端口一启动就监听;
# 只在手机连入配对时瞬态弹出 6 位配对码,连上即自动消失(配合开机自启使用)。
BACKGROUND_MODE = "--background" in sys.argv

# segno 是可选的:装上就显示二维码,没装则退化为纯文本种子(需手动抄写)。
try:
    import segno
except ImportError:  # pragma: no cover
    segno = None

if GUI_MODE:
    try:
        import tkinter as tk
        from tkinter import scrolledtext
    except ImportError:  # pragma: no cover
        GUI_MODE = False

# ---------------------------------------------------------------------------
# Win32 鼠标控制
# ---------------------------------------------------------------------------
if IS_WINDOWS:
    user32 = ctypes.windll.user32

    class POINT(ctypes.Structure):
        _fields_ = [("x", ctypes.c_long), ("y", ctypes.c_long)]

    MOUSEEVENTF_MOVE = 0x0001
    MOUSEEVENTF_LEFTDOWN = 0x0002
    MOUSEEVENTF_LEFTUP = 0x0004
    MOUSEEVENTF_RIGHTDOWN = 0x0008
    MOUSEEVENTF_RIGHTUP = 0x0010
    MOUSEEVENTF_MIDDLEDOWN = 0x0020
    MOUSEEVENTF_MIDDLEUP = 0x0040
    MOUSEEVENTF_WHEEL = 0x0800
    MOUSEEVENTF_ABSOLUTE = 0x8000
    WHEEL_DELTA = 120
    INPUT_MOUSE = 0

    # SendInput 比 mouse_event 更可靠:绝对坐标移动 + 按键都用它,避免 SetCursorPos
    # 与 mouse_event 之间的时序/坐标不一致(在右键菜单等场景下左键点不中)。
    # (INPUT/MOUSEINPUT/_INPUTUNION 统一在下方「文本输入」段定义,避免重复定义导致字段名不一致)

    def _send_input(flags, dx=0, dy=0, data=0):
        inp = INPUT()
        inp.type = INPUT_MOUSE
        inp.union.mi.dwFlags = flags
        inp.union.mi.dx = dx
        inp.union.mi.dy = dy
        inp.union.mi.mouseData = data
        user32.SendInput(1, ctypes.byref(inp), ctypes.sizeof(INPUT))

    def move_mouse(dx, dy):
        _send_input(MOUSEEVENTF_MOVE, dx, dy)

    _BUTTON_FLAGS = (
        (0x01, MOUSEEVENTF_LEFTDOWN, MOUSEEVENTF_LEFTUP),
        (0x02, MOUSEEVENTF_RIGHTDOWN, MOUSEEVENTF_RIGHTUP),
        (0x04, MOUSEEVENTF_MIDDLEDOWN, MOUSEEVENTF_MIDDLEUP),
    )

    def apply_buttons(buttons, prev):
        for bit, down, up in _BUTTON_FLAGS:
            was = prev & bit
            now = buttons & bit
            if now and not was:
                _send_input(down)
                _log_line("[mouse] DOWN bit=%#x" % bit)
            elif was and not now:
                _send_input(up)
                _log_line("[mouse] UP bit=%#x" % bit)
        return buttons

    def scroll_wheel(wheel):
        _send_input(MOUSEEVENTF_WHEEL, 0, 0, wheel * WHEEL_DELTA)

    def release_all_buttons():
        for bit, down, up in _BUTTON_FLAGS:
            _send_input(up)
else:
    def move_mouse(dx, dy):
        print(f"  [mouse] move {dx} {dy}")

    def apply_buttons(buttons, prev):
        if buttons != prev:
            print(f"  [mouse] buttons {prev:#x} -> {buttons:#x}")
        return buttons

    def scroll_wheel(wheel):
        print(f"  [mouse] wheel {wheel}")

    def release_all_buttons():
        pass

# ---------------------------------------------------------------------------
# 屏幕镜像 + 文本输入 + 绝对坐标(远程桌面视图)
# ---------------------------------------------------------------------------
try:
    from PIL import Image, ImageGrab
except ImportError:  # pragma: no cover
    Image = None
    ImageGrab = None

STREAM_FPS = 30             # 屏幕镜像帧率上限(实际由抓屏+编码速度决定)
STREAM_MAX_WIDTH = 0        # 0 = 不缩放,按 PC 原生分辨率(4K/2K 原样)推流,画面最清晰
STREAM_JPEG_QUALITY = 88    # JPEG 质量:高保真文字边缘(GDI 快速抓屏已足够快,不必再靠降质省时间)
STREAM_IDLE_TIMEOUT = None   # 关闭空闲断开:流式观看也不因空闲超时断连

if IS_WINDOWS:
    ULONG_PTR = ctypes.POINTER(ctypes.c_ulong)

    class KEYBDINPUT(ctypes.Structure):
        _fields_ = [
            ("wVk", ctypes.c_ushort),
            ("wScan", ctypes.c_ushort),
            ("dwFlags", ctypes.c_ulong),
            ("time", ctypes.c_ulong),
            ("dwExtraInfo", ULONG_PTR),
        ]

    class MOUSEINPUT(ctypes.Structure):
        _fields_ = [
            ("dx", ctypes.c_long),
            ("dy", ctypes.c_long),
            ("mouseData", ctypes.c_ulong),
            ("dwFlags", ctypes.c_ulong),
            ("time", ctypes.c_ulong),
            ("dwExtraInfo", ULONG_PTR),
        ]

    class HARDWAREINPUT(ctypes.Structure):
        _fields_ = [
            ("uMsg", ctypes.c_ulong),
            ("wParamL", ctypes.c_ushort),
            ("wParamH", ctypes.c_ushort),
        ]

    class _INPUTUNION(ctypes.Union):
        _fields_ = [("mi", MOUSEINPUT), ("ki", KEYBDINPUT), ("hi", HARDWAREINPUT)]

    class INPUT(ctypes.Structure):
        _fields_ = [("type", ctypes.c_ulong), ("union", _INPUTUNION)]

    INPUT_KEYBOARD = 1
    KEYEVENTF_KEYUP = 0x0002
    KEYEVENTF_UNICODE = 0x0004

    user32.SendInput.argtypes = [ctypes.c_uint, ctypes.POINTER(INPUT), ctypes.c_int]
    user32.SendInput.restype = ctypes.c_uint


def type_text(text):
    """把文本作为键盘输入打到电脑当前焦点处(Unicode,正确处理中文/emoji)。"""
    if not text:
        return
    if not IS_WINDOWS:
        print(f"  [text] {text}")
        return
    units = []
    for ch in text:
        cp = ord(ch)
        if cp < 0x10000:
            units.append(cp)
        else:
            cp -= 0x10000
            units.append(0xD800 + (cp >> 10))
            units.append(0xDC00 + (cp & 0x3FF))
    n = len(units)
    arr = (INPUT * (n * 2))()
    for i, code in enumerate(units):
        arr[i * 2].type = INPUT_KEYBOARD
        arr[i * 2].union.ki.wVk = 0
        arr[i * 2].union.ki.wScan = code
        arr[i * 2].union.ki.dwFlags = KEYEVENTF_UNICODE
        arr[i * 2 + 1].type = INPUT_KEYBOARD
        arr[i * 2 + 1].union.ki.wVk = 0
        arr[i * 2 + 1].union.ki.wScan = code
        arr[i * 2 + 1].union.ki.dwFlags = KEYEVENTF_UNICODE | KEYEVENTF_KEYUP
    try:
        user32.SendInput(n * 2, arr, ctypes.sizeof(INPUT))
    except Exception:
        pass


# 流式画面缩小后的尺寸(手机端按这个分辨率映射坐标)与真实屏幕尺寸。
# capture_frame 里更新;abs_move 据此把「流式坐标」映射回真实屏幕坐标。
STREAM_W = 0
STREAM_H = 0
REAL_W = 0
REAL_H = 0


def _abs_coords(x, y):
    """把流式画面坐标映射成 SendInput 绝对坐标(0..65535 覆盖整个主屏)。"""
    rx = int(x)
    ry = int(y)
    if STREAM_W > 0 and REAL_W > 0:
        rx = int(x * REAL_W / STREAM_W)
        ry = int(y * REAL_H / STREAM_H)
    ax = int(rx * 65535 / (REAL_W - 1)) if REAL_W > 1 else 0
    ay = int(ry * 65535 / (REAL_H - 1)) if REAL_H > 1 else 0
    return ax, ay


def abs_move(x, y):
    """把光标移到屏幕绝对坐标(流式坐标 -> 真实屏幕)。"""
    if IS_WINDOWS:
        ax, ay = _abs_coords(x, y)
        _send_input(MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE, ax, ay)
    else:
        print(f"  [abs] move {x} {y}")


def abs_click(x, y, flags):
    """把「移动光标到绝对坐标」和「按下/松开按键」合成一次 SendInput 调用。

    分两次调用时,某些场景(右键菜单、窗口标题栏按钮)下点击可能落在移动前的旧位置;
    原子发送能保证 DOWN/UP 精确落在 (x, y) 对应的屏幕坐标上。
    """
    ax, ay = _abs_coords(x, y)
    events = (INPUT * 2)()
    events[0].type = INPUT_MOUSE
    events[0].union.mi.dwFlags = MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE
    events[0].union.mi.dx = ax
    events[0].union.mi.dy = ay
    events[1].type = INPUT_MOUSE
    events[1].union.mi.dwFlags = flags
    user32.SendInput(2, events, ctypes.sizeof(INPUT))


def _grab_screen_fast():
    """GDI BitBlt 直接抓整屏成 numpy(BGRA)再转 PIL RGB;比 PIL ImageGrab 快很多。

    失败抛异常,由 capture_frame 回退到 PIL ImageGrab。
    """
    import ctypes as _ct
    import numpy as _np
    u32 = _ct.windll.user32
    g32 = _ct.windll.gdi32
    w = u32.GetSystemMetrics(0)
    h = u32.GetSystemMetrics(1)
    hwnd = u32.GetDesktopWindow()
    hdc = u32.GetWindowDC(hwnd)
    mdc = g32.CreateCompatibleDC(hdc)
    bmp = g32.CreateCompatibleBitmap(hdc, w, h)
    g32.SelectObject(mdc, bmp)
    g32.BitBlt(mdc, 0, 0, w, h, hdc, 0, 0, 0x00CC0020)  # SRCCOPY

    class BI(_ct.Structure):
        _fields_ = [
            ("sz", _ct.c_uint32), ("wd", _ct.c_int32), ("ht", _ct.c_int32),
            ("pl", _ct.c_uint16), ("bc", _ct.c_uint16), ("cp", _ct.c_uint32),
            ("szimg", _ct.c_uint32), ("xpm", _ct.c_int32), ("ypm", _ct.c_int32),
            ("clr", _ct.c_uint32), ("imp", _ct.c_uint32),
        ]
    bi = BI()
    bi.sz = _ct.sizeof(BI)
    bi.wd = w
    bi.ht = -h          # 负高度 = 自顶向下,免去手动翻转
    bi.pl = 1
    bi.bc = 32
    bi.cp = 0           # BI_RGB
    buf = _np.zeros((h, w, 4), dtype=_np.uint8)
    g32.GetDIBits(mdc, bmp, 0, h, buf.ctypes.data_as(_ct.c_void_p), _ct.byref(bi), 0)
    g32.DeleteObject(bmp)
    g32.DeleteDC(mdc)
    u32.ReleaseDC(hwnd, hdc)
    return Image.fromarray(buf[:, :, [2, 1, 0]], "RGB")  # BGRA -> RGB


def capture_frame():
    """抓一帧屏幕,返回 (jpeg_bytes, 流式宽, 流式高);失败返回 (None, 0, 0)。

    注意:STREAM_MAX_WIDTH=0 时按 PC 原生分辨率推流,STREAM_W/H == REAL_W/H;
    返回的 (w,h) 是发给手机的实际尺寸,保证 FRAME 头与图像内容一致。
    """
    global STREAM_W, STREAM_H, REAL_W, REAL_H
    if ImageGrab is None:
        return None, 0, 0
    try:
        img = None
        if IS_WINDOWS:
            try:
                img = _grab_screen_fast()
            except Exception:
                img = None
        if img is None:
            img = ImageGrab.grab()
        REAL_W, REAL_H = img.size
        if STREAM_MAX_WIDTH > 0 and img.width > STREAM_MAX_WIDTH:
            ratio = STREAM_MAX_WIDTH / img.width
            img = img.resize((STREAM_MAX_WIDTH, int(img.height * ratio)), Image.BILINEAR)
        STREAM_W, STREAM_H = img.size
        buf = io.BytesIO()
        img.save(buf, "JPEG", quality=STREAM_JPEG_QUALITY)
        return buf.getvalue(), STREAM_W, STREAM_H
    except Exception:
        return None, 0, 0


def stream_frames(conn, f, streaming, write_lock):
    """独立线程:持续抓屏并把 JPEG 帧(带长度头)发给手机。

    画面没变(与上一帧逐字节相同)就跳过不重发 —— 桌面静止时镜像几乎不占带宽,
    把 Wi-Fi 全让给摄像头/麦克风;画面一变就立刻按档位帧率发(≤~1 秒兜底补一帧)。
    抓屏+编码耗时不计入间隔,按档位帧率匀着推,避免越拖越慢。
    """
    last = None       # (宽, 高, 上一帧已发的 JPEG 字节)
    last_t = 0.0
    while streaming.is_set():
        t0 = time.time()
        data, w, h = capture_frame()
        if data:
            now = time.time()
            same = last is not None and last[0] == w and last[1] == h and last[2] == data
            if not (same and now - last_t < 1.0):
                try:
                    with write_lock:
                        f.write(f"FRAME {len(data)} {w} {h}\n")
                        f.flush()
                        conn.sendall(data)
                    last = (w, h, data)
                    last_t = now
                except Exception:
                    break
        slot = 1.0 / STREAM_FPS
        dt = time.time() - t0
        if dt < slot:
            time.sleep(slot - dt)

# ---------------------------------------------------------------------------
# DPAPI 加密/解密(Windows 用户级加密,密钥绑定当前 Windows 账户)
# ---------------------------------------------------------------------------
if IS_WINDOWS:
    from ctypes import wintypes

    class DATA_BLOB(ctypes.Structure):
        _fields_ = [
            ("cbData", wintypes.DWORD),
            ("pbData", ctypes.POINTER(ctypes.c_char)),
        ]


def dpapi_encrypt(data: bytes) -> bytes:
    """DPAPI 加密;非 Windows 或失败则原样返回(降级为不加密)。"""
    if not IS_WINDOWS or not data:
        return data
    try:
        crypt32 = ctypes.windll.crypt32
        kernel32 = ctypes.windll.kernel32
        in_buf = ctypes.create_string_buffer(data, len(data))
        in_blob = DATA_BLOB(len(data), ctypes.cast(in_buf, ctypes.POINTER(ctypes.c_char)))
        out_blob = DATA_BLOB()
        if not crypt32.CryptProtectData(
            ctypes.byref(in_blob), None, None, None, None, 0x01, ctypes.byref(out_blob)
        ):
            return data
        result = ctypes.string_at(out_blob.pbData, out_blob.cbData)
        kernel32.LocalFree(out_blob.pbData)
        return result
    except Exception:
        return data


def dpapi_decrypt(data: bytes) -> bytes:
    if not IS_WINDOWS or not data:
        return data
    try:
        crypt32 = ctypes.windll.crypt32
        kernel32 = ctypes.windll.kernel32
        in_buf = ctypes.create_string_buffer(data, len(data))
        in_blob = DATA_BLOB(len(data), ctypes.cast(in_buf, ctypes.POINTER(ctypes.c_char)))
        out_blob = DATA_BLOB()
        if not crypt32.CryptUnprotectData(
            ctypes.byref(in_blob), None, None, None, None, 0x01, ctypes.byref(out_blob)
        ):
            return data
        result = ctypes.string_at(out_blob.pbData, out_blob.cbData)
        kernel32.LocalFree(out_blob.pbData)
        return result
    except Exception:
        return data

# ---------------------------------------------------------------------------
# 存储:种子 + 一次性恢复码(DPAPI 加密落盘)
# ---------------------------------------------------------------------------
_store_lock = threading.Lock()
_store = {"secret": None, "recovery": None}  # 内存缓存


def _read_store_from_disk():
    """从 secret.json 读取并 DPAPI 解密。返回 dict。"""
    if not os.path.exists(SECRET_PATH):
        return {"secret": None, "recovery": None}
    try:
        with open(SECRET_PATH, "r", encoding="utf-8") as f:
            raw = json.load(f)
    except Exception:
        return {"secret": None, "recovery": None}
    out = {"secret": None, "recovery": None}
    if raw.get("totp_secret_enc"):
        try:
            out["secret"] = dpapi_decrypt(base64.b64decode(raw["totp_secret_enc"])).decode("ascii")
        except Exception:
            out["secret"] = None
    if raw.get("recovery_code_enc"):
        try:
            out["recovery"] = dpapi_decrypt(base64.b64decode(raw["recovery_code_enc"])).decode("ascii")
        except Exception:
            out["recovery"] = None
    return out


def _write_store_to_disk(store):
    raw = {}
    if store.get("secret"):
        raw["totp_secret_enc"] = base64.b64encode(
            dpapi_encrypt(store["secret"].encode("ascii"))
        ).decode("ascii")
    if store.get("recovery"):
        raw["recovery_code_enc"] = base64.b64encode(
            dpapi_encrypt(store["recovery"].encode("ascii"))
        ).decode("ascii")
    tmp = SECRET_PATH + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(raw, f, indent=2)
    os.replace(tmp, SECRET_PATH)  # 原子替换,避免写一半损坏


def init_store():
    global _store
    with _store_lock:
        _store = _read_store_from_disk()


def get_secret():
    with _store_lock:
        return _store["secret"]


def get_recovery():
    with _store_lock:
        return _store["recovery"]


def save_pair(secret, recovery):
    """原子保存新种子 + 新恢复码(覆盖旧的,旧的恢复码随覆盖自然作废)。"""
    global _store
    with _store_lock:
        _store = {"secret": secret, "recovery": recovery}
        _write_store_to_disk(_store)


def check_recovery(code):
    """校验一次性恢复码:匹配即有效。"""
    with _store_lock:
        return bool(_store["recovery"]) and hmac.compare_digest(_store["recovery"], code)


def generate_secret():
    """生成 TOTP 种子(16 字节 = 32 hex 字符,放进二维码)。"""
    return secrets.token_hex(16)


def generate_recovery():
    """生成一次性恢复码(8 位数字,好抄写)。"""
    return f"{secrets.randbelow(100000000):08d}"

# ---------------------------------------------------------------------------
# 动态配对码(全局单一,每次新连接覆盖旧的)
# ---------------------------------------------------------------------------
_pin_lock = threading.Lock()
_current_pin = None
_current_pin_expiry = 0.0


def new_pairing_code():
    global _current_pin, _current_pin_expiry
    with _pin_lock:
        _current_pin = f"{secrets.randbelow(1000000):06d}"
        _current_pin_expiry = time.time() + PIN_TTL
        return _current_pin


def check_pairing_code(pin):
    with _pin_lock:
        if _current_pin is None or time.time() > _current_pin_expiry:
            return False
        return hmac.compare_digest(pin, _current_pin)

# ---------------------------------------------------------------------------
# TOTP (RFC 6238, HMAC-SHA1)
# ---------------------------------------------------------------------------
def totp_code(secret, now=None):
    key = bytes.fromhex(secret)
    counter = int((now if now is not None else time.time()) // TOTP_PERIOD)
    msg = struct.pack(">Q", counter)
    digest = hmac.new(key, msg, hashlib.sha1).digest()
    offset = digest[-1] & 0x0F
    binary = (
        (digest[offset] & 0x7F) << 24
        | (digest[offset + 1] & 0xFF) << 16
        | (digest[offset + 2] & 0xFF) << 8
        | (digest[offset + 3] & 0xFF)
    )
    return f"{binary % (10 ** TOTP_DIGITS):0{TOTP_DIGITS}d}"


def verify_totp(secret, code):
    now = time.time()
    for w in range(-TOTP_WINDOW, TOTP_WINDOW + 1):
        if hmac.compare_digest(totp_code(secret, now + w * TOTP_PERIOD), code):
            return True
    return False

# ---------------------------------------------------------------------------
# 锁定状态(全局:锁住「这台电脑」的 TOTP 认证入口)
# ---------------------------------------------------------------------------
_lock = threading.Lock()
_fail_count = 0
_lock_count = 0
_lock_until = 0.0


def check_totp(secret, code):
    """返回 (status, info):ok / bad / locked(含剩余秒数)。"""
    global _fail_count, _lock_count, _lock_until
    with _lock:
        now = time.time()
        if now < _lock_until:
            return "locked", int(_lock_until - now)
        if verify_totp(secret, code):
            _fail_count = 0
            return "ok", 0
        _fail_count += 1
        if _fail_count >= MAX_TOTP_FAILS:
            _lock_count += 1
            _fail_count = 0
            dur = LOCK_BASE_SEC * (2 ** (_lock_count - 1))  # 30, 60, 120, 240...
            _lock_until = now + dur
            return "locked", dur
        return "bad", _fail_count

# ---------------------------------------------------------------------------
# 会话密钥与签名
# ---------------------------------------------------------------------------
def derive_session_key(pin, secret):
    material = f"{pin}|{secret}".encode("utf-8")
    return hmac.new(material, b"session", hashlib.sha256).digest()


def sign_command(session_key, payload):
    """对一条控制命令签名。payload 是「命令字母 + 空格 + 各字段」拼接(不含签名)。"""
    return hmac.new(session_key, payload.encode("utf-8"), hashlib.sha256).hexdigest()


def verify_command(session_key, payload, sig):
    return hmac.compare_digest(sign_command(session_key, payload), sig)


# ---------------------------------------------------------------------------
# 免码续连 token(认证通过后签发;短时间内断线重连免输配对码)
# ---------------------------------------------------------------------------
RESUME_TTL = 600          # 10 分钟有效,每次续连成功自动顺延
_resume_tokens = {}
_resume_lock = threading.Lock()


def _issue_resume():
    """签发一个免码续连 token(随机 128 位),存到内存,TTL 后自动过期。"""
    tok = secrets.token_hex(16)
    with _resume_lock:
        _resume_tokens[tok] = time.time() + RESUME_TTL
    return tok


def _check_resume(tok):
    """校验续连 token;有效则顺延有效期(滑动续期)并返回 True。"""
    with _resume_lock:
        exp = _resume_tokens.get(tok)
        if exp is None:
            return False
        now = time.time()
        if now > exp:
            _resume_tokens.pop(tok, None)
            return False
        _resume_tokens[tok] = now + RESUME_TTL
        if len(_resume_tokens) > 64:   # 顺手清过期项,防内存无限增长
            for k, e in list(_resume_tokens.items()):
                if e <= now:
                    _resume_tokens.pop(k, None)
        return True


def _resume_session_key(tok):
    """续连会话密钥 = HMAC-SHA256(token, "resume-session"),手机端需一致。"""
    return hmac.new(tok.encode("utf-8"), b"resume-session", hashlib.sha256).digest()

# ---------------------------------------------------------------------------
# 二维码(segno)
# ---------------------------------------------------------------------------
def make_qr_png_b64(text):
    """返回二维码 PNG 的 base64 字符串(供 Tkinter PhotoImage 加载);无 segno 时返回 None。"""
    if segno is None:
        return None
    try:
        buf = io.BytesIO()
        segno.make(text).save(buf, kind="png", scale=6, border=2)
        return base64.b64encode(buf.getvalue()).decode("ascii")
    except Exception:
        return None

# ---------------------------------------------------------------------------
# 前端输出抽象:GUI 走队列(主线程 after 轮询),CLI 走 print + 弹窗
# ---------------------------------------------------------------------------
import queue as _queue_mod

gui_queue = _queue_mod.Queue()


def _emit(kind, **data):
    _log_line("[emit] %s %s" % (kind, data))
    if GUI_MODE or BACKGROUND_MODE:
        # GUI:配对码在主面板显示;后台模式:由 BackgroundHost 瞬态弹窗显示
        gui_queue.put({"kind": kind, **data})
    else:
        _cli_emit(kind, **data)


def _cli_emit(kind, **data):
    if kind == "pairing":
        pin = data["pin"]
        print()
        print("  *" * 20)
        print(f"  *  手机触控板配对码: {pin}   *")
        print("  *" * 20)
        _cli_popup(f"手机触控板配对码: {pin}\n\n请在手机端输入这个码。\n({PIN_TTL} 秒内有效)")
    elif kind == "setup":
        secret = data["secret"]
        recovery = data["recovery"]
        grouped = "-".join(secret[i:i + 4] for i in range(0, len(secret), 4))
        print()
        print("  *" * 30)
        print("  首次配对 —— 请用手机扫描二维码或手动输入种子:")
        print(f"      {grouped}")
        print(f"  恢复码(请妥善保存,手机丢失/卸载时用): {recovery}")
        print("  *" * 30)
        if segno is None:
            _cli_popup(
                f"首次配对种子:\n\n{grouped}\n\n恢复码(保存好): {recovery}"
            )
        else:
            _cli_popup(
                f"首次配对 —— 手机扫描二维码,或手动输入种子:\n\n{grouped}\n\n"
                f"恢复码(请妥善保存,手机丢失/卸载时用): {recovery}"
            )
    elif kind == "connected":
        print(f"[+] 客户端 {data.get('addr', '?')} 认证通过,进入控制模式")
    elif kind == "disconnected":
        print(f"[-] 客户端 {data.get('addr', '?')} 已断开")
    elif kind == "log":
        print(f"[i] {data['msg']}")


def _cli_popup(text):
    if not IS_WINDOWS:
        return

    def _show():
        try:
            user32.MessageBoxW.argtypes = [
                ctypes.c_void_p, ctypes.c_wchar_p, ctypes.c_wchar_p, ctypes.c_uint,
            ]
            user32.MessageBoxW.restype = ctypes.c_int
            user32.MessageBoxW(None, text, "手机触控板配对", 0x40)
        except Exception:
            pass

    threading.Thread(target=_show, daemon=True).start()

# ---------------------------------------------------------------------------
# 控制连接注册表:电脑端状态窗图标可反向给手机下发媒体开关命令
# ---------------------------------------------------------------------------
_ctrl_registry = {}
_ctrl_registry_lock = threading.Lock()


def _register_ctrl(f, write_lock):
    with _ctrl_registry_lock:
        _ctrl_registry[id(f)] = (f, write_lock)


def _unregister_ctrl(f):
    with _ctrl_registry_lock:
        _ctrl_registry.pop(id(f), None)


def send_ctrl_line(line):
    """向所有已认证的控制连接下发一行命令(反向控制手机摄像头/麦克风开关)。返回送达数。"""
    with _ctrl_registry_lock:
        items = list(_ctrl_registry.values())
    n = 0
    for f, write_lock in items:
        try:
            with write_lock:
                f.write(line + "\n")
                f.flush()
            n += 1
        except Exception:
            pass
    return n

# ---------------------------------------------------------------------------
# 客户端会话
# ---------------------------------------------------------------------------
def handle_client(conn, addr):
    # 减小延迟(禁用 Nagle)+ 开启 keepalive 防网络层静默断连
    try:
        conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        conn.setsockopt(socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1)
    except Exception:
        pass
    # 媒体连接会立刻发 `MEDIA <token>`,控制连接则等收到 PIN_REQUIRED 才发东西。
    # 先短超时探测首行区分二者:媒体连接直接转媒体处理,不再走配对流程。
    try:
        conn.settimeout(0.5)
        first = media._recv_line(conn) if media is not None else None
    except Exception:
        first = None
    if first and first.startswith("MEDIA "):
        media.handle_media_conn(conn, first)
        try:
            conn.close()
        except Exception:
            pass
        return

    f = conn.makefile("rw", encoding="utf-8", newline="\n")
    prev_buttons = 0
    try:
        # ---- 免码续连:自动重连的手机一上来就发 `RESUME <token>`(首行探测已捕获)。
        # 命中则不弹新配对码、不做 TOTP,直接以续连会话密钥进控制模式。
        conn.settimeout(AUTH_TIMEOUT)
        resumed = False
        if first and first.startswith("RESUME "):
            tok = first[len("RESUME "):].strip()
            if not _check_resume(tok):
                f.write("ERR resume expired\n")
                f.flush()
                return
            session_key = _resume_session_key(tok)
            resumed = True
            _log_line("[resume] 免码续连成功")
        else:
            # ---- 因素一:动态配对码 ----
            pin = new_pairing_code()
            _emit("pairing", pin=pin)
            f.write("PIN_REQUIRED\n")
            f.flush()

            line = _read_line(f)
            if not line or not line.startswith("PIN "):
                return
            if not check_pairing_code(line[4:].strip()):
                f.write("ERR bad pin\n")
                f.flush()
                return

            # ---- 因素二:种子(首次配对走二维码,已配对等 TOTP/恢复码) ----
            secret = get_secret()
            pending_secret = None
            pending_recovery = None
            if secret is None:
                # 首次配对:电脑生成种子+恢复码,前端显示二维码(带外传种子)
                pending_secret = generate_secret()
                pending_recovery = generate_recovery()
                _emit("setup", secret=pending_secret, recovery=pending_recovery)
                f.write("SETUP\n")
                f.flush()
            else:
                f.write("TOTP_REQUIRED\n")
                f.flush()

            active_secret = secret if secret is not None else pending_secret

            # TOTP / RECOVER 循环
            session_key = None
            while True:
                line = _read_line(f)
                if not line:
                    return

                # 恢复流程:手机本地没种子时,凭一次性恢复码重新配对
                if line.startswith("RECOVER ") and secret is not None:
                    if check_recovery(line[len("RECOVER "):].strip()):
                        pending_secret = generate_secret()
                        pending_recovery = generate_recovery()
                        active_secret = pending_secret
                        _emit("setup", secret=pending_secret, recovery=pending_recovery)
                        f.write("SETUP\n")
                        f.flush()
                        continue  # 手机扫码后发 TOTP
                    f.write("ERR bad recovery\n")
                    f.flush()
                    continue

                if not line.startswith("TOTP "):
                    continue
                code = line[5:].strip()
                status, info = check_totp(active_secret, code)
                if status == "ok":
                    if pending_secret is not None:
                        # 首次配对或恢复配对成功:原子保存新种子+新恢复码
                        save_pair(pending_secret, pending_recovery)
                        _emit("log", msg="配对完成,已保存密钥(DPAPI 加密)")
                    session_key = derive_session_key(pin, active_secret)
                    break
                if status == "locked":
                    f.write(f"ERR locked {info}\n")
                    f.flush()
                    return
                f.write("ERR bad totp\n")
                f.flush()

        # ---- 认证通过,进入控制模式(60 秒空闲超时;流式观看时放宽) ----
        conn.settimeout(IDLE_TIMEOUT)
        f.write("OK\n")
        f.flush()
        # 签发媒体 token,手机凭它开「摄像头/麦克风」媒体连接(免重复认证)
        if media is not None:
            f.write("MEDIA_TOKEN %s\n" % media.issue_token())
            f.flush()
        # 免码续连 token:掉线重连时凭它 10 分钟内免输配对码(重连自动续新/顺延)
        f.write("RESUME %s\n" % _issue_resume())
        f.flush()
        _emit("connected", addr=addr[0] if addr else "?")

        prev_seq = 0
        cmd_count = 0
        streaming = threading.Event()
        write_lock = threading.Lock()
        _register_ctrl(f, write_lock)
        while True:
            try:
                line = _read_line(f)
            except (socket.timeout, TimeoutError):
                streaming.clear()
                _emit("log", msg="空闲超时,已断开")
                break
            if not line:
                streaming.clear()
                _log_line("[disconnect] 客户端关闭连接")
                break

            parts = line.split()
            if len(parts) < 3:
                _log_line("[reject] 字段不足: %r" % line)
                continue
            cmd = parts[0]
            try:
                seq = int(parts[1])
            except ValueError:
                _log_line("[reject] 序号非法: %r" % line)
                continue
            if seq <= prev_seq:
                _log_line("[reject] 序号回退 seq=%s prev=%s" % (seq, prev_seq))
                continue
            # 校验签名:载荷 = 除最后一段(签名)外的全部字段
            if not verify_command(session_key, " ".join(parts[:-1]), parts[-1]):
                _log_line("[reject] 签名不符: %r" % line)
                continue
            prev_seq = seq
            cmd_count += 1
            if cmd_count % 50 == 1:
                _log_line("[ok] 已处理 %d 条指令, 最近: %s" % (cmd_count, cmd))

            if cmd == "M" and len(parts) == 7:
                try:
                    dx, dy, wheel, buttons = (int(parts[2]), int(parts[3]),
                                              int(parts[4]), int(parts[5]))
                except ValueError:
                    continue
                if dx or dy:
                    move_mouse(dx, dy)
                if wheel:
                    scroll_wheel(wheel)
                prev_buttons = apply_buttons(buttons, prev_buttons)
            elif cmd == "A" and len(parts) == 6:
                try:
                    x, y, buttons = int(parts[2]), int(parts[3]), int(parts[4])
                except ValueError:
                    continue
                if IS_WINDOWS and buttons != prev_buttons:
                    # 按键有变化:原子地「移到绝对坐标 + 按下/松开」,保证点击落点准确
                    for bit, down, up in _BUTTON_FLAGS:
                        was = prev_buttons & bit
                        now = buttons & bit
                        if now and not was:
                            abs_click(x, y, down)
                            _log_line("[mouse] DOWN bit=%#x" % bit)
                        elif was and not now:
                            abs_click(x, y, up)
                            _log_line("[mouse] UP bit=%#x" % bit)
                    pt = POINT()
                    user32.GetCursorPos(ctypes.byref(pt))
                    _log_line(
                        "[click] 手机(%d,%d) btn=%#x -> 光标(%d,%d) 屏幕 %d x %d 流 %d x %d"
                        % (x, y, buttons, pt.x, pt.y, REAL_W, REAL_H, STREAM_W, STREAM_H)
                    )
                    prev_buttons = buttons
                else:
                    abs_move(x, y)
                    prev_buttons = apply_buttons(buttons, prev_buttons)
            elif cmd == "T" and len(parts) == 4:
                try:
                    text = base64.b64decode(parts[2]).decode("utf-8")
                except Exception:
                    continue
                type_text(text)
            elif cmd == "V" and len(parts) == 4:
                if parts[2] == "1":
                    if not streaming.is_set():
                        streaming.set()
                        conn.settimeout(STREAM_IDLE_TIMEOUT)
                        threading.Thread(
                            target=stream_frames,
                            args=(conn, f, streaming, write_lock),
                            daemon=True,
                        ).start()
                elif parts[2] == "0":
                    streaming.clear()
                    conn.settimeout(IDLE_TIMEOUT)
            elif cmd == "MR" and len(parts) in (5, 6):
                # 镜像档位:手机端选清晰度后下发,动态改下一帧的抓屏尺寸/帧率上限/JPEG 质量
                # (maxWidth<=0 = 按电脑原生分辨率;fps 1~60 帧率上限;quality 50~95,越低越省带宽)
                global STREAM_MAX_WIDTH, STREAM_FPS, STREAM_JPEG_QUALITY
                try:
                    mw = int(parts[2])
                    fps = int(parts[3])
                    STREAM_MAX_WIDTH = max(0, min(3840, mw))
                    STREAM_FPS = max(1, min(60, fps))
                    if len(parts) == 6:   # 新手机端带质量;旧版(只 2 个参数)保持原质量
                        q = int(parts[4])
                        STREAM_JPEG_QUALITY = max(50, min(95, q))
                    _log_line("[mirror] 档位: 宽%s @ %sfps JPEGQ%d" % (
                        "≤%d" % STREAM_MAX_WIDTH if STREAM_MAX_WIDTH else "原生",
                        STREAM_FPS, STREAM_JPEG_QUALITY))
                except ValueError:
                    continue
    except Exception as e:
        _emit("log", msg=f"会话异常: {e}")
    finally:
        if prev_buttons:
            release_all_buttons()
        _unregister_ctrl(f)
        try:
            f.close()
        except Exception:
            pass
        conn.close()
        _emit("disconnected", addr=addr[0] if addr else "?")


def _read_line(f):
    """读一行;超时抛 TimeoutError,断开返回 None,正常返回去掉换行的字符串。"""
    try:
        line = f.readline()
    except (socket.timeout, TimeoutError):
        raise
    except Exception:
        return None
    if line == "":
        return None
    return line.strip()

# ---------------------------------------------------------------------------
# 电脑前端登录 PIN(打开程序要先输 PIN,防止别人在电脑上看到二维码/恢复码)
# ---------------------------------------------------------------------------
MASTER_PATH = os.path.join(HERE, "master.json")
PBKDF2_ITERATIONS = 200_000


def master_exists():
    return os.path.exists(MASTER_PATH)


def set_master_pin(pin):
    salt = secrets.token_hex(16)
    verifier = hashlib.pbkdf2_hmac(
        "sha256", pin.encode("utf-8"), bytes.fromhex(salt), PBKDF2_ITERATIONS
    ).hex()
    with open(MASTER_PATH, "w", encoding="utf-8") as f:
        json.dump({"salt": salt, "verifier": verifier}, f, indent=2)


def verify_master_pin(pin):
    try:
        with open(MASTER_PATH, "r", encoding="utf-8") as f:
            data = json.load(f)
        verifier = hashlib.pbkdf2_hmac(
            "sha256", pin.encode("utf-8"), bytes.fromhex(data["salt"]), PBKDF2_ITERATIONS
        ).hex()
        return hmac.compare_digest(verifier, data["verifier"])
    except Exception:
        return False

# ---------------------------------------------------------------------------
# GUI 前端(Tkinter)
# ---------------------------------------------------------------------------
class GuiApp:
    def __init__(self, root, ip, port, paired):
        self.root = root
        self.ip = ip
        self.port = port
        self.paired = paired
        self._qr_photo = None  # 持引用防 GC
        root.title("手机触控板 — 电脑端")
        root.minsize(420, 540)
        if master_exists():
            self._build_login()
        else:
            self._build_setup_pin()
        self.root.after(100, self._poll)

    def _clear_root(self):
        for w in self.root.winfo_children():
            w.destroy()

    # ---- 首次使用:设置登录 PIN ----
    def _build_setup_pin(self):
        self._clear_root()
        pad = {"padx": 12, "pady": 6}
        tk.Label(self.root, text="首次使用:设置登录 PIN", font=("Microsoft YaHei", 15, "bold")).pack(anchor="w", **pad)
        tk.Label(
            self.root, fg="#666", wraplength=380, justify="left",
            text="以后每次打开本程序都要先输入这个 PIN,防止别人在电脑上直接看到配对二维码、种子和恢复码。"
        ).pack(anchor="w", **pad)
        tk.Label(self.root, text="登录 PIN(6 位数字):").pack(anchor="w", **pad)
        self._pin_entry1 = tk.Entry(self.root, show="•", font=("Consolas", 14))
        self._pin_entry1.pack(anchor="w", **pad)
        tk.Label(self.root, text="再次确认 PIN:").pack(anchor="w", **pad)
        self._pin_entry2 = tk.Entry(self.root, show="•", font=("Consolas", 14))
        self._pin_entry2.pack(anchor="w", **pad)
        self._pin_err = tk.Label(self.root, text="", fg="#cc0000")
        self._pin_err.pack(anchor="w", **pad)
        tk.Button(self.root, text="设置并进入", command=self._on_set_pin).pack(anchor="w", **pad)

    def _on_set_pin(self):
        p1 = self._pin_entry1.get().strip()
        p2 = self._pin_entry2.get().strip()
        if not (len(p1) == 6 and p1.isdigit()):
            self._pin_err.config(text="PIN 必须是 6 位数字")
            return
        if p1 != p2:
            self._pin_err.config(text="两次输入不一致,请重试")
            return
        set_master_pin(p1)
        self._build_main()

    # ---- 登录 ----
    def _build_login(self):
        self._clear_root()
        pad = {"padx": 12, "pady": 6}
        tk.Label(self.root, text="登录", font=("Microsoft YaHei", 15, "bold")).pack(anchor="w", **pad)
        tk.Label(self.root, text="请输入登录 PIN 以打开控制面板。", fg="#666").pack(anchor="w", **pad)
        self._pin_entry = tk.Entry(self.root, show="•", font=("Consolas", 14))
        self._pin_entry.pack(anchor="w", **pad)
        self._pin_err = tk.Label(self.root, text="", fg="#cc0000")
        self._pin_err.pack(anchor="w", **pad)
        tk.Button(self.root, text="进入", command=self._on_login).pack(anchor="w", **pad)
        self._pin_entry.bind("<Return>", lambda e: self._on_login())
        self._pin_entry.focus_set()

    def _on_login(self):
        pin = self._pin_entry.get().strip()
        if verify_master_pin(pin):
            self._build_main()
        else:
            self._pin_err.config(text="PIN 错误,请重试")
            self._pin_entry.delete(0, "end")

    # ---- 主面板(登录后) ----
    def _build_main(self):
        self._clear_root()
        pad = {"padx": 12, "pady": 4}

        tk.Label(self.root, text="手机触控板", font=("Microsoft YaHei", 16, "bold")).pack(anchor="w", **pad)
        self.info_var = tk.StringVar()
        self.info_var.set(
            f"本机地址  {self.ip}:{self.port}    状态:{'已配对' if self.paired else '未配对'}"
        )
        tk.Label(self.root, textvariable=self.info_var, fg="#336699").pack(anchor="w", **pad)

        # 状态行
        self.status_var = tk.StringVar()
        self.status_var.set("等待手机连接…")
        tk.Label(self.root, textvariable=self.status_var, font=("Microsoft YaHei", 11)).pack(anchor="w", **pad)

        # 手机媒体开关:在主面板就能开/关手机摄像头、麦克风(开=绿字,关=灰字)
        med_row = tk.Frame(self.root)
        med_row.pack(anchor="w", **pad)
        self.cam_btn = tk.Button(
            med_row, text="摄像头:关", width=11, font=("Microsoft YaHei", 10),
            command=lambda: self._media_toggle("cam"))
        self.mic_btn = tk.Button(
            med_row, text="麦克风:关", width=11, font=("Microsoft YaHei", 10),
            command=lambda: self._media_toggle("mic"))
        self.float_btn = tk.Button(
            med_row, text="媒体悬浮窗", width=11, font=("Microsoft YaHei", 10),
            command=self._show_media_window)
        self.cam_btn.pack(side="left")
        self.mic_btn.pack(side="left", padx=6)
        self.float_btn.pack(side="left")

        # 配对码
        self.pin_var = tk.StringVar()
        self.pin_var.set("—")
        tk.Label(self.root, text="配对码(手机输这个):", fg="#666").pack(anchor="w", **pad)
        tk.Label(self.root, textvariable=self.pin_var, font=("Consolas", 30, "bold"), fg="#cc0000").pack(anchor="w", **pad)

        # 二维码 + 种子 + 恢复码(首次配对时显示)
        self.qr_label = tk.Label(self.root)
        self.qr_label.pack(**pad)
        self.secret_var = tk.StringVar()
        self.secret_var.set("")
        tk.Label(self.root, textvariable=self.secret_var, font=("Consolas", 9), fg="#333",
                 wraplength=400, justify="left").pack(anchor="w", **pad)
        self.recovery_var = tk.StringVar()
        self.recovery_var.set("")
        tk.Label(self.root, textvariable=self.recovery_var, font=("Consolas", 12, "bold"),
                 fg="#008800", wraplength=400, justify="left").pack(anchor="w", **pad)

        # 日志
        tk.Label(self.root, text="日志:", fg="#666").pack(anchor="w", **pad)
        self.log = scrolledtext.ScrolledText(self.root, height=10, state="disabled", wrap="word")
        self.log.pack(fill="both", expand=True, **pad)

        # 登录后才启动监听
        threading.Thread(target=serve, args=(self.port,), daemon=True).start()

    def _poll(self):
        while True:
            try:
                evt = gui_queue.get_nowait()
            except _queue_mod.Empty:
                break
            self._handle(evt)
        self._refresh_media_ui()
        self.root.after(100, self._poll)

    # ---- 主面板上的手机媒体开关 ----
    def _media_state(self):
        video = audio = False
        if media is not None:
            try:
                video, audio = media.media_status()
            except Exception:
                video = audio = False
        return video, audio

    def _refresh_media_ui(self):
        if not hasattr(self, "cam_btn"):
            return
        video, audio = self._media_state()
        self.cam_btn.config(
            text="摄像头:开" if video else "摄像头:关",
            fg="#2e7d32" if video else "#333333")
        self.mic_btn.config(
            text="麦克风:开" if audio else "麦克风:关",
            fg="#2e7d32" if audio else "#333333")

    def _media_toggle(self, kind):
        video, audio = self._media_state()
        if kind == "cam":
            n = send_ctrl_line("CMD CAM 0" if video else "CMD CAM 1")
        else:
            n = send_ctrl_line("CMD MIC 0" if audio else "CMD MIC 1")
        if n == 0:
            self.status_var.set("手机还没连上 —— 先让手机连接,再点摄像头/麦克风开关")

    def _show_media_window(self):
        """「媒体悬浮窗」被点 X 关掉后,从这里把它再调出来。"""
        if MediaStatusWindow._instance is not None:
            MediaStatusWindow._instance.show()
        else:
            import tkinter as tk
            MediaStatusWindow(tk.Toplevel(self.root))

    def _handle(self, evt):
        kind = evt["kind"]
        if kind == "pairing":
            self.pin_var.set(evt["pin"])
            self.status_var.set("配对码已生成,请在手机端输入")
        elif kind == "setup":
            secret = evt["secret"]
            recovery = evt["recovery"]
            self.status_var.set("首次配对:手机扫码或输入下方种子")
            grouped = "-".join(secret[i:i + 4] for i in range(0, len(secret), 4))
            self.secret_var.set(f"种子: {grouped}")
            self.recovery_var.set(f"恢复码(保存好,手机丢失/卸载时用): {recovery}")
            b64 = make_qr_png_b64(secret)
            if b64:
                try:
                    self._qr_photo = tk.PhotoImage(data=b64)
                    self.qr_label.config(image=self._qr_photo, text="")
                except Exception:
                    self.qr_label.config(image="", text="(二维码生成失败,请手动输入种子)")
            else:
                self.qr_label.config(image="", text="(未装 segno,请手动输入种子)")
        elif kind == "connected":
            self.status_var.set("已连接,正在控制鼠标")
            self.pin_var.set("—")
            self._qr_photo = None
            self.qr_label.config(image="", text="")
            self.secret_var.set("")
            self.recovery_var.set("")
        elif kind == "disconnected":
            if self.status_var.get().startswith("已连接"):
                self.status_var.set("已断开,等待手机连接…")
        elif kind == "log":
            self._log(evt["msg"])

    def _log(self, msg):
        self.log.config(state="normal")
        self.log.insert("end", f"{msg}\n")
        self.log.see("end")
        self.log.config(state="disabled")


# ---------------------------------------------------------------------------
# 后台静默模式(开机自启用):根窗口隐藏,serve 启动即监听。
# 手机连入需要配对码时,瞬态弹一个小窗显示;手机输对连上后小窗自动消失。
# ---------------------------------------------------------------------------
class BackgroundHost:
    """后台宿主:无常驻窗口、无登录。配对码/首次配对种子只在需要时瞬态弹出。"""

    _POLL_MS = 150
    _PIN_MS = (PIN_TTL + 3) * 1000     # 配对码 120s 有效,多留 3s 缓冲后自动关窗
    _SETUP_MS = 180 * 1000             # 首次配对种子窗口:给足抄写时间

    def __init__(self, root, ip, port, paired):
        self.root = root
        self.ip = ip
        self.port = port
        self._pair_win = None
        self._pair_after = None
        self._setup_win = None
        self._setup_after = None
        # 媒体面板:手机一开始推流就自动弹,停流自动收;也可经本地 9530 命令唤出
        self._console = None
        self._was_media = False     # 上一轮是否有媒体在推(用于检测「重新开始推流」)
        self._was_active = False
        self._off_ticks = 0         # 连续无推流轮数
        self._manual = False        # 用户主动唤出过:不因停流自动收起
        self._user_hidden = False   # 用户点了 X:本轮推流内不再自动弹
        self._frame = None          # 最新一帧 JPEG(供预览,仅在面板可见时解码)
        self._last_vs = -1
        try:
            root.title("手机触控板(后台)")
        except Exception:
            pass
        root.withdraw()  # 隐藏根窗口:整个服务没有任何常驻窗口
        # 端口一启动就监听,不需要登录
        threading.Thread(target=serve, args=(port,), daemon=True).start()
        # 本地回环命令口:手机媒体面板.bat 通过它把面板唤出来
        threading.Thread(target=self._ipc_listener, daemon=True).start()
        root.after(self._POLL_MS, self._poll)

    def _ipc_listener(self):
        """仅监听 127.0.0.1:9530,收到一行 SHOW 就唤出媒体面板(局域网不可达,无安全风险)。"""
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        try:
            s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            s.bind(("127.0.0.1", MEDIA_IPC_PORT))
            s.listen(2)
            s.settimeout(1.0)
        except Exception:
            try:
                s.close()
            except Exception:
                pass
            return
        while True:
            try:
                conn, _ = s.accept()
                try:
                    data = conn.recv(64).decode("utf-8", "replace").strip().upper()
                    if data == "SHOW":
                        gui_queue.put({"kind": "console_show"})
                except Exception:
                    pass
                try:
                    conn.close()
                except Exception:
                    pass
            except socket.timeout:
                continue
            except Exception:
                try:
                    time.sleep(0.2)
                except Exception:
                    pass

    # ---- 事件轮询 ----
    def _poll(self):
        while True:
            try:
                evt = gui_queue.get_nowait()
            except _queue_mod.Empty:
                break
            self._handle(evt)
        self._tick_media()
        self.root.after(self._POLL_MS, self._poll)

    def _handle(self, evt):
        kind = evt["kind"]
        if kind == "pairing":
            self._close_windows()
            self._show_code(evt["pin"])
        elif kind == "setup":
            self._close_windows()
            self._show_setup(evt["secret"], evt["recovery"])
        elif kind == "connected":
            # 连上了:关掉配对码/种子小窗,恢复"无窗口"状态
            self._close_windows()
        elif kind == "console_show":
            # 手机媒体面板.bat 唤出:主动展示并保持(直到用户点 X 收起)
            self._manual = True
            self._show_console()

    # ---- 媒体面板自动弹/收 + 预览/电平刷新(在 Tk 主线程轮询) ----
    def _tick_media(self):
        video = audio = False
        mic_level = 0.0
        if media is not None:
            try:
                video, audio = media.media_status()
            except Exception:
                video = audio = False
            try:
                serial, jpeg = media.latest_video()
                if serial != self._last_vs:
                    self._last_vs = serial
                    self._frame = jpeg
            except Exception:
                pass
            try:
                mic_level = media.audio_level()
            except Exception:
                mic_level = 0.0

        active = bool(video or audio)
        rise = active and not self._was_active
        self._was_active = active

        if active:
            self._off_ticks = 0
            # 重新开始推流:若之前被用户收起,这轮可以再自动弹出来
            if rise and self._user_hidden:
                self._user_hidden = False
                self._show_console()
            # 平时:没人收起就一直亮着;被收起后不再弹,等下一次 rise
            if not self._user_hidden and (self._console is None or not self._console.visible()):
                self._show_console()
        else:
            self._off_ticks += 1
            # 非手动唤出:停流约 1.2 秒后自动收起(约 8 轮)
            if self._off_ticks > 8 and not self._manual:
                if self._console is not None and self._console.visible():
                    self._console.hide()

        if self._console is not None and self._console.visible():
            if video and self._frame is not None:
                self._console.show_frame(self._frame)
            elif not video:
                self._console.clear_preview()
            self._console.refresh(video, audio, mic_level)

    def _show_console(self):
        try:
            import tkinter as tk
        except Exception:
            return
        if self._console is None:
            try:
                self._console = MediaConsole(tk.Toplevel(self.root))
                self._console.close_cb = self._on_console_closed
            except Exception:
                return
        try:
            self._console.show()
        except Exception:
            pass

    def _on_console_closed(self):
        """用户点 X 收起媒体面板:本轮推流内不再自动弹;手动唤出状态也取消。"""
        self._user_hidden = True
        self._manual = False

    def _close_windows(self):
        if self._pair_after:
            try:
                self.root.after_cancel(self._pair_after)
            except Exception:
                pass
            self._pair_after = None
        if self._pair_win is not None:
            try:
                self._pair_win.destroy()
            except Exception:
                pass
            self._pair_win = None
        if self._setup_after:
            try:
                self.root.after_cancel(self._setup_after)
            except Exception:
                pass
            self._setup_after = None
        if self._setup_win is not None:
            try:
                self._setup_win.destroy()
            except Exception:
                pass
            self._setup_win = None

    def _center(self, w):
        try:
            self.root.update_idletasks()
            sx, sy = self.root.winfo_screenwidth(), self.root.winfo_screenheight()
            ww, wh = w.winfo_reqwidth(), w.winfo_reqheight()
            w.geometry("+%d+%d" % (max(0, (sx - ww) // 2), max(0, (sy - wh) // 3)))
        except Exception:
            pass

    def _show_code(self, pin):
        import tkinter as tk
        w = tk.Toplevel(self.root)
        try:
            w.title("手机触控板 — 配对")
            w.attributes("-topmost", True)
            w.configure(bg="#1e1e1e")
        except Exception:
            pass
        tk.Label(
            w, text="手机正在连接 —— 请在手机上输入配对码:",
            bg="#1e1e1e", fg="#e8e8e8", font=("Microsoft YaHei", 11)
        ).pack(padx=24, pady=(18, 4))
        tk.Label(
            w, text=pin, bg="#1e1e1e", fg="#ff5252",
            font=("Consolas", 30, "bold")
        ).pack(pady=4)
        tk.Label(
            w, text="(手机输对连上后,此窗自动关闭;120 秒内有效)",
            bg="#1e1e1e", fg="#9aa0a6", font=("Microsoft YaHei", 9)
        ).pack(padx=24, pady=(2, 14))
        self._pair_win = w
        self._center(w)
        self._pair_after = self.root.after(self._PIN_MS, self._close_windows)

    def _show_setup(self, secret, recovery):
        import tkinter as tk
        grouped = "-".join(secret[i:i + 4] for i in range(0, len(secret), 4))
        w = tk.Toplevel(self.root)
        try:
            w.title("手机触控板 — 首次配对")
            w.attributes("-topmost", True)
            w.configure(bg="#1e1e1e")
        except Exception:
            pass
        tk.Label(
            w, text="首次配对 —— 用手机「扫描」连接后,在电脑下方提示处手动输入种子:",
            bg="#1e1e1e", fg="#e8e8e8", font=("Microsoft YaHei", 11), wraplength=380, justify="left"
        ).pack(padx=22, pady=(16, 6))
        tk.Label(
            w, text="种子: %s" % grouped, bg="#1e1e1e", fg="#ffb74d",
            font=("Consolas", 12), wraplength=400, justify="left"
        ).pack(padx=22, pady=4)
        tk.Label(
            w, text="恢复码(保存好,手机丢失/卸载时用): %s" % recovery,
            bg="#1e1e1e", fg="#66bb6a", font=("Consolas", 11), wraplength=400, justify="left"
        ).pack(padx=22, pady=4)
        tk.Label(
            w, text="(配完连上后此窗自动关闭)",
            bg="#1e1e1e", fg="#9aa0a6", font=("Microsoft YaHei", 9)
        ).pack(pady=(4, 12))
        self._setup_win = w
        self._center(w)
        self._setup_after = self.root.after(self._SETUP_MS, self._close_windows)


# ---------------------------------------------------------------------------
# 状态悬浮窗:手机摄像头/麦克风推流状态(绿灯=推流中,红灯=空闲)
# ---------------------------------------------------------------------------
class MediaStatusWindow:
    """置顶小窗:显示并反向控制手机摄像头/麦克风。点图标开关,绿灯=推流中,红灯+斜杠=关。"""

    _POLL_MS = 400
    _instance = None  # 便于主面板「媒体悬浮窗」按钮把它重新调出来

    def __init__(self, win):
        import tkinter as tk
        MediaStatusWindow._instance = self
        self.win = win
        try:
            win.title("超级终端")
            win.attributes("-topmost", True)
        except Exception:
            pass
        try:
            win.geometry("216x120+24+24")
        except Exception:
            pass
        try:
            win.configure(bg="#1e1e1e")
        except Exception:
            pass
        try:
            tk.Label(
                win, text="手机媒体  ·  点图标开/关",
                bg="#1e1e1e", fg="#9aa0a6", font=("Microsoft YaHei", 9)
            ).pack()
        except Exception:
            pass
        row = tk.Frame(win, bg="#1e1e1e")
        row.pack()
        self.cam = self._make_cell(row, "摄像头", "cam")
        self.mic = self._make_cell(row, "麦克风", "mic")
        self._render(self.cam, "cam", False)
        self._render(self.mic, "mic", False)
        # 点右上角 X 只隐藏,不销毁;随时可从主面板「媒体悬浮窗」按钮再调出来
        try:
            win.protocol("WM_DELETE_WINDOW", self._hide)
        except Exception:
            pass
        win.after(self._POLL_MS, self._poll)

    def _make_cell(self, parent, caption, kind):
        import tkinter as tk
        cell = tk.Frame(parent, bg="#1e1e1e")
        c = tk.Canvas(cell, width=72, height=62, bg="#1e1e1e",
                      highlightthickness=0, cursor="hand2")
        c.pack()
        c.bind("<Button-1>", lambda e, k=kind: self._toggle(k))
        try:
            tk.Label(cell, text=caption, bg="#1e1e1e", fg="#d6d6d6",
                     font=("Microsoft YaHei", 9)).pack()
        except Exception:
            pass
        cell.pack(side="left", padx=8)
        return c

    def _hide(self):
        try:
            self.win.withdraw()
        except Exception:
            pass

    def show(self):
        """把悬浮窗重新调到前台(主面板「媒体悬浮窗」按钮调用)。"""
        try:
            self.win.deiconify()
            self.win.lift()
            self.win.attributes("-topmost", True)
        except Exception:
            pass

    @staticmethod
    def _render(c, kind, active):
        c.delete("all")
        line = "#d6d6d6"
        if kind == "cam":
            c.create_rectangle(14, 28, 58, 58, outline=line, width=2)
            c.create_oval(24, 34, 48, 58, outline=line, width=2)
            c.create_oval(30, 40, 37, 47, fill=line, outline="")
        else:
            c.create_oval(29, 12, 43, 34, outline=line, width=2)
            c.create_line(36, 34, 36, 50, fill=line, width=2)
            c.create_arc(26, 42, 46, 62, start=0, extent=180, style="arc", outline=line, width=2)
        dot = "#2ecc40" if active else "#e53e3e"
        c.create_oval(50, 8, 66, 24, fill=dot, outline="")
        if not active:
            # 关闭状态:图标上叠一条红色斜杠,直观表示「已关」
            c.create_line(13, 60, 59, 12, fill="#e53e3e", width=3)

    def _toggle(self, kind):
        video = audio = False
        if media is not None:
            try:
                video, audio = media.media_status()
            except Exception:
                video = audio = False
        if kind == "cam":
            send_ctrl_line("CMD CAM 0" if video else "CMD CAM 1")
        else:
            send_ctrl_line("CMD MIC 0" if audio else "CMD MIC 1")

    def _poll(self):
        video = audio = False
        if media is not None:
            try:
                video, audio = media.media_status()
            except Exception:
                video = audio = False
        self._render(self.cam, "cam", video)
        self._render(self.mic, "mic", audio)
        self.win.after(self._POLL_MS, self._poll)


# ---------------------------------------------------------------------------
# 手机媒体面板(后台模式用):手机一开始推流就自动弹出,关停则自动收起。
# 在电脑上实时看到手机摄像头画面、验证/开关麦克风,不必开任何别的软件。
# ---------------------------------------------------------------------------
class MediaConsole:
    """置顶小面板:手机摄像头画面预览 + 摄像头/麦克风快捷开关 + 麦克风电平表。

    「一窗两档」:完整面板(标题/开关/电平/提示)可一键切成「纯画面小窗」——
    只剩画面、没有按钮和文字,按住可拖动,双击在小窗↔大画面间切换,右键菜单
    可回完整面板或收起。纯画面模式用 overrideredirect 去掉系统边框,画面即窗口。
    由 BackgroundHost 决定何时 show()/hide();本类只负责画界面和反向开关。
    """

    _POLL_PREVIEW = True   # 是否实时刷新预览画面
    _SW, _SH = 398, 200    # 完整面板预览画布大小(像素)
    _SMALL = (420, 316)    # 纯画面·小窗画布(4:3)
    _LARGE = (960, 720)    # 纯画面·大画面画布

    def __init__(self, win):
        import tkinter as tk
        try:
            from PIL import Image, ImageTk
            self._Image, self._ImageTk = Image, ImageTk
        except Exception:
            self._Image = self._ImageTk = None
        self.win = win
        self._shown = False
        self._compact = False          # 是否处于「纯画面小窗」档
        self._full_geom = None         # 进纯画面前完整面板的几何(退出时恢复)
        self._cw, self._ch = self._SW, self._SH   # 当前预览画布像素尺寸
        self._drag = None              # 拖动用 (按下时x_root, y_root, 窗口x, 窗口y)
        self._hint_due = 0.0           # 临时操作提示显示到此刻(进纯画面/切大小后几秒)
        self._photo = None
        self._lvl_disp = 0.0
        self._note = None
        self._placeholder_note = None  # 兼容旧属性名,保留引用
        self.close_cb = None           # 由宿主设置:用户点 X 收起面板时通知
        try:
            win.title("手机媒体面板")
            win.configure(bg="#1e1e1e")
            win.attributes("-topmost", True)
        except Exception:
            pass
        try:
            win.geometry("+%d+%d" % (self._right_x(), 60))
        except Exception:
            pass

        # ---- 完整面板内容;._full 记录每个顶层子件的 pack 选项,便于「纯画面」切走再切回 ----
        self._full = []
        def pk(widget, **kw):
            widget.pack(**kw)
            self._full.append((widget, kw))

        # 标题行(标题/预览/电平/图标/提示 全部记进 ._full,纯画面档一键收走、退出再摆回)
        self._t1 = tk.Label(win, text="手机摄像头 / 麦克风", bg="#1e1e1e", fg="#e8e8e8",
                            font=("Microsoft YaHei", 12, "bold"))
        pk(self._t1, pady=(10, 0))
        self._t2 = tk.Label(win, text="手机端开摄像头/麦克风时此面板自动弹出 · 点「纯画面」去按钮只看画面",
                            bg="#1e1e1e", fg="#9aa0a6", font=("Microsoft YaHei", 9))
        pk(self._t2, pady=(0, 6))

        # 预览画布(完整面板与纯画面共用同一块画布,切档时只改尺寸/位置)
        self.preview = tk.Canvas(win, width=self._SW, height=self._SH, bg="#000000",
                                 highlightthickness=0)
        pk(self.preview, padx=12, pady=2)
        self._draw_placeholder("摄像头未推流 —— 点下方图标打开")

        # 麦克风电平条
        lvl_row = tk.Frame(win, bg="#1e1e1e")
        pk(lvl_row, fill="x", padx=14, pady=(6, 0))
        tk.Label(lvl_row, text="麦克风电平", bg="#1e1e1e", fg="#b0b6bc",
                 font=("Microsoft YaHei", 9)).pack(side="left")
        self.lvl_canvas = tk.Canvas(lvl_row, width=230, height=12, bg="#2a2a2a",
                                    highlightthickness=0)
        self.lvl_canvas.pack(side="right")

        # 图标行:摄像头 / 麦克风 点击开关 + 纯画面切换
        icon_row = tk.Frame(win, bg="#1e1e1e")
        pk(icon_row, pady=(8, 0))
        self.cam_canvas = self._make_icon(icon_row, "摄像头", "cam")
        self.mic_canvas = self._make_icon(icon_row, "麦克风", "mic")
        MediaStatusWindow._render(self.cam_canvas, "cam", False)
        MediaStatusWindow._render(self.mic_canvas, "mic", False)
        self.compact_btn = tk.Button(
            icon_row, text="纯画面", bg="#2a2a2a", fg="#9fd3ff", relief="flat",
            activebackground="#333333", activeforeground="#cfe8ff", cursor="hand2",
            bd=0, padx=12, pady=10, font=("Microsoft YaHei", 9),
            command=self._enter_compact)
        self.compact_btn.pack(side="left", padx=14)

        # 提示行
        self._tip1 = tk.Label(win, text="在别的软件里选:摄像头 = OBS Virtual Camera · 麦克风 = CABLE Input",
                              bg="#1e1e1e", fg="#8f9aa6", font=("Microsoft YaHei", 9))
        pk(self._tip1, pady=(6, 0))
        self._tip2 = tk.Label(win, text="设系统默认:Windows 设置 → 系统 → 声音 → 输入(选 CABLE Input);相机同理",
                              bg="#1e1e1e", fg="#8f9aa6", font=("Microsoft YaHei", 9))
        pk(self._tip2, pady=(0, 10))

        # 画布交互:纯画面档拖动 / 双击切大小 / 右键菜单(完整档里这些回调都直接忽略)
        self.preview.bind("<Button-1>", self._on_press)
        self.preview.bind("<B1-Motion>", self._on_drag)
        self.preview.bind("<Double-Button-1>", self._on_dbl)
        self.preview.bind("<Button-3>", self._on_menu)
        self._ctx = tk.Menu(win, tearoff=0)

        # X = 收起(不销毁,宿主仍能把它再调出来)
        try:
            win.protocol("WM_DELETE_WINDOW", self._on_x)
        except Exception:
            pass

    def _right_x(self):
        try:
            return max(0, self.win.winfo_screenwidth() - 440)
        except Exception:
            return 60

    def _make_icon(self, parent, caption, kind):
        import tkinter as tk
        cell = tk.Frame(parent, bg="#1e1e1e")
        c = tk.Canvas(cell, width=72, height=62, bg="#1e1e1e", highlightthickness=0,
                      cursor="hand2")
        c.pack()
        c.bind("<Button-1>", lambda e, k=kind: self._toggle(k))
        tk.Label(cell, text=caption, bg="#1e1e1e", fg="#d6d6d6",
                 font=("Microsoft YaHei", 9)).pack()
        cell.pack(side="left", padx=12)
        return c

    # ---- 显示 / 隐藏 ----
    def visible(self):
        return self._shown

    def show(self):
        self._shown = True
        try:
            self.win.deiconify()
            self.win.lift()
            self.win.attributes("-topmost", True)
        except Exception:
            pass

    def hide(self):
        self._shown = False
        try:
            self.win.withdraw()
        except Exception:
            pass

    def _on_x(self):
        # 收起:若是纯画面档,先还原成完整面板,下次自动弹出/手动唤出还是那个带控制的完整面板
        if self._compact:
            self._exit_compact()
        self._shown = False
        try:
            self.win.withdraw()
        except Exception:
            pass
        if self.close_cb:
            try:
                self.close_cb()
            except Exception:
                pass

    # ---- 纯画面小窗(一窗两档) ----
    def _enter_compact(self):
        if self._compact:
            return
        self._compact = True
        try:
            self._full_geom = self.win.geometry()   # 记住完整面板形状,退出时还原
        except Exception:
            self._full_geom = None
        # 只留画布,其余全收走
        for w, _ in self._full:
            try:
                w.pack_forget()
            except Exception:
                pass
        self._note = None
        self._compact_layout(self._SMALL)
        self._shuffle_border(True)
        self._hint_due = time.time() + 4.0
        try:
            self.win.update_idletasks()
        except Exception:
            pass

    def _exit_compact(self):
        if not self._compact:
            return
        self._compact = False
        try:
            self.preview.pack_forget()
        except Exception:
            pass
        self._shuffle_border(False)
        if self._full_geom:
            try:
                self.win.geometry(self._full_geom)
            except Exception:
                pass
        self._full_geom = None
        self._set_canvas((self._SW, self._SH))
        for w, kw in self._full:     # 按原顺序原参数摆回完整面板
            try:
                w.pack(**kw)
            except Exception:
                pass
        self._note = None
        try:
            self.win.update_idletasks()
            self.win.deiconify()
            self.win.lift()
        except Exception:
            pass

    def _shuffle_border(self, borderless):
        """切系统边框必须把窗口先收起来再改,否则某些 Windows 版本不生效。"""
        try:
            self.win.withdraw()
            self.win.overrideredirect(borderless)
            self.win.deiconify()
        except Exception:
            pass

    def _set_canvas(self, size):
        self._cw, self._ch = size
        try:
            self.preview.configure(width=self._cw, height=self._ch)
        except Exception:
            pass

    def _compact_layout(self, size):
        """纯画面档:只把画布摆上窗口,窗口自动缩成画布大小并尽量留在屏幕内。"""
        self._set_canvas(size)
        try:
            self.preview.pack(fill="none", expand=False)
        except Exception:
            pass
        w, h = size
        try:
            sx, sy = self.win.winfo_screenwidth(), self.win.winfo_screenheight()
            cx, cy = self.win.winfo_x(), self.win.winfo_y()
            x = max(0, min(cx, sx - w - 4))
            y = max(0, min(cy, sy - h - 4))
            self.win.geometry("%dx%d+%d+%d" % (w, h, x, y))
        except Exception:
            pass

    def _toggle_compact_size(self):
        if not self._compact:
            return
        self._compact_layout(self._LARGE if (self._cw, self._ch) != self._LARGE else self._SMALL)
        self._hint_due = time.time() + 3.0

    # 拖动 / 双击 / 右键(仅纯画面档生效)
    def _on_press(self, evt):
        if not self._compact:
            return
        self._drag = (evt.x_root, evt.y_root, self.win.winfo_x(), self.win.winfo_y())

    def _on_drag(self, evt):
        if not self._compact or self._drag is None:
            return
        ox, oy, wx, wy = self._drag
        try:
            self.win.geometry("+%d+%d" % (wx + evt.x_root - ox, wy + evt.y_root - oy))
        except Exception:
            pass

    def _on_dbl(self, _evt=None):
        if not self._compact:
            return
        self._toggle_compact_size()

    def _on_menu(self, evt):
        if not self._compact:
            return
        m = self._ctx
        m.delete(0, "end")
        large = (self._cw, self._ch) == self._LARGE
        m.add_command(label="切换为" + ("小窗" if large else "大画面"), command=self._toggle_compact_size)
        m.add_command(label="回到完整面板", command=self._exit_compact)
        m.add_separator()
        m.add_command(label="收起面板", command=self._on_x)
        try:
            m.tk_popup(evt.x_root, evt.y_root)
        finally:
            try:
                m.grab_release()
            except Exception:
                pass

    def _hint_text(self):
        if (self._cw, self._ch) == self._LARGE:
            return "双击缩回小窗 · 按住拖动 · 右键菜单"
        return "纯画面小窗 · 双击放大 · 按住拖动 · 右键菜单"

    # ---- 反向开关(给手机下发 CMD) ----
    def _toggle(self, kind):
        video = audio = False
        if media is not None:
            try:
                video, audio = media.media_status()
            except Exception:
                video = audio = False
        if kind == "cam":
            n = send_ctrl_line("CMD CAM 0" if video else "CMD CAM 1")
        else:
            n = send_ctrl_line("CMD MIC 0" if audio else "CMD MIC 1")
        if n == 0:
            self._flash("手机没连上,无法开关 —— 先在手机上连接配对")
        else:
            self._flash("已向手机发送「%s」开关指令" % ("关摄像头" if kind == "cam" and video else "开摄像头"
                                                    if kind == "cam" else "关麦克风" if audio else "开麦克风"))

    def _flash(self, text):
        """在图标行下方短暂提示一句。"""
        import tkinter as tk
        if self._note is None:
            self._note = tk.Label(self.win, text=text, bg="#1e1e1e", fg="#ffb74d",
                                  font=("Microsoft YaHei", 9))
            self._note.pack(pady=(2, 0))
        else:
            self._note.configure(text=text)
        try:
            if hasattr(self, "_flash_after") and self._flash_after:
                self.win.after_cancel(self._flash_after)
        except Exception:
            pass

        def _clear():
            if self._note is not None:
                try:
                    self._note.configure(text="")
                except Exception:
                    pass

        try:
            self._flash_after = self.win.after(2600, _clear)
        except Exception:
            pass

    # ---- 每帧刷新(宿主在 Tk 主线程轮询时调用) ----
    def refresh(self, video, audio, mic_level):
        # 图标状态(纯画面档时图标在隐藏区,画了也不可见,无妨)
        try:
            MediaStatusWindow._render(self.cam_canvas, "cam", video)
            MediaStatusWindow._render(self.mic_canvas, "mic", audio)
        except Exception:
            pass
        # 电平条:平滑显示,静音缓慢回落
        target = min(1.0, max(0.0, mic_level))
        if target > self._lvl_disp:
            self._lvl_disp = target
        else:
            self._lvl_disp = max(0.0, self._lvl_disp - 0.012)
        try:
            self.lvl_canvas.delete("all")
            w = int(230 * self._lvl_disp)
            if w > 0:
                color = "#2ecc40" if self._lvl_disp < 0.7 else "#ffb74d"
                self.lvl_canvas.create_rectangle(0, 0, w, 12, fill=color, outline="")
        except Exception:
            pass

    def show_frame(self, jpeg):
        """把最新一帧 JPEG 画进预览(仅在推流时由宿主调用)。"""
        if not self._shown or self._Image is None or self._ImageTk is None:
            return
        try:
            img = self._Image.open(io.BytesIO(jpeg))
            img = img.convert("RGB")
        except Exception:
            return
        try:
            w, h = img.size
            if w <= 0 or h <= 0:
                return
            cw = max(self._cw, 8)
            ch = max(self._ch, 8)
            scale = min((cw - 2) / float(w), (ch - 2) / float(h))
            if scale < 1.0 or self._compact:   # 纯画面档允许放大填满;完整档只缩小不放大
                img = img.resize((max(1, int(w * scale)), max(1, int(h * scale))))
            self._photo = self._ImageTk.PhotoImage(img)
            self.preview.delete("all")
            self.preview.create_image(cw // 2, ch // 2, image=self._photo)
            if self._compact and time.time() < self._hint_due:
                self.preview.create_text(cw // 2, 14, text=self._hint_text(),
                                         fill="#dff3ff", font=("Microsoft YaHei", 9))
        except Exception:
            pass

    def clear_preview(self):
        try:
            self.preview.delete("all")
            self._draw_placeholder("摄像头未推流 —— 点上方图标打开")
        except Exception:
            pass

    def _draw_placeholder(self, text):
        try:
            self.preview.delete("all")
            self.preview.create_text(max(self._cw // 2, 2), max(self._ch // 2, 2), text=text,
                                     fill="#55616e", font=("Microsoft YaHei", 10))
        except Exception:
            pass

# ---------------------------------------------------------------------------
# 主入口
# ---------------------------------------------------------------------------
def get_lan_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))
        return s.getsockname()[0]
    except Exception:
        return "127.0.0.1"
    finally:
        s.close()


def serve(port):
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    # 独占端口:Windows 上 SO_REUSEADDR 会让第二个实例也能绑定同一端口,
    # 造成两个电脑端都监听、各自弹配对码、手机连上后互相抢连接。
    # 用 SO_EXCLUSIVEADDRUSE 强制独占,第二个实例 bind 失败即退出,杜绝重复启动。
    if hasattr(socket, "SO_EXCLUSIVEADDRUSE"):
        server.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
    try:
        server.bind(("0.0.0.0", port))
    except OSError:
        try:
            print("!! 端口 %d 已被占用,电脑端已在运行,本次不再监听" % port, file=sys.stderr)
        except Exception:
            pass
        _log_line("!! 端口 %d 已被占用,本次不再监听" % port)
        if GUI_MODE or BACKGROUND_MODE:
            # GUI/后台模式下 bind 失败=已有实例在跑;直接退出整个进程,避免留下一个
            # 「配对码永远是 —」的假面板 / 一个不监听的幽灵后台进程。
            os._exit(1)
        raise
    server.listen(5)
    # 成功独占端口后才开始广播,避免第二个实例也广播让手机看到多个电脑端
    threading.Thread(target=discovery_beacon, args=(port,), daemon=True).start()
    while True:
        conn, addr = server.accept()
        threading.Thread(target=handle_client, args=(conn, addr), daemon=True).start()


def discovery_beacon(port):
    """向局域网广播「我是手机触控板电脑端」,手机扫描端口 DISCOVERY_PORT 即可发现。"""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    payload = json.dumps({"app": "phone-touchpad", "port": port}).encode("utf-8")
    while True:
        try:
            s.sendto(payload, ("255.255.255.255", DISCOVERY_PORT))
        except Exception:
            pass
        time.sleep(BEACON_INTERVAL)


def _autostart_lnk_path():
    appdata = os.environ.get("APPDATA", "")
    return os.path.join(
        appdata, r"Microsoft\Windows\Start Menu\Programs\Startup",
        "phone-touchpad-bg.lnk",
    )


def _pythonw_path():
    exe = sys.executable or "pythonw.exe"
    if exe.lower().endswith("python.exe"):
        exe = exe[:-len("python.exe")] + "pythonw.exe"
    return exe


def install_autostart():
    """在 Windows「启动」文件夹建快捷方式:开机自动后台运行 server.py --background。"""
    lnk = _autostart_lnk_path()
    script = os.path.abspath(__file__)
    exe = _pythonw_path()
    wdir = os.path.dirname(script)
    if not os.path.exists(exe):
        print("找不到 pythonw: %s" % exe)
        return 1
    ps = (
        "$ws=New-Object -ComObject WScript.Shell;"
        "$s=$ws.CreateShortcut('%s');" % lnk
        + "$s.TargetPath='%s';" % exe
        + "$s.Arguments='\"%s\" --background';" % script
        + "$s.WorkingDirectory='%s';" % wdir
        + "$s.WindowStyle=7;"
        + "$s.Save();"
        + "Write-Output 'created'"
    )
    try:
        out = subprocess.check_output(
            ["powershell", "-NoProfile", "-Command", ps],
            stderr=subprocess.STDOUT, text=True,
        )
    except Exception as e:
        print("创建开机自启失败: %s" % e)
        return 1
    print("开机自启已设置:")
    print("  快捷方式:", lnk)
    print("  目标:", exe, '"%s" --background' % script)
    return 0


def remove_autostart():
    lnk = _autostart_lnk_path()
    try:
        if os.path.exists(lnk):
            os.remove(lnk)
            print("已取消开机自启(删除了启动快捷方式)")
        else:
            print("本来就没有开机自启快捷方式")
    except Exception as e:
        print("取消失败: %s" % e)
        return 1
    return 0


def main():
    port = LISTEN_PORT_DEFAULT
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    if args:
        try:
            port = int(args[0])
        except ValueError:
            pass

    if "--install-autostart" in sys.argv:
        sys.exit(install_autostart())
    if "--remove-autostart" in sys.argv:
        sys.exit(remove_autostart())
    if "--summon-media" in sys.argv:
        # 手机媒体面板.bat 调用的客户端:让正在后台跑的服务弹出媒体面板
        try:
            s = socket.create_connection(("127.0.0.1", MEDIA_IPC_PORT), timeout=2)
            try:
                s.sendall(b"SHOW\n")
            finally:
                s.close()
        except Exception:
            pass
        return

    init_store()
    ip = get_lan_ip()
    paired = get_secret() is not None

    if BACKGROUND_MODE:
        # 后台静默:无常驻窗口、无登录,serve 启动即监听;配对码瞬态弹窗
        try:
            import tkinter as tk
        except Exception:
            tk = None
        if tk is not None:
            root = tk.Tk()
            BackgroundHost(root, ip, port, paired)
            root.mainloop()
        else:
            serve(port)  # 极端情况无 tkinter:纯后台监听,配对码只能靠日志
        return

    if GUI_MODE:
        import tkinter as tk
        root = tk.Tk()
        # 状态悬浮窗独立于登录面板,启动即显示(未登录/未连接时媒体灯全红)
        MediaStatusWindow(tk.Toplevel(root))
        GuiApp(root, ip, port, paired)  # 登录成功后在 _build_main 里才启动监听
        root.mainloop()
    else:
        print("=" * 60)
        print("  手机触控板 —— 电脑端服务 (无 GUI 模式 + 状态悬浮窗)")
        print(f"  本机 IP  : {ip}")
        print(f"  端口     : {port}")
        print(f"  配对状态 : {'已配对(有密钥)' if paired else '未配对'}")
        print("=" * 60)
        try:
            import tkinter as tk
        except Exception:
            tk = None
        if tk is not None:
            root = tk.Tk()
            MediaStatusWindow(root)  # root 即状态窗,关窗即退出
            threading.Thread(target=serve, args=(port,), daemon=True).start()
            root.mainloop()
        else:
            serve(port)


if __name__ == "__main__":
    main()
