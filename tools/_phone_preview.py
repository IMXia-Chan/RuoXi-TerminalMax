# -*- coding: utf-8 -*-
"""只读预览：把官网套进一个 390px 宽的 iframe 里，模拟真机宽度。

为什么不能直接用 `--window-size=390`：Edge 无头会把窗口宽度夹到 ~492，
截出来的图是「492 的排版被裁到 390」，看起来像溢出，其实没有。
iframe 里的 innerWidth 是真 390，媒体查询也就按 390 生效。

用法：python tools/_phone_preview.py
然后 Edge 无头截 520x1700 的图。
"""
import io
import os

DST = r"C:/Users/27014/phone-touchpad/.claude/tmp/phone_preview.html"

HOST = """<!DOCTYPE html>
<html><head><meta charset="utf-8"><style>
  html,body{margin:0;background:#000}
  iframe{width:390px;height:1700px;border:0;display:block}
</style></head>
<body><iframe src="file:///D:/conmarn-site-gh/index.html" scrolling="no"></iframe></body></html>
"""

os.makedirs(os.path.dirname(DST), exist_ok=True)
io.open(DST, "w", encoding="utf-8", newline="\n").write(HOST)
print("已写入", DST)
