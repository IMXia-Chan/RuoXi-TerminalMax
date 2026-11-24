# -*- coding: utf-8 -*-
"""把官网打包成一个离线单文件 HTML。

用法（在 cmd 里）：
    python C:\\Users\\27014\\phone-touchpad\\tools\\site_bundle.py

产物：桌面\\ConMarn-官网-离线版.html
双击即可打开，不依赖网络。样式、脚本、矢量图与全部截图都内嵌在同一个文件里。
"""
from __future__ import print_function

import base64
import os
import re
import sys

SRC = r"D:\conmarn-site-gh"
OUT = os.path.join(os.path.expanduser("~"), "Desktop", "ConMarn-官网-离线版.html")

MIME = {
    ".jpg": "image/jpeg",
    ".jpeg": "image/jpeg",
    ".png": "image/png",
    ".svg": "image/svg+xml",
    ".css": "text/css;charset=utf-8",
    ".js": "application/javascript;charset=utf-8",
}

# 站内资源 -> 离线版里没有对应文件，点了会打不开。统一给一句提示。
MISSING_HINT = ("离线预览版不包含下载包与附属页面。"
                "安装包与英文版请访问在线站点。")


def size_of(path):
    try:
        return os.path.getsize(path)
    except OSError:
        return -1


def read_text(path):
    with open(path, "rb") as f:
        raw = f.read()
    for enc in ("utf-8-sig", "utf-8", "gbk"):
        try:
            return raw.decode(enc)
        except UnicodeDecodeError:
            continue
    raise SystemExit("读不了这个文件（编码未知）：%s" % path)


def data_uri(path):
    ext = os.path.splitext(path)[1].lower()
    mime = MIME.get(ext, "application/octet-stream")
    with open(path, "rb") as f:
        raw = f.read()
    return "data:%s;base64,%s" % (mime, base64.b64encode(raw).decode("ascii"))


def main():
    if not os.path.isdir(SRC):
        raise SystemExit("找不到站点目录：%s" % SRC)

    index = os.path.join(SRC, "index.html")
    if not os.path.isfile(index):
        raise SystemExit("找不到首页：%s" % index)

    html = read_text(index)
    report = []

    # ---- 1. 样式表 ----
    css_path = os.path.join(SRC, "assets", "site.css")
    css = read_text(css_path)
    tag = '<link rel="stylesheet" href="./assets/site.css">'
    if tag not in html:
        raise SystemExit("首页里没找到样式表引用，站点结构变了。")
    html = html.replace(tag, "<style>\n%s\n</style>" % css)
    report.append(("style  site.css", size_of(css_path)))

    # ---- 1b. 浅色主题样式表（若存在）----
    light_path = os.path.join(SRC, "assets", "light.css")
    if os.path.isfile(light_path):
        light = read_text(light_path)
        tag = '<link rel="stylesheet" href="./assets/light.css">'
        if tag in html:
            html = html.replace(tag, "<style>\n%s\n</style>" % light)
            report.append(("style  light.css", size_of(light_path)))

    # ---- 2. 脚本 ----
    js_path = os.path.join(SRC, "assets", "site.js")
    js = read_text(js_path)
    # 防止脚本内容里出现 </script 把标签提前闭合
    js = js.replace("</script", "<\\/script")
    tag = '<script src="./assets/site.js" defer></script>'
    if tag not in html:
        raise SystemExit("首页里没找到脚本引用，站点结构变了。")
    html = html.replace(tag, "<script>\n%s\n</script>" % js)
    report.append(("script site.js", size_of(js_path)))

    # ---- 2b. 主题切换脚本（若存在）----
    tj_path = os.path.join(SRC, "assets", "theme.js")
    if os.path.isfile(tj_path):
        tj = read_text(tj_path)
        tj = tj.replace("</script", "<\\/script")
        tag = '<script src="./assets/theme.js" defer></script>'
        if tag in html:
            html = html.replace(tag, "<script>\n%s\n</script>" % tj)
            report.append(("script theme.js", size_of(tj_path)))

    # ---- 3. 站点图标 ----
    fav = os.path.join(SRC, "favicon.svg")
    if 'href="./favicon.svg"' in html and os.path.isfile(fav):
        html = html.replace('href="./favicon.svg"', 'href="%s"' % data_uri(fav))
        report.append(("icon   favicon.svg", size_of(fav)))

    # ---- 4. 架构图（<img src>）----
    arch = os.path.join(SRC, "assets", "architecture.svg")
    if 'src="./assets/architecture.svg"' in html and os.path.isfile(arch):
        html = html.replace('src="./assets/architecture.svg"', 'src="%s"' % data_uri(arch))
        report.append(("svg    architecture.svg", size_of(arch)))

    # ---- 5. 截图（style 里的 background-image:url(...)）----
    shot_re = re.compile(r"url\((\./shots/[^)\"']+)\)")
    done = set()

    def repl(m):
        rel = m.group(1)
        path = os.path.join(SRC, rel[2:].replace("/", os.sep))
        if not os.path.isfile(path):
            print("!! 缺图，原样保留：%s" % rel, file=sys.stderr)
            return m.group(0)
        if rel not in done:
            report.append(("shot   %s" % os.path.basename(path), size_of(path)))
            done.add(rel)
        return "url(%s)" % data_uri(path)

    html = shot_re.sub(repl, html)

    # ---- 6. 离线提示：站内没有的链接点开时说明一句 ----
    guard = """
<script>
(function(){
  var HINT = %s;
  document.addEventListener('click', function(ev){
    var a = ev.target.closest ? ev.target.closest('a') : null;
    if (!a) return;
    var href = a.getAttribute('href') || '';
    if (href === '#' || href.charAt(0) === '#' || /^(https?:|mailto:)/.test(href)) return;
    ev.preventDefault();
    alert(HINT);
  }, true);
})();
</script>
""" % repr(MISSING_HINT).replace("'", '"')

    if "</body>" in html:
        html = html.replace("</body>", guard + "</body>", 1)
    else:
        html += guard

    # ---- 7. 落盘 ----
    out_dir = os.path.dirname(OUT)
    if not os.path.isdir(out_dir):
        raise SystemExit("找不到输出目录：%s" % out_dir)

    with open(OUT, "w", encoding="utf-8") as f:
        f.write(html)

    total_src = sum(s for _, s in report if s > 0)
    out_size = os.path.getsize(OUT)

    print("")
    print("=== 内嵌明细 ===")
    for name, s in report:
        print("  %-28s %8.1f KB" % (name, s / 1024.0 if s > 0 else 0))
    print("  %-28s %8.1f KB" % ("原文件合计", total_src / 1024.0))
    print("")
    print("产物：%s" % OUT)
    print("大小：%.2f MB" % (out_size / 1048576.0))
    print("")
    print("验证：双击打开，断网状态下从头滚到底，截图与架构图都应显示。")


if __name__ == "__main__":
    main()
