# -*- coding: utf-8 -*-
"""click_ui 到底会不会去点**点不了的东西** —— 以及报出来的数字是不是真的。

这个文件盯的是 2026-10-03 实测挖出来的一串毛病,它们有个共同点:
**都不报错、都不崩,只是安静地把事做错。**

    现象:VS Code 当前台时,`find("微信")` / `find("发送")` / `find("文件传输助手")`
          三个不同的名字返回的是**同一个东西** —— 编辑器正文里一段滚出视野的
          `Text` 节点(真实矩形 `333,-10395 33x17`),而它的 cx/cy 按约定是 0。

    三个独立的错,叠在一起:

    ① `find` 是「找个东西让我点」的查询,却会把**点不了的类型**带回来。
       `Text` 是非可点类型,离屏又让它没有坐标,而且它没有 Invoke 可发 ——
       收回来一条路都走不通。→ 修在 uia.ps1 的 New-El(离屏 + 非可点 = 丢)。

    ② 回包里的 `count` 是假的。`Find-Hits` 只命中 1 个时,PowerShell 把返回的
       数组**展开**成了裸 hashtable,而 hashtable 的 `.Count` 是**键数** ——
       元素字典正好 11 个键,于是真实命中 1 个却报 `count: 11`。
       → 修在 Find-Scoped 的消费端加 `@(...)`。

    ③ `_click_ui` 拿 `els[0]` 就点。命中里只要混进一个 0×0 的元素排在前面,
       鼠标就飞到 (0,0) 去了。→ 修在 ai_tools._clickable:只点屏幕内、有面积的。

前两条靠**真的去问 UIA**来验(打桩验不了,过滤发生在 PowerShell 那一侧);
第③条靠**打桩**来验 —— 因为要构造「0×0 的假命中排在真按钮前面」这种局面,
在真桌面上撞不出来,只能自己造。

点击一律用记录器代替真点:这是用户正在用的桌面,不该被测试乱点。
"""
import sys

sys.path.insert(0, r"C:\Users\27014\phone-touchpad\pc-server")
sys.stdout.reconfigure(encoding="utf-8", errors="replace")
import ai_tools
import uia

# 只有这些类型是「可点的」。和 uia.ps1 里的 $ACTIONABLE 是同一张表 ——
# 两边必须一致,不一致的话这个测试会替真正的 bug 打掩护。
ACTIONABLE = {
    "Button", "MenuItem", "Edit", "ComboBox", "ListItem",
    "TabItem", "CheckBox", "RadioButton", "Hyperlink", "TreeItem",
    "SplitButton", "Menu", "Slider",
}

clicked = []


def fake_click(x, y):
    clicked.append((x, y))


fails = []
voids = []


def check(cond, label, detail=""):
    print("   %s %s%s" % ("✓" if cond else "✗", label, "" if cond else "   ← " + str(detail)))
    if not cond:
        fails.append(label)


def note_void(label):
    print("   ⚠ %s" % label)
    voids.append(label)


# ---------------------------------------------------------------------------
print("=== 1) find 带回来的离屏元素,必须**都是可点类型** ===")
#
# 判据就是这一条:一个元素如果离屏,它就只能靠"让控件自己动作"来用(见 uia.invoke),
# 而那要求它是可点类型。非可点 + 离屏 = 既没坐标可点、又没命令可发,是纯噪声。
#
# ⚠️ 这一节**可能是空过**:屏幕上的字变了,就撞不到这种情况了。所以下面的
#    「2) 阳性对照」不是可选项 —— 它是用来证明过滤没有变成"一律丢掉"的。
NAME_PROBES = ["文件传输助手", "发送", "微信", "关闭", "确定", "复制"]
bad_off = []
seen_off = 0
for nm in NAME_PROBES:
    els, raw = uia.find(nm, None, None, 50)   # limit 给大,免得截断掩盖真数量
    for e in els:
        if e.get("offscreen"):
            seen_off += 1
            if e.get("type") not in ACTIONABLE:
                bad_off.append("%s（%s）" % (e["name"][:20], e.get("type")))
    print("   find(%-14s) 命中 %d 个（回包 count=%s）"
          % ("「%s」" % nm, len(els), raw.get("count")))
check(not bad_off, "没有「离屏 + 不可点」的元素混进来", bad_off)
print("   （本轮共见到 %d 个离屏元素,全部是可点类型——若为 0 说明本轮没撞上这种情况）" % seen_off)
if seen_off == 0:
    note_void("本轮一个离屏元素都没见到,第 1 节空过了")

# ---------------------------------------------------------------------------
print()
print("=== 2) 阳性对照:可点的离屏控件**仍然要查得到** ===")
#
# 没有这一节,把过滤写成 `return $null`(一律丢掉)也能让第 1 节全绿 ——
# 而那正是这个项目一直在打的病根:「看不见」被当成了「没有」。
# 实测 VS Code 这个窗口有大量离屏但支持 Invoke 的 Button。
ctrl, craw = uia.find("Copy response to clipboard", window="Visual Studio Code", limit=50)
n_off_btn = sum(1 for e in ctrl if e.get("offscreen") and e.get("type") in ACTIONABLE)
print("   VS Code 里查到 %d 个,其中「离屏但可点」的有 %d 个" % (len(ctrl), n_off_btn))
if not craw.get("ok"):
    note_void("查不到 VS Code 窗口,阳性对照做不了:%s" % craw.get("error"))
elif n_off_btn == 0:
    note_void("本轮没查到离屏按钮(窗口状态变了),阳性对照空过")
else:
    check(True, "离屏的可点控件没有被一起过滤掉")
    print("     ↑ 这就是「知道优先、看见兜底」:知道它在,只是没画出来。")

# ---------------------------------------------------------------------------
print()
print("=== 3) count 必须是真的命中数,不是那个字典的键数 ===")
#
# 元素字典正好有 11 个键(name/type/rank/offscreen/x/y/w/h/cx/cy/_el),
# 所以「命中 1 个却报 11」这个 bug 长得非常像一个合理的数字 —— 它不刺眼,
# 会被一路当成"同名的还有 11 个"喂给经验库和云端老师。
#
# limit 给到 50,保证没有截断:这时 count 必须**恰好等于**拿回来的个数。
for nm in NAME_PROBES:
    els, raw = uia.find(nm, None, None, 50)
    c = raw.get("count")
    ok = (c == len(els))
    print("   find(%-14s) count=%-4s 实际 %d 个  %s"
          % ("「%s」" % nm, c, len(els), "✓" if ok else "✗ 对不上"))
    if not ok:
        fails.append("count 对不上:%s 报 %s,实际 %d" % (nm, c, len(els)))
check(not [f for f in fails if "count 对不上" in f],
      "count 和实际个数一致（一个都不许差）",
      [f for f in fails if "count 对不上" in f])

# ---------------------------------------------------------------------------
print()
print("=== 4) ★ 红线:_click_ui 绝不点「点不了的」 ===")
#
# 这一节必须打桩。要在真桌面上撞出「一个 0×0 的假命中排在真按钮前面」,
# 得先让某个窗口的正文里恰好出现那串字 —— 那种局面不可复现,测不了。
# 而"点不了的东西"分两种,两种都得拦住:
#     离屏   —— 坐标是虚拟滚动空间里的天文数字(这里按约定给 0)
#     零面积 —— 在屏幕上,但占不到一个像素(同样是 0×0,同样是点到左上角)
real_uia = ai_tools.uia


class Stub:
    """只回我们喂给它的东西,别的都不碰。"""

    def __init__(self, find_els, ocr_els=None, total=0):
        self.find_els = find_els
        self.ocr_els = ocr_els or []
        self.total = total

    def find(self, name, window=None, ctype=None, limit=5):
        return list(self.find_els), {"ok": True, "window": "桩窗口", "desk": False,
                                     "count": len(self.find_els)}

    def ocr_find(self, name, window=None, limit=5):
        return list(self.ocr_els), {"ok": True, "window": "桩窗口", "total": self.total,
                                    "count": len(self.ocr_els)}

    def invoke(self, name, window=None):
        return {"ok": False, "error": "桩:不接受命令"}, {}

    def list_elements(self, window=None, limit=40):
        return [], {"ok": True, "window": "桩窗口", "count": 0}


def el(name, ctype, cx, cy, w, h, off=False):
    return {"name": name, "type": ctype, "offscreen": off, "rank": 0,
            "x": cx, "y": cy, "w": w, "h": h, "cx": cx, "cy": cy}


def run_stub(stub, name="某个按钮", window=None):
    before = len(clicked)
    ai_tools.uia = stub
    try:
        r = ai_tools.execute({"tool": "click_ui", "args": dict(name=name)},
                             click_fn=fake_click)
    finally:
        ai_tools.uia = real_uia
    return r, clicked[before:]


CASES = [
    # (说明, 桩, 该点吗, 期望坐标)
    ("只有离屏 Text → 不许点", Stub([el("某", "Text", 0, 0, 0, 0, off=True)]), False, None),
    ("只有零面积按钮 → 不许点", Stub([el("某", "Button", 0, 0, 0, 0)]), False, None),
    ("★ 零面积假命中排在真按钮**前面** → 必须点真按钮",
     Stub([el("某", "Text", 0, 0, 0, 0), el("某", "Button", 300, 400, 60, 30)]),
     True, (300, 400)),
    ("★ 离屏假命中排在真按钮前面 → 必须点真按钮",
     Stub([el("某", "Button", 0, 0, 0, 0, off=True), el("某", "Button", 512, 256, 60, 30)]),
     True, (512, 256)),
    ("全是点不了的、但 OCR 在屏幕上找到了 → 点 OCR 那个",
     Stub([el("某", "Text", 0, 0, 0, 0, off=True)], [el("某", "Text", 777, 888, 40, 20)], 30),
     True, (777, 888)),
    ("阳性对照:一个正常按钮 → 必须点",
     Stub([el("某", "Button", 100, 200, 60, 30)]), True, (100, 200)),
]

for label, stub, should_click, want in CASES:
    r, got = run_stub(stub)
    did = len(got) > 0
    ok = (did == should_click) and (not should_click or got[0] == want)
    print("   %s %s" % ("✓" if ok else "✗", label))
    print("       ok=%-5s 点了=%s  回的是「%s」"
          % (r.get("ok"), got, (r.get("error") or r.get("via") or "")[:52]))
    if not ok:
        fails.append(label)

# ---------------------------------------------------------------------------
print()
print("=== 总账 ===")
print("   这一轮一共点过 %d 次:%s" % (len(clicked), clicked))
outside = [(x, y) for x, y in clicked if not (0 <= x <= 1920 and 0 <= y <= 1080)]
check(not outside, "没有一次点落在屏幕外", outside)
# 点数应该**正好**等于上面标了"该点"的用例数 —— 多一次就说明有地方偷偷点了。
want_n = sum(1 for _, _, s, _ in CASES if s)
check(len(clicked) == want_n, "点击次数正好等于「该点」的用例数", "%d vs %d" % (len(clicked), want_n))

print()
if fails:
    print("   **失败 %d 项**: %s" % (len(fails), fails))
elif voids:
    print("   通过,但有 %d 项**空过**(不是通过,是没验成): %s" % (len(voids), voids))
else:
    print("   全部通过 ✓")
sys.exit(1 if fails else (2 if voids else 0))
