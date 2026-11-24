#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
她的「网页版」—— 在电脑上用浏览器打开她自己。

★★ 为什么这件事几乎不用写代码(2026-10-06 晚查实):
    her.js:1176 那一行是
        const vrmUrl = new URL('sample.vrm', document.baseURI).href;
    它按 **baseURI 相对**解析。手机上 baseURI 是
        https://appassets.androidplatform.net/her/
    —— 同一个目录,只要在电脑上用 http serve 出来,baseURI 变成
        http://127.0.0.1:8090/
    那一行就会去取本机的 /sample.vrm。**her.js 一个字都不用改。**
    而且它调 Kotlin 的地方全是 `window.HerBridge?.xxx`(可选链),
    没有桥它**不报错、只是什么都不做** —— 所以它在浏览器里跑得起来。

★ 那缺的是什么?**不缺渲染,缺的是「把页面端出来」和「出错了看得见」。**

  第二件比第一件要紧。这个项目最恨的就是**静默失败**,而浏览器的失败
  恰恰是最静默的:她的 JS 里 `HerBridge.onError(msg)` 是往 Android 报错的,
  在电脑上没有那个对象 → 报错信号**进了虚空**。屏幕上是「她没出来」,
  终端里一行都没有 —— 和「她还在加载」长得一模一样。

  所以这个服务做三件事:
    ① 把她那个目录 serve 出来(和手机**同一份字节**,不另存一份);
    ② **注入一小段桥**,把 onReady / onError / onNote 全部接到终端上;
    ③ 报错就**大声报**,并且**超时没 ready 也报** —— 那正是最像"正常"的一种坏。

★ 为什么单独起一个进程,而不是塞进 pc-server:
    pc-server 是 9527 那条裸 socket + 一个 Tkinter 界面,而且它**不热加载**
    (改了要重启,重启要过 UAC)。把网页塞进去 = 为了看一个网页去重启她的神经中枢。
    这里只读不写,拿 `app/src/main/assets/her/` 当根,**一行现有代码都不碰**。

用法:
    python tools/her-web.py                 # 用 assets 里那份 sample.vrm
    python tools/her-web.py --open          # 顺手用 Edge 的 --app 模式打开(无地址栏)
    python tools/her-web.py --vrm D:\\x.vrm  # 指定一具身体
    python tools/her-web.py --port 8091

★ 不给 --vrm 时按这个顺序找身体(第一个存在的就用):
    ① 桌面上的 .vrm(★ 他刚导出的就在那儿)
    ② D:\\her-build\\vrm\\ 里最新那个
    ③ assets 里那份 sample.vrm
  并在启动横幅里**报出它到底用了哪个、多少字节** —— 换身体最容易出的错
  就是「我换了它没变」,而那种失败一个字都不会报。

★ 只绑 127.0.0.1。两个理由:
    ① 这是她的身体,不该出现在局域网上;
    ② 以后电脑端要开麦克风,**浏览器只在安全上下文里给麦克风**,
       而 `http://127.0.0.1` 算安全上下文、`http://192.168.x.x` **不算**。
       这不是选择,是浏览器的规矩 —— 见计划里那条「别在真机上踩」。
"""

import argparse
import glob
import json
import os
import subprocess
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, unquote

# 控制台是 GBK 的,中文能打;但万一碰到打不出的字别让它崩
try:
    sys.stdout.reconfigure(errors="replace")
    sys.stderr.reconfigure(errors="replace")
except Exception:
    pass

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
DEFAULT_ROOT = os.path.join(REPO, "app", "src", "main", "assets", "her")

# ★ 注入口。它是 index.html 里**逐字**存在的一行;找不到它就不serve,直接报错
#   (fail closed —— 宁可看不到,也不给一个"能打开但桥没接上"的页面,
#    那种画面看着完全正常,而报错信号又一次进了虚空)。
MARKER = '<script src="her.bundle.js"></script>'
INJECT = '<script src="__bridge.js"></script>\n  ' + MARKER

MIME = {
    ".html": "text/html; charset=utf-8",
    ".js": "text/javascript; charset=utf-8",
    ".vrm": "model/gltf-binary",
    ".glb": "model/gltf-binary",
    ".gltf": "model/gltf+json",
    ".json": "application/json; charset=utf-8",
    ".png": "image/png",
    ".jpg": "image/jpeg",
    ".jpeg": "image/jpeg",
    ".webp": "image/webp",
    ".bin": "application/octet-stream",
    ".txt": "text/plain; charset=utf-8",
}


# ---------------------------------------------------------------------------
# 桥:注入给浏览器的那一小段。它**不在仓库里任何 .js 里** —— 因为手机那份
#     index.html 一个字节都不该动(手机上有真的 HerBridge,不需要它)。
# ---------------------------------------------------------------------------
BRIDGE_JS = r"""
// __bridge.js —— 只在电脑的浏览器里注入。手机那份 index.html 一个字节都不动。
//
// 它的全部工作:把「她那边出事了」这句话从浏览器里搬出来,搬到终端上。
// 没有它,onError 是往一个不存在的对象上报,报得再对也没人听见。
(function () {
  var t0 = Date.now();
  var gotReady = false;

  function stamp() {
    var s = ((Date.now() - t0) / 1000).toFixed(1);
    while (s.length < 5) s = ' ' + s;
    return '[' + s + 's]';
  }

  function note(level, msg) {
    try { console.log(stamp(), level + ': ' + msg); } catch (e) {}
    try {
      navigator.sendBeacon('/__note', new Blob(
        [JSON.stringify({ level: level, msg: String(msg) })],
        { type: 'application/json' }));
    } catch (e) {}
  }

  // ---- 让地址栏能戳她,不用先造一堆按钮(他嫌装饰) --------------------
  //   ?emo=happy   笑一个          ?say=你好   张嘴说话(按字数估时间)
  //   ?talk=1      一直说          ?listen=1  她在听的姿态
  //   ?presence=0  走神            ?cycle=1   六种表情每 2.5 秒换一个
  function poke() {
    var q = new URLSearchParams(location.search);
    if (!q.toString()) return;
    var H = window.Her;
    if (!H) { note('★ 有参数但没有 window.Her', '戳不动'); return; }
    var did = [];
    if (q.has('emo')) { H.setEmotion(q.get('emo'), 1); did.push('emo=' + q.get('emo')); }
    // ?cycle=1 —— 六种情绪每 2.5 秒换一个。
    //   ★ 它是给「表情到底动不动」用的,不是装饰:那件事**只有他能判**,
    //     而手打六遍网址太笨。这里一个新按钮都不画 —— 只是把六个网址变成一个。
    //   ★ her.js 的 setEmotion 只存**一个**目标(this.emotion),后来者直接替掉前者,
    //     所以不会六种表情糊在一起。
    if (q.has('cycle')) {
      var EMO = ['happy', 'sad', 'angry', 'relaxed', 'surprised', 'neutral'];
      var k = 0;
      setInterval(function () {
        H.setEmotion(EMO[k], 1);
        note('表情', EMO[k]);
        k = (k + 1) % EMO.length;
      }, 2500);
      did.push('cycle(六种,每 2.5 秒一换)');
    }
    if (q.has('presence')) { H.setPresence(parseFloat(q.get('presence'))); did.push('presence'); }
    if (q.has('listen')) { H.setListening(q.get('listen') !== '0'); did.push('listening'); }
    if (q.has('talk')) { H.setTalking(q.get('talk') !== '0'); did.push('talking'); }
    if (q.has('say')) {
      var txt = q.get('say') || '你好,我是从漫。';
      H.setTalking(true);
      did.push('say="' + txt + '"');
      // 口型按「播到第几个字」开合 —— 这里只是拿它当秒表用
      var n = txt.length, i = 0;
      var iv = setInterval(function () {
        i++;
        H.speakRange(i, n);
        if (i >= n) {
          clearInterval(iv);
          setTimeout(function () { H.speakRange(0, 0); H.setTalking(false); }, 260);
        }
      }, 190);
    }
    note('戳了她', did.join(' '));
  }

  window.HerBridge = {
    onReady: function () {
      gotReady = true;
      var n = window.Her ? Object.keys(window.Her).length : 0;
      note('就绪', 'window.Her 有 ' + n + ' 个方法');
      if (!window.Her) note('★ 报了就绪但没有 window.Her', '页面接不上');
      setTimeout(poke, 120);
    },
    onError: function (m) { note('★ 她没能出场', m); },
    onNote:  function (m) { note('她', m); },
    onObjectTapped: function (id) { note('点了物件', id); },
    onEmptyTapped:  function () { note('点了空处', '什么都没发生的话,那是她对'); },
  };

  window.addEventListener('error', function (e) {
    note('★ 页面 JS 报错', (e && e.message) + ' @' + (e && e.filename) + ':' + (e && e.lineno));
  });
  window.addEventListener('unhandledrejection', function (e) {
    var r = e && e.reason;
    note('★ 未处理的 promise 失败', (r && (r.message || r)) || r);
  });

  // ★★ 这两条是这个文件里最值钱的:分不开「她还在加载」和「她永远出不来」。
  //    屏幕上看这两种一模一样,而这个项目最恨的就是分不清。
  //
  //    ★ 为什么是**两级**而不是一级(2026-10-06 当场改的):
  //      第一版只有一个 20 秒的 ★ 报警,结果**软件渲染下一次正常的加载
  //      (25 秒)也被它报成了"出事"** —— 一个会喊狼来了的看门狗,
  //      比没有看门狗更坏:它会教人**不看**。所以拆成
  //        「慢」= 就一句平话,不带 ★
  //        「死」= 45 秒还没动静,那才是真该去看的
  setTimeout(function () {
    if (!gotReady) note('还在加载', '15 秒了还没出来。软件渲染下这是正常的 —— 10MB 的模型要解包');
  }, 15000);
  setTimeout(function () {
    if (!gotReady) note('★ 45 秒了还没等到 onReady',
      '这就不是慢了。看终端上面有没有更早的错。');
  }, 45000);

  note('桥接上了', '注入的胶水脚本已运行');
})();
""".strip()


# ---------------------------------------------------------------------------
# 找身体
# ---------------------------------------------------------------------------
def newest_vrm(folder):
    if not os.path.isdir(folder):
        return None
    hits = [p for p in glob.glob(os.path.join(folder, "*.vrm")) if os.path.isfile(p)]
    if not hits:
        return None
    return max(hits, key=os.path.getmtime)


def pick_vrm(explicit, root):
    if explicit:
        if not os.path.isfile(explicit):
            sys.exit("✗ --vrm 指的文件不存在:%s" % explicit)
        return explicit, "你指定的"
    desktop = newest_vrm(os.path.join(os.path.expanduser("~"), "Desktop"))
    if desktop:
        return desktop, "桌面上的(最新那个)"
    built = newest_vrm("D:/her-build/vrm")
    if built:
        return built, "D:\\her-build\\vrm(最新那个)"
    fallback = os.path.join(root, "sample.vrm")
    if os.path.isfile(fallback):
        return fallback, "assets 里内置那份"
    return None, "没找到"


# ---------------------------------------------------------------------------
# 服务
# ---------------------------------------------------------------------------
class Handler(BaseHTTPRequestHandler):
    server_version = "her-web"
    root = DEFAULT_ROOT
    vrm_path = None
    vrm_note = ""

    def log_message(self, fmt, *args):
        # 默认那个会把每个请求都刷一行;我们要的是她那边的动静,不是这些
        pass

    # -- 小工具 ----------------------------------------------------------
    def _send(self, code, body, ctype):
        if isinstance(body, str):
            body = body.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        # ★ 不许缓存:换一具身体之后刷新就该是新的人,
        #   而「缓存了旧的」的症状正是「我换了它没变」——那种失败一个字都不报。
        self.send_header("Cache-Control", "no-store, must-revalidate")
        self.end_headers()
        try:
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def _err_page(self, code, title, detail):
        html = (
            "<!DOCTYPE html><meta charset='utf-8'>"
            "<style>body{background:#160f13;color:#e8d8e0;font:15px/1.7 system-ui;"
            "padding:40px;max-width:46em}h1{color:#e05b8e;font-size:19px}"
            "code{background:#2a1f25;padding:2px 6px;border-radius:4px}</style>"
            "<h1>%s</h1><p>%s</p>" % (title, detail)
        )
        self._send(code, html, MIME[".html"])

    def _serve_file(self, path):
        try:
            with open(path, "rb") as fp:
                data = fp.read()
        except OSError as e:
            self._err_page(500, "读不到这个文件", "`%s` —— %s" % (path, e))
            return
        ext = os.path.splitext(path)[1].lower()
        self._send(200, data, MIME.get(ext, "application/octet-stream"))

    # -- GET -------------------------------------------------------------
    def do_GET(self):
        path = unquote(urlparse(self.path).path)

        if path in ("/", "/index.html"):
            self._serve_index()
            return

        if path == "/__bridge.js":
            self._send(200, BRIDGE_JS, MIME[".js"])
            return

        # ★ 身体:不管请求的是 sample.vrm 还是别的名字,都回**挑中的那一具**。
        #   这样 her.js 那行相对路径一个字不用改,而换身体只是重启一次这个脚本。
        if path.lower().endswith(".vrm") or path == "/sample.vrm":
            if not self.vrm_path:
                self._err_page(404, "没有身体可以用",
                    "assets 里没有 sample.vrm,桌面上也没有 .vrm。"
                    "在 VRoid Studio 里「导出」一个 .vrm 丢桌面,然后重启这个脚本。")
                return
            self._serve_file(self.vrm_path)
            return

        # 其余静态文件:只许在本目录里
        rel = path.lstrip("/")
        full = os.path.normpath(os.path.join(self.root, rel))
        if not full.startswith(os.path.normpath(self.root) + os.sep):
            self._err_page(403, "只能在她的目录里", "`%s` 跑到外面去了" % path)
            return
        if os.path.isfile(full):
            self._serve_file(full)
            return
        self._err_page(404, "没有这个文件", "`%s`" % path)

    def _serve_index(self):
        src = os.path.join(self.root, "index.html")
        try:
            with open(src, "r", encoding="utf-8") as fp:
                html = fp.read()
        except OSError as e:
            self._err_page(500, "读不到 index.html", "%s" % e)
            return
        if MARKER not in html:
            # ★ fail closed:桥接不上就**别 serve**。
            #   一个"能打开、但报错进虚空"的页面比打不开更坏。
            self._err_page(500, "注入口找不到了",
                "index.html 里没有 <code>%s</code> 这一行,我没法把桥接上去。<br>"
                "<br>要么 index.html 被改过(那就改这个脚本里的 MARKER),"
                "要么你打开的是别的目录。<br><br>现在服务的是:<code>%s</code>"
                % (MARKER.replace("<", "&lt;").replace(">", "&gt;"), src))
            return
        self._send(200, html.replace(MARKER, INJECT), MIME[".html"])

    # -- POST /__note ----------------------------------------------------
    def do_POST(self):
        if urlparse(self.path).path != "/__note":
            self._send(404, "{}", MIME[".json"])
            return
        try:
            n = int(self.headers.get("Content-Length") or 0)
            raw = self.rfile.read(n) if n else b""
            obj = json.loads(raw.decode("utf-8", "replace") or "{}")
        except Exception:
            obj = {}
        level = str(obj.get("level", "?"))
        msg = str(obj.get("msg", ""))
        hit = any(ch in level for ch in "★")
        print("  %s %-12s %s" % ("✗" if hit else "·", level, msg), flush=True)
        self._send(204, b"", "text/plain")


class Server(ThreadingHTTPServer):
    daemon_threads = True

    # ★★ 必须关掉它,而且这是**踩过一次才加的**(2026-10-06 当晚):
    #
    #   Windows 上 `SO_REUSEADDR` 的语义和 Linux **不一样** —— 它不叫
    #   「端口还在 TIME_WAIT,让我复用」,它叫「**允许我抢一个已经在用的端口**」。
    #   Python 的 `HTTPServer` 默认 `allow_reuse_address = 1`,所以在 Windows 上
    #   可以**两个进程同时 LISTEN 同一个端口**,内核把请求随机分给其中一个。
    #
    #   症状:日志「有时有、有时没有」——因为你看的是**另一个进程**的日志。
    #   当晚就是这么被骗了一次:明明 console 里桥跑了,服务端却一条没收到,
    #   差点去修一个根本不存在的 bug(sendBeacon 一直是好的)。
    #
    #   关掉之后:第二个实例 bind 直接失败、当场退出 —— 报错总比分裂好。
    allow_reuse_address = False


def main():
    ap = argparse.ArgumentParser(add_help=True)
    ap.add_argument("--port", type=int, default=8090)
    ap.add_argument("--root", default=DEFAULT_ROOT, help="serve 哪个目录(默认 assets/her)")
    ap.add_argument("--vrm", default=None, help="指定一具身体")
    ap.add_argument("--open", action="store_true", help="顺手用 Edge 的 --app 模式打开")
    a = ap.parse_args()

    root = os.path.abspath(a.root)
    if not os.path.isdir(root):
        sys.exit("✗ 找不到目录:%s" % root)
    if not os.path.isfile(os.path.join(root, "index.html")):
        sys.exit("✗ %s 里没有 index.html" % root)

    vrm, why = pick_vrm(a.vrm, root)
    if vrm:
        vrm = os.path.normpath(vrm)

    Handler.root = root
    Handler.vrm_path = vrm

    # ★ 全部 flush:不 flush 的话 banner 会卡在缓冲区里,而这个脚本的另一半用处
    #   就是「实时看着她说她怎么了」。缓冲会让它看起来像没在跑。
    def say(s=""):
        print(s, flush=True)

    url = "http://127.0.0.1:%d/" % a.port
    say("")
    say("  她的网页版        pid %d" % os.getpid())
    say("  ────────────────────────────────────────────────")
    say("  地址    %s" % url)
    say("  目录    %s" % root)
    if vrm:
        say("  身体    %s" % vrm)
        say("          (%s,%.1f MB)" % (why, os.path.getsize(vrm) / 1048576.0))
    else:
        say("  身体    ✗ 一具都没有 —— 她出不了场。导一个 .vrm 丢桌面再重启。")
    say("")
    say("  换身体:把导出的 .vrm 丢到桌面,重启这个脚本(它自己挑最新的那个)。")
    say("  戳她:  %s?emo=happy      或  ?say=你好   或  ?talk=1" % url)
    say("  看脸:  %s?cycle=1        ← 六种表情每 2.5 秒换一个,一次看完" % url)
    say("  她那边出什么事,都会打在这个终端里。")
    say("")
    if a.open:
        # --app= 是 Edge 的无地址栏模式;整屏更像「她的房间」而不是一个标签页
        edge = None
        for c in ("msedge", r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
                  r"C:\Program Files\Microsoft\Edge\Application\msedge.exe"):
            if c.endswith(".exe"):
                if os.path.isfile(c):
                    edge = c
                    break
            else:
                edge = c
                break
        subprocess.Popen([edge, "--app=" + url, "--start-maximized"])
        say("  · 已经叫了 Edge")

    try:
        srv = Server(("127.0.0.1", a.port), Handler)
    except OSError as e:
        # ★ 这一条是给「已经有另一份在跑」准备的 —— 说清楚,别让人以为它起来了
        sys.exit("✗ 绑不上 127.0.0.1:%d —— %s\n"
                 "  (多半是已经有一份在跑了:看 %s 那个网页能不能打开)"
                 % (a.port, e, url))
    say("  在听了。Ctrl-C 收工。\n")
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        say("\n  收工。")


if __name__ == "__main__":
    main()
