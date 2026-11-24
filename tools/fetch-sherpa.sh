#!/usr/bin/env bash
#
# 把 sherpa-onnx 的 native 库和 Kotlin 源码装进项目。这是「她的耳朵」E1。
#
# ★★ 为什么必须有这个脚本 —— 2026-10-04 晚立的:
#
# 和 `build-her.sh` 同一个理由:**手搓 vendor 的失败样子是静默的**。
# 少拷一个 `.so`、漏一个 `.kt`,当场的表现都是「编译过了」——
# 直到运行期某个 `external fun` 抛 `UnsatisfiedLinkError`,而那时的症状
# 长得像「她听不见」,和 KWS 没命中、阈值太紧、麦克风被占**完全分不开**。
# 这个项目已经在别处吃过同族的亏(`test_hit_probe.py` 那次拿用户自己的浏览器当靶子,
# 三次「实测结论」里错了两次)。
#
# 所以:下载、抽取、拷贝、**打印指纹**在同一条命令里,而且可重复跑。
#
# ★ 上游锁死在 v1.13.8。换版本要连 `.so` 和 22 个 `.kt` **一起换** ——
#   JNI 是按字段名对到 native 结构体上的,错位的表现是首次 native 调用才炸。
#
# 用法:
#   tools/fetch-sherpa.sh            # 下载 + 装进项目
#   tools/fetch-sherpa.sh --check    # 只核对已装的那份对不对,不改任何文件
#
set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TAG="v1.13.8"
TARBALL="sherpa-onnx-${TAG}-android.tar.bz2"
URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/${TAG}/${TARBALL}"
# 模型和 native 库都不进仓库,所以暂存在仓库外面。
STAGE="/c/Users/27014/voice-lab/_vendor"

JNI_DIR="$REPO/app/src/main/jniLibs/arm64-v8a"
KT_DIR="$REPO/app/src/main/java/com/k2fsa/sherpa/onnx"

CHECK_ONLY=0
[ "${1:-}" = "--check" ] && CHECK_ONLY=1

die() { echo "✗ $*" >&2; exit 1; }
ok()  { echo "✓ $*"; }
sum() { sha256sum "$1" 2>/dev/null | cut -c1-16; }
sz()  { stat -c%s "$1" 2>/dev/null || echo "?"; }

# ★ 只要这两个。官方包里的 README.md 明说:
#   c-api / cxx-api 那两个是给**不用 JNI** 的人,拷了白胖 3.6MB。
#
# ★ 而且实测过了:它们**不需要 libomp.so,也不需要 libc++_shared.so** ——
#   `llvm-readelf -d` 出来的 NEEDED 只有 libandroid/liblog/libm/libdl/libc
#   加我们自己带的 libonnxruntime.so。所以和 llama 那份 libomp 不冲突。
LIBS=(libsherpa-onnx-jni.so libonnxruntime.so)

# ---------------------------------------------------------------------------
# --check:只核对,不碰任何文件
# ---------------------------------------------------------------------------
if [ "$CHECK_ONLY" = "1" ]; then
  echo "== 核对已装的 sherpa($TAG) =="
  bad=0
  for f in "${LIBS[@]}"; do
    if [ -f "$JNI_DIR/$f" ]; then
      ok "$f 在 ($(sz "$JNI_DIR/$f") 字节, sha256:$(sum "$JNI_DIR/$f")…)"
    else
      echo "✗ $f **不在** $JNI_DIR —— 耳朵加载不起来"; bad=1
    fi
  done
  n=$(ls -1 "$KT_DIR"/*.kt 2>/dev/null | wc -l)
  if [ "$n" -ge 22 ]; then
    ok "Kotlin 源码 $n 个在 $KT_DIR"
  else
    echo "✗ Kotlin 源码只有 $n 个(上游是 22 个)—— 手挑漏了会在运行期炸"; bad=1
  fi
  echo
  if [ "$bad" = "0" ]; then ok "全部就位"; exit 0; fi
  die "有缺,跑一次不带 --check 的"
fi

# ---------------------------------------------------------------------------
# 1. 下载
# ---------------------------------------------------------------------------
# ★ 走 tools/dl.sh —— 它只在**真的停滞**时中断(不是 --max-time 那种总时长),
#   并且下完按 Content-Length 对账。2026-10-04 那次 TTS 六个模型全失败就是
#   栽在 `--max-time` 上,详见那个脚本的文件头。
mkdir -p "$STAGE" "$JNI_DIR" "$KT_DIR" || die "建不出目录"

echo "· 下载 $TARBALL"
"$REPO/tools/dl.sh" "$URL" "$STAGE/$TARBALL" || die "下载失败"

# ---------------------------------------------------------------------------
# 2. 抽取 .so
# ---------------------------------------------------------------------------
echo "· 抽取 arm64-v8a 的 .so"
TMP="$(mktemp -d)" || die "建不出临时目录"
trap 'rm -rf "$TMP"' EXIT
tar -xjf "$STAGE/$TARBALL" -C "$TMP" ./jniLibs/arm64-v8a 2>/dev/null \
  || die "解包失败 —— 包可能被截断(校验字节数)"

for f in "${LIBS[@]}"; do
  src="$TMP/jniLibs/arm64-v8a/$f"
  [ -f "$src" ] || die "包里没有 $f —— 上游结构变了,别猜,去看 $STAGE/$TARBALL 的目录"
  cp "$src" "$JNI_DIR/$f" || die "拷不了 $f"
done
ok "两个 .so 就位"

# ---------------------------------------------------------------------------
# 3. 取 Kotlin 源码
# ---------------------------------------------------------------------------
# ★ **整个 kotlin-api/ 目录都拿,不挑。** 原计划写「约 9 个」,实测有 22 个,
#   而 `OfflineRecognizer.kt` 一个就 56KB。手挑漏一个引用要等运行期才炸,
#   全拿一共约 132KB,编译代价可以忽略。
# ★ 包名 `com.k2fsa.sherpa.onnx` **一个字都不能改** —— JNI 按类名注册 native 方法。
echo "· 取 Kotlin 源码(整个 kotlin-api/)"
export ALL_PROXY="${ALL_PROXY:-socks5h://127.0.0.1:10808}"
FILES=$(gh api "repos/k2-fsa/sherpa-onnx/contents/sherpa-onnx/kotlin-api?ref=$TAG" \
        --jq '.[] | select(.name|endswith(".kt")) | .name' 2>/dev/null)
[ -n "$FILES" ] || die "列不出上游文件(gh 没登录?代理没起?)"

for f in $FILES; do
  curl -sSL --speed-limit 8192 --speed-time 60 \
    -o "$KT_DIR/$f" \
    "https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/$TAG/sherpa-onnx/kotlin-api/$f" \
    || die "取 $f 失败"
  [ -s "$KT_DIR/$f" ] || die "$f 是空的"
done
n=$(echo "$FILES" | wc -l)
ok "$n 个 .kt 就位(共 $(du -sk "$KT_DIR" | cut -f1) KB)"

# ---------------------------------------------------------------------------
# 4. 对账 + 指纹
# ---------------------------------------------------------------------------
# ★ 这一步才是这个脚本存在的理由:把「我以为拷了」变成「字节数一样」。
echo
echo "== 装进来的东西 =="
for f in "${LIBS[@]}"; do
  printf "  %-28s %10s 字节  sha256:%s…\n" "$f" "$(sz "$JNI_DIR/$f")" "$(sum "$JNI_DIR/$f")"
done
printf "  %-28s %10s 个\n" "kotlin-api/*.kt" "$n"

echo
echo "== 和 llama 的 libomp 有没有冲突 =="
# 这条是**验过**的结论,不是推测:见本文件开头。
if grep -qa "libomp.so" "$JNI_DIR/libsherpa-onnx-jni.so" 2>/dev/null; then
  echo "⚠ jni.so 里出现了 libomp.so —— 和 llama 那份同名文件会撞。重新查 NEEDED。" >&2
else
  ok "sherpa 不引用 libomp/libc++_shared(和 llama 那份 1,229,304 字节的不冲突)"
fi

echo
# ★ 下面用单引号 —— 双引号里的反引号会被 bash 当命令替换**执行掉**,
#   症状是脚本「成功退出」但多打两行 `xxx: command not found`。
#   (2026-10-04 自己踩的:每一步都报成功、输出却是错的,正是这个项目最怕的那种。)
echo '下一步:接 E2(EarMic.kt / Ear.kt),然后在真机上看到这一行 ——'
echo "  model.log:  耳: 原生库加载成功 ver=…"
