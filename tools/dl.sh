#!/usr/bin/env bash
#
# 一个「不会假装成功」的下载器。
#
# ★★ 为什么必须有这个脚本 —— 2026-10-04 晚立的:
#
# 那天 TTS 那批六个模型,**每一个都是「重试三次全失败」**。查下来不是网络断了,
# 是脚本用错了 curl 的超时语义:
#
#     curl --max-time 2400        # ← 这是**总时长**,不是**停滞时长**
#
# 这台机器实测最快只有 ~190 KB/s(SOCKS 代理;直连 34 KB/s,ghproxy 39 KB/s)。
# 一个 117 MB 的包要跑十几分钟,中间抖一下,这个参数到点就杀 —— 而 `-C -` 续传
# 又接不上,于是三次重试全废。
#
# 正确的写法是**只在真的停滞时才中断**:
#     --speed-limit 8192 --speed-time 60     # 慢于 8KB/s 持续 60 秒才算死
# 这样「慢但在爬」永远不会被杀,「卡住了」才会退出来重试。
#
# 另一件事:**下载完必须对账**。这个项目已经吃过太多次「每一步都成功、结果不对」
# 的亏(工具表抄三遍、前缀快照的 n_saved=4096 每道弱闸都过得去)。所以:
#   · 期望字节数从服务器自己那儿问(--size,按 Content-Length)
#   · 下完比对,不一致就报错退出,**绝不交给下一步**
#
# 用法:
#   tools/dl.sh <url> <目标文件> [期望字节数]
#   tools/dl.sh --size <url>              # 只问大小,不下载
#
# 路径可以是 github 的 release 地址;脚本会按下面的顺序挑源。
#
set -uo pipefail

SOCKS="socks5h://127.0.0.1:10808"
MIRRORS=("" "https://ghproxy.net/" "https://gh-proxy.com/")

die() { echo "✗ $*" >&2; exit 1; }
ok()  { echo "✓ $*"; }

# 期望字节数:跟着重定向走到最后一条 Content-Length。
# -I 只取头,所以再大的包也是秒回。
ask_size() {
  curl -sIL --max-time 30 --socks5-hostname 127.0.0.1:10808 "$1" 2>/dev/null \
    | tr -d '\r' | awk 'tolower($1)=="content-length:"{n=$2} END{print n+0}'
}

if [ "${1:-}" = "--size" ]; then
  [ $# -ge 2 ] || die "用法:tools/dl.sh --size <url>"
  ask_size "$2"
  exit 0
fi

[ $# -ge 2 ] || die "用法:tools/dl.sh <url> <目标文件> [期望字节数]"
URL="$1"; DEST="$2"; WANT="${3:-0}"

mkdir -p "$(dirname "$DEST")" || die "建不出目录 $(dirname "$DEST")"

# 期望字节数没给就自己问 —— **不问就没法对账**,那这个脚本就白写了。
if [ "$WANT" = "0" ] || [ -z "$WANT" ]; then
  WANT="$(ask_size "$URL")"
  [ "$WANT" -gt 0 ] 2>/dev/null || die "问不到 $(basename "$URL") 的大小 —— 连接或地址有问题"
fi

# 已经到了就不动(幂等:这个脚本会被反复跑)
if [ -f "$DEST" ] && [ "$(stat -c%s "$DEST" 2>/dev/null || echo 0)" = "$WANT" ]; then
  ok "已存在且大小对:$(basename "$DEST") ($WANT 字节)"
  exit 0
fi

PART="$DEST.part"
TARGET_KB=$(( WANT / 1024 ))

# ★ 外层循环是**必须的**:单次 curl 撞上停滞会退出,但文件已经下了大半,
#   下一轮从断点接着爬。所以「失败几次」在这里是正常路径,不是异常。
for round in $(seq 1 40); do
  for base in "${MIRRORS[@]}"; do
    have=$(( $(stat -c%s "$PART" 2>/dev/null || echo 0) / 1024 ))
    printf "\r  %s  %d/%d KB (第%d轮 %s)   " \
      "$(basename "$DEST")" "$have" "$TARGET_KB" "$round" "${base:-直连+代理}"

    # ★ --speed-limit/--speed-time 是这个脚本存在的理由,见文件头。
    #    没有 --max-time:慢但在爬的下载**不该被杀**。
    curl -sL -C - \
      --socks5-hostname 127.0.0.1:10808 \
      --speed-limit 8192 --speed-time 60 \
      --retry 0 \
      -o "$PART" "${base}${URL}" 2>/dev/null

    now=$(stat -c%s "$PART" 2>/dev/null || echo 0)
    if [ "$now" = "$WANT" ]; then
      printf "\r%*s\r" 70 ""
      mv "$PART" "$DEST" || die "改名失败 $PART → $DEST"
      ok "$(basename "$DEST"):$WANT 字节,对账一致"
      exit 0
    fi
  done
done

printf "\r%*s\r" 70 ""
die "$(basename "$DEST") 下了 40 轮还差 $(( (WANT - $(stat -c%s "$PART" 2>/dev/null || echo 0)) / 1024 )) KB。留着 $PART 下次续传。"
