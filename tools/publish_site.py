#!/usr/bin/env python3
"""官网发布：把新打的 release 包换到站点上，并把页面里的版本引用一并改掉。

用法（在仓库根目录）：

    python tools/publish_site.py          只改本地站点目录，不提交
    python tools/publish_site.py --push   改完提交并推送到 gh-pages

为什么要这个脚本：发版原本是手工拷包、手工改链接，而构建脚本还放在
`.claude/tmp` 里被清理过一次，等于每次发版都从零拼一遍。这里把四步固定下来：

    读版本 → 换包 → 改引用 → 提交推送

它**不重建站点**：页面结构一个字不动，只替换 APK 文件、版本号与构建日期。
重复执行是安全的（同一个版本跑两次，第二次什么都不改）。
"""
from __future__ import annotations

import datetime
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
GRADLE = ROOT / "app" / "build.gradle.kts"
APK = ROOT / "app" / "build" / "outputs" / "apk" / "release" / "app-release.apk"
SITE = Path(r"D:\conmarn-site-gh")

# 中文站 / 英文站各一句安装说明。★ 这一句是整件事的重点：用户习惯性地「先卸载再装新的」，
# 一卸载本机的模型、房间和配对就跟着没了 —— 那才会产生「像重装系统」的观感。
NOTE = {
    "zh": ('<p class="meta"><span>升级请直接覆盖安装，不要先卸载</span>'
           '<span>卸载会连同本机的模型、房间与配对数据一并删除</span></p>'),
    "en": ('<p class="meta"><span>Install over the current build — do not uninstall first</span>'
           '<span>Uninstalling also deletes the on-device model, room and pairing data</span></p>'),
}


def die(msg: str) -> "None":
    print("!! " + msg)
    sys.exit(1)


def version() -> tuple[str, int]:
    text = GRADLE.read_text(encoding="utf-8")
    name = re.search(r'versionName\s*=\s*"([^"]+)"', text)
    code = re.search(r"versionCode\s*=\s*(\d+)", text)
    if not name or not code:
        die("读不到 app/build.gradle.kts 里的版本号")
    return name.group(1), int(code.group(1))


def read(path: Path) -> str:
    # newline="" 保留原有的 CRLF，避免整份文件被改成 LF 而出现满屏 diff
    with path.open("r", encoding="utf-8", newline="") as f:
        return f.read()


def write(path: Path, text: str) -> None:
    with path.open("w", encoding="utf-8", newline="") as f:
        f.write(text)


def sync_size(html: str, size: int) -> str:
    """把页面上的安装包体积改成这次真实的大小。

    ★ 以前这里是手写的，发一次版就有一处对不上（规格栏写着上一版的字节数）。
      一律从构建产物现算：字节数按十进制 MB，与页面上原有写法一致。
    """
    mb = f"{size / 1e6:.1f}"
    html = re.sub(r"\d[\d,]* 字节（约 [\d.]+ MB）", f"{size:,} 字节（约 {mb} MB）", html)
    html = re.sub(r"\d[\d,]* bytes \(about [\d.]+ MB\)",
                  f"{size:,} bytes (about {mb} MB)", html)
    html = re.sub(r"(<span>)[\d.]+ MB(</span>)", rf"\g<1>{mb} MB\g<2>", html)
    html = re.sub(r"(安装包 )[\d.]+ MB", rf"\g<1>{mb} MB", html)
    html = re.sub(r"(The package is )[\d.]+ MB", rf"\g<1>{mb} MB", html)
    return html


def patch_page(path: Path, prefix: str, ver: str, lang: str, today: str,
               size: int) -> list[str]:
    """改一页：APK 链接、版本号、体积、架构图日期、安装说明。返回改了什么。"""
    src = read(path)
    out = src
    done: list[str] = []

    scaled = sync_size(out, size)
    if scaled != out:
        done.append(f"体积 → {size:,} 字节")
        out = scaled

    # 1) 下载链接：先记下当前的包名，再统一换成新版本（不限旧版本号，免得漏）
    old = sorted(set(re.findall(re.escape(prefix) + r"ConMarn-([0-9.]+)\.apk", out)))
    out = re.sub(re.escape(prefix) + r"ConMarn-[0-9.]+\.apk",
                 f"{prefix}ConMarn-{ver}.apk", out)
    if out != src:
        done.append("下载链接 " + ", ".join(old) + f" → {ver}")

    # 2) 导航栏那个版本角标
    new = re.sub(r'(<span class="ver">)[^<]*(</span>)', rf"\g<1>{ver}\g<2>", out)
    if new != out:
        done.append("版本角标 → " + ver)
        out = new

    # 3) 架构图下面的「V3.1 · 日期」
    new = re.sub(r"(<span>V)[0-9.]+( · )\d{4}-\d{2}-\d{2}(</span>)",
                 rf"\g<1>{ver}\g<2>{today}\g<3>", out)
    if new != out:
        done.append("架构图标注 → V" + ver + " · " + today)
        out = new

    # 4) 安装说明：只在没有的时候插一次，插在体积/架构那行后面
    if "升级请直接覆盖安装" not in out and "do not uninstall first" not in out:
        line = re.search(r"[ \t]*<p class=\"meta\"><span>\d+\.\d+ MB</span>.*?</p>", out)
        if line is None:
            die(f"{path.name}: 找不到体积/架构那一行，安装说明没地方插")
        indent = re.match(r"[ \t]*", line.group(0)).group(0)
        insert = line.group(0) + "\r\n" + indent + NOTE[lang] if "\r\n" in out \
            else line.group(0) + "\n" + indent + NOTE[lang]
        out = out[:line.end()] + insert[len(line.group(0)):] + out[line.end():]
        done.append("补上安装说明")

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
    ver, code = version()
    today = datetime.date.today().isoformat()

    if not APK.is_file():
        die(f"没有构建产物：{APK}（先跑 gradlew :app:assembleRelease）")
    if not (SITE / "index.html").is_file():
        die(f"站点目录不对：{SITE}")

    # ── 换包 ────────────────────────────────────────────────────────────────
    target = SITE / f"ConMarn-{ver}.apk"
    shutil.copyfile(APK, target)
    print(f"包   {APK.stat().st_size / 1e6:.1f} MB → {target.name}")

    olds = [p for p in SITE.glob("ConMarn-*.apk") if p != target]
    for p in olds:
        print(f"删掉旧包 {p.name}（{p.stat().st_size / 1e6:.1f} MB，git 里还有）")
        p.unlink()

    # ── 改引用 ──────────────────────────────────────────────────────────────
    for page, prefix, lang in ((SITE / "index.html", "./", "zh"),
                               (SITE / "en" / "index.html", "../", "en")):
        if not page.is_file():
            print(f"跳过（没有这页）：{page}")
            continue
        changed = patch_page(page, prefix, ver, lang, today, APK.stat().st_size)
        print(f"页   {page.relative_to(SITE)}：" + ("；".join(changed) if changed else "无改动"))

    # ── 提交推送 ────────────────────────────────────────────────────────────
    status = git("status", "--porcelain").strip()
    if not status:
        print("站点目录没有变化，收工。")
        return
    print("git 变更：\n" + status)
    if not push:
        print("\n没有 --push，只改了本地。确认无误后：python tools/publish_site.py --push")
        return

    git("add", "-A")
    git("commit", "-m", f"官网发布 v{ver}（versionCode {code}）：更新安装包，补充覆盖安装说明")
    git("push", "origin", "gh-pages")
    print(f"\n已推送 gh-pages：v{ver}")


if __name__ == "__main__":
    main()
