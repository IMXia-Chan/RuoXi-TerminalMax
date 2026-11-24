# -*- coding: utf-8 -*-
"""一次性：统计工程规模，供计划书/PRD 引用（只数数，不改任何东西）。"""
import os

ROOT = r"C:\Users\27014\phone-touchpad"

GROUPS = {
    "安卓端 Kotlin 主代码": ("app/src/main/java", (".kt",)),
    "安卓端 Kotlin 测试": ("app/src/test/java", (".kt",)),
    "电脑端服务 Python": ("pc-server", (".py",)),
    "房间网页层 her-src": ("her-src", (".js", ".ts", ".html", ".css", ".json")),
}

SKIP = {".git", ".gradle", "build", "node_modules", "__pycache__", "dist",
        "dist-onefile", "build-onefile", ".idea", ".kotlin"}


def count(rel, exts):
    files = lines = 0
    base0 = os.path.join(ROOT, rel)
    for base, dirs, names in os.walk(base0):
        dirs[:] = [d for d in dirs if d not in SKIP]
        for n in names:
            if os.path.splitext(n)[1].lower() in exts:
                p = os.path.join(base, n)
                try:
                    with open(p, "r", encoding="utf-8", errors="ignore") as f:
                        lines += sum(1 for _ in f)
                except OSError:
                    continue
                files += 1
    return files, lines


total_f = total_l = 0
for label, (rel, exts) in GROUPS.items():
    f, l = count(rel, exts)
    total_f += f
    total_l += l
    print("%-24s %4d 个文件  %6d 行" % (label, f, l))
print("%-24s %4d 个文件  %6d 行" % ("合计", total_f, total_l))

apk = os.path.join(ROOT, "app", "build", "outputs", "apk", "release", "app-release.apk")
if os.path.isfile(apk):
    print("release APK  %.1f MB" % (os.path.getsize(apk) / 1e6))
