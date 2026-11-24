# -*- coding: utf-8 -*-
"""只读探针：线上官网是不是已经有电脑端的包了。"""
import re
import sys
import urllib.request

BASE = "https://imxia-chan.github.io/ConMarn.AI/"
EXE = "ConMarn-1.0.0-win64.exe"


def get(url, method="GET"):
    req = urllib.request.Request(url, method=method,
                                 headers={"User-Agent": "Mozilla/5.0 conmarn-check"})
    with urllib.request.urlopen(req, timeout=20) as r:
        return r.status, dict(r.headers), r.read()


try:
    status, _h, body = get(BASE)
    html = body.decode("utf-8", errors="replace")
    print("首页", status, len(body), "字节")
    print("含 win64 链接:", "-win64.exe" in html)
    for m in re.finditer(r'href="([^"]*-win64\.exe)"', html):
        print("  链接:", m.group(1))
    for m in re.finditer(r'href="([^"]*\.apk)"', html):
        print("  APK :", m.group(1))
except Exception as e:
    print("首页 ERR", type(e).__name__, e)
    sys.exit(1)

try:
    status, headers, body = get(BASE + EXE, "HEAD")
    print("exe  ", status,
          "content-length =", headers.get("Content-Length"),
          "type =", headers.get("Content-Type"))
except Exception as e:
    print("exe  ERR", type(e).__name__, e)
