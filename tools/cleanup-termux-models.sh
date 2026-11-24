#!/usr/bin/env bash
# ───────────────────────────────────────────────────────────────
# 若息 · 清掉旧 Termux 方案留下的模型副本(约 5.4 GB)
#
# 三份同样的模型:
#   ① /sdcard/Download/ruoxi-ai/models   冷备份母本(回滚靠它)  → 保留
#   ② $HOME/models                       旧方案(Termux 跑服务) → 本脚本删
#   ③ /sdcard/Android/data/.../models    若息内嵌方案在用       → 保留
#
# 设计要点:**全程不看文件名,只认字节数**。
#   母本用 HuggingFace 原名、若息那份改成了 brain/eye/mmproj,
#   同字节不同名 —— 认名字必然翻车(已踩过)。
#   只删「与母本某一项字节数完全相同」的文件,路径猜错也不会误伤。
#
# 用法:  bash /sdcard/Download/ruoxi-ai/cleanup.sh
# ───────────────────────────────────────────────────────────────
set -u

MASTER="/sdcard/Download/ruoxi-ai/models"
APP="/sdcard/Android/data/com.example.touchpad/files/models"

# 三个模型的期望字节数 + 人类可读的名字(不依赖文件名)
SIZES=(2497281120 2497281664 453974304)
LABELS=("脑 Qwen3-4B" "眼 Qwen3VL-4B" "眼投影 mmproj")

say()  { printf '%s\n' "$*"; }
b2g()  { awk -v b="${1:-0}" 'BEGIN{printf "%.2f GB", b/1024/1024/1024}'; }
fsize(){ stat -c %s "$1" 2>/dev/null || echo 0; }

# 在某个目录里找「字节数等于 want」的文件,打印并回显路径;找不到回显空
find_by_size() {
    local dir="$1" want="$2" f
    for f in "$dir"/*.gguf; do
        [ -f "$f" ] || continue
        if [ "$(fsize "$f")" = "$want" ]; then printf '%s' "$f"; return 0; fi
    done
    return 1
}

say ""
say "══════════ 若息模型清理 ══════════"
say ""

for t in stat du awk find; do
    command -v "$t" >/dev/null 2>&1 || {
        say "缺少命令:$t   → 先跑: pkg install findutils coreutils gawk"; exit 1; }
done

# ── 前置:Termux 到底有没有存储权限 ────────────────────
if ! ls /sdcard/Download >/dev/null 2>&1; then
    say "Termux 读不到 /sdcard —— 还没授存储权限。"
    say "先跑:  termux-setup-storage   (弹窗点允许)"
    exit 1
fi

# ── 闸门:冷备份母本必须完好(唯一的回滚来源)─────────
say "① 体检:冷备份母本(万一出事靠它回滚)"
say "   $MASTER"
bad=0
for i in "${!SIZES[@]}"; do
    hit="$(find_by_size "$MASTER" "${SIZES[$i]}")"
    if [ -n "$hit" ]; then
        printf '   ✓ %-16s %s\n' "${LABELS[$i]}" "$(b2g "${SIZES[$i]}")"
    else
        printf '   ✗ %-16s 缺失(%s)\n' "${LABELS[$i]}" "${SIZES[$i]}"
        bad=1
    fi
done

if [ "$bad" != 0 ]; then
    say ""
    say "!! 母本不全,拒绝清理 —— 它是删掉旧副本后唯一的回滚来源。"
    if [ -z "$(ls -A "$MASTER" 2>/dev/null)" ]; then
        say "   (目录空的或读不到。先确认 $MASTER 存在)"
    else
        say "   目录里现有:"
        ls -la "$MASTER" 2>/dev/null | while IFS= read -r l; do printf '     %s\n' "$l"; done
    fi
    exit 1
fi
say "   → 母本三项俱全,可回滚。"

# ── 顺带看一眼若息在用的那份(读不到属正常)──────────
say ""
say "② 若息内嵌方案在用的那份(本脚本不碰它)"
app_bad=0
for i in "${!SIZES[@]}"; do
    if [ -n "$(find_by_size "$APP" "${SIZES[$i]}")" ]; then
        printf '   ✓ %-16s %s\n' "${LABELS[$i]}" "$(b2g "${SIZES[$i]}")"
    else
        app_bad=1
    fi
done
if [ "$app_bad" != 0 ]; then
    say "   ⓘ 读不到/不全 —— Android 11+ 作用域存储封了别的 App 的 Android/data,"
    say "     这是正常的,不是文件丢了。该份已从电脑侧 adb 核实过字节数。"
fi

# ── 扫描 $HOME 里的重复副本 ───────────────────────────
say ""
say "③ 扫描 \$HOME 下与母本一模一样的文件…"

before_kb="$(du -sk "$HOME" 2>/dev/null | awk '{print $1}')"; before_kb="${before_kb:-0}"

dup=""; total=0; n=0
while IFS= read -r path; do
    [ -n "$path" ] || continue
    sz="$(fsize "$path")"
    for want in "${SIZES[@]}"; do
        if [ "$sz" = "$want" ]; then
            printf '   找到 %-10s %s\n' "$(b2g "$sz")" "$path"
            dup="$dup$path"$'\n'; total=$((total + sz)); n=$((n + 1)); break
        fi
    done
done < <(find "$HOME" -type f -name '*.gguf' -size +100M 2>/dev/null)

if [ "$n" = 0 ]; then
    say "   没找到重复副本(可能已经清过了)。"
    say ""
    say "   \$HOME 里其余 ruoxi 相关的东西(本脚本不动):"
    find "$HOME" -maxdepth 2 \( -name 'start-*.sh' -o -name 'ruoxi*' \) 2>/dev/null \
        | while IFS= read -r p; do printf '     %s\n' "$p"; done
    say ""
    exit 0
fi

say ""
say "   共 $n 个文件,合计 $(b2g "$total")"

# ── 确认 ──────────────────────────────────────────────
say ""
say "   最后一道:若息 AI 现在能正常用吗?"
say "   能,就说明它在用的那份是好的 —— 敲 y 开删。"
say ""
printf '确认删除以上文件?[y/N] '
read -r ans
case "$ans" in
    y|Y|yes|YES) ;;
    *) say "已取消,什么都没删。"; exit 0 ;;
esac

freed=0
while IFS= read -r path; do
    [ -n "$path" ] || continue
    sz="$(fsize "$path")"
    if rm -f -- "$path" 2>/dev/null; then
        freed=$((freed + sz))
        printf '   已删 %s\n' "$path"
    else
        printf '   删不掉(权限?)%s\n' "$path"
    fi
done <<< "$dup"

rmdir "$HOME/models" "$HOME/ruoxi/models" 2>/dev/null

after_kb="$(du -sk "$HOME" 2>/dev/null | awk '{print $1}')"; after_kb="${after_kb:-0}"

say ""
say "══════════════ 完成 ══════════════"
say "  释放        $(b2g "$freed")"
say "  \$HOME       $(b2g $((before_kb * 1024))) → $(b2g $((after_kb * 1024)))"
say ""
say "  还剩两份完好副本:母本 + 若息在用那份。"
say ""
