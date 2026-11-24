#!/usr/bin/env bash
# ───────────────────────────────────────────────────────────────
# 若息 · 存储体检(只读,不删任何东西)
#
# 查同一批模型在手机上到底存了几份、各占多大、哪份还在用。
#
# 用法:  bash /sdcard/Download/ruoxi-ai/scan.sh
# ───────────────────────────────────────────────────────────────
set -u

APP="/sdcard/Android/data/com.example.touchpad/files/models"
MASTER="/sdcard/Download/ruoxi-ai/models"

NAMES=(brain.gguf eye.gguf mmproj.gguf)
SIZES=(2497281120 2497281664 453974304)

say()  { printf '%s\n' "$*"; }
b2g()  { awk -v b="${1:-0}" 'BEGIN{printf "%.2f GB", b/1024/1024/1024}'; }
b2m()  { awk -v b="${1:-0}" 'BEGIN{printf "%.0f MB", b/1024/1024}'; }
fsize(){ stat -c %s "$1" 2>/dev/null || echo 0; }

say ""
say "════════ 若息存储体检(只读) ════════"
say ""

for t in find stat du awk; do
    command -v "$t" >/dev/null 2>&1 || {
        say "缺少命令:$t   → 先跑: pkg install findutils coreutils gawk"; exit 1; }
done

# ── ① 若息正在用的那份 ────────────────────────────────
say "① 若息内嵌方案在用的(必须留)"
say "   $APP"
ok=0
for i in "${!NAMES[@]}"; do
    f="$APP/${NAMES[$i]}"
    got="$(fsize "$f")"
    if [ "$got" = "${SIZES[$i]}" ]; then
        printf '   ✓ %-42s %s\n' "${NAMES[$i]}" "$(b2g "$got")"; ok=$((ok+1))
    elif [ "$got" = 0 ]; then
        printf '   ✗ %-42s 不存在\n' "${NAMES[$i]}"
    else
        printf '   ! %-42s %s(与母本不一致)\n' "${NAMES[$i]}" "$(b2g "$got")"
    fi
done
[ "$ok" = 3 ] && say "   → 三个都齐,若息本体完好。"

# ── ② 母本 ────────────────────────────────────────────
say ""
say "② 母本/setup.sh 的源(建议留作冷备份)"
say "   $MASTER"
mok=0
for i in "${!NAMES[@]}"; do
    f="$MASTER/${NAMES[$i]}"
    got="$(fsize "$f")"
    [ "$got" = "${SIZES[$i]}" ] && { printf '   ✓ %-42s %s\n' "${NAMES[$i]}" "$(b2g "$got")"; mok=$((mok+1)); }
done
say "   共 $mok/3 个文件完整,合计 $(b2g "$(du -sb "$MASTER" 2>/dev/null | awk '{print $1}')")"

# ── ③ Termux 内部:重复副本 ───────────────────────────
say ""
say "③ Termux 内部 \$HOME 下的重复副本(旧方案遗留)"
say "   $HOME"

before_kb="$(du -sk "$HOME" 2>/dev/null | awk '{print $1}')"; before_kb="${before_kb:-0}"
say "   \$HOME 当前总计 $(b2g $((before_kb * 1024)))"
say ""

dup=""; total=0; n=0
while IFS= read -r path; do
    [ -n "$path" ] || continue
    sz="$(fsize "$path")"
    for want in "${SIZES[@]}"; do
        if [ "$sz" = "$want" ]; then
            printf '   ● %-11s %s\n' "$(b2g "$sz")" "$path"
            dup="$dup$path"$'\n'; total=$((total + sz)); n=$((n + 1)); break
        fi
    done
done < <(find "$HOME" -type f -name '*.gguf' -size +100M 2>/dev/null)

say ""
if [ "$n" -gt 0 ]; then
    say "   → 与正在用的那份**逐字节相同**的副本:$n 个,合计 $(b2g "$total")"
    say "     这些是旧 Termux 方案留下的,内嵌方案上线后已无人读取。"
else
    say "   → 没找到重复副本。"
fi

# ── ④ 其它零碎 ────────────────────────────────────────
say ""
say "④ 其它 ruoxi 相关(占得不多,仅供参考)"
for d in /sdcard/ruoxi-llama "$HOME" ; do
    find "$d" -maxdepth 1 \( -name 'start-*.sh' -o -name 'ruoxi*' -o -name '*.so' \) 2>/dev/null \
        | while IFS= read -r p; do printf '   %s\n' "$p"; done
done

# ── 结论 ──────────────────────────────────────────────
say ""
say "════════════════ 结论 ════════════════"
if [ "$n" -gt 0 ]; then
    say "可回收:$(b2g "$total")(Termux 里的重复副本)"
    say ""
    say "确认无误后,自己删(本脚本不动手):"
    say ""
    while IFS= read -r p; do [ -n "$p" ] && say "   rm -f \"$p\""; done <<< "$dup"
    say "   rmdir \"\$HOME/models\" 2>/dev/null"
else
    say "无需清理。"
fi
say ""
