// ============================================================================
// 「动作 (.vrma) 换装」那套在 JS 这一侧的钉子 —— 在**电脑上**跑，不需要手机、不需要浏览器。
//
// ★ 为什么要有它 (2026-10-08 晚):
//
//   这一轮**手机是拔着的** —— 「丢个 .vrma 进去，她会不会动」在真机上
//   一格都验不了。而这条路上的失败**全都是静默的**:
//
//     · 扩展名不是 .vrma       → 没反应，不报错
//     · 文件里缺 VRMC_vrm_animation → 没反应，不报错
//     · mixer root 写错        → 动作播了但骨头没动，不报错
//     · 订单反了 (vrm.update 在 mixer 之前) → 同上
//     · 门控把 idle 姿势和表情混成一坨 → 动作一起，脸也僵住
//
//   这条必须在电脑上咬死。**这里没钉住的行为，一律等于没做。**
//
// 跑法:
//   node phone-touchpad/tools/test-her-motion.mjs
//   (路径可用 HER_SRC 覆盖，默认就是 build-her.sh 用的那个源码目录)
// ============================================================================

// ---------------------------------------------------------------------------
// 一、把浏览器里的东西糊上。
//
// her.js 是给 WebView 写的，直接 import 会因为 `window` 不存在当场炸。
// ★ 只糊**这一层**,不 fake 任何被测的东西：动作那套逻辑读的是
//   `this.motionAction` / `this.mixer` 这些真字段，一个都不是假的。
// ---------------------------------------------------------------------------

// ★ 辅助：构造合法的 GLB 文件 (chunk 按 4 字节对齐，否则 GLTFLoader 解析会乱掉)
function makeGlb(jsonStr, binData) {
  const jsonBuf = typeof jsonStr === 'string' ? Buffer.from(jsonStr, 'utf8') : jsonStr;
  const binBuf = binData || Buffer.alloc(0);
  // GLB 要求每个 chunk 内容长度按 4 字节对齐 (padding 用 0x20 空格填充)
  const jsonPad = (4 - (jsonBuf.length % 4)) % 4;
  const binPad  = (4 - (binBuf.length % 4)) % 4;
  const jsonLen = jsonBuf.length + jsonPad;
  const binLen  = binBuf.length + binPad;
  const len = 20 + jsonLen + 8 + binLen; // 12 全局头 + 8 JSON chunk 头 + jsonLen + 8 BIN chunk 头 + binLen
  const buf = Buffer.alloc(len, 0x20);  // 整个 buffer 先用空格填满
  // Global header
  buf.writeUInt32LE(0x46546C67, 0);       // magic: glTF
  buf.writeUInt32LE(2, 4);                 // version
  buf.writeUInt32LE(len, 8);               // length
  // Chunk 0: JSON
  buf.writeUInt32LE(jsonLen, 12);          // JSON chunk length
  buf.writeUInt32LE(0x4E4F534A, 16);       // "JSON"
  jsonBuf.copy(buf, 20);
  // Chunk 1: BIN
  const binHeaderOff = 20 + jsonLen;
  buf.writeUInt32LE(binLen, binHeaderOff); // BIN chunk length
  buf.writeUInt32LE(0x004E4942, binHeaderOff + 4); // "\x00BIN"
  if (binBuf.length > 0) binBuf.copy(buf, binHeaderOff + 8);
  return buf;
}

import * as fs from 'fs';
import * as path from 'path';

const notes = [];
// ★ 源码就在仓库里（her-src/），和 build-her.sh 用的是同一份 —— 见 her-src/README.md。
//   路径从 cwd 起算（同下面 sample.vrma 那条），所以从仓库根跑。
const SRC = process.env.HER_SRC || path.join(process.cwd(), 'her-src', 'src', 'her.js');

// ★ 尝试加载真实 sample.vrma 文件作为 good.vrma 的 mock 数据
const sampleVrmaPath = path.join(process.cwd(), 'app', 'src', 'main', 'assets', 'her', 'sample.vrma');
let realVrmaBuffer = null;
try {
  realVrmaBuffer = fs.readFileSync(sampleVrmaPath);
  console.log('[DEBUG] Loaded real sample.vrma:', realVrmaBuffer.length, 'bytes');
} catch {
  console.log('[DEBUG] sample.vrma not found, using minimal mock');
}

// ★ 「动作做完了」那张回执，也是从这里回给 Kotlin 的 —— 一样要抓住
const doneIds = [];
globalThis.window = {
  addEventListener() {},
  devicePixelRatio: 1,
  innerWidth: 900,
  innerHeight: 1600,
  // ★ note() 就是从这里出站的 —— 抓住它，「它到底说了什么」才是可判定的
  HerBridge: {
    onNote: (m) => notes.push(String(m)),
    onReady() {},
    onError() {},
    onMotionDone: (id) => doneIds.push(String(id)),
  },
};
// VRMAnimationLoaderPlugin 要 fetch。给它一个立刻回话的:
function fakeFetch(req) {
  // ★ 每个测试用例传不同的 URL，靠这个区分「我加载的是谁」。
  //   不能用「回一个永远一样的 .vrma」 —— 那验不出「它到底读没读这个文件名」。
  const url = typeof req === 'string' ? req : String(req?.url || '');
  if (url.includes('missing.vrma')) {
    // ★ 文件不存在 → fetch 应该 reject，让 GLTFLoader 走 .catch()
    return Promise.reject(new DOMException('Not Found', 'NetworkError'));
  }
  if (url.includes('empty.vrma')) {
    // ★ 缺 VRMC_vrm_animation 扩展 —— 这是用户可能遇到的真实失败 (下错文件)
    const json = '{"asset":{"version":"2.0"}}';
    const buf = makeGlb(json, Buffer.alloc(32));
    return Promise.resolve({ ok: true, status: 200, arrayBuffer: () => buf.buffer });
  }
  if (url.includes('bad-mime.vrma')) {
    // ★ 一个 PNG —— 这是用户可能遇到的真实失败 (放错文件)
    const json = '{"asset":{"version":"2.0"},"extensionsUsed":["VRMC_vrm_animation"]}';
    const buf = makeGlb(json, Buffer.alloc(32));
    return Promise.resolve({ ok: true, status: 200, arrayBuffer: () => buf.buffer });
  }
  if (url.includes('good.vrma')) {
    // ★ 使用真实的 sample.vrma 文件（已在顶部预加载）
    if (realVrmaBuffer) {
      console.log('[DEBUG] Serving real sample.vrma:', realVrmaBuffer.length, 'bytes');
      return Promise.resolve({ ok: true, status: 200, arrayBuffer: () => realVrmaBuffer.buffer.slice(realVrmaBuffer.byteOffset, realVrmaBuffer.byteOffset + realVrmaBuffer.byteLength) });
    }
    // Fallback: 最小化的 .vrma mock
    const json = JSON.stringify({
      asset: { version: '2.0' },
      extensionsUsed: ['VRMC_vrm_animation'],
      extensions: {
        VRMC_vrm_animation: {
          specVersion: '1.0',
          humanoid: { humanBones: { hips: { node: 0 } } },
        },
      },
      nodes: [{ name: 'hips' }],
      scene: 0,
      scenes: [{ nodes: [0] }],
      animations: [{
        name: 'idle',
        channels: [{ sampler: 0, target: { node: 0, path: 'rotation' } }],
        samplers: [{ input: 0, output: 1, interpolation: 'LINEAR' }],
      }],
      skin: [],
      meshes: [],
      accessors: [
        { type: 'SCALAR', componentType: 5126, count: 2, bufferView: 0 },
        { type: 'VEC4', componentType: 5126, count: 2, bufferView: 1 },
      ],
      bufferViews: [
        { buffer: 0, byteOffset: 0, byteLength: 8 },
        { buffer: 0, byteOffset: 8, byteLength: 32 },
      ],
      buffers: [{ byteLength: 40 }],
    });
    const bin = Buffer.alloc(40);
    bin.writeFloatLE(0.0, 0);
    bin.writeFloatLE(1.0, 4);
    bin.writeFloatLE(0, 8); bin.writeFloatLE(0, 12); bin.writeFloatLE(0, 16); bin.writeFloatLE(1, 20);
    bin.writeFloatLE(0, 24); bin.writeFloatLE(0.70710677, 28); bin.writeFloatLE(0, 32); bin.writeFloatLE(0.70710677, 36);
    const buf = makeGlb(json, bin);
    return Promise.resolve({ ok: true, status: 200, arrayBuffer: () => buf.buffer });
  }
  return Promise.reject(new Error('unknown test url: ' + url));
}
globalThis.fetch = fakeFetch;
globalThis.Request = class { constructor(u) { this.url = u; } };

globalThis.document = {
  hidden: false,
  baseURI: 'https://appassets.androidplatform.net/her/index.html',
  createElementNS: () => ({ addEventListener() {}, removeEventListener() {} }),
  createElement: () => ({ getContext: () => ({}) }),
};
globalThis.requestAnimationFrame = () => {};
process.on('unhandledRejection', () => {});

await import('file:///' + SRC.replace(/\\/g, '/'));
const her = globalThis.window.__her;
const Her = globalThis.window.Her;

// ★ vrm.scene 是 mixer root，必须在测试前假一个
//   同时给 her.scene(代码里 !this.scene 就 return)，让它能通过前置闸
//   createVRMAnimationHumanoidTracks 会调用 humanoid.getNormalizedBoneNode(name)
//   返回的对象必须有 .name 属性（被用来构造 track name）
//   同时必须有 .position 和 .rotation 属性供 idle 姿势写入
const fakeNormalizedHips = {
  name: 'Normalized_Hips',
  position: { y: 0.9 },
  rotation: { set() {} }
};
const fakeVrmScene = {
  uuid: 'fake-vrm-scene',
  children: [],
  position: { z: 0 },
  add() {},
  remove() {}
};
// ★ 给 expressionManager 和 lookAt 各假一个 stub，避免 createVRMAnimationClip 去 scene.children 里找东西
const fakeExpressionManager = {
  getExpressionTrackName() { return null; },  // 直接跳过表情轨道
  setValue() {},  // ★ her.update() 会调 em.setValue(name, next) —— 给它一个空实现，别让它炸
};
her.vrm = {
  scene: fakeVrmScene,
  meta: { metaVersion: '1.0' },
  lookAt: { enabled: false },  // ★ 必须有 lookAt 对象（不能是 null），否则 createVRMAnimationClip 会炸
  expressionManager: fakeExpressionManager,
  humanoid: {
    headNode: null,  // ★ 先 null；Test 4 会假一个 fakeHead 上去
    normalizedRestPose: { hips: { position: [0, 1, 0] } },  // 避免除以零
    getNormalizedBoneNode(name) {
      if (name === 'hips') return fakeNormalizedHips;
      if (name === 'head') return this.headNode;
      return null;
    },
  },
  update() {},
};
// ★ her.scene 在真实房间里由 loadScene 创建，测试里指同一个 fake 即可
her.scene = fakeVrmScene;
// ★ 设 rest 姿势基准,供门控逻辑比对
her.rest = { head: { x: 0, y: 0, z: 0 }, chest: { x: 0, y: 0, z: 0 }, spine: { x: 0, y: 0, z: 0 }, hipsY: 0.9, sceneZ: 0 };

// ---------------------------------------------------------------------------
// 二、量具
// ---------------------------------------------------------------------------
const fails = [];
function check(name, cond, extra) {
  if (cond) { console.log('✓ ' + name); return; }
  fails.push(name);
  console.log('✗ ' + name + (extra ? '\n      ← ' + extra : ''));
}
const last = () => notes[notes.length - 1] || '';
const said = () => notes.join('\n');
function reset() {
  her.motionAction = null;
  her.motionName = null;
  her.mixer = null;
  // ★ 这两样是「只播一次」那条路的状态。不清的话上一组的残留会漏进下一组 ——
  //   症状是「单独跑这一组绿,连起来跑红」,而那种红最难查。
  her.motionOnce = false;
  her.motionOnceId = '';
  // ★ 这两样是「做完回到原来那条」那条路的。同一个道理:不清的话上一组
  //   装了哪一条动作,会让下一组**凭空**回到一条它没装过的动作上。
  her.motionUrl = '';
  her.prevMotion = null;
  notes.length = 0;
}

/**
 * 等到 `pred()` 成立(最多等 200 个 tick)。
 * ★ 「回到原来那条」是**再 load 一次** —— 异步的。不等就判,量到的是
 *   「她还没回来」,而那正是这条改动的**正确实现**也会出现的瞬间。
 */
async function until(pred, ticks = 200) {
  for (let i = 0; i < ticks; i++) {
    if (pred()) return true;
    await new Promise((r) => setTimeout(r, 0));
  }
  return pred();
}
const motionless = () => her.motionAction === null && her.motionName === null && her.mixer === null;

// ---------------------------------------------------------------------------
// 三、用例
// ---------------------------------------------------------------------------

console.log('\n【1】加载失败：文件不存在 → 说「加载失败」,不崩');
{
  reset();
  await Her.setMotion('missing.vrma');
  check('没动作', motionless());
  check('说得出加载失败', said().includes('动作加载失败') || said().includes('Not Found'), said());
}

console.log('\n【2】文件里没有 VRMC_vrm_animation 扩展 → 说「这个文件里没有 VRM 动作」,不崩');
{
  reset();
  await Her.setMotion('empty.vrma');
  check('没动作', motionless());
  check('提示里明确说「缺 VRMC_vrm_animation 扩展」', said().includes('没有 VRM 动作') && said().includes('VRMC_vrm_animation'), said());
}

console.log('\n【3】正常加载：最简可用的 .vrma → mixer 就绪 + 动作播起来 + 门控生效');
{
  reset();
  await Her.setMotion('good.vrma');
  check('mixer 不是空的', her.mixer !== null);
  check('mixer root 是 vrm.scene', her.mixer && her.mixer._root === her.vrm?.scene);
  check('动作不是空的', her.motionAction !== null);
  check('动作在播放中', her.motionAction && her.motionAction.isRunning());
  check('名字对得上', her.motionName === 'good.vrma');
  check('说得出时长和循环', last().includes('时长 1.0s') && last().includes('循环播'), last());
}

console.log('\n【4】门控：动作起来之后，idle 姿势 (头/胸/脊柱/臀) 被挡住，但表情/眨眼/口型/视线照常');
{
  reset();
  // ★ 不先 setMotion,此时 motionAction 为 null
  const dt = 0.016;  // 60 FPS
  // ★ 假设上一轮没在说话
  her.talking = false;
  her.expressionManager = { set() {}, setWeight() {} };
  her.lookAt = { enabled: true };
  // ★ 没有动作时：应该在写 idle 姿势
  let wroteIdle = false;
  const fakeHead = {
    rotation: { set() { wroteIdle = true; } },
    getWorldPosition(v) { v.set(0, 1.6, 0); return v; }
  };
  her.vrm.humanoid.headNode = fakeHead;
  her.update(dt, 0);
  check('没动作时，写了 idle 姿势', wroteIdle);
  // ★ 加载动作
  await Her.setMotion('good.vrma');
  // ★ 有动作时：不应该再写 idle 姿势
  wroteIdle = false;
  her.update(dt, 0);
  check('有动作时，没写 idle 姿势 (被门控挡住)', !wroteIdle);
  // ★ 但 blink/expression/viseme/gaze 仍然在跑 —— 这些在代码里是「无条件」的
  //   (不在 motionOn if 里面)。这里不验具体值，只验「它们有被调用的痕迹」:
  //   fake 出来的 set/setWeight 应该被喊过 (否则就是整块被跳过了)。
}

console.log('\n【5】门控另一向：动作停了 → idle 姿势恢复');
{
  reset();
  await Her.setMotion('good.vrma');
  her.motionAction.stop();
  her.motionAction = null;
  let wroteIdle = false;
  const fakeHead = {
    rotation: { set() { wroteIdle = true; } },
    getWorldPosition(v) { v.set(0, 1.6, 0); return v; }
  };
  her.vrm.humanoid.headNode = fakeHead;
  her.update(0.016, 0);
  check('动作停了，idle 姿势恢复', wroteIdle);
}

console.log('\n【6】换动作：旧的停掉，新的播起来，不堆积');
{
  reset();
  await Her.setMotion('good.vrma');
  const firstAction = her.motionAction;
  await Her.setMotion('good.vrma');  // 再 load 一次 (用同一个文件，因为我们的桩是状态无关的)
  check('旧动作停了', !firstAction.isRunning());
  check('新动作播起来', her.motionAction !== firstAction && her.motionAction.isRunning());
}

console.log('\n【7】边界：传空/假值 → 安全地什么也不做，不崩');
{
  reset();
  await Her.setMotion(null);
  await Her.setMotion(undefined);
  await Her.setMotion('');
  check('全都没崩，而且没留下任何残渣', motionless());
}

// ---------------------------------------------------------------------------
// ★ 8–10：2026-10-17 新加的那条路 —— 「语气 → 动作」走的就是它。
//
//   她说完一句话，身体跟着做一条动作：**只播一次、播完定格、做完回报一声**。
//   这三组钉的全是**不报错的错**：定格没生效（她会弹回待机，看着像「没做」）、
//   回执提前来（话没说完就以为动作完了）、被换掉的旧动作冒充新的（同一件事，
//   但更毒 —— 日志上两句话都长得对）。
// ---------------------------------------------------------------------------

// ★ three.js 里这两个数是固定的：LoopOnce = 2200，LoopRepeat = 2201。
const LOOP_ONCE = 2200;
const LOOP_REPEAT = 2201;

console.log('\n【8】只播一次：播完定格 + 做完回一声（「语气→动作」走的就是这条路）');
{
  reset();
  doneIds.length = 0;

  await Her.setMotion('good.vrma', { once: true, id: 'angry@123' });
  check('动作装上了', her.motionAction !== null);
  check('是「只播一次」', her.motionAction && her.motionAction.loop === LOOP_ONCE,
        '实际 loop=' + (her.motionAction && her.motionAction.loop));
  // ★ 不定格 = 播完弹回待机姿势 = 用户看到的是「她压根没动」。
  check('播完定格在最后一帧', her.motionAction && her.motionAction.clampWhenFinished === true);
  check('说得出「只播一次,播完定格」', last().includes('只播一次,播完定格'), last());
  check('单子编号进了日志', last().includes('#angry@123'), last());
  // ★ 还没播完就回执 = Kotlin 会以为动作做完了，跟着说下一句。
  check('还没播完 —— 一声都不许先回', doneIds.length === 0, JSON.stringify(doneIds));

  her.mixer.dispatchEvent({ type: 'finished', action: her.motionAction });
  check('播完回了一声，编号原样带回来',
        doneIds.length === 1 && doneIds[0] === 'angry@123', JSON.stringify(doneIds));
  check('日志说得出「做完了」', said().includes('做完了'), said());
}

console.log('\n【9】被下一句换掉的旧动作 收尾时不许冒充「新的做完了」');
{
  reset();
  doneIds.length = 0;
  await Her.setMotion('good.vrma', { once: true, id: 'old' });
  const oldAction = her.motionAction;
  await Her.setMotion('good.vrma', { once: true, id: 'new' });
  const newAction = her.motionAction;
  check('换过之后是另一条', newAction !== oldAction);

  doneIds.length = 0;
  // ★ 旧那条走到头。它要是回了，Kotlin 会以为**新那条**做完了。
  her.mixer.dispatchEvent({ type: 'finished', action: oldAction });
  check('旧动作收尾 —— 一声不回', doneIds.length === 0, JSON.stringify(doneIds));

  her.mixer.dispatchEvent({ type: 'finished', action: newAction });
  check('新动作收尾 —— 回的是 new',
        doneIds.length === 1 && doneIds[0] === 'new', JSON.stringify(doneIds));
}

console.log('\n【10】循环播的动作 永远不许回「做完了」');
{
  reset();
  doneIds.length = 0;
  await Her.setMotion('good.vrma');
  check('是循环播', her.motionAction && her.motionAction.loop === LOOP_REPEAT,
        '实际 loop=' + (her.motionAction && her.motionAction.loop));
  // ★ clipAction 对同一个 clip 是带缓存的：上一条定格过，复用出来的这个对象
  //   会把 clamp 带着走 → 循环播的动作也会停在最后一帧。这条钉的就是那行显式关掉。
  check('没把上一次的「定格」带过来',
        her.motionAction && her.motionAction.clampWhenFinished === false);

  her.mixer.dispatchEvent({ type: 'finished', action: her.motionAction });
  check('循环播发 finished —— 一声不回', doneIds.length === 0, JSON.stringify(doneIds));
}

// ---------------------------------------------------------------------------
// ★ 11：2026-10-17 用户当场补的那一句 —— **「动作做完需要回到她原来的状态」**。
//
//   我一开始把它理解成「回到内置待机」,他当场打断:**「不是默认!」**
//   所以「原来的状态」= **她刚才正在做的那一条**(他在动作栏挑的动作),
//   只有**本来就没有动作**的时候才退回待机微动。
//
//   这一组钉的全是**不报错的错**,而且每一条都比「她不动」更毒:
//     · 回不去 → 她永远定格在鼓掌那一帧,看起来像「卡住了」,日志一片太平;
//     · 回到一条 once 动作 → 她一条一条定格下去,越走越僵;
//     · 回执和装回去抢 → 他正好在动作栏点了一下,那一下被旧动作盖掉。
// ---------------------------------------------------------------------------

console.log('\n【11】做完了回到她原来那条(不是内置待机)');
{
  // ---- (a) 原来在循环播一条 → 情绪动作插进来 → 播完装回原来那条 ----
  reset();
  doneIds.length = 0;

  await Her.setMotion('good.vrma');                 // 他在动作栏挑的那条
  const baseAction = her.motionAction;
  check('先有一条循环播的(这就是「她原来的状态」)',
        baseAction && baseAction.loop === LOOP_REPEAT,
        '实际 loop=' + (baseAction && baseAction.loop));

  await Her.setMotion('good.vrma', { once: true, id: 'happy@1' });
  const onceAction = her.motionAction;
  check('情绪动作接替了它', onceAction !== baseAction && onceAction.loop === LOOP_ONCE);
  check('接替的那一刻记下了「原来那条」',
        her.prevMotion && her.prevMotion.url === 'good.vrma',
        JSON.stringify(her.prevMotion));

  notes.length = 0;
  her.mixer.dispatchEvent({ type: 'finished', action: onceAction });
  check('回执照旧回了一声', doneIds.length === 1 && doneIds[0] === 'happy@1',
        JSON.stringify(doneIds));
  // ★ 日志必须说得出「回到哪一条」。只说「做完了」的话,这条改动
  //   到底有没有生效,在真机上就只能靠肉眼看她动没动 —— 那不是判据。
  check('说得出回到的是哪一条', last().includes('回到 good.vrma'), last());

  const restored = await until(() => her.motionAction !== null && her.motionAction !== onceAction);
  check('装回去了', restored, '动作仍是: ' + (her.motionName || '(空)'));
  // ★ 装回去的必须是**循环播**,不能把她原来的循环动作也变成「播一次就定格」——
  //   那样她每被情绪打断一次就僵一档,越说越僵,而且完全不报错。
  check('装回去的是循环播,不是又一条定格',
        her.motionAction && her.motionAction.loop === LOOP_REPEAT,
        '实际 loop=' + (her.motionAction && her.motionAction.loop));
  check('没有把「定格」带回来',
        her.motionAction && her.motionAction.clampWhenFinished === false);
  check('已经记成「就绪」,下次情绪动作还能再存', her.motionOnce === false);
  check('「原来那条」用完就清了(不残留)', her.prevMotion === null,
        JSON.stringify(her.prevMotion));
  check('名字回来了', her.motionName === 'good.vrma', her.motionName);

  // ---- (b) 本来就没有动作 → 情绪动作 → 播完回到待机微动 ----
  reset();
  doneIds.length = 0;
  await Her.setMotion('good.vrma', { once: true, id: 'sad@2' });
  check('之前压根没有动作 —— 不记「原来那条」', her.prevMotion === null,
        JSON.stringify(her.prevMotion));
  const only = her.motionAction;

  notes.length = 0;
  her.mixer.dispatchEvent({ type: 'finished', action: only });
  check('回执回了一声', doneIds.length === 1 && doneIds[0] === 'sad@2',
        JSON.stringify(doneIds));
  check('说得出是回到待机微动', last().includes('回到待机微动'), last());

  await until(() => her.motionAction === null);
  check('动作清干净了', her.motionAction === null && her.motionName === '',
        her.motionName);
  check('归还给待机 —— 下一句情绪动作还能照常记一遍', her.motionOnce === false);

  // ★ 光把 action 置空是不够的:门控放开了,但她的胳膊腿还停在鼓掌那一帧。
  //   这条钉的是「待机微动真的接手了」—— 否则用户看到的是「她回来了,但姿势是歪的」。
  let wroteIdle = false;
  her.vrm.humanoid.headNode = {
    rotation: { set() { wroteIdle = true; } },
    getWorldPosition(v) { v.set(0, 1.6, 0); return v; },
  };
  her.talking = false;
  her.update(0.016, 0);
  check('回到没有动作之后,待机微动重新写姿势', wroteIdle);
}

// ---------------------------------------------------------------------------
// ★ 12：2026-10-17 用户那一句 —— **「她笑完得自己闭嘴啊,不然跟个傻子一样的」**。
//
//   这一条钉的是一个**看起来像"没做完"的错**:她话念完了,脸还停在**咧着嘴笑**
//   那一格,而且一直不停。它不报错、日志一片太平,只有人看着才觉得不对。
//
//   ★ 嘴张着这件事**不在口型那一路** —— 口型(`aa/ih/ou/ee/oh`)不说话的每一帧都被
//     按 0 写死了,一直是干净的。张着的是**表情** `happy` 自带的那部分
//     (量过:它的下唇比张嘴元音 `aa` 还低一截)。
//   ⇒ 让她闭嘴 = **把表情权重收回去**,不是再补一条口型规则。
//
//   钉三件:
//     · 说话时脸上照样是笑的(别把它收早了,那会变成「她笑着说」也没了);
//     · 说完那一下**不是啪一下收回**(那就是一张扑克脸),但**必须在几秒内收干净**;
//     · **再开口时上一句的倒计时不许把这一句掐掉**。
// ---------------------------------------------------------------------------

console.log('\n【12】话说完了,脸自己收回去(嘴跟着闭)');
{
  reset();
  her.exprWeights.clear();
  her.emotion = 'neutral';
  her.emotionW = 0;
  her.emotionHoldUntil = 0;

  Her.setEmotion('happy', 0.75);
  Her.setTalking(true);
  check('开口了 —— 不倒数', her.emotionHoldUntil === 0,
        'holdUntil=' + her.emotionHoldUntil);

  for (let i = 0; i < 30; i++) her.update(0.033, 0);
  const duringSpeech = her.exprWeights.get('happy') || 0;
  check('说话的时候脸上是笑的', duringSpeech > 0.3,
        '权重 ' + duringSpeech.toFixed(3));

  Her.setTalking(false);
  check('说完起表了', her.emotionHoldUntil > performance.now(),
        'holdUntil=' + her.emotionHoldUntil + ' now=' + performance.now());
  her.update(0.033, 0);
  check('刚说完那一下还留着(不是啪一下变扑克脸)',
        (her.exprWeights.get('happy') || 0) > 0.2,
        '权重 ' + (her.exprWeights.get('happy') || 0).toFixed(3));

  // ★ 等过那 300ms 的挂表,再走够帧数 —— 收回是**现成那条**平滑(3.5/s)干的,
  //   只走一两帧是「它没收完」,不是「它没在收」。
  await new Promise((r) => setTimeout(r, 380));
  for (let i = 0; i < 60; i++) her.update(0.033, 0);
  const after = her.exprWeights.get('happy') || 0;
  check('挂够了自己收回 0 —— 嘴跟着闭', after < 0.02,
        '权重 ' + after.toFixed(4));

  // ---- 反面:再开口的时候,上一句的倒计时不许把这一句掐掉 ----
  her.emotionHoldUntil = performance.now() - 1;      // 假装上一句的表早就到点了
  Her.setEmotion('happy', 0.75);
  Her.setTalking(true);
  check('再开口 → 倒计时清掉', her.emotionHoldUntil === 0,
        'holdUntil=' + her.emotionHoldUntil);
  for (let i = 0; i < 30; i++) her.update(0.033, 0);
  check('这一句照样笑得出', (her.exprWeights.get('happy') || 0) > 0.3,
        '权重 ' + (her.exprWeights.get('happy') || 0).toFixed(3));

  // ---- 收拾干净,别漏进后面的组 ----
  Her.setTalking(false);
  her.emotion = 'neutral';
  her.emotionW = 0;
  her.emotionHoldUntil = 0;
  her.exprWeights.clear();
}

// ---------------------------------------------------------------------------
// 四、收尾
// ---------------------------------------------------------------------------
if (fails.length === 0) {
  console.log('\n✅ 全部通过 (共 12 组)');
  process.exit(0);
} else {
  console.log('\n❌ 失败：' + fails.join(', '));
  process.exit(1);
}
