# -*- coding: utf-8 -*-
"""把浅色/深色主题切换部署进官网六个页面（幂等）。

每个页面做四件事：
  1. <meta name="color-scheme"> 补上 light；
  2. site.css 之后挂 light.css 与首屏防闪脚本；
  3. 导航里插入 ☀/🌙 切换按钮；
  4. </body> 前挂 theme.js。

之后把 light.css / theme.js 复制进 assets，重打离线单文件，
并用默认浏览器打开本地首页验收。已部署过的页面直接跳过。
"""
import os
import shutil
import subprocess
import sys

SITE = r"D:\conmarn-site-gh"
TOOLS = r"C:\Users\27014\phone-touchpad\tools"
BUNDLER = os.path.join(TOOLS, "site_bundle.py")

INLINE = """<script>
/* 首屏前确定主题，避免闪烁。未保存过选择时跟随系统。 */
(function(){
  try{
    var t = localStorage.getItem('conmarn-theme');
    if(t === 'light' || t === 'dark'){
      document.documentElement.setAttribute('data-theme', t);
    } else if(window.matchMedia && window.matchMedia('(prefers-color-scheme: light)').matches){
      document.documentElement.setAttribute('data-theme','light');
    }
  }catch(e){}
})();
</script>"""

BTN_ZH = ('<button class="theme-toggle" type="button" aria-label="切换主题" title="切换主题">'
          '<span class="i-sun" aria-hidden="true">\u2600</span>'
          '<span class="i-moon" aria-hidden="true">\U0001F319</span></button>')
BTN_EN = ('<button class="theme-toggle" type="button" aria-label="Toggle theme" title="Toggle theme">'
          '<span class="i-sun" aria-hidden="true">\u2600</span>'
          '<span class="i-moon" aria-hidden="true">\U0001F319</span></button>')

# (相对路径, 资源前缀, 按钮文案, 插入方式, 锚点文本)
# 插入方式 before=放在锚点前；after=放在锚点后。
PAGES = [
    ("index.html", "./", BTN_ZH, "before",
     '<a class="lang" href="en/index.html"'),
    ("privacy.html", "./", BTN_ZH, "after",
     '<a class="lang" href="en/privacy.html" hreflang="en">EN</a>\n    </div>'),
    ("terms.html", "./", BTN_ZH, "after",
     '<a class="lang" href="en/terms.html" hreflang="en">EN</a>\n    </div>'),
    ("en/index.html", "../", BTN_EN, "before",
     '<a class="lang" href="../index.html"'),
    ("en/privacy.html", "../", BTN_EN, "after",
     '<a class="lang" href="../privacy.html" hreflang="zh-CN">\u4e2d\u6587</a>\n    </div>'),
    ("en/terms.html", "../", BTN_EN, "after",
     '<a class="lang" href="../terms.html" hreflang="zh-CN">\u4e2d\u6587</a>\n    </div>'),
]


def read_text(path):
    with open(path, "rb") as f:
        raw = f.read()
    eol = "\r\n" if b"\r\n" in raw else "\n"
    for enc in ("utf-8-sig", "utf-8"):
        try:
            text = raw.decode(enc)
            break
        except UnicodeDecodeError:
            continue
    else:
        raise SystemExit("编码不认识（应为 UTF-8）：%s" % path)
    if eol == "\r\n":
        text = text.replace("\r\n", "\n")
    return text, eol


def write_text(path, text, eol):
    if eol == "\r\n":
        text = text.replace("\n", "\r\n")
    with open(path, "w", encoding="utf-8", newline="") as f:
        f.write(text)


def patch(path, prefix, btn, mode, anchor):
    if not os.path.isfile(path):
        print("!! 不存在，跳过：%s" % path)
        return False
    html, eol = read_text(path)
    if "theme-toggle" in html:
        print("= 已部署过，跳过：%s" % os.path.basename(path))
        return False

    changes = 0

    old_meta = '<meta name="color-scheme" content="dark">'
    if old_meta in html:
        html = html.replace(old_meta, '<meta name="color-scheme" content="dark light">', 1)
        changes += 1
    else:
        print("!! 找不到 color-scheme meta：%s" % path)

    css_tag = '<link rel="stylesheet" href="%sassets/site.css">' % prefix
    if css_tag in html:
        new_css = (css_tag + "\n"
                   + '<link rel="stylesheet" href="%sassets/light.css">' % prefix + "\n"
                   + INLINE)
        html = html.replace(css_tag, new_css, 1)
        changes += 1
    else:
        print("!! 找不到 site.css 引用：%s" % path)

    if anchor not in html:
        print("!! 找不到按钮锚点：%s" % path)
    else:
        if mode == "before":
            html = html.replace(anchor, btn + "\n    " + anchor, 1)
        else:
            html = html.replace(anchor, anchor + "\n    " + btn, 1)
        changes += 1

    if "</body>" in html:
        html = html.replace("</body>",
                            '<script src="%sassets/theme.js" defer></script>\n</body>' % prefix, 1)
        changes += 1
    else:
        print("!! 找不到 </body>：%s" % path)

    ok = (html.count("theme-toggle") == 1
          and html.count("light.css") == 1
          and html.count("theme.js") == 1)
    if not ok:
        print("!! 插入结果校验不过，未写入：%s" % path)
        return False

    write_text(path, html, eol)
    print("+ 已部署（%d 处改动）：%s" % (changes, path.replace(SITE + os.sep, "")))
    return True


def main():
    if not os.path.isdir(SITE):
        raise SystemExit("找不到站点目录：%s" % SITE)

    print("== 1/3 六个页面 ==")
    done = 0
    for rel, prefix, btn, mode, anchor in PAGES:
        path = os.path.join(SITE, *rel.split("/"))
        if patch(path, prefix, btn, mode, anchor):
            done += 1
    print("本轮新部署 %d 个页面" % done)

    print("== 2/3 资源文件 ==")
    for name in ("light.css", "theme.js"):
        src = os.path.join(TOOLS, name)
        dst = os.path.join(SITE, "assets", name)
        if not os.path.isfile(src):
            print("!! 找不到：%s" % src)
            continue
        shutil.copyfile(src, dst)
        print("+ 已复制：assets\\%s" % name)

    print("== 3/3 离线单文件 ==")
    if os.path.isfile(BUNDLER):
        subprocess.run([sys.executable, BUNDLER])
    else:
        print("!! 找不到打包脚本：%s" % BUNDLER)

    print("")
    print("完成。正在用默认浏览器打开本地首页验收……")
    try:
        os.startfile(os.path.join(SITE, "index.html"))
    except OSError:
        print("浏览器没能自动打开，请手动双击 D:\\conmarn-site-gh\\index.html")


if __name__ == "__main__":
    main()
