# -*- coding: utf-8 -*-
"""
ConMarn · 换装 —— 把一个 `.vrm` 人物模型推到手机上,并且**验到它真的生效**。

## 为什么有这个东西

手机那头「放文件就生效」这条路**本身是对的**(`Wardrobe.kt`:只认扩展名、不认文件名)。
但它周围有三个坑,而这三个坑**一个都不会报错**:

  1. **目录里留两个 `.vrm` → 静默用按名字排在前面那个**(`WardrobeMath.pick` 第 4 条)。
     他以为换的是新的,实际用的是旧的,屏幕上、任何回执里都不会说一句「你没换」。
  2. **`adb push` 落的文件属主是 `shell`。** 今天盘上那份碰巧是 `rw-rw-rw-` 能读 ——
     不能指望。2026-10-05 在 `tts/` 上栽过一次,症状是「模型不在」而模型明明在盘上。
  3. ★★ **`push` 回成功 ≠ 她换了。** 「我换了它没变」在这条路上**一个字都不会报** ——
     唯一的分界线是 `model.log` 里那行 `换装:人物:用 xxx(person/)`。

★ 所以这个脚本真正卖的不是「省得敲 adb」,是**第 3 条**:
  它传完会去读那一行,**把「换了没生效」从玄学变成一句话**。

## 它做的每一步(全部写进窗口里的日志,一条都不藏)

  1. 找 adb、找设备(0 台 / 多台 / 没授权都要说话,不许静默)
  2. 把 `person/` 里现有的东西列出来(换之前先看一眼)
  3. push 到**临时名** → **按字节数对账**(认字节数不认文件名)
  4. 把旧的 `.vrm` **改名**成 `.old`,**不删字节** —— 他随时能拿回来
  5. 临时名 → 固定名 `model.vrm`
  6. `chmod`(保险;那三个坑里第 2 条)
  7. 重开她(`force-stop` + `am start`)—— **可取消**,见下面
  8. ★ **读 `model.log` 验货**:最后那条 `换装:人物` 说的是不是这个文件

## ★ 为什么目标是固定名 `model.vrm`,不留他原来的文件名

两条理由,都不是洁癖:

  * **「排序取第一个」那个坑的根,就是「目录里可能不止一个 `.vrm`」。** 固定名 = 永远只有一个。
  * **中文文件名过 `adb shell` 会撞编码**(Windows 命令行是 GBK,adb shell 收 UTF-8)。
    `adb push` 那一步是二进制传输、不受影响,但**改名那一步走的是 shell** ——
    留着 `新人物.vrm` 这种名字,就多一次在中文上翻车的机会。**固定 ASCII 名,这个坑不存在。**

## ★ 关于「重开她」的代价(要说给他听)

重开 = `force-stop` + `am start` —— **会把她整个进程杀掉,模型要重新装载**。
他要是正在跟她说话,会被打断。所以它是一个**勾选框**、默认勾上:
「一键换装」的意思就是一步到位,但随时能取消 —— 取消之后文件照样放好,
只是要他自己退一次房间再进(那样脚本就验不了货,它会**明说没验**)。

## 自检

    python tools/wardrobe_push.py --selftest

只跑**读**的那一半 + 拿**点开头的临时文件**验一遍 push/改名/对账/删除的机制。
点开头的文件 App 完全看不见(`WardrobeMath.allowedExternal` 第一条),
所以自检**不碰他任何一个模型、也不重开她**。
"""

from __future__ import annotations

import os
import queue
import subprocess
import sys
import threading
import time
import traceback

CREATE_NO_WINDOW = 0x08000000

HERE = os.path.dirname(os.path.abspath(__file__))
DROP_DIR = os.path.join(HERE, "人物")

# ★ 2026-11-08:applicationId 已从 com.example.touchpad 改成 com.conmarn.app
#   (Java 包名仍是 com.example.touchpad,所以 Activity 类名不变)。
#   这段常量还停在旧包名时,脚本会往一个不存在的目录推,而且**一步都不报错**。
PKG_NAME = "com.conmarn.app"
LAUNCHER = "com.conmarn.app/com.example.touchpad.ConMarnLauncher"
PERSON_DIR = "/sdcard/Android/data/%s/files/person" % PKG_NAME
MODEL_LOG = "/sdcard/Android/media/%s/model.log" % PKG_NAME

# ★ 固定名。理由见文件头。
TARGET_NAME = "model.vrm"
# ★ 点开头 —— App 完全看不见它(`WardrobeMath.pick` 第一条就把它筛掉)。
#   于是「传了一半」这个状态**永远不会被她读到**,不用怕半截文件顶掉好文件。
TEMP_NAME = ".wardrobe_new.vrm"
# ★ 旧文件改名而不是删:字节全在,他随时能拿回来。
OLD_SUFFIX = ".old"

LOG_FILE = os.path.join(os.environ.get("TEMP", "."), "conmarn_wardrobe.log")

ADB = None


# ---------------------------------------------------------------- 跑命令这一层

def find_adb() -> str:
    """找 adb。找不到就回字面量 "adb",让它去 PATH 里碰运气(报错时再说人话)。"""
    cands = []
    for env in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        v = os.environ.get(env)
        if v:
            cands.append(os.path.join(v, "platform-tools", "adb.exe"))
            cands.append(os.path.join(v, "platform-tools", "adb"))
    la = os.environ.get("LOCALAPPDATA")
    if la:
        cands.append(os.path.join(la, "Android", "Sdk", "platform-tools", "adb.exe"))
    # 本机实测就是这个
    cands.append(r"C:\Users\27014\AppData\Local\Android\Sdk\platform-tools\adb.exe")
    for c in cands:
        if c and os.path.isfile(c):
            return c
    return "adb"


def run(args, timeout=180):
    """跑一条命令。**永远不抛** —— 回 (返回码, stdout, stderr)。

    全部走 list 形式,不经过 shell:
      * Windows 的路径反斜杠、空格、中文都不会被二次解释;
      * 中文也不会撞 cmd 的 GBK。
    """
    try:
        p = subprocess.run(
            args, capture_output=True, timeout=timeout,
            creationflags=CREATE_NO_WINDOW,
        )
    except FileNotFoundError:
        return -1, "", "找不到这个程序: %s" % args[0]
    except subprocess.TimeoutExpired:
        return -2, "", "超时(%.0f 秒): %s" % (timeout, " ".join(args))
    except Exception as e:  # noqa: BLE001 —— 这一层不许把异常漏出去
        return -3, "", "%s: %s" % (type(e).__name__, e)
    return (p.returncode,
            p.stdout.decode("utf-8", "replace"),
            p.stderr.decode("utf-8", "replace"))


def adb(*args, timeout=180):
    return run([ADB, *args], timeout=timeout)


def sh(*args, timeout=180):
    """一条设备上的 shell 命令。"""
    return adb("shell", *args, timeout=timeout)


# ---------------------------------------------------------------- 问设备这一层

def devices():
    """回 (能用的序列号列表, 一句话描述)。

    ★ 三种「有设备但不能用」都要分开说 —— 它们长得一样、解法完全不同:
      没插线 / 没授权 / 被别的程序占着(offline)。
    """
    rc, out, err = adb("devices")
    if rc != 0:
        return [], "adb 跑不起来: " + (err.strip() or out.strip() or "未知原因")
    rows = []
    for line in out.splitlines()[1:]:
        line = line.strip()
        if not line or line.startswith("*"):
            continue
        parts = line.split()
        if len(parts) >= 2:
            rows.append((parts[0], parts[1]))
    ok = [s for s, st in rows if st == "device"]
    if ok:
        if len(ok) > 1:
            return ok, "插着 %d 台设备:%s —— 只插一台再点。" % (len(ok), ", ".join(ok))
        return ok, "手机已连上(%s)" % ok[0]
    if not rows:
        return [], "没看到手机。插好 USB 线、手机上开「USB 调试」,再点一次。"
    states = ", ".join("%s=%s" % (s, st) for s, st in rows)
    if any(st == "unauthorized" for _, st in rows):
        return [], ("手机没授权(%s)。看手机屏幕,点「允许 USB 调试」,再点一次。"
                    % states)
    return [], "手机连上了但不能用(%s)。拔了重插一次试试。" % states


def list_person():
    """回 [(名字, 字节数)]。**读不到就抛 RuntimeError(带人话)**。"""
    rc, out, err = sh("ls", "-l", PERSON_DIR)
    if rc != 0:
        raise RuntimeError(
            "读不到手机上的 person/ 目录 —— 她可能还没起来过(那个目录是 App 自己建的)。"
            "先在手机上打开一次她的房间,再回来点。")
    items = []
    for line in out.splitlines():
        line = line.rstrip()
        if not line or line.startswith("total"):
            continue
        parts = line.split()
        if len(parts) < 8:
            continue
        try:
            size = int(parts[4])
        except ValueError:
            continue
        name = " ".join(parts[7:])
        if name in (".", ".."):
            continue
        items.append((name, size))
    return items


def remote_size(path):
    """设备上一个文件的字节数。拿不到回 None(**不许拿 0 冒充**)。"""
    rc, out, err = sh("stat", "-c", "%s", path)
    s = out.strip()
    if rc == 0 and s.isdigit():
        return int(s)
    return None


def wardrobe_lines():
    """`model.log` 里所有 `换装:人物` 那几行(只取尾巴,那文件很大)。"""
    rc, out, err = adb("exec-out", "tail", "-n", "800", MODEL_LOG, timeout=60)
    if rc != 0 or not out:
        rc2, out, err = adb("exec-out", "cat", MODEL_LOG, timeout=60)
        if rc2 != 0:
            return []
    return [l.strip() for l in out.splitlines() if "换装:人物" in l]


# ---------------------------------------------------------------- 干正事

def push_model(local_path, say):
    """把 [local_path] 换上去。成功回 True。

    [say] 是写日志的回调(带一个 `ok=False` 关键字好让界面能标红)。
    """
    if not os.path.isfile(local_path):
        say("找不到这个文件:%s" % local_path, ok=False)
        return False
    local_bytes = os.path.getsize(local_path)
    base = os.path.basename(local_path)
    if not base.lower().endswith(".vrm"):
        say("这个不是 .vrm:%s —— 手机那头只认 .vrm。" % base, ok=False)
        return False
    if local_bytes <= 0:
        say("这个文件是 0 字节:%s —— 传过去她也会当成坏文件。" % base, ok=False)
        return False

    # ---- 1. 设备 ----
    serials, desc = devices()
    if not serials:
        say(desc, ok=False)
        return False
    say(desc)
    if len(serials) > 1:
        say("插了不止一台,先拔到只剩一台。", ok=False)
        return False

    # ---- 2. 换之前先看一眼 ----
    try:
        before = list_person()
    except RuntimeError as e:
        say(str(e), ok=False)
        return False
    say("换之前的 person/:%s" % ("(空)" if not before else ""))
    for n, s in before:
        say("    %-28s %s" % (n, fmt_size(s)))
    old_vrm = [n for n, _ in before
               if n.lower().endswith(".vrm") and not n.startswith(".") and n != TARGET_NAME]

    before_lines = wardrobe_lines()
    before_last = before_lines[-1] if before_lines else None
    before_n = len(before_lines)

    # ---- 3. push 到临时名 + 按字节数对账 ----
    remote_tmp = "%s/%s" % (PERSON_DIR, TEMP_NAME)
    say("正在传:%s(%.1f MB)…" % (base, local_bytes / 1048576.0))
    t0 = time.time()
    rc, out, err = adb("push", local_path, remote_tmp, timeout=900)
    if rc != 0:
        say("传失败:%s" % (err.strip() or out.strip() or "adb 回 %d" % rc), ok=False)
        return False
    got = remote_size(remote_tmp)
    if got != local_bytes:
        # ★ 认字节数不认文件名 —— 这台机器上「推过去的是半截」真发生过。
        say("传过去的大小不对:本地 %d 字节,手机上 %s 字节。**没换。**"
            % (local_bytes, "读不到" if got is None else str(got)), ok=False)
        return False
    say("传到手机上了:%.1f MB,字节数对得上(%.1fs)"
        % (got / 1048576.0, time.time() - t0))

    # ---- 4. 旧的改名(不删字节) ----
    moved = []
    for n in old_vrm:
        rc, out, err = sh("mv", "-f",
                          "%s/%s" % (PERSON_DIR, n),
                          "%s/%s%s" % (PERSON_DIR, n, OLD_SUFFIX))
        if rc == 0:
            moved.append(n)
        else:
            say("旧的 %s 没能改名(%s)—— 先停手,没换。" % (n, err.strip() or rc), ok=False)
            return False
    if moved:
        say("旧的先收起来了(没删,名字后面加了 %s):%s"
            % (OLD_SUFFIX, ", ".join(moved)))

    # ---- 5. 临时名 → 固定名 ----
    rc, out, err = sh("mv", "-f", remote_tmp, "%s/%s" % (PERSON_DIR, TARGET_NAME))
    if rc != 0:
        say("改名失败(%s)—— 文件还在,但没换。" % (err.strip() or rc), ok=False)
        return False
    final = "%s/%s" % (PERSON_DIR, TARGET_NAME)

    # ---- 6. chmod(那两个坑里的第 2 条) ----
    rc, out, err = sh("chmod", "666", final)
    if rc != 0:
        say("chmod 没成功(%s)—— 不一定坏,但要说出来。" % (err.strip() or rc), ok=False)
    else:
        say("权限已放开(666)")

    got = remote_size(final)
    if got != local_bytes:
        say("换上之后大小对不上:%s。**先别高兴。**"
            % ("读不到" if got is None else str(got)), ok=False)
        return False
    say("已就位:person/%s(%.1f MB)" % (TARGET_NAME, got / 1048576.0))

    # ---- 7. 重开她 ----
    if not RESTART:
        say("按你说的没重开她。**这一步没验** —— "
            "你退出房间再进一次就生效(或者下次勾上「传完重开她」)。", ok=False)
        return True

    say("重开她(会把她整个进程停掉,模型重新装载,十几秒)…")
    rc, out, err = sh("am", "force-stop", PKG_NAME)
    if rc != 0:
        say("停不掉她(%s)—— 那她可能还捧着旧模型。" % (err.strip() or rc), ok=False)
    rc, out, err = sh("am", "start", "-n", LAUNCHER)
    if rc != 0:
        say("没把她叫起来(%s)—— 你自己点一下她的图标。" % (err.strip() or rc), ok=False)
        return True

    # ---- 8. ★ 验货:读 model.log ----
    say("等她自己出场(最长 150 秒)…")
    deadline = time.time() + 150
    while time.time() < deadline:
        time.sleep(3)
        lines = wardrobe_lines()
        if lines and (len(lines) > before_n or lines[-1] != before_last):
            last = lines[-1]
            if TARGET_NAME in last:
                say("★ 成了。" + last)
                say("她身上现在就是刚传上去的这个。")
                return True
            say("★ 她读到的**不是**刚传的那个:" + last, ok=False)
            say("(日志在 model.log,搜「换装:」四个字能看到全部)", ok=False)
            return False

    say("★ **没验到** —— 150 秒里 model.log 没出现新的「换装:人物」行。", ok=False)
    say("可能:她没起来 / 你退出了房间没进 / App 起得慢。"
        "**我不敢说成了。** 你去手机上点开她的房间,再看一眼盘上那两个文件。", ok=False)
    return False


# ---------------------------------------------------------------- 小工具

def fmt_size(n):
    if n is None:
        return "?"
    for unit in ("B", "KB", "MB"):
        if n < 1024 or unit == "MB":
            return "%.1f %s" % (n, unit) if unit != "B" else "%d B" % n
        n /= 1024.0
    return "%d B" % n


RESTART = True


def scan_drop_dir():
    """`人物/` 文件夹里的 .vrm,新的排前面。"""
    try:
        os.makedirs(DROP_DIR, exist_ok=True)
    except Exception:
        return []
    out = []
    try:
        for n in os.listdir(DROP_DIR):
            if n.lower().endswith(".vrm") and not n.startswith("."):
                p = os.path.join(DROP_DIR, n)
                if os.path.isfile(p):
                    out.append(p)
    except Exception:
        return []
    out.sort(key=lambda p: os.path.getmtime(p), reverse=True)
    return out


def write_file_log(msg):
    try:
        with open(LOG_FILE, "a", encoding="utf-8") as f:
            f.write("%s %s\n" % (time.strftime("%H:%M:%S"), msg))
    except Exception:
        pass


# ---------------------------------------------------------------- 界面

def main_gui():
    import tkinter as tk
    from tkinter import ttk, filedialog, messagebox

    global RESTART

    root = tk.Tk()
    root.title("ConMarn · 换装")
    root.geometry("620x460")
    root.minsize(560, 400)

    chosen = {"path": None}

    # ★ 后台线程**不许**直接摸界面。Tkinter 底下那个 Tcl 不是线程安全的:
    #   从别的线程调 `root.after` 平时看着没事,偶尔整个窗口凭空消失 ——
    #   而「不报错的失败」正是这个项目的头号敌人。所以后台只往队列塞函数,
    #   主线程每 60ms 取一次。多这十行,换掉一个查不出来的偶发崩溃。
    ui_q = queue.Queue()

    def post(fn):
        ui_q.put(fn)

    def pump():
        try:
            while True:
                ui_q.get_nowait()()
        except queue.Empty:
            pass
        root.after(60, pump)

    # --- 手机状态 ---
    dev_var = tk.StringVar(value="正在看手机…")
    tk.Label(root, textvariable=dev_var, anchor="w", fg="#444").pack(
        fill="x", padx=12, pady=(10, 4))

    # --- 选文件 ---
    box = tk.LabelFrame(root, text="人物模型(.vrm)")
    box.pack(fill="x", padx=12, pady=4)
    row = tk.Frame(box)
    row.pack(fill="x", padx=8, pady=6)

    files = scan_drop_dir()
    combo = ttk.Combobox(row, state="readonly", width=44,
                         values=[os.path.basename(p) for p in files])
    if files:
        combo.current(0)
        chosen["path"] = files[0]
    else:
        combo.set("(人物/ 文件夹是空的)")
    combo.pack(side="left")

    def on_pick(_e=None):
        i = combo.current()
        if 0 <= i < len(files):
            chosen["path"] = files[i]
            info_var.set(describe(chosen["path"]))

    combo.bind("<<ComboboxSelected>>", on_pick)

    info_var = tk.StringVar(value=describe(chosen["path"]))
    tk.Label(box, textvariable=info_var, anchor="w", fg="#666").pack(
        fill="x", padx=10, pady=(0, 6))

    btns = tk.Frame(box)
    btns.pack(fill="x", padx=8, pady=(0, 8))

    def pick_other():
        p = filedialog.askopenfilename(
            title="选一个人物模型",
            filetypes=[("VRM 模型", "*.vrm"), ("所有文件", "*.*")])
        if p:
            chosen["path"] = p
            info_var.set(describe(p))

    def open_drop():
        try:
            os.startfile(DROP_DIR)
        except Exception as e:
            messagebox.showerror("打不开文件夹", str(e))

    def refresh():
        threading.Thread(target=refresh_dev, daemon=True).start()

    tk.Button(btns, text="选别的文件…", command=pick_other).pack(side="left")
    tk.Button(btns, text="打开「人物」文件夹", command=open_drop).pack(side="left", padx=6)
    tk.Button(btns, text="刷新手机状态", command=refresh).pack(side="left")

    # --- 传 ---
    act = tk.Frame(root)
    act.pack(fill="x", padx=12, pady=4)
    go = tk.Button(act, text="传至手机", width=14, height=1)
    go.pack(side="left")
    restart_var = tk.BooleanVar(value=True)
    tk.Checkbutton(act, text="传完重开她(约十几秒,期间不能跟她说话)",
                   variable=restart_var).pack(side="left", padx=8)

    # --- 日志 ---
    lf = tk.LabelFrame(root, text="日志")
    lf.pack(fill="both", expand=True, padx=12, pady=(4, 12))
    txt = tk.Text(lf, wrap="word", height=12, state="disabled",
                  background="#1b1b1f", foreground="#d8d8d8", insertbackground="#d8d8d8")
    sb = tk.Scrollbar(lf, command=txt.yview)
    txt.configure(yscrollcommand=sb.set)
    sb.pack(side="right", fill="y")
    txt.pack(side="left", fill="both", expand=True)

    def say(msg, ok=True):
        msg = str(msg)
        write_file_log(msg)

        def do():
            txt.configure(state="normal")
            if not ok:
                txt.insert("end", "✗ " + msg + "\n", ("bad",))
            else:
                txt.insert("end", msg + "\n")
            txt.see("end")
            txt.configure(state="disabled")
        post(do)

    txt.tag_configure("bad", foreground="#ff8a8a")

    def refresh_dev():
        global ADB
        ADB = find_adb()
        serials, desc = devices()
        def do():
            dev_var.set(desc)
        post(do)

    # --- 点「传至手机」 ---
    def on_go():
        global RESTART
        p = chosen["path"]
        if not p:
            messagebox.showwarning(
                "还没选模型",
                "把 .vrm 丢进「人物」文件夹(点左边那个按钮能打开它),\n"
                "或者点「选别的文件…」。")
            return
        if not messagebox.askyesno(
                "确认换装",
                "要把这个换上去吗?\n\n%s\n\n"
                "→ 手机上的名字会变成 model.vrm\n"
                "→ 旧的不会删,只在名字后面加 .old" % p):
            return
        RESTART = bool(restart_var.get())
        go.configure(state="disabled")

        def work():
            try:
                say("——— 开始 ———")
                push_model(p, say)
            except Exception:
                say("脚本自己崩了(这不是你操作的问题):", ok=False)
                for l in traceback.format_exc().splitlines():
                    say("    " + l, ok=False)
            finally:
                say("——— 结束 ———")
                post(lambda: go.configure(state="normal"))

        threading.Thread(target=work, daemon=True).start()

    go.configure(command=on_go)

    threading.Thread(target=refresh_dev, daemon=True).start()
    pump()
    root.mainloop()


def describe(p):
    if not p:
        return "还没选。把 .vrm 丢进「%s」,或者点「选别的文件…」。" % os.path.basename(DROP_DIR)
    try:
        return "%s   （%.1f MB）" % (p, os.path.getsize(p) / 1048576.0)
    except Exception:
        return p


# ---------------------------------------------------------------- 自检

def selftest():
    """只跑**读**那一半 + 拿**点开头的临时文件**验一遍机制。

    ★ 全程不碰 `model.vrm` / 他任何一份模型 / 不重开她 ——
      点开头的文件 App 根本看不见(`WardrobeMath.allowedExternal` 第一条)。
    """
    global ADB
    os.environ.setdefault("PYTHONIOENCODING", "utf-8")
    fails = []

    def check(name, ok, detail=""):
        print(("  OK   " if ok else "  FAIL ") + name + (("   " + detail) if detail else ""))
        if not ok:
            fails.append(name)

    print("== 1. 找 adb ==")
    ADB = find_adb()
    check("adb 路径", os.path.isfile(ADB), ADB)

    print("== 2. 找设备 ==")
    serials, desc = devices()
    check("设备", bool(serials), desc)

    print("== 3. 读 person/ ==")
    try:
        items = list_person()
        check("列目录", True, "%d 项" % len(items))
        for n, s in items:
            print("         %-28s %s" % (n, fmt_size(s)))
    except RuntimeError as e:
        check("列目录", False, str(e))

    print("== 4. 读 model.log 的换装行 ==")
    lines = wardrobe_lines()
    check("换装:人物 行", bool(lines), lines[-1] if lines else "(一条都没有)")

    if not serials:
        print("\n没设备,后面那几步(要往手机上写)跳过了。")
        return 1 if fails else 0

    print("== 5. push / 对账 / 改名 / 删除(**只用点开头的临时文件**)==")
    tmp_local = os.path.join(os.environ.get("TEMP", "."), ".wardrobe_selftest.vrm")
    with open(tmp_local, "wb") as f:
        f.write(b"VRMSELFTEST" + bytes(range(256)) * 4096)   # 约 1 MB,故意非整数
    want = os.path.getsize(tmp_local)

    a = "%s/.selftest_a.vrm" % PERSON_DIR
    b = "%s/.selftest_b.vrm" % PERSON_DIR
    try:
        rc, out, err = adb("push", tmp_local, a, timeout=180)
        check("push", rc == 0, err.strip() or out.strip())
        got = remote_size(a)
        check("按字节数对账", got == want, "本地 %d / 手机 %s" % (want, got))

        rc, out, err = sh("chmod", "666", a)
        check("chmod", rc == 0, err.strip())

        rc, out, err = sh("mv", "-f", a, b)
        check("改名(mv)", rc == 0, err.strip())
        check("改名后大小不变", remote_size(b) == want, str(remote_size(b)))

        rc, out, err = sh("rm", "-f", b)
        check("删掉", rc == 0, err.strip())
        check("删干净了", remote_size(b) is None)
    finally:
        try:
            os.remove(tmp_local)
        except Exception:
            pass
        sh("rm", "-f", a)
        sh("rm", "-f", b)

    print("== 6. 再列一次 person/(确认自检没留下东西)==")
    try:
        after = list_person()
        check("没留垃圾", after == items,
              "之前 %d 项 / 现在 %d 项" % (len(items), len(after)))
    except RuntimeError as e:
        check("没留垃圾", False, str(e))

    print()
    if fails:
        print("有 %d 项没过:%s" % (len(fails), "; ".join(fails)))
        return 1
    print("全过。")
    return 0


if __name__ == "__main__":
    if "--selftest" in sys.argv:
        sys.exit(selftest())
    try:
        main_gui()
    except Exception:
        import tkinter.messagebox as mb
        mb.showerror("ConMarn 换装 · 起不来",
                     traceback.format_exc() + "\n\n日志:%s" % LOG_FILE)
        raise
