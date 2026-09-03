# -*- coding: utf-8 -*-
"""
media.py —— 超级终端:手机摄像头/麦克风 -> 电脑虚拟摄像头/虚拟麦克风

原理:
  - 虚拟摄像头:手机推 JPEG 帧 -> pyvirtualcam 写入 OBS Virtual Camera(需装 OBS Studio)
  - 虚拟麦克风:手机推 PCM -> sounddevice 写入 VB-Cable 的 CABLE Input(需装 VB-Cable)

传输:独立 TCP 连接(复用 9527 端口),先发一行 `MEDIA <token>` 认证,之后是二进制帧:
  [1 字节 type][4 字节大端 length][length 字节 payload]
    type=1 视频(JPEG),type=2 音频(PCM16 单声道)

依赖(可选,缺了也不影响鼠标控制):
  numpy / pillow(已装)、pyvirtualcam、sounddevice
"""
import io
import os
import struct
import sys
import threading
import time

try:
    import numpy as np
except ImportError:
    np = None

try:
    import pyvirtualcam
except ImportError:
    pyvirtualcam = None

try:
    import sounddevice as sd
except ImportError:
    sd = None

try:
    from PIL import Image
except ImportError:
    Image = None

# PyInstaller 单文件打包后 __file__ 指向临时解包目录,日志要写到 exe 所在目录(与 server.py 一致)。
if getattr(sys, "frozen", False):
    HERE = os.path.dirname(os.path.abspath(sys.executable))
else:
    HERE = os.path.dirname(os.path.abspath(__file__))
LOG_PATH = os.path.join(HERE, "server.log")
_log_lock = threading.Lock()


def _log(msg):
    try:
        with _log_lock:
            with open(LOG_PATH, "a", encoding="utf-8") as fp:
                fp.write("%s [media] %s\n" % (time.strftime("%H:%M:%S"), msg))
    except Exception:
        pass


# ---- 可调参数 ----
# 与手机端采集档位保持一致(手机端 640x480 实测稳定 30fps,60 上不去,就都按 30)。
VIDEO_WIDTH = 640
VIDEO_HEIGHT = 480
VIDEO_FPS = 30
AUDIO_RATE = 16000
AUDIO_CHANNELS = 1

TYPE_VIDEO = 1
TYPE_AUDIO = 2
HEADER_LEN = 5          # 1 字节 type + 4 字节长度
MEDIA_TOKEN_TTL = 1800  # 媒体 token 有效期(秒);手机端掉线自动续连时常会复用旧 token,放宽避免续不上

# ---- 媒体 token(控制连接认证通过后签发,媒体连接凭它免重复认证) ----
_tokens = {}
_tokens_lock = threading.Lock()


def issue_token():
    """签发一个短期媒体 token(随机 128 位)。"""
    tok = os.urandom(16).hex()
    with _tokens_lock:
        _tokens[tok] = time.time() + MEDIA_TOKEN_TTL
    return tok


def check_token(tok):
    with _tokens_lock:
        exp = _tokens.get(tok)
        if exp is None:
            return False
        if time.time() > exp:
            _tokens.pop(tok, None)
            return False
        return True


# ---- 媒体活跃状态(供电脑端状态悬浮窗显示摄像头/麦克风是否在推流) ----
_video_last = 0.0
_audio_last = 0.0
_status_lock = threading.Lock()


def _touch(kind):
    global _video_last, _audio_last
    with _status_lock:
        if kind == TYPE_VIDEO:
            _video_last = time.time()
        elif kind == TYPE_AUDIO:
            _audio_last = time.time()


def media_status():
    """返回 (摄像头是否在推流, 麦克风是否在推流);最近 2 秒内有帧视为活跃。"""
    with _status_lock:
        now = time.time()
        return (now - _video_last < 2.0, now - _audio_last < 2.0)


def latest_video():
    """电脑端预览用:返回 (帧序号, 最新 JPEG);无帧时序号不变。"""
    return _video.latest()


def audio_level():
    """电脑端电平表用:最近一帧入麦音频的 RMS(0~1 左右,静音为 0)。"""
    try:
        return float(_audio.level)
    except Exception:
        return 0.0


# ---- 二进制 socket 读 ----
def _recv_exact(conn, n):
    buf = b""
    while len(buf) < n:
        try:
            chunk = conn.recv(n - len(buf))
        except Exception:
            return None
        if not chunk:
            return None
        buf += chunk
    return buf


def _recv_line(conn):
    """逐字节读一行(仅用于媒体连接的首行认证,量小,慢一点无妨)。"""
    line = b""
    while True:
        try:
            b = conn.recv(1)
        except Exception:
            return None
        if not b:
            return None if not line else line.decode("utf-8", "replace")
        if b == b"\n":
            return line.decode("utf-8", "replace")
        if b != b"\r":
            line += b


# ---- 视频 sink:最新一帧 + pyvirtualcam 推送循环 ----
class VideoSink:
    def __init__(self):
        self._latest = None
        self._serial = 0
        self._lock = threading.Lock()
        self._running = threading.Event()
        self.ready = False

    def push(self, jpeg: bytes):
        with self._lock:
            self._latest = jpeg
            self._serial += 1

    def latest(self):
        """返回 (递增序号, 最新一帧 JPEG);没帧时序号不变、返回 None。供电脑端预览用。"""
        with self._lock:
            return (self._serial, self._latest)

    def start(self):
        if pyvirtualcam is None or np is None or Image is None:
            _log("虚拟摄像头不可用(pyvirtualcam/numpy/PIL 缺失),先 pip install pyvirtualcam")
            return False
        try:
            self.cam = pyvirtualcam.Camera(
                width=VIDEO_WIDTH, height=VIDEO_HEIGHT, fps=VIDEO_FPS,
                fmt=pyvirtualcam.PixelFormat.RGB,
            )
        except Exception as e:
            _log("打开虚拟摄像头失败(装了 OBS 吗?): %r" % e)
            return False
        _log("虚拟摄像头已就绪: %s (%dx%d@%d)" % (self.cam.device, VIDEO_WIDTH, VIDEO_HEIGHT, VIDEO_FPS))
        self._running.set()
        threading.Thread(target=self._loop, daemon=True).start()
        self.ready = True
        return True

    def _loop(self):
        while self._running.is_set():
            with self._lock:
                jpeg = self._latest
            if jpeg is None:
                time.sleep(0.01)
                continue
            try:
                img = Image.open(io.BytesIO(jpeg)).convert("RGB")
                if img.width != VIDEO_WIDTH or img.height != VIDEO_HEIGHT:
                    img = img.resize((VIDEO_WIDTH, VIDEO_HEIGHT))
                arr = np.ascontiguousarray(np.asarray(img))  # H,W,3 RGB
                self.cam.send(arr)
                self.cam.sleep_until_next_frame()
            except Exception:
                time.sleep(0.02)

    def stop(self):
        self._running.clear()


# ---- 音频 sink:队列 + sounddevice 写入 VB-Cable ----
class AudioSink:
    def __init__(self):
        self._q = []
        self._lock = threading.Lock()
        self._cond = threading.Condition(self._lock)
        self._running = threading.Event()
        self.ready = False
        self.level = 0.0   # 最近一帧的 RMS 电平(0~1),供电脑端电平表轮询

    def push(self, pcm: bytes):
        with self._cond:
            self._q.append(pcm)
            self._cond.notify_all()

    @staticmethod
    def _find_cable_input():
        if sd is None:
            return None
        try:
            for i, dev in enumerate(sd.query_devices()):
                name = dev.get("name", "") or ""
                if "CABLE Input" in name and (dev.get("max_output_channels") or 0) > 0:
                    return i
        except Exception:
            pass
        return None

    def start(self):
        if sd is None or np is None:
            _log("虚拟麦克风不可用(sounddevice/numpy 缺失),先 pip install sounddevice")
            return False
        dev = self._find_cable_input()
        if dev is None:
            _log("没找到 VB-Cable 的 CABLE Input,请先安装 VB-Cable")
            return False
        try:
            self.stream = sd.OutputStream(
                samplerate=AUDIO_RATE, channels=AUDIO_CHANNELS,
                dtype="int16", device=dev,
            )
            self.stream.start()
        except Exception as e:
            _log("打开 CABLE Input 失败: %r" % e)
            return False
        _log("虚拟麦克风已就绪: 设备 #%d @ %dHz 单声道" % (dev, AUDIO_RATE))
        self._running.set()
        threading.Thread(target=self._loop, daemon=True).start()
        self.ready = True
        return True

    def _loop(self):
        # 声音要「实时但抗抖」:少量积压就喂给声卡(sd.write 是阻塞式、按真实时间节流,不会快进),
        # 积压若超过 ~350ms 说明网络/手机卡了很久,直接丢弃旧数据追到实时——宁可几毫秒停顿也别爆音拖音。
        max_drop = AUDIO_RATE * 2 * 350 // 1000   # 超过它丢弃
        max_take = AUDIO_RATE * 2 * 80 // 1000    # 单次最多喂 80ms,避免大块突发
        while self._running.is_set():
            with self._cond:
                while len(self._q) == 0 and self._running.is_set():
                    self._cond.wait(0.3)
                if not self._running.is_set():
                    break
                q = self._q
                # 落后太多就丢旧的
                if sum(len(c) for c in q) > max_drop:
                    q.clear()
                    continue
                chunks = []
                n = 0
                while q and n < max_take:
                    c = q.pop(0)
                    chunks.append(c)
                    n += len(c)
                pcm = b"".join(chunks)
                # 只取一小段(≤80ms)就放开锁,别攒一大堆一次性写
            # 顺手统计电平(入麦信号大小),供电脑端「麦克风电平」表显示/验证声音
            if np is not None and pcm:
                try:
                    _a = np.frombuffer(pcm, dtype=np.int16).astype(np.float32)
                    r = float(np.sqrt(np.mean(_a * _a))) / 32768.0
                    if r > self.level:
                        self.level = r          # 上冲快,波形明显
                    else:
                        self.level = self.level * 0.8 + r * 0.2   # 回落慢,显示稳定
                except Exception:
                    pass
            try:
                self.stream.write(np.frombuffer(pcm, dtype=np.int16))
            except Exception:
                time.sleep(0.01)

    def stop(self):
        self._running.clear()
        with self._cond:
            self._cond.notify_all()


_video = VideoSink()
_audio = AudioSink()
_media_lock = threading.Lock()


def handle_media_conn(conn, first_line):
    """处理一条媒体连接。first_line 形如 'MEDIA <token>'。认证通过后循环收帧分流。"""
    parts = first_line.split()
    if len(parts) != 2 or not check_token(parts[1]):
        try:
            conn.sendall(b"ERR bad media token\n")
        except Exception:
            pass
        return
    try:
        conn.sendall(b"MEDIA_OK\n")
    except Exception:
        return
    try:
        conn.settimeout(None)   # 清除 handle_client 探测首行时设置的 0.5s 超时,进入阻塞收帧
    except Exception:
        pass
    _log("媒体连接已认证")
    # ---- 到达节奏遥测(诊断掉帧用):统计视频帧数/大小/间隔,每 5s 打一行 ----
    win_start = time.time()
    win_frames = 0
    win_bytes = 0
    win_audio = 0
    win_maxgap = 0.0
    t_prev = None
    last_video = None
    while True:
        hdr = _recv_exact(conn, HEADER_LEN)
        if hdr is None:
            break
        typ = hdr[0]
        (length,) = struct.unpack(">I", hdr[1:HEADER_LEN])
        if length <= 0 or length > 8 * 1024 * 1024:
            break
        payload = _recv_exact(conn, length)
        if payload is None:
            break
        now = time.time()
        if typ == TYPE_VIDEO:
            win_frames += 1
            win_bytes += len(payload)
            if t_prev is not None:
                g = now - t_prev
                if g > win_maxgap:
                    win_maxgap = g
            t_prev = now
            last_video = payload
            _touch(TYPE_VIDEO)
            with _media_lock:
                if not _video.ready:
                    _video.start()
            _video.push(payload)
        elif typ == TYPE_AUDIO:
            win_audio += 1
            _touch(TYPE_AUDIO)
            with _media_lock:
                if not _audio.ready:
                    _audio.start()
            _audio.push(payload)
        if now - win_start >= 5.0:
            span = now - win_start
            dims = ""
            if Image is not None and last_video is not None:
                try:
                    im = Image.open(io.BytesIO(last_video))
                    dims = "%dx%d" % im.size
                except Exception:
                    dims = "?"
            _log("遥测 5s:视频 %d 帧(%.1ffps) 均 %.0fKB/帧 音频 %d 段 最大帧间隔 %.0fms 分辨率%s" % (
                win_frames, win_frames / span, win_bytes / win_frames / 1024,
                win_audio, win_maxgap * 1000, dims))
            win_start = now
            win_frames = 0
            win_bytes = 0
            win_audio = 0
            win_maxgap = 0.0
            # t_prev 保留:跨窗口的帧间隔也要算进去,避免漏掉「卡顿恰好卡在窗口边界」的情况
    if win_frames or win_audio:
        span = now - win_start if now > win_start else 1.0
        _log("遥测 末段:视频 %d 帧(%.1ffps) 音频 %d 段 最大帧间隔 %.0fms" % (
            win_frames, win_frames / span, win_audio, win_maxgap * 1000))
    _log("媒体连接结束")
