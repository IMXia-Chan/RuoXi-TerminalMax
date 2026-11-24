# -*- coding: utf-8 -*-
"""Windows UI Automation 的 Python 门面 —— 按**名字**找控件,毫秒级、1 像素精度。

## 为什么需要它

`click_element` 走的是「截图 → 视觉模型看图 → 吐出坐标」。眼睛(VLM)的精度
天花板是**一个视觉 token = 32x32 像素**(1920x1080 切 8100 个 16px patch,
2x2 合并)。而开始按钮图标只有 **24x24** —— 比一个 token 还小。

实测(2026-10-02):问模型「任务栏最左边的开始按钮」,它回 y=1041;
用两套独立办法测出来的真值是 **y=1060**。差 19 像素,点击落进任务栏的空白
padding 里,菜单打不开。**这不是它没瞄准,是它在自己那一格里尽力了。**

同一次查询,UIA 给的是 `(0,1040)-(48,1080)`,中心 (24,1060):
**精确到像素,耗时 27 毫秒**。VLM 那一次是 215 秒。差四个数量级。

## 分层策略

1. `uia.py`(本文件)—— UIA 按名字找。覆盖 UWP / Chrome / Electron / 经典 Win32。
2. `list_ui` / `click_ui` 工具 —— 让模型「按名字点」,不再猜坐标。
3. **OCR(`ocr_find`)—— 补在 UIA 和眼之间**。微信是 Qt/DirectUI 自绘,UIA 扒不出
   东西(实测:整个微信窗口只有 1 个后代元素);但字是**画在屏幕上**的,
   Windows 自带 OCR 认得出,给的还是像素级包围盒。实测全屏约 1 秒,
   对比眼的 215 秒 —— 又快了两个数量级。
4. `click_element`(VLM)—— **最后手段**。OCR 也读不出时(纯图标、无文字)才轮到它。

子进程是常驻的:PowerShell 冷启动 + 加载 UIAutomation 程序集要几百毫秒,
而查询本身只要几十毫秒。常驻之后每次查询就只花查询的钱。
"""

import json
import os
import queue
import re
import subprocess
import threading
import time

HERE = os.path.dirname(os.path.abspath(__file__))
PS1 = os.path.join(HERE, "uia.ps1")

# Windows OCR 把**每个汉字当成一个独立的「词」**,回给我们的是「若 息 模 型 清 理」。
# 直接拿它做子串匹配必然 False —— 字明明认得出来,却匹配不上(实测踩到)。
# 所以:匹配前把空白全压掉;给模型看的时候再把中文之间的空格去掉。
_CJK = r"[　-〿一-鿿＀-￯]"
_RE_CJK_SPACE = re.compile(r"(?<=%s)\s+(?=%s)" % (_CJK, _CJK))


def _squash(s):
    """去掉所有空白 —— 只用于**匹配**。"""
    return "".join((s or "").split())


def _tidy(s):
    """把「若 息 模 型」理成「若息模型」,但保留英文词之间的空格。

    只影响给模型看的名字,不影响坐标。
    """
    return _RE_CJK_SPACE.sub("", (s or "").strip())

# PowerShell 冷启动要编译内联的 C#(GetForegroundWindow 那段),实测 2~5 秒。
# 这是**一次性**开销,之后每次查询几十毫秒。
FIRST_CALL_TIMEOUT = 30.0
CALL_TIMEOUT = 15.0
# OCR 比普通查询贵:截全屏 + 放大 2× + 存 PNG + WinRT 解码 + 识别,实测约 1 秒。
# 首次调用还要顺带加载 WinRT 那几个程序集,所以留宽些。
OCR_TIMEOUT = 25.0

_proc = None
_q = None
_lock = threading.Lock()
_reader = None
_started_at = 0.0


def _spawn():
    """起 PowerShell 并挂上读线程。调用方必须持有 _lock。"""
    global _proc, _q, _reader, _started_at
    # -NoProfile:别让用户的 profile 拖慢启动或往 stdout 里吐东西(那会污染协议)
    # -ExecutionPolicy Bypass:策略组可能禁掉 .ps1
    flags = 0x08000000 if os.name == "nt" else 0  # CREATE_NO_WINDOW
    _proc = subprocess.Popen(
        ["powershell", "-NoProfile", "-NonInteractive",
         "-ExecutionPolicy", "Bypass", "-File", PS1],
        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        creationflags=flags,
    )
    _q = queue.Queue()
    _started_at = time.time()

    def pump(stream, q):
        try:
            for line in iter(stream.readline, b""):
                q.put(line)
        except Exception:
            pass
        q.put(None)   # 流断了

    _reader = threading.Thread(target=pump, args=(_proc.stdout, _q), daemon=True)
    _reader.start()
    # stderr 也要抽干,否则管道写满会把子进程堵死(经典死锁)
    threading.Thread(target=pump, args=(_proc.stderr, queue.Queue()), daemon=True).start()


def _ensure():
    """保证有个活着的子进程。调用方必须持有 _lock。"""
    if _proc is None or _proc.poll() is not None:
        _spawn()


def call(req, timeout=None):
    """发一条请求,等一条回复。→ dict。

    **任何异常都转成 {"ok": False, "error": ...}** —— 这个函数的调用方是工具
    执行路径,它要的是「这次没成」,不是把整个请求线程炸掉。
    """
    global _proc
    with _lock:
        _ensure()
        first = (time.time() - _started_at) < 60
        tmo = timeout or (FIRST_CALL_TIMEOUT if first else CALL_TIMEOUT)
        p, q = _proc, _q
        try:
            # ensure_ascii=True(默认):中文全变成 \uXXXX 转义发过去。
            # 这样**完全绕开** stdin 的编码问题 —— 不用赌 PowerShell 那边
            # 把重定向的 stdin 当 UTF-8 还是 GBK 解。
            p.stdin.write((json.dumps(req) + "\n").encode("ascii"))
            p.stdin.flush()
        except Exception as e:
            _proc = None
            return {"ok": False, "error": "UIA 助手没接上:%s" % e}

        deadline = time.time() + tmo
        while True:
            remain = deadline - time.time()
            if remain <= 0:
                # 卡住了就把它掐了,下次调用会自己重启 —— 不能让它一直挂在
                # 那里占着锁,那会把后面所有工具调用一起拖死。
                try:
                    p.kill()
                except Exception:
                    pass
                _proc = None
                return {"ok": False, "error": "UIA 查询超时(%.0fs),已重启助手" % tmo}
            try:
                line = q.get(timeout=remain)
            except queue.Empty:
                continue
            if line is None:
                _proc = None
                return {"ok": False, "error": "UIA 助手退出了"}
            s = line.decode("utf-8", "replace").strip()
            if not s:
                continue
            try:
                return json.loads(s)
            except Exception:
                # 非协议输出(PowerShell 的警告等)跳过,继续等真正的回复
                continue


def ping():
    return call({"cmd": "ping"})


def find(name, window=None, ctype=None, limit=5):
    """按名字找控件。→ [{"name","type","offscreen",...}, ...],按匹配度排。

    **连离屏的一起搜**。模型问的是「有没有 X」,不是「屏幕上看不看得见 X」——
    实测 VS Code 一个窗口 972 个可点控件里 909 个(94%)是离屏的,以前被
    IsOffscreen 一刀切掉,于是「明明在,却说没有」。

    屏幕内的一律排在离屏的前面(看得见的动起来更可预测)。

    ⚠️ `offscreen=True` 的元素**坐标不可信**:它不是空的,而是「虚拟滚动空间」
    里的天文数字(实测一个按钮 y=-64728)——

    照着点,鼠标会飞到屏幕外面去。**别拿它的 cx/cy 去点**,
    该走 [invoke],让控件自己动作。
    """
    r = call({"cmd": "find", "name": name, "window": window,
              "type": ctype, "limit": limit})
    return r.get("elements") or [], r


def probe(x, y, cache=None):
    """「屏幕上这一点是不是一个能打字的地方?」→ {"ok","editable","cursor","at","focus"}。

    手机端的用法:用户点了一下镜像画面(那一击已经先落到电脑上了),
    手机接着问这一句,回来说能打字就**弹出手机自己的输入法** ——
    于是「点电脑的输入框」和「手机键盘」变成同一个动作,
    不用先去按钮里找一个「键盘」。

    **判据是 `editable`,不是 `at`。** 它是三层合起来的:
      ① `cursor` —— 鼠标指针是不是 I 型。**这一条说了算**,因为不管界面
         谁画的都有效,连微信那种 UIA 完全看不见的自绘界面也认。
      ② `at`     —— 这一点上是什么控件(最小面积优先)。说得清"点中的是哪个"。
      ③ `focus`  —— 现在谁有键盘焦点。**只当补充,不当判据**:浏览器里它
         一律回页面 Document,拿它作准 = 在网页上点哪儿都弹键盘。

    ★ 已知的**假阳性**:浏览器里可选中的正文也是 I 型指针 —— 点一段网页
      文字也会弹键盘。这是**故意的**:误弹按一下返回就没了,漏判则整个功能
      不成立,两个方向的代价不对称。所以宁可多弹。
    ★ 这是**只读**的:不点、不打字、不改任何东西。它和 `patterns` 同一类。
    ★ 电脑端不是 Windows / PowerShell 起不来,会回 ok=false —— 调用方据此
      **当成一次普通点击**,不要弹键盘。
    """
    req = {"cmd": "probe", "x": int(x), "y": int(y)}
    if cache is not None:
        req["cache"] = bool(cache)
    return call(req, timeout=CALL_TIMEOUT)


def invoke(name, window=None):
    """让**控件自己动作**,而不是点像素。

    这是「知道优先」真正的样子:有控件树的时候,根本不必知道它在哪。
    对离屏控件更是**唯一**可行的路 —— 它没画出来,坐标是虚拟滚动空间里的
    天文数字,拿它去点只会点到屏幕外面。

    PowerShell 那边依次试 Invoke / Select / Toggle / Expand;都不行就
    `ScrollIntoView()` 把它滚进视野 —— 滚动由控件自己算,比我们猜「滚几屏」准。

    → (结果 dict, 原始回包)。两种成功:
        {"ok":True, "action":"Invoke", ...}                   已经执行,没碰鼠标
        {"ok":True, "action":"ScrollIntoView", "need_click":True, "cx":..,"cy":..}
                                                              —— 已滚进视野,该由调用方点这组坐标
    """
    r = call({"cmd": "invoke", "name": name, "window": window})
    return r, r


def set_value(name, value, window=None):
    """把一个值**直接写进**某个控件(UIA ValuePattern),不点、不打字、不管焦点。

    ★ 这是「跨应用读写」里**写**的那一半:type 往当前焦点框敲字,焦点一跑就敲进
      别处;这里是**指名道姓**写某个控件,前台是谁都不影响。
    ★ 只对支持 ValuePattern 且**不是只读**的控件有效(输入框、可编辑单元格)。
      不支持就如实回 ok=false —— 调用方该退回 click_ui + type,别硬来。
    ★ 回执带 `old` / `now` / `verified`:写完当场读回来比一次。
      「发出去了」不许当成「写进去了」—— SetValue 不抛异常只说明它收下了。
      `verified` 可能是 `'unknown'`(读不回来了),那**不等于**写成功。

    → (结果 dict, 原始回包)
    """
    r = call({"cmd": "setvalue", "name": name, "value": value, "window": window})
    return r, r


def patterns(name, window=None):
    """只读诊断:目标支持哪些 UIA Pattern、包围盒长什么样。**不产生副作用**。

    用来回答「这个控件能不能直接命令」。→ (元素列表, 原始回包)
    """
    r = call({"cmd": "patterns", "name": name, "window": window})
    return r.get("hits") or [], r


def list_elements(window=None, limit=40):
    """列出一个窗口里可点的控件。window=None 时看**前台**窗口。"""
    r = call({"cmd": "list", "window": window, "limit": limit})
    return r.get("elements") or [], r


def ocr(window=None, limit=200):
    """整屏识字。→ [{"name","type":"Text","x","y","w","h","cx","cy"}, ...]

    `window` 给了就只在那个窗口的矩形里取(避免捞到别的窗口的字)。
    """
    r = call({"cmd": "ocr", "window": window, "limit": limit}, timeout=OCR_TIMEOUT)
    return r.get("lines") or [], r


def ocr_find(name, window=None, limit=5):
    """在屏幕**文字**里找目标 —— 不依赖控件信息,自绘界面也认。

    这是 UIA 和「眼」之间的那一层。微信是 Qt/DirectUI,整个窗口 UIA 只暴露
    1 个后代元素,按名字找控件那条路在它面前是瞎的;但字是**画在屏幕上**的,
    OCR 认得出,而且给的同样是像素级包围盒(实测全屏 0.5 秒,眼是 220 秒)。

    排序与 [find] 同构:**整行完全相等** > 子串命中,子串里再由短到长 ——
    「发送」这种按钮标签很短,含「发送」两个字的长句子多半是正文,不该优先。

    → (元素列表, 原始回包)。元素结构与 [find] 完全一致,调用方不用分情况。
    """
    name = (name or "").strip()
    if not name:
        return [], {"ok": False, "error": "没给要找的文字"}
    lines, raw = ocr(window=window, limit=200)
    if not raw.get("ok"):
        return [], raw

    # 这次 OCR **总共**认出多少行 —— 不是命中多少,是"屏幕上到底有没有字"。
    # 上层拿它判断失败的性质:认出 36 行却没有目标 = 这个界面不暴露结构,
    # 字画在屏幕上但目标不在此屏(自绘窗口 / 内容滚出视野);
    # 一行都没认出 = 窗口是空的或没渲染。两者该走的策略完全不同。
    # 顺手计数,零额外开销(OCR 本来就必须跑)。
    total = len(lines)

    want = _squash(name)
    exact, partial = [], []
    for e in lines:
        t = (e.get("name") or "").strip()
        flat = _squash(t)
        if not flat:
            continue
        e = dict(e)
        e["name"] = _tidy(t)          # 只改给模型看的名字,坐标不动
        if flat == want:
            exact.append(e)
        elif want in flat:
            partial.append(e)
    # 子串命中里,短行更像按钮/标签,长行更像正文 —— 短的自然排前面
    partial.sort(key=lambda e: len(_squash(e["name"])))
    hits = exact + partial
    return hits[:limit], {"ok": True, "count": len(hits),
                          "total": total,
                          "window": raw.get("window") or ""}


def describe(elements, max_n=40):
    """把元素表压成给模型看的紧凑文本。

    **必须紧凑**:这是要塞进 4B 模型 8192 上下文的。每条一行、坐标只留整数中心,
    比 JSON 省一半以上。同时把坐标一起给出去 —— 模型可以直接说「点第几个」,
    也可以把坐标抄进 click_at。
    """
    lines = []
    for i, e in enumerate(elements[:max_n], 1):
        lines.append("%d. %s（%s）→ (%d, %d)" % (i, e["name"], e["type"], e["cx"], e["cy"]))
    return "\n".join(lines)
