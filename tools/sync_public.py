# -*- coding: utf-8 -*-
"""把工作树同步到开源快照 `D:\\conmarn-src`（远端 IMXia-Chan/ConMarn.AI）。

用法（仓库根目录）：

    python tools/sync_public.py            只同步，不提交
    python tools/sync_public.py --commit   同步 + 提交（不推）

★★ 这个脚本**只做加法**：把源码目录里的文件覆盖到快照里，**从不删除**快照里的东西。
   「删」是发布决定，要人看着做，不能由脚本顺手办。

★★ 硬性排除（一个都不能进公开仓库）：
   签名私钥 `*.jks` / `keystore.properties`、本机路径 `local.properties`、
   配对密钥 `secret.json` / `master.json`、含明文配对码的 `server.log`、
   模型与语音样本、构建产物、`__pycache__`。
"""
import filecmp
import os
import shutil
import sys

SRC = r"C:\Users\27014\phone-touchpad"
DST = r"D:\conmarn-src"

INCLUDE_DIRS = ["app/src", "eval", "her-src", "pc-server", "tools", "gradle"]
INCLUDE_FILES = [
    "app/build.gradle.kts", "app/proguard-rules.pro",
    "build.gradle.kts", "settings.gradle.kts", "gradle.properties",
    "gradlew", "gradlew.bat",
    "README.md", "LICENSE", "NOTICE", "THIRD_PARTY.md", "ARCHITECTURE.md",
    "ruoxi-terminal.ico",
]

SKIP_DIR_NAMES = {".git", ".gradle", ".kotlin", "__pycache__", "build", "dist",
                  "dist-onefile", "build-onefile", ".idea", ".claude", "node_modules",
                  ".venv",          # 构建缓存
                  "archive", "wx-preview"}   # tools 下的历史存档与临时预览
SKIP_EXT = {".apk", ".jks", ".keystore", ".bin", ".gguf", ".onnx", ".pth",
            ".safetensors", ".log", ".vrm", ".vrmx", ".zip", ".exe",
            # 调试产物：截图、PyInstaller 中间表、评测落盘
            ".png", ".jpg", ".jpeg", ".gif", ".webp", ".fst",
            ".out", ".jsonl", ".toc", ".pyc"}
SKIP_NAMES = {"secret.json", "master.json", "keystore.properties", "local.properties",
              "server.log", "fake_llm.log", "restart_log.txt", "model.log"}
MAX_BYTES = 8_000_000      # 单文件超过 8 MB 的一律先问人，不偷偷带上去


def skip(path: str, rel: str = "") -> str:
    name = os.path.basename(path)
    if name in SKIP_NAMES:
        return "排除名单"
    rel_u = rel.replace("\\", "/")
    # ★ 电脑端界面资源（含 demo-qr.png）本身就是产品的一部分，按扩展名排除对它们不适用
    if not rel_u.startswith("pc-server/app_web/"):
        if os.path.splitext(name)[1].lower() in SKIP_EXT:
            return "按扩展名排除"
    # ★ tools/ 与 eval/ 下 `_` 开头的是我自己的一次性探针/评测落盘，不属于产品
    if rel_u.split("/")[0] in ("tools", "eval") and name.startswith("_"):
        return "一次性探针"
    # ★ tools/ 根目录的 .html 都是界面预览稿（产品页在 pc-server/app_web/）
    if rel_u.startswith("tools/") and rel_u.count("/") == 1 and name.lower().endswith(".html"):
        return "预览稿"
    try:
        size = os.path.getsize(path)
    except OSError:
        return "读不到"
    if size > MAX_BYTES:
        return "超过 8 MB"
    return ""


def main():
    do_commit = "--commit" in sys.argv
    added, updated, same, skipped = [], [], 0, []

    def take(rel):
        nonlocal same
        src = os.path.join(SRC, rel.replace("/", os.sep))
        dst = os.path.join(DST, rel.replace("/", os.sep))
        why = skip(src, rel)
        if why:
            skipped.append((rel, why))
            return
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        if os.path.exists(dst) and filecmp.cmp(src, dst, shallow=False):
            same += 1
            return
        (updated if os.path.exists(dst) else added).append(rel)
        shutil.copyfile(src, dst)

    for d in INCLUDE_DIRS:
        root = os.path.join(SRC, d)
        for base, dirs, files in os.walk(root):
            dirs[:] = [x for x in dirs if x not in SKIP_DIR_NAMES]
            for f in files:
                full = os.path.join(base, f)
                take(os.path.relpath(full, SRC))
    for f in INCLUDE_FILES:
        full = os.path.join(SRC, f)
        if os.path.isfile(full):
            take(f)

    print(f"新增 {len(added)}  更新 {len(updated)}  未变 {same}  跳过 {len(skipped)}")
    for rel in added:
        print("  + " + rel)
    for rel in updated:
        print("  ~ " + rel)
    if skipped and "-v" in sys.argv:
        for rel, why in skipped:
            print(f"  - {rel}  ({why})")

    if not do_commit:
        print("\n没有 --commit，只同步了文件；提交请自己看一遍再走。")
        return 0

    import subprocess
    r = subprocess.run(["git", "-C", DST, "add", "-A"])
    if r.returncode != 0:
        return r.returncode
    r = subprocess.run(["git", "-C", DST, "status", "--short"], capture_output=True, text=True)
    print("\n" + r.stdout.strip())
    r = subprocess.run(["git", "-C", DST, "commit", "-m",
                        "同步 2026-11-08：体检修复、多任务令牌、电脑端 App、发版工具"])
    return r.returncode


if __name__ == "__main__":
    sys.exit(main())
