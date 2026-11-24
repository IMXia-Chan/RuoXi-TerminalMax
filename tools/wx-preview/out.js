(() => {
  // D:/her-build/web/src/room.js
  function cv(w, h) {
    const c = document.createElement("canvas");
    c.width = w;
    c.height = h;
    return c;
  }
  function rnd(a, b) {
    return a + Math.random() * (b - a);
  }
  function rgb(r, g, b) {
    return "rgb(" + Math.round(r) + "," + Math.round(g) + "," + Math.round(b) + ")";
  }
  function rgba(r, g, b, a) {
    return "rgba(" + r + "," + g + "," + b + "," + a.toFixed(3) + ")";
  }
  var WX_PHASE = {
    night: {
      sky: [[0, "#050a1e"], [0.42, "#0f2049"], [0.76, "#26406f"], [1, "#5b6280"]],
      stars: 460,
      moon: 1,
      sun: 0,
      city: ["#22345c", "#182644", "#0c1424"],
      lit: 1,
      ground: ["#070b13", "#04060b"]
    },
    dusk: {
      sky: [[0, "#121a3c"], [0.42, "#3c2c52"], [0.78, "#8f5a5a"], [1, "#d2946a"]],
      stars: 70,
      moon: 0.35,
      sun: 0.55,
      city: ["#2c3450", "#1f2740", "#101827"],
      lit: 0.8,
      ground: ["#140f16", "#0a0709"]
    },
    day: {
      sky: [[0, "#2e6cb4"], [0.45, "#5c9ad4"], [0.78, "#a9cfe6"], [1, "#dbe8ef"]],
      stars: 0,
      moon: 0,
      sun: 1,
      city: ["#8598ad", "#647a92", "#46586e"],
      lit: 0.12,
      ground: ["#3a3a3c", "#242424"]
    }
  };
  function shade(hex, grey, dark) {
    const n = parseInt(hex.slice(1), 16);
    let r = n >> 16 & 255, g = n >> 8 & 255, b = n & 255;
    if (grey > 0) {
      const m = (r + g + b) / 3;
      r += (m - r) * grey;
      g += (m - g) * grey;
      b += (m - b) * grey;
    }
    if (dark > 0) {
      const k = 1 - dark;
      r *= k;
      g *= k;
      b *= k;
    }
    return rgb(r, g, b);
  }
  function wxLook(wx) {
    const phase = wx && wx.phase || "night";
    const kind = wx && wx.kind || "clear";
    const base = WX_PHASE[phase] || WX_PHASE.night;
    const L = {
      phase,
      kind,
      sky: base.sky,
      city: base.city,
      ground: base.ground,
      stars: base.stars,
      moon: base.moon,
      sun: base.sun,
      lit: base.lit,
      clouds: 3,
      grey: 0,
      dark: 0,
      veil: 0
    };
    if (kind === "partly") {
      L.stars *= 0.45;
      L.moon *= 0.8;
      L.clouds = 7;
      L.grey = 0.1;
    } else if (kind === "overcast") {
      L.stars = 0;
      L.moon = 0;
      L.sun *= 0.2;
      L.clouds = 14;
      L.grey = 0.34;
    } else if (kind === "fog") {
      L.stars = 0;
      L.moon = 0;
      L.sun *= 0.3;
      L.clouds = 10;
      L.grey = 0.55;
      L.veil = 0.42;
    } else if (kind === "rain") {
      L.stars = 0;
      L.moon = 0;
      L.sun *= 0.15;
      L.clouds = 13;
      L.grey = 0.44;
      L.dark = 0.2;
      L.veil = 0.08;
    } else if (kind === "snow") {
      L.stars = 0;
      L.moon = 0;
      L.sun *= 0.25;
      L.clouds = 12;
      L.grey = 0.3;
      L.veil = 0.26;
    } else if (kind === "thunder") {
      L.stars = 0;
      L.moon = 0;
      L.sun *= 0.1;
      L.clouds = 15;
      L.grey = 0.48;
      L.dark = 0.32;
      L.veil = 0.05;
    }
    return L;
  }
  function windowCanvas(W2, H2, wx) {
    const L = wxLook(wx);
    const c = cv(W2, H2);
    const g = c.getContext("2d");
    const HZ = H2 * 0.72;
    const sky = g.createLinearGradient(0, 0, 0, HZ);
    for (const [p, hex] of L.sky) sky.addColorStop(p, shade(hex, L.grey, L.dark));
    g.fillStyle = sky;
    g.fillRect(0, 0, W2, HZ + 1);
    const stars = Math.round(L.stars);
    for (let i = 0; i < stars; i++) {
      const x = rnd(0, W2);
      const y = rnd(0, HZ * 0.82);
      const a = rnd(0.18, 0.9) * (1 - y / (HZ * 0.82)) + 0.1;
      g.fillStyle = rgba(226, 238, 255, a);
      g.beginPath();
      g.arc(x, y, rnd(0.5, 1.5), 0, Math.PI * 2);
      g.fill();
    }
    const cloudA = 0.05 + L.grey * 0.16;
    for (let i = 0; i < L.clouds; i++) {
      const x = rnd(-W2 * 0.1, W2 * 1.1);
      const y = rnd(H2 * 0.06, HZ * 0.7);
      const rw = rnd(W2 * 0.12, W2 * 0.3);
      const rh = rnd(H2 * 0.012, H2 * 0.035);
      for (let ring = 3; ring >= 1; ring--) {
        g.fillStyle = rgba(150, 175, 215, cloudA * ring);
        g.beginPath();
        g.ellipse(x, y, rw * ring / 3, rh * ring / 3, 0, 0, Math.PI * 2);
        g.fill();
      }
    }
    const MX = W2 * 0.348, MY = H2 * 0.155, MR = H2 * 0.072;
    if (L.moon > 0) {
      g.save();
      g.globalAlpha = L.moon;
      const halo = g.createRadialGradient(MX, MY, MR * 0.6, MX, MY, MR * 5.2);
      halo.addColorStop(0, "rgba(198,220,255,0.55)");
      halo.addColorStop(0.35, "rgba(170,198,245,0.18)");
      halo.addColorStop(1, "rgba(150,180,235,0)");
      g.fillStyle = halo;
      g.beginPath();
      g.arc(MX, MY, MR * 5.2, 0, Math.PI * 2);
      g.fill();
      g.fillStyle = "#eaf1ff";
      g.beginPath();
      g.arc(MX, MY, MR, 0, Math.PI * 2);
      g.fill();
      for (let i = 0; i < 22; i++) {
        const a = rnd(0, Math.PI * 2);
        const d = Math.sqrt(Math.random()) * MR * 0.82;
        g.fillStyle = rgba(196, 210, 236, rnd(0.2, 0.5));
        g.beginPath();
        g.arc(MX + Math.cos(a) * d, MY + Math.sin(a) * d, rnd(MR * 0.05, MR * 0.16), 0, Math.PI * 2);
        g.fill();
      }
      g.restore();
    }
    const sunY = L.phase === "dusk" ? HZ * 0.82 : H2 * 0.17;
    const SX = W2 * 0.66, SR = H2 * 0.055;
    if (L.sun > 0) {
      g.save();
      g.globalAlpha = Math.min(1, L.sun);
      const warm = L.phase === "dusk";
      const glow = g.createRadialGradient(SX, sunY, SR * 0.4, SX, sunY, SR * 7);
      glow.addColorStop(0, warm ? "rgba(255,196,150,0.75)" : "rgba(255,246,215,0.65)");
      glow.addColorStop(0.4, warm ? "rgba(255,170,120,0.22)" : "rgba(255,240,200,0.20)");
      glow.addColorStop(1, "rgba(255,220,160,0)");
      g.fillStyle = glow;
      g.beginPath();
      g.arc(SX, sunY, SR * 7, 0, Math.PI * 2);
      g.fill();
      g.fillStyle = warm ? "#ffd9b0" : "#fffdf2";
      g.beginPath();
      g.arc(SX, sunY, SR, 0, Math.PI * 2);
      g.fill();
      g.restore();
    }
    const layers = [
      { col: L.city[0], n: 50, h0: 0.03, h1: 0.12, lit: 0.07, warm: 0.82, s: 0.5 },
      { col: L.city[1], n: 38, h0: 0.08, h1: 0.27, lit: 0.13, warm: 0.78, s: 0.8 },
      { col: L.city[2], n: 26, h0: 0.05, h1: 0.16, lit: 0.1, warm: 0.7, s: 1.15 }
    ];
    for (const L2 of layers) {
      L2.col = shade(L2.col, L.grey, L.dark);
      const step = W2 / L2.n;
      for (let i = 0; i < L2.n; i++) {
        const bw = step * rnd(0.6, 0.98);
        const bxx = i * step + rnd(0, Math.max(0, step - bw));
        const bh = H2 * rnd(L2.h0, L2.h1);
        const by = HZ - bh;
        g.fillStyle = L2.col;
        g.fillRect(bxx, by, bw, bh);
        const cols = Math.max(1, Math.floor(bw / (7 * L2.s)));
        const rows = Math.max(1, Math.floor(bh / (9 * L2.s)));
        for (let cc = 0; cc < cols; cc++) {
          for (let rr = 0; rr < rows; rr++) {
            if (Math.random() > L2.lit * L.lit) continue;
            g.fillStyle = Math.random() < L2.warm ? rgba(255, 206, 140, rnd(0.35, 0.95)) : rgba(180, 220, 255, rnd(0.3, 0.8));
            g.fillRect(
              bxx + 2.5 * L2.s + cc * 7 * L2.s,
              by + 3 * L2.s + rr * 9 * L2.s,
              3.2 * L2.s,
              4 * L2.s
            );
          }
        }
        if (bh > H2 * 0.22 && Math.random() < 0.5) {
          g.fillStyle = "rgba(255,80,70,0.85)";
          g.beginPath();
          g.arc(bxx + bw / 2, by - 2, 1.6, 0, Math.PI * 2);
          g.fill();
        }
      }
    }
    const haze = g.createLinearGradient(0, HZ - H2 * 0.08, 0, HZ + H2 * 0.02);
    const hazeA = L.phase === "day" ? 0.1 : 0.2;
    haze.addColorStop(0, "rgba(255,190,120,0)");
    haze.addColorStop(0.72, rgba(255, 186, 116, hazeA));
    haze.addColorStop(1, "rgba(255,170,100,0.05)");
    g.fillStyle = haze;
    g.fillRect(0, HZ - H2 * 0.08, W2, H2 * 0.1);
    const ground = g.createLinearGradient(0, HZ, 0, H2);
    ground.addColorStop(0, shade(L.ground[0], L.grey, L.dark));
    ground.addColorStop(1, shade(L.ground[1], L.grey, L.dark));
    g.fillStyle = ground;
    g.fillRect(0, HZ, W2, H2 - HZ);
    for (let i = 0; i < 70; i++) {
      g.fillStyle = rgba(255, 214, 160, rnd(0.06, 0.3) * L.lit);
      g.fillRect(rnd(0, W2), rnd(HZ + 2, H2), rnd(1, 2.6), rnd(1, 2));
    }
    if (L.veil > 0) {
      g.fillStyle = rgba(214, 220, 228, L.veil);
      g.fillRect(0, 0, W2, H2);
    }
    return c;
  }
  function dropCanvas(W2, H2) {
    const c = cv(W2, H2);
    const g = c.getContext("2d");
    for (let i = 0; i < 26; i++) {
      const x = rnd(6, W2 - 6);
      const y = rnd(6, H2 - 6);
      const r = rnd(2.2, 6.5);
      const len = r * rnd(1.2, 4.2);
      const tr = g.createLinearGradient(x, y - len, x, y);
      tr.addColorStop(0, "rgba(200,225,255,0)");
      tr.addColorStop(1, "rgba(212,233,255,0.42)");
      g.strokeStyle = tr;
      g.lineWidth = Math.max(1, r * 0.55);
      g.lineCap = "round";
      g.beginPath();
      g.moveTo(x, y - len);
      g.lineTo(x, y - r * 0.4);
      g.stroke();
      const dg = g.createRadialGradient(x - r * 0.3, y - r * 0.35, r * 0.1, x, y, r);
      dg.addColorStop(0, "rgba(255,255,255,0.92)");
      dg.addColorStop(0.45, "rgba(198,222,250,0.50)");
      dg.addColorStop(1, "rgba(120,150,190,0.12)");
      g.fillStyle = dg;
      g.beginPath();
      g.ellipse(x, y, r * 0.82, r, 0, 0, Math.PI * 2);
      g.fill();
      g.fillStyle = "rgba(255,255,255,0.85)";
      g.beginPath();
      g.arc(x - r * 0.3, y - r * 0.38, r * 0.22, 0, Math.PI * 2);
      g.fill();
    }
    for (let i = 0; i < 260; i++) {
      g.fillStyle = rgba(230, 240, 255, rnd(0.05, 0.22));
      g.beginPath();
      g.arc(rnd(0, W2), rnd(0, H2), rnd(0.4, 1.1), 0, Math.PI * 2);
      g.fill();
    }
    return c;
  }
  function precipCanvas(kind) {
    const W2 = 128, H2 = 256;
    const c = cv(W2, H2);
    const g = c.getContext("2d");
    if (kind === "snow") {
      for (let i = 0; i < 90; i++) {
        const a = rnd(0.25, 0.9);
        g.fillStyle = rgba(255, 255, 255, a);
        g.beginPath();
        g.arc(rnd(0, W2), rnd(2, H2 - 2), rnd(1, 2.6), 0, Math.PI * 2);
        g.fill();
      }
      return c;
    }
    g.lineCap = "round";
    for (let i = 0; i < 110; i++) {
      const x = rnd(0, W2);
      const y = rnd(4, H2 - 4);
      const len = rnd(8, 22);
      g.strokeStyle = rgba(190, 214, 240, rnd(0.12, 0.42));
      g.lineWidth = rnd(0.7, 1.4);
      g.beginPath();
      g.moveTo(x, y);
      g.lineTo(x - len * 0.16, y + len);
      g.stroke();
    }
    return c;
  }

  // entry.js
  var W = 1024;
  var H = 640;
  var PHASES = ["day", "dusk", "night"];
  var PHASE_CN = { day: "\u767D\u5929", dusk: "\u9EC4\u660F", night: "\u591C\u91CC" };
  var KINDS = ["clear", "partly", "overcast", "fog", "rain", "thunder", "snow"];
  var KIND_CN = {
    clear: "\u6674",
    partly: "\u591A\u4E91",
    overcast: "\u9634",
    fog: "\u96FE",
    rain: "\u96E8",
    thunder: "\u96F7",
    snow: "\u96EA"
  };
  function tile(ctx, src, rx, ry, alpha, offsetY = 0) {
    if (alpha <= 0) return;
    ctx.save();
    ctx.globalAlpha = alpha;
    const tw = W / rx;
    const th = H / ry;
    const oy = offsetY % 1 * th;
    for (let y = -1; y <= ry; y++) {
      for (let x = 0; x < rx; x++) {
        ctx.drawImage(src, x * tw, y * th + oy, tw, th);
      }
    }
    ctx.restore();
  }
  function pane(phase, kind) {
    const c = document.createElement("canvas");
    c.width = W;
    c.height = H;
    const ctx = c.getContext("2d");
    ctx.drawImage(windowCanvas(W, H, { phase, kind }), 0, 0);
    const falling = kind === "rain" || kind === "thunder" || kind === "snow";
    const wet = kind === "rain" || kind === "thunder";
    if (falling) {
      const snow = kind === "snow";
      tile(
        ctx,
        precipCanvas(snow ? "snow" : "rain"),
        snow ? 2.5 : 3,
        snow ? 1.6 : 2,
        snow ? 0.9 : 0.72,
        0.33
      );
    }
    if (wet) tile(ctx, dropCanvas(128, 256), 2.5, 2, 0.9, 0.4);
    return c;
  }
  window.__render = () => {
    const host = document.getElementById("grid");
    const scale = 320 / W;
    for (const phase of PHASES) {
      for (const kind of KINDS) {
        const cell = document.createElement("div");
        cell.className = "cell";
        const cv2 = pane(phase, kind);
        cv2.className = "shot";
        cv2.style.width = `${W * scale}px`;
        cv2.style.height = `${H * scale}px`;
        const lb = document.createElement("div");
        lb.className = "lb";
        lb.textContent = `${PHASE_CN[phase]} \xB7 ${KIND_CN[kind]}`;
        cell.appendChild(cv2);
        cell.appendChild(lb);
        host.appendChild(cell);
      }
    }
    const s = document.createElement("div");
    s.id = "done";
    s.textContent = "ok";
    document.body.appendChild(s);
  };
})();
