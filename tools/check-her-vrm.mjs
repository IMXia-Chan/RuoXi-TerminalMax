// ============================================================================
// 「这个 .vrm 她认不认」—— 在**电脑上**先验一遍,不用等真机。
//
// ★ 为什么要有它(2026-10-06 晚,他刚用 VRoid Studio 做完第一个形象):
//
//   她身上那些「会动」的东西,全是**按名字**去要的:
//
//     em.setValue('happy', 0.8)      ← 笑
//     em.setValue('aa', 1.0)         ← 张嘴(口型)
//     vrm.lookAt.target = …          ← 眼睛跟着你
//
//   **名字对不上, setValue 不会报错, 只是什么都不发生。**
//   于是症状是「她面无表情」「嘴不动」—— 而那和「模型没加载出来」在屏幕上
//   长得一模一样。这正是这个项目最恨的那种失败:**静默的**。
//
//   所以判定必须在电脑上咬死。跑一次,缺哪个名字当场列出来。
//
// ★ 它**不**加载 three.js(那要一堆 DOM 壳)。它直接读 GLB 容器里那份 JSON ——
//   而表情名 / 骨骼名 / 版本号**全在那份 JSON 里**,一个字节都不少。
//   (这样做还有个好处:读的是**文件里真写了什么**,不是"我以为它会写什么"。)
//
// 跑法:
//   node phone-touchpad/tools/check-her-vrm.mjs <文件.vrm>
//   node phone-touchpad/tools/check-her-vrm.mjs            ← 不给就找桌面上的
// ============================================================================

import { readFileSync, readdirSync, existsSync, statSync } from 'node:fs';
import { join } from 'node:path';

// ---------------------------------------------------------------------------
// 一、她要的**全部**名字 —— 这份清单从 her.js 里抄下来的,不是我想的
//
//   her.js:1125  情绪六项(E 循环里逐个 setValue)
//   her.js:1134  blink 单独驱动
//   her.js:1139  口型五项
//   her.js:307   lookAt(vrm.lookAt 存在才会去用)
// ---------------------------------------------------------------------------
const WANTS = {
  情绪: ['happy', 'sad', 'angry', 'relaxed', 'surprised', 'neutral'],
  眨眼: ['blink'],
  口型: ['aa', 'ih', 'ou', 'ee', 'oh'],
};

// ---------------------------------------------------------------------------
// 二、VRM 0.x → 1.0 的名字翻译表
//
// ★★ 这张表**不是我编的**,是从**真会跑的那份库**里抄的:
//     node_modules/@pixiv/three-vrm-core/lib/three-vrm-core.module.js:1025
//     (变量名 v0v1PresetNameMap)
//
//   为什么必须有它:VRoid Studio 默认导出的是 **VRM 0.x**,而 0.x 的那套名字
//   和 1.0 **不一样** —— 它叫 joy / sorrow / fun / a / i / u / e / o。
//   如果不去查这张表,看到 "joy" 就会判「她笑不了」,而实际上 three-vrm
//   在加载时已经把它登记成 happy 了。**那是量具的错,不是模型的错。**
//
//   ★ 反过来说:表里**没有**白名单之外的东西 —— 凡是 0.x 里不存在的名字,
//     翻译表也救不了。下面 `不在表里` 那一档要单独说。
// ---------------------------------------------------------------------------
const V0V1 = {
  a: 'aa', e: 'ee', i: 'ih', o: 'oh', u: 'ou',
  blink: 'blink', blink_l: 'blinkLeft', blink_r: 'blinkRight',
  joy: 'happy', angry: 'angry', sorrow: 'sad', fun: 'relaxed',
  neutral: 'neutral',
  lookup: 'lookUp', lookdown: 'lookDown',
  lookleft: 'lookLeft', lookright: 'lookRight',
};

// ---------------------------------------------------------------------------
// 三、GLB:容器是 12 字节头 + 一串块。第一块一定是 JSON。
// ---------------------------------------------------------------------------
function readGlbJson(path) {
  const buf = readFileSync(path);
  if (buf.length < 20) throw new Error('文件太小了,不像 glb/vrm');
  const magic = buf.readUInt32LE(0);
  if (magic !== 0x46546c67) {
    // 0x46546c67 = "glTF" 小端
    throw new Error('不是 glb/vrm(头四个字节不是 glTF)。' +
      '★ 如果这是 VRoid 的工程文件(.vroid),那它是个 zip —— ' +
      '要去 Studio 里「导出为 VRM」,存盘那个用不了');
  }
  const total = buf.readUInt32LE(8);
  let off = 12;
  while (off + 8 <= buf.length) {
    const len = buf.readUInt32LE(off);
    const type = buf.readUInt32LE(off + 4);
    const data = buf.subarray(off + 8, off + 8 + len);
    if (type === 0x4e4f534a) {          // "JSON"
      return { json: JSON.parse(data.toString('utf8')), fileBytes: buf.length, declaredBytes: total };
    }
    off += 8 + len;
  }
  throw new Error('glb 里没找到 JSON 块');
}

// ---------------------------------------------------------------------------
// 四、把「这个文件里实际登记了哪些名字」翻出来(两种版本各读各的)
// ---------------------------------------------------------------------------
function readExpressions(json) {
  const ext = json.extensions || {};
  const v1 = ext.VRMC_vrm;
  const v0 = ext.VRM;

  if (v1) {
    const preset = (v1.expressions && v1.expressions.preset) || {};
    const custom = (v1.expressions && v1.expressions.custom) || {};
    return {
      version: '1.0',
      names: [...Object.keys(preset), ...Object.keys(custom)],
      presetNames: Object.keys(preset),
      customNames: Object.keys(custom),
      meta: v1.meta || {},
      humanBones: v1.humanoid ? Object.keys(v1.humanoid.humanBones || {}) : [],
      lookAt: v1.lookAt || null,
      raw: v1,
    };
  }
  if (v0) {
    const groups = ((v0.blendShapeMaster || {}).blendShapeGroups) || [];
    // ★ 0.x 的规矩:presetName 在翻译表里 → 用翻译后的名字;
    //   不在表里(unknown,或者 VRoid 自己起的名字)→ three-vrm 拿 `name` 当名字。
    const names = [];
    const detail = [];
    for (const g of groups) {
      const preset = String(g.presetName || '');
      const mapped = V0V1[preset.toLowerCase()];
      const finalName = mapped || String(g.name || '');
      names.push(finalName);
      detail.push({ presetName: preset, name: g.name, as: finalName, mapped: !!mapped });
    }
    return {
      version: '0.x',
      names,
      presetNames: groups.map((g) => String(g.presetName || '')),
      customNames: detail.filter((d) => !d.mapped).map((d) => d.name),
      detail,
      meta: v0.meta || {},
      humanBones: (v0.humanoid && v0.humanoid.humanBones || []).map((b) => b.bone),
      lookAt: null,
      raw: v0,
    };
  }
  throw new Error('这个文件里既没有 VRM 也没有 VRMC_vrm 扩展 —— 它可能只是个普通 glb');
}

// ---------------------------------------------------------------------------
// 四·五、★★ 这个名字**背后有没有东西** —— 光有名字不够
//
//   ★ 为什么必须有这一条(2026-10-06 晚):上面那张清单只证明「名字在」。
//     而 VRM 里一个表情**可以**是个空壳 —— 名字登记了、morphTargetBinds 却是空数组。
//     那时 setValue('happy', 1) **一路成功、什么都不动**,屏幕上是**一张不笑的脸**。
//     ★ 它的症状和「模型没加载出来」在屏幕上长得一模一样,而这一条能把它挑出来。
//     典型来路:在 VRoid Studio 里动过表情那一栏、或把预设里的形变删干净了。
//
//   一个表情的「载荷」有三种,任何一种非空都算它真会动:
//     morphTargetBinds(形变,最常见的) / materialColorBinds(变色) / textureTransformBinds(贴图位移)
// ---------------------------------------------------------------------------
function bindsOf(expr, name) {
  if (expr.version === '1.0') {
    const ex = (expr.raw.expressions || {});
    const e = (ex.preset && ex.preset[name]) || (ex.custom && ex.custom[name]);
    if (!e) return 0;
    return (e.morphTargetBinds || []).length
      + (e.materialColorBinds || []).length
      + (e.textureTransformBinds || []).length;
  }
  // 0.x:按「翻译之后的名字」去找那一组,数它的 binds
  const groups = ((expr.raw.blendShapeMaster || {}).blendShapeGroups) || [];
  let n = 0;
  for (const g of groups) {
    const mapped = V0V1[String(g.presetName || '').toLowerCase()];
    const finalName = mapped || String(g.name || '');
    if (finalName === name) n += (g.binds || []).length;
  }
  return n;
}

// ---------------------------------------------------------------------------
// 五、找文件:不给参数就找桌面上的 .vrm
// ---------------------------------------------------------------------------
function findTarget() {
  const arg = process.argv[2];
  if (arg) return arg;
  const desk = join(process.env.USERPROFILE || 'C:/Users/27014', 'Desktop');
  if (!existsSync(desk)) return null;
  const hits = readdirSync(desk).filter((f) => f.toLowerCase().endsWith('.vrm'));
  return hits.length ? join(desk, hits[0]) : null;
}

// ---------------------------------------------------------------------------
// 六、跑
// ---------------------------------------------------------------------------
const fails = [];
function check(name, cond, extra) {
  if (cond) { console.log('  ✓ ' + name); return true; }
  fails.push(name);
  console.log('  ✗ ' + name + (extra ? '\n        ← ' + extra : ''));
  return false;
}

const target = findTarget();
if (!target) {
  console.log('桌面上没有 .vrm。');
  console.log('在 VRoid Studio 里走「导出」→ 导出为 VRM,丢在桌面就行。');
  process.exit(1);
}

console.log('\n文件:' + target);
const { json, fileBytes } = readGlbJson(target);
const expr = readExpressions(json);

console.log('体积:' + (fileBytes / 1048576).toFixed(2) + ' MB');
console.log('格式:VRM ' + expr.version);
console.log('名字:' + (expr.meta.title || expr.meta.name || '(没写)'));
console.log('作者:' + (expr.meta.author || (expr.meta.authors || []).join(', ') || '(没写)'));

console.log('\n【1】她要的每个名字,这个文件里有没有 —— ★ 而且**名字背后有没有东西**');
for (const [group, names] of Object.entries(WANTS)) {
  for (const n of names) {
    if (!check(group + ' · ' + n, expr.names.includes(n),
      '没这个表情。这个文件里有的是:' + expr.names.join(', '))) continue;
    // ★★ 名字在,还得有载荷。空壳 = setValue 成功、脸不动、零报错。
    const nb = bindsOf(expr, n);
    check('    └ 它会动吗(' + nb + ' 个形变)', nb > 0,
      '★ 名字在,但它是个**空壳** —— 里面一个形变都没有。' +
      'setValue 会「成功」而脸一动不动,而且不报错。' +
      '去 VRoid Studio 检查这个表情那一栏是不是被清空了,重新导一次');
  }
}

console.log('\n【2】文件里实际登记的全部表情名');
console.log('  ' + expr.names.join(', ') || '  (一个都没有)');
if (expr.version === '0.x') {
  console.log('\n  0.x 的原始 presetName → 加载时被翻译成什么:');
  for (const d of expr.detail || []) {
    console.log('    ' + (d.presetName || '(空)').padEnd(12) + ' → ' + d.as +
      (d.mapped ? '' : '   ← ★ 没在翻译表里,靠它自己的名字'));
  }
}

console.log('\n【3】骨骼(她呼吸 / 眨眼 / 看向你 要靠这些)');
// her.js 要动的:头、脖子、脊(呼吸)、髋,以及 lookAt 要的眼睛。
const NEED_BONES = ['head', 'neck', 'spine', 'hips'];
const hasBones = expr.humanBones || [];
for (const b of NEED_BONES) {
  check('骨骼 · ' + b, hasBones.includes(b), '这个文件里的骨骼:' + hasBones.join(', '));
}
console.log('  (骨骼总数 ' + hasBones.length + ')');

console.log('\n【4】眼睛能不能跟着你(her.js:307 只在 vrm.lookAt 存在时才去用)');
if (expr.version === '1.0') {
  check('有 lookAt 设置', !!expr.lookAt,
    '没有 lookAt → 她不会看你,而且是静默的(那一行 if 直接跳过)');
} else {
  // ★ 0.x 没有 lookAt 扩展。three-vrm 会**自己建一个** ——
  //   条件是眼睛骨骼成对存在。这一条因此要换个判据问。
  check('眼睛骨骼成对(leftEye / rightEye)',
    hasBones.includes('leftEye') && hasBones.includes('rightEye'),
    '0.x 靠这对骨骼自建 lookAt;缺了就没有视线跟随。这个文件里的骨骼:' + hasBones.join(', '));
}

console.log('\n【5】贴图(她要打进 APK,体积是账)');
const imgs = json.images || [];
let binBytes = 0;
for (const bv of json.bufferViews || []) binBytes += bv.byteLength || 0;
console.log('  贴图 ' + imgs.length + ' 张');
for (const im of imgs) {
  if (im.bufferView != null) {
    const bv = json.bufferViews[im.bufferView];
    console.log('    ' + (im.mimeType || '?') + '  ' +
      ((bv && bv.byteLength ? bv.byteLength : 0) / 1048576).toFixed(2) + ' MB');
  } else if (im.uri) {
    console.log('    ' + (im.uri.startsWith('data:') ? '(内嵌)' : im.uri));
  }
}
console.log('  现在这份 sample.vrm 是 10.7 MB —— 那是要打进 APK 的数,拿它比。');

console.log('\n' + (fails.length
  ? '✗ ' + fails.length + ' 条没过:\n  - ' + fails.join('\n  - ')
  : '✓ 她认得这份身体:要的每个名字都在'));
process.exit(fails.length ? 1 : 0);
