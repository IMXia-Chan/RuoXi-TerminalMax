# -*- coding: utf-8 -*-
"""type 的验货 + 回车闸门。

钉住的是 2026-10-03 那场翻车的**判据**,不是某段实现:
  真机现象:search/click_ui/type/hotkey 四步全回 ok=True,而微信里输入框是空的、
  一条消息都没发出去,模型却报「已成功发送」。

这个文件要保证的事,一条一条列出来:
  1. 屏幕**没变化**且 OCR 也找不到  → type 必须回 ok=False(不许谎报成功);
  2. 屏幕**有变化**                  → ok=True,且 verified="screen";
  3. 像素没变化但 OCR 找到了          → 也算成功(别冤枉「变化小」的输入);
  4. 两件仪器都给不出证据             → ok=True 但 verified="unknown" ——
     **仪器坏了不许判失败**,更不许说成「验过了」;
  5. **屏幕太闹时降级到 OCR,不是投降**(这条是第一版设计错误的地方:旧代码
     一遇噪声大就直接回 unknown,等于把唯一还管用的仪器也关了 —— 实测真机上
     「打 12 个字符只改变 202 像素,而终端一刷新基线就有 11000」,照旧代码
     这个修复在用户机器上等于没做);
  6. OCR 头一遍没认出来、第二遍认出来了 → 算成功(OCR 抓屏可能比字画出来早一步);
  7. 上一次 type 失败 → 紧接着的回车**被拦下**(空地方按回车什么都发不出去);
  8. click_ui 点成别处之后,那条失败记录不该再拦回车(现场变了);
  9. search 的搜索词打不进去时,要当场判死,不能烂到下游。

★ 2026-10-03 第二轮(用户报「打出来但是没发送」)补的,这是那条链上**最后一处
  闭着眼睛回成功**的地方:
 10. 回车按下去而屏幕**逐像素一模一样** → ok=False(消息还躺在输入框里,别谎报成功);
 10b. **但判据只能是「一个像素都没变」,不能是「小于某个门槛」** —— 实机标定:
     记事本里打字改 10790 像素,回车只改 **20** 个,而那次回车**确实发生了**。
     门槛会把真实事件判成失败 → 诱使模型重发一条已经发出去的消息。这条是钉子;
 11. 屏幕上「有反应」**不等于**「已发送」 → 措辞不许越界;
 12. 基线自己就在动 → unknown,**不许定罪**(定罪会诱使把已发出的消息再发一遍);
 13. 抓不到屏幕 → unknown,同样不许冤枉;
 14. **type 的成功回执必须明说「还没发出去、还得按回车」** —— 真机根因就在这:
     回执读起来像个完成态,4B 模型就当它收工了。这条是防回归的钉子。
"""
import io
import os
import sys

# 本机控制台默认 GBK,直接 print("✓") 会 UnicodeEncodeError 把整个测试进程带走
# (这个坑在 pythonw 那条路上已经吃过一次,见 memory: dev-machine-pythonw-firewall-rule)。
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from PIL import Image

import ai_tools


# --------------------------------------------------------------------------
# 造图当「屏幕」:全黑;右下角多一块白的(= 屏幕上多了东西);
# 还有一张「很闹」的 —— 模拟终端在刷新/放视频,屏幕自己在剧烈变化。
# --------------------------------------------------------------------------
def _blank():
    return Image.new("L", (640, 360), 0)


def _with_blob():
    im = _blank()
    for x in range(20, 140):
        for y in range(20, 60):
            im.putpixel((x, y), 255)
    return im


def _with_speck(n=20):
    """只亮 20 个像素 —— 实机里「记事本按回车、光标下移一行」就是这个量级。"""
    im = _blank()
    for x in range(200, 200 + n):
        im.putpixel((x, 200), 255)
    return im


def _busy():
    """一整条横带都在变 —— 噪声量级和真机上「终端刷新」实测的 ~11000 同档。"""
    im = _blank()
    for y in range(0, 30):
        for x in range(0, 640, 2):
            im.putpixel((x, y), 255)
    return im


class FakeScreen:
    """按脚本吐东西。每调一次往前走一格,走完停在最后一格上。"""

    def __init__(self, sequence):
        self.seq = list(sequence)
        self.i = 0

    def __call__(self, *a, **kw):
        # 真身 _text_on_screen(text) 是带参数的,_screen_thumb() 不带 —— 同一个
        # 假函数得两副面孔都受得住。
        v = self.seq[min(self.i, len(self.seq) - 1)]
        self.i += 1
        return v


def with_fakes(sequence, ocr_result, fn):
    """把 _screen_thumb / _text_on_screen 换成假的,跑 fn,再还原。

    ocr_result 给单个值 = 每次都回它;给一串 = 每次取下一个(用来测「第一遍
    没认出、第二遍认出」这种重试路径)。
    """
    old_thumb = ai_tools._screen_thumb
    old_ocr = ai_tools._text_on_screen
    seq = ocr_result if isinstance(ocr_result, (list, tuple)) else [ocr_result]
    ai_tools._screen_thumb = FakeScreen(sequence)
    ai_tools._text_on_screen = FakeScreen(seq)
    ai_tools._clear_type_flag()
    try:
        return fn()
    finally:
        ai_tools._screen_thumb = old_thumb
        ai_tools._text_on_screen = old_ocr
        ai_tools._clear_type_flag()


class MutableScreen:
    """一直回同一张图,直到 [set] 换一张 —— 用来模拟「按下去的那一瞬间界面变了」。

    回车验货只认「有没有变」,所以必须能精确控制「变没变」:按下之前是这张,
    按下之后是那张。按脚本数格子的 [FakeScreen] 做不到这一点 —— 它不知道
    「哪一次调用发生在按下回车之后」。
    """

    def __init__(self, img):
        self.img = img

    def set(self, img):
        self.img = img

    def __call__(self, *a, **kw):
        return self.img


def with_enter_screen(shot, fn):
    """回车专用:把 `_screen_thumb` 换成一个只会回同一张图的屏幕,跑 fn,再还原。

    fn 拿到那个屏幕对象,可以在按下回车的瞬间 `screen.set(别的图)`。
    """
    old_thumb, old_hotkey = ai_tools._screen_thumb, ai_tools._press_hotkey
    old_ocr = ai_tools._text_on_screen
    screen = MutableScreen(shot)
    ai_tools._screen_thumb = screen
    # 这些用例关心的是**回车**;前面那次 type 让它顺利成功就行(它自己的判据在
    # 上面第 1~8 条钉着),别让真的 OCR 跑到本机屏幕上,那会又慢又不确定。
    ai_tools._text_on_screen = lambda text: True
    ai_tools._clear_type_flag()
    try:
        return fn(screen)
    finally:
        ai_tools._screen_thumb, ai_tools._press_hotkey = old_thumb, old_hotkey
        ai_tools._text_on_screen = old_ocr
        ai_tools._clear_type_flag()


def run_type(text="helloworld!", **kw):
    return ai_tools.execute({"tool": "type", "args": {"text": text}},
                            type_fn=lambda t: None, **kw)


def run_enter():
    return ai_tools.execute({"tool": "hotkey", "args": {"keys": "enter"}})


# --------------------------------------------------------------------------
# 1 / 2: 没变化 vs 有变化
# --------------------------------------------------------------------------
def test_text_lost_is_reported_as_failure():
    """屏幕一点没变、OCR 也说没有 → 必须 ok=False。这就是真机上发生的事。"""
    out = with_fakes([_blank(), _blank(), _blank()], False, run_type)
    assert out["ok"] is False, "字没打进去却回了成功 —— 正是要消灭的那个 bug: %r" % out
    assert out["verified"] == "failed"
    assert "没打进去" in out["error"]
    assert "click_ui" in out.get("hint", ""), "失败时要给出下一步怎么办"
    print("  1 没变化 → ok=False ✓")


def test_text_landed_is_success():
    out = with_fakes([_blank(), _blank(), _with_blob()], False, run_type)
    assert out["ok"] is True, out
    assert out["verified"] == "screen"
    print("  2 有变化 → ok=True ✓")


# --------------------------------------------------------------------------
# 3: 像素看不出来,但 OCR 找到了 —— 不许冤枉
# --------------------------------------------------------------------------
def test_tiny_change_but_ocr_finds_it_is_success():
    """只打进一个字:屏幕几乎没变,但 OCR 认得出来。不能判失败。"""
    out = with_fakes([_blank(), _blank(), _blank()], True, run_type)
    assert out["ok"] is True, "OCR 都找到了还判失败 = 冤枉一次正常输入: %r" % out
    assert out["verified"] == "screen"
    print("  3 像素没变但 OCR 找到 → ok=True ✓")


# --------------------------------------------------------------------------
# 4: 仪器坏了 —— 既不许判失败,也不许说成验过了
# --------------------------------------------------------------------------
def test_no_screenshot_never_becomes_a_false_failure():
    out = with_fakes([None, None, None], None, run_type)
    assert out["ok"] is True, "抓不了图 + OCR 也没证据,却判失败 = 拿仪器问题冤枉功能: %r" % out
    assert out["verified"] == "unknown", "没验过的事不许说成验过了"
    print("  4 两件仪器都没证据 → ok=True 且 verified=unknown ✓")


# --------------------------------------------------------------------------
# 5: ★ 屏幕太闹时降级到 OCR —— 这条是 2026-10-03 当场改的
# --------------------------------------------------------------------------
def test_busy_screen_falls_back_to_ocr_instead_of_surrendering():
    """★ 回归钉:屏幕闹 ≠ 不验了。像素分不出来时,该由 OCR 接着判。

    旧代码在这里直接回 unknown —— 而真机上「终端一刷新基线就有 11000」,
    等于把关掉的验证当成谨慎。这条测试就是不让它退回去。
    """
    probe = ai_tools._changed_pixels(_blank(), _busy())
    assert probe > 4000, "假图不够闹(%d),测不到「像素失效」那条分支" % probe
    out = with_fakes([_blank(), _busy(), _blank()], True, run_type)
    assert out["verified"] == "screen", \
        "屏幕一闹就放弃验证了 —— 旧代码的毛病又回来了: %r" % out
    assert out["via"] == "OCR 找到"
    print("  5 屏幕太闹但 OCR 找到 → 仍然 ok=True/verified=screen ✓")


def test_busy_screen_with_working_ocr_still_convicts():
    """★ 反向:屏幕再闹,只要 OCR 证明了「这一屏没有这几个字」,就得定罪。

    否则「屏幕闹」会变成谎报成功的万能挡箭牌 —— 比原来的 bug 更糟。
    """
    out = with_fakes([_blank(), _busy(), _blank()], False, run_type)
    assert out["ok"] is False, "OCR 明确说没有,却因为屏幕闹就放行: %r" % out
    assert out["verified"] == "failed"
    print("  6 屏幕太闹 + OCR 说没有 → 仍然定罪 ok=False ✓")


def test_busy_screen_with_dead_ocr_is_unknown():
    """屏幕闹 + OCR 认不出这一屏(一个字都没有)= 没有证据 → unknown,别硬判。"""
    out = with_fakes([_blank(), _busy(), _blank()], None, run_type)
    assert out["ok"] is True, out
    assert out["verified"] == "unknown"
    print("  7 屏幕太闹 + OCR 也没证据 → verified=unknown ✓")


def test_small_change_on_a_busy_screen_is_not_taken_as_evidence():
    """★ 最阴的一种:屏幕自己很闹,**而且**前后又确实差了一点点。

    差的那点完全可能是动画的一帧,不是字。这时**不能用像素当证据**,得听 OCR 的。
    实测这台机器:打 12 个字符才改变 202 像素,而终端一刷新基线就 11000 ——
    202 落在噪声里,判成「打进去了」就是又一次谎报成功。
    """
    out = with_fakes([_blank(), _busy(), _with_blob()], False, run_type)
    assert out["ok"] is False, \
        "屏幕闹的时候把 202 像素的变化当成了「字进去了」: %r" % out
    assert out["verified"] == "failed"
    print("  7b 屏幕闹 + 只变了一点 + OCR 说没有 → 仍然定罪 ✓")


# --------------------------------------------------------------------------
# 6: OCR 重试
# --------------------------------------------------------------------------
def test_ocr_retries_once_before_convicting():
    """实测撞到过:字明明在屏幕上,OCR 头一遍一个字都没认出来(抓屏比字画出来早)。

    所以判决前允许再看一遍 —— 少冤枉一次就少一次。
    """
    out = with_fakes([_blank(), _blank(), _blank()], [None, True], run_type)
    assert out["ok"] is True, "第二遍找到了却还是判失败: %r" % out
    assert out["verified"] == "screen"
    print("  8 OCR 第一遍没认出、第二遍认出 → ok=True ✓")


# --------------------------------------------------------------------------
# 7: 回车闸门
# --------------------------------------------------------------------------
def test_enter_is_blocked_after_a_failed_type():
    """字没打进去之后按回车,必须拦下 —— 空地方按回车可能触发别的按钮。"""
    def go():
        run_type()
        return run_enter()
    out = with_fakes([_blank(), _blank(), _blank()], False, go)
    assert out["ok"] is False, "字都没进去还放行了回车: %r" % out
    assert out.get("blocked_by") == "type_failed"
    assert "什么都没发出去" in out["error"], "要明确告诉模型:这一下什么都没发生"
    print("  9 type 失败后的回车 → 拦下 ✓")


def test_enter_still_works_after_a_good_type():
    """打字成功、回车也真有反应 → 放行。"""
    def go(screen):
        run_type()
        # 回车按下去的那一下,界面把消息画出来了
        ai_tools._press_hotkey = lambda k: (screen.set(_with_blob()), (True, None))[1]
        return run_enter()
    out = with_enter_screen(_blank(), go)
    assert out["ok"] is True, "打字成功了还把回车拦下 = 把功能改坏了: %r" % out
    assert out["verified"] == "screen"
    print(" 10 打字成功后的回车(界面有反应)→ 放行 ✓")


def test_ctrl_a_is_never_blocked():
    """只有回车该被拦。ctrl+a 这种跟「发出去」无关的键不该受牵连。"""
    def go():
        run_type()
        return ai_tools.execute({"tool": "hotkey", "args": {"keys": "ctrl+a"}})
    out = with_fakes([_blank(), _blank(), _blank()], False, go)
    assert out["ok"] is True, out
    print(" 11 ctrl+a 不受闸门影响 ✓")


# --------------------------------------------------------------------------
# 8: 点过别处之后,旧账不该再算
# --------------------------------------------------------------------------
def test_clicking_elsewhere_clears_the_flag():
    def go(screen):
        run_type()                       # 失败,记一笔
        ai_tools._clear_type_flag()      # 模拟 click_ui 点成了别处
        ai_tools._press_hotkey = lambda k: (screen.set(_with_blob()), (True, None))[1]
        return run_enter()
    out = with_enter_screen(_blank(), go)
    assert out["ok"] is True, "点过别的地方了还拿旧账拦回车: %r" % out
    assert out.get("blocked_by") != "type_failed", "旧账没清干净"
    print(" 12 点过别处 → 旧账清掉 ✓")


# --------------------------------------------------------------------------
# 11: 回车本身也要验货 —— 这是整条链上最后一处「闭着眼睛回成功」
# --------------------------------------------------------------------------
def test_enter_that_changes_nothing_is_reported_as_failure():
    """★ 用户报的「打出来但是没发送」的最坏形态:回车按下去,屏幕一动不动。

    这就是「消息还躺在输入框里」的样子 —— 必须回 ok=False,不许再谎报成功。
    """
    def go(screen):
        ai_tools._press_hotkey = lambda k: (True, None)   # 按是按下去了,但什么也没发生
        return run_enter()
    out = with_enter_screen(_blank(), go)
    assert out["ok"] is False, "回车毫无反应却回了成功 —— 正是要消灭的那个 bug: %r" % out
    assert out["verified"] == "failed"
    assert "一个像素都没变" in out["error"]
    assert "不要以为按过回车就算发出去了" in out.get("hint", ""), "要掐死「按过=发过」"
    print(" 15 回车后屏幕一动不动 → ok=False ✓")


def test_enter_with_a_tiny_real_change_is_not_convicted():
    """★ 判据必须是「**逐像素一模一样**」,不能是「小于某个门槛」。

    这条是被实机数据逼出来的:记事本里打「hello world」改 10790 个像素,而紧接着
    按回车**只改 20 个**(光标下移一行,文字本身没动 —— 而它确实发生了)。
    任何 `> ambient*2+30` 这种门槛都会把这次真实的回车判成失败,然后模型就会
    把一条**已经发出去**的消息再发一遍。所以这里钉死:20 个像素也算「有反应」。
    """
    def go(screen):
        ai_tools._press_hotkey = lambda k: (screen.set(_with_speck()), (True, None))[1]
        return run_enter()
    out = with_enter_screen(_blank(), go)
    assert out["ok"] is True, "只变了 20 个像素就判定「什么都没发生」= 会诱使重发: %r" % out
    assert out["verified"] == "screen"
    assert out["changed_px"] == 20, "证据要如实报出来"
    print(" 15b 只变 20 像素(真发生过的回车)→ 算有反应 ✓")


def test_enter_with_a_small_baseline_noise_still_reads_the_reaction():
    """★ 真机 2026-10-03 跑通那次的实际数字:回车的 changed_px=850,却因为基线不是
    绝对静止而回了 unknown —— **一个抓在手里的证据被白白扔掉了**。

    两件事代价不对称,判据就不该共用:说「有反应」说错了只是措辞弱(后面还有
    「这不等于发出去了」兜着);说「什么都没发生」说错了会让用户收到两条消息。
    所以「认有反应」可以宽松:变化够得着噪声门槛就算。
    """
    old_thumb, old_hotkey = ai_tools._screen_thumb, ai_tools._press_hotkey
    # base=全黑 → ambient 比到「只亮 4 个像素」= 4;回车之后屏幕变成一大块白的
    ai_tools._screen_thumb = FakeScreen([_blank(), _with_speck(4), _with_blob()])
    ai_tools._press_hotkey = lambda k: (True, None)
    ai_tools._clear_type_flag()
    try:
        out = run_enter()
    finally:
        ai_tools._screen_thumb, ai_tools._press_hotkey = old_thumb, old_hotkey
        ai_tools._clear_type_flag()
    assert out["ok"] is True, out
    assert out["verified"] == "screen", "850 那样的变化被扔掉了: %r" % out
    print(" 15c 基线有微弱噪声、但变化很大 → 仍然认「有反应」✓")


def test_enter_verdict_never_claims_the_message_was_sent():
    """屏幕上「有反应」只等于有反应,**不等于发出去了** —— 措辞不许越界。"""
    def go(screen):
        ai_tools._press_hotkey = lambda k: (screen.set(_with_blob()), (True, None))[1]
        return run_enter()
    out = with_enter_screen(_blank(), go)
    said = (out.get("note", "") + out.get("error", "") + out.get("via", ""))
    assert "已发送" not in said and "发送成功" not in said, \
        "只看见屏幕变了就说「已发送」= 又一处谎报: %r" % out
    print(" 16 有反应 ≠ 已发送(措辞不越界)✓")


def test_enter_on_a_busy_screen_is_unknown_not_a_false_alarm():
    """★ 屏幕本来就在闹 → 分不出来 → 回 unknown,**不许定罪**。

    定罪的代价不对称:回 unknown 只是没确认;回 failed 会诱使模型把一个
    **已经发出去**的消息再发一遍 —— 那比「没确认」糟得多。
    """
    old_thumb, old_hotkey = ai_tools._screen_thumb, ai_tools._press_hotkey
    # base = _busy,紧接着 ambient 那一次就抓到了 _blank —— 差值 9600,远超门槛。
    ai_tools._screen_thumb = FakeScreen([_busy(), _blank(), _blank()])
    ai_tools._press_hotkey = lambda k: (True, None)
    ai_tools._clear_type_flag()
    try:
        out = run_enter()
    finally:
        ai_tools._screen_thumb, ai_tools._press_hotkey = old_thumb, old_hotkey
        ai_tools._clear_type_flag()
    assert out["ok"] is True, "屏幕太闹时不许判失败(会诱使重发): %r" % out
    assert out["verified"] == "unknown"
    assert "没能确认" in out.get("note", "")
    print(" 17 屏幕太闹 → unknown(不定罪)✓")


def test_enter_with_no_screenshot_is_unknown():
    """抓不到屏幕(非 Windows / 没装 Pillow)时不许判失败。"""
    def go(screen):
        ai_tools._press_hotkey = lambda k: (True, None)
        return run_enter()
    out = with_enter_screen(None, go)
    assert out["ok"] is True, "仪器没有时冤枉一次正常发送: %r" % out
    assert out["verified"] == "unknown"
    print(" 18 抓不到屏幕 → unknown ✓")


def test_enter_reports_the_press_being_rejected():
    """_press_hotkey 自己说没按成 → 原样回失败,别装成验过了。"""
    def go(screen):
        ai_tools._press_hotkey = lambda k: (False, "这个键不在白名单里")
        return run_enter()
    out = with_enter_screen(_blank(), go)
    assert out["ok"] is False and "白名单" in out["error"], out
    print(" 19 按键被白名单拒绝 → 如实回失败 ✓")


# --------------------------------------------------------------------------
# 12: type 的回执必须明说「还没发出去」
# --------------------------------------------------------------------------
def test_type_success_tells_the_model_it_has_not_sent_anything():
    """★ 真机根因:type 成功的回执读起来像完成态,模型就真的收工了。

    所以每条成功回执里都要有 `next`,并且必须点到 hotkey enter。
    这条是防回归的钉子 —— 哪天有人觉得这句话啰嗦把它删了,这里会响。
    """
    out = with_fakes([_blank(), _blank(), _with_blob()], False, run_type)
    assert out["ok"] is True, out
    assert "next" in out, "成功回执里没有那句「还差一步」—— 模型会又直接收工"
    assert "enter" in out["next"], "必须点名 hotkey(enter)"
    assert "还没有发出去" in out["next"]
    print(" 20 type 成功 → 回执点明「还没发出去」✓")


def test_type_unknown_also_carries_the_next_hint():
    """没验成(unknown)也是一次「字可能进去了」—— 同样得提醒还差回车。"""
    out = with_fakes([_blank(), _blank(), _blank()], None, run_type)
    assert out["verified"] == "unknown", out
    assert "next" in out and "enter" in out["next"], out
    print(" 21 type 未验证 → 也带「还差回车」✓")


# --------------------------------------------------------------------------
# 9: 仪器健康守卫本身 —— _text_on_screen 必须是三态,不能塌成两态
# --------------------------------------------------------------------------
def test_text_on_screen_three_states():
    """★ OCR 认出一屏字却没有目标 = 可以定罪;一个字都没认出来 = 不能定罪。

    这条区分就是 `verified="unknown"` 赖以为生的东西。一旦它塌成「找不到就回
    False」,屏幕空了、OCR 崩了、字太花认不出 —— 全都变成「字没打进去」,
    功能会被冤枉到没法用。
    """
    import uia
    old_find = uia.ocr_find
    old_title = ai_tools._task.get("title")
    ai_tools._task["title"] = None
    try:
        cases = [
            # (ocr_find 回什么, 期望, 说明)
            (([], {"ok": True, "total": 40}), False, "认出一屏字却没有目标"),
            (([], {"ok": True, "total": 0}), None, "OCR 一个字都没认出来 = 仪器可疑"),
            (([{"name": "张三"}], {"ok": True, "total": 40}), True, "找到了"),
            (([], {"ok": False, "error": "起不来"}), None, "OCR 报错"),
        ]
        for ret, want, why in cases:
            uia.ocr_find = lambda *a, **k: ret
            got = ai_tools._text_on_screen("张三")
            assert got is want, "%s:期望 %r,拿到 %r" % (why, want, got)
        def boom(*a, **k):
            raise RuntimeError("OCR 崩了")
        uia.ocr_find = boom
        assert ai_tools._text_on_screen("张三") is None, "OCR 抛异常时要回 None"
    finally:
        uia.ocr_find = old_find
        ai_tools._task["title"] = old_title
    print("  8b _text_on_screen 三态分明 ✓")


def test_empty_needle_is_never_evidence():
    assert ai_tools._text_on_screen("") is None, "没给要找的字时不该有结论"
    assert ai_tools._text_on_screen("   ") is None
    print("  8c 空字符串 → 无结论 ✓")


# --------------------------------------------------------------------------
# 10: search 的搜索词打不进去,要当场判死
# --------------------------------------------------------------------------
def test_search_reports_when_the_query_never_landed():
    """搜索框没拿到焦点:Ctrl+F 白按、词没进去 —— 当场失败,别烂到下游。"""
    old_hotkey = ai_tools._press_hotkey
    ai_tools._press_hotkey = lambda k: (True, None)
    try:
        out = with_fakes([_blank(), _blank(), _blank()], False,
                         lambda: ai_tools.execute({"tool": "search",
                                                   "args": {"query": "文件传输助手"}},
                                                  type_fn=lambda t: None))
    finally:
        ai_tools._press_hotkey = old_hotkey
    assert out["ok"] is False, out
    assert out.get("blocked_by") == "search_not_typed"
    assert "搜索词没打进搜索框" in out["error"]
    print(" 13 搜索词没进去 → 当场判死 ✓")


def test_search_ok_when_the_query_lands():
    old_hotkey = ai_tools._press_hotkey
    ai_tools._press_hotkey = lambda k: (True, None)
    try:
        out = with_fakes([_blank(), _blank(), _with_blob()], False,
                         lambda: ai_tools.execute({"tool": "search",
                                                   "args": {"query": "文件传输助手"}},
                                                  type_fn=lambda t: None))
    finally:
        ai_tools._press_hotkey = old_hotkey
    assert out["ok"] is True, out
    assert "next" in out, "成功时那句「还没选中结果」的提醒不能丢"
    print(" 14 搜索词进去了 → ok=True ✓")


if __name__ == "__main__":
    tests = [v for k, v in sorted(globals().items()) if k.startswith("test_")]
    print("type 验货 / 回车闸门 —— 共 %d 条" % len(tests))
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
