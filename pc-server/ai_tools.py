"""AI 操控电脑 —— 工具执行器。

手机端 AiAgent 让本地模型产出「工具调用」,经签名指令 `AI <seq> <b64 json>` 发到这里执行,
执行结果(可能是窗口列表、也可能是截图)再以 `AIR <b64 json>` 回写手机。

设计原则:
- **全部用户态实现**(ctypes / subprocess / os.startfile),不引入 pywinauto 等第三方依赖,
  也不碰任何需要管理员或改系统设置的东西 —— 不影响保修。
- **工具白名单**:只认下面 TOOLS 里列出的工具,别的名字一律拒绝。AI 不能凭空长出新能力。
- **「打开应用」靠开始菜单索引自动发现**(用户级 + 全局 ProgramData 两处 .lnk),
  免去手工维护 exe 路径;apps.json 只用来放别名/兜底,可热加载。

坐标约定(重要):
- `screenshot` 回的是**缩放后**的 JPEG,连同 `w`/`h`(图尺寸)和 `screen_w`/`screen_h`(真实屏幕)一起给。
- 模型在图坐标系里给出落点,调用方负责换算成**真实屏幕像素**再调 `click_at`;
  `click_at` 只接受真实屏幕像素。
"""

import base64
import ctypes
import difflib
import hashlib
import io
import json
import os
import re
import shutil
import socket
import subprocess
import sys
import time

IS_WINDOWS = sys.platform == "win32"

# PyInstaller 单文件打包后 __file__ 在临时解包目录(退出即删),持久化文件要落在 exe 旁边。
# 与 server.py 同一套判定,保证 apps.json 和 secret.json 落在一起。
if getattr(sys, "frozen", False):
    HERE = os.path.dirname(os.path.abspath(sys.executable))
else:
    HERE = os.path.dirname(os.path.abspath(__file__))

APPS_PATH = os.path.join(HERE, "apps.json")
APP_INDEX_TTL = 300.0     # 开始菜单索引缓存 5 分钟,期间新增的快捷方式最多等这么久
SHOT_MAX_WIDTH = 1280     # 发给视觉模型的截图上限宽度(越大越准、越慢越费流量)
SHOT_JPEG_QUALITY = 80
SHOT_MAX_WINDOWS = 8      # 窗口列表最多回这么多条,免得刷爆模型上下文
#   ↑ 原来 24。实测用户常开 10 个窗口,回给模型 543 字符 / 194 token,
#     而这一坨要**逐字过一遍 4B 模型的预填**(手机上 48ms/token,等于白等 9 秒),
#     只为了回答「记事本开着没」。收到 8 条 + 去掉 proc 字段后砍掉约一半。

if IS_WINDOWS:
    user32 = ctypes.windll.user32
    kernel32 = ctypes.windll.kernel32
    # 64 位下句柄是 8 字节,不显式声明 argtypes/restype 会被按 int 截断 —— 必须设。
    kernel32.OpenProcess.restype = ctypes.c_void_p
    kernel32.OpenProcess.argtypes = [ctypes.c_uint32, ctypes.c_int, ctypes.c_uint32]
    kernel32.CloseHandle.argtypes = [ctypes.c_void_p]
    kernel32.QueryFullProcessImageNameW.argtypes = [
        ctypes.c_void_p, ctypes.c_uint32, ctypes.c_wchar_p, ctypes.POINTER(ctypes.c_uint32)
    ]
    user32.GetWindowTextLengthW.argtypes = [ctypes.c_void_p]
    user32.GetWindowTextW.argtypes = [ctypes.c_void_p, ctypes.c_wchar_p, ctypes.c_int]
    user32.IsWindowVisible.argtypes = [ctypes.c_void_p]
    user32.IsWindow.argtypes = [ctypes.c_void_p]
    user32.GetForegroundWindow.restype = ctypes.c_void_p
    user32.SetForegroundWindow.argtypes = [ctypes.c_void_p]
    user32.ShowWindow.argtypes = [ctypes.c_void_p, ctypes.c_int]
    user32.GetWindowThreadProcessId.argtypes = [ctypes.c_void_p, ctypes.POINTER(ctypes.c_uint32)]
    _WNDENUMPROC = ctypes.WINFUNCTYPE(ctypes.c_bool, ctypes.c_void_p, ctypes.c_void_p)

    # DPI 感知:不开的话,系统缩放>100% 时 GetSystemMetrics/GetCursorPos 报的是「逻辑像素」,
    # 而截图抓的是「物理像素」,两者对不上 -> 按截图坐标点过去会偏。开了三者单位统一。
    # 只能设一次(重复设会失败,忽略即可),所以放在模块导入时做。
    try:
        ctypes.windll.shcore.SetProcessDpiAwareness(2)   # PROCESS_PER_MONITOR_DPI_AWARE
    except Exception:
        try:
            ctypes.windll.user32.SetProcessDPIAware()
        except Exception:
            pass

SW_RESTORE = 9
PROCESS_QUERY_LIMITED_INFORMATION = 0x1000
VK_MENU = 0x12
KEYEVENTF_EXTENDEDKEY = 0x0001
KEYEVENTF_KEYUP = 0x0002

# 媒体/音量虚拟键(全是扩展键,发的时候要带 KEYEVENTF_EXTENDEDKEY)
VK_MEDIA_NEXT_TRACK = 0xB0
VK_MEDIA_PREV_TRACK = 0xB1
VK_MEDIA_STOP = 0xB2
VK_MEDIA_PLAY_PAUSE = 0xB3
VK_VOLUME_MUTE = 0xAD
VK_VOLUME_DOWN = 0xAE
VK_VOLUME_UP = 0xAF

MEDIA_ACTIONS = {
    "play_pause": VK_MEDIA_PLAY_PAUSE,
    "next": VK_MEDIA_NEXT_TRACK,
    "prev": VK_MEDIA_PREV_TRACK,
    "stop": VK_MEDIA_STOP,
    "mute": VK_VOLUME_MUTE,
    "volume_up": VK_VOLUME_UP,
    "volume_down": VK_VOLUME_DOWN,
}


# ---------------------------------------------------------------------------
# 键盘虚拟键(补 server.py 的缺口:type_text 只会发 Unicode 字符,发不了
# Ctrl+C / 媒体键 / 方向键这类没有对应字符的键)
# ---------------------------------------------------------------------------
def send_vk(vk, extended=False, times=1):
    """按一下某个虚拟键(按下+抬起)。媒体键/音量键传 extended=True。"""
    if not IS_WINDOWS:
        return
    flags = KEYEVENTF_EXTENDEDKEY if extended else 0
    for _ in range(max(1, int(times))):
        user32.keybd_event(vk, 0, flags, 0)
        user32.keybd_event(vk, 0, flags | KEYEVENTF_KEYUP, 0)


def send_hotkey(vk, modifiers=()):
    """按住 modifiers(VK 列表)→ 按 vk → 松开。例:Ctrl+S = send_hotkey(0x53, [0x11])。"""
    if not IS_WINDOWS:
        return
    for m in modifiers:
        user32.keybd_event(m, 0, 0, 0)
    user32.keybd_event(vk, 0, 0, 0)
    user32.keybd_event(vk, 0, KEYEVENTF_KEYUP, 0)
    for m in reversed(list(modifiers)):
        user32.keybd_event(m, 0, KEYEVENTF_KEYUP, 0)


# ---------------------------------------------------------------------------
# 键盘自动化(用户 2026-10-02 定的安全边界)
# ---------------------------------------------------------------------------
# 背景:微信是 Qt/DirectUI 自绘,UIA 扒不出控件、视觉又慢又飘。要往「文件传输助手」
# 发消息,唯一稳的办法是模拟键盘:Ctrl+F 搜会话 → 打字 → 回车。
#
# 安全边界(用户选的是「白名单按键 + 确认打字」):
#   - **组合键只放开白名单里这些导航/编辑键**,修饰键只有 Ctrl / Alt 两个(见
#     MODIFIER_KEYS)—— 白名单外的键(如 Ctrl+W)永远到不了这里。
#   - **打字(type)不限内容**,但手机端会在发出来之前先弹确认,这里只负责执行
#     已经过用户点头的输入。所以 type 不做语义过滤,只限长度防失控。
#
# ★★ 2026-10-08 用户拍板:**把 alt+f4 加进白名单**。
#   原话是「关微信的时候,我让她关微信她就在搜索框中关微信」,而根因是工具箱里
#   压根没有「关窗口」这个动作 —— 她只能去搜,于是把「关闭」两个字打进了微信
#   自己的搜索框(详见 AiAgent.resolveMiss 里那一段的注释)。问他要不要给这个
#   能力,他选的是「**给,直接关不问**」(不给确认框,点一下直接关)。
#
#   ★ 形状是**直接进白名单**(他的原话:「组合键不管它,让它加白名单」):
#     `alt` 进 MODIFIER_KEYS、`f4` 进 SAFE_KEYS,不另开一张表。
#
# ★★ 同一天他第二次收紧:**「组合键是可以用,但是危险的组合键不能」**。
#   所以这件事最后是**两张表一起管**(见下面 DANGEROUS_COMBOS):
#     白名单(SAFE_KEYS / MODIFIER_KEYS)管「这个键许不许用」;
#     危险表(DANGEROUS_COMBOS)管「这个组合会不会毁掉他手上的东西」。
#   ★ 只放白名单是不够的 —— 因为 alt 一旦成了通用修饰键,`ctrl+f4`(关文档)
#     这种「白名单里的键拼出来的坏组合」就自动合法了。**这正是他补那一句的原因。**
#
#   ★ 它照旧受焦点闸管(`hotkey` 一直在 _GUARDED_TOOLS 里),所以「任务窗口被
#     切走」时这一下按不出去 —— 不会关掉他正在用的别的窗口。

# 单键白名单。字母键 VK = 大写 ASCII。
VK_RETURN = 0x0D
VK_TAB = 0x09
VK_ESCAPE = 0x1B
VK_BACK = 0x08
VK_DELETE = 0x2E
VK_HOME = 0x24
VK_END = 0x23
VK_PAGEUP = 0x21
VK_PAGEDOWN = 0x22
VK_LEFT = 0x25
VK_UP = 0x26
VK_RIGHT = 0x27
VK_DOWN = 0x28
VK_CONTROL = 0x11
VK_F4 = 0x73

SAFE_KEYS = {
    "enter": VK_RETURN, "esc": VK_ESCAPE, "tab": VK_TAB,
    "backspace": VK_BACK, "delete": VK_DELETE,
    "up": VK_UP, "down": VK_DOWN, "left": VK_LEFT, "right": VK_RIGHT,
    "home": VK_HOME, "end": VK_END, "pageup": VK_PAGEUP, "pagedown": VK_PAGEDOWN,
    # ★ 2026-10-08 进的:配 alt 就是「关掉最前面那个窗口」。为什么呢 ——
    #   见本段开头「关微信」那一段(工具箱里原先没有关窗口这个动作)。
    "f4": VK_F4,
    # Ctrl+字母:查找/编辑/保存/发送。特意**不放** w(关标签)、q 等有破坏性的。
    # l 是 2026-10-03 补的:在浏览器里它是「聚焦地址栏」(地址栏同时就是搜索框)——
    # 而浏览器里**没有别的键能替代它**(Ctrl+F 是「本页内查找」,不是搜网页)。
    # 别的应用里 Ctrl+L 也都是无害的(Word 左对齐、编辑器选中整行),不是破坏性操作。
    "a": 0x41, "c": 0x43, "f": 0x46, "l": 0x4C, "n": 0x4E, "s": 0x53,
    "v": 0x56, "x": 0x58, "y": 0x59, "z": 0x5A,
}

# 修饰键白名单(2026-10-08 起多了 alt)。**只有这里列出来的才当得了修饰键** ——
# 名字不在里头的(win / shift…)会被当成主键,然后走「一次只能按一个主键」那条被拒。
MODIFIER_KEYS = {
    "ctrl": VK_CONTROL, "control": VK_CONTROL,
    "alt": VK_MENU,
}

# ★★ 危险组合表 —— 2026-10-08 用户第二次收紧:「**组合键是可以用,但是危险的
#   组合键不能**」。这是**白名单之上**的第二道,不是白名单的替代。
#
# 判据只有一条:**这一下会不会毁掉他手上的东西**(关掉带未保存内容的文档、
# 一次删掉一整段)。**不按「看起来吓人」拦** —— 那会越拦越多,最后变成一张
# 靠感觉写的表,而这个项目的规矩是「形状会撞,表不会」:表要收得回、说得清。
#
# | 组合 | 危险在哪 |
# |---|---|
# | `ctrl+f4` | 关的是**文档 / 标签页**,可能带着他没保存的活。★ 它和 `alt+f4` 只差一个修饰键,但 alt+f4 关的是**窗口本身**,而且是他点名要的那个动作 —— **所以 alt+f4 不在这张表里** |
# | `ctrl+delete` / `alt+delete` | 一次删掉**一整段**(一个词 / 到行尾) |
# | `ctrl+backspace` / `alt+backspace` | 同上,只是往前删 |
#
# ★ 故意**不收**的(写在这儿,免得以后有人「顺手」加进去):
#   - `alt+f4` —— 用户点名要的能力,见上面那段注释。
#   - `delete` / `backspace` **单按** —— 那是正常编辑,白名单里一直有。
#   - `ctrl+z` / `ctrl+s` —— 撤销/保存白名单里一直有(2026-10-02 就定的),
#     「存一下」还是正当任务。**这张表不是用来重审白名单的。**
#   - `alt+tab` / `alt+方向键` —— 切窗口、翻页,不毁东西。
#
# ★ 它**拦不住**什么(要说白,不能让它给人假的安心):「ctrl+a 全选 + delete」
#   这种**两步**组合它看不见 —— 那两步各自都是白名单里的合法键。那一类归
#   三层风险闸管,不是这一层。
# ★★ 改这张表和白名单同一个级别:**要单独说、单独回退**。
DANGEROUS_COMBOS = {
    "ctrl+f4",
    "ctrl+delete", "alt+delete",
    "ctrl+backspace", "alt+backspace",
}


def _combo_key(mods, mains):
    """把一次按键归一化成查表用的样子(`["alt"], ["f4"]` → `"alt+f4"`)。

    归一化三件事,全是「人写着不一样、判据里该是同一个」:
    **顺序**(`f4+alt`)、**大小写**(调用方已经 lower 过了)、
    以及 **`control` 就是 `ctrl`**(MODIFIER_KEYS 里两个名字指的是同一个键,
    不归一化的话 `control+f4` 会从危险表里漏过去)。
    """
    canon = sorted("ctrl" if m == "control" else m for m in mods)
    return "+".join(canon + list(mains))


def allowed_keys():
    """白名单里的键 —— 失败回执里报给模型,免得它照原样重试。"""
    return sorted(SAFE_KEYS.keys())


# type 一次最多输入这么多字符。发消息/搜会话够用,再多就是失控了。
TYPE_MAX_LEN = 500


def _press_hotkey(keys_str):
    """按白名单组合键。格式:「ctrl+f」「alt+f4」或单个键。→ (ok, err_msg)。"""
    if not IS_WINDOWS:
        return True, ""
    parts = [p.strip().lower() for p in str(keys_str).split("+") if p.strip()]

    mods = [p for p in parts if p in MODIFIER_KEYS]
    mains = [p for p in parts if p not in MODIFIER_KEYS]
    if len(mains) != 1:
        return False, "一次只能按一个主键(修饰键只认 ctrl / alt)"
    if len(mods) > 1:
        return False, "一次只支持一个修饰键(ctrl 或 alt,不能两个一起)"
    main = mains[0]
    if main not in SAFE_KEYS:
        return False, "按键「%s」不在安全白名单里" % main
    # ★ 危险组合表在白名单**之后**查 —— 这样「按键不在白名单里」和「这个组合
    #   危险」两句回执各说各的,模型改法不同(前者换键,后者换做法)。
    combo = _combo_key(mods, mains)
    if combo in DANGEROUS_COMBOS:
        return False, ("组合键「%s」不能按 —— 它会关掉或删掉东西。"
                       "要关掉整个窗口就用 hotkey(keys=\"alt+f4\")。" % combo)
    vk = SAFE_KEYS[main]
    if mods:
        send_hotkey(vk, [MODIFIER_KEYS[m] for m in mods])
    else:
        send_vk(vk)
    return True, ""


# ---------------------------------------------------------------------------
# 窗口枚举
# ---------------------------------------------------------------------------
def _proc_name(pid):
    """由 PID 拿进程可执行文件名(拿不到就空串)。"""
    h = kernel32.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, False, pid)
    if not h:
        return ""
    try:
        buf = ctypes.create_unicode_buffer(1024)
        size = ctypes.c_uint32(1024)
        if kernel32.QueryFullProcessImageNameW(h, 0, buf, ctypes.byref(size)):
            return os.path.basename(buf.value)
        return ""
    except Exception:
        return ""
    finally:
        kernel32.CloseHandle(h)


def _window_title(hwnd):
    n = user32.GetWindowTextLengthW(hwnd)
    if n <= 0:
        return ""
    buf = ctypes.create_unicode_buffer(n + 1)
    user32.GetWindowTextW(hwnd, buf, n + 1)
    return buf.value


def _enum_windows():
    """所有「可见且有标题」的顶层窗口 -> [{"title":..,"proc":..,"hwnd":..}]。

    `proc` / `hwnd` 只在本模块内部用(去重、拉前台)。**往外回给模型时一律剥掉** ——
    理由见 [list_windows]。
    """
    out = []

    def cb(hwnd, _lparam):
        try:
            if not user32.IsWindowVisible(hwnd):
                return True
            title = _window_title(hwnd)
            if not title.strip():
                return True
            pid = ctypes.c_uint32()
            user32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
            # pid 也带上:同一个应用常有多个**标题一模一样**的窗口 —— 微信的主窗口和
            # 扫码登录页都叫「微信」,字符串层面完全不可分,只能靠进程年龄认谁是谁。
            # 见 [_window_already_open]。
            out.append({"title": title, "proc": _proc_name(pid.value),
                        "hwnd": hwnd, "pid": pid.value})
        except Exception:
            pass
        return True

    user32.EnumWindows(_WNDENUMPROC(cb), 0)
    return out


def list_windows():
    """→ {"windows": [{"title":..}]}(最多 SHOT_MAX_WINDOWS 条)。

    **只回标题,不回进程名。** 理由:这份结果每轮都原样塞回模型上下文,手机上要
    *逐字过一遍 4B 模型的预填*(≈48ms/token,见 [[ruoxi-embedded-llama-server]])。
    模型要回答的只有「这窗口在不在 / 该切哪个」,靠标题就够;`"proc":"WeChat.exe"`
    这截纯属白等几秒。10 个窗口实测 543 字符 → 现在约 270,第 2 轮预填砍掉小一半。
    """
    if not IS_WINDOWS:
        return {"windows": []}
    wins = _enum_windows()
    # 同一进程的多个窗口(如浏览器)标题不同,都保留;但去掉标题完全重复的
    seen = set()
    out = []
    for w in wins:
        key = w["title"]
        if key in seen:
            continue
        seen.add(key)
        out.append({"title": w["title"]})
        if len(out) >= SHOT_MAX_WINDOWS:
            break
    return {"windows": out}


def _foreground_window():
    """前台窗口标题(没有就 None)。同样只回标题,原因见 [list_windows]。"""
    hwnd = user32.GetForegroundWindow()
    if not hwnd:
        return None
    return _window_title(hwnd)


def focus_window(substr):
    """按标题子串把窗口拉到前台。找不到时回一份相近候选,方便模型自己纠正。"""
    if not IS_WINDOWS:
        return {"ok": False, "error": "仅 Windows 支持"}
    target = (substr or "").strip().lower()
    if not target:
        return {"ok": False, "error": "没给窗口关键词"}
    wins = _enum_windows()
    exact = [w for w in wins if w["title"].lower() == target]
    prefix = [w for w in wins if w["title"].lower().startswith(target)]
    contain = [w for w in wins if target in w["title"].lower()]
    hit = (exact or prefix or contain)
    if not hit:
        names = [w["title"] for w in wins]
        near = difflib.get_close_matches(substr, names, n=5, cutoff=0.3)
        # ★ 这条 hint 是给两个**真踩过的错**写的(2026-10-03),它们长得一模一样
        # ——「没找到标题含 X 的窗口」—— 但下一步该做的事完全相反:
        #   (1) 模型把「文件传输助手」这种**会话名**当成窗口标题去 focus_window;
        #   (2) 应用**根本没开着**(微信没在跑,当然没有它的窗口)。
        # 真机上 (2) 白烧了一整轮:模型先 focus_window 失败、下一轮才 open_app、
        # 再下一轮又 focus_window 一次。所以两种可能都得在**出错的那一刻**说出来,
        # 而不是写进 SYSTEM_PROMPT —— 提示词是给没犯错时的它看的,这里才是它真在找路的时候。
        return {"ok": False, "error": "没找到标题含「%s」的窗口" % substr,
                "hint": "两种可能,挑一个试:(1) 「%s」不是一个窗口标题,而是某个应用**里面**"
                        "的条目(聊天会话、文件、邮件、网页标签)—— 那就先 focus_window 那个"
                        "应用,再用 search 在它里面搜这个名字;(2) 这个应用**根本没开着** —— "
                        "那就直接 open_app(name=%s),不用先 focus_window,打开后它自己就在前台。"
                        % (substr, substr),
                "candidates": near or names[:8]}
    w = hit[0]
    if not _focus_hwnd(w["hwnd"]):
        return {"ok": False, "error": "找到了窗口但抢不到前台焦点",
                "window": {"title": w["title"]}}
    _set_task_window(w["hwnd"], w["title"])   # 切到哪儿,本任务就在哪儿动手
    return {"ok": True, "focused": {"title": w["title"]}}


def _focus_hwnd(hwnd, wait_ms=600):
    """把某个窗口拉到前台(最小化了就先还原)。**返回的「成功」是核验过的**:
    真的变成前台窗口了才回 True。

    ★ 为什么非核验不可:`SetForegroundWindow` 是**异步**的 —— 它的返回值只说
    「系统收下了这个请求」,前台可能过几毫秒才真的切过去;而且它照样可能返回 0
    却切成功了。2026-10-03 真机上撞到了它的代价:

        ← type  失败:焦点在 VS Code 上…(判「顶不回来」,拒绝输入)
        ← hotkey 成功              (同一秒、几毫秒后,判「已经是前台」,放行)

    **一次任务里两个工具对同一件事给出相反的结论。** 根子就是那个立刻回读:
    第一次读到的是旧值。改成轮询等待之后,两者看到的才是同一个事实。

    抽成独立函数是因为「打开一个已经在跑的应用」也要走这一步 —— 见 [_launch]。
    """
    try:
        user32.ShowWindow(hwnd, SW_RESTORE)   # 最小化了就先还原(它也是异步的)
    except Exception:
        pass
    if user32.GetForegroundWindow() == hwnd:
        return True                       # 已经是前台了,别白按一下
    user32.SetForegroundWindow(hwnd)
    if _await_foreground(hwnd, wait_ms):
        return True
    # 前台锁:某些情况下系统不让后台进程抢焦点。先空按一下 Alt,通常就能拿到前台权限。
    user32.keybd_event(VK_MENU, 0, 0, 0)
    user32.keybd_event(VK_MENU, 0, KEYEVENTF_KEYUP, 0)
    user32.SetForegroundWindow(hwnd)
    return _await_foreground(hwnd, wait_ms)


def _await_foreground(hwnd, wait_ms):
    """等它真的变成前台。等到了回 True,等不到回 False(不抛)。"""
    deadline = time.time() + wait_ms / 1000.0
    while True:
        if user32.GetForegroundWindow() == hwnd:
            return True
        if time.time() >= deadline:
            return False
        time.sleep(0.03)


# ---------------------------------------------------------------------------
# 「本次任务在操作哪个窗口」—— 输入类工具的统一守门
# ---------------------------------------------------------------------------
# 为什么要有这个:2026-10-03 真机上,模型 `open_app 微信` → `click_ui 文件传输助手`
# → `click_ui 发送`,中途那 24 秒本地模型超时里用户切回了 VS Code,于是
# `search` 的 Ctrl+F **打进了编辑器**(在 VS Code 里就是「往上滑」),`click_ui`
# 又在全桌面范围里找到并点掉了东西,最后模型还报「已成功发送 hello world」。
#
# 根子:**工具层从来没有「我在操作哪个窗口」这个概念** —— `type`/`hotkey`/`search`/
# `scroll` 一律「打到当前焦点」,`click_ui` 不给 window 时全桌面找。而「当前焦点」
# 在模型思考的这几十秒里随时会被人拿走。
#
# 所以:凡是 `open_app` / `focus_window` 成功落到的窗口,记成**本任务的操作窗口**,
# 之后每次要动手前先查一次前台。不在就**先顶回一次**(用户只是一时切走,常见);
# 顶回了还被切走,说明用户是**故意**的 —— 那就拒绝,而不是继续往别人窗口里打字。
#
# 这是**机制**,由代码兜死,不靠叮嘱模型「记得先 focus_window」—— 4B 记不住,
# 而且那正是这次翻车时它以为自己已经在做的事。见 [[ruoxi-teacher-learning-loop]]。
_task = {"hwnd": None, "title": "", "words": []}
_refocused_once = False     # 「顶回一次」的额度,换任务窗口时重置

# 这些工具都是「往当前焦点里送东西」,一律过守门。`open_app` / `focus_window` 本身
# 就是在**决定**焦点在哪,不能拦;`get_state` / `screenshot` 只读,无所谓;
# `media` 发的是全局媒体键,跟焦点无关。
# ★ set_value 也在这里:它虽然不碰鼠标键盘,但**改的是别的应用里的内容** ——
#   焦点被切走时写进去,就是往别人的框里灌字。和 type 一模一样要过焦点闸。
_GUARDED_TOOLS = ("click_at", "click_ui", "type", "hotkey", "search", "scroll",
                  "set_value")


def _pid_of(hwnd):
    """窗口属于哪个进程(拿不到回 0)。**0 绝不用来判等** —— 「不知道」不等于「是同一个」。"""
    try:
        pid = ctypes.c_uint32(0)
        user32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
        return int(pid.value)
    except Exception:
        return 0


def _same_app(a, b):
    a, b = _pid_of(a), _pid_of(b)
    return bool(a) and a == b


def _adopt_task_window(w):
    """认领一个窗口当任务窗口。**不重置顶回额度** —— 只给守门内部迟到认领用。"""
    _task["hwnd"] = w["hwnd"]
    _task["title"] = w.get("title") or _window_title(w["hwnd"]) or ""


def _set_task_window(hwnd=None, title="", words=()):
    """钉下本任务的操作窗口。

    `hwnd=None` 表示「知道要找谁、但它还没露面」(刚启动的应用)——
    存下候选词,守门第一次跑到时再认领,认不到就不守(见 [_guard_focus])。
    """
    global _refocused_once
    _task["words"] = [w for w in words if w]
    _refocused_once = False
    if hwnd:
        _task["hwnd"] = hwnd
        _task["title"] = title or _window_title(hwnd) or ""
    else:
        _task["hwnd"] = None
        _task["title"] = ""


def _clear_task_window():
    global _refocused_once
    _task.update({"hwnd": None, "title": "", "words": []})
    _refocused_once = False


def _guard_focus():
    """动手之前查一次前台。可以动手就回 None,否则回一句拒绝理由。

    **不知道就别猜**:任务窗口句柄失效(窗口关了)、或者压根没钉过任务窗口,
    都回 None 放行 —— 守门退回改之前的行为,而不是胡乱拦。
    猜错的代价是每一次输入都打在错的窗口上,比不守更糟。
    """
    global _refocused_once
    if not IS_WINDOWS:
        return None
    hwnd = _task["hwnd"]
    if hwnd:
        # 窗口可能已经被关掉了。IsWindow 说没了 → 清掉,不猜。
        if not user32.IsWindow(hwnd):
            _clear_task_window()
            return None
    else:
        # 还没认领(刚启动的应用)。用候选词试一次,这次是白认 —— 认到就钉上。
        if not _task["words"]:
            return None
        w = _window_already_open(_task["words"])
        if w is None:
            return None              # 还没露面 → 守不了,不拦
        _adopt_task_window(w)
        hwnd = _task["hwnd"]
    fg = user32.GetForegroundWindow()
    if fg == hwnd:
        return None
    # 同一个应用的其他窗口:对话框、次级窗口都是**独立顶层窗口**,但进程是同一个。
    # 那还是「我在操作的那个应用」,拦下来只会让 AI 看起来坏了(点不动保存对话框)。
    if _same_app(fg, hwnd):
        return None
    title = _task["title"] or "本任务窗口"
    if not _refocused_once:
        _refocused_once = True
        # [_focus_hwnd] 自己会等到前台真的切过来才回 True —— 不能立刻回读,
        # SetForegroundWindow 是异步的,那样会把自己刚顶回来的窗口判成「没顶回来」。
        if _focus_hwnd(hwnd):
            return None              # 顶回来了,放行
    now = _window_title(fg) or "(桌面或其他窗口)"
    return ("焦点现在在「%s」上,不在本任务操作的「%s」上 —— 已经顶回过一次还是被切走了,"
            "为避免把字打到别的窗口里,这次不动手。需要先 focus_window 把「%s」切回前台。"
            % (now, title, title))


# ---------------------------------------------------------------------------
# ★★★ 三层风险闸 · 第二层:动作级兜底(2026-10-06)
#
# 第一层在**手机侧**(`RiskMath.kt`):用户那句话里有没有钱/身份的词,有就让这一轮
# 每一步都问他。那一层判的是**他说的那句话**。
#
# 这一层判的是**动手那一瞬间电脑上的事实** —— 焦点在谁身上、要点的那个控件叫什么。
#
# ## 为什么两层必须判不同的东西
#
# 第一层会漏,而且是**明知**的漏:「把这个付了」里既没有名单里的词、也没有金额,
# 那一层抓不住它。抓得住的只有动手这一刻 —— 那一下真正点的按钮叫「确认支付」。
# 两层判在**不同的东西**上,才叫两层;判同一件事的两层,漏起来一起漏。
#
# ## ★ 名单的取舍:为什么「微信」不在应用名单里,而银行在
#
# 判据是**这个应用本身是不是钱**:
#   - 银行 / 证券 / 支付宝 / 云闪付 / PayPal —— 它存在就是为了动钱,进去做什么都要问;
#   - 微信 / 淘宝 / 浏览器 —— 本身不做钱的事,危险的是**里面某一个按钮**。
#     所以它们由**控件名**那一关抓(「转账」「立即购买」「发送」),不由应用名抓。
#
# 这条分界的意义:日常在微信里发消息**不该**每一步都点头 ——
# **一条会被关掉的安全功能等于没有**(和 `RiskMath` 文件头那条同一个道理)。
#
# ★★ 这两张名单**只写在这里**,和 [SAFE_KEYS] 同一个位置、同一个规矩:
#   **绝不能被外部内容改写** —— 网页、被点开的 App、模型自己,一个都不行。
#   (Aider CVE-2026-85674:自动加载仓库根的配置并无确认执行 → clone 一个恶意仓库
#    再跑就是任意代码执行。**能被外部内容改的名单等于没有名单。**)
# ---------------------------------------------------------------------------
RISKY_APPS = (
    # 它存在就是为了动钱
    "支付宝", "alipay", "云闪付", "unionpay", "银联", "财付通", "tenpay", "paypal",
    "招商银行", "工商银行", "建设银行", "农业银行", "中国银行", "交通银行",
    "邮储", "中信银行", "浦发银行", "民生银行", "兴业银行", "光大银行",
    "网银", "银行", "证券", "股票", "基金", "期货", "同花顺", "东方财富",
)

RISKY_WORDS = (
    # ── 钱:点下去就收不回来 ──
    "支付", "付款", "转账", "汇款", "提现", "充值", "红包", "买单", "结算",
    "下单", "提交订单", "立即购买", "确认购买", "申购", "赎回", "买入", "卖出",
    "借款", "贷款", "还款", "确认收货", "免密",
    # ── 送出去:消息、表单 —— 发出去就是发出去了 ──
    "发送", "提交", "发布", "转发", "同意并", "授权", "允许", "开通", "签约",
    # ── 不可逆的破坏 ──
    "删除", "卸载", "格式化", "关机", "重启", "注销", "退出登录", "清空",
)

# 会**改变电脑上的东西**的工具 —— 只有它们过这一层。`open_app` / `focus_window`
# 决定的是焦点,不往里面送东西(和手机侧的判定一致,见 `RiskMath.needsTap`)。
#
# ⚠️ **以后加新的写工具必须加进来。** [\_GUARDED_TOOLS] 就吃过这个亏 ——
#    **新工具不在名单里 = 完全没有守门**,而且不报错。
_MUTATING_TOOLS = ("click_at", "click_ui", "type", "hotkey", "search", "scroll",
                   "set_value")   # set_value 2026-11-06 真做出来了


def _risky_app(proc, title):
    """焦点窗口是不是「本身就是钱」的应用。命中回**那个词**,否则 None。

    ★ 只回命中的那个词,不回整个标题:窗口标题里可能有一整段对话/正文
      (微信窗口标题就是对方的昵称),那是不该进日志也不要进回执的东西。
      回一个「银行」既说清原因,又不可能把内容带出去 —— 同 `RiskMath.matchedWord`。
    """
    hay = ("%s %s" % (proc or "", title or "")).lower()
    for w in RISKY_APPS:
        if w.lower() in hay:
            return w
    return None


def _risky_control(tool, args):
    """这一下要动的那个控件,名字里有没有危险词。命中回**那个词**,否则 None。

    ★ 只对**按名字操作控件**的工具成立 —— 点坐标的(`click_at`)没有名字可判。
      判不出来就默认放行,**由「焦点在谁身上」那一关和手机侧第一层接住**。
      这不是漏洞,是这一层的已知边界:硬要猜「坐标 500,300 是不是支付按钮」,
      猜错的代价是**该拦的没拦**(见文件头那条「宁可漏不可错」)。
    """
    if tool not in ("click_ui", "set_value"):
        return None
    name = str((args or {}).get("name") or "")
    if not name:
        return None
    low = name.lower()
    for w in RISKY_WORDS:
        if w.lower() in low:
            return w
    return None


def risk_reason(tool, args, fg_proc="", fg_title=""):
    """这一下要不要他先点个头。→ None(可以动手)或**一句给他看的人话**。

    ★★ **纯函数** —— 事实由调用方查好传进来(前台窗口的进程名和标题)。
      这样它能被 `test_risk_gate.py` 钉住,**不用真的开一个支付宝起来**。
      这个项目里「测不了的东西等于不可信」,而这一层是承重墙,必须能钉。

    ★ 判据是**窄的、可数的**,不让模型判:项目里对「让 4B 判风险」已经有结论
      (见计划里不抄 `LLMSecurityAnalyzer` 那一条)—— 4B 稳不住,而这里是承重墙。

    会返回两种原因,对应名单里的两类:
      · 应用本身是钱(银行/证券/支付宝)→ 进去做**任何事**都问;
      · 控件名字是钱 / 是送出去 / 是不可逆(转账、发送、删除)→ 点它**这一下**问。
    """
    if tool not in _MUTATING_TOOLS:
        return None
    app = _risky_app(fg_proc, fg_title)
    if app:
        return ("这一步要动的是「%s」里的东西 —— 这个应用能花钱、能碰身份,"
                "按规矩得你先点一下头。" % app)
    ctl = _risky_control(tool, args)
    if ctl:
        return ("这一步点的是名字里带「%s」的按钮 —— 这类按钮一点就收不回来,"
                "得你先点一下头。" % ctl)
    return None


# ---------------------------------------------------------------------------
# ★★★ 值的样子:出门之前就把不该出去的东西拿掉(2026-10-06)
#
# ## 它补的是哪条路 —— 今天唯一一条**敞着**的
#
# `read_screen`(OCR)已经把屏幕上的**原文**整段交给她了,**一个字符都没过滤**。
# 另一边 `list_ui` 反而是干净的:UIA 读得到框里的值,但只拿它判一下「这框能不能打字」
# (`editable`),然后就把值扔了 —— 值从来不上回执。
#
# 所以今天「框里的值」只有一个出口,而那个出口是敞的。这一段先堵它。
#
# ## ★ 遮挡 ≠ 移除
#
# 在画面上糊一层马赛克、底下照样把原文发出去 —— **等于没挡**。
# 必须是**数据层就拿掉**,不是画上去。所以这一段做在**电脑侧、出门之前**
# (`read_screen` 组装回执那一处),而不是等到了手机再判。
#
# ## ★ 判据为什么是「校验位」,不是「长得像」
#
# 光看位数会误伤 `read_screen` **最正当的用途** —— 读报错、读代码、读日志,
# 那里面全是数字。误伤的代价是「她读不出报错里那个行号」,而那正是这个工具存在的理由。
#
# 银行卡号有 **Luhn 校验**,身份证有 **ISO 7064 mod-11-2 校验**:
# 真实号码必然过,随手一串数字只有十分之一能过。再叠一层身份证的出生日期合理性,
# 误报就低到可以忽略。**这就是「能被单测钉死」和「只能靠猜」的区别。**
#
# ## ★ 长度只认 15 / 16 / 19,而且**首位只认 2~6**
#
# 13 位数字在日志里满地都是(毫秒时间戳),16 位是银行卡最常见的长度。
# 所以只认 ISO/IEC 7812 里真正在用的那三个长度,`13 / 14 / 17 / 18` 一个都不认。
# (18 位不走这里 —— 那是身份证的地盘,由上面那条判。)
#
# ★★ 首位那条是**量出来的,不是想出来的**(2026-10-06,`value-fp-probe/fp_probe.py`)。
#    拿这台机器上 282 个真文件(源码 + 文档 + 日志 + 一份安卓崩溃墓碑)跑了一遍,
#    Luhn 在**真实内容**里唯一一次误报是墓碑里的 **`0000000000000000`** ——
#    ★ 十六个零**过得了 Luhn**(和是 0,0 能被 10 整除),而崩溃墓碑里满屏都是它
#    (`x0  0000000000000000`)。而「读墓碑」正是这个项目天天在干的事。
#    ISO/IEC 7812 的行业号规定支付卡首位只能是 2~6(0 是 ISO 保留、1 是航空、7 是石油、9 是电信),
#    所以补上首位这一条 —— 它同时是**标准要求的**,和**挡住全零串**的。
#
# ## ★ 只遮这两类,手机号 / 邮箱 / 姓名**不在这儿遮**
#
# 这是他 2026-10-06 定的三类,别混成两类:
#   · 卡号 / 身份证 = **不读不写** → 连进她的上下文都不许 → **在这里、出门就遮**;
#   · 手机号 / 姓名 / 地址 / 邮箱 = **只读、不写、不上云** → 该在「要不要发云」
#     那一刻判(那是手机侧 `ApiMath.sendsOk` 的地盘)。**在这里遮会把「只读」也一起遮掉**,
#     而那正是他否掉「一刀切」时说的那句:「粗的又难跑操作电脑了」。
# 一次只动一处,所以这一轮只做前一类。
# ---------------------------------------------------------------------------
KIND_CARD = "银行卡号"
KIND_ID = "身份证号"
# ★ 回执里报类别的顺序**写死**,不按出现先后 —— 同一屏文字换个排法,
#   回执不该跟着变(测试也好钉)。
_REDACT_KINDS = (KIND_CARD, KIND_ID)

# 身份证:17 位 + 校验位(末位可能是 X)。
# ★ 两头都不许粘着别的字母数字,免得从一长串 hash 里切出 18 位来。
_ID_RE = re.compile(r"(?<!\d)(\d{17}[\dXx])(?![\dA-Za-z])")
# 银行卡:只认 15 / 16 / 19 位,且不许粘着别的数字。
# ★ 正则这一层**只管「长得像一坨数字」**;「是不是卡号」由下面的 `card_shape_ok` 一个地方判 ——
#   分开写的那条(分组)和连着写的这条(不分组)共用同一个判据,免得两边各判各的、迟早对不上。
_CARD_RE = re.compile(r"(?<!\d)(\d{15}|\d{16}|\d{19})(?!\d)")
# ★★ **分开写的卡号** —— 屏幕上最常见的其实**不是**连着的 `4111111111111111`,
#    而是 `4111 1111 1111 1111` / `3782-822463-10005` 那种分组样子。
#    只认连着的那一条,等于把最常出现的那个格式整个漏掉,而**漏是静默的**。
#    分组长度放开到 3~6 位:银联 19 位那套末组是 3 位(`…7890 128`),Amex 15 位是 4/6/5。
_GROUPED_RE = re.compile(r"(?<![\dA-Za-z])(\d{3,6}(?:[ -]\d{3,6}){2,})(?![\dA-Za-z])")
_CARD_LENS = (15, 16, 19)

_ID_WEIGHTS = (7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2)
_ID_CHECK = "10X98765432"


def luhn_ok(digits):
    """Luhn 校验(银行卡号用的那个)。纯算术,不碰网络。

    从右往左,每隔一位翻倍,翻倍后超过 9 就减 9,全都加起来能被 10 整除就算过。
    """
    total = 0
    for i, ch in enumerate(reversed(digits)):
        n = ord(ch) - 48
        if i % 2 == 1:
            n *= 2
            if n > 9:
                n -= 9
        total += n
    return total % 10 == 0


def card_shape_ok(digits):
    """一串**纯数字**:长度 + 行业号 + Luhn,**三关都过**才当它是卡号。

    ★ 三关缺一不可,而且各有各的来由:

    1. **长度**只认 15 / 16 / 19(ISO/IEC 7812 里真在用的那三个)。
       这条挡的是**毫秒时间戳** —— 13 位数字在日志里满地都是。
    2. **首位 2~6**。ISO/IEC 7812 的行业号(MII):0 是 ISO 保留、1 是航空、7 是石油、9 是电信,
       **支付卡就是 2~6**。★★ 这一条是**量出来的**:2026-10-06 拿这台机器上 282 个真文件跑误伤,
       Luhn 在真实内容里唯一一次误报是安卓崩溃墓碑里的 `0000000000000000` ——
       **十六个零过得了 Luhn**(和是 0,0 能被 10 整除),而墓碑里满屏都是它。
       「读墓碑」正是这个项目天天在干的事,所以这一关不是讲究。
    3. **Luhn**。真号码必然过,随手一串只有十分之一能过。
    """
    if len(digits) not in _CARD_LENS:
        return False
    if digits[0] not in "23456":
        return False
    return luhn_ok(digits)


def cn_id_ok(s):
    """18 位身份证:校验位 + 出生日期合理性。**两个都过**才算。

    只过校验位的话,`X` 结尾那一位有 1/11 的运气会撞上;加上日期这一层,
    像 `123456789012345678` 这种就永远不会被当成身份证。
    """
    if len(s) != 18 or not s[:17].isdigit():
        return False
    body = s[:17]
    try:
        y, m, d = int(body[6:10]), int(body[10:12]), int(body[12:14])
    except ValueError:
        return False
    if not (1900 <= y <= 2099 and 1 <= m <= 12 and 1 <= d <= 31):
        return False
    total = sum((ord(body[i]) - 48) * _ID_WEIGHTS[i] for i in range(17))
    return _ID_CHECK[total % 11] == s[17].upper()


def _star_keep_last4(s):
    """只留**后 4 位数字**,其余数字变星号;**分隔符和长度原样保留**。

    ★ 保留格式是有用的:他/她一眼能看出「这里原本是一串号码」,而不是一段被吃掉的文字。
      留后 4 位是银行卡小票的老规矩 —— 够认出是哪张卡,凑不出完整一串。
      (分隔符也留着,所以 `3782 822463 10005` 遮完还是那个分组样子。)
    """
    digits = sum(1 for c in s if c.isdigit())
    cut = digits - 4
    seen = 0
    out = []
    for c in s:
        if not c.isdigit():
            out.append(c)
            continue
        out.append(c if seen >= cut else "*")
        seen += 1
    return "".join(out)


def redact_values(text):
    """把文本里**确定是号码**的东西打掉。→ (遮过的文本, 命中的类别名)

    ★ **纯函数** —— 不碰屏幕、不碰网络,所以 `test_redact.py` 能把它两个方向都钉死:
      真的号码必须遮,**而读报错/读代码/读日志那些数字必须一个字都不动**。
      第二个方向才是要紧的那个 —— 误伤的代价是这个工具没法再用来查报错。

    ★ 回的是**类别名**(「银行卡号」),**不是命中的值** —— 同 `_risky_app` 那条老规矩:
      回执里不该出现敏感的东西,哪怕是为了说明「我遮了它」。
    """
    if not text:
        return text, ()
    hit = set()

    def _id_sub(m):
        if cn_id_ok(m.group(1)):
            hit.add(KIND_ID)
            return _star_keep_last4(m.group(1))
        return m.group(0)

    # ★ 身份证先做:它 18 位,和卡号那三个长度(15/16/19)不重叠,顺序只是为了清楚。
    out = _ID_RE.sub(_id_sub, text)

    def _card_sub(m):
        if card_shape_ok(m.group(1)):
            hit.add(KIND_CARD)
            return _star_keep_last4(m.group(1))
        return m.group(0)

    out = _CARD_RE.sub(_card_sub, out)

    def _grouped_sub(m):
        s = m.group(1)
        digits = s.replace(" ", "").replace("-", "")
        # 同一个判据 —— 单靠「四个一组」会误伤日期区间、工号这类东西。
        if card_shape_ok(digits):
            hit.add(KIND_CARD)
            return _star_keep_last4(s)
        return m.group(0)

    out = _GROUPED_RE.sub(_grouped_sub, out)
    return out, tuple(k for k in _REDACT_KINDS if k in hit)


# ---------------------------------------------------------------------------
# 手机号 / 邮箱的判据
#
# **装在哪儿**早先没定,现在定了(2026-11-06 核过代码,不是「记得」):
#   银行卡号 / 身份证 = 他定的「不读不写」→ 装在 [redact_values] 里,屏幕上直接打码;
#   手机号 / 邮箱       = 他定的「**能读**、不能写、只是本地读」→
#     要是也在屏幕上遮成 `138****5678`,那「能读」当场就没了,
#     而跨应用搬运恰恰要靠读(读出来是星号,填过去也是星号)。
#   所以它们装在 [redact_all] 里 —— 那一条只用于**出门之前**:
#     `server.py` 发云前的那道闸、`scrub_log.py` 写盘前的那道闸。
#   屏幕上读回来的东西一个字都不动。
#
# ★ 形状 vs 表(这一节的全部道理):
#   银行卡有 Luhn、身份证有 mod-11-2 —— **校验位让「形状」变成「确定」**。
#   手机号和邮箱没有校验位,但各有**一张表**顶上来:
#     手机号查**号段表**(前三位有没有被放出去)、邮箱查**域名格式**(结尾得像个真域名)。
#   同一条道理:**形状会撞,表不会。**
# ---------------------------------------------------------------------------
KIND_PHONE = "手机号"
KIND_EMAIL = "邮箱"

# 中国手机号的**号段表**(前三位)。来源是工信部分配给四家运营商 + 虚拟运营商的那批。
# ⚠️ **这张表会过期** —— 新号段是后来才发的。而过期的表现是**漏**,漏是静默的。
#    所以:① 一次写宽(宁多不少,多一个的代价只是多判一次「不上云」);
#         ② 指望维护它不现实 —— 真要长时间靠它,得让「这串像手机号但前三位的号段我不认」
#            有地方能看见(留痕),否则它会安安静静地变成一条无用的判据。
_MOBILE_SEGMENTS = frozenset((
    # 移动
    "134", "135", "136", "137", "138", "139", "147", "148",
    "150", "151", "152", "157", "158", "159",
    "172", "178", "182", "183", "184", "187", "188",
    "195", "197", "198",
    # 联通
    "130", "131", "132", "145", "146", "155", "156", "166", "167",
    "171", "175", "176", "185", "186", "196",
    # 电信
    "133", "149", "153", "162", "173", "174", "177",
    "180", "181", "189", "190", "191", "193", "199",
    # 广电 / 虚拟运营商
    "192", "165", "170",
))
_MOBILE_RE = re.compile(r"(?<!\d)(1\d{10})(?!\d)")

_EMAIL_RE = re.compile(
    r"(?<![\w.+-])([A-Za-z0-9._%+-]+)@([A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)+)(?![\w.-])")
_LABEL_RE = re.compile(r"[a-z0-9-]+")


def phone_shape_ok(digits):
    """11 位、1 开头、**前三位在号段表里**。纯算术,不碰网络。

    ★ 为什么非要那张表:`1[3-9]` 加九个数字,**谁都能凑出来**
      (订单号、流水号、随便一串)。真正决定它是手机号的是「这段号有没有被放出去」。
    ★ 这是**收窄**,所以它的失效方向是**漏**(表里没有的新号段)。宁可这样,
      也不要为了不漏而放宽成纯形状 —— 那会把一堆流水号判成手机号。
    """
    return len(digits) == 11 and digits[:3] in _MOBILE_SEGMENTS


def email_shape_ok(local, domain):
    """local 与 domain 分开判:长度合规矩 + 每一段域名像域名。

    ★★ 真正干活的是最后那条「**域名每一段必须全小写**」。
      真域名惯例小写,而**代码里的标识符是驼峰** ——
      实测那条误伤 `this@MainActivity.contentResolver`(Kotlin 里 `this@标签` 的写法)
      被它一句话干掉,而且**不用任何顶级域名白名单**(白名单会过期,这条不会)。

      所以这里**不查 TLD 白名单** —— 白名单的失效方向是「新的顶级域名全漏」,
      而「小写 + 2~24 个字母」是结构性的,不会随 ICANN 的新 gTLD 过期。
      (代价:全大写的域名 `USER@EXAMPLE.COM` 会漏 —— 这种写法在真邮件里很少,
       而且漏的方向是「不遮」,比误伤便宜。)
    """
    if not (1 <= len(local) <= 64):
        return False
    if len(domain) > 253:
        return False
    labels = domain.split(".")
    if len(labels) < 2:
        return False
    for lb in labels:
        if not (1 <= len(lb) <= 63):
            return False
        if not _LABEL_RE.fullmatch(lb):
            return False          # ★ 有大写/别的东西 → 那是代码,不是域名
    tld = labels[-1]
    if not (2 <= len(tld) <= 24) or not tld.isalpha():
        return False
    return True


def phone_hits(text):
    """文本里**认得出的手机号**(过号段表的那批)→ 列表。纯读,不改文本。"""
    return [m.group(1) for m in _MOBILE_RE.finditer(text or "")
            if phone_shape_ok(m.group(1))]


def email_hits(text):
    """文本里**认得出的邮箱**→ 列表。纯读,不改文本。"""
    return [m.group(0) for m in _EMAIL_RE.finditer(text or "")
            if email_shape_ok(m.group(1), m.group(2))]


# ---------------------------------------------------------------------------
# 落盘 / 出门那一份:四条判据合成的一道闸
#
# ★★ **[redact_all] 和 [redact_values] 是**用途不同的两件事**,别混成一个:
#
# | | [redact_values] | [redact_all] |
# |---|---|---|
# | 给谁看 | **屏幕 / 模型** —— 即 `read_screen` 的回执 | **盘上的日志 / 出门那一份** |
# | 遮什么 | 银行卡号 + 身份证号 | 那两样 **+ 手机号 + 邮箱** |
# | 留什么 | ★ **保后四位**(`4111********1111`) | ★ **整段换成标记** |
#
# 差别的理由只有一条,而且两个方向都成立:
#
# - **保后四位是给人眼看的**(银行卡小票的老规矩:够认出是哪张卡)。
#   `read_screen` 是给模型读的,它需要「这儿有个号码」这个事实本身。
# - **日志里没有「认出是哪张卡」这个需求** —— 少留一个字节都是白给的。
# - **手机号 / 邮箱那两条判据一直「没有调用方」是故意的**(屏幕上要保「能读」,
#   否则「读出来 → 填过去」的跨应用搬运当场就废了)。但**能读 ≠ 该在你盘上躺到明年**。
#   用户 2026-10-06 的裁决正是这一句:「**办完这件事就没了 …… 一个字节都不进文件、不记日志**」。
# ---------------------------------------------------------------------------
_MARK = {
    KIND_CARD: "[银行卡号]",
    KIND_ID: "[身份证号]",
    KIND_PHONE: "[手机号]",
    KIND_EMAIL: "[邮箱]",
}

# ★ 回执里报类别的顺序**由这张表定,不由正则跑的先后定** —— 和 `_REDACT_KINDS` 同一个规矩。
#   两个理由:① 同一段文字换个排法,回执不该跟着变(否则事后没法比对);
#   ② **实现里的先后是内部细节**,哪天为了正则的匹配正确性调了顺序,回执不该跟着动。
_ALL_KINDS = (KIND_CARD, KIND_ID, KIND_PHONE, KIND_EMAIL)


def redact_all(text):
    """**所有有形状的值**换成标记 → (遮过的文本, 命中的类别名)。

    类别的顺序写死(银行卡 → 身份证 → 手机号 → 邮箱),不跟着出现先后走 ——
    同一段文字换个排法,回执和日志不该跟着变,否则事后没法比对。

    ★ 遮过的那份再喂进来是**幂等**的(标记本身不含数字和 `@`,匹配不上任何一条正则)——
      所以它可以同时在「调用点」和「唯一写入口」两处各过一遍,不会互相打架。
    """
    if not text:
        return text, ()
    # ★ 廉价预筛:四条判据里三条要数字、邮箱那条要 `@`。
    #   两样都没有 ⇒ 一个字都匹配不上。日志里绝大多数行是这种(`[mouse] DOWN bit=0x1`),
    #   而 `_log_line` 连鼠标事件都要走 —— 这道筛子省的是那条最热的路。
    if "@" not in text and not any(c.isdigit() for c in text):
        return text, ()

    hit = set()

    def _mark(kind):
        hit.add(kind)
        return _MARK[kind]

    def _id_sub(m):
        return _mark(KIND_ID) if cn_id_ok(m.group(1)) else m.group(0)

    def _card_sub(m):
        return _mark(KIND_CARD) if card_shape_ok(m.group(1)) else m.group(0)

    def _grouped_sub(m):
        digits = m.group(1).replace(" ", "").replace("-", "")
        # 同一个判据 —— 光看「四个一组」会把日期区间、工号也一起吃掉。
        return _mark(KIND_CARD) if card_shape_ok(digits) else m.group(0)

    def _phone_sub(m):
        return _mark(KIND_PHONE) if phone_shape_ok(m.group(1)) else m.group(0)

    def _email_sub(m):
        return _mark(KIND_EMAIL) if email_shape_ok(m.group(1), m.group(2)) else m.group(0)

    # ★ 身份证先做:它 18 位,和卡号那三个长度(15/16/19)不重叠,先后只是为了清楚。
    #   (同 `redact_values` 那句。回执的顺序不靠这里定,靠 `_ALL_KINDS`。)
    out = _ID_RE.sub(_id_sub, text)
    out = _CARD_RE.sub(_card_sub, out)
    out = _GROUPED_RE.sub(_grouped_sub, out)
    out = _MOBILE_RE.sub(_phone_sub, out)
    out = _EMAIL_RE.sub(_email_sub, out)
    return out, tuple(k for k in _ALL_KINDS if k in hit)


# ---------------------------------------------------------------------------
# 打开应用
# ---------------------------------------------------------------------------
_INDEX_CACHE = {"at": 0.0, "items": []}   # items: [(显示名, 目标路径)]

_APPS_CFG = {"at": 0.0, "aliases": {}}


def _apps_aliases():
    """apps.json 热加载:{"微信":"shell:AppsFolder\\...","浏览器":"C:\\..\\chrome.exe"}。"""
    try:
        m = os.path.getmtime(APPS_PATH)
    except OSError:
        return {}
    if m == _APPS_CFG["at"]:
        return _APPS_CFG["aliases"]
    try:
        with open(APPS_PATH, encoding="utf-8") as fp:
            data = json.load(fp)
        if isinstance(data, dict):
            _APPS_CFG["aliases"] = {str(k).strip(): str(v) for k, v in data.items()
                                    if not str(k).startswith("_")}
        else:
            _APPS_CFG["aliases"] = {}
    except Exception:
        _APPS_CFG["aliases"] = {}     # 文件坏了/正在写:别每次重试
    _APPS_CFG["at"] = m
    return _APPS_CFG["aliases"]


def _start_menu_dirs():
    dirs = []
    for env, tail in (("APPDATA", r"Microsoft\Windows\Start Menu\Programs"),
                      ("ProgramData", r"Microsoft\Windows\Start Menu\Programs")):
        root = os.environ.get(env)
        if root:
            d = os.path.join(root, tail)
            if os.path.isdir(d):
                dirs.append(d)
    return dirs


def _app_paths():
    """注册表 App Paths 里登记的可执行文件 → [(名字, exe 全路径)]。

    ★ 为什么非要有这一份索引(2026-10-03 拿 Edge 试别的软件时撞出来的):
    **这台机器的开始菜单里根本没有 Edge 的 .lnk** —— ProgramData 和用户级两处都翻遍了,
    一条都没有。于是 `open_app("Edge")` 四级兜底全落空,回「启动失败」。
    可 Edge 明明装在 `C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe`。

    Windows 自己从来不靠 .lnk 找 Edge,它查的是 App Paths —— 凡是正规安装的应用
    (Edge / Chrome / 各种开发工具)都会在这儿登记。**这是比开始菜单更可靠的一份索引**,
    而且零维护:装了什么就在里面。
    """
    if not IS_WINDOWS:
        return []
    try:
        import winreg
    except ImportError:
        return []
    roots = [
        (winreg.HKEY_LOCAL_MACHINE,
         r"SOFTWARE\Microsoft\Windows\CurrentVersion\App Paths"),
        (winreg.HKEY_CURRENT_USER,
         r"SOFTWARE\Microsoft\Windows\CurrentVersion\App Paths"),
        # 32 位应用在 64 位系统上登记在这棵树里(Bandizip 这类)
        (winreg.HKEY_LOCAL_MACHINE,
         r"SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\App Paths"),
    ]
    out, seen = [], set()
    for hive, path in roots:
        try:
            key = winreg.OpenKey(hive, path)
        except OSError:
            continue
        for i in range(winreg.QueryInfoKey(key)[0]):
            try:
                sub = winreg.EnumKey(key, i)
                with winreg.OpenKey(key, sub) as sk:
                    exe = str(winreg.QueryValueEx(sk, "")[0]).strip().strip('"')
            except OSError:
                continue
            # 登记了但文件已经被删掉的(卸载没清干净)不要 —— 那正是「烂别名」的翻版。
            if not exe or not os.path.isfile(exe):
                continue
            name = os.path.splitext(sub)[0].strip()
            if not name or name.lower() in seen:
                continue
            seen.add(name.lower())
            out.append((name, exe))
    return out


def _app_index():
    """扫「开始菜单 + 注册表 App Paths」收集「显示名 -> 启动目标」。缓存 APP_INDEX_TTL 秒。"""
    now = time.time()
    if _INDEX_CACHE["items"] and now - _INDEX_CACHE["at"] < APP_INDEX_TTL:
        return _INDEX_CACHE["items"]
    items = []
    for root in _start_menu_dirs():
        for dirpath, _dirnames, filenames in os.walk(root):
            for fn in filenames:
                if not fn.lower().endswith(".lnk"):
                    continue
                name = os.path.splitext(fn)[0].strip()
                if name:
                    items.append((name, os.path.join(dirpath, fn)))
    # 开始菜单排前面:人给 .lnk 起的显示名比 exe 名更接近用户会说的话
    # (「Visual Studio Code」vs「Code」)。App Paths 只补**开始菜单里没有的**。
    have = {n.lower() for n, _ in items}
    items += [(n, p) for n, p in _app_paths() if n.lower() not in have]
    _INDEX_CACHE["items"] = items
    _INDEX_CACHE["at"] = now
    return items


# 零宽 / 不可见字符。**Edge 的窗口标题里就实实在在藏了一个零宽空格**
# (「Microsoft<U+200B> Edge」,len 比看起来多 1),不洗掉的话「Microsoft Edge」永远
# 匹配不上,而且肉眼 debug 一辈子看不出来 —— 字符串看着就是对的。
_INVISIBLE = dict.fromkeys([0x00ad, 0x180e, 0x200b, 0x200c, 0x200d, 0x2060, 0xfeff], None)


def _squash(s):
    """标题归一化:去掉零宽字符、把空白折成一个空格、转小写。

    `" ".join(s.split())` 而不是正则 —— str.split() 不带参数时按**所有 Unicode 空白**
    切(含全角空格 U+3000),正好是这里要的,省一个 import。
    """
    return " ".join((s or "").translate(_INVISIBLE).split()).lower()


class _FILETIME(ctypes.Structure):
    """GetProcessTimes 要的 FILETIME。

    没引 `ctypes.wintypes` —— 本模块通篇只用 `ctypes` 本体,为这一个结构多引一个模块
    不值当(而且它在非 Windows 上根本没有,会连累模块导入)。
    """
    _fields_ = [("lo", ctypes.c_uint32), ("hi", ctypes.c_uint32)]


def _proc_start(pid):
    """进程启动时刻(FILETIME,越大越晚)。拿不到返回 None。"""
    try:
        h = kernel32.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, False, pid)
    except Exception:
        return None
    if not h:
        return None
    try:
        c, e, kt, ut = _FILETIME(), _FILETIME(), _FILETIME(), _FILETIME()
        if not kernel32.GetProcessTimes(h, ctypes.byref(c), ctypes.byref(e),
                                        ctypes.byref(kt), ctypes.byref(ut)):
            return None
        return (c.hi << 32) | c.lo
    except Exception:
        return None
    finally:
        kernel32.CloseHandle(h)


def _target_words(target):
    """从启动目标里猜「这应用的窗口标题里大概会出现什么词」。

    光靠用户说的名字、或光靠文件路径,**各漏一半**:说「浏览器」时标题写的是
    Microsoft Edge;说「记事本」时目标是 notepad.exe,标题里写的却是「记事本」。
    两路合起来才盖得住。
    """
    t = (target or "").strip()
    if not t:
        return []
    if t.lower().startswith("shell:"):
        # shell:AppsFolder\包名!入口 —— 入口名常常就是窗口名的一部分
        tail = t.replace("\\", "/").rsplit("/", 1)[-1]
        return [tail.split("!")[-1]] if tail else []
    stem = os.path.splitext(os.path.basename(t))[0]
    return [stem] if stem else []


def _window_already_open(cands):
    """候选词里有没有哪个能落在某个可见窗口的标题里?返回那个窗口,没有就 None。

    **可能同时命中好几个** —— 同一应用常有多个**标题一模一样**的窗口:微信就是这样,
    用户最小化着的那个真窗口和刚被启动出来的扫码登录页**两个都叫「微信」**,字符串
    层面完全不可分。所以不能「取第一个算数」,得排:

      1. 标题**完全等于**候选词 > 只是**包含**;
      2. 候选词可信度(靠前 = 更可信:用户自己说的名字 > 从文件名猜的);
      3. **进程起得越早越优先。**

    第 3 条是这里**唯一有客观答案**的判据:「已经在跑的那个」就是最早起来那个;后起的
    多半正是刚被启动出来的新实例,而新实例常停在登录页上。有客观答案的事就该由代码
    兜死 —— 这也正是它不该交给模型去猜的原因。
    """
    hits = []
    for order, w in enumerate(_enum_windows()):
        title = _squash(w.get("title"))
        if not title:
            continue
        for i, c in enumerate(cands):
            cl = _squash(c)
            if len(cl) < 2:              # 一个字的名字太容易误命中
                continue
            if cl in title:
                hits.append((i, 0 if title == cl else 1, order, w))
                break                    # 一个窗口只记它最好的那个候选词
    if not hits:
        return None
    # 进程年龄放在排序键里,`min` 会对每个候选各算一次 —— 命中数很少,不必缓存。
    # 拿不到年龄的用一个大数垫底:**不知道 ≠ 最老**,不能让它凭这个胜出。
    def rank(h):
        i, exact, order, w = h
        return (i, exact, _proc_start(w.get("pid")) or (1 << 62), order)
    return min(hits, key=rank)[3]


def _wait_window(words, timeout=12.0, poll=0.2):
    """等一个匹配 `words` 的顶层窗口**真的出现**。返回 (窗口 | None, 等了多久秒)。

    判据用的是 [_window_already_open] —— 和服务端别处认窗口**完全同源**。
    这一点是故意的:如果这里用一套更宽松的判据,就会等在一个"服务端并不认"的窗口上,
    白等满 12 秒;用更严的判据则会漏掉真实的窗口。同源才不会自相矛盾。

    为什么是轮询而不是等某个事件:Windows 没有「某进程开了窗口」的可靠通知
    (SetWinEventHook 要一个带消息循环的线程,代价远大于这里).轮询一次是全窗口
    枚举,几十个窗口,开销可以忽略;0.2 秒一次够用,也不会把 CPU 拉起来。

    ⚠️ 它保证的是「窗口存在」,**不是**「内容画好了」。别把它当后者用。
    """
    t0 = time.time()
    while True:
        w = _window_already_open(words)
        if w is not None:
            return w, time.time() - t0
        if time.time() - t0 >= timeout:
            return None, time.time() - t0
        time.sleep(poll)


def _launch(target, *cands):
    """启动一个 exe / .lnk / shell: 目标 —— **但它已经在跑的话,只切前台,不另开进程。**

    ★ 这不是优化,是修一个**真会把任务推进死胡同**的坑:微信这类客户端,再启动一次会
    开出一个**没有登录态的新实例**,直接顶一张登录二维码出来 —— 而用户那个早就登进去的
    窗口一直好好开着。用户要的是「用我的微信」,不是「给我一个微信」;停在登录页上,
    后面什么都点不动。

    为什么写死在代码里、而不是叮嘱模型「先 list_windows 看看在不在」:「已经在跑就别开
    第二个」是**机制**,有确定答案;指望 4B 每轮都记得先查一遍是赌运气,还白多一次往返。
    机制归代码 —— 这是本项目既定的分工(见 memory: ruoxi-teacher-learning-loop)。

    已知代价(认为值得):确实想开第二个窗口的应用(比如记事本再开一篇)会被切到已有的
    那个去。这个代价远小于上面那个 —— 用户顶多多按一次 Ctrl+N。

    返回 dict(不是字符串):`{target, ready, waited_s, window?}`。
    冷启动那条路会**等窗口真的出现**再返回,理由见下面 ★★ 那段;
    `ready=False` 表示命令发出去了但窗口没露面 —— 那是我们**不知道**的状态,照实说。
    """
    t = (target or "").strip()
    words = [c for c in cands if c] + _target_words(t)
    w = _window_already_open(words)
    if w is not None:
        ok = _focus_hwnd(w["hwnd"])
        # 抢不到焦点也**绝不**退回去开新实例 —— 那正好掉进上面那个坑。
        _set_task_window(w["hwnd"], w["title"], words)
        print("[open_app] 「%s」已在运行(窗口:%s),切前台%s"
              % (t, w["title"], "" if ok else "失败(但也不新开)"), flush=True)
        return {"target": t, "ready": True, "waited_s": 0.0,
                "window": w["title"], "reused": True}
    if t.lower().startswith("shell:"):
        # UWP 应用没有 .lnk,得走 AppsFolder 由 explorer 代启
        subprocess.Popen(["explorer.exe", t])
    else:
        os.startfile(t)     # 交给 Shell:exe 直接跑,.lnk 按快捷方式解析

    # ★★ 2026-10-03:启动之后**要等窗口真的出现**再返回,不能发完命令就走。
    #
    # 真机实测(这是定案的证据,不是推测):09:47 模型 `open_app 微信`(冷启动,
    # 172ms 返回)→ **0.7 秒后**就 `click_ui 文件传输助手` → 连失败三次,
    # 每次回「这一屏上没有」;三分钟后微信早开着了,`open_app` 只花 4ms(纯切前台),
    # 同一句 `click_ui` **一次成功**(via OCR)。中间那三分钟的差别就是:窗口画出来了没有。
    #
    # 事后量「按下启动 → _window_already_open 认得到」= **3.20 秒 / 0.90 秒**
    # (两次独立测量,差在系统缓存),**两次都远超那 0.7 秒**。
    #
    # 代价对比很清楚:在这里等最多 12 秒,而发早了要付的是 —— 一次失败的 click_ui
    # 3.3~10.3 秒,加一串失败兜底(搜索/滚动重找)合计 **17 秒**,还往经验库和
    # 云端老师那里灌一条**假的**「blind」观察(界面明明只是还没画出来)。
    #
    # ⚠️ 说清楚这个修复**不覆盖**什么:窗口"存在"不等于内容"画好了"。
    # 我试着量过后半段,但仪器不稳(热身后的 list_elements 仍要 1.7 秒、
    # 窗口刚出现时查询全部失败),**没量出来**。所以这里**不塞一个猜的 sleep** ——
    # 那种数字会变成谁也不敢动的祖传常量。真要治,得找一个"内容已就绪"的判据。
    win, waited = _wait_window(words)
    if win is not None:
        _focus_hwnd(win["hwnd"])
        _set_task_window(win["hwnd"], win["title"], words)
        return {"target": t, "ready": True, "waited_s": round(waited, 2),
                "window": win["title"]}
    # 12 秒都没等到:启动**确实发出去了**(所以 ok 仍然是 True),但窗口没露面 ——
    # 老实把这个状态报上去,让上层自己决定是再等等还是当它失败了。
    # 不在这里写 "已经打开了":那是我们不知道的事。
    # 顺带把「要找谁」记下,守门第一次跑到时再认领(认不到就不守,见 [_guard_focus])。
    _set_task_window(None, "", words)
    return {"target": t, "ready": False, "waited_s": round(waited, 2),
            "note": "启动命令已发出,但 %.0f 秒内没看到它的窗口 —— 可能还在启动,"
                    "也可能没起来。" % waited}


def open_app(name):
    """按友好名打开应用:先查 apps.json 别名,再查开始菜单索引,最后兜底。"""
    if not IS_WINDOWS:
        return {"ok": False, "error": "仅 Windows 支持"}
    raw = (name or "").strip()
    if not raw:
        return {"ok": False, "error": "没给应用名"}
    low = raw.lower()

    # 匹配策略:宁可不匹配(回候选让模型重说),也别开错应用 —— 开错窗口用户一眼就看见,
    # 而给了候选,模型下一轮换个名字就能成。所以「反向包含」要加长度约束。
    aliases = _apps_aliases()

    def _alias_hit(name_low):
        """别名匹配:输入含别名(正向)直接算;别名含输入(反向)要求覆盖过半。"""
        best = None
        for k in aliases:
            kl = k.lower()
            if kl == name_low:
                return k, 3                                  # exact
            if name_low and name_low in kl:
                score = 2                                    # 用户说的名字是别名的前缀/片段
            elif kl in name_low and len(kl) >= len(name_low) * 0.6:
                score = 1                                    # 别名只占用户说法的一小截 -> 不算
            else:
                continue
            if best is None or score > best[1]:
                best = (k, score)
        return best

    # 四条路依次试。**任何一条失败都不许当场返回** —— 一条坏记录不代表这个应用不存在,
    # 后面那条路可能正好认识它。
    # ★ 这是 2026-10-03 拿 Edge 试「别的软件」时撞出来的真洞:apps.json 里 `"edge"`
    #   指着的 `Microsoft Edge.lnk` **在这台机器上根本不存在**,而别名是第一级 ——
    #   它一抛异常就 `return 失败`,于是开始菜单 / App Paths / PATH / Shell 四级
    #   一级都没轮到。用户看到的是「找不到 Edge」,而 Edge 装得好好的。
    #   和本项目反复踩的那类 bug 同形:**一个坏来源把其余全部静默挡死**。
    tried = []

    # 1) apps.json 别名
    hit = _alias_hit(low)
    if hit:
        k = hit[0]
        try:
            # raw 在前、k 在后:候选词按可信度排,用户自己说的名字优先于别名键。
            return dict({"ok": True, "launched": k}, **_launch(aliases[k], raw, k))
        except Exception as e:
            tried.append("apps.json 别名「%s」→ %s 起不来(%s)" % (k, aliases[k], e))

    # 2) 索引:开始菜单 .lnk + 注册表 App Paths(Edge 这类没有 .lnk 的靠后者)
    items = _app_index()
    names = [n for n, _p in items]
    exact = [it for it in items if it[0].lower() == low]
    prefix = [it for it in items if it[0].lower().startswith(low)]
    contain = [it for it in items if low in it[0].lower()]
    pick = exact or prefix or contain
    if pick:
        best = sorted(pick, key=lambda it: len(it[0]))[0]   # 名字最短的通常最「主」
        try:
            return dict({"ok": True, "launched": best[0]},
                        **_launch(best[1], raw, best[0]))
        except Exception as e:
            tried.append("「%s」→ %s 起不来(%s)" % (best[0], best[1], e))

    # 3) PATH 里的可执行文件
    exe = shutil.which(raw)
    if exe:
        try:
            return dict({"ok": True, "launched": raw}, **_launch(exe, raw))
        except Exception as e:
            tried.append("PATH 里的 %s 起不来(%s)" % (exe, e))

    # 4) 最后兜底:当成路径/协议交给 Shell(ShellExecute 自己会查 App Paths);失败了就把相近名字回给模型
    try:
        return dict({"ok": True, "launched": raw}, **_launch(raw, raw))
    except Exception as e:
        tried.append("当成路径交给 Shell 也不行(%s)" % e)
        near = difflib.get_close_matches(raw, names, n=6, cutoff=0.35)
        if not near:
            near = difflib.get_close_matches(raw, list(aliases.keys()), n=6, cutoff=0.35)
        return {"ok": False, "error": "找不到应用「%s」" % raw,
                "tried": tried,
                "candidates": near,
                "hint": "四条路都试过了(见 tried)。要是某个别名指向的路径已经没了,"
                        "改 pc-server/apps.json 里那一条就行"}


# ---------------------------------------------------------------------------
# 截图(给视觉模型看的)
# ---------------------------------------------------------------------------
def screenshot():
    """抓主屏 → 缩到 SHOT_MAX_WIDTH → JPEG → base64。

    回 {"image","w","h","screen_w","screen_h"}:w/h 是这张图的尺寸,模型按图给坐标;
    调用方用 screen_w/screen_h 换算出真实屏幕像素再 click_at。
    """
    if not IS_WINDOWS:
        return {"ok": False, "error": "仅 Windows 支持"}
    try:
        from PIL import Image, ImageGrab
    except ImportError:
        return {"ok": False, "error": "没装 Pillow,无法截图"}
    try:
        img = ImageGrab.grab()
    except Exception as e:
        return {"ok": False, "error": "截图失败: %s" % e}
    sw, sh = img.size
    if img.width > SHOT_MAX_WIDTH:
        ratio = SHOT_MAX_WIDTH / float(img.width)
        img = img.resize((SHOT_MAX_WIDTH, max(1, int(img.height * ratio))))
    buf = io.BytesIO()
    img.convert("RGB").save(buf, format="JPEG", quality=SHOT_JPEG_QUALITY)
    return {
        "ok": True,
        "image": base64.b64encode(buf.getvalue()).decode("ascii"),
        "w": img.width, "h": img.height,
        "screen_w": sw, "screen_h": sh,
    }


# ---------------------------------------------------------------------------
# UI Automation:按名字找控件
# ---------------------------------------------------------------------------
# 为什么要这条:click_element 走「截图 → 视觉模型看图 → 吐坐标」,而眼睛的精度
# 天花板是**一个视觉 token = 32x32 像素**,开始按钮图标只有 24x24 —— 比一个 token
# 还小。实测它给 y=1041,真值 1060,差 19 像素,点击落进任务栏空白处,菜单打不开。
# UIA 给的是 (24,1060),0 误差,150~500 毫秒(视觉那条路要 215 秒)。
#
# 覆盖:任务栏/开始菜单/经典 Win32/UWP/Chrome/Electron。
# **不覆盖**:微信(Qt/DirectUI 自绘,整个窗口 UIA 只暴露 1 个元素)。
#   这一格现在由 OCR 补上(见下面 _click_ui 里的兜底):字是画在屏幕上的,
#   不管谁画的都能认,而且是像素级包围盒。实测全屏约 1 秒。
# 三层都是**分层**,不是替换:UIA(27ms) → OCR(~1s) → 眼(215s)。
#
# 而且 UIA 知道的**不只是「屏幕上有什么」** —— 连没画出来的控件它也认识。
# 实测 VS Code 一个窗口 972 个可点控件,**909 个(94%)是离屏的**,
# 以前被 IsOffscreen 一刀切掉,于是模型问「有没有 X」时,我们明明知道却说没有。
# 现在:find 连离屏一起搜;但**离屏的绝不按坐标点**(它的包围盒是「虚拟滚动
# 空间」里的天文数字,照点鼠标会飞出屏幕),而是走 uia.invoke 让控件自己动作,
# 或者 ScrollIntoView 把它滚进来。这就是「知道优先、看见兜底」。
try:
    import uia
    UIA_OK = True
    UIA_ERR = ""
except Exception as e:      # PowerShell 不在、策略禁了脚本……都算
    uia = None
    UIA_OK = False
    UIA_ERR = "%s: %s" % (type(e).__name__, e)


def _list_ui(window=None):
    """列出窗口里可点的控件。

    ★★ 2026-10-06:这一条**也是**一条敞着的读路 —— 别以为只有 OCR 会漏字。
    在 UI 自动化里,**文本控件/容器的 `Name` 属性就是它显示出来的那段原文**
    (`uia.ps1:116` 自己写着踩过的坑:VS Code 里有个容器的 Name 是**整篇文档/终端的内容**)。
    所以控件名里头一样可能躺着一个卡号 —— 出门之前过同一道闸。
    """
    if not UIA_OK:
        return {"ok": False, "error": "UI 自动化用不了(%s)" % UIA_ERR}
    els, raw = uia.list_elements(window, limit=40)
    if not raw.get("ok"):
        return {"ok": False, "error": raw.get("error") or "列不出来"}
    if not els:
        return {"ok": True, "window": raw.get("window"), "count": 0,
                "text": "这个窗口里没有可点的控件(可能是自绘界面,改用 click_element)"}
    hit = set()
    safe = []
    for e in els:
        nm, kinds = redact_values(e["name"])
        hit.update(kinds)
        safe.append(dict(e, name=nm))
    out = {"ok": True, "window": raw.get("window"), "count": len(safe),
           "elements": [{"name": e["name"], "type": e["type"], "x": e["cx"], "y": e["cy"]}
                        for e in safe],
           # ★ 文本也拿**遮过的**那份重新渲染 —— 两份要是各遮各的,迟早对不上。
           "text": uia.describe(safe, 40)}
    if hit:
        # 遮了就说,同 `_read_screen`。★ 而且要说清「名字被改过」——
        # 否则她会照着星号去 `click_ui`,然后回一句「没找到」,而真正的原因是名字变了。
        out["redacted"] = [k for k in _REDACT_KINDS if k in hit]
        out["note"] = ("★ 这里面有「%s」已经打码(只留后四位),名字被改过 —— "
                       "**别拿带星号的名字去点它**。" % "」「".join(out["redacted"]))
    return out


def _obs(kind, window, target, **extra):
    """失败时给上层的**观察**,不是结论。这是整件事的关键区别。

        「界面上没有叫 X 的控件」      ← **结论**。拿到它的人除了放弃,什么都做不了。
        {kind:"blind", ocr_lines:36}  ← **观察**。拿到它的人能推出来:
                                         「这界面认得出字却没有目标 → 目标在更深处,
                                           那就滚动,或者用它自己的搜索。」

    kind 三种(上层按它查策略):
        offscreen —— 找到了,但在屏幕外,而且不接受直接命令
        blind     —— 屏幕上有字(UIA 控件树里没有),但没有目标
        empty     —— 一个字都没认出来

    ⚠️ **只放结构,不放内容** —— 没有 OCR 文本、没有屏幕上的字。两个理由:
      1. 隐私:这份观察可能会被发去云端问「该怎么办」(手机端 askTeacher),
         而屏幕文字里就有聊天记录。结构能说明问题,内容不能。
      2. 够用:策略需要知道的是「这界面是什么样的」,不是「上面写了什么」。
    """
    o = {"kind": kind, "window": window or "", "target": target}
    o.update(extra)
    return o


def _mark_where(out):
    """给点击成功的回执贴上「点的那一瞬间前台是哪个窗口」。

    ★ 为什么非要它(2026-10-03):那次真机翻车的日志只有「click_ui 成功」四个字,
    谁也说不清它点的是哪儿的「发送」—— 排查只能靠猜。而 PC 侧其实**一直**把
    control/window/clicked 回传了,是没人看。
    又为什么不是直接用 `window`:OCR 兜底是**全屏**认字、UIA 也可能在搜全桌面,
    这两种情况下 `window` 是空的 —— 而「前台是谁」不依赖「搜的时候知道自己在哪」,
    是唯一的地面真值。
    """
    try:
        out["clicked_in"] = _window_title(user32.GetForegroundWindow())
    except Exception:
        pass
    return out


def _scope_ok(raw):
    """UIA 这一批命中,是不是落在「本任务正在操作的那个应用」里?

    ★ 2026-10-03 真机栽在这,是这个项目到现在为止**最严重的一次误点**:
    模型要点的东西是微信里的「文件传输助手」,而 `Find-Scoped` 在**前台窗口里没找到**
    之后会去搜**其余所有桌面窗口**;那一遍在 VS Code 里匹配到了几段 `Text` 节点 ——
    内容是 `click_ui {"name":"文件传输助手"}` 这种**我们自己打出来的运行日志**
    (用户开着 logcat/终端)。于是「滚进视野 → 点它」,报 ok=True。
    微信的会话一个都没碰着,用户看到的就是「搜到了,但进不去」。

    为什么判据不是「标题像不像」:独立弹窗(打开/保存)是**另一个顶层窗口**,
    标题完全不像主窗口,但那是正当目标。所以比**进程**。

    三种情况放行(不猜):
      - `desk` 没报上来(旧版助手脚本);
      - 没钉任务窗口 —— 「不知道在操作谁」就不该替它拦;
      - 命中窗口的标题找不到对应 hwnd —— 认不出就别装作认得。
    """
    if not raw:
        return True
    # 「前台以外那些窗口」那一遍来的 —— 我们要操作的界面根本不在那儿。
    if raw.get("desk"):
        return False
    nm = raw.get("window") or ""
    hwnd = _task["hwnd"]
    if not nm or not hwnd:
        return True
    if nm == _task["title"]:
        return True                      # 标题就对得上,不必枚举
    for w in _enum_windows():
        if w["title"] == nm:
            # 同进程 = 这个应用自己的弹窗/子窗口,放行;别的进程 = 别人的窗口,拒。
            return _same_app(w["hwnd"], hwnd)
    return True


def _ocr_scope(window):
    """OCR 该在哪儿认字。模型没指名窗口时,**用本任务的操作窗口**,而不是全屏。

    ★ 和 [_scope_ok] 是同一件事的另一半:UIA 那半把「别的窗口里的命中」丢掉了,
    如果 OCR 这半还是全屏认字,它会**原样把同一批字再捞回来**(同一个 VS Code 日志行
    既在 UIA 树里、也画在屏幕上),那就等于白修。两条路必须绑在同一个作用域上。
    """
    return window or _task["title"] or None


def _clickable(els):
    """命中里哪些**真能按坐标点**:屏幕内、且面积不为零。

    ★ 为什么不能直接拿 els[0] 去点(2026-10-03 实测):
    `find` 走的是 anyType=$true,会把 Text/Pane 这类**非可点类型**一起带回来。
    实测 VS Code 当前台时,`find("微信")`、`find("发送")`、`find("文件传输助手")`
    三个不同的名字返回的是**同一个**滚出视野的正文 Text 节点(真实矩形
    `333,-10395 33x17`),而它的 cx/cy 按约定全是 0 —— 照点就点到屏幕左上角。

    这类元素在源头已经堵掉了(uia.ps1 的 New-El:离屏 + 非可点类型直接丢),
    这里是**第二道闸**:UIA 之外还有 OCR 那条路会填 els,而"能不能点"这件事
    不该只靠上游记得标。闸门设在真正动手的地方,才拦得住以后新加的来路。

    面积判据用 `<= 0` 而不是 `== 0` —— UIA 偶尔回 -1 这种哨兵值,别赌。
    """
    out = []
    for e in els:
        if e.get("offscreen"):
            continue
        if (e.get("w") or 0) <= 0 or (e.get("h") or 0) <= 0:
            continue
        out.append(e)
    return out


def _window_by_title(title):
    """按标题**完全相等**找一个可见顶层窗口。找不到回 None(**不猜**)。"""
    if not title:
        return None
    for w in _enum_windows():
        if w["title"] == title:
            return w
    return None


def _click_ui(name, window, click_fn):
    if not UIA_OK:
        return {"ok": False, "error": "UI 自动化用不了(%s)" % UIA_ERR}
    name = str(name or "").strip()
    if not name:
        return {"ok": False, "error": "没说要按哪个控件"}
    if click_fn is None:
        return {"ok": False, "error": "本端没接鼠标控制,点不了"}

    els, raw = uia.find(name, window, limit=5)
    if not raw.get("ok"):
        return {"ok": False, "error": raw.get("error") or "查找失败"}
    # 记住「UIA 找的是哪个窗口」—— 失败时要拿它当观察的一部分(「这是个什么界面」)。
    # 非记不可:下面 OCR 兜底那一步会把 raw 整个换掉,而 OCR 的 window 字段是空的,
    # 一覆盖这条线索就没了。
    where = raw.get("window") or ""
    # ★ 命中的**窗口**先过一道:落在别的应用里的命中不是我们要点的东西,
    # 当没找到处理,让它照常落到底下的 OCR / 观察那条路上去。
    # 2026-10-03:就是这里放进去了一段 VS Code 里的日志文本,点了个寂寞。
    if els and not _scope_ok(raw):
        print("[ai] %r 在「%s」里找到 %d 个,但那是别的窗口 —— 丢掉"
              % (name, raw.get("window"), len(els)), flush=True)
        els = []
        # 观察里的「窗口」要写**我们本来打算在哪找**,不能写那个被丢掉的窗口:
        # 否则经验库/老师会以为「这是个 VS Code 界面」,照着教一整套错的策略。
        where = _task["title"] or ""

    # ★ 只点「真能点的」。见 [_clickable] —— 命中有可能全是点不了的。
    good = _clickable(els)

    # 「找到了、但一个都点不了」时把话记在这,**不当场返回** —— 还得让 OCR 再看一眼。
    # 为什么不当场返回,见下面 ★ 2026-10-03 那一段。None = 没这回事。
    stuck = None

    # 一个都点不了,而**模型显式点名要在哪个窗口里找、那窗口又不在前台** ——
    # 那"够不着"的真原因很可能是它被别的窗口压住了,不是它不在这一屏。
    # 把那个窗口顶到前面,重找一次。
    #
    # 为什么舍得花这几百毫秒:手机侧一看到 click_ui 失败就会启动 resolveMiss
    # (滚窗口 → 用它自带的搜索 → 问云端老师 → 看图,最长 215 秒),而那可能
    # 只是因为窗口在后面。**先确认前台,再谈"找不到"。**
    #
    # 只在 `window` 给了的时候做:模型没点名说明它自己也不知道该在谁里面找,
    # 那时候替它挑一个窗口切到前台,就是在改用户的桌面。
    if els and not good and window:
        hit_win = raw.get("window") or ""
        if hit_win and hit_win != _window_title(user32.GetForegroundWindow()):
            w = _window_by_title(hit_win)
            if w and _focus_hwnd(w["hwnd"]):
                els2, raw2 = uia.find(name, window, limit=5)
                if raw2.get("ok") and els2 and _scope_ok(raw2):
                    els, raw, good = els2, raw2, _clickable(els2)
                    where = raw.get("window") or where
                    print("[ai] 「%s」的窗口原本在后面,顶到前台重找了一次" % name, flush=True)

    if els and not good:
        # 找到了,但一个都点不了:要么在屏幕外,要么在屏幕上占不到一个像素。
        #
        # ⚠️ **这种元素绝不能按坐标点。** 它的包围盒不是空的,而是「虚拟滚动
        # 空间」里的天文数字 —— 实测 VS Code 里一个按钮是 y=-64728。照着点,
        # 鼠标会飞到屏幕外,或者落到别的控件上。这正是「没看见却自信地动手」。
        # (所以以前那个 IsOffscreen 一刀切,其实一直在替我们挡这个。)
        #
        # 有控件树的时候根本不必知道它在哪:**让控件自己动作**。
        off = els[0]
        r2, _ = uia.invoke(name, window)
        # invoke 有自己的 Find-Scoped 那一趟,可能**跟 find 落在不同的窗口上**
        # (find 命中不了的时候它会去找别人)—— 同一道闸门得过两次。
        if r2.get("ok") and not _scope_ok(r2):
            r2 = {"ok": False, "error": "找到的那个控件在别的窗口里"}
        if r2.get("ok"):
            if r2.get("need_click"):
                # 控件不接受命令,但 ScrollIntoView 把它滚进视野了 ——
                # 这组坐标是**刚取的、屏幕内的**,可以点。
                click_fn(r2["cx"], r2["cy"])
                return _mark_where({"ok": True, "clicked": [r2["cx"], r2["cy"]],
                                    "control": r2.get("name"), "type": r2.get("type"),
                                    "window": r2.get("window"),
                                    "via": "UIA 滚进视野后点击"})
            return _mark_where({"ok": True, "control": r2.get("name"), "type": r2.get("type"),
                                "window": r2.get("window"),
                                "via": "UIA 直接命令控件(%s),没碰鼠标" % r2.get("action")})

        # 控件不接受命令。**既不能谎报「没有」,也不许掉头就走。**
        # 说「界面上没有」是假话 —— 它明明在,只是动不了。照实说,并给出**观察**,
        # 让上层自己决定下一步(以前这里写死一句「改用 click_element」,
        # 那等于替它做了决定)。
        # 话说准:是「屏幕外」还是「在屏幕上但没面积」,对上层是两条不同的线索
        # (前者可能要滚/切前台,后者多半是命中了不该命中的东西)。
        #
        # ★ 2026-10-03 改:这里**必须再让 OCR 看一眼**,不能当场 return。
        # 这段注释以前写的是「OCR 只看得到屏幕,而 UIA 已经证明屏幕内没有同名控件
        # (屏幕内的排在前面),白跑一趟还多花一秒」。**那个理由有一半是错的** ——
        # 「屏幕内的排在前面」只保证「排在前面的若可点,早被选走了」,
        # 它**不保证**「屏幕内没有另一个能点的同名东西」。走到这儿还有一支是
        # **在屏幕上、但占不到一个像素**的退化节点,那种节点什么也证明不了,
        # 而它底下可能正压着一个 OCR 认得出的真字。
        # 实测就是这么翻的:find 回了个 0×0 的 Text,invoke 动不了,于是整个
        # click_ui 在 OCR **之前**就返回了 —— 能救命的那条路被垃圾命中挡死,
        # 又变回了「看不见 = 没有」。
        #
        # 代价说清楚:补这一趟 OCR 约 1 秒。而这是一条**已经失败**的路径,
        # 上层(手机)接到失败会启动 resolveMiss,最长 215 秒。
        # 拿 1 秒换掉一次 215 秒,顺带换回一次本来能成功的点击 —— 值。
        why = "它在屏幕外" if off.get("offscreen") else "它在屏幕上占不到一个像素"
        stuck = {"error": "找到了「%s」,但%s,而且不接受直接命令" % (off["name"], why),
                 "name": off["name"],
                 "window": where or raw.get("window") or "",
                 "offscreen": bool(off.get("offscreen")),
                 "same_name": raw.get("count") or 0}

    via = "uia"
    if not els or stuck:
        # UIA 在自绘界面里是瞎的(微信那种 Qt/DirectUI),但字是**画在屏幕上**的,
        # 让 OCR 认一遍。这是 UIA 和「眼」之间新加的一层:实测全屏约 1 秒,
        # 而视觉那条路要 215 秒 —— 且给的是同样的像素级包围盒。
        #
        # `stuck`(UIA 找到了、但一个都点不了)也要走这一趟,理由见上面那段。
        oels, oraw = uia.ocr_find(name, window=_ocr_scope(window), limit=5)
        if not oraw.get("ok"):
            # 这是**真故障**(OCR 引擎起不来),不是「没看见」——
            # 所以不给 observation:观察是用来描述"感知到的情况"的,
            # 而这里根本没感知成。上层看到没有 observation 就知道该走通用顺序。
            if not stuck:
                return {"ok": False, "error": oraw.get("error") or "OCR 失败"}
            # 但手里已经攥着一条**确切**的信息(UIA 找到了、只是动不了),
            # 不能因为 OCR 起不来就把它丢了 —— 那又是拿"看不清"盖掉"看得清"。
            print("[ai] OCR 起不来(%s),但 UIA 那边有确切结果,按那个交代"
                  % oraw.get("error"), flush=True)
        else:
            els, raw, via = oels, oraw, "ocr"

    # 走到这儿 els 有两种来路(上面的 UIA / 这里的 OCR)—— 第一种情况里还可能
    # 压根没跑成 OCR。所以**重算一次**,别信上面那个旧的 good。
    #
    # 这也是整个 _click_ui **唯一的出口闸门**:不管命中从哪儿来,
    # 点不了的东西一律不许走到 click_fn。一条路加一道闸,不如一道闸管住所有路。
    good = _clickable(els)
    if not good:
        n_lines = raw.get("total") or 0
        if stuck:
            # 「找到了、但动不了」**且**「屏幕上也没有第二个能点的」——
            # 两条一起给。上层要滚、要搜、要换窗口,靠的是这两条,不是一句「没有」。
            # (n_lines 尤其有用:OCR 认得出字却说没有目标,那就是"目标在更深处";
            #   一个字都没认出来,那是"这窗口没渲染/是空的"。)
            return {"ok": False, "error": stuck["error"],
                    "observation": _obs("offscreen", stuck["window"], stuck["name"],
                                        offscreen=stuck["offscreen"],
                                        same_name=stuck["same_name"],
                                        ocr_lines=n_lines)}

        # **别在这里硬点。** 连屏幕上都没有这几个字,就别编坐标 ——
        # 编了正好复制我们要消灭的那个毛病。
        #
        # 分成两种失败,因为对应的办法完全不同:
        #   blind —— 认得出字,但没有目标 → 多半是**目标在更深处**(要滚动),
        #            或者这个界面**不暴露结构**(自绘,得用它自己的搜索框)。
        #   empty —— 一个字都没认出来 → 窗口是空的或没渲染。
        # 这两种都**不是**「没有这个东西」,只是「这一屏上没有」。别把前者说成后者。
        return {"ok": False,
                "error": "这一屏上没有「%s」" % name,
                "observation": _obs("blind" if n_lines else "empty",
                                    where or raw.get("window"), name, ocr_lines=n_lines)}

    best = good[0]
    click_fn(best["cx"], best["cy"])
    out = {"ok": True, "clicked": [best["cx"], best["cy"]],
           "control": best["name"], "type": best["type"],
           "window": raw.get("window")}
    _mark_where(out)
    if via == "ocr":
        # 让模型知道这次靠的是屏幕文字,不是控件 —— UIA 查不到时它心里有数。
        out["via"] = "OCR 屏幕文字"
    if len(els) > 1:
        # 有歧义就说出来。模型看到之后能改口,而不是以为点对了。
        # 离屏元素常常**同名重复**(实测 VS Code 里「Copy response to clipboard」
        # 一次就抽出 8 个),所以光列名字没用 —— 总数也得报出来。
        out["also_found"] = ["%s（%s）" % (e["name"], e["type"]) for e in els[1:4]]
        n = raw.get("count") or len(els)
        if n > len(els):
            out["also_found"].append("…同名的共查到 %d 个" % n)
    return out


def _scroll(direction, amount, scroll_fn):
    """滚动当前窗口的内容。

    为什么需要它:**UIA 那层「知道屏幕外有什么」的本事,在自绘界面上是失效的** ——
    微信那种窗口的 UIA 树是空的,谁都不知道下面还藏着什么。这时候唯一的办法就是
    **滚一下,让目标进入这一屏**,再重新找一次。

    用鼠标滚轮而不是 PageUp/PageDown:滚轮对列表/聊天记录更通用,
    而 PageUp/PageDown 在不少自绘控件里压根不动。
    """
    d = str(direction or "").strip().lower()
    if d not in ("up", "down"):
        return {"ok": False, "error": "方向只能是 up 或 down", "allowed": ["up", "down"]}
    try:
        n = int(amount)
    except (TypeError, ValueError):
        n = 3
    n = max(1, min(10, n))
    if scroll_fn is None:
        return {"ok": False, "error": "本端没接鼠标控制,滚不了"}
    # Windows 滚轮约定:正值向上,负值向下。
    scroll_fn(n if d == "up" else -n)
    return {"ok": True, "scrolled": d, "amount": n}


def _read_screen(window=None):
    """把屏幕上的文字 OCR 出来,按行返回 —— 「用户问屏幕内容」时的眼睛。

    典型用途:「这段代码为什么报错了」。UIA 在自绘界面/编辑器正文里常常是瞎的
    (VS Code 的代码区几乎不给控件),但字是画在屏幕上的,OCR 认得出。

    ★ 边界要说清:这里回的是**文字**,不是截图 —— 截图永远不出这台机器(硬边界),
    文字则是「用户问的就是它们」才被拿去分析(比如把报错发给云端讲道理)。
    note 字段把这条边界写进回执,让模型自己心里有数。

    ★★ 2026-10-06:出门之前先过一遍 [redact_values] —— 屏幕上**确定是卡号/身份证**
    的那些数字**在这里就被拿掉**,不进回执、不进她的上下文、更不可能被发去云端。
    遮了会在回执里**明说**(`redacted` + note 里一句话)—— 不许静默:
    不说的话她会以为屏幕上真写着星号,然后拿星号当事实用。
    """
    lines, raw = uia.ocr(window=window, limit=200)
    if not raw.get("ok"):
        return {"ok": False, "error": raw.get("error") or "OCR 失败"}
    # 编号行:模型要说「第几行的什么」说得清,云端分析也省得猜。
    numbered = ["%d. %s" % (i, (l.get("name") or "").strip())
                for i, l in enumerate(lines, 1)]
    text = "\n".join(s for s in numbered if not s.endswith(". "))
    note = ("以上是屏幕上的**文字**(OCR,不是截图)。用户问到它们才能外发分析;"
            "坐标没有给 —— 要点东西用 click_ui。")
    text, redacted = redact_values(text)
    out = {"ok": True, "text": text, "count": len(numbered),
           "window": raw.get("window") or "", "note": note}
    if redacted:
        out["redacted"] = list(redacted)
        out["note"] = ("★ 屏幕上有「%s」已经**打码**(只留后四位),原文没有进这份回执 —— "
                       "别去猜它是什么。" % "」「".join(redacted)) + note
    return out


# ---------------------------------------------------------------------------
# 「字到底打进去了没有」—— 输入类工具的验货
# ---------------------------------------------------------------------------
# ★ 2026-10-03 真机翻车,用户原话:「文件传输助手又没发出去东西,只是打开了」。
# 那一轮的 PC 日志,每一步都写着成功:
#
#   search   -> ok=True   via="Ctrl+F 后输入(未回车)"
#   click_ui -> ok=True   clicked=[1036,256] via="OCR 屏幕文字"
#   type     -> ok=True   7ms
#   hotkey   -> ok=True   1ms
#
# 而微信那边的现场是:独立聊天窗口开着、**消息输入框是空的、「发送」按钮是灰的、
# 聊天区一条消息都没有**。模型却报「✅ 已成功发送 helloworld!」。
#
# 根子:**type 从来没验过货。** 它把字交给 SendInput,只要函数不抛异常就回 ok=True。
# 可 SendInput 的返回值只说「系统收下了这批事件」—— 它**不知道**这些事件最后有没有
# 变成屏幕上的一行字。焦点窗口不收合成键盘输入(Qt / 自绘 / 被输入法接管),或者
# 输入框压根没有焦点,字就当场蒸发,而回执上写着「成功」。
#
# 这和 click_ui 那边早就治过的病是同一个,只是方向相反:
# **「看不见」不许当成「没有」;「发出去了」也不许当成「打进去了」。**
#
# 怎么验 —— 两件仪器,分工不一样。**这一点第一版设计错了,2026-10-03 当场改的**:
#
# 仪器一:打字前后比屏幕。便宜、确定,但**只是个便宜的肯定**,不是判据。
#     先拍两张间隔的图,量出「没人动的时候屏幕自己会变多少」(光标闪烁、时钟、
#     动画)当基线,再拍打完的那张。基线让阈值自己长出来,不用拍脑袋定。
#
# 仪器二:OCR 当场找那几个字。慢(实测一次 ~3 秒),但在屏幕闹的时候**只有它还管用**。
#
# ★ 第一版的错:环境噪声一大(比如我的终端在刷新)就直接回 `verified="unknown"`,
#   **把唯一还管用的仪器也一起扔了** —— 那不是谨慎,是把功能关掉。
#   实测这台机器:打字 12 个字符只改变 **202** 个像素;安静时基线 10(阈值 50,够用),
#   可终端一刷新基线就跳到 **11000**(阈值 22030,202 永远够不着)。
#   照第一版,用户终端一开,这个修复在真机上就等于没做。
#
# 现在的顺序:
#   1. 像素变化**明显超过噪声** → 直接算成功,不惊动 OCR(快路径,绝大多数情况走这里);
#   2. 像素分不出来(屏幕闹 / 抓不到图)→ 交给 OCR 定,而不是投降;
#   3. OCR 也**没有证据可用**才回 `unknown`;OCR 跑通了却说没有,才是真的没有。
#
# 判据的不对称是故意的:**只有确实没打进去才回 ok=False**;两件仪器都用不了时回
# ok=True 但标 `verified="unknown"` —— **绝不把自己没验过的事说成验过了**,
# 也绝不用「仪器坏了」去冤枉一次正常的输入。

# 「上一次 type 到底打进去了没有」—— 给 hotkey 的回车当闸门。
# ★ 为什么非要有这道闸(2026-10-03 同一场翻车):type 没打进去,紧接着的 hotkey
# enter 照样按下去了,而回车在不少界面里是「确认 / 打开 / 发送」。
# 往一个**字都没打进去**的地方按回车,轻则白按,重则触发了默认按钮(确认删除、
# 发出空消息)。更糟的是模型看到连回车都「成功」,就把「已发送」写进了回答里 ——
# 这正是用户看到的那句「只是打开了」。
_last_type = {"ok": None, "at": 0.0}


def _remember_type(out):
    _last_type["ok"] = bool(out.get("ok"))
    _last_type["at"] = time.time()


def _clear_type_flag():
    """点过别的地方之后,「上次输入失败」这条就不该再拦回车了 —— 现场变了。"""
    _last_type["ok"] = None


def _screen_thumb():
    """当前主屏的缩略灰度图。抓不到回 None(非 Windows / 没装 Pillow / 抓屏失败)。"""
    try:
        from PIL import ImageGrab
    except ImportError:
        return None
    try:
        img = ImageGrab.grab()
    except Exception:
        return None
    if img.width > 640:
        img = img.resize((640, max(1, int(img.height * 640.0 / img.width))))
    return img.convert("L")


def _changed_pixels(a, b):
    """两张缩略图里差得明显的像素个数(>30/255 才算,滤掉 JPEG 噪声)。

    不用 numpy:ai_tools 有意只依赖标准库 + Pillow,别为了一个计数把数组库拖进来。
    """
    if a is None or b is None or a.size != b.size:
        return None
    from PIL import ImageChops
    hist = ImageChops.difference(a, b).histogram()
    return sum(hist[31:])


def _text_on_screen(text):
    """屏幕上(先任务窗口、再全屏)OCR 找不找得到这段文字。

    三态 —— 这三种必须分得清,混成两态就是前面那个 bug 的翻版:
      True  —— 找到了。字确实在屏幕上。
      False —— **OCR 确实跑通了,屏幕上确实没有这几个字**。可以定罪。
      None  —— OCR 用不了 / 屏幕上它一个字都没认出来 —— **没有证据**,
               不许当成「没有」。
    """
    needle = (text or "").strip()
    if not needle:
        return None
    scopes = []
    if _task["title"]:
        scopes.append(_task["title"])
    scopes.append(None)      # 全屏兜底:字可能被打进了另一个窗口
    saw_any_text = False     # OCR 认出来的**总行数**>0 = 这台仪器此刻是活的
    for scope in scopes:
        try:
            hits, raw = uia.ocr_find(needle, window=scope, limit=5)
        except Exception:
            continue
        if not raw.get("ok"):
            continue
        if hits:
            return True
        if (raw.get("total") or 0) > 0:
            saw_any_text = True
    # 认出一大堆字却没有目标 = 仪器没坏,是真的没有。这才敢回 False。
    # 一个字都没认出来 = 分不清「屏幕是空的」和「OCR 瞎了」,回 None 别定罪。
    return False if saw_any_text else None


# ★ 为什么每条成功回执里都要塞一句这个(2026-10-03,真机):
# 用户的原话是「打出来但是没发送」。日志里 type 明明成功了(225 像素的变化,
# 证据是真的),然后**就没了** —— 模型没调 hotkey enter,直接给了答复。
# 原因和当初 search 那次一模一样:**回执读起来像个完成态**。「已输入「hello world!」」
# 这句话里没有任何一个字提示「还差一步」,4B 模型就把它当成了句号。
# search 靠 `next` 字段治好了,这里照抄。语气要硬、要说清「type 从不负责发送」,
# 否则模型还是会往「已经发出去了」上理解。
_TYPE_NEXT = ("这段字**只是躺在输入框里,还没有发出去** —— type 从来不负责发送。"
              "如果你要的是「发出去 / 提交」,下一步必须再调一次 hotkey(\"enter\")"
              "(或者 click_ui 点界面上那个「发送」按钮),否则它就永远停在框里;"
              "如果只是填表 / 改内容,那到这里就算完了。"
              "**但别把「字进去了」当成「发出去了」—— 这是两件事。**")


def _type_verified(text, type_fn):
    """type 的实现:打字 + 验货。回执里的 `verified` 说明这次到底验没验成。

    verified 三种取值:
      "screen"  —— 屏幕上真的多出来东西,或者 OCR 当场找到了这几个字;
      "failed"  —— **OCR 跑通了**、屏幕上确实没有这几个字,ok 一定是 False;
      "unknown" —— 两件仪器都给不出证据,**没验过**,别当成验过了。
    """
    shown = text[:80] + ("…" if len(text) > 80 else "")

    base = _screen_thumb()
    ambient = None
    if base is not None:
        time.sleep(0.25)
        ambient = _changed_pixels(base, _screen_thumb())

    type_fn(text)
    time.sleep(0.35)         # 等界面把字画出来 —— 打进去到画出来之间有一帧的差

    # ---- 仪器一:像素。只是**便宜的肯定**,超过噪声就直接放行,不惊动 OCR。----
    changed = _changed_pixels(base, _screen_thumb()) if base is not None else None
    if changed is not None:
        # 基线自己会长:光标闪一下约几个像素。把「无人操作时的变化」乘 2 再加一点
        # 余量,免得把光标闪烁当成「打进字了」。
        # 屏幕太闹时 floor 会变得很大 —— 那只是让这条路**失效**,不是判失败,
        # 下面 OCR 接着管。
        if changed > (ambient or 0) * 2 + 30:
            return {"ok": True, "typed": shown, "verified": "screen",
                    "via": "屏幕变化", "changed_px": changed, "next": _TYPE_NEXT}

    # ---- 仪器二:OCR。像素分不出来的时候,它才是判据。----
    # 找两遍:OCR 抓屏有可能比界面把字画出来早一步(实测撞到过一次 —— 字明明
    # 在屏幕上,头一遍却一个字都没认出来)。第二遍隔开一点再看。
    saw = None
    for attempt in (1, 2):
        saw = _text_on_screen(text)
        if saw is not None:
            break
        time.sleep(0.6)

    if saw is True:
        return {"ok": True, "typed": shown, "verified": "screen", "via": "OCR 找到",
                "next": _TYPE_NEXT}
    if saw is None:
        return {"ok": True, "typed": shown, "verified": "unknown",
                "note": "屏幕比对和 OCR 这次都给不出证据(屏幕变化太频繁,或者 OCR "
                        "认不出这一屏的字),**没能确认字到底进去没有**。",
                "next": _TYPE_NEXT}

    return {
        "ok": False,
        "error": "字没打进去:屏幕上前后几乎没变化(差了 %s 个像素),OCR 把这一屏"
                 "认得清清楚楚,里面就是没有「%s」。**这段文字现在不在任何地方** ——"
                 "别当它发出去了。"
                 % ("?" if changed is None else changed, text[:24]),
        "typed": shown,
        "verified": "failed",
        "changed_px": changed,
        "hint": "多半是当前焦点窗口不收合成键盘输入(自绘 / Qt 应用 / 被输入法接管),"
                "或者它的输入框没被点中。先用 click_ui 点一下**要输入的那个框**,"
                "再 type 一次。",
    }


def _enter_verified(keys):
    """回车 / 提交键 —— 按下去,**并看一眼到底有没有事发生**。

    为什么非要做(2026-10-03,真机):到这一步为止,`hotkey` 是整条链上**唯一一个
    还闭着眼睛回成功的** —— `_press_hotkey` 只要没抛异常就回 ok=True,1 毫秒返回。
    于是「回车按下去了」和「消息发出去了」在日志里长得一模一样,而用户看到的
    是消息躺在输入框里没动。

    ★ 这里的仪器只有一件:**屏幕变化**,而且只能用「有没有变」,不能用「变了多少」。
      为什么不上 OCR —— 因为 OCR 在这件事上**根本分不清**「字还在输入框里」和
      「字已经发出去了」:两种情况屏幕上都有这几个字。**拿一件分不清的仪器去定罪,
      比没有仪器更糟**。(用户提的那个猜想 —— 先发过一句 hello world、再发同一句,
      会不会被误判成成功 —— 在 OCR 这条路上答案是「会」。所以这里不走 OCR。)

    ★ 三态还是那三态,一次都不许混 —— 但**两头的严格程度故意不同**:
      - **定罪**要最严:基线绝对静止(ambient==0)**且**之后逐像素一模一样
        → ok=False。这是正面证据:按了回车什么都没画出来。措辞写成「屏幕上前后
        一个像素都没变」—— 说我们真看见的那个东西,不写「回车失败」(我们并不知道
        那一下键有没有送到)。
      - **认「有反应」**可以宽松:基线静止时任何变化都算(没有别的东西在动,
        那就是这一下按出来的);基线在动时,变化够得着噪声门槛也算。
        注意这**不等于**「发出去了」—— 有反应只说明有反馈,措辞只说「有反应」,
        **绝不写「已发送」**。
      - 分不出来(抓不到图 / 基线在动且变化落在噪声里)→ ok=True +
        verified="unknown",**绝不用「仪器坏了」去冤枉一次正常的发送**。
    """
    base = _screen_thumb()
    ambient = None
    if base is not None:
        time.sleep(0.25)
        ambient = _changed_pixels(base, _screen_thumb())

    ok, err = _press_hotkey(keys)
    if not ok:
        return {"ok": False, "error": err, "allowed": allowed_keys()}

    if base is None:
        return {"ok": True, "pressed": keys.strip(), "verified": "unknown",
                "note": "这一下按出去了,但本端抓不到屏幕,没能确认它有反应。"}

    time.sleep(0.45)     # 等界面把「发出去了」这件事画出来
    changed = _changed_pixels(base, _screen_thumb())

    # ---- 判据的不对称,是这里唯一的设计要点。----
    #
    # 实测(本机 1920×1080,缩到 640 宽的灰度图,2026-10-03 干净标定):
    #   静置 20 次 → ambient 有 16 次**恰好是 0**,其余是 1 / 4 / 721 / 4368。
    #   「屏幕完全不动」是常态,不是奢望。
    #
    # ★ **定罪**(说「什么都没发生」)要求基线**绝对静止,且之后逐像素完全相同**。
    #   判据是 `changed == 0`,**不是**「小于某个门槛」—— 这一条是被数据逼出来的:
    #   记事本里打「hello world」改 10790 个像素,而紧接着按回车只改 **20** 个
    #   (光标下移一行,文字本身没动)。任何 `> ambient*2+30` 这种门槛都会把这次
    #   **真实发生**的回车判成失败,而失败的代价是:模型会把一条**已经发出去**的
    #   消息再发一遍。`==0` 不需要猜门槛:前后一模一样,就是什么都没画出来。
    #
    # ★ 但**认「有反应」可以宽松得多** —— 这是 2026-10-03 真机跑通后补的:
    #   那次回车的 changed_px=850(变化明明很大),却因为基线不是绝对静止而回了
    #   `unknown`,白白扔掉了一个抓在手里的证据。两件事的代价不对称,判据就不该共用一个:
    #     说「有反应」说错了 → 只是措辞弱,后面还有「这不等于发出去了」兜着;
    #     说「什么都没发生」说错了 → 模型重发,用户收到两条。
    #   所以:基线绝对静止时,**任何**变化都必然是这一下按出来的(没有别的东西在动);
    #   基线在动时,变化够得着噪声门槛也算数。
    still = (ambient == 0)

    if changed is None:
        return {"ok": True, "pressed": keys.strip(), "verified": "unknown",
                "note": "抓到的两张屏幕图对不上(分辨率中途变了?),**没能比** —— "
                        "别当已经发好了。"}

    if changed == 0 and still:
        return {"ok": False, "pressed": keys.strip(), "verified": "failed",
                "changed_px": 0,
                "error": "回车按下去了,但**屏幕上前后一个像素都没变**"
                         "(基线先静置确认过是完全静止的)。如果这一下本该是"
                         "「发送 / 确认」,那**它没有发生** —— 内容多半还躺在输入框里。",
                "hint": "先用 click_ui 点一下**那个输入框**(或者它的「发送」按钮),"
                        "确认焦点真在那上面,再 hotkey enter。"
                        "**不要以为按过回车就算发出去了。**"}

    if still or changed > (ambient or 0) * 2 + 30:
        return {"ok": True, "pressed": keys.strip(), "verified": "screen",
                "changed_px": changed,
                "note": "屏幕上确实动了 %s 个像素 —— 有反应,但**这不等于已经发出去了**。"
                        % changed}

    # 基线自己在动,而变化又够不着噪声门槛 —— 两件都够不上,老实说分不出来。
    return {"ok": True, "pressed": keys.strip(), "verified": "unknown",
            "changed_px": changed,
            "note": "屏幕本来就在自己动(基线 %s 像素/0.25 秒),而这一下的变化(%s)"
                    "又落在那片噪声里,分不出有没有反应 —— **没能确认发出去没有**,"
                    "别当已经发好了。"
                    % (ambient if ambient is not None else "?", changed)}


# 浏览器地址栏的几种叫法。有它 = 这个窗口是浏览器,而**地址栏就是它的搜索框**。
# Edge / Chrome 中文版:「地址和搜索栏」;英文版:「Address and search bar」;
# Firefox:「地址栏」。都按子串找,所以不必穷举。
_ADDRESS_BAR_NAMES = ("地址和搜索栏", "地址栏", "搜索或输入网址",
                      "address and search bar", "address bar")


def _address_bar(window):
    """当前窗口有没有地址栏 → 有就返回那个控件(没有则 None)。

    ★ 为什么非要有这一步(2026-10-03 拿 Edge 试「别的软件能不能用」时撞出来的):
    `_search` 的实现是 **Ctrl+F**。在微信里 Ctrl+F 是「搜会话」,所以那个写法在那里
    是对的 —— **可搬到浏览器就是错的**:实测在 Edge 里按下 Ctrl+F,UIA 树里新冒出来的
    控件叫「在页面上查找 / 上一个结果 / 下一个结果」。也就是说,用户说「用 Edge 搜一下
    XXX」,这条链会**一路回成功**地把词打进那个「本页内查找」框,然后什么也没搜到。

    这就是「只在一个软件上能用」最典型的来源:**把一个应用里成立的写法当成了通用写法**。
    判据不能是应用名(那要维护一份名单),得是**界面上有没有这个东西** —— 有地址栏的
    就是浏览器,而浏览器的搜索框就是它。
    """
    for name in _ADDRESS_BAR_NAMES:
        try:
            hits, raw = uia.find(name, window=window, limit=1)
        except Exception:
            continue
        if raw.get("ok") and hits:
            return hits[0]
    return None


def _fg():
    """当前前台窗口 → (hwnd, 标题)。判「网页真的跳了没有」用的就是它。"""
    hwnd = user32.GetForegroundWindow()
    return hwnd, (_window_title(hwnd) if hwnd else None)


def _wait_navigated(hwnd0, title0, seconds):
    """盯 seconds 秒,看目标窗口有没有**跳走**。

    → ("jumped", 新标题) / ("left", None) / ("still", None)

    ★ 判据为什么是**标题**而不是像素(2026-10-03,真机教的):
    [_enter_verified] 那套像素判据在这儿**恰好会把人骗过去** —— 实测在 Edge 里,
    第一下回车明明没导航,屏幕上却实实在在变了 5558 个像素(自动补全下拉框收起来了)。
    像素只知道「有东西变了」,分不清「下拉框关了」和「页面跳了」,于是回执写着
    verified="screen"(有反应),而页面纹丝不动。
    浏览器的标题则**一导航就一定变**(「新标签页」→「claude - 搜索」,实测 0.31 秒),
    又便宜又准。

    句柄必须一起比:前台要是被别的窗口抢走了,标题也会「变」——
    那不是导航成功,是它根本没在前台。**把「变了」当成「做成了」是本项目的老毛病。**
    """
    end = time.time() + seconds
    while time.time() < end:
        hwnd, title = _fg()
        if hwnd != hwnd0:
            return "left", None
        if title != title0:
            return "jumped", title
        time.sleep(0.08)
    return "still", None


def _search_in_browser(query, bar, type_fn):
    """浏览器里的「搜一下」= 把词写进**地址栏**(它同时就是搜索框),**并回车搜出去**。

    用 Ctrl+L 而不是点坐标:浏览器里 Ctrl+L 就是「聚焦地址栏」,而且它**会顺手整条
    选中已有的网址**,所以紧接着打字是整体替换,不会拼在旧 URL 屁股后面。
    点击那条路还要处理「地址栏是不是空的」,多一个不确定性,没理由要。

    ★ 为什么提交这一步**必须在这儿做**(2026-10-03,第一版拆给模型做,当场就错):
    第一版是「只写词、不回车」,回执里写着「还差一步:hotkey(enter)」。真机跑下来
    **每一步都回成功、页面却一次都没跳**。查出来是这个:

        在 Edge 地址栏打完字、自动补全下拉框弹出来时,**第一下回车不导航**
        —— 第二下才跳。实测 9 轮无一例外(5 轮单按全没动,4 轮补第二下全跳了)。

    而这个「第一下被吃掉」的事,`hotkey(enter)` 那边是**测不出来**的:它只按一下,
    然后看屏幕 —— 屏幕确实动了(下拉框收起来了)。于是它老老实实回 ok=True,
    模型以为搜完了,用户看到的就是「说了搜过了,可页面还在原地」。

    根子是**动词切错了**:浏览器里「字进了地址栏」和「真的搜出去」是**同一个动作的
    两半**(微信那边不同 —— 那儿回车意味着「打开第一个结果」,是个真该由模型拍板的
    导航决定,所以 [_search] 至今仍然不按回车)。拆成两次调用,中间那半就没人负责了。

    ★ 所以重试回车这件事只放在这里,不放 [_enter_verified]:这里上下文是确定的
    (「我刚把词写进地址栏,现在要的就是搜出去」)。放到通用的 hotkey 上去重试是危险的
    —— 在别的窗口里,回车可能就是「发送」/「提交表单」,补第二下等于重复提交。
    """
    ok, err = _press_hotkey("ctrl+l")
    if not ok:
        return {"ok": False, "error": err}
    time.sleep(0.35)   # 等地址栏拿到焦点
    tout = _type_verified(query, type_fn)
    _remember_type(tout)
    if not tout.get("ok"):
        return {"ok": False, "blocked_by": "search_not_typed",
                "error": "搜索词没打进地址栏。" + (tout.get("error") or ""),
                "hint": "Ctrl+L 没被这个浏览器接住(它不在前台?)。先 focus_window "
                        "把它切到前台再 search。"}

    # ---- 提交:回车。第一下可能被自动补全吃掉,那就补第二下 ----
    hwnd0, title0 = _fg()
    jumped, tries, lost = None, 0, False
    for tries in (1, 2):
        # 按之前先确认前台**还是**这个浏览器。焦点要是被抢走了,这一下回车就打到
        # 别人窗口上去了 —— 而在别的应用里,回车可能是「发送」/「确认删除」。
        # 宁可判失败,也绝不对着一个不知名的窗口敲回车。
        hwnd, _ = _fg()
        if hwnd0 and hwnd != hwnd0:
            lost = True
            break
        ok, err = _press_hotkey("enter")
        if not ok:
            return {"ok": False, "error": err, "allowed": allowed_keys()}
        # 第一下等 1.6 秒够了:实测标题在导航开始后 0.31 秒就变了,不必等页面画完。
        state, jumped = _wait_navigated(hwnd0, title0, 1.6 if tries == 1 else 2.5)
        if state == "jumped":
            break
        if state == "left":
            lost, jumped = True, None
            break

    if lost or not jumped:
        if lost:
            return {"ok": False, "blocked_by": "lost_focus",
                    "error": "词已经写进地址栏了,但**前台窗口被抢走了**,所以没再按回车"
                             "(那一下会打在那个不知名的窗口上)。",
                    "hint": "先 focus_window 把这个浏览器切回前台,再 search 一次。"}
        return {"ok": False, "blocked_by": "search_not_submitted",
                "error": "搜索词写进地址栏了,但**按了两次回车页面都没动** —— 没搜出去。",
                "hint": "这个窗口可能只是**看起来**在前台。先 focus_window 把它切到前台,"
                        "再 search 一次。**别以为按过回车就算搜过了。**"}

    return {"ok": True, "searched": query, "via": "浏览器地址栏(Ctrl+L + 回车)",
            "page": jumped, "enter_tries": tries, "verified": "title",
            "next": "**已经搜出去了**,页面标题现在是「%s」,结果就在网页上。"
                    "下一步用 click_ui(name=结果中那一条的标题)把它点开。"
                    "搜索已经发生了,**不用再按回车**。" % jumped}


def _search(query, type_fn):
    """用当前界面**自己的搜索**把目标捞出来 —— 不靠看,靠问。

    这是绕开视口最彻底的一招:搜索框问的是「**你有没有**这个东西」,
    而不是「屏幕上画没画出来」。东西埋得多深、要不要滚几十屏,全都不重要了。

    实现就是发 Ctrl+F(微信和绝大多数 Windows 应用都是它)再输入词。
    **故意不按回车**:回车在有些应用里等于「直接打开第一个结果」,那是个导航决定,
    该交给下一步(click_ui 去点结果),不该在这里替它拍板。
    """
    q = str(query or "").strip()
    if not q:
        return {"ok": False, "error": "没给要搜的词"}
    if len(q) > TYPE_MAX_LEN:
        return {"ok": False, "error": "要搜的词太长(上限 %d 字)" % TYPE_MAX_LEN}
    if type_fn is None:
        return {"ok": False, "error": "本端没接键盘输入"}

    # ★ 先分岔:是浏览器就走地址栏,不是才走 Ctrl+F。理由见 [_address_bar]。
    bar = _address_bar(_task["title"]) if _task["title"] else None
    if bar is not None:
        return _search_in_browser(q, bar, type_fn)

    ok, err = _press_hotkey("ctrl+f")
    if not ok:
        return {"ok": False, "error": err}
    time.sleep(0.35)   # 等搜索框弹出来并拿到焦点,不然字会打到别的地方去
    # ★ 搜索词也要验货(2026-10-03):搜索框要是没拿到焦点,这一下「搜」既没搜到
    # 东西、又把后面整条链(click_ui 找不到结果 → type 打进空处 → 回车)全带偏,
    # 而每一步都回「成功」。就地判死,比让它烂到下游便宜得多。
    tout = _type_verified(q, type_fn)
    _remember_type(tout)
    if not tout.get("ok"):
        return {"ok": False, "blocked_by": "search_not_typed",
                "error": "搜索词没打进搜索框。" + (tout.get("error") or ""),
                "hint": "Ctrl+F 可能没被当前窗口接住(它不在前台,或这个应用不用 Ctrl+F)。"
                        "先 focus_window / click_ui 点中这个应用,或直接点它的搜索框,再 search。"}
    time.sleep(0.35)   # 等结果渲染出来,紧接着的 click_ui 才找得到
    # ★ `next` 这一步不能省(2026-10-03):真机上模型调完 search 就**直接 type 了**,
    # 字全打进了那个还没收起来的搜索框,回车之后变成了「搜索这句话」——
    # 用户看到的就是「搜到了「文件传输助手」,但进不去」。
    # 原因不是它笨,是**它以为 search 已经把事办完了**:回执里写着
    # 「已搜索「文件传输助手」」,读起来就是个完成态。所以把「还差一步」写在明面上。
    return {"ok": True, "searched": q, "via": "Ctrl+F 后输入(未回车)",
            "next": "搜索框里只是把「%s」打进去了,**还没有选中任何结果**,也还没有打开它。"
                    "现在结果列表就在下面 —— 用 click_ui(name=\"%s\") 点中列表里的那一条,"
                    "才算真的进去。**在点中之前不要 type**,否则字会打进搜索框。" % (q, q)}


# ---------------------------------------------------------------------------
# 工具表 + 统一入口
# ---------------------------------------------------------------------------
# 每个工具:描述(给模型看)+ 参数表 + 执行函数。
# 注意 execute() 只认这张表里的名字 —— 白名单就是这里。
#
# 表里两样可选字段,是给「手协议」用的(见 hand_info):
#
#   "model": False —— 这个工具**不给文本模型看**。手机端要的是「模型看得见的
#       那一份」,而 screenshot / click_at 是像素原语:文本模型看不到图,给它
#       坐标原语只会让它瞎猜。以前这条规矩只活在手机端那份手抄清单里,
#       现在把它变成电脑端的数据 —— 少一处会跑偏的地方。
#
#   "locks": [...] —— 执行它会占用电脑上的什么**排他资源**。
#       手机侧同时可能有好几件事在跑,谁也拦不住两个任务一起抢鼠标。
#       ★ **不写这个键 = 独占**(整台电脑排队)—— 新加工具忘了写是安全的,
#       写错了才是危险的,所以默认往严的那边倒。
#       已经核对过是只读的工具写 `[]`(空列表 = 不占任何东西),别省。
TOOLS = {
    "open_app": {
        "desc": "打开电脑上的某个应用,如 微信/网易云音乐/Chrome/记事本",
        "params": {"name": "应用名(中文名或开始菜单里的名字)"},
        "locks": ["pc.foreground"],          # 新窗口会抢前台
    },
    "list_windows": {
        "desc": "列出电脑当前所有可见窗口的标题和进程名",
        "params": {},
        "locks": [],                          # 只读,不占
    },
    "focus_window": {
        "desc": "把标题包含某关键词的窗口切到前台",
        "params": {"title": "窗口标题关键词"},
        "locks": ["pc.foreground"],
    },
    "media": {
        "desc": "控制电脑正在播放的媒体:播放/暂停、上一首、下一首、停止、静音、音量加减",
        "params": {"action": "play_pause | next | prev | stop | mute | volume_up | volume_down"},
        # 媒体键是全局的,不碰前台窗口;但「先暂停再下一首」和「先下一首再暂停」
        # 结果不一样,所以媒体命令之间仍然要排队 —— 单独一把锁,不占用 pc.keyboard。
        "locks": ["pc.media"],
    },
    "get_state": {
        "desc": "看电脑当前状态:前台窗口是哪个,以及打开了哪些窗口",
        "params": {},
        "locks": [],
    },
    "screenshot": {
        "desc": "截取电脑屏幕(给视觉模型用来找界面元素的位置)",
        "params": {},
        "locks": [],
        "model": False,                       # 像素原语,不给文本模型看
    },
    "click_at": {
        "desc": "在屏幕的某个坐标点一下左键(坐标是真实屏幕像素,不是截图里的像素)",
        "params": {"x": "横坐标", "y": "纵坐标"},
        "locks": ["pc.mouse", "pc.foreground"],
        "model": False,
    },
    "list_ui": {
        "desc": "列出电脑当前窗口里所有可以点的控件(按钮/菜单/输入框的名字和位置)。"
                "想知道「能点什么」时用它,比截图快几百倍。",
        "params": {"window": "窗口标题关键词,不填就当前窗口"},
        "optional": ["window"],
        "locks": [],                          # 只读 UIA 树
    },
    "read_screen": {
        "desc": "把屏幕上的**文字**认出来(OCR),按行返回编号的文本。"
                "用户问「屏幕上的」「代码为什么报错」「这个错误是什么」这类问题时,"
                "先用它把文字拿到手再分析。★ 它只回文字、不给坐标:"
                "想知道「能点什么」用 list_ui,要点就用 click_ui。"
                "只读、不改屏幕。",
        "params": {"window": "只认哪个窗口里的文字,不填就整个屏幕"},
        "optional": ["window"],
        "locks": [],                          # 只读 OCR
    },
    "click_ui": {
        "desc": "按**名字**点击电脑上的控件(如「发送」「关闭」「文件」「开始」)。"
                "毫秒级、精确到像素。点屏幕上看得见文字的东西优先用它。"
                "它连**屏幕外**的控件也找得到(不止屏幕上画出来的),"
                "找不到时会如实回报「这一屏上没有」并说明为什么,由上层决定下一步。",
        "params": {"name": "控件名字,用界面上看得见的文字",
                   "window": "限定在哪个窗口里找,不填就先当前窗口再全桌面"},
        "optional": ["window"],
        # ★ 找 + 点必须**整段**占着锁:找完到点下去之间要是被别的任务切走了
        # 前台窗口,就会点进另一个窗口里去。这也是它跟 list_ui 的区别 ——
        # list_ui 只看不点,不需要锁。
        "locks": ["pc.mouse", "pc.foreground"],
    },
    "set_value": {
        "desc": "把一个值**直接写进**某个输入框(不点、不打字、不管焦点在谁身上)。"
                "这是「跨应用读写」里**写**的那一半:type 往当前焦点框里敲字,"
                "焦点一跑就敲进别处;这个工具是**指名道姓**写某个控件,前台是谁都不影响。"
                "只对普通输入框有效 —— 自绘界面(微信聊天框、网页富文本编辑器)不支持时"
                "会如实回失败,那时候退回 click_ui + type。"
                "写完会当场读回来核对(回执 verified);verified=unknown 只表示读不回来,"
                "**不等于写成功**。",
        "params": {"name": "控件名字(和 click_ui 用的是同一个名字)",
                   "text": "要写进去的内容",
                   "window": "限定在哪个窗口里找,不填就先当前窗口再全桌面"},
        "optional": ["window"],
        # 它不碰鼠标键盘,但**改的是别的应用里的内容** —— 和 type 抢的是同一块地,
        # 所以占同一对锁。焦点被切走时写进去,就是往别人的框里灌字。
        "locks": ["pc.keyboard", "pc.foreground"],
    },
    "scroll": {
        "desc": "滚动当前窗口的内容。要找的东西可能要滚动才看得到时用它 —— "
                "滚完再调一次 click_ui 找。方向是相对当前位置的。",
        "params": {"direction": "up 或 down",
                   "amount": "滚多少,1~10,默认 3(数字越大滚得越远)"},
        "optional": ["amount"],
        "locks": ["pc.mouse", "pc.foreground"],   # 滚的是当前焦点窗口
    },
    "search": {
        "desc": "在当前界面里搜一个词。**程序会按应用自己选对的方式搜**,而且两种方式"
                "对「搜完了没有」的答案不一样,回执里的 next 会写清楚,照着做就行:"
                "(1) **浏览器**(Edge/Chrome/Firefox,靠界面上有没有地址栏认出来):"
                "词写进**地址栏**做网页搜索,**回车也由 search 自己按** —— 回来的"
                "ok=True 就表示**页面已经跳了、结果已经在网页上**,直接 click_ui 点结果,"
                "**不用再按回车**。"
                "(2) **别的应用**(微信这类):发 Ctrl+F 把词打进应用自己的搜索框,"
                "但**故意不按回车** —— 那儿的回车是「打开第一个结果」,是个导航决定,"
                "留给 click_ui 去点。回执里会说「还没选中任何结果」。"
                "适合「东西在很深的地方、要滚很久」的场景 —— 让应用自己把它捞出来,"
                "比一屏一屏翻快得多,而且埋多深都不怕。"
                "★ 判据是**回执里的 next**,别背规则:同一个 search 在两个地方语义不同。",
        "params": {"query": "要搜的词,如人名、会话名、文件名、关键词"},
        # 两条路都要敲键盘,而且都建立在前台窗口是谁之上
        "locks": ["pc.keyboard", "pc.foreground"],
    },
    "hotkey": {
        "desc": "按一个**安全**组合键(修饰键只有 ctrl / alt,主键得在白名单里)。"
                "微信里搜会话/发消息、切输入框聚焦用它。"
                "★★ 要**关掉当前这个窗口**(退出/关闭/最小化类的要求)就用 "
                "hotkey(keys=\"alt+f4\") —— 那是关窗口的唯一正确动作,"
                "**不要**把这个词拿去 search 或 click_ui,那个窗口里根本没有一样东西叫这个名字,"
                "拿它去搜只会把「关闭」两个字打进这个应用自己的搜索框。"
                "★★ 有**危险组合**会被直接拒绝:ctrl+f4(关文档,可能带着没保存的东西)、"
                "ctrl+delete / alt+delete / ctrl+backspace / alt+backspace(一次删掉一整段)。"
                "这一条没有商量 —— 被拒了就换做法,别换个写法重试同一个意思。"
                "注意:白名单外的键也会被拒绝 —— Win 键、ctrl+w 这类一律不开。"
                "★ enter 是**提交键**:发消息的最后一步就是它 —— 按下后会当场看屏幕"
                "有没有反应,屏幕一动不动就回失败(那说明内容还躺在输入框里没发出去)。"
                "所以 type 完**必须**补一次 hotkey(\"enter\"),别以为打进去就等于发出了。",
        "params": {"keys": "组合键,如 alt+f4 关窗口 / ctrl+f / ctrl+v / enter / esc"},
        "locks": ["pc.keyboard", "pc.foreground"],
    },
    "type": {
        "desc": "往电脑当前焦点输入一段文字(中文/emoji 都行)。"
                "配合 hotkey 用:先 ctrl+f 搜会话,再 type 输入名字,回车选中,"
                "再 type 输入要发的内容,回车发送。执行前会让用户确认内容。"
                "**打完会当场核对屏幕上有没有出现这段字**:没出现就回失败,"
                "并且那之后的回车会被拦下 —— 别把失败当成功。"
                "遇到失败:先 click_ui 点中要输入的那个框,再 type 一次。"
                "★ **type 只负责把字写进输入框,它不发送。**要「发出去」,"
                "type 成功之后必须**再调一次 hotkey(\"enter\")**(或 click_ui 点"
                "「发送」按钮)—— 少这一步,消息就永远停在框里,用户只会看到字打出来了"
                "却什么也没发出去。",
        "params": {"text": "要输入的文字"},
        "locks": ["pc.keyboard", "pc.foreground"],
    },
}


def locks_of(name):
    """→ 这个工具要占的排他资源列表;`None` 表示**没声明 = 独占**。

    ★ 刻意区分 `None`(没人声明过)和 `[]`(核对过、确实不占):
    两者要是混成一个,以后新加的工具就会**悄悄**变成「不占任何资源」——
    而并行里最难查、后果最脏的错,正是这个。
    """
    spec = TOOLS.get(name) or {}
    return spec.get("locks")            # None 或 list


def tool_schema(for_model=True):
    """把 TOOLS 转成 OpenAI/llama.cpp 的 tools 数组,直接塞进请求体。

    参数默认**必填**;要可选就在 spec 里写 `"optional": ["window"]`。
    (曾经这里无条件把所有参数标成必填,于是「不填 window 也行」这种工具
    会逼着模型每轮都编一个 window 出来 —— 它编不准,结果反而更差。)

    for_model=True 时跳过标了 `"model": False` 的工具(screenshot / click_at
    这类像素原语)。手机端要的是「模型看得见的那一份」,而这份清单以前是
    手抄在 Kotlin 里的 —— 现在从这里出去,少一处会跑偏的地方。
    """
    out = []
    for name, spec in TOOLS.items():
        if for_model and spec.get("model") is False:
            continue
        props = {k: {"type": "string", "description": v} for k, v in spec["params"].items()}
        opt = set(spec.get("optional") or ())
        out.append({
            "type": "function",
            "function": {
                "name": name,
                "description": spec["desc"],
                "parameters": {
                    "type": "object",
                    "properties": props,
                    "required": [k for k in spec["params"] if k not in opt],
                },
            },
        })
    return out


def schema_fingerprint():
    """工具表的指纹(名字 + 参数 + 必填,**不含描述文字**)。

    手机端拿它比对,一眼判断「我钉住的那份是不是旧了」。
    刻意不含描述:手机那份描述是为这个 4B 模型专门调过的(评测一条条逼出来的),
    和这边的通用描述本来就不该一样 —— 把它们算进指纹,只会天天误报。
    """
    canon = {
        name: {
            "params": sorted(spec.get("params") or {}),
            "required": sorted(set(spec.get("params") or {})
                               - set(spec.get("optional") or ())),
            "model": spec.get("model") is not False,
        }
        for name, spec in sorted(TOOLS.items())
    }
    blob = json.dumps(canon, sort_keys=True, ensure_ascii=False).encode("utf-8")
    return hashlib.sha256(blob).hexdigest()[:12]


HAND_KIND = "windows-pc"


def hand_info(port=None):
    """这只手的**自述** —— 「我是谁、我会什么」。

    这是「手协议」的核心:手机端不该靠手抄一份清单来知道电脑会什么,
    应该问它。同一份东西也决定了手机端能不能发现自己手上的表过期了。

    → {id, kind, name, version, tools, hidden, locks}
       tools  —— 给模型看的那一份(OpenAI 格式),就是 tool_schema(True)
       hidden —— 有、但**故意不给模型看**的(像素原语)
       locks  —— 工具名 → 要占的排他资源;值是 null 表示「没声明 = 独占」
    """
    info = {
        "id": socket.gethostname() or "pc",
        "kind": HAND_KIND,
        "name": socket.gethostname() or "电脑",
        "version": schema_fingerprint(),
        "tools": tool_schema(for_model=True),
        "hidden": sorted(n for n, s in TOOLS.items() if s.get("model") is False),
        "locks": {n: locks_of(n) for n in TOOLS},
    }
    if port is not None:
        info["port"] = port
    return info


def execute(call, click_fn=None, type_fn=None, scroll_fn=None):
    """执行一个工具调用。

    call = {"tool": "open_app", "args": {...}};click_fn / type_fn / scroll_fn
    由 server.py 注入(鼠标/键盘原语都在那边,避免两边各写一份 ctypes 代码)。
    → {"ok": bool, ...}
    """
    if not isinstance(call, dict):
        return {"ok": False, "error": "工具调用格式不对"}
    tool = str(call.get("tool") or "").strip()
    args = call.get("args") or {}
    if not isinstance(args, dict):
        args = {}
    if tool not in TOOLS:
        return {"ok": False, "error": "未知工具「%s」" % tool,
                "allowed": sorted(TOOLS.keys())}

    # ★ 输入类工具的统一守门 —— 一处收口,不许散进各个工具里(漏接一个 = 没守)。
    # 详见 [_guard_focus]:焦点跑到别的窗口去了,先顶回一次,还被切走就拒绝动手。
    if tool in _GUARDED_TOOLS:
        why = _guard_focus()
        if why:
            print("[ai] %s 被拦:%s" % (tool, why), flush=True)
            return {"ok": False, "error": why}

    # ★★ 三层风险闸 · 第二层(动作级兜底)—— 详见 [risk_reason] 和文件头那一段。
    # ★ 位置故意在 `_guard_focus` **之后**:两道闸管的是不同的事,都要过 ——
    #   焦点闸问「这一步会不会打到别的窗口去」,风险闸问「这一步该不该做」。
    # ★★ `confirmed` 只认 **call 的顶层**,而且必须是**布尔 True**。
    #   模型能填的只有 `args`(payload 里 {"tool","args","id"}),它就算在 args 里
    #   塞一个 "confirmed": true 也**够不着这里** —— 这条有测试钉着。
    #   这一层是「用户用手指点过确认」的唯一凭证,不能有任何别的来源。
    if call.get("confirmed") is not True:
        # ★ 「应用是不是钱」那一半要知道前台是谁 —— 非 Windows 上查不到,那就不判这一半,
        #   但**控件名那一半照判**(它不依赖任何系统 API)。查不到 ≠ 放行。
        fg = 0
        if IS_WINDOWS:
            try:
                fg = user32.GetForegroundWindow() or 0
            except Exception:
                fg = 0
        why = risk_reason(tool, args,
                          _proc_name(_pid_of(fg)) if fg else "",
                          _window_title(fg) if fg else "")
        if why:
            print("[ai] %s 被风险闸拦下,等用户确认:%s" % (tool, why), flush=True)
            return {"ok": False, "blocked_by": "needs_confirm", "error": why}

    try:
        if tool == "open_app":
            return open_app(args.get("name", ""))
        if tool == "list_windows":
            return {"ok": True, **list_windows()}
        if tool == "focus_window":
            return focus_window(args.get("title", ""))
        if tool == "media":
            action = str(args.get("action") or "").strip().lower()
            vk = MEDIA_ACTIONS.get(action)
            if vk is None:
                return {"ok": False, "error": "不认识的媒体动作「%s」" % action,
                        "allowed": sorted(MEDIA_ACTIONS.keys())}
            send_vk(vk, extended=True)
            return {"ok": True, "action": action}
        if tool == "get_state":
            return {"ok": True, "foreground": _foreground_window(), **list_windows()}
        if tool == "screenshot":
            return screenshot()
        if tool == "click_at":
            if click_fn is None:
                return {"ok": False, "error": "本端没接鼠标控制,点不了"}
            x, y = int(args.get("x", 0)), int(args.get("y", 0))
            click_fn(x, y)
            return {"ok": True, "clicked": [x, y]}
        if tool == "list_ui":
            return _list_ui(args.get("window") or None)
        if tool == "read_screen":
            return _read_screen(args.get("window") or None)
        if tool == "click_ui":
            # 「不给 window 就先当前窗口再全桌面」**保留不动** —— 上面的守门已经保证
            # 了「当前窗口」就是本任务的操作窗口,这个默认值的起点因此是对的。
            # 别改成「只在任务窗口里找」:打开/保存这类**独立弹窗**的控件不在主窗口里,
            # 那样会把它们全挡在外面,是白丢能力。
            out = _click_ui(args.get("name", ""), args.get("window") or None, click_fn)
            # 点成了别的地方 = 现场变了,「上次输入失败」不该再拦回车。
            if isinstance(out, dict) and out.get("ok"):
                _clear_type_flag()
            return out
        if tool == "set_value":
            # ★ 和 type 是同一件事的两条路:能指名道姓就用这条(不依赖焦点),
            #   控件不支持 ValuePattern 时**如实回失败**,由上层退回 click_ui + type。
            if not UIA_OK:
                return {"ok": False, "error": "UI 自动化用不了(%s)" % UIA_ERR}
            name = str(args.get("name") or "").strip()
            text = str(args.get("text") or "")
            if not name:
                return {"ok": False, "error": "没说要写进哪个控件"}
            if not text.strip():
                return {"ok": False, "error": "没给要写入的文字(它会把框里原来的内容整个换掉,"
                                              "所以空值不给写)"}
            if len(text) > TYPE_MAX_LEN:
                return {"ok": False, "error": "文字太长(上限 %d 字)" % TYPE_MAX_LEN}
            out, _raw = uia.set_value(name, text, args.get("window") or None)
            # 写进去了 = 现场变了,「上次 type 失败」不该再拦回车(和 click_ui 同一个理由)。
            if isinstance(out, dict) and out.get("ok"):
                _clear_type_flag()
            return out
        if tool == "scroll":
            return _scroll(args.get("direction"), args.get("amount"), scroll_fn)
        if tool == "search":
            return _search(args.get("query"), type_fn)
        if tool == "hotkey":
            keys = str(args.get("keys") or "").strip()
            # ★ 字都没打进去,这一下回车不放行。理由见 [_last_type] 上面那段 ——
            # 往空的输入框按回车什么也发不出去,而在别的界面里回车可能是「确认」。
            # 120 秒的窗口够长(一次任务里 type 到 enter 通常几秒),又不会把
            # 上一条任务的失败赖到这一条头上。
            if "enter" in keys.lower() and _last_type["ok"] is False \
                    and (time.time() - _last_type["at"]) < 120:
                return {"ok": False, "blocked_by": "type_failed",
                        "error": "上一次 type 没把字打进去(屏幕上没变化),所以这次回车"
                                 "被拦下了 —— 空着的地方按回车发不出任何东西,"
                                 "还可能触发别的按钮。**现在什么都没发出去。**"
                                 "先用 click_ui 点中要输入的那个框,再 type,再回车。"}
            # ★ 回车/提交键要验货(2026-10-03)。别的组合键(ctrl+f 之类)**不验**:
            # 它们的反馈本来就可能是「什么都没变」,拿屏幕变化去判是冤枉。
            # 回车特殊,是因为它在绝大多数界面里等于「提交」—— 而「提交没提交」
            # 恰恰是屏幕上看得出来的事。理由见 [_enter_verified]。
            if "enter" in keys.lower():
                return _enter_verified(keys)
            ok, err = _press_hotkey(args.get("keys", ""))
            if not ok:
                return {"ok": False, "error": err, "allowed": allowed_keys()}
            return {"ok": True, "pressed": args.get("keys", "").strip()}
        if tool == "type":
            text = str(args.get("text") or "")
            if not text.strip():
                return {"ok": False, "error": "没给要输入的文字"}
            if len(text) > TYPE_MAX_LEN:
                return {"ok": False, "error": "文字太长(上限 %d 字)" % TYPE_MAX_LEN}
            if type_fn is None:
                return {"ok": False, "error": "本端没接键盘输入"}
            out = _type_verified(text, type_fn)
            _remember_type(out)
            return out
    except Exception as e:
        # ★ type 半路崩了 = 字**确定没进去**(而且可能只打进去一半),更该上闸。
        # 不补这一刀的话,崩掉的那次 type 会留下一个「上一次没说失败」的空档,
        # 紧接着的回车照样按下去 —— 那就是拿一个半截的输入框当空的按回车。
        if tool == "type":
            _remember_type({"ok": False})
        return {"ok": False, "error": "%s 执行出错: %s" % (tool, e)}

    return {"ok": False, "error": "未知工具「%s」" % tool}
