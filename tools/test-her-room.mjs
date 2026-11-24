// ============================================================================
// 「换房间布置」那套在 JS 这一侧的钉子 —— 在**电脑上**跑,不需要手机、不需要浏览器。
//
// ★ 为什么要有它(2026-10-05 下午):
//
//   这一轮**手机是拔着的** —— 「丢个 room.json 进去,房间会变成什么样」在真机上
//   一格都验不了。而这条路上的失败**全都是静默的**:
//
//     · 字段名写错一个字母   → 没反应,不报错
//     · 写了 "0.6" 不是 0.6  → 没反应,不报错
//     · 少写一行 x           → 东西跑到她脚底下(不是「不动」)
//     · 整份 JSON 写坏了      → 没有这份文件,房间照旧
//
//   最后一条尤其要紧:它和「你放错文件夹了」在屏幕上**一模一样**。
//   所以判定必须在电脑上咬死。**这里没钉住的行为,一律等于没做。**
//
// 跑法:
//   node phone-touchpad/tools/test-her-room.mjs
//   (路径可用 HER_SRC 覆盖,默认就是 build-her.sh 用的那个源码目录)
// ============================================================================

// ---------------------------------------------------------------------------
// 一、把浏览器里的东西糊上。
//
// her.js 是给 WebView 写的,直接 import 会因为 `window` 不存在当场炸。
// ★ 只糊**这一层**,不 fake 任何被测的东西:房间布置那套逻辑读的是
//   `this.roomDist` / `this.objRoot` 这些真字段,一个都不是假的。
// ---------------------------------------------------------------------------
const notes = [];
const SRC = process.env.HER_SRC || 'D:/her-build/web/src/her.js';

globalThis.window = {
  addEventListener() {},
  devicePixelRatio: 1,
  innerWidth: 900,
  innerHeight: 1600,
  // ★ note() 就是从这里出站的 —— 抓住它,「它到底说了什么」才是可判定的
  HerBridge: { onNote: (m) => notes.push(String(m)), onReady() {}, onError() {} },
};
// makeBackdrop 要画一块 canvas。给它一个够用的假的:我们验的不是那块渐变好不好看,
// 是「换底色这件事有没有发生」。
//
// ★ createElementNS 是给**背景图**那条路用的(three 的 TextureLoader 要走它造一个 <img>)。
//   没有它的话 setBackdropImage 会在 node 里直接抛 —— 那会验出「这条路一跑就炸」的**假**结论,
//   而真机上它是好用的。所以这里补一张**立刻回话**的假图:
//   设 src 的那一刻就把 load 喊出来(src 里带 "missing" 的喊 error)。
//   ★ 只糊这一层 —— 被测的是 setBackdropImage 自己的判据和落点,不是浏览器怎么解码 png。
function fakeImg() {
  const ls = {};
  return {
    width: 64, height: 64,
    addEventListener(t, fn) { (ls[t] = ls[t] || []).push(fn); },
    removeEventListener() {},
    set src(v) {
      this._src = v;
      const t = String(v).includes('missing') ? 'error' : 'load';
      (ls[t] || []).slice().forEach((f) => f.call(this, {}));
    },
    get src() { return this._src; },
  };
}
globalThis.document = {
  hidden: false,
  baseURI: 'https://appassets.androidplatform.net/her/index.html',
  getElementById: () => null,
  createElementNS: () => fakeImg(),
  createElement: () => ({
    width: 0, height: 0,
    getContext: () => ({
      createLinearGradient: () => ({ addColorStop() {} }),
      fillStyle: null, fillRect() {},
    }),
  }),
};
globalThis.requestAnimationFrame = () => {};
// 文件末尾那个「启动」IIFE 在 node 里注定跑不动(没有真 canvas),
// 和被测的东西无关,别让它把进程带崩。
process.on('unhandledRejection', () => {});

await import('file:///' + SRC.replace(/\\/g, '/'));
const her = globalThis.window.__her;
const Her = globalThis.window.Her;

// scene 只被用到 `.add()` 和当背景的落脚点;真的 WebGL 一次都不会碰。
her.scene = { add() {}, background: null, fog: null };

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
/**
 * ★ 这一轮**所有**说过的话,不只是最后一句。
 *
 * 为什么需要它:setRoom 的收尾一定会再补一句总结(「房间布置: …」或
 * 「没有一项认得的」),所以**具体那句抱怨在倒数第二条**上。
 * 只读 `last()` 的话,「它到底说没说为什么」会判成没说 —— 那是**量具的错,不是代码的错**。
 */
const said = () => notes.join('\n');

/** 每个用例开头都从「没写过 room.json」的状态出发。 */
function reset() {
  her.roomObjects = null;
  her.roomDist = 1;
  her.roomHeight = 1;
  her.roomBackground = null;
  her.roomBackgroundImage = null;
  her.objRoot = null;
  her.bbox = null;
  her.scenery = null;
  // ★ 内置房间那两项也要复位 —— 不然后面某一组把「内置房间」关掉之后,
  //   之后的组会拿着一个「本来就关着」的房间去验「默认是开的」,假红。
  her.roomRoot = null;
  her.roomBuiltin = true;
  // ★ 假 scene 也是被测状态的一部分 —— 上一组用例换过的底色会在这一组里
  //   被误读成「scenery 动了」。漏掉这两行,【7】就会假红。
  her.scene.background = null;
  her.scene.fog = null;
  notes.length = 0;
}
/** 「一个字都没动」—— 这条不变量比任何单个断言都值钱。 */
const untouched = () =>
  her.roomObjects === null && her.roomDist === 1 &&
  her.roomHeight === 1 && her.roomBackground === null &&
  her.roomBackgroundImage === null;

// ---------------------------------------------------------------------------
// 三、用例
// ---------------------------------------------------------------------------

console.log('\n【1】没有 room.json 的样子:写了个空对象,房间一格都不该变');
{
  reset();
  Her.setRoom('{}');
  check('四项全保持默认', untouched());
  check('并且说得出「没一项认得」', last().includes('没有一项认得'), last());

  reset();
  Her.setRoom('{"不认识的字段": 1}');
  check('只写不认识的字段 → 还是一个字没动', untouched());
  check('提示里列出了能写什么',
    last().includes('builtin') && last().includes('background') &&
    last().includes('camera') && last().includes('objects'),
    last());
}

console.log('\n【2】background:认六位十六进制,别的一律当没写');
{
  reset();
  Her.setRoom('{"background":"#aabbcc"}');
  check('#aabbcc 认了', her.roomBackground === '#aabbcc', String(her.roomBackground));
  check('日志里说了换成哪个色', last().includes('#aabbcc'), last());

  reset();
  Her.setRoom('{"background":"#AABBCC"}');
  check('大写归一成小写(不然下次比较会对不上)', her.roomBackground === '#aabbcc',
    String(her.roomBackground));

  // ★ 这一组是「我写了它没照做」最常见的四种长相
  for (const bad of ['"red"', '"#abc"', '"#12345g"', '123', 'null', '"0x160f13"']) {
    reset();
    Her.setRoom('{"background":' + bad + '}');
    check('background=' + bad + ' → 当没写', her.roomBackground === null,
      String(her.roomBackground));
    check('background=' + bad + ' → 说得出为什么', said().includes('认不出来'), said());
  }
}

console.log('\n【2b】background 的第二种写法:一张图(2026-10-06 加)');
{
  // ---- 认了,而且**屏幕上真的换了** ----
  // ★ 只验「字段记住了」是不够的:日志写了、屏幕没动的样子和「成功」一模一样。
  //   这一条钉的是「scene.background 那个对象**换了个人**」。
  reset();
  const oldTex = { dispose() { this.disposed = true; } };
  her.scene.background = oldTex;
  Her.setRoom('{"background":"wall.png"}');
  const tex = her.scene.background;
  check('wall.png 认了', her.roomBackgroundImage === 'wall.png', String(her.roomBackgroundImage));
  check('★ scene.background **真的换成了那张图**(不是只记了个字段)',
    tex && tex !== oldTex, String(tex));
  check('换的是 TextureLoader 出来的那张(看得出是个 Texture)',
    !!(tex && tex.image), String(tex && tex.image));
  check('旧那张贴图释放掉了(换一次漏一张)', oldTex.disposed === true);
  check('说得出用上了哪张图', said().includes('用上了图 wall.png'), said());

  // ---- ★ 底色和图是**同一个位置的两个写法** ----
  // 不清另一头的话,图比底色晚一拍回来,屏幕上最后是哪张就全看时序 ——
  // 那是「我写了 A 它显示 B」,而且不报错。
  reset();
  Her.setRoom('{"background":"#aabbcc"}');
  Her.setRoom('{"background":"wall.png"}');
  check('★ 设图 → 底色要让开', her.roomBackground === null, String(her.roomBackground));
  reset();
  Her.setRoom('{"background":"wall.png"}');
  Her.setRoom('{"background":"#aabbcc"}');
  check('★ 设回底色 → 图那个字段也要让开',
    her.roomBackground === '#aabbcc' && her.roomBackgroundImage === null,
    JSON.stringify([her.roomBackground, her.roomBackgroundImage]));

  // ---- 后缀大小写不敏感(和 .vrm 那条一个口径) ----
  for (const n of ['WALL.PNG', 'wall.jpeg', 'wall.JPG', 'wall.webp']) {
    reset();
    Her.setRoom('{"background":"' + n + '"}');
    check(n + ' 认了', her.roomBackgroundImage === n, String(her.roomBackgroundImage));
  }

  // ---- ★★ 不认的写法:一律当没写,而且要说出来 ----
  //   这里**故意收得很紧**:只有「一个光秃秃的文件名」算数。
  //   认了带 `/`、`\`、`:` 的写法,就等于给自己开了一条「去别处取图」的口子 ——
  //   而这条路上的图必须从 room/ 或 person/ 里来(Wardrobe 按名字精确找)。
  for (const bad of ['"sub/wall.png"', '"http://x.com/w.png"', '"wall.svg"', '"wall"', '""']) {
    reset();
    Her.setRoom('{"background":' + bad + '}');
    check('background=' + bad + ' → 当没写,而且不动屏幕上的东西',
      untouched() && her.scene.background === null,
      JSON.stringify([her.roomBackground, her.roomBackgroundImage]));
    check('background=' + bad + ' → 说得出为什么', said().includes('认不出来'), said());
  }

  // ---- 两头空格:去掉照样认(编辑器里的空格是看不见的) ----
  reset();
  Her.setRoom('{"background":"  wall.png  "}');
  check('两头有空格 → 去空格照样认', her.roomBackgroundImage === 'wall.png',
    JSON.stringify(her.roomBackgroundImage));

  // ---- ★ 图读不出来时 ----
  //   两件事同时要成立:**说得出为什么**,而且**屏幕上原来那层一个像素都不动**。
  //   先黑一下再报错,和「抠图没抠干净」是同一类观感问题。
  reset();
  const keep = { dispose() {} };
  her.scene.background = keep;
  Her.setRoom('{"background":"missing.png"}');
  check('读不出来 → 那个字段清掉(不留一个"以为在用的"值)',
    her.roomBackgroundImage === null, String(her.roomBackgroundImage));
  check('读不出来 → 说得出为什么,而且告诉他把图放哪',
    said().includes('没读出来') && said().includes('files/room/'), said());
  check('★ 读不出来 → 屏幕上原来那层**一个像素都没动**',
    her.scene.background === keep, String(her.scene.background));
}

console.log('\n【3】camera:是**倍数**,而且越界要夹住');
{
  reset();
  Her.setRoom('{"camera":{"distance":1.3}}');
  check('distance 生效', her.roomDist === 1.3, String(her.roomDist));
  check('height 没写 → 不动', her.roomHeight === 1, String(her.roomHeight));

  reset();
  Her.setRoom('{"camera":{"height":0.8}}');
  check('height 生效', her.roomHeight === 0.8, String(her.roomHeight));

  reset();
  Her.setRoom('{"camera":{"distance":9}}');
  check('distance=9 夹到 3(不然她会小成一个点)',
    her.roomDist === 3, String(her.roomDist));
  check('★ 而且**说了**「你写了 9,夹住了」—— 写 9 看见 ×3 却没人解释,下一句就是「我写了它没照做」',
    said().includes('写了 9'), said());

  reset();
  Her.setRoom('{"camera":{"distance":0.01}}');
  check('distance=0.01 夹到 0.3(不然镜头会怼进她脸里)',
    her.roomDist === 0.3, String(her.roomDist));
  check('同样要说是夹的', said().includes('写了 0.01'), said());

  // ★ 没夹住的时候**不许啰嗦** —— 每轮都喊一句「夹住了」等于没喊。
  reset();
  Her.setRoom('{"camera":{"distance":1.3}}');
  check('正常值不带那句夹住的废话', !said().includes('夹住'), said());

  // ★ 写成字符串是最像会犯的错(从别处抄一份配置过来),它必须**当没写**,
  //   而不是被悄悄转成数字 —— 悄悄转的话,以后出现真正的写法错误就没人发现得了。
  reset();
  Her.setRoom('{"camera":{"distance":"1.5"}}');
  check('distance="1.5"(字符串)→ 当没写', her.roomDist === 1, String(her.roomDist));

  reset();
  Her.setRoom('{"camera":{"distance":null}}');
  check('distance=null → 当没写(不是变成 0)', her.roomDist === 1, String(her.roomDist));

  // ★ 这一条钉的是「0 不特殊」:它在 0.3~3.0 之外,和写 0.01 是同一种情况。
  //   要是给 0 单开一条「当没写」,就会变成 0.01 夹住、0 忽略 —— 同一个错误两副面孔。
  //   (注意和下面 objects 的 `x: 0` 对着看:那个 0 是合法位置,**必须**真的挪过去。)
  reset();
  Her.setRoom('{"camera":{"distance":0}}');
  check('distance=0 → 和 0.01 一样夹到 0.3,不是被忽略',
    her.roomDist === 0.3, String(her.roomDist));
  check('distance=0 也说了是夹的', said().includes('写了 0'), said());

  reset();
  Her.setCamera = null;   // 只是提醒:这里没有第二份相机逻辑
  Her.setRoom('{"camera":[1.2]}');
  check('camera 写成数组 → 当没写,不炸', her.roomDist === 1, String(her.roomDist));
}

console.log('\n【4】objects:存得下,而且报得出件数');
{
  reset();
  Her.setRoom('{"objects":{"pc":{"x":1},"ac":{"x":2}}}');
  check('两份都存下来了',
    her.roomObjects && her.roomObjects.pc && her.roomObjects.ac);
  check('日志里说了几件', last().includes('2 件'), last());

  reset();
  Her.setRoom('{"objects":[]}');
  check('objects 写成数组 → 当没写', her.roomObjects === null, String(her.roomObjects));
}

console.log('\n【5】坏输入:一律当没写,并且说出来');
{
  reset();
  Her.setRoom('{ 这不是 json');
  check('解析失败 → 什么都没动', untouched());
  check('解析失败 → 说得出原因', last().includes('解析失败'), last());

  for (const bad of ['[1,2]', 'null', '"hello"', '123', 'true']) {
    reset();
    Her.setRoom(bad);
    check('顶层是 ' + bad + ' → 当没写', untouched());
    check('顶层是 ' + bad + ' → 说得出「不是对象」', last().includes('不是对象'), last());
  }
}

// ---------------------------------------------------------------------------
// 四、端到端:摆法真的套到物件上了
//
// ★ 这一段**不经浏览器**跑通了 setObjects -> makeObject -> roomPlace 整条路。
//   它验的是「配置读到了,东西也真的挪了」—— 而那正是只看日志看不出来的那一步:
//   日志里写「摆法 1 件」而物件没动,是完全可能的(而且不报错)。
// ---------------------------------------------------------------------------
const BOX = {
  min: { x: -0.3, y: 0.0, z: -0.1 },
  max: { x: 0.3, y: 1.7, z: 0.1 },
};
const OBJS = [{ id: 'pc', kind: 'windows-pc', name: '电脑' },
              { id: 'ac', kind: 'android-self', name: '手机' }];
const at = (id) => {
  const o = her.objRoot.children.find((c) => c.userData.herObjectId === id);
  return o ? [o.position.x, o.position.y, o.position.z, o.scale.x] : null;
};

console.log('\n【6】端到端:room.json 写的摆法,真的落到物件上');
{
  // ---- 先记一份「没写 room.json」时它们本来在哪 ----
  reset();
  her.bbox = BOX;
  Her.setObjects(OBJS);
  const pc0 = at('pc'), ac0 = at('ac');
  check('两件都摆出来了', !!pc0 && !!ac0, JSON.stringify([pc0, ac0]));
  check('日志里带上了实际位置(用户写 room.json 的起手数字)',
    /\bpc\(-?\d/.test(last()) && /\bac\(-?\d/.test(last()), last());

  // ---- 顺序 A:先给配置,再摆东西(真机上就是这条 —— onReady 里 pushRoom 在前)----
  reset();
  her.bbox = BOX;
  Her.setRoom('{"objects":{"pc":{"x":-1.1,"z":-0.5}}}');
  Her.setObjects(OBJS);
  const pcA = at('pc'), acA = at('ac');
  check('A 顺序:写了的那件挪过去了',
    pcA && Math.abs(pcA[0] + 1.1) < 1e-9 && Math.abs(pcA[2] + 0.5) < 1e-9,
    JSON.stringify(pcA));
  check('A 顺序:没写的那件**一格都没动**',
    acA && acA[0] === ac0[0] && acA[1] === ac0[1] && acA[2] === ac0[2],
    JSON.stringify([ac0, acA]));
  check('A 顺序:没写的 y 保持原样(不是被抹成 0)',
    pcA && Math.abs(pcA[1] - pc0[1]) < 1e-9, JSON.stringify([pc0, pcA]));

  // ---- 顺序 B:先摆东西,再给配置 ----
  // ★ 这条顺序在真机上也会出现(pushRoomObjects 被 onResume 再调一次)。
  //   漏了「回头补套」的话表现是「日志写了、房间没变」,而且不报错。
  reset();
  her.bbox = BOX;
  Her.setObjects(OBJS);
  Her.setRoom('{"objects":{"pc":{"x":-1.1,"z":-0.5}}}');
  const pcB = at('pc'), acB = at('ac');
  check('B 顺序:后到的配置也套上去了',
    pcB && Math.abs(pcB[0] + 1.1) < 1e-9 && Math.abs(pcB[2] + 0.5) < 1e-9,
    JSON.stringify(pcB));
  check('B 顺序:没写的那件还是没动', acB && acB[0] === ac0[0], JSON.stringify(acB));

  // ---- scale ----
  reset();
  her.bbox = BOX;
  Her.setRoom('{"objects":{"pc":{"scale":2.5}}}');
  Her.setObjects(OBJS);
  const pcC = at('pc');
  check('scale 生效', pcC && Math.abs(pcC[3] - 2.5) < 1e-9, JSON.stringify(pcC));

  // ---- ★ 判据收窄:0 是合法值,缺省不是 ----
  reset();
  her.bbox = BOX;
  Her.setRoom('{"objects":{"pc":{"x":0}}}');
  Her.setObjects(OBJS);
  const pcD = at('pc');
  check('★ x=0 是**合法值**,要真的挪到 0(不能和「没写」混成一件事)',
    pcD && pcD[0] === 0, JSON.stringify(pcD));

  reset();
  her.bbox = BOX;
  Her.setRoom('{"objects":{"pc":{"x":null,"y":"0.5","scale":0}}}');
  Her.setObjects(OBJS);
  const pcE = at('pc');
  check('null / 字符串 / 0 倍的 scale 一律当没写',
    pcE && pcE[0] === pc0[0] && pcE[1] === pc0[1] && pcE[3] === 1,
    JSON.stringify([pc0, pcE]));

  // ---- 认不出来的 id ----
  reset();
  her.bbox = BOX;
  Her.setRoom('{"objects":{"根本没有这件东西":{"x":5}}}');
  Her.setObjects(OBJS);
  check('写了不存在的 id → 不炸,别的照旧', at('pc')[0] === pc0[0]);
}

console.log('\n【7】scenery:没有文件时一个字都不该做');
{
  reset();
  // Kotlin 那边确认过文件在才会调 setScenery,所以「什么都没调」时:
  check('scenery 仍是空的', her.scenery === null);
  check('雾没被动过', her.scene.fog === null && her.scene.background === null);
  Her.setScenery('');          // 空地址 = 没有
  check('传空地址 → 直接返回,不炸', her.scenery === null);
}

// ---------------------------------------------------------------------------
// 【8】内置房间(room.js 现搭的那间):默认有,但**关得掉**
//
// ★ 为什么这一组必须存在:内置那间屋子是「房间布置」这个功能里**唯一一个
//   不请自来**的东西 —— 用户没丢任何文件,它也在。所以「我不想看见它」这条
//   要么有路可走,要么用户只能拿自己的模型去盖它,而盖出来的样子是
//   「我做的模型坏了」,不是「里面还藏着一间」。
// ---------------------------------------------------------------------------
console.log('\n【8】builtin:内置房间开 / 关');
{
  // ---- 关得掉,而且真的翻过去了 ----
  reset();
  her.roomRoot = { visible: true };
  Her.setRoom('{"builtin":false}');
  check('builtin:false → 记下来了', her.roomBuiltin === false, String(her.roomBuiltin));
  check('builtin:false → 内置房间**真的**看不见了', her.roomRoot.visible === false);
  check('并且说得出做了什么', said().includes('内置房间 关'), last());

  // ---- 开得回来 ----
  reset();
  her.roomRoot = { visible: false };
  Her.setRoom('{"builtin":true}');
  check('builtin:true → 开回来', her.roomBuiltin === true && her.roomRoot.visible === true,
    String(her.roomRoot.visible));

  // ---- ★ 字符串 "false" 必须当没写 ----
  // 这是这一项最容易踩的坑:JSON 里的 "false" 是个**非空字符串**,
  // 拿它当布尔用就是真 —— 用户写 `"builtin": "false"`(加了引号),
  // 得到的是**和字面正好相反**的结果,而且一句话都不说。
  reset();
  her.roomRoot = { visible: true };
  Her.setRoom('{"builtin":"false"}');
  check('加引号的 "false" → 当没写,不乱开乱关',
    her.roomBuiltin === true && her.roomRoot.visible === true);
  check('加引号的 "false" → 说得出为什么', said().includes('builtin'), last());

  // ---- 出场比配置晚:这一项也要落得住 ----
  // 顺序不保证(Kotlin 先读 room.json 还是先 boot 都有可能)。
  // 只写在 setRoom 里的话,「先读配置后出场」这一半就把它丢了 —— 而丢的样子
  // 是「我写了 builtin:false,房间还在」,和没写一模一样。
  reset();
  Her.setRoom('{"builtin":false}');          // 这时 roomRoot 还没造出来
  check('出场之前读到的 builtin:false,先记着', her.roomBuiltin === false);
  her.roomRoot = { visible: true };
  her.roomRoot.visible = her.roomBuiltin;    // = boot 里那一步
  check('★ 出场时按记着的那份落地(这一行就是 boot 里的那一行)',
    her.roomRoot.visible === false);
}

// ---------------------------------------------------------------------------
// 【9】拍悬浮窗那张透明人形:房间里的一切都要藏掉,而且**按原样还原**
//
// ★★ 这场事故的样子很轻:悬浮窗里那颗球上,她背后糊着一堵墙。不报错。
// ★★ 而还原写错的样子更轻:拍完之后内置房间自己**冒出来**盖在用户的 room.glb 上,
//    之后每拍一次叠一次 —— 没人会怀疑到「拍图」这一步。
// ---------------------------------------------------------------------------
console.log('\n【9】captureFigure:藏掉房间,再按原样放回去');
{
  const THREE = await import('file:///' +
    SRC.replace(/\\/g, '/').replace(/\/src\/her\.js$/, '') +
    '/node_modules/three/build/three.module.js');

  reset();
  // 用户丢过 room.glb:内置那间**本来就是关着的**(setScenery 关的)
  her.roomRoot = { visible: false };
  her.scenery = { visible: true };
  her.objRoot = { visible: true };
  her.bbox = new THREE.Box3(
    new THREE.Vector3(-0.4, 0, -0.2), new THREE.Vector3(0.4, 1.7, 0.2));

  let shot = null;
  her.renderer = {
    domElement: {
      clientWidth: 900, clientHeight: 1600,
      toDataURL: () => 'data:image/png;base64,AAAA',
    },
    setSize() {}, setClearColor() {},
    render() {
      shot = { room: her.roomRoot.visible, objs: her.objRoot.visible, scen: her.scenery.visible };
    },
  };
  her.camera = new THREE.PerspectiveCamera(30, 900 / 1600, 0.1, 100);
  her.vrm = { update() {}, scene: new THREE.Group() };

  const url = her.captureFigure();
  check('拍出了图', String(url).startsWith('data:'), String(url));
  check('★ 按快门那一下,房间里三样**全藏起来了**',
    shot && !shot.room && !shot.objs && !shot.scen, JSON.stringify(shot));
  check('★ 拍完按**原来各自的样子**还原:内置那间仍然是关的',
    her.roomRoot.visible === false, String(her.roomRoot.visible));
  check('另外两样还原成看得见',
    her.objRoot.visible === true && her.scenery.visible === true);
  check('背景 / 雾都还原了', her.scene.background === null && her.scene.fog === null);
}

// ---------------------------------------------------------------------------
console.log('\n' + (fails.length
  ? '✗ ' + fails.length + ' 条没过:\n  - ' + fails.join('\n  - ')
  : '✓ 全部通过'));
process.exit(fails.length ? 1 : 0);
