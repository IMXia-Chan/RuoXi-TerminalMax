// 把「窗外那扇窗」在各种天气下长什么样，离线画成一张对照图。
//
// ★ 画法**直接 import 房间那份**，不抄。抄一份的结局是两边慢慢走偏，
//   而窗外画错了是不会报错的（默认就是夜晴），只有眼睛看得出来。
import { windowCanvas, precipCanvas, dropCanvas } from 'D:/her-build/web/src/room.js';

const W = 1024;
const H = 640;

const PHASES = ['day', 'dusk', 'night'];
const PHASE_CN = { day: '白天', dusk: '黄昏', night: '夜里' };
const KINDS = ['clear', 'partly', 'overcast', 'fog', 'rain', 'thunder', 'snow'];
const KIND_CN = {
  clear: '晴', partly: '多云', overcast: '阴', fog: '雾',
  rain: '雨', thunder: '雷', snow: '雪',
};

/** 把一张可平铺的贴图按 repeat 铺满整个画布，整体乘一个不透明度。 */
function tile(ctx, src, rx, ry, alpha, offsetY = 0) {
  if (alpha <= 0) return;
  ctx.save();
  ctx.globalAlpha = alpha;
  const tw = W / rx;
  const th = H / ry;
  const oy = (offsetY % 1) * th;
  for (let y = -1; y <= ry; y++) {
    for (let x = 0; x < rx; x++) {
      ctx.drawImage(src, x * tw, y * th + oy, tw, th);
    }
  }
  ctx.restore();
}

/** 画一格：天光 + （雨雪层）+（玻璃水珠层）。参数与 room.js 的 window3d.set 同源。 */
function pane(phase, kind) {
  const c = document.createElement('canvas');
  c.width = W;
  c.height = H;
  const ctx = c.getContext('2d');
  ctx.drawImage(windowCanvas(W, H, { phase, kind }), 0, 0);

  const falling = kind === 'rain' || kind === 'thunder' || kind === 'snow';
  const wet = kind === 'rain' || kind === 'thunder';
  if (falling) {
    const snow = kind === 'snow';
    tile(ctx, precipCanvas(snow ? 'snow' : 'rain'),
      snow ? 2.5 : 3, snow ? 1.6 : 2, snow ? 0.9 : 0.72, 0.33);
  }
  if (wet) tile(ctx, dropCanvas(128, 256), 2.5, 2, 0.9, 0.4);
  return c;
}

window.__render = () => {
  const host = document.getElementById('grid');
  const scale = 320 / W;
  for (const phase of PHASES) {
    for (const kind of KINDS) {
      const cell = document.createElement('div');
      cell.className = 'cell';
      const cv = pane(phase, kind);
      cv.className = 'shot';
      cv.style.width = `${W * scale}px`;
      cv.style.height = `${H * scale}px`;
      const lb = document.createElement('div');
      lb.className = 'lb';
      lb.textContent = `${PHASE_CN[phase]} · ${KIND_CN[kind]}`;
      cell.appendChild(cv);
      cell.appendChild(lb);
      host.appendChild(cell);
    }
  }
  const s = document.createElement('div');
  s.id = 'done';
  s.textContent = 'ok';
  document.body.appendChild(s);
};
