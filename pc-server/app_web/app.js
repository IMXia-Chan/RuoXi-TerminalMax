'use strict';

/* ConMarn 电脑端界面。
   数据全部来自本机回环上的界面服务(conmarn_app.py),写操作一律 POST 并带 X-ConMarn 头。 */

const $ = (sel, root = document) => root.querySelector(sel);
const $$ = (sel, root = document) => Array.from(root.querySelectorAll(sel));

const TITLES = {
  overview: '概览',
  pair: '配对',
  media: '媒体',
  files: '文件',
  log: '日志',
  settings: '设置'
};

const state = {
  page: 'overview',
  status: null,
  unlocked: false,
  masterPin: '',
  gateMsg: '',
  qrAt: 0,
  localPath: '',
  phoneLoading: false,
  logAuto: true,
  frameUrl: null,
  frameBusy: false,
  autoBusy: false,
  forgetArmed: 0
};

/* ---------- 基础请求 ---------- */

async function getJSON(path) {
  if (Demo.on()) return Demo.get(path);
  try {
    const r = await fetch(path, { cache: 'no-store' });
    return await r.json();
  } catch (e) {
    return { ok: false, err: 'ui_fetch_failed' };
  }
}

async function post(path, body) {
  if (Demo.on()) return Demo.post(path, body);
  try {
    const init = { method: 'POST', cache: 'no-store', headers: { 'X-ConMarn': '1' } };
    if (body !== undefined) {
      init.headers['Content-Type'] = 'application/json';
      init.body = JSON.stringify(body);
    }
    const r = await fetch(path, init);
    return await r.json();
  } catch (e) {
    return { ok: false, err: 'ui_fetch_failed' };
  }
}

/* ---------- 格式化 ---------- */

function fmtDuration(sec) {
  sec = Math.max(0, Math.floor(Number(sec) || 0));
  const h = Math.floor(sec / 3600);
  const m = Math.floor((sec % 3600) / 60);
  const s = sec % 60;
  if (h) return h + ' 小时 ' + m + ' 分';
  if (m) return m + ' 分 ' + String(s).padStart(2, '0') + ' 秒';
  return s + ' 秒';
}

function fmtSize(bytes) {
  const n = Number(bytes) || 0;
  if (n < 1024) return n + ' B';
  const units = ['KB', 'MB', 'GB', 'TB'];
  let v = n / 1024;
  let i = 0;
  while (v >= 1024 && i < units.length - 1) { v /= 1024; i++; }
  return (v >= 10 ? v.toFixed(0) : v.toFixed(1)) + ' ' + units[i];
}

function fmtTime(ts) {
  if (!ts) return '—';
  const d = new Date(Number(ts) * 1000);
  if (isNaN(d.getTime())) return '—';
  const p = (x) => String(x).padStart(2, '0');
  return p(d.getHours()) + ':' + p(d.getMinutes()) + ':' + p(d.getSeconds());
}

function b64(text) {
  const bytes = new TextEncoder().encode(text);
  let bin = '';
  bytes.forEach((b) => { bin += String.fromCharCode(b); });
  return btoa(bin);
}

/* ---------- 提示 ---------- */

let toastTimer = null;
function toast(text, kind) {
  const el = $('#toast');
  el.textContent = text;
  el.classList.toggle('bad', kind === 'bad');
  el.classList.add('on');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.classList.remove('on'), 3200);
}

/* ---------- 导航 ---------- */

function go(page) {
  state.page = page;
  $$('.nav button').forEach((b) => b.classList.toggle('on', b.dataset.page === page));
  $$('.page').forEach((p) => p.classList.toggle('on', p.id === 'page-' + page));
  $('#pageTitle').textContent = TITLES[page] || page;

  if (page === 'pair') renderPair();
  if (page === 'files') {
    loadLocal(state.localPath);
    loadPhone(0);
  }
  if (page === 'log') loadLog(true);
  if (page === 'settings') { loadAutostart(); syncDemoSwitch(); }
  if (page === 'media') tickMedia();
}

$$('.nav button').forEach((b) => {
  b.addEventListener('click', () => go(b.dataset.page));
});

/* ---------- 状态 ---------- */

function setSwitch(el, on) {
  const node = typeof el === 'string' ? $(el) : el;
  if (!node) return;
  node.classList.toggle('on', !!on);
  node.setAttribute('aria-checked', on ? 'true' : 'false');
}

function setChip(sel, text, kind) {
  const el = $(sel);
  const label = el.querySelector('span');
  if (label) label.textContent = text;
  el.classList.remove('good', 'bad', 'warn');
  if (kind) el.classList.add(kind);
}

async function refreshStatus() {
  const s = await getJSON('/api/status');
  state.status = s;
  renderStatus(s);
  maybeRefreshQr();
}

function renderStatus(s) {
  const ok = !!(s && s.ok);
  const down = !ok && s && s.err === 'service_down';
  const stale = !ok && s && s.err === 'bridge_missing';

  $('#banner').classList.toggle('on', !ok);
  $('#chipDemo').classList.toggle('hidden', !Demo.on());

  const railDot = $('#railDot');
  railDot.classList.remove('good', 'bad', 'warn');
  if (ok) {
    railDot.classList.add('good');
    $('#railText').textContent = '服务已连接';
  } else if (down) {
    railDot.classList.add('bad');
    $('#railText').textContent = '服务未运行';
  } else if (stale) {
    railDot.classList.add('warn');
    $('#railText').textContent = '服务版本过旧';
  } else {
    railDot.classList.add('bad');
    $('#railText').textContent = '无法读取服务状态';
  }

  setChip('#chipSvc', ok ? '服务已连接' : (down ? '服务未运行' : '服务状态未知'), ok ? 'good' : (down ? 'bad' : 'warn'));
  setChip('#chipPhone', !ok ? '手机' : (s.connected > 0 ? '手机已连接' : '手机未连接'), ok && s.connected > 0 ? 'good' : null);
  setChip('#chipPair', !ok ? '配对' : (s.paired ? '已配对' : '未配对'), ok ? (s.paired ? 'good' : 'warn') : null);

  /* 概览 */
  $('#ovUptime').textContent = ok ? fmtDuration(s.uptime) : '—';
  $('#ovBridge').textContent = ok ? ('桥接协议 v' + (s.bridge || '?')) : '';
  $('#ovPhone').textContent = ok ? (s.connected > 0 ? '已连接' : '未连接') : '—';
  $('#ovPhoneSub').textContent = ok && s.last_conn_at ? ('最近 ' + fmtTime(s.last_conn_at)) : '';
  $('#ovPair').textContent = ok ? (s.paired ? '已配对' : '未配对') : '—';
  $('#ovPairSub').textContent = ok ? (s.pin ? '配对码有效' : '') : '';

  $('#ovAddr').textContent = ok ? ((s.lan_ip || '—') + ' : ' + (s.port || '—')) : '—';
  $('#ovPort').textContent = ok ? String(s.port || '—') : '—';
  $('#ovLastConn').textContent = ok
    ? (s.last_conn_at ? ((s.last_addr || '未知地址') + ' · ' + fmtTime(s.last_conn_at)) : '本次运行内无记录')
    : '—';
  $('#ovDir').textContent = ok ? (s.transfer_dir || '—') : '—';
  $('#ovElev').textContent = ok ? (s.elevated ? '管理员权限' : '普通权限') : '—';

  /* 配对码 */
  const pin = ok ? s.pin : null;
  $('#ovPin').textContent = pin || '—';
  const left = ok ? Number(s.pin_left) || 0 : 0;
  const ttl = ok ? Number(s.pin_ttl) || 120 : 120;
  $('#ovPinBar').style.width = pin ? Math.round(Math.min(1, left / ttl) * 100) + '%' : '0%';
  $('#ovPinHint').textContent = pin ? ('剩余 ' + left + ' 秒') : '当前没有待处理的配对请求。';

  /* 最近事件 */
  const ul = $('#ovEvents');
  ul.textContent = '';
  const notes = ok ? (s.notes || []) : [];
  if (!notes.length) {
    const li = document.createElement('li');
    li.className = 'empty';
    li.textContent = '暂无事件。';
    ul.appendChild(li);
  } else {
    notes.slice(-8).reverse().forEach((n) => {
      const li = document.createElement('li');
      const t = document.createElement('time');
      t.textContent = fmtTime(n.at);
      const span = document.createElement('span');
      span.textContent = n.msg || n.kind || '';
      li.appendChild(t);
      li.appendChild(span);
      ul.appendChild(li);
    });
  }

  /* 设置页的服务行 */
  $('#svcState').textContent = ok
    ? ('运行中 · 已运行 ' + fmtDuration(s.uptime))
    : (down ? '未运行' : '状态未知');
  $('#svcStart').disabled = ok;
  $('#setDir').textContent = ok ? (s.transfer_dir || '—') : '—';
  $('#setVersion').textContent = ok
    ? ('界面 v1.0.0 · 桥接协议 v' + (s.bridge || '?') + ' · 控制端口 ' + (s.port || '—'))
    : '界面 v1.0.0';

  /* 设置页的开关在服务不可用时禁用 */
  ['#swAuto'].forEach((sel) => { $(sel).disabled = !ok; });

  if (state.page === 'pair') renderPair();
}

/* ---------- 配对 ---------- */

function renderPair() {
  const s = state.status || {};
  const ok = !!s.ok;
  const master = !!(ok && s.master);

  $('#pairGate').classList.toggle('hidden', state.unlocked);
  $('#pairBody').classList.toggle('hidden', !state.unlocked);

  $('#pairGateTitle').textContent = master ? '输入登录 PIN' : '首次使用,请设置登录 PIN';
  $('#pairUnlockBtn').classList.toggle('hidden', !master);
  $('#pairSetBtn').classList.toggle('hidden', master);

  $('#pairGateMsg').textContent = state.gateMsg || (ok ? '' : '电脑端服务未运行。');

  const forgetBtn = $('#pairForgetBtn');
  const armed = Date.now() - state.forgetArmed < 5000;
  forgetBtn.textContent = armed ? '确认解除' : '解除配对';
  forgetBtn.classList.toggle('solid', armed);
  $('#pairForgetState').textContent = s.paired
    ? '清除本机保存的配对密钥,手机需要重新配对。'
    : '当前没有配对记录。';
  forgetBtn.disabled = !ok || !s.paired;
}

function showQr(r) {
  const img = $('#pairQr');
  const blank = $('#pairQrEmpty');
  if (r && (r.png || r.img)) {
    img.src = r.img ? r.img : ('data:image/png;base64,' + r.png);
    img.classList.remove('hidden');
    blank.classList.add('hidden');
  } else {
    img.classList.add('hidden');
    img.removeAttribute('src');
    blank.classList.remove('hidden');
  }
  $('#pairCode').textContent = (r && r.qr) ? r.qr : '—';
  $('#pairCodeLabel').textContent = Demo.on() ? '示例配对内容' : '一次性配对码';
  $('#pairQrHint').textContent = (r && r.qr)
    ? (Demo.on() ? '演示模式使用的示例二维码,不能用于配对。' : '二维码每次配对只能使用一次。')
    : '在手机上发起连接后,此处会显示二维码。';
}

async function unlock() {
  const pin = $('#pairPin').value.trim();
  if (!/^\d{6}$/.test(pin)) {
    state.gateMsg = '请输入 6 位数字。';
    $('#pairGateMsg').textContent = state.gateMsg;
    return;
  }
  const r = await post('/api/pair/unlock', { pin });
  if (r.ok) {
    state.unlocked = true;
    state.masterPin = pin;
    state.qrAt = Date.now();
    state.gateMsg = '';
    $('#pairPin').value = '';
    renderPair();
    showQr(r);
    toast('已解锁');
    return;
  }
  if (r.need_set) {
    state.gateMsg = '尚未设置登录 PIN,请先设置。';
    $('#pairGateMsg').textContent = state.gateMsg;
    $('#pairUnlockBtn').classList.add('hidden');
    $('#pairSetBtn').classList.remove('hidden');
    return;
  }
  state.gateMsg = r.err || '解锁失败';
  $('#pairGateMsg').textContent = state.gateMsg;
}

async function setPin() {
  const pin = $('#pairPin').value.trim();
  if (!/^\d{6}$/.test(pin)) {
    state.gateMsg = '请输入 6 位数字。';
    $('#pairGateMsg').textContent = state.gateMsg;
    return;
  }
  const r = await post('/api/pair/setpin', { pin });
  if (!r.ok) {
    state.gateMsg = r.err || '设置失败';
    $('#pairGateMsg').textContent = state.gateMsg;
    return;
  }
  state.unlocked = true;
  state.masterPin = pin;
  state.qrAt = Date.now();
  $('#pairPin').value = '';
  $('#pairGateMsg').textContent = '';
  renderPair();
  showQr(r);
  toast('登录 PIN 已设置');
  refreshStatus();
}

async function maybeRefreshQr() {
  const s = state.status || {};
  if (state.page !== 'pair' || !state.unlocked || !state.masterPin) return;
  if (!s.ok || !s.setup_pending) return;
  if (Date.now() - state.qrAt < 3000) return;
  state.qrAt = Date.now();
  const r = await post('/api/pair/unlock', { pin: state.masterPin });
  if (r.ok) showQr(r);
}

$('#pairUnlockBtn').addEventListener('click', unlock);
$('#pairSetBtn').addEventListener('click', setPin);
$('#pairPin').addEventListener('keydown', (e) => {
  if (e.key === 'Enter') {
    if ($('#pairSetBtn').classList.contains('hidden')) unlock();
    else setPin();
  }
});

$('#pairForgetBtn').addEventListener('click', async () => {
  if (Date.now() - state.forgetArmed > 5000) {
    state.forgetArmed = Date.now();
    renderPair();
    toast('再次点击以确认解除配对');
    return;
  }
  state.forgetArmed = 0;
  const r = await post('/api/pair/forget', { confirm: 'FORGET' });
  if (r.ok) {
    state.unlocked = false;
    state.masterPin = '';
    showQr(null);
    toast('已解除配对');
    refreshStatus();
  } else {
    toast(r.err || '解除配对失败', 'bad');
  }
  renderPair();
});

/* ---------- 媒体 ---------- */

async function tickMedia() {
  if (state.page !== 'media') return;
  const l = await getJSON('/api/level');
  if (l.ok) {
    setSwitch('#swCam', !!l.cam);
    setSwitch('#swMic', !!l.mic);
    $('#levelBar').style.width = Math.round(Math.max(0, Math.min(1, Number(l.level) || 0)) * 100) + '%';
    if (l.cam) await pullFrame();
    else clearFrame();
  } else {
    clearFrame();
  }
}

async function pullFrame() {
  if (Demo.on()) { clearFrame(); return; }
  if (state.frameBusy) return;
  state.frameBusy = true;
  try {
    const r = await fetch('/api/frame.jpg', { cache: 'no-store' });
    if (!r.ok) { clearFrame(); return; }
    const blob = await r.blob();
    if (!blob.size) { clearFrame(); return; }
    const url = URL.createObjectURL(blob);
    $('#camFrame').src = url;
    $('#camFrameBox').classList.add('has');
    if (state.frameUrl) URL.revokeObjectURL(state.frameUrl);
    state.frameUrl = url;
  } catch (e) {
    clearFrame();
  } finally {
    state.frameBusy = false;
  }
}

function clearFrame() {
  $('#camFrameBox').classList.remove('has');
  $('#camEmpty').textContent = (Demo.on() && $('#swCam').classList.contains('on'))
    ? '演示模式不显示摄像头画面'
    : '电脑端未开启摄像头';
}

async function toggleDev(dev) {
  const el = dev === 'CAM' ? $('#swCam') : $('#swMic');
  const on = !el.classList.contains('on');
  el.disabled = true;
  const r = await post('/api/media', { dev: dev, on: on });
  el.disabled = false;
  if (r.ok) {
    setSwitch(el, on);
    if (!r.delivered) toast('电脑端未连接到手机,指令未送达', 'bad');
  } else {
    toast(r.err || '操作失败', 'bad');
  }
  tickMedia();
}

$('#swCam').addEventListener('click', () => toggleDev('CAM'));
$('#swMic').addEventListener('click', () => toggleDev('MIC'));

/* ---------- 文件 ---------- */

function parentOf(p) {
  const i = p.lastIndexOf('/');
  return i < 0 ? '' : p.slice(0, i);
}

function childOf(p, name) {
  return p ? p + '/' + name : name;
}

function emptyRow(text) {
  const li = document.createElement('li');
  li.className = 'empty';
  li.textContent = text;
  return li;
}

async function loadLocal(rel) {
  state.localPath = rel || '';
  const r = await getJSON('/api/files?scope=local&path=' + encodeURIComponent(b64(state.localPath)));
  const ul = $('#localList');
  ul.textContent = '';
  $('#localPath').textContent = '/' + state.localPath;

  if (!r.ok || r.entries === null || r.entries === undefined) {
    ul.appendChild(emptyRow(r.ok ? '目录不存在或无法访问。' : (r.err === 'service_down' ? '电脑端服务未运行。' : (r.err || '读取失败'))));
    return;
  }
  if (!r.entries.length) {
    ul.appendChild(emptyRow('目录为空。'));
    return;
  }

  r.entries.forEach((e) => {
    const li = document.createElement('li');
    const name = document.createElement('span');
    name.className = 'name' + (e.d ? ' dir' : '');
    name.textContent = (e.d ? '文件夹 · ' : '') + e.n;
    name.title = e.n;
    if (e.d) name.addEventListener('click', () => loadLocal(childOf(state.localPath, e.n)));

    const size = document.createElement('span');
    size.className = 'size';
    size.textContent = e.d ? '' : fmtSize(e.s);

    li.appendChild(name);
    li.appendChild(size);

    if (!e.d) {
      const btn = document.createElement('button');
      btn.type = 'button';
      btn.className = 'btn ghost';
      btn.textContent = '发送到手机';
      btn.addEventListener('click', () => sendToPhone(e.n));
      li.appendChild(btn);
    }
    ul.appendChild(li);
  });
}

async function sendToPhone(name) {
  const r = await post('/api/files/send', { name: name });
  if (r.ok) toast('已提交发送:' + name);
  else toast(r.err || '发送失败', 'bad');
}

async function loadPhone(pollTimes) {
  if (state.phoneLoading) return;
  state.phoneLoading = true;
  try {
    let r = await getJSON('/api/files?scope=phone');
    let tries = 0;
    while (pollTimes && tries < pollTimes && (!r.ok || r.entries === null || r.entries === undefined)) {
      tries++;
      await new Promise((res) => setTimeout(res, 1200));
      r = await getJSON('/api/files?scope=phone');
    }
    renderPhone(r);
  } finally {
    state.phoneLoading = false;
  }
}

function renderPhone(r) {
  const ul = $('#phoneList');
  ul.textContent = '';

  if (!r.ok) {
    ul.appendChild(emptyRow(r.err === 'service_down' ? '电脑端服务未运行。' : (r.err || '读取失败')));
    return;
  }
  if (r.entries === null || r.entries === undefined) {
    ul.appendChild(emptyRow('尚未读取。请连接手机后点击刷新。'));
    return;
  }
  if (!r.entries.length) {
    ul.appendChild(emptyRow('手机中转目录为空。'));
    return;
  }
  r.entries.forEach((e) => {
    const li = document.createElement('li');
    const name = document.createElement('span');
    name.className = 'name';
    name.textContent = (e.d ? '文件夹 · ' : '') + e.n;
    name.title = e.n;

    const size = document.createElement('span');
    size.className = 'size';
    size.textContent = e.d ? '' : fmtSize(e.s);

    li.appendChild(name);
    li.appendChild(size);

    if (!e.d) {
      const btn = document.createElement('button');
      btn.type = 'button';
      btn.className = 'btn ghost';
      btn.textContent = '取回';
      btn.addEventListener('click', () => pullFromPhone(e.n));
      li.appendChild(btn);
    }
    ul.appendChild(li);
  });
}

async function pullFromPhone(name) {
  const r = await post('/api/files/pull', { name: name });
  if (r.ok) toast('已提交取回:' + name);
  else toast(r.err || '取回失败', 'bad');
}

async function refreshPhone() {
  const r = await post('/api/files/refresh');
  if (!r.ok) {
    toast(r.err || '请求失败', 'bad');
    return;
  }
  loadPhone(4);
}

async function uploadFiles(files) {
  if (!files || !files.length) return;
  if (Demo.on()) {
    toast('演示模式下不保存文件', 'bad');
    return;
  }
  for (const f of files) {
    try {
      const resp = await fetch('/api/files/upload', {
        method: 'POST',
        cache: 'no-store',
        headers: { 'X-ConMarn': '1', 'X-Filename': b64(f.name) },
        body: f
      });
      const j = await resp.json();
      if (j.ok) toast('已上传:' + j.name);
      else toast(j.err || ('上传失败:' + f.name), 'bad');
    } catch (e) {
      toast('上传失败:' + f.name, 'bad');
    }
  }
  loadLocal(state.localPath);
}

$('#localRefresh').addEventListener('click', () => loadLocal(state.localPath));
$('#localUp').addEventListener('click', () => loadLocal(parentOf(state.localPath)));
$('#localOpen').addEventListener('click', async () => {
  const r = await post('/api/open', { target: 'TRANSFER_DIR' });
  if (!r.ok) toast(r.err || '无法打开目录', 'bad');
});
$('#localUploadBtn').addEventListener('click', () => $('#localFile').click());
$('#localFile').addEventListener('change', (e) => {
  uploadFiles(e.target.files);
  e.target.value = '';
});
$('#phoneRefresh').addEventListener('click', refreshPhone);
$('#setOpenDir').addEventListener('click', async () => {
  const r = await post('/api/open', { target: 'TRANSFER_DIR' });
  if (!r.ok) toast(r.err || '无法打开目录', 'bad');
});

/* ---------- 日志 ---------- */

async function loadLog(forceScroll) {
  const r = await getJSON('/api/log?n=300');
  const box = $('#logBox');
  const atBottom = box.scrollTop + box.clientHeight >= box.scrollHeight - 24;
  if (!r.ok) {
    box.textContent = r.err === 'service_down' ? '电脑端服务未运行。' : (r.err || '读取日志失败');
    return;
  }
  const lines = r.lines || [];
  box.textContent = lines.length ? lines.join('\n') : '日志为空。';
  if (forceScroll || atBottom) box.scrollTop = box.scrollHeight;
}

$('#logRefresh').addEventListener('click', () => loadLog(true));
$('#swLog').addEventListener('click', () => {
  state.logAuto = !state.logAuto;
  setSwitch('#swLog', state.logAuto);
});

/* ---------- 设置 ---------- */

async function loadAutostart() {
  $('#autoState').textContent = '正在读取';
  const r = await getJSON('/api/autostart');
  if (!r.ok) {
    $('#autoState').textContent = r.err === 'service_down' ? '电脑端服务未运行' : (r.err || '无法读取');
    return;
  }
  setSwitch('#swAuto', !!r.enabled);
  if (r.enabled) {
    $('#autoState').textContent = r.source === 'task'
      ? ('已启用 · 计划任务 ' + (r.task_name || ''))
      : '已启用 · 启动文件夹';
  } else {
    $('#autoState').textContent = '已关闭';
  }
}

$('#swAuto').addEventListener('click', async () => {
  if (state.autoBusy) return;
  state.autoBusy = true;
  const el = $('#swAuto');
  const on = !el.classList.contains('on');
  el.disabled = true;
  $('#autoState').textContent = '正在写入';
  const r = await post('/api/autostart', { on: on });
  state.autoBusy = false;
  el.disabled = false;
  if (r.ok) {
    setSwitch(el, !!r.enabled);
    toast(on ? '开机自启已启用,下次登录生效' : '开机自启已关闭,下次登录生效');
  } else {
    toast(r.err || '设置失败', 'bad');
  }
  loadAutostart();
});

async function startService() {
  const btns = [$('#svcStart'), $('#bannerStart')];
  btns.forEach((b) => { b.disabled = true; });
  toast('正在启动电脑端服务');
  const r = await post('/api/service/start');
  btns.forEach((b) => { b.disabled = false; });
  if (r.ok) {
    toast('电脑端服务已启动');
    await refreshStatus();
  } else {
    toast(r.err || '启动失败', 'bad');
    await refreshStatus();
  }
}

$('#svcStart').addEventListener('click', startService);
$('#bannerStart').addEventListener('click', startService);

/* ---------- 演示模式 ---------- */

function syncDemoSwitch() {
  setSwitch('#swDemo', Demo.on());
  $('#demoState').textContent = Demo.on() ? '开启 · 界面显示示例数据' : '关闭 · 界面显示真实数据';
}

$('#swDemo').addEventListener('click', async () => {
  Demo.set(!Demo.on());
  syncDemoSwitch();

  /* 切换后所有页面都按新的数据源重来一遍 */
  state.unlocked = false;
  state.masterPin = '';
  state.gateMsg = '';
  state.localPath = '';
  state.qrAt = 0;
  $('#pairPin').value = '';
  showQr(null);

  await refreshStatus();
  if (state.page === 'pair') renderPair();
  if (state.page === 'files') { loadLocal(''); loadPhone(0); }
  if (state.page === 'log') loadLog(true);
  if (state.page === 'media') tickMedia();
  toast(Demo.on() ? '演示模式已开启' : '演示模式已关闭');
});

/* ---------- 定时器 ---------- */

setInterval(refreshStatus, 1500);
setInterval(() => {
  if (state.page === 'media') tickMedia();
}, 700);
setInterval(() => {
  if (state.page === 'log' && state.logAuto) loadLog(false);
}, 2000);

/* ?demo=1 / ?demo=0 直接切换演示模式,并记住选择 */
const demoParam = new URLSearchParams(location.search).get('demo');
if (demoParam === '1' || demoParam === '0') Demo.set(demoParam === '1');
syncDemoSwitch();

/* 页面进入时立刻取一次状态 */
refreshStatus();

/* 允许用 ?p=配对 这样的地址直接打开某一页 */
const initialPage = new URLSearchParams(location.search).get('p');
if (initialPage && TITLES[initialPage]) go(initialPage);
