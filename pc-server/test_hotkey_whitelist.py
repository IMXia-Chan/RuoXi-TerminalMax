# -*- coding: utf-8 -*-
"""按键白名单(`SAFE_KEYS` + `MODIFIER_KEYS`)—— 2026-10-08 起 alt+f4 在里面。

为什么单开一个文件、而不是塞进 `test_open_app_and_browser_search.py`:
那条线的主题是「浏览器里的 search 是另一条路」,这条的主题是**安全边界本身**
(谁能按键盘、能按出什么)。混在一起,以后有人想收紧/放松这条边界时,
会在一堆浏览器用例里找不到它。

## 背景(2026-10-08)

用户报:「关微信的时候,我让她关微信她就在搜索框中关微信」。
根因不是判据写错,是**工具箱里压根没有「关窗口」这个动作** ——
模型自己想到按 Alt+F4,而电脑那边回「一次只能按一个主键」,于是它只能去搜,
把「关闭」两个字打进了微信自己的搜索框(详见 `AiAgent.resolveMiss` 那段)。

问他要不要给这个能力,他选的是「**给,直接关不问**」(不给确认框,点一下直接关)。

★ 当天先做过一版「整条写死的组合表」(`HOTKEY_COMBOS`),他看完说:
**「组合键不管它,让它加白名单」** —— 所以现在是这一版:
`alt` 进 `MODIFIER_KEYS`、`f4` 进 `SAFE_KEYS`,不另开一张表。

★★ **这个形状的直接后果,正是本文件要钉住的东西**:
放开的是**修饰键本身**,所以凡是「一个修饰键 + 白名单里的主键」都合法 ——
alt+f4 是,`alt+tab` 也是。**这不是意外,是他选的形状。**
所以测试**不去列一张「允许哪些组合」的死名单**(那会随白名单变化而过时),
而是钉住**那条规则**:主键在白名单里 + 至多一个修饰键 ⇒ 放行;否则拒。
`alt+tab` 那一条单独留着 —— 它是这个取舍最容易被看见的一个例子。

★★ 但同一天他**又收了一次**:**「组合键是可以用,但是危险的组合键不能」**。
所以现在是**两张表一起管**:
  - 白名单(`SAFE_KEYS` / `MODIFIER_KEYS`)—— 这个键许不许用;
  - 危险表(`DANGEROUS_COMBOS`)—— 这个组合会不会毁掉他手上的东西。
★ 本文件两条都要钉,而且**两条都要正反两面**:光钉「alt+f4 放行」,
一个「什么都不拦」的实现全绿;光钉「ctrl+f4 被拦」,
一个「什么都不许按」的实现也全绿。

## 两个方向都要钉(本项目铁律)

坏掉的判据和准的判据,在没有正控的测试里长得一模一样。所以这里
**既钉「alt+f4 真的放行」,也钉「白名单外的键真的不放行」** ——
只测前者的话,一个「什么都不拦、全放开」的实现也能全绿。

★ 全部用例都 mock 掉 `send_hotkey` / `send_vk`:单测**不许动用户的屏幕**。
  (同 `test_open_app_and_browser_search.py` 里那条的精神 —— 那里连白名单都只核对表。)

跑:`python test_hotkey_whitelist.py`
"""
import io
import os
import sys

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import ai_tools


# --------------------------------------------------------------------------
# 仪器:把「真的按了什么」录下来,而不是真按
# --------------------------------------------------------------------------
class Recorder(object):
    """替掉两个真实发送函数,把 (vk, modifiers) 记下来。"""

    def __init__(self):
        self.combos = []    # send_hotkey(vk, mods)
        self.singles = []   # send_vk(vk)

    def install(self):
        self._old_hk = ai_tools.send_hotkey
        self._old_vk = ai_tools.send_vk
        ai_tools.send_hotkey = lambda vk, mods=(): self.combos.append((vk, tuple(mods)))
        ai_tools.send_vk = lambda vk: self.singles.append(vk)

    def restore(self):
        ai_tools.send_hotkey = self._old_hk
        ai_tools.send_vk = self._old_vk

    @property
    def nothing_pressed(self):
        return not self.combos and not self.singles


def press(keys):
    """按一下,回 (ok, err, 记录)。全部 mock,不碰屏幕。"""
    rec = Recorder()
    rec.install()
    try:
        ok, err = ai_tools._press_hotkey(keys)
    finally:
        rec.restore()
    return ok, err, rec


# --------------------------------------------------------------------------
# 正控:该放行的必须放行 —— 否则下面每一条「拒绝」都可能是「它什么都不认」
# --------------------------------------------------------------------------
def test_alt_f4_is_allowed():
    """用户点名要的那一个 —— 它要是不过,这个 bug 就没修。"""
    ok, err, rec = press("alt+f4")
    assert ok is True, "alt+f4 被拒了: %r" % err
    assert rec.combos == [(ai_tools.VK_F4, (ai_tools.VK_MENU,))], \
        "按下去的不是 Alt+F4,而是 %r" % rec.combos
    assert rec.singles == [], "它走成了单键路径"
    print("  1 alt+f4 放行,且真的发的是 Alt+F4 ✓")


def test_alt_f4_is_order_and_case_insensitive():
    """拆成 parts 再分修饰键/主键,所以写在前写在后、大写小写都该认。"""
    for raw in ("f4+alt", "F4+ALT", "Alt + F4", "  alt+f4  "):
        ok, err, rec = press(raw)
        assert ok is True, "「%s」没认出来: %r" % (raw, err)
        assert rec.combos == [(ai_tools.VK_F4, (ai_tools.VK_MENU,))], \
            "「%s」发出来的是 %r" % (raw, rec.combos)
    print("  2 顺序 / 大小写 / 空格都不影响 ✓")


def test_vk_codes_are_right():
    """两个数是这件事里唯一「写错了不报错」的地方 —— 钉住它们。

    0x12 是 Alt,写成 0x5B(Win 键)的话,alt+f4 会变成 Win+F4,一样不报错;
    0x73 写成 0x72 就是 F3(打开查找),窗口照样不关,也照样不报错。
    """
    assert ai_tools.VK_MENU == 0x12, "VK_MENU 不是 Alt 了"
    assert ai_tools.VK_F4 == 0x73, "VK_F4 写错了"
    assert ai_tools.SAFE_KEYS["f4"] == 0x73, "白名单里的 f4 指到了别的键"
    print("  3 VK 码是对的(Alt=0x12 / F4=0x73)✓")


# --------------------------------------------------------------------------
# ★★ 反方向 —— 这一半才是重点:白名单是唯一入口
# --------------------------------------------------------------------------
def test_whitelist_is_the_only_way_in():
    """**规则本身**,而不是一张死名单:

      主键在白名单里 + 至多一个修饰键 ⇒ 放行;主键不在 ⇒ 拒。

    ★ 这条覆盖三种形状(光按、ctrl+、alt+),而且**白名单以后加一个键,
      它自动跟着覆盖** —— 不会像死名单那样过期。
    ★ 危险表里那几条**跳过**(同一张白名单里的键拼出来的,由下一条单独钉),
      判据直接问 `DANGEROUS_COMBOS`,不另抄一份 —— 抄一份就会过期。
    """
    skipped = 0
    for name, vk in sorted(ai_tools.SAFE_KEYS.items()):
        ok, err, rec = press(name)
        assert ok is True and rec.singles == [vk], \
            "光按「%s」该放行: ok=%r err=%r 按了 %r" % (name, ok, err, rec.singles)

        for mod, vk_mod in (("ctrl", ai_tools.VK_CONTROL), ("alt", ai_tools.VK_MENU)):
            if ai_tools._combo_key([mod], [name]) in ai_tools.DANGEROUS_COMBOS:
                skipped += 1
                continue
            ok, err, rec = press(mod + "+" + name)
            assert ok is True and rec.combos == [(vk, (vk_mod,))], \
                "%s+「%s」该放行: ok=%r err=%r 按了 %r" % (mod, name, ok, err, rec.combos)
    print("  4 白名单里 %d 个键,光按 / ctrl+ / alt+ 全放行(跳过危险表里那 %d 条)✓"
          % (len(ai_tools.SAFE_KEYS), skipped))


def test_dangerous_combos_are_blocked():
    """★★ 用户第二次收紧的那一条:**「组合键是可以用,但是危险的组合键不能」**。

    这些组合**每一个字都是白名单里的**(f4 / delete / backspace + ctrl / alt)——
    所以光靠白名单**拦不住它们**。这正是这张表存在的全部理由。

    判据两条,缺一不可:
      ① 被拒(ok=False),而且回执**点名是哪个组合**、并给出正确做法;
      ② ★ **一个键都不许按下去** —— 这是本项目「凭什么算拒绝」的唯一硬证据
         (同 `test_click_ui.py:95`「被拦的那一下一次都没点」)。
    """
    assert len(ai_tools.DANGEROUS_COMBOS) >= 5, "危险表被掏空了"
    # 表里每一条都真的是「白名单里的键拼出来的」—— 否则它根本轮不到这张表管
    for combo in ai_tools.DANGEROUS_COMBOS:
        parts = combo.split("+")
        mods, mains = parts[:-1], parts[-1:]
        assert all(m in ai_tools.MODIFIER_KEYS for m in mods), combo
        assert mains[0] in ai_tools.SAFE_KEYS, \
            "「%s」的主键不在白名单里 —— 它本该被白名单那条拦住,收进这张表是多余的" % combo

    for raw in ("ctrl+f4", "ctrl+delete", "alt+delete",
                "ctrl+backspace", "alt+backspace"):
        for written in (raw, raw.replace("ctrl", "control").upper().replace("F4", "f4"),
                        "+".join(reversed(raw.split("+")))):   # 换写法不许绕过
            ok, err, rec = press(written)
            assert ok is False, "「%s」被放行了 —— 它会毁掉他手上的东西" % written
            assert rec.nothing_pressed, "「%s」被拒了,但键**已经按下去了**" % written
            assert err and ("危险" in err or "不能按" in err), \
                "拒了但没说清为什么: %r" % err
    print("  5★ 5 条危险组合(含换序/大小写/control 写法)全拒,且一个键都没按 ✓")


def test_bare_f4_is_rejected():
    """白名单外的单键一律不放行。

    ★ 「f4 现在在白名单里了」不等于「什么键都行」—— 这两件事分开钉,
      否则一个「SAFE_KEYS 被清空、什么都放行」的实现也能全绿。
    """
    for name in ("w", "q", "f5", "f12", "insert", "printscreen", "0", ","):
        assert name not in ai_tools.SAFE_KEYS, "「%s」怎么进白名单了" % name
        ok, err, rec = press(name)
        assert ok is False, "「%s」被放行了 —— 白名单被动过" % name
        assert rec.nothing_pressed, "「%s」被拒了,但键**已经按下去了**" % name
        assert err and name in err, "拒了但没点名是哪个键: %r" % err
    print("  6 白名单外的 8 个键全拒,且一个键都没按 ✓")


def test_only_ctrl_and_alt_are_modifiers():
    """★ win / shift **不是**修饰键 —— 名字不在 `MODIFIER_KEYS` 里就当主键使。

    所以 `alt+tab` 合法(它是本形状最容易被看见的一个例子),而
    `win+r` / `shift+delete` **不合法**:前者两个主键,后者主键 shift 不在白名单。
    """
    # 这个形状的直接后果,明写在这儿 —— 谁要改它,先看见它在。
    ok, err, rec = press("alt+tab")
    assert ok is True and rec.combos == [(ai_tools.VK_TAB, (ai_tools.VK_MENU,))], \
        "alt+tab 今天是放行的(alt 是通用修饰键、tab 在白名单里): %r %r" % (err, rec.combos)

    for raw in ("win+r", "windows+r", "shift+delete", "win+f4", "shift+f4"):
        ok, err, rec = press(raw)
        assert ok is False, "「%s」被放行了 —— win / shift 不该是修饰键" % raw
        assert rec.nothing_pressed, "「%s」被拒了,但键**已经按下去了**" % raw
    assert "win" not in ai_tools.MODIFIER_KEYS and "shift" not in ai_tools.MODIFIER_KEYS
    print("  7 alt / tab 是放行的(形状如此);win / shift 不是修饰键,5 个反例全拒 ✓")


def test_two_modifiers_rejected():
    """ctrl+alt+f4 / alt+ctrl+f 这类两个修饰键的一律拒 —— 一次一个。"""
    for raw in ("ctrl+alt+f4", "alt+ctrl+f", "ctrl+ctrl+f"):
        ok, err, rec = press(raw)
        assert ok is False, "「%s」被放行了" % raw
        assert rec.nothing_pressed, "「%s」被拒了,但按下去了" % raw
    print("  8 两个修饰键的组合全拒,且一个键都没按 ✓")


def test_no_main_key_rejected():
    """只有一个修饰键、没有主键 —— 拒。"""
    for raw in ("alt", "ctrl", "control"):
        ok, err, rec = press(raw)
        assert ok is False, "光一个「%s」被放行了" % raw
        assert rec.nothing_pressed, "「%s」被拒了,但按下去了" % raw
    print("  9 光一个修饰键不放行 ✓")


# --------------------------------------------------------------------------
# 回归:原来那条路一个字都没变
# --------------------------------------------------------------------------
def test_the_old_uses_still_work():
    """改动是把 MODIFIER_KEYS 交给原来的分支 —— 老用例必须逐字不变。"""
    ok, err, rec = press("enter")
    assert ok is True and rec.singles == [ai_tools.VK_RETURN] and not rec.combos, (ok, err)

    ok, err, rec = press("ctrl+f")
    assert ok is True and rec.combos == [(0x46, (ai_tools.VK_CONTROL,))], (ok, err, rec.combos)

    ok, err, rec = press("l")     # Ctrl+L 那个老特例(单键 l 也在白名单里)
    assert ok is True and rec.singles == [0x4C], (ok, err, rec.singles)

    ok, err, rec = press("ctrl+control+s")   # 「control」是老写法,还是同一个 Ctrl
    assert ok is False and rec.nothing_pressed, "两个修饰键应该被拒"
    print(" 10 老的白名单逐字没变(enter / ctrl+f / l 放行)✓")


def test_allowed_list_matches_reality():
    """失败回执里报的那份「可以按什么」必须**真的都能按**。

    少报一个 ⇒ 模型被拒之后只会照原样重试;多报一个 ⇒ 比不报更坏。
    两个方向都钉。
    """
    keys = ai_tools.allowed_keys()
    assert "f4" in keys, "回执里没告诉模型有 f4: %r" % keys
    assert "enter" in keys and "l" in keys, "老的那些也在(白名单没被顶掉)"
    assert keys == sorted(keys), "报出去的是乱序的,模型更难照抄"
    assert sorted(keys) == sorted(ai_tools.SAFE_KEYS.keys()), \
        "回执报的和白名单不是同一份 —— 一定有一边漏了"

    for k in keys:
        ok, _, rec = press(k)
        assert ok is True and not rec.singles == [], "回执里报了「%s」,但它其实按不了" % k
    print(" 11 回执里的清单带上 f4,且报出来的每一个都真能按 ✓")


if __name__ == "__main__":
    tests = [v for k, v in sorted(globals().items()) if k.startswith("test_")]
    print("按键白名单(含 alt+f4)—— 共 %d 条" % len(tests))
    bad = 0
    for t in tests:
        try:
            t()
        except AssertionError as e:
            bad += 1
            print("  !! %s 失败: %s" % (t.__name__, e))
        except Exception as e:
            bad += 1
            print("  !! %s 出错: %r" % (t.__name__, e))
    print()
    if bad:
        print("%d/%d 条没过" % (bad, len(tests)))
        sys.exit(1)
    print("全部 %d 条通过" % len(tests))
