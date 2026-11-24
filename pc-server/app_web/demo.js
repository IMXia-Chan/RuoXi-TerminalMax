'use strict';

/* 演示模式。
 *
 * 打开后,界面上的每一次读取都返回下面的示例数据,不连接电脑端服务;
 * 每一次写入(开关、传文件、配对、开工自启)只在内存里改状态,不产生任何真实动作。
 * 因此录屏或截图时画面里不会出现真实的地址、路径、文件名与配对码。
 *
 * 顶栏会常驻「演示模式」标记,设置页有开关和说明;关闭后立刻回到真实数据。
 */

const Demo = (() => {
  const KEY = 'conmarn.demo';
  const PIN_TTL = 120;
  const START = Math.floor(Date.now() / 1000) - 3725;

  /* 内存状态:进入演示模式时重置,退出不做任何处理 */
  const st = {
    paired: true,
    connected: true,
    cam: false,
    mic: true,
    level: 0.38,
    auto: true
  };

  let pinStart = Date.now() - 33000;
  let lastConn = Math.floor(Date.now() / 1000) - 95;

  /* 示例数据一律使用代号:不出现真实的地址、路径、端口与文件名。 */
  const HOST = 'CM-HOST';
  const PORT = 'CM-P1';
  const PEER = 'CM-PHONE';
  const STATION = 'C:\\CM\\STATION';

  const LOCAL = {
    '': [
      { n: 'DOCS', d: 1, s: 0 },
      { n: 'SHOTS', d: 1, s: 0 },
      { n: 'DOC-01.pdf', d: 0, s: 2457600 },
      { n: 'DOC-02.docx', d: 0, s: 51200 },
      { n: 'IMG-01.jpg', d: 0, s: 3145728 },
      { n: 'PKG-01.zip', d: 0, s: 76546048 }
    ],
    'DOCS': [
      { n: 'DOC-03.pdf', d: 0, s: 1048576 },
      { n: 'DOC-04.pdf', d: 0, s: 1572864 },
      { n: 'REF', d: 1, s: 0 }
    ],
    'DOCS/REF': [
      { n: 'DOC-05.pdf', d: 0, s: 524288 }
    ],
    'SHOTS': [
      { n: 'IMG-02.png', d: 0, s: 262144 },
      { n: 'IMG-03.png', d: 0, s: 288358 }
    ]
  };

  const PHONE = [
    { n: 'AUD-01.m4a', d: 0, s: 5242880 },
    { n: 'DOC-06.docx', d: 0, s: 51200 },
    { n: 'IMG-04.jpg', d: 0, s: 2202009 }
  ];

  const LOG = [
    '[server] 电脑端服务已启动',
    '[server] 监听 ' + HOST + ':' + PORT,
    '[discovery] UDP 就绪',
    '[auth] 收到连接 ' + PEER,
    '[auth] 配对码校验通过',
    '[auth] TOTP 校验通过,会话建立',
    '[media] 手机请求媒体状态',
    '[ctrl] CMD MIC 1',
    '[file] 收到 DOC-06.docx(50.0 KB)',
    '[file] 已发送 DOC-01.pdf(2.3 MB)',
    '[ctrl] CMD CAM 1',
    '[media] 视频帧 1280x720'
  ];

  function on() {
    return localStorage.getItem(KEY) === '1';
  }

  function set(flag) {
    if (flag) {
      st.paired = true;
      st.connected = true;
      st.cam = false;
      st.mic = true;
      st.level = 0.38;
      st.auto = true;
      pinStart = Date.now() - 33000;
      lastConn = Math.floor(Date.now() / 1000) - 95;
    }
    localStorage.setItem(KEY, flag ? '1' : '0');
  }

  function unb64(text) {
    try {
      const bin = atob(text);
      const bytes = Uint8Array.from(bin, (c) => c.charCodeAt(0));
      return new TextDecoder().decode(bytes);
    } catch (e) {
      return '';
    }
  }

  function now() {
    return Math.floor(Date.now() / 1000);
  }

  function pinLeft() {
    const el = (Date.now() - pinStart) / 1000;
    return Math.max(0, Math.round(PIN_TTL - (el % PIN_TTL)));
  }

  function status() {
    return {
      ok: true,
      bridge: 1,
      demo: true,
      paired: st.paired,
      master: true,
      connected: st.connected ? 1 : 0,
      last_addr: PEER,
      last_conn_at: lastConn,
      pin: st.paired ? '482913' : null,
      pin_left: pinLeft(),
      pin_ttl: PIN_TTL,
      cam: st.cam,
      mic: st.mic,
      level: st.level,
      port: PORT,
      lan_ip: HOST,
      autostart: st.auto,
      transfer_dir: STATION,
      uptime: now() - START,
      elevated: true,
      setup_pending: false,
      notes: [
        { kind: 'connected', msg: '手机已连接: ' + PEER, at: lastConn },
        { kind: 'file_note', msg: '已收到 DOC-06.docx(50.0 KB)', at: lastConn - 240 },
        { kind: 'upload_done', msg: '手机上传完成: IMG-04.jpg', at: lastConn - 600 }
      ]
    };
  }

  function get(path) {
    if (path === '/api/status') return status();

    if (path.startsWith('/api/log')) {
      const m = /[?&]n=(\d+)/.exec(path);
      const n = m ? Math.min(Number(m[1]) || 200, LOG.length) : LOG.length;
      return { ok: true, lines: LOG.slice(-n) };
    }

    if (path === '/api/autostart') {
      return {
        ok: true,
        enabled: st.auto,
        source: 'task',
        task: { exists: true, enabled: st.auto, state: st.auto ? 'Ready' : 'Disabled' },
        task_name: 'ConMarn-PCServer',
        lnk: false
      };
    }

    if (path === '/api/level') {
      return { ok: true, cam: st.cam, mic: st.mic, level: st.mic ? st.level : 0 };
    }

    if (path.startsWith('/api/files')) {
      const q = new URLSearchParams(path.slice(path.indexOf('?') + 1));
      if ((q.get('scope') || 'local').toLowerCase() === 'phone') {
        return { ok: true, path: '/CM/STATION', at: now() - 30, entries: PHONE };
      }
      const rel = unb64(q.get('path') || '');
      const entries = Object.prototype.hasOwnProperty.call(LOCAL, rel) ? LOCAL[rel] : [];
      return { ok: true, path: rel, entries: entries };
    }

    if (path === '/api/frame.jpg') {
      return { ok: false, err: 'demo', empty: true };
    }

    return { ok: false, err: '演示模式不支持该请求' };
  }

  function post(path, body) {
    body = body || {};

    if (path === '/api/media') {
      const dev = String(body.dev || '').toUpperCase();
      if (dev !== 'CAM' && dev !== 'MIC') return { ok: false, err: '未知设备' };
      if (dev === 'CAM') st.cam = !!body.on; else st.mic = !!body.on;
      return { ok: true, sent: 1, delivered: st.connected };
    }

    if (path === '/api/autostart') {
      st.auto = !!body.on;
      return { ok: true, enabled: st.auto, source: 'task', task_name: 'ConMarn-PCServer' };
    }

    if (path === '/api/files/refresh' || path === '/api/files/open') {
      return { ok: true };
    }

    if (path === '/api/files/send') {
      return { ok: true, name: body.name };
    }

    if (path === '/api/files/pull') {
      return { ok: true, name: body.name };
    }

    if (path === '/api/files/upload') {
      return { ok: false, err: '演示模式下不保存文件' };
    }

    if (path === '/api/pair/unlock' || path === '/api/pair/setpin') {
      const pin = String(body.pin || '');
      if (!/^\d{6}$/.test(pin)) return { ok: false, err: '请输入 6 位数字。' };
      return { ok: true, qr: 'CM-PAIR-4C91', img: 'demo-qr.png' };
    }

    if (path === '/api/pair/forget') {
      st.paired = false;
      st.connected = false;
      return { ok: true, paired: false };
    }

    if (path === '/api/open') {
      return { ok: true };
    }

    if (path === '/api/service/start') {
      return { ok: false, err: '演示模式下不会启动服务' };
    }

    return { ok: false, err: '演示模式不支持该请求' };
  }

  return { on: on, set: set, get: get, post: post };
})();
