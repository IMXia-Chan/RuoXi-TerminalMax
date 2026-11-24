# -*- coding: utf-8 -*-
"""只读探针：在指定宽度下量页面到底有没有横向溢出，以及是哪个元素撑出去的。

用法：python tools/_probe_mobile.py
然后用 Edge 无头加载生成的 HTML，--dump-dom 会把 <pre id="PROBE"> 一起打出来。
"""
import io
import os

SRC = r"D:/conmarn-site-gh/index.html"
DST = r"C:/Users/27014/phone-touchpad/.claude/tmp/probe_mobile.html"

BASE = '<base href="file:///D:/conmarn-site-gh/">'

JS = """
<script>
window.addEventListener('load', function(){
  var vw = window.innerWidth;
  var de = document.documentElement;
  var out = [];
  out.push('innerWidth=' + vw);
  out.push('clientWidth=' + de.clientWidth);
  out.push('scrollWidth=' + de.scrollWidth);
  out.push('body.scrollWidth=' + document.body.scrollWidth);
  out.push('fontScaleProbe(1rem)=' + parseFloat(getComputedStyle(de).fontSize));
  var worst = [];
  var all = document.querySelectorAll('body *');
  for (var i = 0; i < all.length; i++) {
    var el = all[i];
    var r = el.getBoundingClientRect();
    if (r.width === 0 && r.height === 0) continue;
    var over = Math.round(r.right - vw);
    if (over > 1) {
      worst.push({over: over, tag: el.tagName.toLowerCase(),
                  cls: (el.className || '').toString().slice(0, 40),
                  right: Math.round(r.right), w: Math.round(r.width)});
    }
  }
  worst.sort(function(a, b){ return b.over - a.over; });
  out.push('overflowing elements=' + worst.length);
  for (var j = 0; j < Math.min(12, worst.length); j++) {
    var w = worst[j];
    out.push('  +' + w.over + 'px  <' + w.tag + ' class="' + w.cls + '">  width=' + w.w);
  }
  var pre = document.createElement('pre');
  pre.id = 'PROBE';
  pre.textContent = out.join('\\n');
  document.body.appendChild(pre);
});
</script>
"""

s = io.open(SRC, encoding="utf-8").read()
s = s.replace("<head>", "<head>\n" + BASE, 1)
s = s.replace("</body>", JS + "</body>", 1)
os.makedirs(os.path.dirname(DST), exist_ok=True)
io.open(DST, "w", encoding="utf-8", newline="\n").write(s)
print("已写入", DST)
