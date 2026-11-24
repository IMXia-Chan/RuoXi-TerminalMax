# -*- coding: utf-8 -*-
"""open_app 的兜底链 + 浏览器里的 search。

三个洞都是 2026-10-03 用户说「试试用 edge 浏览器搜东西,不然我怕只能在微信能用」
之后撞出来的 —— 也就是说**它们一直躺在那儿,只是微信刚好绕开了**:

  1. apps.json 里 `"edge"` 指着的快捷方式在这台机器上**根本不存在**,而别名是第一级
     兜底 —— 它一抛异常就当场返回失败,于是开始菜单 / App Paths / PATH / Shell
     四级**一级都没轮到**。用户看到「找不到 Edge」,而 Edge 装得好好的。
     同形 bug 在本项目反复出现:**一个坏来源把其余全部静默挡死**。

  2. `search` 的实现是 Ctrl+F。微信里 Ctrl+F = 搜会话,所以那写法在那儿是对的;
     **浏览器里 Ctrl+F 是「本页内查找」** —— 实测在 Edge 里按下它,UIA 树新冒出来的
     控件叫「在页面上查找 / 上一个结果 / 下一个结果」。于是「用 Edge 搜 XXX」会
     **一路回成功**地把词打进本页查找框,然后什么也没搜到。
     这是「只在一个软件上能用」最典型的来源:**把一个应用里成立的写法当成了通用写法**。

  3. 修完 2 之后,第一版把「回车提交」拆给模型做(search 只写词、不回车)——
     真机跑下来**每一步都回成功、页面一次都没跳**。查出来:在 Edge 地址栏打完字、
     自动补全下拉框弹出来时,**第一下回车不导航,第二下才跳**(实测 9 轮无例外)。
     而 `hotkey(enter)` 那边测不出这件事 —— 它按完看屏幕,屏幕确实动了
     (下拉框收起来了),于是老实回 ok=True。
     根子是**动词切错了**:浏览器里「字进了地址栏」和「搜出去」是同一个动作的两半。
     (微信那边不同 —— 回车在那儿是「打开第一个结果」,是个真该由模型拍板的导航
     决定,所以 [_search] 至今仍然不按回车。**同一个 search,两处语义不同。**)

跑:`python test_open_app_and_browser_search.py`
"""
import io
import os
import sys
import time

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import ai_tools


# --------------------------------------------------------------------------
# 1. 烂别名不许挡死后面四级
# --------------------------------------------------------------------------
def test_a_broken_alias_does_not_shadow_the_fallbacks():
    """别名指向的路径没了 → 必须继续往下走,靠索引里的 msedge 把它捞回来。"""
    old_alias, old_index, old_launch = (
        ai_tools._apps_aliases, ai_tools._app_index, ai_tools._launch)
    calls = []

    def fake_launch(target, *cands):
        calls.append(target)
        if "Missing.lnk" in target:
            raise FileNotFoundError(2, "系统找不到指定的文件。", target)
        return {"target": target, "ready": True, "waited_s": 0.5, "window": "Edge"}

    ai_tools._apps_aliases = lambda: {"edge": r"C:\nowhere\Missing.lnk"}
    ai_tools._app_index = lambda: [
        ("msedge", r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"),
        ("记事本", "notepad.exe"),
    ]
    ai_tools._launch = fake_launch
    try:
        out = ai_tools.execute({"tool": "open_app", "args": {"name": "Edge"}})
    finally:
        ai_tools._apps_aliases, ai_tools._app_index, ai_tools._launch = (
            old_alias, old_index, old_launch)

    assert out["ok"] is True, \
        "坏别名把后面几级全挡死了 —— 这正是 Edge 那个 bug: %r" % out
    assert out["launched"] == "msedge", out
    assert len(calls) == 2, "应该先试别名(失败)再试索引,实际试了 %r" % calls
    print("  1 烂别名不再挡死兜底链(别名失败→索引接住)✓")


def test_all_four_paths_reported_when_everything_fails():
    """四条路全试过还不行 → 把**每条路为什么不行**如实报出来,方便一次定位。"""
    old_alias, old_index, old_launch, old_which = (
        ai_tools._apps_aliases, ai_tools._app_index, ai_tools._launch, ai_tools.shutil.which)

    def boom(target, *cands):
        raise FileNotFoundError(2, "找不到", target)

    ai_tools._apps_aliases = lambda: {"edge": r"C:\nowhere\Missing.lnk"}
    ai_tools._app_index = lambda: [("msedge", r"C:\Program Files\msedge.exe")]
    ai_tools._launch = boom
    # 给个假路径,好让 PATH 那一级也真的被走到(它只在 which 有结果时才试)
    ai_tools.shutil.which = lambda n: r"C:\fake\edge.exe"
    try:
        out = ai_tools.execute({"tool": "open_app", "args": {"name": "Edge"}})
    finally:
        (ai_tools._apps_aliases, ai_tools._app_index, ai_tools._launch,
         ai_tools.shutil.which) = (old_alias, old_index, old_launch, old_which)

    assert out["ok"] is False, out
    tried = out.get("tried") or []
    assert len(tried) == 4, "四条路要一条不落地报出来,实际只有 %d 条:%r" % (len(tried), tried)
    assert any("apps.json" in t for t in tried), "坏别名那条要能一眼看出来"
    print("  2 全失败时把四条路的失败原因都报出来 ✓")


def test_app_paths_are_real_and_include_the_browsers():
    """真去读一次注册表:App Paths 是这份索引里**唯一**能覆盖浏览器的来源。"""
    rows = ai_tools._app_paths()
    if not rows:
        print("  3 (跳过:本机 App Paths 读不到东西)")
        return
    for _n, p in rows:
        assert os.path.isfile(p), "App Paths 里混进了不存在的文件:%s" % p
    print("  3 App Paths 读通(%d 条,且条条文件真实存在)✓" % len(rows))


# --------------------------------------------------------------------------
# 2. 浏览器里的 search 要走地址栏,而且**要自己搜出去**
# --------------------------------------------------------------------------
T_EDGE = "新标签页 - 个人 - Microsoft​ Edge"


def _search_setup(bar_hit, nav=None, fg_seq=None):
    """搭一个能观察「按了哪个键」的现场。返回 (记录表, 还原函数)。

    nav    —— `_wait_navigated` 每次调用依次返回什么;默认第一次就「跳了」。
    fg_seq —— `_fg()` 依次返回什么 (hwnd, 标题);默认恒定(前台一直没变过)。
    """
    seen = {"keys": [], "typed": []}
    old = (ai_tools._press_hotkey, ai_tools._address_bar, ai_tools._type_verified,
           ai_tools._task.get("title"), ai_tools._fg, ai_tools._wait_navigated)
    ai_tools._press_hotkey = lambda k: (seen["keys"].append(k), (True, ""))[1]

    def fake_type(text, type_fn):
        seen["typed"].append(text)
        return {"ok": True, "typed": text, "verified": "screen"}

    queue = {"nav": list(nav) if nav is not None else [("jumped", "claude - 搜索")] * 9,
             "fg": list(fg_seq) if fg_seq else None}

    def fake_fg():
        if queue["fg"]:
            return queue["fg"].pop(0)
        return (1234, T_EDGE)

    def fake_wait(hwnd0, title0, sec):
        return queue["nav"].pop(0)

    ai_tools._type_verified = fake_type
    ai_tools._address_bar = lambda w: bar_hit
    ai_tools._fg = fake_fg
    ai_tools._wait_navigated = fake_wait
    ai_tools._task["title"] = T_EDGE

    def restore():
        (ai_tools._press_hotkey, ai_tools._address_bar, ai_tools._type_verified,
         title, ai_tools._fg, ai_tools._wait_navigated) = old
        ai_tools._task["title"] = title

    return seen, restore


def _run_search(query="claude", **kw):
    seen, restore = _search_setup({"name": "地址和搜索栏", "type": "Edit"}, **kw)
    try:
        return ai_tools.execute({"tool": "search", "args": {"query": query}},
                                type_fn=lambda t: None), seen
    finally:
        restore()


def test_browser_search_uses_the_address_bar_not_ctrl_f():
    """★ 浏览器里必须走 Ctrl+L(地址栏),**绝不能**按 Ctrl+F —— 那是「本页内查找」。"""
    out, seen = _run_search()
    assert out["ok"] is True, out
    assert "ctrl+f" not in seen["keys"], \
        "在浏览器里按了 Ctrl+F = 本页内查找,搜不到网页: %r" % seen["keys"]
    assert "ctrl+l" in seen["keys"], "应该用 Ctrl+L 聚焦地址栏: %r" % seen["keys"]
    assert seen["typed"] == ["claude"], seen
    assert "地址栏" in out.get("via", ""), out
    print("  4 浏览器里 search → Ctrl+L 地址栏(不是 Ctrl+F)✓")


def test_browser_search_reports_it_actually_searched():
    """★ 浏览器这边 search **自己会按回车搜出去** —— 回执必须这么说,别再说「还没搜」。"""
    out, seen = _run_search()
    assert "enter" in seen["keys"], \
        "浏览器里 search 得自己把车搜出去(第一下会被下拉框吃掉,见下一条): %r" % seen["keys"]
    assert out["verified"] == "title", out
    assert out.get("page") == "claude - 搜索", out
    nxt = out.get("next") or ""
    assert "已经搜出去" in nxt, "要明说搜过了: %r" % nxt
    assert "不用再按回车" in nxt, "要拦住模型多按的那一下回车: %r" % nxt
    assert "click_ui" in nxt, "要说清结果在网页上、得点: %r" % nxt
    print("  5 浏览器 search 自己搜出去,且明说「不用再按回车」✓")


def test_second_enter_when_the_first_is_eaten_by_autocomplete():
    """★★ 真机规律:地址栏打完字后**第一下回车不导航,第二下才跳**(实测 9 轮无例外)。

    这一条是整个修复的核心。第一版把回车留给模型按,于是「每步都成功、页面没动」。
    """
    out, seen = _run_search(nav=[("still", None), ("jumped", "claude - 搜索")])
    enters = [k for k in seen["keys"] if k == "enter"]
    assert len(enters) == 2, "第一下被吃了就得补第二下,实际按了 %d 下" % len(enters)
    assert out["ok"] is True, "补了第二下就该搜出去了: %r" % out
    assert out.get("enter_tries") == 2, out
    assert out.get("page") == "claude - 搜索", out
    print("  5b 第一下回车被自动补全吃掉 → 自动补第二下 ✓")


def test_never_claims_success_when_the_page_did_not_move():
    """★★ 两下都没让页面动 → **必须如实说没搜出去**,不许再演一遍「每步都成功」。"""
    out, seen = _run_search(nav=[("still", None), ("still", None)])
    assert out["ok"] is False, "页面没动却报了成功,正是这次要修的 bug: %r" % out
    assert out.get("blocked_by") == "search_not_submitted", out
    assert "没搜出去" in (out.get("error") or ""), out
    # 也不许改口说「词在地址栏里所以你看着办」—— 它就是没成
    assert "searched" not in out, out
    print("  5c 页面没跳就如实回失败(不再谎报成功)✓")


def test_never_presses_enter_into_a_window_that_stole_the_focus():
    """★★ 前台被抢走时**绝不能**再按回车 —— 在别的应用里那可能就是「发送」/「确认删除」。

    补第二下回车是为了修 Edge,但一个不问青红皂白的重试会把项目的老规矩踩烂。
    """
    out, seen = _run_search(
        nav=[("still", None)],
        fg_seq=[(1234, T_EDGE), (1234, T_EDGE), (9999, "别的窗口")])
    enters = [k for k in seen["keys"] if k == "enter"]
    assert len(enters) == 1, \
        "前台已经不是那个浏览器了,还按了 %d 下回车 = 打在别人窗口上" % len(enters)
    assert out["ok"] is False, out
    assert out.get("blocked_by") == "lost_focus", out
    print("  5d 前台被抢走时绝不补第二次回车(不往别人窗口里敲)✓")


def test_wait_navigated_watches_the_handle_not_only_the_title():
    """判据要**句柄 + 标题一起看**:换了窗口导致的「标题变了」不是导航成功。"""
    old_fg = ai_tools._fg

    def seq_fn(pairs):
        st = {"i": 0}

        def f():
            r = pairs[min(st["i"], len(pairs) - 1)]
            st["i"] += 1
            return r
        return f

    try:
        ai_tools._fg = seq_fn([(1234, "旧标题"), (1234, "新标题")])
        assert ai_tools._wait_navigated(1234, "旧标题", 0.5) == ("jumped", "新标题")

        ai_tools._fg = seq_fn([(1234, "旧标题"), (9999, "别处的标题")])
        state, title = ai_tools._wait_navigated(1234, "旧标题", 0.5)
        assert state == "left", "前台换人了不算导航成功: %r" % ((state, title),)

        ai_tools._fg = seq_fn([(1234, "旧标题")])
        state, _ = ai_tools._wait_navigated(1234, "旧标题", 0.35)
        assert state == "still", state
    finally:
        ai_tools._fg = old_fg
    print("  5e 导航判据 = 句柄没变 且 标题变了 ✓")


def test_non_browser_search_still_uses_ctrl_f():
    """普通应用(微信那种)不能被这次改动带偏 —— 它的搜索键就是 Ctrl+F。"""
    seen, restore = _search_setup(None)
    try:
        out = ai_tools.execute({"tool": "search", "args": {"query": "文件传输助手"}},
                               type_fn=lambda t: None)
    finally:
        restore()
    assert out["ok"] is True, out
    assert "ctrl+f" in seen["keys"], "普通应用里应该还是 Ctrl+F: %r" % seen["keys"]
    assert "ctrl+l" not in seen["keys"], "别把 Ctrl+L 撒到普通应用上: %r" % seen["keys"]
    assert "enter" not in seen["keys"], \
        "微信那边的回车是「打开第一个结果」,得留给模型自己拍板: %r" % seen["keys"]
    assert "还没有选中任何结果" in (out.get("next") or ""), out
    print("  6 非浏览器仍然走 Ctrl+F、且不替它回车(没被带偏)✓")


def test_address_bar_detection_reads_the_ui_tree():
    """地址栏的判据是**界面上有没有这个东西**,不是应用名 —— 换个浏览器也认。"""
    old_find = ai_tools.uia.find

    def fake_find(name, window=None, ctype=None, limit=5):
        hit = [{"name": "Address and search bar", "type": "Edit"}] if "address" in name.lower() else []
        return hit, {"ok": True}

    ai_tools.uia.find = fake_find
    try:
        assert ai_tools._address_bar("whatever") is not None, "英文版地址栏没认出来"
        ai_tools.uia.find = lambda *a, **k: ([], {"ok": False})
        assert ai_tools._address_bar("whatever") is None, "没有地址栏时不该硬说有"
    finally:
        ai_tools.uia.find = old_find
    print("  7 地址栏靠界面判据识别(换了浏览器也认)✓")


def test_ctrl_l_is_in_the_hotkey_whitelist():
    """Ctrl+L 以前不在白名单里 —— 而浏览器里没有别的键能替代它。

    别去真按:这里只核对白名单表(真按会在当前焦点窗口上按下去,而单测不该动用户的
    屏幕)。真正的按键路径由 `_press_hotkey` 自己那套已有测试覆盖。
    """
    assert "l" in ai_tools.SAFE_KEYS, "Ctrl+L 不在白名单,浏览器的搜索就永远是断的"
    assert ai_tools.SAFE_KEYS["l"] == 0x4C, "VK 码写错了"
    print("  8 Ctrl+L 已进白名单 ✓")


if __name__ == "__main__":
    tests = [v for k, v in sorted(globals().items()) if k.startswith("test_")]
    print("open_app 兜底链 / 浏览器 search —— 共 %d 条" % len(tests))
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
