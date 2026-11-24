/*
 * 调度大屏的**模型层**：把 AgentBus 的事件流折成「一行一行看得懂的事」。
 *
 * ★ 纯函数，浏览器和判据共用一份（`tools/dash-test.html` 就是拿它跑的）。
 *
 * ---
 *
 * ## 2026-11-05：结构从「圆心星形图」换成「时间往下排」
 *
 * 他的原话是「协同大屏的那个结构，也非常的不好」。他是对的，旧结构有三个坎：
 *
 * 1. **星形不表达先后。** 这张屏要回答的第一件事是「她刚才到底做了什么、按什么顺序」，
 *    而一圈点只能回答「谁和谁连过」。
 * 2. **并行的几份在图上分不开。** 同时派出去的三份会同时在路上跑，看起来像一团乱麻；
 *    而「同时派了三份、回来两份」正是这张屏最该一眼看清的事。
 * 3. **一圈只放得下四个角色**，名字挤成螺旋，还得靠截断。
 *
 * 现在的结构是一条**时间线**：每件事占一行，从上往下按发生顺序排；同时发出去的那几份
 * 收进同一个组里，组头写明「同时派给几位、回来几份」。
 *
 * ## 一条硬规矩：**只画真事**
 *
 * 每一行都必须对得上一条 [AgentEvent]。没有事件就没有行；「在跑」只在这份活**确实
 * 还没回来**的时候出现（`end === 0`），不是编一个动画。这条约束的来路见 FlowMath
 * 文件头那笔账 —— 好看的动效只要和事实对不上，它就是在撒谎。
 */
(function (root, factory) {
  if (typeof module === "object" && module.exports) module.exports = factory();
  else root.DashCore = factory();
})(this, function () {
  "use strict";

  /** 一屏最多排多少行。再多就是从上下文的滚动里抢注意力，而不是多知道什么。 */
  var MAX_ROWS = 60;

  /** 一行里任务/结果摘要最多显示多少字。点开有全文。 */
  var CLIP = 64;

  /** 来源的名字。 */
  function labelOf(from) {
    if (from === "self") return "她";
    if (from === "hub") return "中枢";
    if (from === "cloud") return "云端";
    return from || "—";
  }

  /** 事件类型的名字。 */
  function kindCn(kind) {
    switch (kind) {
      case "ask": return "派活";
      case "answer": return "回话";
      case "tool": return "动手";
      case "route": return "判定";
      case "note": return "记录";
      case "error": return "出错";
      default: return kind || "事件";
    }
  }

  /** 压成一行：换行变空格，超长截断并说明截了。 */
  function clip(s, n) {
    var t = String(s == null ? "" : s).replace(/\s+/g, " ").trim();
    var max = n || CLIP;
    return t.length <= max ? t : t.slice(0, max) + "…";
  }

  /** 两位数的序号。代号是 01 起的，不是 1 —— 等宽字里它们才对得齐。 */
  function two(n) {
    n = Math.max(0, Math.floor(n || 0));
    return n < 10 ? "0" + n : String(n);
  }

  /** 第 i 个岗位（0 起）的代号。场的节点和列表的行**都只能从这里取**。 */
  function codeOf(i) {
    return "VX-" + two((i || 0) + 1);
  }

  /** 秒数。小于 1 秒也写一位小数 —— 「0.4 秒」和「0 秒」是两件事。 */
  function fmtSec(ms) {
    if (!ms || ms < 0) ms = 0;
    var s = ms / 1000;
    return (s < 10 ? s.toFixed(1) : String(Math.round(s))) + "s";
  }

  /**
   * 事件流 → 显示用的行。
   *
   * 两种行：
   * - `work`：一次派活 + 它的回话（**按 `ref` 配对**），合起来是一张卡；
   *   没配上的（还在跑、或者这一轮被叫停了）也留一张卡，状态就是「在跑」或「没有回话」。
   * - `line`：不属于任何派活的事件（她说的、中枢判定/动手/记录、孤立错误），一行一条。
   *
   * 连续的 `work`（≥2）收进一个组 —— 那正是「同时派出去、还没回来」的样子。
   */
  function build(events) {
    var rows = [];
    var waiting = {};
    // 上一条是「接力」交接记录 → 紧跟着的这一张卡是这条链的下一段，缩进一级。
    var chained = false;
    // ★ 代号：按岗位**第一次出现**的先后编 VX-01、VX-02、VX-03……
    //   和 [rolesOf] 排场里那几个节点用的是同一条规则，所以列表里的 `VX-03`
    //   和场上第三个点**是同一个岗位** —— 这是「用代号」能成立的全部前提。
    //
    // ★ 前缀固定是 `VX`（他 2026-11-05 挑的那一版）：同一个番号体系下的个体，
    //   差别只在序号。换成每个岗位一个字母前缀的话，新岗位一出现就得**给它编字母**，
    //   而字母是凭什么给的没有依据 —— 代号一旦开始有含义，它就不再是代号了。
    var order = {};
    var orderN = 0;
    var list = events || [];
    for (var i = 0; i < list.length; i++) {
      var e = list[i];
      if (e.kind === "ask" && e.role) {
        if (!order[e.role]) order[e.role] = codeOf(orderN++);
        var w = {
          type: "work", id: e.id, role: e.role, code: order[e.role], task: e.text || "",
          start: e.at || 0, end: 0, answer: "", err: "", tok: 0,
          chained: chained
        };
        chained = false;
        rows.push(w);
        waiting[e.id] = w;
      } else if ((e.kind === "answer" || e.kind === "error") && waiting[e.ref]) {
        var t = waiting[e.ref];
        delete waiting[e.ref];
        t.end = e.at || t.start;
        t.tok = e.tok || 0;
        if (e.kind === "error") t.err = e.text || "出错了";
        else t.answer = e.text || "";
      } else {
        // ★ 接力段与段之间那条记录，中枢自己在 role 里写了「接力」。
        //   靠 **role** 认，不靠正文 —— 正文是给人看的，措辞随时会改；
        //   role 是这条记录的身份，改了它这条记录的含义就变了。
        var isChain = e.kind === "note" && e.role === "接力";
        if (isChain) chained = true;
        rows.push({
          type: "line", id: e.id, from: e.from || "", role: e.role || "",
          kind: e.kind || "", text: e.text || "", at: e.at || 0,
          chain: isChain
        });
      }
    }

    // ★ 只有**连着**的派活才成组。中间插了一句「中枢记录」就断开 ——
    //   那说明这是两次独立的派出，不是一次扇出。
    var out = [];
    var i2 = 0;
    while (i2 < rows.length) {
      if (rows[i2].type !== "work") {
        out.push(rows[i2]);
        i2++;
        continue;
      }
      var run = [];
      while (i2 < rows.length && rows[i2].type === "work") {
        run.push(rows[i2]);
        i2++;
      }
      if (run.length >= 2) out.push({ type: "group", items: run, id: run[0].id });
      else out.push(run[0]);
    }
    return out.length > MAX_ROWS ? out.slice(out.length - MAX_ROWS) : out;
  }

  /**
   * 一张派活卡现在的状态。
   *
   * ★ 三条路各自说自己的实话：出错了 / 还在跑 / 回来了。
   *   把「还在跑」写成「等结果」之类模糊的话，等于让她在没回来的时候看起来像回来了。
   */
  function statusOf(row, now) {
    if (!row) return "";
    if (row.err) return "失败 · " + fmtSec((row.end || now) - row.start);
    if (!row.end) return "在跑 · " + fmtSec((now || 0) - row.start);
    // ★ token = 这一步真花掉的量（服务端在回包里自报的 usage）。
    //   回来才记：还没回来的那一步花多少不知道，不估。
    return "回来 " + (row.answer || "").length + " 字 · " + fmtSec(row.end - row.start) +
      " · +" + fmtTok(row.tok) + " tok";
  }

  /** token 读数的写法：一千以上折成 k。 */
  function fmtTok(n) {
    n = n || 0;
    if (n <= 0) return "0";
    if (n < 1000) return String(n);
    if (n < 1000000) return (n / 1000).toFixed(n < 10000 ? 1 : 0) + "k";
    return (n / 1000000).toFixed(1) + "m";
  }

  /** 这一组回来几份 / 一共几份。 */
  function groupStat(items, now) {
    var done = 0, failed = 0, toks = 0;
    for (var i = 0; i < items.length; i++) {
      if (items[i].err) failed++;
      else if (items[i].end) done++;
      toks += (items[i].tok || 0);
    }
    var head = "同时派给 " + items.length + " 位 · 回来 " + done;
    if (failed) head += " · 失败 " + failed;
    if (toks) head += " · +" + fmtTok(toks) + " tok";
    if (done + failed < items.length) head += " · " + (items.length - done - failed) + " 位还在跑";
    return head;
  }

  /**
   * 曲线要的那些点：**每一步真实花掉的秒数**，按派活的先后排。
   *
   * ★ 2026-11-05 他要「像训练大屏那样的曲线」。训练大屏画的是每轮 loss，
   *   这里能画、且**真的有数**的只有一样：一次派活从发出去到拿到回话，
   *   中间花了多少秒。所以纵轴是秒，横轴是第几步 —— 不是随手编一条好看的线。
   * ★ 只收**配上对**的（派活找到了它自己的回话/出错）。还在跑的不进曲线：
   *   它没有终点，画上去就等于替它宣布了一个还没发生的结果。
   * ★ 出错的那一步**照样算一点** —— 它也真的花掉了那么多秒；`err` 让曲线标红，
   *   不是把它从账上抹掉。
   */
  function curve(events) {
    var waiting = {};
    var out = [];
    var list = events || [];
    for (var i = 0; i < list.length; i++) {
      var e = list[i];
      if (e.kind === "ask" && e.role) {
        waiting[e.id] = e;
      } else if ((e.kind === "answer" || e.kind === "error") && waiting[e.ref]) {
        var a = waiting[e.ref];
        delete waiting[e.ref];
        var ms = Math.max(0, (e.at || 0) - (a.at || 0));
        out.push({
          id: a.id, role: a.role, err: e.kind === "error",
          sec: ms / 1000, at: a.at || 0,
          // 这一步真花掉的 token（0 = 服务端没报账）。和秒数是同一步的两个读数。
          tok: e.tok || 0
        });
      }
    }
    return out;
  }

  /**
   * 按**岗位**分账：这个岗位被派了几次、回来几次、错了几个、一共花了多少 token。
   *
   * ★ token 只认**回话/出错**那一条上写的数（服务端在回包里自报的 `usage`）。
   *   派活那条上没有数 —— 请求发出去的时候还不知道生成多少。
   * ★ 没有账的（本地算的、服务端没给 `usage`）就是 0，**不估**。
   */
  function byRole(events) {
    var out = {};
    var list = events || [];
    for (var i = 0; i < list.length; i++) {
      var e = list[i];
      if (!e.role) continue;
      var r = out[e.role] || (out[e.role] = { role: e.role, ask: 0, back: 0, err: 0, tok: 0 });
      if (e.kind === "ask") r.ask++;
      else if (e.kind === "answer") { r.back++; r.tok += (e.tok || 0); }
      else if (e.kind === "error") { r.back++; r.err++; r.tok += (e.tok || 0); }
    }
    return out;
  }

  /** 这一屏一共花掉多少 token。和 [byRole] 同一个口径。 */
  function tokens(events) {
    var sum = 0;
    var list = events || [];
    for (var i = 0; i < list.length; i++) sum += (list[i].tok || 0);
    return sum;
  }

  /**
   * 这一屏上出现过的角色，**按第一次出现的顺序**排。
   *
   * ★ 顺序要稳定：中央那张放射图按这个顺序摆节点，顺序一跳，节点就换位置，
   *   看着像「刚刚谁上线了」，其实什么都没发生。
   */
  function rolesOf(events) {
    var seen = {};
    var out = [];
    var list = events || [];
    for (var i = 0; i < list.length; i++) {
      var r = list[i].role;
      if (list[i].kind === "ask" && r && !seen[r]) {
        seen[r] = 1;
        out.push(r);
      }
    }
    // ★ 2026-11-06：一次分工最多派 8 个角色了（`FanoutMath.MAX_ROLES`），这一屏的
    //   岗位面板也要跟得上 —— 卡在 6 的话，最后两个人干完活在这张屏上根本不存在。
    return out.slice(0, 8);
  }

  /**
   * 顶栏那几个数：派出去几份、回来几份、失败几份、还在跑几份。
   *
   * ★ 这是这一屏唯一「汇总」的地方，所以它必须和下面的卡片**同一口径**：
   *   数的是卡片（一张卡 = 一次派活），不是事件条数 —— 一次派活在事件流里是两条。
   */
  function stats(rows) {
    var s = { asks: 0, answered: 0, failed: 0, running: 0 };
    function one(w) {
      s.asks++;
      if (w.err) s.failed++;
      else if (w.end) s.answered++;
      else s.running++;
    }
    for (var i = 0; i < rows.length; i++) {
      if (rows[i].type === "work") one(rows[i]);
      else if (rows[i].type === "group") {
        for (var k = 0; k < rows[i].items.length; k++) one(rows[i].items[k]);
      }
    }
    return s;
  }

  /** 这一屏最早一条事件到现在多久。没有事件回 0。 */
  function span(events, now) {
    if (!events || !events.length) return 0;
    var t = 0;
    for (var i = 0; i < events.length; i++) {
      if (events[i].at && (!t || events[i].at < t)) t = events[i].at;
    }
    return t && now ? Math.max(0, now - t) : 0;
  }

  /** 还有没有没回来的活 —— 有才需要每半秒刷一次秒数。 */
  function running(rows, now) {
    for (var i = 0; i < rows.length; i++) {
      var r = rows[i];
      if (r.type === "work" && !r.end && !r.err) return true;
      if (r.type === "group") {
        for (var k = 0; k < r.items.length; k++) {
          if (!r.items[k].end && !r.items[k].err) return true;
        }
      }
    }
    return false;
  }

  /** 一行事件给人看的摘要。 */
  function lineOf(row) {
    var who = labelOf(row.from);
    if (row.role) who += "·" + row.role;
    return { who: who, kind: kindCn(row.kind), text: clip(row.text) };
  }

  return {
    MAX_ROWS: MAX_ROWS,
    CLIP: CLIP,
    labelOf: labelOf,
    kindCn: kindCn,
    clip: clip,
    fmtSec: fmtSec,
    codeOf: codeOf,
    build: build,
    curve: curve,
    byRole: byRole,
    tokens: tokens,
    statusOf: statusOf,
    groupStat: groupStat,
    stats: stats,
    span: span,
    rolesOf: rolesOf,
    running: running,
    lineOf: lineOf
  };
});
