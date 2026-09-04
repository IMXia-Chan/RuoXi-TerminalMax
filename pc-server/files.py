# -*- coding: utf-8 -*-
"""
files.py —— 超级终端:接收「手机 -> 电脑」的文件上传

传输:独立 TCP 连接(复用 9527 端口),首行 `FILE <token>` 认证,之后是二进制帧
(与 media.py 同款分帧):
  [1 字节 type][4 字节大端 length][length 字节 payload]
    type=3 FILE_META   payload = json {"name","size","sha256"}
    type=4 FILE_CHUNK  payload = 文件原始字节
    type=5 FILE_END    payload = 空;电脑校验通过后回 `FILE_DONE <sha256>`,失败回 `FILE_ERR`

电脑中转目录(TRANSFER_DIR)与 UI 事件回调(on_event)由 server.py 在运行时注入,
避免循环 import。本模块不 import server。
"""
import hashlib
import json
import os
import re
import struct
import sys
import threading
import time

TYPE_META = 3
TYPE_CHUNK = 4
TYPE_END = 5
HEADER_LEN = 5                # 1 字节 type + 4 字节大端长度
MAX_CHUNK = 8 * 1024 * 1024   # 单片上限 8MB(手机端按 256KB 发,这是兜底)
FILE_TOKEN_TTL = 1800         # 上传 token 有效期(秒)
MAX_FILE_SIZE = 100 * 1024 ** 3   # 单文件 100GB 上限兜底

# ---- 由 server.py 注入 ----
TRANSFER_DIR = os.path.expanduser("~")
on_event = None   # callable(evt: dict);上传完成等事件上报给 GUI

# 日志与 server.py 写同一个文件,方便一起排查
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
                fp.write("%s [file] %s\n" % (time.strftime("%H:%M:%S"), msg))
    except Exception:
        pass


# ---- 文件上传 token(控制连接认证通过后签发,上传连接凭它免重复认证) ----
_tokens = {}
_tokens_lock = threading.Lock()


def issue_file_token():
    """签发一个短期文件上传 token(随机 128 位)。"""
    tok = os.urandom(16).hex()
    with _tokens_lock:
        _tokens[tok] = time.time() + FILE_TOKEN_TTL
    return tok


def check_file_token(tok):
    with _tokens_lock:
        exp = _tokens.get(tok)
        if exp is None:
            return False
        if time.time() > exp:
            _tokens.pop(tok, None)
            return False
        return True


# ---- 路径/文件名安全 ----
def _sanitize_name(name):
    """去掉路径与危险字符,只留一个安全的文件名(Windows 可用)。"""
    name = name.replace("\\", "/")
    name = name.split("/")[-1]
    name = re.sub(r"[\x00-\x1f\x7f]", "", name).strip()
    name = name.rstrip(". ")                     # Windows 文件名不能以点/空格结尾
    if not name:
        name = "未命名_%d" % int(time.time())
    return name[:180]


def _unique_dest(dirpath, name):
    """若目标名已存在,追加 (1)(2)… 避免覆盖用户已有文件。"""
    cand = os.path.join(dirpath, name)
    if not os.path.exists(cand):
        return cand
    base, dot, ext = name.rpartition(".")
    stem = base or name
    ext = (dot + ext) if dot else ""
    for i in range(1, 1000):
        c = os.path.join(dirpath, "%s (%d)%s" % (stem, i, ext))
        if not os.path.exists(c):
            return c
    return os.path.join(dirpath, "%s_%d%s" % (stem, int(time.time()), ext))


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
    """逐字节读一行(仅用于连接首行认证,量小)。"""
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


def _abort(conn, msg):
    try:
        conn.sendall(("FILE_ERR %s\n" % msg).encode("utf-8"))
    except Exception:
        pass


def handle_file_conn(conn, first_line):
    """处理一条文件上传连接。first_line 形如 'FILE <token>'。每条连接传一个文件。"""
    parts = first_line.split()
    if len(parts) != 2 or not check_file_token(parts[1]):
        try:
            conn.sendall(b"ERR bad file token\n")
        except Exception:
            pass
        return
    try:
        conn.sendall(b"FILE_OK\n")
    except Exception:
        return
    try:
        conn.settimeout(None)   # 清除 handle_client 探测首行时的 0.5s 超时
    except Exception:
        pass

    name = ""
    expect_sha = ""
    size = 0
    tmp = None
    fp = None
    sha = hashlib.sha256()
    got = 0
    try:
        while True:
            hdr = _recv_exact(conn, HEADER_LEN)
            if hdr is None:
                break
            typ = hdr[0]
            (length,) = struct.unpack(">I", hdr[1:HEADER_LEN])
            if length < 0 or length > MAX_CHUNK:
                _abort(conn, "bad frame")
                break
            payload = _recv_exact(conn, length)
            if payload is None:
                break

            if typ == TYPE_META:
                try:
                    meta = json.loads(payload.decode("utf-8", "replace"))
                    name = _sanitize_name(str(meta.get("name", "")))
                    # size:>=0 是已知总长;-1 表示未知(如实时流,靠字节数累计+sha 校验)
                    size = int(meta.get("size", -1))
                    expect_sha = str(meta.get("sha256", "") or "").lower()
                except Exception:
                    _abort(conn, "bad meta")
                    break
                if size > MAX_FILE_SIZE:
                    _abort(conn, "file too large")
                    break
                if not name:
                    _abort(conn, "bad name")
                    break
                # 用随机临时名写中转目录,避免多个上传同名的 .part 互踩
                tmp = os.path.join(TRANSFER_DIR, ".tmp_%s.part" % os.urandom(6).hex())
                try:
                    fp = open(tmp, "wb")
                except Exception:
                    _abort(conn, "cannot write")
                    break
                sha = hashlib.sha256()
                got = 0
            elif typ == TYPE_CHUNK:
                if fp is None:
                    _abort(conn, "meta first")
                    break
                got += len(payload)
                if got > MAX_FILE_SIZE:
                    _abort(conn, "too big")
                    break
                try:
                    fp.write(payload)
                except Exception:
                    _abort(conn, "write error")
                    break
                sha.update(payload)
            elif typ == TYPE_END:
                if fp is None:
                    _abort(conn, "meta first")
                    break
                try:
                    fp.flush()
                    fp.close()
                except Exception:
                    pass
                fp = None
                sha_hex = sha.hexdigest()
                known_size = size >= 0
                ok = ((not known_size) or got == size) and \
                    (not expect_sha or expect_sha == sha_hex)
                if ok:
                    dest = _unique_dest(TRANSFER_DIR, name)
                    try:
                        os.replace(tmp, dest)
                        tmp = None
                    except Exception:
                        _abort(conn, "rename error")
                        break
                    base = os.path.basename(dest)
                    try:
                        conn.sendall(("FILE_DONE %s\n" % sha_hex).encode("utf-8"))
                    except Exception:
                        pass
                    _log("收到上传 %s (%d 字节, sha=%s)" % (base, got, sha_hex))
                    if on_event is not None:
                        try:
                            on_event({
                                "kind": "upload_done", "name": base,
                                "size": got, "path": dest,
                            })
                        except Exception:
                            pass
                else:
                    _abort(conn, "checksum")
                    _log("上传校验失败,已丢弃 %s (期望 %d/%s,实际 %d/%s)"
                         % (name, size, expect_sha, got, sha_hex))
                break   # 一条连接只传一个文件
            else:
                # 未知 type:跳过该帧继续(与 media.py 一致,不因未知类型断开)
                continue
    except Exception as e:
        _log("上传连接异常: %s" % e)
    finally:
        if fp is not None:
            try:
                fp.close()
            except Exception:
                pass
        if tmp is not None:
            try:
                os.remove(tmp)   # 中途断开/失败:清掉临时文件,不留 .part 残留
            except Exception:
                pass
    _log("上传连接结束")
