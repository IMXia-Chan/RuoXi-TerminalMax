/*
 * 大屏的绘制层：中间的场 + 下面按时间排的事件。
 *
 * ★ 只在 Android 的 WebView 里跑（电脑上验的是 dash-core.js 和 tools/dash-preview.html）。
 *
 * ## 一条硬规矩：**屏上每一根线都得有来路**
 *
 * 2026-11-05 他看过第一版（满屏几百条细丝纠缠、粒子沿着线跑）之后只问了一句
 * 「所以这些线是什么」，接着定了口径：**「我要真实的东西」**。
 * 那几百条丝确实什么也不表示 —— 线连谁和谁没有含义，粒子往哪走也没有含义，
 * 只是让屏看着满。按他的话，这一层已经**整层撤掉**，别再画回去。
 *
 * 现在画得出来的只有这些，每一样都能指回一条真的记录：
 *
 *   · **中枢**（本机）—— 常驻，它就是这张屏的中心那颗光点。
 *   · **中间那团织网** —— 仪器的内核，不声称任何事实；它会慢慢自转。
 *   · **环** —— 仪表外壳。环上亮一块 = 一次真的派活（块的宽窄跟着这次的真 token 走）。
 *   · **一簇射线** —— 一次真的派活，从环上那个岗位的角度射出去；
 *     还在跑的那一簇最亮、有光点往外走；回来的收暗；失败的压暗并染红。
 *   · **神经网络** —— 核里那一层：中枢到每个岗位一条边（派过活才有），
 *     两个岗位在同一件事上都动过手就多一条协作边；信号沿这些边一直往返传送。
 *   · **岗位面板** —— 只在真派过活之后出现（`DashCore.rolesOf`），写的是真计数和真 token。
 *
 * ★ 界线和上一版一样：**刻度（环、核、飞絮）可以随便画，事件射线不许**
 *   —— 屏幕上任何一束往外射的光，都必须能指回一条真的派活记录。
 *   没有事件时，场上只剩核和环，不自己长东西。
 *
 * 场一直是常驻 30 帧/秒，且**看不见就不画**（`document.hidden`）。
 */
(function () {
  "use strict";

  var C = window.DashCore;

  // ★ 长按不许弹出系统的「复制 / 全选」菜单（CSS 的 user-select 已经关掉了选择，
  //   这里再挡一次 contextmenu —— 有些 WebView 版本光关选择仍然会弹）。
  document.addEventListener("contextmenu", function (e) {
    e.preventDefault();
    return false;
  });

  var cv = document.getElementById("cv");
  var ctx = cv.getContext("2d");
  var stage = document.getElementById("stage");
  var listEl = document.getElementById("list");
  var emptyEl = document.getElementById("empty");
  var hudEl = document.getElementById("hud");
  var subEl = document.getElementById("sub");

  var events = [];
  var rows = [];
  var scene = null;
  var size = { w: 1, h: 1 };
  var dpr = 1;
  var pulses = [];
  var ripples = [];
  var seen = {};
  var statusTimer = 0;
  var raf = 0;
  var lastDraw = 0;
  var frameN = 0;
  var firstPaint = true;

  var FIELD = "236,244,240";   // 场的线色：冷白（密集叠加才亮）
  var ACCENT = "126,232,182";  // 主色：薄荷绿
  var CYAN = "168,255,214";    // 次色：亮薄荷（在跑 / 新到的那一条）
  var WARM = "236,200,140";    // token 读数用暖色，和耗时区分开

  /*
   * 辉光缓冲：把已经画好的画面缩到 1/4、1/9，再放大回来叠上去。
   *
   * ★ 这是这张图「高级」的一半。原来每根线只描一次 1px，屏上就只剩一层
   *   扁平的矢量线 —— 看着像流程图，不像仪器。真实的霓虹/荧光是**会溢出来的**：
   *   亮的东西在暗底上糊开一圈。缩-放就是最便宜的模糊，而且任何 WebView 都有。
   *   不用 `ctx.filter = "blur(…)"`：那个不是所有内核都认，不认的时候整层白丢。
   */
  var bloomCv1 = document.createElement("canvas");
  var bloomCv2 = document.createElement("canvas");
  var bloomC1 = bloomCv1.getContext("2d");
  var bloomC2 = bloomCv2.getContext("2d");

  /** 把主画布按 1/div 缩进缓冲，再放大叠回去。alpha 是叠上去的强度。 */
  function bloomPass(buf, g2, div, alpha, w, h) {
    var bw = Math.max(1, Math.round(w / div));
    var bh = Math.max(1, Math.round(h / div));
    if (buf.width !== bw || buf.height !== bh) {
      buf.width = bw;
      buf.height = bh;
    }
    g2.globalCompositeOperation = "copy";
    g2.drawImage(cv, 0, 0, cv.width, cv.height, 0, 0, bw, bh);
    ctx.save();
    ctx.globalCompositeOperation = "lighter";
    ctx.globalAlpha = alpha;
    ctx.drawImage(buf, 0, 0, bw, bh, 0, 0, w, h);
    ctx.restore();
  }

  /*
   * 颗粒。一整屏纯色的暗底会显得「塑料」，加一层极淡的噪点就有了胶片/屏幕的质感。
   * 只在启动时生成一张 96×96 的小图，之后每帧只是一次 pattern 填充。
   */
  var grainPat = null;
  function grainPattern() {
    var c = document.createElement("canvas");
    c.width = 96;
    c.height = 96;
    var g2 = c.getContext("2d");
    var img = g2.createImageData(96, 96);
    for (var i = 0; i < img.data.length; i += 4) {
      var v = 120 + Math.random() * 135;
      img.data[i] = v;
      img.data[i + 1] = v;
      img.data[i + 2] = v;
      img.data[i + 3] = 255;
    }
    g2.putImageData(img, 0, 0);
    return ctx.createPattern(c, "repeat");
  }

  // ────────────────────────────── 入口

  /**
   * 换掉顶上那行大标题。
   *
   * 同一个页面两处用：她的房间叫「调度中心」，超级工作台叫「超级工作台」。
   * 不重做一份页面 —— 两份就会有两处会走样的样子（这条在 DashWeb 头注释里写过）。
   */
  window.dashTitle = function (t) {
    var el = document.querySelector("#hdr .t");
    if (el && typeof t === "string" && t) el.textContent = t;
  };

  /**
   * 换掉空屏上那一句。
   *
   * 同一份页面两处用：「还没有事件」在她房间里是没话说，在工作台里是还没说要做什么。
   * 换行照写，样式里是 `pre-line`。
   */
  window.dashEmpty = function (t) {
    if (emptyEl && typeof t === "string" && t) emptyEl.textContent = t;
  };

  /**
   * **任务条**：一件活一格。
   *
   * `json` 是 `[{id, code, status, label, title}]`，`cur` 是当前那一件的号。
   * ★ 这一条只有超级工作台会推。她的房间也开着同一个页面，所以「有没有任务条」
   *   要由 Android 那边说了算 —— 页面里不能写死，否则她那块屏上会凭空多一条空带子。
   * ★ 内容一模一样的推送直接丢掉：事件流每来一条就会重画，那会把
   *   `jobsEl.innerHTML` 反复重建，横向滚动的位置跟着跳回开头。
   */
  var jobsEl = document.getElementById("jobs");
  var jobsSig = "";

  window.dashJobs = function (json, cur) {
    if (!jobsEl) return;
    var list;
    try {
      list = typeof json === "string" ? JSON.parse(json) : json;
    } catch (e) {
      return;
    }
    list = list || [];
    var sig = JSON.stringify([list, cur]);
    if (sig === jobsSig) return;
    jobsSig = sig;

    jobsEl.innerHTML = "";
    if (!list.length) {
      jobsEl.hidden = true;
      return;
    }
    jobsEl.hidden = false;
    // ★ `+` 放在**最左边**：搁在末尾的话，活一多它就滚出屏外了 ——
    //   而「新开一件」是这一条上唯一一个**总要够得着**的钮。
    var add = document.createElement("div");
    add.className = "job add";
    add.textContent = "+";
    // ★ 这一下**不带参数**（桥那边的方法是零参的，带一个空串会找不到它）。
    add.addEventListener("click", function () {
      var api = window.ConMarn;
      if (api && typeof api.newJob === "function") {
        try { api.newJob(); } catch (e) {}
      }
    });
    jobsEl.appendChild(add);
    for (var i = 0; i < list.length; i++) jobsEl.appendChild(jobChip(list[i], cur));
  };

  /** 状态 → 那一格的配色。认不出来的当「排队」，不着色。 */
  var JOB_CLASS = {
    running: "run", done: "done", failed: "bad", stopped: "bad", queued: ""
  };

  /** 走的都是**常量名字**的桥方法（见 [DashWeb] 头注释：JS 桥是攻击面）。 */
  function bridge(fn, id) {
    var api = window.ConMarn;
    if (!api || typeof api[fn] !== "function") return;
    try { api[fn](String(id == null ? "" : id)); } catch (e) {}
  }

  function jobChip(j, cur) {
    var el = document.createElement("div");
    el.className = "job" + (j.archived ? " off" : (j.id === cur ? " on" : "")) +
      " " + (JOB_CLASS[j.status] || "");
    var code = document.createElement("b");
    code.textContent = j.code || "";
    var st = document.createElement("i");
    st.textContent = j.label || "";
    var title = document.createElement("span");
    title.textContent = j.title || "";
    el.appendChild(code);
    el.appendChild(st);
    el.appendChild(title);

    // ★ 点一下 = 切过去；长按 = 出菜单（停止 / 归档）。两者必须分开判断：
    //   长按之后手指抬起来还会来一次 click，那一下不能再触发切换。
    var held = false, timer = 0;
    function down() {
      held = false;
      clearTimeout(timer);
      timer = setTimeout(function () {
        held = true;
        bridge("jobMenu", j.id);
      }, 480);
    }
    function up() {
      clearTimeout(timer);
      if (held) return;
      // ★ 归档的那几格点下去**不切过去**（它不在工作台上），直接出菜单 ——
      //   那里有「恢复」和「彻底删」。
      if (j.archived) bridge("jobMenu", j.id);
      else bridge("switchJob", j.id);
    }
    el.addEventListener("pointerdown", down);
    el.addEventListener("pointerup", up);
    el.addEventListener("pointercancel", function () { clearTimeout(timer); });
    el.addEventListener("pointerleave", function () { clearTimeout(timer); });
    return el;
  }

  window.dashPush = function (json) {
    var list;
    try {
      list = typeof json === "string" ? JSON.parse(json) : json;
    } catch (e) {
      return;
    }
    ingest(list || []);
    events = list || [];
    rows = C.build(events);
    paint();
    schedule();
    kick();
  };

  /** 新到的事件 → 放一颗子弹。**只有真事件才产生子弹**。 */
  var greeted = false;
  function ingest(list) {
    var now = performance.now();
    // ★ 打开这一屏时推过来的是一整段历史：那些事**早就发生过了**，不该再飞一遍。
    //   第一推只登记、不放子弹。否则一进屏就是二十颗彗星同时飞、二十圈涟漪同时炸，
    //   看着热闹，但没有一件是「刚发生」的 —— 那正是「只画真事」要挡的东西。
    if (!greeted) {
      greeted = true;
      for (var g = 0; g < list.length; g++) {
        if (list[g] && list[g].id != null) seen[list[g].id] = 1;
      }
      return;
    }
    for (var i = 0; i < list.length; i++) {
      var e = list[i];
      if (!e || seen[e.id]) continue;
      seen[e.id] = 1;
      if (!e.role) continue;
      if (e.kind === "ask") pulses.push({ role: e.role, t0: now, dir: 1 });
      else if (e.kind === "answer" || e.kind === "error") pulses.push({ role: e.role, t0: now, dir: -1 });
    }
    // 一次打开可能一口气补进几十条历史：留最近的那些就够看见「刚才在动」了。
    if (pulses.length > 20) pulses = pulses.slice(pulses.length - 20);
  }

  // ────────────────────────────── 读数与列表

  /** 顶部那行小字：会话跨度 + 真计数。 */
  function paintSub() {
    var s = C.stats(rows);
    var span = C.span(events, Date.now());
    // ★ 2026-11-05：这行字原来写的是 DISPATCH / RETURNED / PENDING / FAILED，
    //   竖屏 430 宽放不下、折成两行。换成短词，一行的宽度就够，意思一点没少。
    // ★ 2026-11-06：左边只剩三成宽（约 240px），这一行最多也就 36 个字符。
    //   原来那串 SENT/BACK/RUN/FAIL 是**底部读数条的重复**（那里还有中文标签），
    //   挤在这里只会被省略号吃掉后两个。所以顶栏只留会话时长这一件这里才有的事，
    //   四个计数交给底部那一条。
    subEl.textContent =
      "SESSION " + pad2(Math.floor(span / 60000)) + ":" + pad2(Math.floor(span / 1000) % 60);
  }

  function pad2(n) {
    n = Math.max(0, Math.floor(n || 0));
    return n < 10 ? "0" + n : String(n);
  }

  /**
   * 在密集的丝线上写字：先用底色描一遍，再填正文。
   *
   * ★ 场上铺满发光的细线，字直接压上去会糊成一团；而这里写的全是**读数**
   *   （这个岗位叫什么、派出去几份、回来几份），糊了就等于没写。
   */
  function label(text, x, y, weight) {
    var ink = ctx.fillStyle;
    ctx.lineJoin = "round";
    ctx.strokeStyle = "rgba(4, 7, 10, 0.92)";
    ctx.lineWidth = weight || 3;
    ctx.strokeText(text, x, y);
    ctx.fillStyle = ink;
    ctx.fillText(text, x, y);
  }

  /**
   * 把一段字的锚点推回画布里。
   *
   * `align` 是当前 `ctx.textAlign`，`width` 是这段字的宽度。放得下就原样返回，
   * 放不下就整体平移，直到贴住 6px 的边距 —— 场放大到贴边之后，右侧那个岗位
   * 的名字本来会被画布切掉半个字。
   */
  function fitX(align, x, width, w) {
    var left = align === "right" ? x - width : align === "center" ? x - width / 2 : x;
    var lim = Math.min(Math.max(left, 6), w - width - 6);
    return x + (lim - left);
  }

  /** 上一轮那四个数。用来判断「这次到底是哪一格变了」—— 变了才动。 */
  var lastHud = null;

  /**
   * 底部那四个读数。
   *
   * ★ 2026-11-05 他要「数字动效」。动效只管**真变了**的那一格：新到一个事件，
   *   派活/回来/在跑里必然有一个数变了，它闪一下；剩下三格一动不动。
   *   这不是「随便闪一闪」—— 闪的那格就是刚发生的事落在哪一格。
   *   初始那一次不闪（没有「之前」，也就没有「变了」）。
   */
  function paintHud() {
    var s = C.stats(rows);
    // ★ 这一屏真的花掉多少 token（`DashCore.tokens` 那条口径，服务端自报的 usage）。
    //   没花的就不记：本地算的那几步没有账，是 0，不估。
    s.tok = C.tokens(events);
    var prev = lastHud;
    function cell(key, n, label, cls, text) {
      var bump = prev && prev[key] !== n ? " bump" : "";
      return '<div class="stat ' + (cls || "") + (n ? "" : " zero") + bump + '">' +
        cellInner(text || pad2(n), label) + "</div>";
    }
    // ★ 2026-11-06：「派活 / 回来 / 在跑」是口语，这一屏是仪表，用调度口径的词。
    hudEl.innerHTML = cell("asks", s.asks, "派发") + cell("answered", s.answered, "回传") +
      cell("failed", s.failed, "失败", "err") + cell("running", s.running, "运行", "run") +
      cell("tok", s.tok, "Token", "tok", fmtTok(s.tok));
    lastHud = s;
    // ★ 读数条刚写满内容时量一次它有多高。启动那一刻它还是空的，`resize()` 量到的是 0 ——
    //   房间就按「没有读数条」铺到底，人一头走进条子底下，看着像从屏幕里掉下去了。
    measureHud();
  }
  function cellInner(text, label) {
    return "<b>" + text + "</b><span>" + label + "</span>";
  }

  function paint() {
    // ★ 2026-11-05：整页不再滚动，滚的是**右边那一栏**（`#list` 自己 overflow）。
    //   所以判据从 `window.scrollY` 换成这个元素的 `scrollTop`。
    //   规矩没变：**他只在他本来就贴着底的时候才被带着走**。他往上翻看旧记录时
    //   来了一条新事件，屏幕不该自己跳到底 —— 那等于把他正在看的东西抢走。
    var nearBottom = listEl.scrollTop + listEl.clientHeight >= listEl.scrollHeight - 72;
    paintSub();
    paintHud();
    paintList();
    if (firstPaint) {
      firstPaint = false;
      listEl.scrollTop = 0;
    } else if (nearBottom) {
      listEl.scrollTop = listEl.scrollHeight;
    }
  }

  /**
   * 这条时间线上**最后一句它自己说的话**。
   *
   * ★ 他要的是「报告就在这条线上读，不要从一个对话框跳到另一个对话框」。
   *   所以最后一条云端答复（`answer` + 云端）直接铺开正文，其余行照旧一行一条。
   *   判据只认**答复**：派活、旁白、报错都不铺开，否则整条线会被一段段长文撑散。
   */
  function reportId() {
    var id = -1;
    for (var i = 0; i < rows.length; i++) {
      var r = rows[i];
      if (r.type === "line" && r.kind === "answer" && r.from === "cloud") id = r.id;
    }
    return id;
  }

  function paintList() {
    listEl.textContent = "";
    if (!rows.length) {
      emptyEl.style.display = "block";
      return;
    }
    emptyEl.style.display = "none";
    var frag = document.createDocumentFragment();
    for (var i = 0; i < rows.length; i++) {
      var r = rows[i];
      if (r.type === "group") frag.appendChild(groupEl(r));
      else if (r.type === "work") frag.appendChild(cardEl(r));
      else frag.appendChild(lineEl(r));
    }
    listEl.appendChild(frag);
  }

  /**
   * 一张作战卡 = **一行**：`01  编辑      22 字 · 4.0s`
   *
   * ★ 2026-11-05 他说的：「底下那一片文章感觉太复杂，能不能用代号来」。
   *   任务原文和回答全文**没有丢**，只是不再铺在屏上 —— 点这一行，原文还在。
   *   屏上留的是**能扫的**那一层：哪个岗位、做完没有、多大、多久。
   */
  function cardEl(row) {
    var el = document.createElement("div");
    el.className = cardClass(row);
    el.setAttribute("data-id", String(row.id));

    var code = document.createElement("span");
    code.className = "code";
    code.textContent = row.code || "";

    var role = document.createElement("span");
    role.className = "role";
    role.textContent = row.role;

    var st = document.createElement("span");
    st.className = "status";

    // 中间一段点线：把「谁」和「结果」连起来。空着一大截只会显得没画完。
    var lead = document.createElement("span");
    lead.className = "lead";

    el.appendChild(code);
    el.appendChild(role);
    el.appendChild(lead);
    el.appendChild(st);

    row._status = st;
    row._el = el;
    paintCard(row, Date.now());
    return el;
  }

  function cardClass(row) {
    return "card " + (row.err ? "fail" : row.end ? "done" : "run") + (row.chained ? " chained" : "");
  }

  /** 一张卡随时间变化的**只有**末尾那段读数 —— 所以单独抽出来，让 tick 能局部改。 */
  function paintCard(row, now) {
    var st = row._status;
    if (!st) return;
    // ★ 每半秒刷一次状态时会重写 className，`chained` 必须跟着一起写回去 ——
    //   少写这一笔，链上第二段跑不过半秒就会被缩回全宽。
    if (row._el) row._el.className = cardClass(row);
    st.textContent = C.statusOf(row, now);
    st.className = "status" + (row.err ? " err" : row.end ? "" : " run");
  }

  function groupEl(group) {
    var el = document.createElement("div");
    el.className = "group";
    var head = document.createElement("div");
    head.className = "ghead";
    head.textContent = C.groupStat(group.items, Date.now()).toUpperCase();
    el.appendChild(head);
    group._head = head;
    for (var i = 0; i < group.items.length; i++) el.appendChild(cardEl(group.items[i]));
    return el;
  }

  function lineEl(row) {
    var el = document.createElement("div");
    // ★ 接力段与段之间那条记录画成「↓ 下一段」，不画成普通记录 ——
    //   普通记录是旁白，这一条是**交接**：上一段的产出正在往下递。
    if (row.chain) {
      el.className = "line chain";
      el.setAttribute("data-id", String(row.id));
      var t = document.createElement("span");
      t.className = "txt";
      t.textContent = "↓ " + C.clip(row.text, 44);
      el.appendChild(t);
      return el;
    }
    var isReport = row.id === reportId();
    el.className = "line" + (row.from === "self" ? " self" : "") +
      (row.kind === "error" ? " err" : "") + (isReport ? " report" : "");
    el.setAttribute("data-id", String(row.id));
    var info = C.lineOf(row);
    // 铺开的那一条给**全文**：点开看全文是给别的行留的路，报告本身不该还要再点一次。
    if (isReport) info.text = row.text;
    var who = document.createElement("span");
    who.className = "who";
    who.textContent = info.who;
    var kind = document.createElement("span");
    kind.className = "kind";
    kind.textContent = info.kind;
    var text = document.createElement("span");
    text.className = "txt";
    text.textContent = info.text;
    el.appendChild(who);
    el.appendChild(kind);
    el.appendChild(text);
    return el;
  }

  // ────────────────────────────── 场

  function roleKey() {
    return C.rolesOf(events).join("|");
  }

  /**
   * 建一次场景。现在它只剩一件事：核心在哪、圈有多大。
   *
   * ★ 原来这里会撒三百来条丝（放射丝 / 横贯丝 / 环流），2026-11-05 按他的
   *   「我要真实的东西」**整层撤掉**了 —— 那些丝连谁和谁都没有含义，
   *   铺满一屏只让人以为有什么。撤掉之后没有随机、没有种子，
   *   也就不会出现「同一件事每次打开长得不一样」。
   */
  /**
   * 看板的排版：左边中枢，右边一列岗位卡。
   *
   * ★ 不做放射/同心圆 —— 那看着像雷达（他 2026-11-05 的原话）。
   * ★ 底部那条读数条是**压在画布上**的，所以排版必须先把它的高度让出来；
   *   不让的话最后一张卡就长在读数条底下（第一版就是这么错的）。
   */
  /**
   * 场的排版：**一张活地图**。
   *
   * ★ 2026-11-05 第五版。他的原话：「实在不行改成戴森球计划那种，每一个 agent、子 agent
   *   都在做自己的事情，然后你可以看见，然后还能调试它。」
   *
   * 前面四版都是**列表的变体**（圆点阵、卡片、树、神经球）—— 换的是皮的画法，
   * 底子还是「一行一件事」。这一版换的是**空间**：
   *
   * - **中枢**是左边那颗核心，不动。
   * - **每个岗位是一栋楼**，固定驻在自己的位置上（位置固定 = 一眼能认出谁是谁，
   *   挪来挪去就成了装饰）。
   * - **派活是路上跑的东西**：从中枢到楼一条路，回来的走同一条路反向。
   *   路上有没有东西、跑得多快，都来自真实事件，不是动画。
   * - 楼上的灯：待派＝暗、已回＝常亮、在跑＝呼吸、失败＝红。
   */
  var PAD = 12;
  var HUD_H = 84;

  /**
   * 场的排版：**像素办公室**。
   *
   * ★ 2026-11-05 第七版。他桌面那张 `KsanaDock/Microverse` 的像素办公室定下了画法。
   * ★ 2026-11-05 第八版（他看完真机提的六条）：
   *   · 人得**会走动、会交谈**，谈完头顶冒泡，气泡**往上飘**＝这一段活做完了；
   *   · 脚下不写 token；
   *   · 字太大，全部缩小一档；
   *   · 人一多不能挤成一坨 —— 每人一条自己的道，各自走动。
   *
   * ★ 老规矩不变：**屏上每个东西都得有来路**。
   *   · 一个人 = 一个岗位；坐下敲键盘 = 还有活没回来；走开晃悠 = 手上没活；
   *   · 两个人凑到一起说话 = 这两份活刚刚都回来了；
   *   · 往上飘的气泡 = 真的一次「回来」，写的是那条回话的原文。
   */
  function hash(s) {
    var v = 0;
    s = String(s || "");
    for (var i = 0; i < s.length; i++) v = (v * 31 + s.charCodeAt(i)) & 0x7fffffff;
    return v;
  }

  function fmtTok(n) {
    n = n || 0;
    if (n <= 0) return "0";
    if (n < 1000) return String(n);
    if (n < 1000000) return (n / 1000).toFixed(n < 10000 ? 1 : 0) + "k";
    return (n / 1000000).toFixed(1) + "m";
  }

  var hitSites = [];

  /* ── 场：光核 · 环 · 射线 ──────────────────────────────────
   * 2026-11-05 第十版。他看完 Ryder191/haloflux 的原图说「对咯！就是这个」，
   * 又看完照它重写的一版说「比它的还更帅」。
   *
   * 观感是三层堆出来的，拆开看没有一层是花招：
   *   1. **核**：一千多根从内沿拉出来的随机贝塞尔曲线织在一起，再轻微失焦。
   *      同心圆再准也只是圆；像一团光，靠的是交叉和参差。
   *   2. **环**：实心暗环 + 里外各七百根 0.5° 的白色脊线。厚度和亮边是脊给的。
   *   3. **射线**：一次派活 = 一簇 14~34 根挨着的射线，长短按平滑随机曲线走，
   *      尾部有流动短划和端点亮斑，最后整屏过一遍低分辨率高斯辉光。
   *
   * ★ 外观照它，数据一条都不照它（它那份是 PolyForm Noncommercial，源码和观感都不搬；
   *   这是照它的做法自己写的）。规矩不变：**屏上每一束都得有来路** ——
   *   一束 = 一次真的派活，环上一块亮 = 同一次派活，一个面板 = 一个真的岗位。
   *   没有事件的时候，场上只剩核和环，不自己长东西。
   */

  /** 底部读数条此刻压掉了多少像素（收起侧栏后它是 0）。在 resize / paintHud 里量。 */
  var hudH = 0;

  /** 场的高度：底部读数条压住的那一截不算（收起侧栏后它就是整块屏）。 */
  function fieldH() { return Math.max(80, size.h - hudH); }

  var CARD_W = 80, CARD_H = 32;
  var RING_W = 13;                    // 环脊的宽度

  /** 场里几个共同的量，每帧按屏大小重算一次。 */
  var GEO = { cx: 0, cy: 0, r: 0, ro: 0, rmax: 0 };

  /**
   * 一次派活：派出去 + 回来的那一段。
   *
   * ★ 配对用的是 `ref`（和 DashCore.build 同一个规矩）：
   *   没配上的就是「还在路上」，配上的才是「回来」或「失败」。
   */
  function tripsOf(list) {
    var waiting = {}, out = [];
    for (var i = 0; i < (list || []).length; i++) {
      var e = list[i];
      if (!e || !e.role) continue;
      if (e.kind === "ask") {
        var tr = { id: e.id, role: e.role, state: "run", ask: e.text || "", back: "", tok: 0,
                   at: e.at || 0, backAt: 0, task: e.task || "" };
        out.push(tr);
        waiting[e.id] = tr;
      } else if ((e.kind === "answer" || e.kind === "error") && waiting[e.ref]) {
        var w0 = waiting[e.ref];
        delete waiting[e.ref];
        w0.state = e.kind === "error" ? "err" : "done";
        w0.back = e.text || "";
        w0.tok = e.tok || 0;
        w0.backAt = e.at || 0;
      }
    }
    return out;
  }

  /**
   * 岗位摆在环上的哪个角度。
   * ★ 起步就错开半格：正十字看着像准星，而且上下两张卡会顶到读数条。
   */
  function slotAngles(n) {
    var out = [];
    var a0 = -Math.PI / 2 - Math.PI / n;
    for (var i = 0; i < n; i++) out.push(a0 + i * Math.PI * 2 / n);
    return out;
  }

  function rr(x, y, w2, h2, r) {
    ctx.beginPath();
    ctx.moveTo(x + r, y);
    ctx.arcTo(x + w2, y, x + w2, y + h2, r);
    ctx.arcTo(x + w2, y + h2, x, y + h2, r);
    ctx.arcTo(x, y + h2, x, y, r);
    ctx.arcTo(x, y, x + w2, y, r);
    ctx.closePath();
  }

  /**
   * 环不是正圆：半径按角度轻轻起伏，整体又慢慢漂。
   *
   * ★ 这是这一版「别那么机械」的主要手法。正圆 + 等分角度 + 直线，三样凑齐就是一张
   *   制图；它原图里没有一样是准的 —— 全是起伏、错开、参差。
   *   ★ 起伏的是**仪器本身**，不表示任何事件，所以可以随便动。
   */
  var TT = 0;
  function wob(a) {
    return 0.55 * Math.sin(3 * a + TT * 0.35)
      + 0.30 * Math.sin(5 * a - TT * 0.23)
      + 0.15 * Math.sin(8 * a + TT * 0.11);
  }
  function rimAt(a, outer) {
    return (outer ? GEO.ro : GEO.r) + wob(a) * (outer ? 1.7 : 1.2);
  }

  /** 二次贝塞尔上的一点。网络里每根连线都是一段弧，信号得能算准它走到哪了。 */
  function qPoint(p0, cp, p1, u) {
    var v = 1 - u;
    return {
      x: v * v * p0.x + 2 * v * u * cp.x + u * u * p1.x,
      y: v * v * p0.y + 2 * v * u * cp.y + u * u * p1.y
    };
  }

  /** 指针现在停在哪个节点上（碰上去高亮它那几条线）。0 = 没有。 */
  var HOVER = { role: null };

  /** 这一帧节点的屏幕位置与手感半径：给指针做命中判定用（每帧重建）。 */
  var NODE_HIT = [];

  /** 确定性随机（同一个 seed 每次长一样）—— 岗位和派活的位置才不会自己跳。 */
  function seeded(seed) {
    var a = (seed | 0) || 1;
    return function () {
      a |= 0; a = a + 0x6D2B79F5 | 0;
      var t = Math.imul(a ^ a >>> 15, 1 | a);
      t = t + Math.imul(t ^ t >>> 7, 61 | t) ^ t;
      return ((t ^ t >>> 14) >>> 0) / 4294967296;
    };
  }

  // ───────────── 核：球面神经网络 ─────────────
  /*
   * ★ 2026-11-06：这里原来是一千多根随机贝塞尔曲线烘成的一张位图 —— 半径不变就永远一样，
   *   所以它看着就是一张贴图。他点名了。
   *
   *   现在换成真东西：节点铺在球面上（黄金角分布，不挤极点），边按球面距离连最近邻，
   *   数据点沿边实时流动。球体自己在转，所以前后关系是靠深度算出来的，不是画上去的。
   */

  var GLB = null;

  function globeInit() {
    if (GLB) return GLB;
    var N = 72, nodes = [], edges = [];
    var R0 = seeded(20261106);
    var ga = Math.PI * (3 - Math.sqrt(5));      // 黄金角
    for (var i = 0; i < N; i++) {
      var y = 1 - (i + 0.5) * 2 / N;
      var rr = Math.sqrt(Math.max(0, 1 - y * y));
      var th = ga * i;
      nodes.push({ x: Math.cos(th) * rr, y: y, z: Math.sin(th) * rr, ph: R0() });
    }
    // 每个点连最近的 3 个邻居；A<B 才收，免得一条边画两遍
    for (var i2 = 0; i2 < N; i2++) {
      var best = [];
      for (var j = 0; j < N; j++) {
        if (j === i2) continue;
        best.push([
          (nodes[i2].x * nodes[j].x + nodes[i2].y * nodes[j].y + nodes[i2].z * nodes[j].z), j
        ]);
      }
      best.sort(function (p1, p2) { return p2[0] - p1[0]; });
      for (var k = 0; k < 4; k++) {
        var j2 = best[k][1];
        if (j2 > i2) edges.push({ a: i2, b: j2, ph: R0(), sp: 0.12 + R0() * 0.22, dir: R0() < 0.5 });
      }
    }
    GLB = { N: N, nodes: nodes, edges: edges };
    return GLB;
  }

  /** 画球：t 是秒，r 是球半径。 */
  function drawGlobe(t, r) {
    var G = globeInit();
    var yaw = t * 0.16, rip = 0.42 + 0.05 * Math.sin(t * 0.23);
    var cyw = Math.cos(yaw), syw = Math.sin(yaw);
    var cp = Math.cos(rip), sp = Math.sin(rip);
    var RAD = r * 0.92;
    var P = [], i, n, x1, z1, y2, z2, kk;

    for (i = 0; i < G.N; i++) {
      n = G.nodes[i];
      x1 = n.x * cyw + n.z * syw;
      z1 = -n.x * syw + n.z * cyw;
      y2 = n.y * cp - z1 * sp;
      z2 = n.y * sp + z1 * cp;
      kk = 1 / (1 + z2 * 0.40);
      P.push({
        x: GEO.cx + x1 * RAD * kk, y: GEO.cy + y2 * RAD * kk,
        z: z2, k: kk, ph: n.ph
      });
    }

    ctx.save();
    ctx.globalCompositeOperation = "lighter";
    ctx.lineCap = "round";

    /* 经纬骨架：没有它，一堆点看着是散的；有了它才是一颗球。 */
    var gj;
    ctx.strokeStyle = "rgba(160,200,240,0.16)";
    ctx.lineWidth = 0.5;
    ctx.beginPath();
    for (gj = -2; gj <= 2; gj++) {                     // 4 条纬线
      var lat = gj * Math.PI / 6, cy3 = Math.sin(lat), rr3 = Math.cos(lat);
      for (var li = 0; li <= 64; li++) {
        var lo = li / 64 * Math.PI * 2;
        var gx3 = Math.cos(lo) * rr3, gy3 = cy3, gz3 = Math.sin(lo) * rr3;
        var ax3 = gx3 * cyw + gz3 * syw, az3 = -gx3 * syw + gz3 * cyw;
        var ay3 = gy3 * cp - az3 * sp, az4 = gy3 * sp + az3 * cp;
        var ak3 = 1 / (1 + az4 * 0.40);
        var sx3 = GEO.cx + ax3 * RAD * ak3, sy3 = GEO.cy + ay3 * RAD * ak3;
        if (li) ctx.lineTo(sx3, sy3); else ctx.moveTo(sx3, sy3);
      }
    }
    for (gj = 0; gj < 6; gj++) {                       // 6 条经线
      var lon = gj * Math.PI / 3;
      for (var mi = 0; mi <= 64; mi++) {
        var la2 = (mi / 64 - 0.5) * Math.PI;
        var gx4 = Math.cos(la2) * Math.cos(lon), gy4 = Math.sin(la2), gz4 = Math.cos(la2) * Math.sin(lon);
        var bx3 = gx4 * cyw + gz4 * syw, bz3 = -gx4 * syw + gz4 * cyw;
        var by3 = gy4 * cp - bz3 * sp, bz4 = gy4 * sp + bz3 * cp;
        var bk3 = 1 / (1 + bz4 * 0.40);
        var sx4 = GEO.cx + bx3 * RAD * bk3, sy4 = GEO.cy + by3 * RAD * bk3;
        if (mi) ctx.lineTo(sx4, sy4); else ctx.moveTo(sx4, sy4);
      }
    }
    ctx.stroke();

    for (var e = 0; e < G.edges.length; e++) {
      var ed = G.edges[e], A = P[ed.a], B = P[ed.b];
      var zm = (A.z + B.z) * 0.5;
      var front = Math.max(0, (zm + 1) * 0.5);       // 0＝背面 1＝正面
      // 边：背面几乎看不见，正面才是一条线
      ctx.strokeStyle = "rgba(198,220,250," + (0.05 + 0.34 * front * front) + ")";
      ctx.lineWidth = 0.55 + 0.7 * front;
      ctx.beginPath(); ctx.moveTo(A.x, A.y); ctx.lineTo(B.x, B.y); ctx.stroke();

      // 数据点：只给看得见的那半边走
      if (front > 0.55) {
        var u = (t * ed.sp + ed.ph) % 1;
        if (!ed.dir) u = 1 - u;
        var px = A.x + (B.x - A.x) * u, py = A.y + (B.y - A.y) * u;
        var ga2 = 4.2 * front;
        var pg = ctx.createRadialGradient(px, py, 0, px, py, ga2);
        pg.addColorStop(0, "rgba(215,248,255," + (0.62 * front) + ")");
        pg.addColorStop(1, "rgba(126,226,255,0)");
        ctx.fillStyle = pg;
        ctx.beginPath(); ctx.arc(px, py, ga2, 0, Math.PI * 2); ctx.fill();
      }
    }

    for (i = 0; i < G.N; i++) {
      var q = P[i];
      var dep = Math.max(0, (q.z + 1) * 0.5);        // 0 背 1 前
      var rr2 = (0.7 + 1.5 * dep) * Math.min(1.6, q.k);
      ctx.fillStyle = "rgba(228,244,255," + (0.10 + 0.72 * dep * dep) + ")";
      ctx.beginPath(); ctx.arc(q.x, q.y, rr2, 0, Math.PI * 2); ctx.fill();
      if (dep > 0.86) {                               // 最靠前的那几个带一圈光
        var ng = ctx.createRadialGradient(q.x, q.y, 0, q.x, q.y, 7);
        ng.addColorStop(0, "rgba(200,240,255,0.30)");
        ng.addColorStop(1, "rgba(126,226,255,0)");
        ctx.fillStyle = ng;
        ctx.beginPath(); ctx.arc(q.x, q.y, 7, 0, Math.PI * 2); ctx.fill();
      }
    }
    ctx.restore();
  }

  // ───────────── 射线：一次派活 = 一簇 ─────────────

  var strandGrads = {};

  /** 从环外沿向外淡出的那条渐变：一簇射线共用一根，颜色按簇定。 */
  function strandGrad(tint, r0, r1) {
    var key = tint + ":" + Math.round(r0) + ":" + Math.round(r1);
    if (strandGrads[key]) return strandGrads[key];
    var gr = ctx.createRadialGradient(GEO.cx, GEO.cy, r0, GEO.cx, GEO.cy, r1);
    if (tint === 0) {
      gr.addColorStop(0, "rgba(238,242,255,0.95)");
      gr.addColorStop(0.30, "rgba(214,222,246,0.55)");
      gr.addColorStop(0.65, "rgba(178,192,232,0.16)");
      gr.addColorStop(1, "rgba(160,180,230,0)");
    } else if (tint === 1) {
      gr.addColorStop(0, "rgba(226,248,248,0.95)");
      gr.addColorStop(0.30, "rgba(178,220,222,0.52)");
      gr.addColorStop(0.65, "rgba(150,196,204,0.15)");
      gr.addColorStop(1, "rgba(140,190,200,0)");
    } else {
      gr.addColorStop(0, "rgba(250,235,245,0.95)");
      gr.addColorStop(0.30, "rgba(232,190,212,0.5)");
      gr.addColorStop(0.65, "rgba(208,160,190,0.15)");
      gr.addColorStop(1, "rgba(200,150,180,0)");
    }
    strandGrads[key] = gr;
    return gr;
  }

  /** 一簇射线里每根的相对长度：平滑随机游走 —— 中间长、两边短，边缘参差。 */
  function makeProfile(n, rnd) {
    var p = [], v = 0.45 + rnd() * 0.55, run = 0;
    for (var k = 0; k < n; k++) {
      if (run-- <= 0) {
        v = Math.max(0.22, Math.min(1, v + (rnd() - 0.5) * 0.8));
        run = 2 + (rnd() * 5 | 0);
      }
      p.push(Math.max(0.18, Math.min(1, v + (rnd() - 0.5) * 0.14)));
    }
    return p;
  }

  var EDGE_A = [0.24, 0.58, 1];

  /**
   * 画一簇射线。
   *
   * @param centerA 这一簇朝哪个角度（岗位角度 + 这条活的偏角）
   * @param len     最长那根的长度
   * @param lum     这一簇的亮度（回来的收暗、在跑的提亮）
   * @param seed    由事件 id 定：同一件事每次打开长得一样
   */
  function drawWedge(centerA, len, lum, seed) {
    var rnd = seeded(seed);
    var n = 14 + (rnd() * 21 | 0);
    var step = (0.42 + rnd() * 0.35) * Math.PI / 180;
    var a0 = centerA - n * step / 2;
    var prof = makeProfile(n, rnd);
    var tint = rnd() < 0.72 ? 0 : (rnd() < 0.6 ? 1 : 2);

    // ★ 每根射线有自己的呼吸相位、自己的流动相位、自己的粗细。
    //   一整簇共用同一个节拍，看着就是一块在闪的板子 —— 这是它原图里最值钱的一条。
    var pf = [], pp = [], ds = [], lwK = [];
    for (var q = 0; q < n; q++) {
      pf.push(0.5 + rnd() * 1.7);
      pp.push(rnd() * Math.PI * 2);
      ds.push(rnd());
      lwK.push(0.6 + rnd() * 0.8);
    }

    var paths = [new Path2D(), new Path2D(), new Path2D()];
    var r0 = GEO.ro + 1;
    var lens = [], solidAt = [];
    var flow = (TT * 0.55) % 1;

    for (var k = 0; k < n; k++) {
      var a = a0 + (k + 0.5) * step;
      var ca = Math.cos(a), sa = Math.sin(a);
      var edge = Math.sin(Math.PI * (k + 0.5) / n);
      var b = edge < 0.38 ? 0 : edge < 0.72 ? 1 : 2;
      var l = len * prof[k] * (0.5 + 0.5 * Math.sqrt(edge))
        * (0.90 + 0.10 * Math.sin(TT * pf[k] + pp[k]))
        * (0.92 + 0.08 * rnd());
      var solid = l * (0.45 + 0.30 * ds[k]);
      lens.push(l);
      solidAt.push(solid);
      paths[b].moveTo(GEO.cx + ca * r0, GEO.cy + sa * r0);
      paths[b].lineTo(GEO.cx + ca * (r0 + solid), GEO.cy + sa * (r0 + solid));
    }

    ctx.strokeStyle = strandGrad(tint, GEO.ro - 2, GEO.rmax * 0.92);
    ctx.lineCap = "butt";
    for (var bi = 0; bi < 3; bi++) {
      ctx.globalAlpha = lum * EDGE_A[bi];
      ctx.lineWidth = 0.75;
      ctx.stroke(paths[bi]);
    }
    // 最亮那一簇再糊一层宽的：不靠整屏模糊，只给亮的东西自己一圈光
    if (lum > 0.8) {
      ctx.globalAlpha = lum * 0.10;
      ctx.lineWidth = 2.6;
      ctx.stroke(paths[2]);
    }
    ctx.globalAlpha = 1;

    // 尾部：短划沿着射线往外流，端点亮斑轮流亮
    ctx.save();
    ctx.globalCompositeOperation = "lighter";
    var tipSlot = (TT * 12) | 0;
    for (var d = 0; d < n; d++) {
      var da = a0 + (d + 0.5) * step;
      var dc = Math.cos(da), dsx = Math.sin(da);
      var dl = lens[d], dsolid = solidAt[d];
      var tail = dl - dsolid;
      if (tail > 6) {
        for (var dd = 0; dd < 2; dd++) {
          var ph = (flow + ds[d] + dd * 0.5) % 1;
          var s0 = dsolid + tail * ph;
          var s1 = Math.min(dl, s0 + 3 + tail * 0.22 * (1 - ph * 0.5));
          if (s1 <= s0) continue;
          ctx.beginPath();
          ctx.moveTo(GEO.cx + dc * (r0 + s0), GEO.cy + dsx * (r0 + s0));
          ctx.lineTo(GEO.cx + dc * (r0 + s1), GEO.cy + dsx * (r0 + s1));
          ctx.strokeStyle = tint === 2
            ? "rgba(255,214,232," + (lum * 0.5) + ")"
            : "rgba(236,244,255," + (lum * 0.45) + ")";
          ctx.lineWidth = 0.8;
          ctx.stroke();
        }
      }
      if ((d + tipSlot) % 4 === 0) {
        ctx.fillStyle = "rgba(255,255,255," + (lum * 0.9) + ")";
        ctx.beginPath();
        ctx.arc(GEO.cx + dc * (r0 + dl), GEO.cy + dsx * (r0 + dl), 1.15, 0, Math.PI * 2);
        ctx.fill();
      }
    }
    ctx.restore();
  }

  /** 在跑的活：一颗光点沿着这一簇的中轴往外走。 */
  function pulseDot(a, len, u) {
    var x = GEO.cx + Math.cos(a) * (GEO.ro + 6 + len * u);
    var y = GEO.cy + Math.sin(a) * (GEO.ro + 6 + len * u);
    var pg = ctx.createRadialGradient(x, y, 0, x, y, 9);
    pg.addColorStop(0, "rgba(210,250,255,0.75)");
    pg.addColorStop(1, "rgba(126,226,255,0)");
    ctx.fillStyle = pg;
    ctx.beginPath(); ctx.arc(x, y, 9, 0, Math.PI * 2); ctx.fill();
    ctx.fillStyle = "#EAFEFF";
    ctx.beginPath(); ctx.arc(x, y, 2, 0, Math.PI * 2); ctx.fill();
  }

  /**
   * 本地那一层现在有谁。
   *
   * ★ 中枢常驻（它就是这台手机上的唯一一层「有手」的东西）；
   *   「她」和「本机算力」**只在真出现过之后才画** —— 事件里的 `from` 就是这么记的
   *   （见 AgentBusModel：hub / local / self / cloud）。
   */
  function localActors() {
    var self = false, local = false;
    for (var i = 0; i < events.length; i++) {
      var e = events[i];
      if (!e) continue;
      if (e.from === "self") self = true;
      else if (e.from === "local") local = true;
    }
    var out = [{ key: "hub", name: "中枢" }];
    if (self) out.push({ key: "self", name: "她" });
    if (local) out.push({ key: "local", name: "本机" });
    return out;
  }

  /**
   * 左上角那只齿轮：本地 agent 的网络长在盘面上，齿自己在转。
   *
   * ★ 齿轮只是「本地这一层」的容器 —— 转的是齿，盘面上的节点和连线一动不动，
   *   因为那是读数，不是动画。
   * 返回本机中枢的坐标，好让两组网络之间的那条通道接上去。
   */
  /** 一帧。 */
  /** 顶栏的实际高度（浮层，不占排版）。量不到就退一个保守值。 */
  function HEAD_H(fh) {
    var el = document.getElementById("hdr");
    if (!el) return 56;
    var h = el.getBoundingClientRect().height;
    if (!(h > 0)) return 56;
    // ★ 下沿是渐隐的，多留 6px 才不会贴着字
    return Math.min(h + 6, fh * 0.34);
  }

  function draw(now) {
    var t = now / 1000;
    TT = t;
    var w = size.w, h = size.h, fh = fieldH();

    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.globalAlpha = 1;
    ctx.globalCompositeOperation = "source-over";
    ctx.filter = "none";
    // ★ 2026-11-06：底不再刷一层不透明的 #08090B —— 刷了就把下面的顶栏玻璃整个盖死。
    //   场这一层只画光，底色交给 body。他要的是「顶栏在最里层」，那就得真的透得下去。
    ctx.clearRect(0, 0, w, h);

    // ★ 齿轮撤了，场回到正中，半径也能放大一点。
    var r = Math.min(w * 0.27, fh * 0.44);
    GEO.cx = w * 0.5;
    GEO.cy = fh * 0.52;
    GEO.r = r;
    GEO.ro = r + RING_W;
    GEO.rmax = Math.hypot(w * 0.5, fh * 0.5) * 1.05;
    strandGrads = {};

    // 背景：中心一团冷光，越往外越黑
    var bg = ctx.createRadialGradient(GEO.cx, GEO.cy, 0, GEO.cx, GEO.cy, GEO.rmax);
    bg.addColorStop(0, "rgba(26,30,42,0.9)");
    bg.addColorStop(0.55, "rgba(14,16,22,0.6)");
    bg.addColorStop(1, "rgba(5,6,8,0)");
    ctx.fillStyle = bg;
    ctx.fillRect(0, 0, w, fh);

    // 刻度方格：极淡，只说「这是一块量板」，不声称任何事实
    ctx.strokeStyle = "rgba(72,158,255,0.030)";
    ctx.lineWidth = 1;
    // ★ 变量名不能叫 gx/gy —— 齿轮的坐标就用这两个名字，`var` 在同一个函数里是同一个变量，
    //   这个循环会把齿轮挪到屏幕外（第一版就是这么没画出来的）。
    for (var lx = 0; lx < w; lx += 26) {
      ctx.beginPath(); ctx.moveTo(lx + 0.5, 0); ctx.lineTo(lx + 0.5, fh); ctx.stroke();
    }
    for (var ly = 0; ly < fh; ly += 26) {
      ctx.beginPath(); ctx.moveTo(0, ly + 0.5); ctx.lineTo(w, ly + 0.5); ctx.stroke();
    }

    var roles = C.rolesOf(events);
    var trips = tripsOf(events);
    var idx = {};
    for (var i = 0; i < roles.length; i++) idx[roles[i]] = i;
    var slots = slotAngles(Math.max(1, roles.length));

    /* 节点位置先算好：射线、环上的亮块、网络全都对着同一组位置，
     * 不然「同一个岗位」在三个地方会是三个角度。 */
    var wall = Date.now();
    var NR = r * 0.68;
    var nodes = {};
    for (var qn = 0; qn < roles.length; qn++) {
      var an = slots[qn];
      // ★ 不钉在等分角上：每个岗位按自己的名字偏一点，再慢慢漂。
      //   四个角一样、角度一样，看着就是一张制图。
      var anJ = an + ((hash(roles[qn]) % 100) - 50) * 0.0042;
      nodes[roles[qn]] = {
        role: roles[qn], a: anJ, i: qn,
        x: GEO.cx + Math.cos(anJ) * NR + Math.sin(t * 0.7 + qn * 2.3) * 2.6,
        y: GEO.cy + Math.sin(anJ) * NR + Math.cos(t * 0.55 + qn * 1.9) * 2.6
      };
    }

    // ── 核：球面神经网络。它自己在转，数据点自己在走。
    // ★ 必须剪在圆里：透视会把靠近镜头的点推到 r 以外，不剪就会压到环上。
    ctx.save();
    ctx.beginPath(); ctx.arc(GEO.cx, GEO.cy, r, 0, Math.PI * 2); ctx.clip();
    drawGlobe(t, r);
    ctx.restore();

    // 环内侧压暗：把核和环分成两个平面
    var occ = ctx.createRadialGradient(GEO.cx, GEO.cy, r * 0.55, GEO.cx, GEO.cy, r);
    occ.addColorStop(0, "rgba(4,5,6,0)");
    occ.addColorStop(1, "rgba(4,5,6,0.72)");
    ctx.fillStyle = occ;
    ctx.beginPath(); ctx.arc(GEO.cx, GEO.cy, r, 0, Math.PI * 2); ctx.fill();

    // ── 射线：一束 = 一次真的派活
    var longest = GEO.rmax * 0.72 - GEO.ro;
    for (var j = 0; j < trips.length; j++) {
      var tr = trips[j];
      if (!(tr.role in idx)) continue;            // 岗位超过这张环摆得下的数，不画
      var seed = hash(String(tr.id));
      var rnd0 = seeded(seed);
      var a = nodes[tr.role].a + (rnd0() - 0.5) * 0.10;
      var base = longest * (0.42 + rnd0() * 0.42);
      var lum = tr.state === "run" ? 1.0 : (tr.state === "err" ? 0.34 : 0.72);
      var breathe = 0.92 + 0.08 * Math.sin(t * 0.7 + (seed % 100));
      drawWedge(a, base * breathe, lum, seed);
      if (tr.state === "run") {
        var u = (t * 0.18 + (seed % 100) / 100) % 1;
        pulseDot(a, base, u);
      }
      if (tr.state === "err") {
        // 失败的那一簇压暗、染红：它确实派出去过，也确实没回来
        ctx.save();
        ctx.globalCompositeOperation = "lighter";
        ctx.strokeStyle = "rgba(255,107,129,0.22)";
        ctx.lineWidth = 1;
        for (var fk = 0; fk < 6; fk++) {
          var fa = a + (fk - 2.5) * 0.012;
          ctx.beginPath();
          ctx.moveTo(GEO.cx + Math.cos(fa) * (GEO.ro + 2), GEO.cy + Math.sin(fa) * (GEO.ro + 2));
          ctx.lineTo(GEO.cx + Math.cos(fa) * (GEO.ro + base * 0.55), GEO.cy + Math.sin(fa) * (GEO.ro + base * 0.55));
          ctx.stroke();
        }
        ctx.restore();
      }
    }

    // ── 飞絮：极淡的向外拉丝。只做空气感，不代表任何一次派活。
    ctx.save();
    ctx.globalCompositeOperation = "lighter";
    ctx.translate(GEO.cx, GEO.cy);
    ctx.rotate(t * 0.012);       // ★ 极慢地转：一张完全静止的场，看着就是印上去的
    ctx.translate(-GEO.cx, -GEO.cy);
    var frnd = seeded(7717);
    for (var fl = 0; fl < 220; fl++) {
      var fa2 = frnd() * Math.PI * 2;
      var fr0 = GEO.ro + 2 + frnd() * longest * 0.9;
      var flen = 6 + frnd() * 86;
      ctx.beginPath();
      ctx.moveTo(GEO.cx + Math.cos(fa2) * fr0, GEO.cy + Math.sin(fa2) * fr0);
      ctx.lineTo(GEO.cx + Math.cos(fa2) * (fr0 + flen), GEO.cy + Math.sin(fa2) * (fr0 + flen));
      ctx.strokeStyle = "rgba(214,222,240," + (0.012 + frnd() * 0.038) + ")";
      ctx.lineWidth = 0.4 + frnd() * 0.6;
      ctx.stroke();
    }
    ctx.restore();

    // ── 环：实心暗环 + 里外两圈脊线
    ctx.save();
    // 环带：里外两条起伏的闭合曲线，用 evenodd 挖空中间
    ctx.beginPath();
    for (var ri2 = 0; ri2 <= 200; ri2++) {
      var ra2 = ri2 / 200 * Math.PI * 2;
      var rx2 = GEO.cx + Math.cos(ra2) * rimAt(ra2, true);
      var ry2 = GEO.cy + Math.sin(ra2) * rimAt(ra2, true);
      if (ri2) ctx.lineTo(rx2, ry2); else ctx.moveTo(rx2, ry2);
    }
    ctx.closePath();
    for (var ri3 = 0; ri3 <= 200; ri3++) {
      var ra3 = ri3 / 200 * Math.PI * 2;
      var rx3 = GEO.cx + Math.cos(ra3) * rimAt(ra3, false);
      var ry3 = GEO.cy + Math.sin(ra3) * rimAt(ra3, false);
      if (ri3) ctx.lineTo(rx3, ry3); else ctx.moveTo(rx3, ry3);
    }
    ctx.closePath();
    ctx.fillStyle = "#06070A";
    ctx.fill("evenodd");

    // 脊线：里外各一圈，长短参差（每根的长度都是它自己的，不是等分）
    ctx.beginPath();
    for (var hk = 0; hk < 420; hk++) {
      var ha = hk * Math.PI * 2 / 420;
      var hc = Math.cos(ha), hs = Math.sin(ha);
      var major = hk % 7 === 0;
      var jit = 0.72 + ((hk * 37) % 11) / 18;
      var li = (major ? RING_W * 0.95 : RING_W * (0.30 + ((hk * 13) % 9) / 22)) * jit;
      var rIn = rimAt(ha, false) + 1;
      var rOut = rimAt(ha, true) - 1;
      // ★ 必须加圆心。漏了 GEO.cx/cy 的话，这 420 根脊线会围着画布左上角 (0,0)
      //   画出一道弧 —— 在屏幕上就是左上角那片「一条一条」的斜纹。
      ctx.moveTo(GEO.cx + hc * rIn, GEO.cy + hs * rIn);
      ctx.lineTo(GEO.cx + hc * (rIn + li), GEO.cy + hs * (rIn + li));
      ctx.moveTo(GEO.cx + hc * rOut, GEO.cy + hs * rOut);
      ctx.lineTo(GEO.cx + hc * (rOut - li), GEO.cy + hs * (rOut - li));
    }
    ctx.globalCompositeOperation = "lighter";
    ctx.strokeStyle = "rgba(232,235,242,0.045)"; ctx.lineWidth = 3.2; ctx.stroke();
    ctx.strokeStyle = "rgba(232,235,242,0.09)"; ctx.lineWidth = 1.3; ctx.stroke();
    ctx.globalCompositeOperation = "source-over";
    ctx.strokeStyle = "rgba(240,243,250,0.75)"; ctx.lineWidth = 0.7; ctx.stroke();

    // 亮边：跟着起伏走，不再是两条完美的圆
    ctx.strokeStyle = "rgba(242,245,252,1)"; ctx.lineWidth = 1.3;
    ctx.beginPath();
    for (var re = 0; re <= 200; re++) {
      var ae = re / 200 * Math.PI * 2;
      var xe = GEO.cx + Math.cos(ae) * (rimAt(ae, false) + 0.5);
      var ye = GEO.cy + Math.sin(ae) * (rimAt(ae, false) + 0.5);
      if (re) ctx.lineTo(xe, ye); else ctx.moveTo(xe, ye);
    }
    ctx.closePath(); ctx.stroke();
    ctx.beginPath();
    for (var ro2 = 0; ro2 <= 200; ro2++) {
      var ao2 = ro2 / 200 * Math.PI * 2;
      var xo2 = GEO.cx + Math.cos(ao2) * (rimAt(ao2, true) - 0.5);
      var yo2 = GEO.cy + Math.sin(ao2) * (rimAt(ao2, true) - 0.5);
      if (ro2) ctx.lineTo(xo2, yo2); else ctx.moveTo(xo2, yo2);
    }
    ctx.closePath(); ctx.stroke();
    ctx.restore();

    /* ── 外沿的一圈梳齿：把那束射线的毛边安到球边上，当它的边框
     *
     * 长度是三个慢波叠出来的，不是等长 —— 等长就是一把齿梳，看不出「刻度」。
     * 每 15 根一根长的，当作整刻度。整圈极慢地转。
     */
    ctx.save();
    ctx.globalCompositeOperation = "lighter";
    var BZ = 360, bzRot = t * 0.06;
    var bzFine = new Path2D(), bzMajor = new Path2D();
    var bzIn = GEO.ro + 2;
    for (var bz = 0; bz < BZ; bz++) {
      var ba2 = bzRot + bz * Math.PI * 2 / BZ;
      var bc2 = Math.cos(ba2), bs2 = Math.sin(ba2);
      var uu = bz * Math.PI * 2 / BZ;
      var klen = 0.22
        + 0.34 * (0.5 + 0.5 * Math.sin(uu * 3 + 0.7))
        + 0.18 * (0.5 + 0.5 * Math.sin(uu * 7 + 2.1))
        + 0.10 * (0.5 + 0.5 * Math.sin(uu * 17 + 4.3))
        + 0.08 * (((bz * 37) % 11) / 11);
      var major2 = bz % 15 === 0;
      var li2 = (major2 ? 1.0 : klen) * 26;
      var tgt = major2 ? bzMajor : bzFine;
      tgt.moveTo(GEO.cx + bc2 * bzIn, GEO.cy + bs2 * bzIn);
      tgt.lineTo(GEO.cx + bc2 * (bzIn + li2), GEO.cy + bs2 * (bzIn + li2));
    }
    ctx.lineCap = "butt";
    ctx.strokeStyle = "rgba(214,232,255,0.13)"; ctx.lineWidth = 0.7; ctx.stroke(bzFine);
    ctx.strokeStyle = "rgba(238,246,255,0.42)"; ctx.lineWidth = 0.9; ctx.stroke(bzMajor);
    ctx.restore();

    // 环上的亮块 = 一次派活（和外面那一簇是同一件事）
    for (var bi2 = 0; bi2 < trips.length; bi2++) {
      var trb = trips[bi2];
      if (!(trb.role in idx)) continue;
      var rnd2 = seeded(hash(String(trb.id)));
      var ba = nodes[trb.role].a + (rnd2() - 0.5) * 0.10;
      var isErr = trb.state === "err";
      var isRun = trb.state === "run";
      var bw = 0.0045 + Math.min(0.02, (trb.tok || 0) / 600000);
      var col = isErr ? "255,107,129" : (isRun ? "168,240,255" : "226,240,255");
      ctx.fillStyle = "rgba(" + col + ")";
      ctx.beginPath();
      var NW = 10;
      for (var w1 = 0; w1 <= NW; w1++) {
        var wa = ba - bw + (2 * bw) * w1 / NW;
        var wr = rimAt(wa, true) - RING_W * 0.5;
        var wx = GEO.cx + Math.cos(wa) * wr, wy = GEO.cy + Math.sin(wa) * wr;
        if (w1) ctx.lineTo(wx, wy); else ctx.moveTo(wx, wy);
      }
      for (var w2 = NW; w2 >= 0; w2--) {
        var wb = ba - bw + (2 * bw) * w2 / NW;
        var wr2 = rimAt(wb, true) - RING_W * 0.5 - 2.6;
        ctx.lineTo(GEO.cx + Math.cos(wb) * wr2, GEO.cy + Math.sin(wb) * wr2);
      }
      ctx.closePath();
      ctx.globalAlpha = isErr ? 0.95 : (isRun ? 0.95 : 0.72);
      ctx.fill();
      ctx.globalAlpha = 1;
    }

    // ── 核的中心：一团白
    ctx.save();
    ctx.globalCompositeOperation = "lighter";
    // ★ 这团白只到 r*0.45、最亮处压到 0.16：再大再亮，上面那层神经网络就被糊住了。
    var coreG = ctx.createRadialGradient(GEO.cx, GEO.cy, 0, GEO.cx, GEO.cy, r * 0.45);
    coreG.addColorStop(0, "rgba(255,255,255,0.16)");
    coreG.addColorStop(0.12, "rgba(228,244,255,0.09)");
    coreG.addColorStop(0.45, "rgba(170,210,255,0.03)");
    coreG.addColorStop(1, "rgba(120,170,255,0)");
    ctx.fillStyle = coreG;
    ctx.beginPath(); ctx.arc(GEO.cx, GEO.cy, r * 0.45, 0, Math.PI * 2); ctx.fill();
    ctx.restore();

    var dp = 1 + 0.06 * Math.sin(t * 1.7);
    var dotG = ctx.createRadialGradient(GEO.cx - 3, GEO.cy - 4, 0, GEO.cx, GEO.cy, 11 * dp);
    dotG.addColorStop(0, "#FFFFFF");
    dotG.addColorStop(0.4, "#E8FFF8");
    dotG.addColorStop(0.75, "rgba(150,240,220,0.75)");
    dotG.addColorStop(1, "rgba(126,226,255,0)");
    ctx.fillStyle = dotG;
    ctx.beginPath(); ctx.arc(GEO.cx, GEO.cy, 11 * dp, 0, Math.PI * 2); ctx.fill();
    ctx.fillStyle = "#FFFFFF";
    ctx.beginPath(); ctx.arc(GEO.cx, GEO.cy, 2.6, 0, Math.PI * 2); ctx.fill();

    ctx.font = "9px ui-monospace, Menlo, Consolas, monospace";
    ctx.fillStyle = "rgba(210,240,235,0.60)";
    ctx.textAlign = "center";
    label("中枢", GEO.cx, GEO.cy + r * 0.55 + 14, 2.4);
    ctx.textAlign = "left";

    /* ── 神经网络：节点是真的岗位，边是真的关系 ─────────────────
     *
     * 两样东西才有资格画成线：
     *   · **中枢 → 岗位** —— 这个岗位被派过活，这条路就存在（派得越多越亮）；
     *   · **岗位 ↔ 岗位** —— 同一件事上有两个岗位都动过手，它们之间才有一条边。
     * 信号点只沿这两类边走：派出去时从中心往外，回来时从外往中心。
     *
     * ★ 这里不铺「随机连线」：一屏好看的随机图他见过，问的第一句是「所以这些线是什么」。
     */

    // 每个岗位此刻的忙闲：还有几件没回来
    var busy = {};
    for (var bq = 0; bq < trips.length; bq++) {
      if (trips[bq].state === "run" && (trips[bq].role in nodes)) {
        busy[trips[bq].role] = (busy[trips[bq].role] || 0) + 1;
      }
    }

    // 协作边：同一个 task 上出现过的两个岗位
    var taskRoles = {}, links = [], seenLink = {};
    for (var te = 0; te < events.length; te++) {
      var ev0 = events[te];
      if (!ev0 || !ev0.role || !ev0.task || !(ev0.role in idx)) continue;
      (taskRoles[ev0.task] = taskRoles[ev0.task] || {})[ev0.role] = 1;
    }
    var taskKeys = Object.keys(taskRoles);
    for (var tk = 0; tk < taskKeys.length && links.length < 16; tk++) {
      var rs = Object.keys(taskRoles[taskKeys[tk]]);
      if (rs.length < 2 || rs.length > 4) continue;
      for (var pa = 0; pa < rs.length; pa++) {
        for (var pb = pa + 1; pb < rs.length; pb++) {
          var lk = rs[pa] + "|" + rs[pb];
          if (seenLink[lk]) continue;
          seenLink[lk] = 1;
          links.push({ a: rs[pa], b: rs[pb] });
        }
      }
    }

    var hub = { x: GEO.cx, y: GEO.cy };

    ctx.save();
    ctx.globalCompositeOperation = "lighter";
    ctx.lineCap = "round";

    // 中枢 → 岗位
    var spokes = {};
    for (var sn = 0; sn < roles.length; sn++) {
      var nd0 = nodes[roles[sn]];
      var bowA = nd0.a + 0.34;
      var cp = {
        x: GEO.cx + Math.cos(bowA) * NR * 0.58,
        y: GEO.cy + Math.sin(bowA) * NR * 0.58
      };
      spokes[nd0.role] = { p0: hub, cp: cp, p1: nd0 };
      var hot = busy[nd0.role] > 0;
      var lit = HOVER.role === nd0.role;
      // ★ 一条线太硬。三根稍微错开的纤维，一粗两细 —— 神经束就是这么长的。
      var nx0 = Math.cos(nd0.a + Math.PI / 2), ny0 = Math.sin(nd0.a + Math.PI / 2);
      var fib = [-9, 0, 9];
      for (var fb = 0; fb < 3; fb++) {
        var off = fib[fb];
        ctx.beginPath();
        ctx.moveTo(hub.x + nx0 * off * 0.15, hub.y + ny0 * off * 0.15);
        ctx.quadraticCurveTo(cp.x + nx0 * off, cp.y + ny0 * off, nd0.x, nd0.y);
        var base2 = fb === 1 ? 1 : 0.55;
        ctx.strokeStyle = lit ? "rgba(205,250,255," + (0.72 * (fb === 1 ? 1 : 0.6)) + ")"
          : hot ? "rgba(160,238,255," + (0.52 * base2) + ")" : "rgba(150,200,255," + (0.24 * base2) + ")";
        ctx.lineWidth = fb === 1 ? (lit ? 1.7 : (hot ? 1.35 : 1.1)) : 0.7;
        ctx.stroke();
      }
    }

    // 岗位 ↔ 岗位（协作边）
    for (var le = 0; le < links.length; le++) {
      var la = nodes[links[le].a], lb = nodes[links[le].b];
      if (!la || !lb) continue;
      var mid = { x: (la.x + lb.x) / 2, y: (la.y + lb.y) / 2 };
      var cpx = GEO.cx + (mid.x - GEO.cx) * 0.42;
      var cpy = GEO.cy + (mid.y - GEO.cy) * 0.42;
      var hotL = (busy[la.role] > 0) || (busy[lb.role] > 0);
      var litL = HOVER.role === la.role || HOVER.role === lb.role;
      ctx.beginPath();
      ctx.moveTo(la.x, la.y);
      ctx.quadraticCurveTo(cpx, cpy, lb.x, lb.y);
      ctx.strokeStyle = litL ? "rgba(170,255,225,0.55)"
        : hotL ? "rgba(130,235,205,0.32)" : "rgba(120,200,180,0.18)";
      ctx.lineWidth = litL ? 1.3 : 0.9;
      ctx.stroke();
    }

    // 信号：派出去从中心往外，回来从外往中心
    /** 一颗信号点：带一小截尾巴，走起来才看得出方向。 */
    function sig(role, forward, u, alpha) {
      var sp = spokes[role];
      if (!sp) return;
      var q0 = forward ? sp.p0 : sp.p1;
      var q2 = forward ? sp.p1 : sp.p0;
      for (var tl = 3; tl >= 0; tl--) {
        var uu = u - (forward ? 1 : -1) * tl * 0.018;
        if (uu < 0 || uu > 1) continue;
        var p = qPoint(q0, sp.cp, q2, uu);
        var a2 = alpha * (1 - tl * 0.24);
        if (tl) {
          ctx.fillStyle = "rgba(190,246,255," + (a2 * 0.55) + ")";
          ctx.beginPath(); ctx.arc(p.x, p.y, 2.4 - tl * 0.45, 0, Math.PI * 2); ctx.fill();
          continue;
        }
        var g2 = ctx.createRadialGradient(p.x, p.y, 0, p.x, p.y, 9);
        g2.addColorStop(0, "rgba(226,253,255," + Math.min(1, a2) + ")");
        g2.addColorStop(1, "rgba(120,226,255,0)");
        ctx.fillStyle = g2;
        ctx.beginPath(); ctx.arc(p.x, p.y, 9, 0, Math.PI * 2); ctx.fill();
        ctx.fillStyle = "rgba(255,255,255," + Math.min(1, a2 + 0.2) + ")";
        ctx.beginPath(); ctx.arc(p.x, p.y, 2, 0, Math.PI * 2); ctx.fill();
      }
    }

    /* 每条**存在**的通路都在持续传送。
     *
     * ★ 这里说的是「这条路是活的」，不是「刚刚又派了一次」：一颗点从中心出去、
     *   半拍之后从外面回来，代表这条通路两端连着。**点的数量跟着这个岗位有没有活在跑变** ——
     *   有活在跑就三道快流，闲下来是一道慢流。事件本身另有那一颗快的（`tr.at/backAt`）。
     *
     * ★ 没有这条常流，一屏静止的线就只是一张示意图 —— 他原话：「没有神经网络交互传输，
     *   就不太好看」。线可以停，但网络不能停。
     */
    var phaseOf = function (role) { return (hash(role) % 100) / 100; };
    for (var sr = 0; sr < roles.length; sr++) {
      var roleNow = roles[sr];
      var busyN = busy[roleNow] || 0;
      var ph = phaseOf(roleNow);
      var spd = busyN ? 0.55 : 0.24;
      var outN = busyN ? 3 : 1;
      for (var k2 = 0; k2 < outN; k2++) {
        sig(roleNow, true, (t * spd + ph + k2 / outN) % 1, busyN ? 0.95 : 0.55);
      }
      var backN = busyN ? 2 : 1;
      for (var k3 = 0; k3 < backN; k3++) {
        sig(roleNow, false, (t * spd * 0.8 + ph + 0.5 + k3 / backN) % 1, busyN ? 0.78 : 0.4);
      }
    }

    // 协作边：两个岗位之间照样有来回，速率比主通路慢一半（它不是主轴）
    for (var lq = 0; lq < links.length; lq++) {
      var ea = nodes[links[lq].a], eb = nodes[links[lq].b];
      if (!ea || !eb) continue;
      var lcp = {
        x: GEO.cx + ((ea.x + eb.x) / 2 - GEO.cx) * 0.42,
        y: GEO.cy + ((ea.y + eb.y) / 2 - GEO.cy) * 0.42
      };
      var lhot = (busy[ea.role] > 0) || (busy[eb.role] > 0);
      for (var ln = 0; ln < 2; ln++) {
        var lu = (t * (lhot ? 0.3 : 0.16) + lq * 0.27 + ln * 0.5) % 1;
        var lp = ln ? qPoint(eb, lcp, ea, lu) : qPoint(ea, lcp, eb, lu);
        var lg = ctx.createRadialGradient(lp.x, lp.y, 0, lp.x, lp.y, 7);
        lg.addColorStop(0, "rgba(170,255,225," + (lhot ? 0.8 : 0.45) + ")");
        lg.addColorStop(1, "rgba(110,230,190,0)");
        ctx.fillStyle = lg;
        ctx.beginPath(); ctx.arc(lp.x, lp.y, 7, 0, Math.PI * 2); ctx.fill();
        ctx.fillStyle = "rgba(235,255,248," + (lhot ? 0.9 : 0.55) + ")";
        ctx.beginPath(); ctx.arc(lp.x, lp.y, 1.4, 0, Math.PI * 2); ctx.fill();
      }
    }

    // 那一刻真的派出去 / 真的回来：额外一颗又快又亮，和常流区分开
    for (var sj = 0; sj < trips.length; sj++) {
      var trj = trips[sj];
      if (!spokes[trj.role]) continue;
      if (trj.at) {
        var up = (wall - trj.at) / 900;
        if (up >= 0 && up <= 1) sig(trj.role, true, up, 1);
      }
      if (trj.backAt) {
        var ub = (wall - trj.backAt) / 900;
        if (ub >= 0 && ub <= 1) sig(trj.role, false, ub, 1);
      }
    }
    ctx.restore();

    // 节点
    NODE_HIT = [];
    for (var nn = 0; nn < roles.length; nn++) {
      var nd2 = nodes[roles[nn]];
      var isBusy = (busy[nd2.role] || 0) > 0;
      var isHov = HOVER.role === nd2.role;
      var pulse = isBusy ? (0.5 + 0.5 * Math.sin(t * 3.1 + nn)) : 0;
      var rr0 = 6.2 + (isHov ? 2.6 : 0) + pulse * 1.2;

      if (isBusy || isHov) {
        ctx.save();
        ctx.globalCompositeOperation = "lighter";
        var halo = ctx.createRadialGradient(nd2.x, nd2.y, 0, nd2.x, nd2.y, rr0 + 10);
        halo.addColorStop(0, "rgba(130,240,255," + (0.24 + pulse * 0.16) + ")");
        halo.addColorStop(1, "rgba(120,220,255,0)");
        ctx.fillStyle = halo;
        ctx.beginPath(); ctx.arc(nd2.x, nd2.y, rr0 + 10, 0, Math.PI * 2); ctx.fill();
        ctx.restore();
      }

      /* ★ 节点是**光**，不是珠子。
       *   以前这里是「深色实心圆 + 硬描边 + 中心白点」，看着像一颗塑料扣子。
       *   改成一圈受光的细环（左上亮、右下暗）+ 一团从中心散开的光，没有实体面。 */
      var nl = ctx.createLinearGradient(nd2.x - rr0, nd2.y - rr0, nd2.x + rr0, nd2.y + rr0);
      var na1 = isBusy ? 0.95 : (isHov ? 1 : 0.62);
      nl.addColorStop(0, "rgba(236,252,255," + na1 + ")");
      nl.addColorStop(0.52, "rgba(150,220,250," + (na1 * 0.42) + ")");
      nl.addColorStop(1, "rgba(96,160,205," + (na1 * 0.16) + ")");
      ctx.beginPath();
      ctx.arc(nd2.x, nd2.y, rr0, 0, Math.PI * 2);
      ctx.strokeStyle = nl;
      ctx.lineWidth = isHov ? 1.7 : 1.25;
      ctx.stroke();

      ctx.save();
      ctx.globalCompositeOperation = "lighter";
      var nc = ctx.createRadialGradient(nd2.x, nd2.y, 0, nd2.x, nd2.y, rr0 * 0.95);
      nc.addColorStop(0, "rgba(255,255,255," + (isBusy ? 0.95 : 0.7) + ")");
      nc.addColorStop(0.35, "rgba(170,240,255," + (isBusy ? 0.42 : 0.26) + ")");
      nc.addColorStop(1, "rgba(120,200,255,0)");
      ctx.fillStyle = nc;
      ctx.beginPath(); ctx.arc(nd2.x, nd2.y, rr0 * 0.95, 0, Math.PI * 2); ctx.fill();

      // 四根刻度：小小的准星，让它看起来是「仪表上的一个点」
      ctx.strokeStyle = "rgba(210,245,255," + (isBusy ? 0.7 : 0.4) + ")";
      ctx.lineWidth = 0.8;
      for (var tk2 = 0; tk2 < 4; tk2++) {
        var ta2 = Math.PI / 4 + tk2 * Math.PI / 2;
        ctx.beginPath();
        ctx.moveTo(nd2.x + Math.cos(ta2) * (rr0 - 1.2), nd2.y + Math.sin(ta2) * (rr0 - 1.2));
        ctx.lineTo(nd2.x + Math.cos(ta2) * (rr0 + 3.6), nd2.y + Math.sin(ta2) * (rr0 + 3.6));
        ctx.stroke();
      }
      ctx.restore();

      // 代号贴在节点外侧：面板太远，凑近看还得能对上号
      ctx.font = "8px ui-monospace, Menlo, Consolas, monospace";
      ctx.fillStyle = "rgba(170,200,230,0.75)";
      ctx.textAlign = "center";
      ctx.fillText(C.codeOf(nd2.i), nd2.x + Math.cos(nd2.a) * (rr0 + 9),
        nd2.y + Math.sin(nd2.a) * (rr0 + 9) + 3);
      ctx.textAlign = "left";

      // 手指比针尖粗：命中半径给到节点外 16px，否则「点不着」会被当成没反应
      NODE_HIT.push({ x: nd2.x, y: nd2.y, r: rr0 + 16, role: nd2.role });
    }

    /* ★ 2026-11-06 撤掉：环外那圈「研究员 / 核验员 …」的岗位卡。
     *   他要的是球本身做主角，卡片压在球上把网络盖掉了一半。
     *   岗位的账还在：右边列表一行一个岗位，点开就是全文。
     *   `hitSites` 留着但是空的 —— 场上的点击从此不做事，只有悬停还会点亮节点。 */
    hitSites = [];

    // ── 辉光：1/3 分辨率 + 高斯糊，叠回去。亮的东西要在暗底上糊开一圈。
    var GS = 3;
    var gw = Math.ceil(w / GS), gh = Math.ceil(h / GS);
    if (bloomCv2.width !== gw || bloomCv2.height !== gh) {
      bloomCv2.width = gw; bloomCv2.height = gh;
    }
    bloomC2.clearRect(0, 0, gw, gh);
    bloomC2.drawImage(cv, 0, 0, gw, gh);
    ctx.save();
    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.globalCompositeOperation = "lighter";
    // ★ 辉光只负责「亮的东西边缘有一圈光」，不该负责让整张图发虚：
    //   糊半径收到 5px、两趟加起来 0.36。以前是 9px + 0.77，全屏都是雾。
    try {
      ctx.filter = "blur(5px)";
      ctx.globalAlpha = 0.26;
      ctx.drawImage(bloomCv2, 0, 0, gw * GS, gh * GS);
      ctx.filter = "none";
    } catch (e) { /* 没有 filter 时下面的轻叠还留着 */ }
    ctx.globalAlpha = 0.10;
    ctx.drawImage(bloomCv2, 0, 0, gw * GS, gh * GS);
    ctx.restore();

    // ── 暗角 + 扫描线 + 颗粒
    var vg = ctx.createRadialGradient(w * 0.5, fh * 0.5, Math.min(w, fh) * 0.30,
      w * 0.5, fh * 0.5, Math.max(w, fh) * 0.95);
    vg.addColorStop(0, "rgba(0,0,0,0)");
    vg.addColorStop(1, "rgba(0,0,0,0.66)");
    ctx.fillStyle = vg;
    ctx.fillRect(0, 0, w, h);

    ctx.fillStyle = "rgba(140,180,255,0.016)";
    for (var sy = 0; sy < fh; sy += 3) ctx.fillRect(0, sy, w, 1);

    if (!grainPat) grainPat = grainPattern();
    if (grainPat) {
      var gx2 = Math.floor(t * 24) % 96, gy2 = Math.floor(t * 37) % 96;
      ctx.save();
      ctx.globalAlpha = 0.030;
      ctx.globalCompositeOperation = "lighter";
      ctx.translate(-gx2, -gy2);
      ctx.fillStyle = grainPat;
      ctx.fillRect(gx2, gy2, w + 96, h + 96);
      ctx.restore();
    }
  }

  // ────────────────────────────── 循环

  /** 量一下读数条此刻压掉了多少像素（收起侧栏时它是隐藏的，就是 0）。 */
  function measureHud() {
    hudH = (hudEl && hudEl.offsetParent) ? Math.max(0, hudEl.offsetHeight) : 0;
  }

  function resize() {
    var r = stage.getBoundingClientRect();
    size = { w: Math.max(1, r.width), h: Math.max(1, r.height) };
    // 读数条还露着的时候，它压住的那一截不算地面（收起侧栏后它就没了，量到 0）。
    measureHud();
    dpr = Math.min(window.devicePixelRatio || 1, 2);
    cv.width = Math.round(size.w * dpr);
    cv.height = Math.round(size.h * dpr);
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    scene = null;
    draw(performance.now());
  }
  window.addEventListener("resize", resize);

  // ────────────────────────────── 分界

  /*
   * 左边那块场和右边那份列表之间的比例：**钉死 30.5%，不可拖**。
   *
   * ★ 2026-11-06 他自己在手机上拖到位（左边三成、右栏七成），这个数就写进
   *   dash.css 的 `:root { --split }` 一处。这里不再算、不再存、也不再拖。
   */
  /* ★ 2026-11-06 撤掉「点一下让场全屏」：他不再要这个动作。
     场的大小直接拖中间那道分界（#grip）就行，少一颗浮在球边上的钮。 */

  function kick() {
    if (raf) return;
    raf = requestAnimationFrame(frame);
  }

  function frame(ts) {
    raf = 0;
    if (document.hidden) return;
    // ★ 30 帧就够了：这是一张诊断屏，不是游戏。多点帧率只换来发热。
    if (ts - lastDraw >= 33) {
      lastDraw = ts;
      var nn = performance.now();
      // 读数条的高度会随字体和数字宽度轻微变，隔一阵再量一次（量 DOM 会逼排版，不能每帧做）。
      if ((frameN++ % 60) === 0) measureHud();
      draw(nn);
    }
    raf = requestAnimationFrame(frame);
  }

  document.addEventListener("visibilitychange", function () {
    if (!document.hidden) kick();
  });

  /** 每半秒刷新「在跑 · 几秒」，只在真的有活在跑的时候。 */
  function schedule() {
    if (statusTimer) return;
    if (!C.running(rows, Date.now())) return;
    statusTimer = setTimeout(tick, 500);
  }

  function tick() {
    statusTimer = 0;
    var now = Date.now();
    paintSub();
    for (var i = 0; i < rows.length; i++) {
      var r = rows[i];
      if (r.type === "work") paintCard(r, now);
      else if (r.type === "group") {
        if (r._head) r._head.textContent = C.groupStat(r.items, now).toUpperCase();
        for (var k = 0; k < r.items.length; k++) paintCard(r.items[k], now);
      }
    }
    if (C.running(rows, now)) statusTimer = setTimeout(tick, 500);
  }

  /* 指针碰到哪个节点，就把那个岗位的连线和下游点亮。
   * ★ 这是**读数**，不是装饰：亮起来的是「这个岗位真的派过活 / 真的和谁同一件事动过手」。 */
  function nodeAt(clientX, clientY, rect) {
    var x = (clientX - rect.left) * (size.w / rect.width);
    var y = (clientY - rect.top) * (size.h / rect.height);
    for (var i = 0; i < NODE_HIT.length; i++) {
      var n = NODE_HIT[i];
      var dx = x - n.x, dy = y - n.y;
      if (dx * dx + dy * dy <= n.r * n.r) return n.role;
    }
    return null;
  }

  cv.addEventListener("pointermove", function (ev) {
    var r0 = cv.getBoundingClientRect();
    HOVER.role = nodeAt(ev.clientX, ev.clientY, r0);
  });

  cv.addEventListener("pointerleave", function () { HOVER.role = null; });
  cv.addEventListener("pointercancel", function () { HOVER.role = null; });

  /* 点地图上的楼 → 和点列表里那一行是同一件事（走同一条桥）。 */
  cv.addEventListener("click", function (ev) {
    var r = cv.getBoundingClientRect();
    var x = (ev.clientX - r.left) * (size.w / r.width);
    var y = (ev.clientY - r.top) * (size.h / r.height);
    for (var i = 0; i < hitSites.length; i++) {
      var s = hitSites[i];
      if (x >= s.x && x <= s.x + s.w && y >= s.y && y <= s.y + s.h) {
        if (window.ConMarn && window.ConMarn.openStep && s.wk && s.wk.id) {
          window.ConMarn.openStep(s.wk.id);
        }
        return;
      }
    }
  });

  /* 点一行 → 交出那一步的完整原文（Android 那边有 StepStore，见 showStepDetail）。 */
  listEl.addEventListener("click", function (ev) {
    var node = ev.target;
    while (node && node !== listEl && !node.getAttribute("data-id")) node = node.parentNode;
    if (!node || node === listEl) return;
    var id = parseInt(node.getAttribute("data-id"), 10);
    if (!id) return;
    if (window.ConMarn && window.ConMarn.openStep) window.ConMarn.openStep(id);
  });

  resize();
  kick();
  window.dashPush("[]");
})();
