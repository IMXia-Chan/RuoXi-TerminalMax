#!/usr/bin/env bash
#
# 打包她的「房间」web 层,并拷进 APK 的 assets。
#
# ★★ 为什么必须有这个脚本 —— 2026-10-04 立的:
#
# 在这之前,这条路**全靠手**:源码在 `D:\her-build\web\src\her.js`,
# 手工 `esbuild` 打包,再手工把两个文件拷进 `app/src/main/assets/her/`。
# `package.json` 连 `scripts` 字段都没有。
#
# 手搓构建的失败样子非常难查:**改了源码、构建过了、装上了,手机上还是旧的**。
# 没有任何一步会报错 —— 因为每一步都「成功」了,只是没人做。
# 这个项目已经在别处吃过同族的亏(工具表抄三遍、前缀快照的 `n_saved=4096`
# 那道「每道弱闸都过得去」的假成功)。
#
# 所以:打包和拷贝**在同一条命令里**,而且末尾把产物指纹打出来 ——
# 让你能拿它和 APK 里那份对。
#
# 用法:
#   tools/build-her.sh            # 打包 + 拷进 assets
#   tools/build-her.sh --check    # 只比对,不改任何文件(验「手机上那份是不是最新的」)
#
set -euo pipefail

# 脚本在 <repo>/tools/ 下,所以 repo 根是它的上一级
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="D:/her-build/web"
DEST="$REPO/app/src/main/assets/her"

CHECK_ONLY=0
[ "${1:-}" = "--check" ] && CHECK_ONLY=1

die() { echo "✗ $*" >&2; exit 1; }
ok()  { echo "✓ $*"; }

[ -d "$SRC/src" ] || die "找不到她的源码目录:$SRC/src(D:\\her-build 那份)"
[ -f "$SRC/src/her.js" ] || die "找不到 $SRC/src/her.js"
[ -x "$SRC/node_modules/.bin/esbuild" ] || die "esbuild 不在。先 cd $SRC && npm install"

# ★ 2026-11-07:这台机**没有 node**(`.bin/esbuild` 是个 node 脚本,`exec: node: not found`)。
#   esbuild 包里另带一份**原生 exe**,有它就绕开 node 直接跑 —— 打包不需要 JS 运行时。
ESBUILD="$SRC/node_modules/.bin/esbuild"
if [ -x "$SRC/node_modules/@esbuild/win32-x64/esbuild.exe" ]; then
  ESBUILD="$SRC/node_modules/@esbuild/win32-x64/esbuild.exe"
fi

# ---- 1. 打包 ---------------------------------------------------------------
# 参数是**照现有那份 bundle 反推**的(IIFE + 压过),换了产物会不一样。
# target 定 es2020:她的页面跑在 Android WebView 里(本机 Android 17,Chrome 130+),
# 用不上更老的兼容,但 `?.` / `??` 这些语法要原样留着 —— 源码里到处都是。
if [ "$CHECK_ONLY" = "0" ]; then
  echo "· 打包 $SRC/src/her.js"
  ( cd "$SRC" && "$ESBUILD" src/her.js \
      --bundle --format=iife --minify --target=es2020 \
      --outfile=her.bundle.js --log-level=warning )
  ok "打包完成"
fi

# ---- 2. 拷贝 ---------------------------------------------------------------
# sample.vrm 有 10MB,**不在**每次拷贝之列(它几乎不变,重拷是白等)。
# 但如果它不在 assets 里,她出不了场 —— 那要说出来,不能静静跳过。
if [ "$CHECK_ONLY" = "0" ]; then
  cp "$SRC/her.bundle.js" "$DEST/her.bundle.js"
  cp "$SRC/index.html"    "$DEST/index.html"
  ok "拷进 $DEST"
fi

# ---- 3. 对账 ---------------------------------------------------------------
# ★ 这一步才是这个脚本存在的理由:把「我以为拷了」变成「字节数一样」。
sum() { sha256sum "$1" 2>/dev/null | cut -c1-16; }
sz()  { stat -c%s "$1" 2>/dev/null || echo "?"; }

for f in her.bundle.js index.html; do
  a="$SRC/$f"; b="$DEST/$f"
  [ -f "$b" ] || die "assets 里没有 $f —— 她加载不出来"
  if [ "$(sum "$a")" = "$(sum "$b")" ]; then
    ok "$f 一致 (sha256:$(sum "$a")…, $(sz "$b") 字节)"
  else
    die "$f **不一致** —— 源码 $(sz "$a") 字节,assets $(sz "$b") 字节。重跑不带 --check 的这次"
  fi
done

if [ -f "$DEST/sample.vrm" ]; then
  ok "sample.vrm 在 ($(sz "$DEST/sample.vrm") 字节)"
else
  echo "⚠ sample.vrm **不在** $DEST —— 她出不了场。从 $SRC/../vrm/sample.vrm 拷一份。" >&2
fi

echo
echo "下一步:出包 —— GRADLE_USER_HOME=/d/gradle \\"
echo "  /d/gradle/wrapper/dists/gradle-9.6.0-bin/42k10rwplmzkhuboz9kdazi7s/gradle-9.6.0/bin/gradle \\"
echo "  --rerun-tasks :app:assembleRelease"
