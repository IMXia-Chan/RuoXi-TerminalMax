# -*- coding: utf-8 -*-
"""只读探针：GET 一个本机地址，打印状态码和开头一小段。用法：python tools/_http_probe.py [url]"""
import sys
import urllib.request

url = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:9611/"
try:
    with urllib.request.urlopen(url, timeout=8) as r:
        body = r.read(4000).decode("utf-8", errors="replace")
        print("status", r.status, "bytes>=", len(body))
        print(body[:300].replace("\n", " "))
except Exception as e:
    print("ERR", type(e).__name__, e)
