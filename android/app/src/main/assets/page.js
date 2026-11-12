/*
 * Ghostream page script for the in-app browser. Runs in every page and frame, as early as possible.
 *  1. Element hiding: asks the app which ad selectors apply to this page (site rules, plus generic rules
 *     for the ids and classes actually on the page, as uBlock Origin does) and hides those elements.
 *  2. Video detection: puts a Download button on each video playing on the page. The button lives in a
 *     closed shadow root, so the page can't restyle or script it, and only real taps are acted on.
 *  3. Video playlists the page loads under other names, found by their content, for downloading.
 *  4. Video mode: with the phone turned sideways, the playing video fills the screen.
 *  5. YouTube without Shorts (a setting).
 */
(function () {
  'use strict';
  if (window.__fdPage) return;
  window.__fdPage = true;
  var B = window.__fdBridge;
  if (!B) return;

  // ---------- 0b. Full screen without a pause ----------
  // Going in or out of full screen, the WebView moves the video to another view and, for a moment, the page
  // counts as hidden. YouTube unloads the video then (a black screen, paused). For 3 seconds after a page asks
  // for full screen or leaves it, the page stays "visible". Leaving the app is not affected.
  (function () {
    var holdUntil = 0;
    function hold() { holdUntil = Date.now() + 3000; }
    function held() { return Date.now() < holdUntil; }
    function wrap(proto, name) {
      var f = proto && proto[name];
      if (typeof f !== 'function') return;
      proto[name] = function () { hold(); return f.apply(this, arguments); };
    }
    wrap(Element.prototype, 'requestFullscreen');
    wrap(Element.prototype, 'webkitRequestFullscreen');
    wrap(Element.prototype, 'webkitRequestFullScreen');
    wrap(HTMLVideoElement.prototype, 'webkitEnterFullscreen');
    wrap(Document.prototype, 'exitFullscreen');
    wrap(Document.prototype, 'webkitExitFullscreen');
    document.addEventListener('fullscreenchange', hold, true);
    document.addEventListener('webkitfullscreenchange', hold, true);
    function getter(name, whenHeld) {
      var d = Object.getOwnPropertyDescriptor(Document.prototype, name);
      if (!d || !d.get) return;
      Object.defineProperty(Document.prototype, name, {
        configurable: true, enumerable: d.enumerable,
        get: function () { return held() ? whenHeld : d.get.call(this); },
      });
    }
    getter('visibilityState', 'visible');
    getter('hidden', false);
    getter('webkitVisibilityState', 'visible');
    getter('webkitHidden', false);
    function stop(e) { if (held()) e.stopImmediatePropagation(); }
    document.addEventListener('visibilitychange', stop, true);
    document.addEventListener('webkitvisibilitychange', stop, true);
    window.addEventListener('visibilitychange', stop, true);
  })();

  // ---------- 0a. YouTube quality ----------
  // YouTube picks the quality from the player's size on the page, which on a phone means 360p. The app asks for
  // the quality set in Settings (the highest on Wi-Fi by default) each time a video starts playing.
  if (/(^|\.)youtube\.com$/.test(location.hostname) && B.youtubeQuality) {
    var YT_ORDER = ['highres', 'hd2880', 'hd2160', 'hd1440', 'hd1080', 'hd720', 'large', 'medium', 'small', 'tiny'];
    document.addEventListener('playing', function () {
      var want = B.youtubeQuality();
      var p = document.getElementById('movie_player');
      if (!want || !p || !p.setPlaybackQualityRange || !p.getAvailableQualityLevels) return;
      var have = p.getAvailableQualityLevels().filter(function (q) { return q !== 'auto'; });
      // "Highest" stops at 4K: 8K drops most of its frames on phones, and no phone screen shows more than 4K.
      var cap = YT_ORDER.indexOf(want === 'highest' ? 'hd2160' : want);
      var pick = have.filter(function (q) { return YT_ORDER.indexOf(q) >= cap; })[0];
      if (pick && p.getPlaybackQuality() !== pick) p.setPlaybackQualityRange(pick, pick);
    }, true);
  }

  // ---------- 0. YouTube without video ads (like uBlock Origin and Brave) ----------
  // Runs before YouTube's own scripts: the ad entries are taken out of the player data, so the player
  // never sees ads. If one still slips through, it's muted and skipped.
  if (/(^|\.)(youtube\.com|youtube-nocookie\.com)$/.test(location.hostname) && B.youtubeAdsOff()) {
    var AD_KEYS = ['adPlacements', 'playerAds', 'adSlots', 'adBreakHeartbeatParams', 'adBreakParams'];
    var removed = 0;
    var strip = function (o, depth) {
      if (!o || typeof o !== 'object' || depth > 4) return o;
      for (var i = 0; i < AD_KEYS.length; i++) if (AD_KEYS[i] in o) { delete o[AD_KEYS[i]]; removed++; }
      if (o.playerResponse) strip(o.playerResponse, depth + 1);
      if (Array.isArray(o)) for (var j = 0; j < o.length; j++) strip(o[j], depth + 1);
      else if (o.response) strip(o.response, depth + 1);
      return o;
    };
    var parse = JSON.parse;
    JSON.parse = function () {
      var r = parse.apply(this, arguments);
      try { strip(r, 0); } catch (e) { /* not ours */ }
      return r;
    };
    var json = Response.prototype.json;
    Response.prototype.json = function () {
      return json.apply(this, arguments).then(function (r) { try { strip(r, 0); } catch (e) { /* not ours */ } return r; });
    };
    ['ytInitialPlayerResponse', 'ytInitialData'].forEach(function (name) {
      var value;
      try {
        Object.defineProperty(window, name, {
          configurable: true,
          get: function () { return value; },
          set: function (v) { value = strip(v, 0); },
        });
      } catch (e) { /* already defined */ }
    });
    setInterval(function () {
      var player = document.querySelector('.ad-showing, .ad-interrupting');
      if (!player) return;
      var v = player.querySelector('video');
      if (v) {
        v.muted = true;
        if (isFinite(v.duration) && v.duration > 0) v.currentTime = v.duration;
      }
      var skip = document.querySelector('.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button, .ytm-skip-ad-button, [class*="skip-ad"] button');
      if (skip) skip.click();
      removed++;
    }, 300);
    window.__fdAdsRemoved = function () { return removed; };
  }

  // ---------- YouTube without Shorts ----------
  if (/(^|\.)youtube\.com$/.test(location.hostname) && window.top === window && B.hideShorts()) {
    // A Shorts link opens as a normal video.
    var fromShorts = function () {
      var m = /^\/shorts\/([\w-]{6,})/.exec(location.pathname);
      if (m) location.replace('/watch?v=' + m[1]);
    };
    fromShorts();
    setInterval(fromShorts, 500);
    var shortsCss = [
      // m.youtube.com
      'ytm-reel-shelf-renderer', 'ytm-shorts-lockup-view-model', 'ytm-shorts-lockup-view-model-v2',
      'ytm-rich-section-renderer:has(ytm-shorts-lockup-view-model, ytm-shorts-lockup-view-model-v2, ytm-reel-shelf-renderer)',
      'grid-shelf-view-model:has(ytm-shorts-lockup-view-model, ytm-shorts-lockup-view-model-v2)',
      'ytm-item-section-renderer:has(> * > ytm-reel-shelf-renderer)',
      'ytm-pivot-bar-item-renderer:has(.pivot-shorts)',
      'ytm-video-with-context-renderer:has(a[href^="/shorts/"])', 'ytm-compact-video-renderer:has(a[href^="/shorts/"])',
      'ytm-rich-item-renderer:has(a[href^="/shorts/"])',
      // www.youtube.com
      'ytd-reel-shelf-renderer', 'ytd-rich-shelf-renderer[is-shorts]', 'ytd-rich-section-renderer:has(ytd-rich-shelf-renderer[is-shorts])',
      'ytd-guide-entry-renderer:has(a[title="Shorts"])', 'ytd-mini-guide-entry-renderer[aria-label="Shorts"]',
      'ytd-video-renderer:has(a[href^="/shorts/"])', 'ytd-grid-video-renderer:has(a[href^="/shorts/"])',
      'ytd-rich-item-renderer:has(a[href^="/shorts/"])', 'yt-tab-shape[tab-title="Shorts"]',
      'grid-shelf-view-model:has(a[href^="/shorts/"])',
    ];
    var hideShorts = function () {
      var st = document.createElement('style');
      st.setAttribute('data-fd-shorts', '');
      (document.head || document.documentElement).appendChild(st);
      shortsCss.forEach(function (sel) {
        try { st.sheet.insertRule(sel + '{display:none!important}', st.sheet.cssRules.length); } catch (e) { /* unsupported here */ }
      });
    };
    if (document.documentElement) hideShorts();
    else document.addEventListener('readystatechange', hideShorts, { once: true });
  }

  // ---------- 1. element hiding ----------
  var style = null;
  function sheet() {
    if (!style) {
      style = document.createElement('style');
      style.setAttribute('data-fd', '');
      (document.head || document.documentElement).appendChild(style);
    }
    if (!style.isConnected) (document.head || document.documentElement).appendChild(style);
    return style.sheet;
  }
  function hide(text) {
    if (!text) return;
    var sels = text.split('\n');
    var s = sheet();
    if (!s) return;
    // Groups of 40 per rule; a selector this browser doesn't understand only costs its own group, which is
    // then added one by one.
    for (var i = 0; i < sels.length; i += 40) {
      var group = sels.slice(i, i + 40);
      try {
        s.insertRule(group.join(',\n') + '{display:none!important}', s.cssRules.length);
      } catch (e) {
        group.forEach(function (one) {
          try { s.insertRule(one + '{display:none!important}', s.cssRules.length); } catch (e2) { /* unsupported selector */ }
        });
      }
    }
  }

  var seen = Object.create(null);
  var pending = [];
  var timer = 0;
  function collect(el) {
    if (el.id && !seen['#' + el.id]) { seen['#' + el.id] = 1; pending.push('#' + el.id); }
    var cl = el.classList;
    if (cl) for (var i = 0; i < cl.length; i++) {
      var c = '.' + cl[i];
      if (!seen[c]) { seen[c] = 1; pending.push(c); }
    }
  }
  function scan(root) {
    if (root.nodeType !== 1) return;
    collect(root);
    var all = root.querySelectorAll('[id],[class]');
    for (var i = 0; i < all.length; i++) collect(all[i]);
  }
  function flush() {
    timer = 0;
    if (!pending.length) return;
    var batch = pending.join(' ');
    pending = [];
    try { hide(B.cosmeticFor(location.href, batch)); } catch (e) { /* bridge gone */ }
  }
  function later() { if (!timer) timer = setTimeout(flush, 200); }

  try { hide(B.cosmeticBase(location.href)); } catch (e) { /* bridge gone */ }

  // ---------- 1b. procedural hiding: "div:has-text(Sponsored):upward(2)", ":remove()", ":style()"… ----------
  var PROC_OP = /^:(has-text|-abp-contains|-abp-has|upward|nth-ancestor|remove|matches-css-before|matches-css-after|matches-css|min-text-length|xpath|matches-path|style)\(/;
  var PROC_ANY = /:(has-text|-abp-contains|-abp-has|upward|nth-ancestor|matches-css|min-text-length|xpath)\(/;
  // "div.card:has-text(Ad):upward(2)" → [{css:'div.card'}, {op:'has-text', arg:'Ad'}, {op:'upward', arg:'2'}]
  function closeParen(sel, from) {
    var depth = 1, j = from;
    while (j < sel.length && depth > 0) {
      var c = sel[j];
      if (c === '\\') { j += 2; continue; }
      if (c === '(') depth++; else if (c === ')') depth--;
      j++;
    }
    return depth === 0 ? j : -1;
  }
  function parseProc(sel) {
    var steps = [], css = '', i = 0;
    while (i < sel.length) {
      var m = PROC_OP.exec(sel.slice(i));
      if (m) {
        var start = i + m[0].length, end = closeParen(sel, start);
        if (end < 0) return null;
        if (css.trim() || !steps.length) steps.push({ css: css.trim() });
        css = '';
        steps.push({ op: m[1], arg: sel.slice(start, end - 1).trim() });
        i = end;
        continue;
      }
      var ch = sel[i];
      if (ch === '[') { // attribute values may hold ':' or '('
        var k = sel.indexOf(']', i);
        if (k < 0) return null;
        css += sel.slice(i, k + 1); i = k + 1; continue;
      }
      if (ch === '(') { // a native pseudo-class argument, e.g. :not(…) or :has(…)
        var e2 = closeParen(sel, i + 1);
        if (e2 < 0) return null;
        var inner = sel.slice(i + 1, e2 - 1), nat = /:(has|not)$/.exec(css);
        if (nat && PROC_ANY.test(inner)) { // ":has(div:has-text(AD))": run the inside as a procedural rule too
          css = css.slice(0, -nat[0].length);
          if (css.trim() || !steps.length) steps.push({ css: css.trim() });
          css = '';
          var sub = parseProc(inner);
          if (!sub) return null;
          steps.push({ op: nat[1], steps: sub });
        } else css += sel.slice(i, e2);
        i = e2; continue;
      }
      css += ch; i++;
    }
    if (css.trim()) steps.push({ css: css.trim() });
    return steps;
  }
  function textTest(arg) {
    var m = /^\/(.*)\/([imsu]*)$/.exec(arg);
    if (m) { var re = new RegExp(m[1], m[2]); return function (s) { return re.test(s); }; }
    return function (s) { return s.indexOf(arg) >= 0; };
  }
  function cssTest(arg) { // "position: fixed" or "z-index: /^\d{4,}$/"
    var c = arg.indexOf(':');
    if (c < 0) return null;
    var prop = arg.slice(0, c).trim(), val = arg.slice(c + 1).trim();
    var m = /^\/(.*)\/([imsu]*)$/.exec(val);
    var re = m ? new RegExp(m[1], m[2])
      : new RegExp('^' + val.replace(/[.+?^${}()|[\]\\]/g, '\\$&').replace(/\*/g, '.*') + '$');
    return { prop: prop, re: re };
  }
  function filterStep(nodes, op, a) {
    switch (op) {
      case 'has-text': case '-abp-contains':
        var tt = textTest(a);
        return nodes.filter(function (el) { return tt(el.textContent || ''); });
      case 'min-text-length':
        var len = parseInt(a, 10) || 0;
        return nodes.filter(function (el) { return (el.textContent || '').length >= len; });
      case '-abp-has':
        return nodes.filter(function (el) { return el.querySelector(':scope ' + a) !== null; });
      case 'matches-css': case 'matches-css-before': case 'matches-css-after':
        var ct = cssTest(a);
        if (!ct) return [];
        var pseudo = op === 'matches-css' ? null : '::' + op.slice(12);
        return nodes.filter(function (el) { return ct.re.test(getComputedStyle(el, pseudo).getPropertyValue(ct.prop)); });
      case 'upward': case 'nth-ancestor':
        var num = /^\d+$/.test(a) ? parseInt(a, 10) : 0;
        return nodes.map(function (el) {
          if (num) { var p = el; for (var u = 0; u < num && p; u++) p = p.parentElement; return p; }
          return el.parentElement ? el.parentElement.closest(a) : null;
        }).filter(Boolean);
      default:
        return null; // unknown: the rule is skipped
    }
  }
  function all(root, css) { return Array.prototype.slice.call(root.querySelectorAll(css)); }
  /**
   * Runs parsed steps. Without [ctx] they start from the page; with it, from inside ctx (":has(…)"), or on ctx
   * itself when [self] is set (":not(…)"). Returns {nodes, action, style}, or null when nothing can match.
   */
  function select(steps, ctx, self) {
    var nodes = null, action = 'hide', style = '';
    function begin(css) {
      if (!ctx) return all(document, css || 'body *');
      if (self) return css && !ctx.matches(css) ? [] : [ctx];
      return all(ctx, css ? ':scope ' + css : '*');
    }
    for (var s = 0; s < steps.length; s++) {
      var st = steps[s];
      if (st.css !== undefined) {
        if (nodes === null) nodes = begin(st.css);
        else nodes = nodes.reduce(function (acc, el) { return acc.concat(all(el, ':scope ' + st.css)); }, []);
      } else if (st.op === 'has' || st.op === 'not') {
        if (nodes === null) nodes = begin('');
        var want = st.op === 'has';
        nodes = nodes.filter(function (el) {
          var r = select(st.steps, el, !want);
          return !!(r && r.nodes && r.nodes.length) === want;
        });
      } else if (st.op === 'xpath') {
        var found = [];
        (nodes === null ? [document] : nodes).forEach(function (ctx) {
          var r = document.evaluate(st.arg, ctx, null, XPathResult.ORDERED_NODE_SNAPSHOT_TYPE, null);
          for (var x = 0; x < r.snapshotLength; x++) if (r.snapshotItem(x).nodeType === 1) found.push(r.snapshotItem(x));
        });
        nodes = found;
      } else if (st.op === 'matches-path') {
        if (!textTest(st.arg)(location.pathname + location.search)) return null;
      } else if (st.op === 'remove') {
        action = 'remove';
      } else if (st.op === 'style') {
        action = 'style'; style = st.arg;
      } else {
        if (nodes === null) nodes = begin('');
        nodes = filterStep(nodes, st.op, st.arg);
        if (nodes === null) return null;
      }
      if (nodes && !nodes.length) return null;
    }
    return { nodes: nodes, action: action, style: style };
  }
  function runSteps(steps) {
    var r = select(steps, null, false);
    if (!r || !r.nodes) return;
    var action = r.action, style = r.style;
    r.nodes.forEach(function (el) {
      if (el === document.documentElement || el === document.body) return; // never blank the whole page
      if (action === 'remove') el.remove();
      else if (action === 'style') el.style.cssText += ';' + style;
      else el.style.setProperty('display', 'none', 'important');
    });
  }
  var procRules = [];
  try { procRules = JSON.parse(B.proceduralFor(location.href)).map(parseProc).filter(Boolean); } catch (e) { procRules = []; }
  var procTimer = 0;
  function runProcedural() {
    procTimer = 0;
    procRules.forEach(function (steps) { try { runSteps(steps); } catch (e) { /* a rule this page can't run */ } });
  }
  function procSoon() { if (procRules.length && !procTimer) procTimer = setTimeout(runProcedural, 300); }

  function start() {
    scan(document.documentElement);
    flush();
    runProcedural();
    new MutationObserver(function (muts) {
      for (var i = 0; i < muts.length; i++) {
        var m = muts[i];
        if (m.type === 'attributes') collect(m.target);
        else for (var j = 0; j < m.addedNodes.length; j++) scan(m.addedNodes[j]);
      }
      if (pending.length) later();
      procSoon();
    }).observe(document.documentElement, { childList: true, subtree: true, attributes: true, attributeFilter: ['id', 'class'] });
    setInterval(placeButtons, 400);
    addEventListener('scroll', placeButtons, { passive: true, capture: true });
    addEventListener('resize', placeButtons, { passive: true });
  }

  // ---------- 2. Download buttons on videos ----------
  var MIN_W = 200, MIN_H = 110;
  var host = document.createElement('div');
  host.style.cssText = 'all:initial;position:fixed;top:0;left:0;width:0;height:0;z-index:2147483647;';
  var root = host.attachShadow ? host.attachShadow({ mode: 'closed' }) : host;
  var css = document.createElement('style');
  css.textContent =
    // A small round dot with the app's icon, so it hides as little of the video as possible.
    '.b{position:fixed;display:flex;align-items:center;justify-content:center;width:34px;height:34px;border-radius:50%;' +
    'background:#fff;box-shadow:0 2px 8px rgba(0,0,0,.45);cursor:pointer;-webkit-tap-highlight-color:transparent;' +
    'user-select:none;opacity:.9}' +
    '.b:active{transform:scale(.9);opacity:1}' +
    '.b svg{width:22px;height:22px}';
  root.appendChild(css);
  var buttons = []; // { video, el }
  var lastCount = -1;
  var lastHref = '';

  function button(video) {
    var el = document.createElement('div');
    el.className = 'b';
    el.setAttribute('role', 'button');
    el.setAttribute('aria-label', 'Download with Ghostream');
    el.title = 'Download with Ghostream';
    // Built with DOM calls, not innerHTML: sites like YouTube only allow "Trusted Types" HTML.
    var NS = 'http://www.w3.org/2000/svg';
    var svg = document.createElementNS(NS, 'svg');
    svg.setAttribute('viewBox', '0 0 24 24');
    // The app icon: a cloud with a download arrow in a circle.
    ['M5.70,15.91 A3.5,3.5 0 0,1 6.00,9.04 A5.5,5.5 0 0,1 16.91,8.00 A4,4 0 0,1 18.30,15.78 A6.3,6.3 0 0,0 5.70,15.91 Z',
     'M6.80,16.00 A5.2,5.2 0 1,1 17.20,16.00 A5.2,5.2 0 1,1 6.80,16.00 Z M11.2,12.4 L12.8,12.4 L12.8,17.0 L14.7,15.1 L15.8,16.2 L12,20.0 L8.2,16.2 L9.3,15.1 L11.2,17.0 Z'].forEach(function (d) {
      var p = document.createElementNS(NS, 'path');
      p.setAttribute('fill', '#0a93f5');
      p.setAttribute('fill-rule', 'evenodd');
      p.setAttribute('d', d);
      svg.appendChild(p);
    });
    el.appendChild(svg);
    el.addEventListener('click', function (ev) {
      if (!ev.isTrusted) return;
      ev.preventDefault();
      ev.stopPropagation();
      var src = video.currentSrc || video.src || '';
      if (!src) { var s = video.querySelector('source[src]'); if (s) src = s.src; }
      try {
        B.video(JSON.stringify({
          page: location.href,
          top: window.top === window,
          src: src,
          title: document.title,
          width: video.videoWidth || 0,
          height: video.videoHeight || 0,
        }));
      } catch (e) { /* bridge gone */ }
    }, true);
    return el;
  }

  function placeButtons() {
    if (document.fullscreenElement || document.documentElement.hasAttribute('data-fd-video-on')) { host.style.display = 'none'; return; }
    host.style.display = '';
    if (!host.isConnected && document.documentElement) document.documentElement.appendChild(host);
    var vids = document.getElementsByTagName('video');
    var shown = 0;
    // New videos get a button; removed ones lose it.
    for (var i = 0; i < vids.length; i++) {
      var v = vids[i];
      if (!buttons.some(function (b) { return b.video === v; })) {
        var el = button(v);
        root.appendChild(el);
        buttons.push({ video: v, el: el });
      }
    }
    buttons = buttons.filter(function (b) {
      if (!b.video.isConnected) { b.el.remove(); return false; }
      return true;
    });
    for (var k = 0; k < buttons.length; k++) {
      var b = buttons[k];
      var r = b.video.getBoundingClientRect();
      var visible = r.width >= MIN_W && r.height >= MIN_H && r.bottom > 30 && r.top < innerHeight - 30 &&
        getComputedStyle(b.video).visibility !== 'hidden';
      // Something over the video (a search box and its suggestions, a menu): no button over that.
      if (visible) {
        var player = b.video, pr;
        while (player.parentElement && player.parentElement !== document.body &&
          (pr = player.parentElement.getBoundingClientRect()).width * pr.height <= r.width * r.height * 1.35 + 1) player = player.parentElement;
        var cx = Math.min(Math.max(r.left + r.width / 2, 1), innerWidth - 1), cy = Math.min(Math.max(r.top + r.height / 2, 1), innerHeight - 1);
        var top = document.elementFromPoint(cx, cy);
        if (top && top !== host && !host.contains(top) && !player.contains(top) && !top.contains(player)) visible = false;
      }
      if (!visible) { b.el.style.display = 'none'; continue; }
      shown++;
      b.el.style.display = 'flex';
      // Top right: clear of the mute button players show at the top left, and of play and the timeline below.
      b.el.style.top = Math.max(8, r.top + 8) + 'px';
      b.el.style.left = Math.max(8, Math.min(innerWidth, r.right) - 42) + 'px';
      b.el.style.right = 'auto';
    }
    var count = buttons.length;
    if (count !== lastCount || location.href !== lastHref) {
      lastCount = count;
      lastHref = location.href;
      try { B.found(count, location.href, window.top === window); } catch (e) { /* bridge gone */ }
    }
    return shown;
  }

  // ---------- 3. Video playlists hidden behind other names ----------
  // Many sites load their HLS (.m3u8) or DASH (.mpd) playlist from an address that doesn't say so; the
  // content does. Found ones are offered when the video's Download button is tapped.
  (function () {
    var reported = Object.create(null);
    function report(url, kind) {
      if (!url || reported[url]) return;
      reported[url] = 1;
      try { B.media(String(url), kind, location.href); } catch (e) { /* bridge gone */ }
    }
    function sniff(url, text) {
      if (typeof text !== 'string') return;
      var head = text.slice(0, 400).replace(/^\uFEFF/, '').replace(/^\s+/, '');
      if (head.lastIndexOf('#EXTM3U', 0) === 0) report(url, 'm3u8');
      else if (/^(<\?xml[^>]*>\s*)?(<!--[\s\S]*?-->\s*)*<MPD[\s>]/.test(head)) report(url, 'mpd');
    }
    var P = XMLHttpRequest.prototype;
    var open = P.open;
    P.open = function () {
      if (!this.__fdSniff) {
        this.__fdSniff = 1;
        this.addEventListener('load', function () {
          try { if (this.responseType === '' || this.responseType === 'text') sniff(this.responseURL, this.responseText); } catch (e) { /* unreadable */ }
        });
      }
      return open.apply(this, arguments);
    };
    if (!window.fetch) return;
    window.fetch = new Proxy(window.fetch, {
      apply: function (f, self, a) {
        var p = Reflect.apply(f, self, a);
        p.then(function (res) {
          try {
            var ct = (res.headers.get('content-type') || '').toLowerCase();
            var len = +(res.headers.get('content-length') || 0);
            if (/mpegurl/.test(ct)) report(res.url, 'm3u8');
            else if (/dash\+xml/.test(ct)) report(res.url, 'mpd');
            else if ((ct === '' || /^text\/plain|octet-stream/.test(ct)) && len > 0 && len < 524288) {
              res.clone().text().then(function (t) { sniff(res.url, t); }, function () {});
            }
          } catch (e) { /* opaque response */ }
        }, function () {});
        return p;
      },
    });
  })();

  // ---------- 4. Video mode: the phone turned sideways while a video plays ----------
  // The app calls __fdVideoMode(true, [frames with videos]) in the top page. The playing video's player (with
  // its own controls) is pinned over the whole screen; the app hides its own bars. false undoes it.
  if (window.top === window) {
    var vmSheet = null;
    var vmMarked = [];
    var VM_CSS = [
      'html[data-fd-video-on],html[data-fd-video-on] body{overflow:hidden!important}',
      '[data-fd-up]{transform:none!important;filter:none!important;perspective:none!important;contain:none!important;' +
        'will-change:auto!important;z-index:2147483646!important}',
      '[data-fd-video]{position:fixed!important;left:0!important;top:0!important;right:auto!important;bottom:auto!important;' +
        'width:100vw!important;height:100vh!important;max-width:none!important;max-height:none!important;min-width:0!important;' +
        'min-height:0!important;margin:0!important;padding:0!important;border:0!important;border-radius:0!important;' +
        'transform:none!important;aspect-ratio:auto!important;z-index:2147483646!important;background:#000!important}',
      'video[data-fd-video]{object-fit:fill!important}',
      // Padding too: players such as YouTube's keep 16:9 with a padding-bottom, which would push the video down.
      '[data-fd-path]{width:100%!important;height:100%!important;max-width:none!important;max-height:none!important;' +
        'min-height:0!important;padding:0!important;transform:none!important;aspect-ratio:auto!important}',
      '[data-fd-vid]{position:absolute!important;left:0!important;top:0!important;width:100%!important;height:100%!important;' +
        'max-width:none!important;max-height:none!important;object-fit:fill!important;transform:none!important}',
    ];
    var vmMark = function (el, attr) { el.setAttribute(attr, ''); vmMarked.push([el, attr]); };
    var vmOff = function () {
      vmMarked.forEach(function (m) { m[0].removeAttribute(m[1]); });
      vmMarked = [];
      if (vmSheet) { vmSheet.remove(); vmSheet = null; }
      document.documentElement.removeAttribute('data-fd-video-on');
    };
    var vmPlaying = function () {
      var vs = document.getElementsByTagName('video'), best = null, area = 0;
      for (var i = 0; i < vs.length; i++) {
        var v = vs[i];
        if (v.paused || v.ended) continue;
        var r = v.getBoundingClientRect();
        if (r.width < 120 || r.height < 68 || r.bottom <= 0 || r.top >= innerHeight) continue;
        if (r.width * r.height > area) { area = r.width * r.height; best = v; }
      }
      return best;
    };
    var vmFrame = function (frames) {
      var list = document.getElementsByTagName('iframe'), best = null, area = 0;
      for (var i = 0; i < list.length; i++) {
        var f = list[i];
        if (frames.indexOf(f.src) < 0) continue;
        var r = f.getBoundingClientRect();
        if (r.width < 120 || r.height < 68) continue;
        if (r.width * r.height > area) { area = r.width * r.height; best = f; }
      }
      return best;
    };
    // bare: only the video itself (picture-in-picture), without the player's own buttons over it.
    var vmOn = function (frames, bare) {
      vmOff();
      var video = vmPlaying(), box, kind = 'video';
      if (video && bare) {
        box = video;
      } else if (video) {
        // The player: the largest box around the video that is still about the video's size.
        var vr = video.getBoundingClientRect();
        box = video;
        for (var p = video.parentElement; p && p !== document.body && p !== document.documentElement; p = p.parentElement) {
          var r = p.getBoundingClientRect();
          if (r.width * r.height > vr.width * vr.height * 1.35 + 1) break;
          box = p;
        }
        if (box !== video) {
          for (var e = video.parentElement; e && e !== box; e = e.parentElement) vmMark(e, 'data-fd-path');
          vmMark(video, 'data-fd-vid');
        }
      } else {
        box = vmFrame(frames || []);
        kind = 'frame';
        if (!box) return '';
      }
      vmMark(box, 'data-fd-video');
      for (var up = box.parentElement; up && up !== document.documentElement; up = up.parentElement) vmMark(up, 'data-fd-up');
      document.documentElement.setAttribute('data-fd-video-on', '');
      // Added rule by rule, which pages with a strict Content-Security-Policy still allow.
      vmSheet = document.createElement('style');
      (document.head || document.documentElement).appendChild(vmSheet);
      VM_CSS.forEach(function (rule) { try { vmSheet.sheet.insertRule(rule, vmSheet.sheet.cssRules.length); } catch (e2) { /* skip */ } });
      return kind;
    };
    try {
      Object.defineProperty(window, '__fdVideoMode', {
        value: function (on, frames, bare) {
          try {
            if (on) return vmOn(frames, bare);
            vmOff();
            // Players sized themselves for the full-screen or picture-in-picture window: have them measure again.
            [60, 400].forEach(function (ms) { setTimeout(function () { window.dispatchEvent(new Event('resize')); }, ms); });
            return '';
          } catch (e) { return ''; }
        },
      });
    } catch (e) { /* already there */ }
  }

  // ---------- 5b. Pinch zoom on every page ----------
  // Some pages turn zooming off in their viewport tag; the user can always zoom, as in other browsers.
  if (window.top === window) {
    var fdZoomable = function () {
      var m = document.querySelector('meta[name="viewport" i]');
      if (!m) return;
      var was = m.getAttribute('content') || '';
      var now = was.replace(/user-scalable\s*=\s*(no|0)/i, 'user-scalable=yes').replace(/maximum-scale\s*=\s*[\d.]+/i, 'maximum-scale=10');
      if (now !== was) m.setAttribute('content', now);
    };
    document.addEventListener('DOMContentLoaded', fdZoomable, { once: true });
    window.addEventListener('load', fdZoomable, { once: true });

    // Pop-ups that cover the screen (age checks, cookie and terms notices) but are taller than a phone screen: their
    // buttons end up below the screen with no way to scroll to them. Such a pop-up scrolls instead.
    var fdReachable = function () {
      if (!document.body) return;
      var vh = window.innerHeight;
      var near = [];
      Array.prototype.forEach.call(document.body.children, function (e) {
        near.push(e);
        Array.prototype.push.apply(near, e.children);
      });
      near.forEach(function (e) {
        if (e.scrollHeight <= e.clientHeight + 4 || e.clientHeight < vh * 0.8) return;
        var s = getComputedStyle(e);
        if (s.position !== 'fixed' || s.overflowY === 'auto' || s.overflowY === 'scroll') return;
        e.style.setProperty('overflow-y', 'auto', 'important');
        e.style.setProperty('-webkit-overflow-scrolling', 'touch');
      });
    };
    [0, 1000, 3000].forEach(function (ms) {
      window.addEventListener('load', function () { setTimeout(fdReachable, ms); }, { once: true });
    });
    document.addEventListener('DOMContentLoaded', fdReachable, { once: true });
    window.addEventListener('resize', fdReachable);
  }

  // ---------- 5c. Passwords (the app's password manager, ui/Passwords.kt) ----------
  // Top page only: it tells the app when it asks for a user name or password, hands over what was sent when the
  // user signs in, and fills a saved sign-in when the app asks (after the user's fingerprint).
  if (window.top === window && window.__fdBridge && window.__fdBridge.loginForm) {
    var fdUserLike = function (e) {
      if (!e || e.tagName !== 'INPUT') return false;
      var t = (e.getAttribute('type') || 'text').toLowerCase();
      if (t !== 'text' && t !== 'email' && t !== 'tel') return false;
      var a = ((e.getAttribute('autocomplete') || '') + ' ' + (e.name || '') + ' ' + (e.id || '')).toLowerCase();
      return t === 'email' || /user|mail|login|identifier|account|phone/.test(a);
    };
    var fdShown = function (e) {
      var r = e.getBoundingClientRect();
      return r.width > 0 && r.height > 0 && getComputedStyle(e).visibility !== 'hidden';
    };
    var fdFields = function () {
      var ins = Array.prototype.slice.call(document.getElementsByTagName('input'));
      return {
        pass: ins.filter(function (e) { return e.type === 'password' && fdShown(e); }),
        user: ins.filter(function (e) { return fdUserLike(e) && fdShown(e); }),
        all: ins,
      };
    };
    // Pages that ask for the user name first (Google, Microsoft…) keep it out of sight on the password step.
    var fdLastUser = '';
    document.addEventListener('change', function (ev) {
      var e = ev.target;
      if (fdUserLike(e) && e.value) {
        fdLastUser = e.value;
        try { __fdBridge.loginIdentifier(location.href, e.value); } catch (x) { /* skip */ }
      }
    }, true);
    // Tapping into a sign-in box: the app offers the saved sign-in with the fingerprint, as on an iPhone.
    document.addEventListener('focusin', function (ev) {
      var e = ev.target;
      if (e && e.tagName === 'INPUT' && (e.type === 'password' || fdUserLike(e)) && !e.value) {
        try { __fdBridge.loginFocus(location.href); } catch (x) { /* skip */ }
      }
    }, true);
    var fdSent = '';
    var fdCapture = function () {
      var f = fdFields();
      var p = f.pass.filter(function (e) { return e.value; })[0];
      if (!p) return;
      var u = f.user.filter(function (e) { return e.value; })[0] ||
        f.all.filter(function (e) { return e.value && e !== p && fdUserLike(e) && (!p.form || e.form === p.form); })[0];
      var user = u ? u.value : fdLastUser;
      if (fdSent === user + '\n' + p.value) return;
      fdSent = user + '\n' + p.value;
      try { __fdBridge.loginSubmitted(location.href, user, p.value); } catch (x) { /* skip */ }
    };
    document.addEventListener('submit', fdCapture, true);
    document.addEventListener('click', function (ev) {
      var t = ev.target;
      if (t && t.closest && t.closest('button,input[type=submit],input[type=button],[role=button]')) fdCapture();
    }, true);
    document.addEventListener('keydown', function (ev) {
      if (ev.key === 'Enter' && ev.target && ev.target.type === 'password') fdCapture();
    }, true);
    window.addEventListener('pagehide', fdCapture);

    var fdAsked = null;
    var fdScan = function () {
      var f = fdFields();
      var kind = f.pass.length ? 'password' : f.user.length ? 'username' : '';
      if (kind !== fdAsked) {
        fdAsked = kind;
        try { __fdBridge.loginForm(location.href, kind); } catch (x) { /* skip */ }
      }
    };
    var fdScanTimer = 0;
    var fdScanSoon = function () { clearTimeout(fdScanTimer); fdScanTimer = setTimeout(fdScan, 400); };
    document.addEventListener('DOMContentLoaded', function () {
      fdScan();
      new MutationObserver(fdScanSoon).observe(document.documentElement, { childList: true, subtree: true });
    }, { once: true });
    window.addEventListener('load', fdScanSoon, { once: true });

    // Typed in the way the page's own scripts (React and the like) notice.
    var fdSet = function (e, v) {
      var setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
      e.focus();
      setter.call(e, v);
      e.dispatchEvent(new Event('input', { bubbles: true }));
      e.dispatchEvent(new Event('change', { bubbles: true }));
    };
    // A password export made in the page (Google Password Manager's "Export passwords" builds the file in the page
    // and saves it from a blob: link) goes straight to the app's import, so no unencrypted copy lands on the phone.
    var fdBlobs = {};
    var fdMakeUrl = URL.createObjectURL;
    URL.createObjectURL = function (o) {
      var u = fdMakeUrl.apply(this, arguments);
      try { if (o instanceof Blob && o.size < 5e6) fdBlobs[u] = o; } catch (x) { /* skip */ }
      return u;
    };
    var fdExport = function (a) {
      var blob = a && a.href && fdBlobs[a.href];
      if (!blob) return false;
      var name = a.getAttribute('download') || '';
      if (!/\.csv$/i.test(name) && !/csv/i.test(blob.type)) return false;
      blob.text().then(function (text) {
        var head = text.slice(0, 300).split('\n')[0].toLowerCase();
        if (head.indexOf('password') >= 0) __fdBridge.passwordFile(location.href, name, text);
        else fdClick.call(a);
      });
      return true;
    };
    var fdClick = HTMLAnchorElement.prototype.click;
    HTMLAnchorElement.prototype.click = function () { if (!fdExport(this)) fdClick.apply(this, arguments); };
    document.addEventListener('click', function (ev) {
      var a = ev.target && ev.target.closest && ev.target.closest('a[href^="blob:"]');
      if (a && fdExport(a)) ev.preventDefault();
    }, true);

    Object.defineProperty(window, '__fdFill', {
      value: function (user, pass) {
        var f = fdFields();
        if (user && f.user[0] && !f.user[0].readOnly) fdSet(f.user[0], user);
        if (pass && f.pass[0]) fdSet(f.pass[0], pass);
        fdLastUser = user || fdLastUser;
        return f.pass.length ? 'password' : f.user.length ? 'username' : '';
      },
    });
  }

  // ---------- 6. Video shape and speed (the app's full-screen video controls) ----------
  // The app posts {__fdVideo: {fit, rate}} to the top page; each frame applies it to its own videos and passes it
  // on to its frames, so players in frames of other sites follow too.
  var FITS = { original: 'scale-down', fit: 'contain', fill: 'fill', zoom: 'cover' };
  var fitSheet = null;
  var fdRate = 0;
  // Picture-in-picture buttons. Pause stops what plays (and remembers it); play resumes those, or else the largest
  // video that was started before; seek moves the playing video.
  var fdPausedByApp = [];
  var videoCommand = function (o) {
    var vs = Array.prototype.slice.call(document.getElementsByTagName('video'));
    var playing = vs.filter(function (v) { return !v.paused && !v.ended; });
    try {
      if (o.cmd === 'pause') {
        fdPausedByApp = playing;
        playing.forEach(function (v) { v.pause(); });
      } else if (o.cmd === 'play') {
        var again = fdPausedByApp.filter(function (v) { return v.isConnected; });
        if (!again.length) {
          var best = null, area = 0;
          vs.forEach(function (v) {
            var r = v.getBoundingClientRect();
            if (v.currentTime > 0 && r.width * r.height >= area) { area = r.width * r.height; best = v; }
          });
          if (best) again = [best];
        }
        fdPausedByApp = [];
        again.forEach(function (v) { var p = v.play(); if (p && p.catch) p.catch(function () { /* blocked */ }); });
      } else if (o.cmd === 'seek') {
        playing.forEach(function (v) { v.currentTime = Math.max(0, v.currentTime + o.by); });
      }
    } catch (e) { /* skip */ }
  };
  var applyVideo = function (o) {
    if (o.fit && FITS[o.fit]) {
      var f = FITS[o.fit];
      if (!fitSheet) { fitSheet = document.createElement('style'); (document.head || document.documentElement).appendChild(fitSheet); }
      var sh = fitSheet.sheet;
      try { while (sh.cssRules.length) sh.deleteRule(0); } catch (e) { /* skip */ }
      [
        ':fullscreen video,video:fullscreen{position:fixed!important;left:0!important;top:0!important;width:100vw!important;' +
          'height:100vh!important;max-width:none!important;max-height:none!important;transform:none!important;object-fit:' + f + '!important;background:#000!important}',
        ':-webkit-full-screen video,video:-webkit-full-screen{position:fixed!important;left:0!important;top:0!important;width:100vw!important;' +
          'height:100vh!important;transform:none!important;object-fit:' + f + '!important;background:#000!important}',
        'html video[data-fd-vid],html video[data-fd-video]{object-fit:' + f + '!important}',
      ].forEach(function (rule) { try { sh.insertRule(rule, sh.cssRules.length); } catch (e) { /* skip */ } });
    }
    if (o.cmd) videoCommand(o);
    if (o.rate > 0) {
      fdRate = o.rate;
      var vs = document.getElementsByTagName('video');
      for (var i = 0; i < vs.length; i++) try { vs[i].playbackRate = o.rate; } catch (e) { /* skip */ }
    }
    var fr = document.getElementsByTagName('iframe');
    for (var j = 0; j < fr.length; j++) try { fr[j].contentWindow.postMessage({ __fdVideo: o }, '*'); } catch (e) { /* skip */ }
  };
  window.addEventListener('message', function (e) { if (e.data && e.data.__fdVideo) applyVideo(e.data.__fdVideo); });
  // Players reset the speed for each new video: keep the chosen one.
  document.addEventListener('play', function (e) {
    if (fdRate && e.target && e.target.tagName === 'VIDEO' && e.target.playbackRate !== fdRate) e.target.playbackRate = fdRate;
  }, true);
  // Whether a video is playing, so leaving the app can open picture-in-picture.
  var fdPlayingSent = false;
  var fdPlayState = function () {
    var on = false, vs = document.getElementsByTagName('video');
    for (var i = 0; i < vs.length; i++) if (!vs[i].paused && !vs[i].ended && vs[i].readyState > 2) { on = true; break; }
    if (on !== fdPlayingSent) { fdPlayingSent = on; try { __fdBridge.playing(on); } catch (e) { /* no bridge */ } }
  };
  ['playing', 'pause', 'ended', 'emptied'].forEach(function (t) { document.addEventListener(t, fdPlayState, true); });

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start, { once: true });
  else start();
})();
