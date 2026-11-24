/* 主题切换。
   深色为默认；点击导航栏按钮在深色与浅色之间切换，选择写入 localStorage。
   载入时只同步界面（meta 与按钮提示），不写入 localStorage，
   因此「未选择过」的用户会一直跟随系统偏好，直到他第一次点击。 */
(function () {
  var KEY = 'conmarn-theme';
  var html = document.documentElement;
  var EN = html.getAttribute('lang') === 'en';
  var labels = EN
    ? { toLight: 'Switch to light theme', toDark: 'Switch to dark theme' }
    : { toLight: '切换到浅色主题', toDark: '切换到深色主题' };

  function current() {
    return html.getAttribute('data-theme') === 'light' ? 'light' : 'dark';
  }

  function syncUI() {
    var t = current();
    var meta = document.querySelector('meta[name="color-scheme"]');
    if (meta) { meta.setAttribute('content', t); }
    var btn = document.querySelector('.theme-toggle');
    if (btn) {
      var next = (t === 'light') ? labels.toDark : labels.toLight;
      btn.setAttribute('aria-label', next);
      btn.setAttribute('title', next);
    }
  }

  function set(t) {
    html.setAttribute('data-theme', t);
    try { localStorage.setItem(KEY, t); } catch (e) {}
    syncUI();
  }

  document.addEventListener('click', function (ev) {
    var el = ev.target;
    while (el && el !== document) {
      if (el.classList && el.classList.contains('theme-toggle')) {
        set(current() === 'light' ? 'dark' : 'light');
        return;
      }
      el = el.parentNode;
    }
  });

  syncUI();
})();
