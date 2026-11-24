#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""假的 OpenAI 兼容模型服务,用来在没有真模型时验证「AI 助手」整条链路。

它不推理,只按剧本回:第一次请求回一个 tool_call(让电脑执行某个工具),
下一次(收到 tool 结果后)回一句最终答复。这样就能在只花本地流量、不调云端 API 的情况下
把「手机说话 -> 文本模型出工具调用 -> 电脑执行 -> AIR 回执 -> 文本模型收尾」整条路走通。

用法:
    python fake_llm.py                     # 监听 0.0.0.0:8080,剧本 = 打开记事本
    python fake_llm.py --script media      # 剧本 = 播放/暂停
    python fake_llm.py --port 8081         # 换端口(也可当视觉模型用,回固定坐标)

手机端把「文本模型地址」填成 http://<电脑局域网IP>:8080 即可。

剧本:
    notepad  第一次调 open_app("记事本"),然后收尾
    media    第一次调 media("play_pause"),然后收尾
    windows  先 get_state,再收尾(报告窗口数)
    click    调 click_element("开始按钮")走视觉链路(需要另一台跑视觉模型的)
    vision   当视觉模型用:对任何截图都回 {"x":500,"y":500}
"""

import argparse
import json
import re
import sys
import io
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

try:
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
except Exception:
    pass

SCRIPT = "notepad"
PORT = 8080


def tool_call(name, args, call_id="call_1"):
    return {
        "id": call_id,
        "type": "function",
        "function": {"name": name, "arguments": json.dumps(args, ensure_ascii=False)},
    }


def decide(script, messages):
    """看历史里有没有 tool 结果:没有就出工具调用,有了就收尾。"""
    if script == "vision":
        return {"role": "assistant", "content": '{"x": 500, "y": 500}'}

    # 已经有一条 tool 结果了 -> 收尾
    tool_results = [m for m in messages if m.get("role") == "tool"]
    if tool_results:
        last = tool_results[-1].get("content") or ""
        try:
            obj = json.loads(last)
            ok = obj.get("ok") is True
        except Exception:
            ok = "true" in last.lower()
        res = tool_results[-1].get("content", "")
        m = re.search(r'"windows"\s*:\s*\[', res)
        extra = "(拿到窗口列表了)" if m else ""
        return {
            "role": "assistant",
            "content": ("办好了%s。" % extra) if ok else ("没做成:%s" % res[:150]),
        }

    # 第一轮:按剧本出工具调用
    if script == "notepad":
        return {"role": "assistant", "content": None,
                "tool_calls": [tool_call("open_app", {"name": "记事本"})]}
    if script == "media":
        return {"role": "assistant", "content": None,
                "tool_calls": [tool_call("media", {"action": "play_pause"})]}
    if script == "windows":
        return {"role": "assistant", "content": None,
                "tool_calls": [tool_call("get_state", {})]}
    if script == "click":
        return {"role": "assistant", "content": None,
                "tool_calls": [tool_call("click_element", {"description": "开始按钮"})]}
    return {"role": "assistant", "content": "（假模型:不认识的剧本 %s）" % script}


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        sys.stdout.write("[fake_llm] %s\n" % (fmt % args))
        sys.stdout.flush()

    def do_GET(self):
        if self.path.rstrip("/") in ("/v1/models", "/models"):
            self._json(200, {"object": "list", "data": [{"id": "fake", "object": "model"}]})
        elif self.path.rstrip("/") == "/health":
            self._json(200, {"status": "ok", "script": SCRIPT})
        else:
            self._json(404, {"error": "not found"})

    def do_POST(self):
        if not self.path.rstrip("/").endswith("/chat/completions"):
            self._json(404, {"error": "not found"})
            return
        try:
            n = int(self.headers.get("Content-Length", "0"))
            body = json.loads(self.rfile.read(n).decode("utf-8"))
        except Exception as e:
            self._json(400, {"error": "bad body: %s" % e})
            return
        messages = body.get("messages") or []
        has_img = any(isinstance(m.get("content"), list) for m in messages)
        script = "vision" if has_img else SCRIPT
        print("[fake_llm] 收到 %d 条消息%s -> 剧本 %s" % (
            len(messages), "(含图片)" if has_img else "", script), flush=True)
        msg = decide(script, messages)
        self._json(200, {
            "id": "chatcmpl-fake",
            "object": "chat.completion",
            "model": body.get("model", "fake"),
            "choices": [{"index": 0, "message": msg, "finish_reason": "stop"}],
            "usage": {"prompt_tokens": 0, "completion_tokens": 0, "total_tokens": 0},
        })

    def _json(self, code, obj):
        data = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8080)
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--script", default="notepad",
                    choices=["notepad", "media", "windows", "click", "vision"])
    a = ap.parse_args()
    SCRIPT = a.script
    PORT = a.port
    print("假模型服务已启动:http://%s:%d  剧本=%s" % (a.host, a.port, a.script), flush=True)
    print("手机「文本模型地址」填: http://192.168.1.14:%d" % a.port, flush=True)
    ThreadingHTTPServer((a.host, a.port), Handler).serve_forever()
