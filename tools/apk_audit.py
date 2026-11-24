#!/usr/bin/env python3
"""发版前拆包自查：这个 APK 里有没有不该出门的东西。

用法：

    python tools/apk_audit.py [路径.apk]

不给路径就查 `app/build/outputs/apk/release/app-release.apk`。

查四件事：

1. **签名与密钥材料**：`.jks` / `.p12` / `.pem` / `.env` / `keystore.properties`
   —— 打进包里等于把签名私钥一起公开。
2. **凭据**：`sk-` 开头的密钥、`Authorization: Bearer`、私钥头、`api_key=` 这类。
3. **个人与本机痕迹**：本机用户名、Windows 用户目录、电脑主机名、邮箱。
4. **大件与模型**：`.gguf` / `.onnx` / `.vrm` / 语音样本 —— 模型不随包分发。

★ 它只读不写，也不会修改 APK。命中不代表一定有问题（比如文案里出现 "token" 很正常），
  但每一条**都要落到人眼看过**才能勾。
"""
from __future__ import annotations

import re
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_APK = ROOT / "app" / "build" / "outputs" / "apk" / "release" / "app-release.apk"

SUSPICIOUS_NAME = re.compile(
    r"(?i)(keystore|\.jks$|\.p12$|\.pfx$|\.pem$|\.key$|secret|credential|password|"
    r"\.env$|id_rsa|google-services\.json)")
BIG_OR_ASSET = re.compile(r"(?i)\.(gguf|onnx|vrm|vrm|caffemodel|pt|pth|safetensors)$")

# 只扫这些「文本友好」的条目；dex 单独按原始字节扫一遍
TEXTUAL = re.compile(r"(?i)\.(json|xml|html|js|css|txt|md|yml|yaml|properties|ini|cfg|conf|csv|srt|vtt)$")

PATTERNS = [
    ("密钥前缀 sk-", re.compile(r"\bsk-[A-Za-z0-9_\-]{12,}")),
    ("Bearer 令牌", re.compile(r"(?i)bearer\s+[A-Za-z0-9._\-]{20,}")),
    ("私钥块", re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----")),
    ("密钥字段", re.compile(r"(?i)(api[_-]?key|apikey|access[_-]?key)\s*[=:]\s*[\"']?[A-Za-z0-9_\-]{12,}")),
    ("本机用户名", re.compile(r"(?i)C:\\\\?Users\\\\?[A-Za-z0-9_.\-]+")),
    ("Mac 家目录", re.compile(r"/Users/[A-Za-z0-9_.\-]+/")),
    ("电脑主机名", re.compile(r"\bLAPTOP-[A-Z0-9]{6,}\b")),
    ("邮箱", re.compile(r"\b[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}\b")),
]


def printable_ratio(s: str) -> float:
    if not s:
        return 0.0
    ok = sum(1 for c in s if " " <= c <= "~" or c in "一一" or "一" <= c <= "鿿")
    return ok / len(s)


def scan_text(where: str, text: str, findings: list[str]) -> None:
    for label, pat in PATTERNS:
        for m in pat.finditer(text):
            # 只截一小段上下文，避免把整份文件打到屏幕上
            s = max(0, m.start() - 40)
            snip = text[s:m.end() + 40].replace("\n", " ").replace("\r", " ")
            # ★ dex 是二进制，字符串池里夹着大量乱码。命中串本身和上下文都必须是
            #   可读文本才算数 —— 否则「sk-」会在随机字节里天天命中。
            if printable_ratio(m.group(0)) < 0.95 or printable_ratio(snip) < 0.85:
                continue
            findings.append(f"[{label}] {where}  …{snip}…")


def main() -> None:
    # 控制台是 GBK，命中串里可能带非 GBK 字符 —— 换成 UTF-8 并容错，否则一命中就崩
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass
    apk = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_APK
    if not apk.is_file():
        print(f"!! 没找到 {apk}")
        sys.exit(1)

    z = zipfile.ZipFile(apk)
    names = z.namelist()
    findings: list[str] = []

    print(f"包   {apk.name}  {apk.stat().st_size:,} 字节  {len(names)} 个条目")

    # 1) 文件名层面
    sus = [n for n in names if SUSPICIOUS_NAME.search(n)]
    big = [n for n in names if BIG_OR_ASSET.search(n)]
    print(f"\n[1] 密钥/凭据类文件名：{len(sus)} 个")
    for n in sus:
        print("    " + n)
    print(f"[4] 模型类大件：{len(big)} 个")
    for n in big:
        print("    " + n)

    # 顺手把最大的十条列出来 —— 73 MB 里到底装了什么，一眼看得见
    print("\n[5] 体积前十：")
    for i in sorted(z.infolist(), key=lambda i: -i.file_size)[:10]:
        print(f"    {i.file_size:>10,}  {i.filename}")

    # 2/3) 内容层面：文本条目逐条扫
    scanned = 0
    for n in names:
        if not TEXTUAL.search(n):
            continue
        try:
            data = z.read(n)
        except Exception:
            continue
        if len(data) > 4_000_000:
            continue
        scanned += 1
        scan_text(n, data.decode("utf-8", errors="ignore"), findings)
    print(f"\n[2][3] 扫了 {scanned} 个文本条目")

    # dex 也扫一遍原始字节 —— 硬编码的字面量都在这里，按 UTF-8 子串是能捞出来的
    for n in names:
        if n.startswith("classes") and n.endswith(".dex"):
            data = z.read(n)
            scan_text(n + "(原始字节)", data.decode("utf-8", errors="ignore"), findings)
            print(f"    扫过 {n}（{len(data):,} 字节）")

    print(f"\n命中 {len(findings)} 条：")
    seen = set()
    for f in findings:
        key = f[:160]
        if key in seen:
            continue
        seen.add(key)
        print("    " + f)
    if not findings:
        print("    （干净）")


if __name__ == "__main__":
    main()
