# -*- coding: utf-8 -*-
"""把电脑端的 exe 发到官网（GitHub Pages）。

用法（仓库根目录）：

    python tools/publish_pc.py            只改本地站点目录，不提交
    python tools/publish_pc.py --push     改完提交并推送到 gh-pages

前置：先跑 `python tools/pack_pc.py --onefile` 出单文件 exe。

它做四件事，和 publish_site.py 一个路子（页面结构不动，只加/换引用）：
    读版本 → 换 exe → 在下载区补一颗「下载电脑端」→ 补一行电脑端的说明
重复执行是安全的。
"""
from __future__ import annotations

import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APP_PY = ROOT / "pc-server" / "conmarn_app.py"
EXE = ROOT / "pc-server" / "dist-onefile" / "ConMarn.exe"
SITE = Path(r"D:\conmarn-site-gh")

TAG = "conmarn-pc"          # 用来认出「这一段是电脑端的」，重复跑不会插第二遍

CTA = {
    "zh": ('<a class="btn btn-g" href="./ConMarn-{ver}-win64.exe" download>'
           '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3v12m0 0l-4-4m4 4l4-4M4 20h16"/></svg>'
           '下载电脑端</a>'),
    "en": ('<a class="btn btn-g" href="../ConMarn-{ver}-win64.exe" download>'
           '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3v12m0 0l-4-4m4 4l4-4M4 20h16"/></svg>'
           'Download for PC</a>'),
}

META = {
    "zh": ('<p class="meta" data-{tag}><span>电脑端 {mb} MB</span><span>Windows 10 / 11 64 位</span>'
           '<span>未签名，首次运行 Windows 可能提示，选择「仍要运行」</span></p>'),
    "en": ('<p class="meta" data-{tag}><span>PC build {mb} MB</span><span>Windows 10 / 11 64-bit</span>'
           '<span>Unsigned — if Windows warns, choose “More info → Run anyway”</span></p>'),
}


def die(msg: str):
    print("!! " + msg)
    sys.exit(1)


def version() -> str:
    m = re.search(r'APP_VERSION\s*=\s*"([^"]+)"', APP_PY.read_text(encoding="utf-8"))
    if not m:
        die("读不到 pc-server/conmarn_app.py 里的 APP_VERSION")
    return m.group(1)


def read(path: Path) -> str:
    with path.open("r", encoding="utf-8", newline="") as f:
        return f.read()


def write(path: Path, text: str) -> None:
    with path.open("w", encoding="utf-8", newline="") as f:
        f.write(text)


def patch(path: Path, ver: str, lang: str, size: int) -> list[str]:
    src = read(path)
    out = src
    done: list[str] = []
    mb = f"{size / 1e6:.1f}"
    nl = "\r\n" if "\r\n" in out else "\n"

    # 1) 换掉旧版 exe 的链接（不限旧版本号，免得漏）
    olds = sorted(set(re.findall(r"ConMarn-([0-9.]+)-win64\.exe", out)))
    out = re.sub(r"ConMarn-[0-9.]+-win64\.exe", f"ConMarn-{ver}-win64.exe", out)
    if olds:
        done.append("exe 链接 " + ", ".join(olds) + f" → {ver}")

    # 2) 下载区补一颗按钮：插在 APK 那颗后面
    if f'data-{TAG}' not in out and "-win64.exe" not in out:
        m = re.search(r'[ \t]*<a class="btn btn-p"[^>]*>[^<]*(?:<svg.*?</svg>)?[^<]*</a>', out, re.S)
        if m is None:
            die(f"{path}: 找不到「下载 APK」那颗按钮，电脑端的按钮没地方插")
        indent = re.match(r"[ \t]*", m.group(0)).group(0)
        btn = indent + CTA[lang].format(ver=ver)
        out = out[:m.end()] + nl + btn + out[m.end():]
        done.append("补上「下载电脑端」按钮")

    # 3) 体积那一行下面补一句电脑端的说明（只补一次）
    line = META[lang].format(tag=TAG, mb=mb)
    if f'data-{TAG}' not in out:
        m = re.search(r'[ \t]*<p class="meta">(?:(?!</p>).)*?</p>', out, re.S)
        # 找到第一条 meta（体积/架构那行）后插一行
        if m is None:
            die(f"{path}: 找不到 meta 行，电脑端说明没地方插")
        out = out[:m.end()] + nl + re.match(r"[ \t]*", m.group(0)).group(0) + line + out[m.end():]
        done.append(f"补上电脑端说明（{mb} MB）")
    else:
        out = re.sub(r'<p class="meta" data-' + TAG + r'>.*?</p>', line, out, flags=re.S)
        if out != src:
            done.append(f"电脑端说明 → {mb} MB")

    if out != src:
        write(path, out)
    return done


def git(*args: str) -> str:
    r = subprocess.run(["git", "-C", str(SITE), *args],
                       capture_output=True, text=True, encoding="utf-8", errors="replace")
    if r.returncode != 0:
        die(f"git {' '.join(args)} 失败：{r.stderr.strip()}")
    return r.stdout


def main() -> None:
    push = "--push" in sys.argv
    ver = version()

    if not EXE.is_file():
        die(f"没有产物：{EXE}（先跑 python tools/pack_pc.py --onefile）")

    target = SITE / f"ConMarn-{ver}-win64.exe"
    shutil.copyfile(EXE, target)
    size = target.stat().st_size
    print(f"exe  {size / 1e6:.1f} MB → {target.name}")

    for p in SITE.glob("ConMarn-*-win64.exe"):
        if p != target:
            print(f"删掉旧 exe {p.name}")
            p.unlink()

    for page, lang in ((SITE / "index.html", "zh"), (SITE / "en" / "index.html", "en")):
        if not page.is_file():
            print(f"跳过（没有这页）：{page}")
            continue
        changed = patch(page, ver, lang, size)
        print(f"页   {page.relative_to(SITE)}：" + ("；".join(changed) if changed else "无改动"))

    status = git("status", "--porcelain").strip()
    if not status:
        print("站点目录没有变化，收工。")
        return
    print("git 变更：\n" + status)
    if not push:
        print("\n没有 --push，只改了本地。")
        return

    git("add", "-A")
    git("commit", "-m", f"官网发布电脑端 v{ver}：新增 Windows 单文件 exe 下载")
    git("push", "origin", "gh-pages")
    print(f"\n已推送 gh-pages：电脑端 v{ver}")


if __name__ == "__main__":
    main()
