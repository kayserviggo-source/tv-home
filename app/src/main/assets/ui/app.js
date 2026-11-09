/* TV Home interface. Everything on screen is driven from here; the TV itself is reached
   through the `Native` object that the Android side provides. */
(function () {
  'use strict';

  var Native = window.Native;
  var stage = document.getElementById('stage');

  function $(selector, root) { return (root || document).querySelector(selector); }
  function $$(selector, root) { return Array.prototype.slice.call((root || document).querySelectorAll(selector)); }
  function esc(text) {
    return String(text == null ? '' : text).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  if (!Native) {
    stage.innerHTML = '<div style="padding:96px;font-size:32px;line-height:1.4">This is the TV Home interface. ' +
      'It only works inside the TV app. Add ?mock=1 to the address to preview it with sample content.</div>';
    return;
  }

  function call(name) {
    var args = Array.prototype.slice.call(arguments, 1);
    try { return Native[name].apply(Native, args); } catch (e) { return undefined; }
  }
  function callJson(name, fallback) {
    var args = Array.prototype.slice.call(arguments, 2);
    try {
      var parsed = JSON.parse(Native[name].apply(Native, args));
      return parsed == null ? fallback : parsed;
    } catch (e) { return fallback; }
  }

  var store = {
    get: function (key, fallback) {
      try {
        var raw = localStorage.getItem(key);
        return raw === null ? fallback : JSON.parse(raw);
      } catch (e) { return fallback; }
    },
    set: function (key, value) {
      try { localStorage.setItem(key, JSON.stringify(value)); } catch (e) { /* storage unavailable */ }
    }
  };

  var MAX_PINS = 4;
  var prefs = {
    autoOpen: store.get('autoOpen', true),
    idleMin: store.get('idleMin', 2)
  };
  var state = {
    view: 'home',
    returnView: 'home',
    panel: false,
    visible: true,
    np: null,
    npKey: '',
    apps: [],
    pins: [],
    status: {},
    feedSig: null,
    lastInput: Date.now(),
    lastCommandAt: 0,
    stoppedSince: 0,
    awake: null,
    sleep: { mode: 'off', at: 0, minutes: 0 },
    lastFocus: {},
    panelReturn: null
  };

  /* ---------- Scaling: the layout is 1920x1080 and is fitted to whatever the TV reports ---------- */

  var scale = 1;
  function fit() {
    scale = Math.min(window.innerWidth / 1920, window.innerHeight / 1080) || 1;
    stage.style.transform = Math.abs(scale - 1) < 0.001 ? '' : 'scale(' + scale + ')';
  }

  /* ---------- Clock ---------- */

  var lastClock = '';
  function renderClock() {
    var now = new Date();
    var time = ('0' + now.getHours()).slice(-2) + ':' + ('0' + now.getMinutes()).slice(-2);
    if (time === lastClock) { return; }
    lastClock = time;
    var date = now.toLocaleDateString('en-GB', { weekday: 'long', day: 'numeric', month: 'long' });
    $$('[data-clock="time"]').forEach(function (el) { el.textContent = time; });
    $$('[data-clock="date"]').forEach(function (el) { el.textContent = date; });
  }

  /* ---------- Focus: moving around with the remote ---------- */

  function scopeRoot() { return state.panel ? $('#panel') : $('#' + state.view); }
  function isShown(el) { return el.offsetParent !== null && !el.disabled; }
  function focusables() { return $$('.f', scopeRoot()).filter(isShown); }

  function defaultFocus() {
    var root = scopeRoot();
    var pick = null;
    if (state.panel) {
      pick = $('.opt[aria-pressed="true"]', root);
    } else if (state.view === 'home') {
      pick = $('#now [data-act="toggle"]');
      if (!pick || !isShown(pick)) { pick = $$('#now .pill.f').filter(isShown)[0] || null; }
      if (!pick || !isShown(pick)) { pick = $('#pins .f'); }
    } else if (state.view === 'player') {
      pick = $('#player [data-act="toggle"]');
      if (!pick || !isShown(pick)) { pick = $('#player [data-act="home"]'); }
    }
    if (!pick || !isShown(pick)) { pick = focusables()[0] || null; }
    return pick;
  }

  function focus(el) {
    if (!el) { return; }
    try { el.focus({ preventScroll: true }); } catch (e) { el.focus(); }
    reveal(el);
  }

  function rememberFocus() {
    var el = document.activeElement;
    if (el && el.classList && el.classList.contains('f')) { state.lastFocus[state.view] = el; }
  }

  function restoreFocus() {
    var el = state.lastFocus[state.view];
    if (!el || !document.body.contains(el) || !isShown(el) || scopeRoot() !== el.closest('.view')) {
      el = defaultFocus();
    }
    if (el) { focus(el); } else if (document.activeElement) { document.activeElement.blur(); }
  }

  /** Scrolls whatever needs scrolling so the focused item is comfortably on screen. */
  function reveal(el) {
    var scroller = el.closest('.scroller');
    if (scroller) {
      var left = el.offsetLeft - (scroller.clientWidth - el.offsetWidth) / 2;
      scroller.scrollTo({ left: Math.max(0, left), behavior: 'smooth' });
    }
    var rows = el.closest('#rows');
    if (rows) {
      var row = el.closest('.row');
      var top = 0;
      if (row) {
        // Rows snap to the top. The last row keeps the one before it on screen when both fit,
        // so the bottom of the list is not mostly empty.
        var anchor = row;
        var before = row.previousElementSibling;
        if (!row.nextElementSibling && before && before.classList.contains('row') &&
            row.offsetTop + row.offsetHeight - (before.offsetTop - 28) <= rows.clientHeight - 40) {
          anchor = before;
        }
        top = Math.max(0, anchor.offsetTop - 28);
      }
      rows.scrollTo({ top: top, behavior: 'smooth' });
      return;
    }
    var box = el.closest('.grid-scroll, .panel-body');
    if (box) {
      var r = el.getBoundingClientRect();
      var c = box.getBoundingClientRect();
      var top = (r.top - c.top) / scale + box.scrollTop;
      var height = r.height / scale;
      var margin = 70;
      if (top - margin < box.scrollTop) {
        box.scrollTo({ top: Math.max(0, top - margin), behavior: 'smooth' });
      } else if (top + height + margin > box.scrollTop + box.clientHeight) {
        box.scrollTo({ top: top + height + margin - box.clientHeight, behavior: 'smooth' });
      }
    }
  }

  /** Where an item sits in the page's own layout, ignoring how far its list is scrolled up or down. */
  function layoutRect(el) {
    var r = el.getBoundingClientRect();
    var box = el.closest('#rows, .grid-scroll, .panel-body');
    var shift = box ? box.scrollTop * scale : 0;
    return { left: r.left, right: r.right, top: r.top + shift, bottom: r.bottom + shift };
  }

  function move(direction) {
    var items = focusables();
    if (!items.length) { return; }
    var current = document.activeElement;
    if (!current || items.indexOf(current) < 0) { focus(defaultFocus() || items[0]); return; }

    var a = layoutRect(current);
    var zone = current.closest('[data-zone]');
    var best = null;
    var bestScore = Infinity;

    if (direction === 'left' || direction === 'right') {
      items.forEach(function (el) {
        if (el === current) { return; }
        var b = layoutRect(el);
        var ahead = direction === 'right' ? b.left - a.left : a.left - b.left;
        if (ahead <= 1) { return; }
        var gap = Math.max(0, direction === 'right' ? b.left - a.right : a.left - b.right);
        var overlap = Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top);
        // Sideways moves stay level. Only inside one group may they step to a different height.
        if (overlap <= 0 && (!zone || el.closest('[data-zone]') !== zone)) { return; }
        var offset = Math.abs((a.top + a.bottom) / 2 - (b.top + b.bottom) / 2);
        var score = gap + (overlap > 0 ? 0 : 3000) + offset * 2;
        if (score < bestScore) { bestScore = score; best = el; }
      });
    } else {
      // Up and down go to the nearest line of items first, then to whichever item on it is closest.
      var candidates = [];
      var nearest = Infinity;
      items.forEach(function (el) {
        if (el === current) { return; }
        var b = layoutRect(el);
        var distance = direction === 'down' ? b.top - a.bottom : a.top - b.bottom;
        if (distance < -4) { return; }
        distance = Math.max(0, distance);
        candidates.push({ el: el, rect: b, distance: distance });
        if (distance < nearest) { nearest = distance; }
      });
      // In a group laid out as blocks side by side (the hero: player card next to the pinned
      // apps), up and down stay in the same column instead of jumping across to the other block.
      var strict = zone && zone.hasAttribute('data-columns');
      if (strict) {
        candidates = candidates.filter(function (c) {
          if (c.el.closest('[data-zone]') !== zone) { return true; }
          return Math.max(a.left, c.rect.left) < Math.min(a.right, c.rect.right);
        });
        nearest = Infinity;
        candidates.forEach(function (c) { if (c.distance < nearest) { nearest = c.distance; } });
      }
      candidates.forEach(function (c) {
        if (c.distance > nearest + 40 * scale) { return; }
        var b = c.rect;
        var side = Math.max(0, Math.max(a.left, b.left) - Math.min(a.right, b.right));
        var centre = Math.abs((a.left + a.right) / 2 - (b.left + b.right) / 2);
        var score = side + centre * 0.05 + c.distance * 0.5;
        if (score < bestScore) { bestScore = score; best = c.el; }
      });
    }

    if (best) { focus(best); }
  }

  /* ---------- Views ---------- */

  function show(view) {
    if (state.view === view) { return; }
    rememberFocus();
    if (view === 'ambient') { state.returnView = state.view; }
    if (view === 'allapps') { renderAllApps(); }
    state.view = view;
    $$('.view').forEach(function (v) { v.classList.toggle('on', v.id === view); });
    if (view === 'ambient') {
      if (document.activeElement) { document.activeElement.blur(); }
    } else {
      if (view === 'allapps') { state.lastFocus.allapps = null; }
      restoreFocus();
    }
    updateAwake();
  }

  function leaveAmbient() {
    if (state.view === 'ambient') { show(state.returnView || 'home'); }
  }

  function goHome() {
    closePanel();
    if (state.view !== 'home') { show('home'); }
    $('#rows').scrollTo({ top: 0, behavior: 'smooth' });
    focus(defaultFocus());
  }

  function back() {
    if (state.panel) { closePanel(); return; }
    if (state.view === 'ambient') { leaveAmbient(); return; }
    goHome();
  }

  /* ---------- Now playing ---------- */

  function isMusicApp(pkg) { return /^com\.spotify\./.test(pkg || ''); }

  function clock(ms) {
    if (!(ms >= 0)) { return ''; }
    var total = Math.floor(ms / 1000);
    var h = Math.floor(total / 3600);
    var m = Math.floor((total % 3600) / 60);
    var s = total % 60;
    return (h ? h + ':' + ('0' + m).slice(-2) : m) + ':' + ('0' + s).slice(-2);
  }

  function media(command, argument) {
    state.lastCommandAt = Date.now();
    call('media', command, argument == null ? '' : String(argument));
    setTimeout(pull, 180);
    setTimeout(pull, 700);
  }

  function pull() {
    var np = callJson('getNowPlaying', { access: false, active: false });
    var before = state.np;
    state.np = np;
    var key = np.active ? np.title + '\n' + (np.artist || '') : '';

    var startedNow = before && np.active && np.playing && !(before.active && before.playing);
    if (startedNow && prefs.autoOpen && state.visible && state.view === 'home' && !state.panel &&
        isMusicApp(np.pkg) && Date.now() - state.lastCommandAt > 4000) {
      show('player');
    }

    var sleepBefore = state.sleep.mode + ':' + state.sleep.minutes;
    state.sleep = np.sleep || { mode: 'off', at: 0, minutes: 0 };
    if (np.sleepFired) { toast('Sleep timer: music paused'); }
    if (state.panel && sleepBefore !== state.sleep.mode + ':' + state.sleep.minutes) { renderPanel(); }

    if (np.active && np.playing) {
      state.stoppedSince = 0;
    } else if (!state.stoppedSince) {
      state.stoppedSince = Date.now();
    }

    state.npKey = key;
    renderNow();
    updateAwake();
  }

  function setAll(name, text, longAt) {
    $$('[data-np="' + name + '"]').forEach(function (el) {
      if (el.textContent !== text) { el.textContent = text; }
      if (longAt) {
        var limit = el.classList.contains('player-title') ? longAt + 2 : longAt;
        el.classList.toggle('long', text.length > limit);
      }
    });
  }

  var lastArt = null;
  function renderNow() {
    var np = state.np || { access: false, active: false };
    var mode = !np.access ? 'setup' : (np.active ? 'active' : 'idle');
    stage.classList.toggle('np-setup', mode === 'setup');
    stage.classList.toggle('np-idle', mode === 'idle');
    stage.classList.toggle('playing', mode === 'active' && !!np.playing);
    stage.classList.toggle('paused', !(mode === 'active' && np.playing));

    var title, artist = '', status, hint = '', source = '', art = '', position = '', duration = '', fraction = 0;
    if (mode === 'setup') {
      status = 'Setup';
      title = 'Almost there';
      hint = 'One permission is missing before your music can show up here.';
    } else if (mode === 'idle') {
      status = 'Music';
      title = 'Nothing playing';
      hint = 'Start something on your phone and it shows up here.';
    } else {
      status = np.playing ? 'Now playing' : 'Paused';
      title = np.title || '';
      artist = np.artist || '';
      source = [np.album, np.app].filter(function (x) { return x && x !== np.title; }).join(' · ');
      art = np.art || '';
      position = clock(np.position);
      duration = np.duration > 0 ? clock(np.duration) : '';
      fraction = np.duration > 0 ? Math.min(1, np.position / np.duration) : 0;
    }

    setAll('status', status);
    setAll('title', title, 16);
    setAll('artist', artist);
    setAll('hint', hint);
    setAll('source', source);
    setAll('pos', position);
    setAll('dur', duration);
    setAll('open-app', 'Open ' + ((mode === 'active' && np.app) || 'Spotify'));
    $$('[data-np="bar"]').forEach(function (el) { el.style.width = (fraction * 100).toFixed(2) + '%'; });

    if (art !== lastArt) {
      lastArt = art;
      $$('img[data-np="art"]').forEach(function (img, index) {
        img.onerror = function () { img.removeAttribute('src'); };
        if (index === 0) { img.onload = function () { tintFrom(img); }; }
        if (art) { img.src = art; } else { img.removeAttribute('src'); }
      });
      if (!art) { stage.style.removeProperty('--label'); }
    }

    // If the focused control just disappeared (music stopped, say), move to a sensible one.
    var active = document.activeElement;
    if (state.view !== 'ambient' && (!active || active === document.body || !isShown(active))) {
      var fallback = defaultFocus();
      if (fallback) { focus(fallback); }
    }
  }

  /** Colours the record's label from the cover, kept within a range that suits the palette. */
  function tintFrom(img) {
    try {
      var canvas = document.createElement('canvas');
      canvas.width = canvas.height = 8;
      var ctx = canvas.getContext('2d');
      ctx.drawImage(img, 0, 0, 8, 8);
      var data = ctx.getImageData(0, 0, 8, 8).data;
      var r = 0, g = 0, b = 0, n = data.length / 4;
      for (var i = 0; i < data.length; i += 4) { r += data[i]; g += data[i + 1]; b += data[i + 2]; }
      r /= n * 255; g /= n * 255; b /= n * 255;
      var max = Math.max(r, g, b), min = Math.min(r, g, b);
      var l = (max + min) / 2, d = max - min, h = 0;
      var s = d === 0 ? 0 : d / (1 - Math.abs(2 * l - 1));
      if (d !== 0) {
        if (max === r) { h = ((g - b) / d) % 6; } else if (max === g) { h = (b - r) / d + 2; } else { h = (r - g) / d + 4; }
        h = Math.round(h * 60); if (h < 0) { h += 360; }
      }
      s = Math.max(0.3, Math.min(0.6, s));
      l = Math.max(0.42, Math.min(0.56, l));
      stage.style.setProperty('--label', 'hsl(' + h + ',' + Math.round(s * 100) + '%,' + Math.round(l * 100) + '%)');
    } catch (e) {
      stage.style.removeProperty('--label');
    }
  }

  function updateAwake() {
    var np = state.np;
    var want = !!(np && np.active && np.playing && state.visible);
    if (want !== state.awake) { state.awake = want; call('keepAwake', want); }
  }

  /* ---------- Sleep timer ---------- */

  function sleepText() {
    if (state.sleep.mode === 'timer') {
      var left = Math.max(1, Math.ceil((state.sleep.at - Date.now()) / 60000));
      return left + ' min left';
    }
    if (state.sleep.mode === 'track') { return 'End of track'; }
    return 'Off';
  }

  function renderSleepLabel() {
    var text = 'Sleep timer · ' + sleepText();
    $$('[data-sleep-label]').forEach(function (el) { if (el.textContent !== text) { el.textContent = text; } });
    var heading = $('#panel [data-sleep-heading]');
    if (heading) { heading.textContent = state.sleep.mode === 'timer' ? 'Sleep timer · ' + sleepText() : 'Sleep timer'; }
  }

  /* ---------- Apps ---------- */

  function imageUrl(kind, pkg) {
    if (window.MockImages) { return window.MockImages(kind, pkg); }
    return kind + '/' + encodeURIComponent(pkg);
  }

  function appByPkg(pkg) {
    for (var i = 0; i < state.apps.length; i++) { if (state.apps[i].pkg === pkg) { return state.apps[i]; } }
    return null;
  }

  function defaultPins() {
    var wanted = [/^spotify/i, /^youtube$/i, /netflix/i, /svt/i];
    var pins = [];
    wanted.forEach(function (pattern) {
      for (var i = 0; i < state.apps.length; i++) {
        var app = state.apps[i];
        if (pattern.test(app.label) && pins.indexOf(app.pkg) < 0) { pins.push(app.pkg); return; }
      }
    });
    state.apps.forEach(function (app) {
      if (pins.length < MAX_PINS && app.tv && pins.indexOf(app.pkg) < 0) { pins.push(app.pkg); }
    });
    return pins.slice(0, MAX_PINS);
  }

  function loadApps() {
    state.apps = callJson('getApps', []);
    var saved = store.get('pins', null);
    var pins = saved ? saved.filter(function (pkg) { return !!appByPkg(pkg); }) : defaultPins();
    var changed = JSON.stringify(pins) !== JSON.stringify(state.pins);
    state.pins = pins;
    if (changed || !$('#pins').children.length) { renderPins(); }
  }

  function makeTile(app) {
    var tile = document.createElement('button');
    tile.className = 'tile f' + (app.banner ? ' has-banner' : '');
    tile.dataset.pkg = app.pkg;
    tile.setAttribute('aria-label', app.label);
    var html = '';
    if (app.banner) { html += '<img class="banner" alt="" src="' + esc(imageUrl('banner', app.pkg)) + '">'; }
    html += '<img class="icon" alt="" src="' + esc(imageUrl('icon', app.pkg)) + '">';
    html += '<span class="name">' + esc(app.label) + '</span><span class="badge">On Home</span>';
    tile.innerHTML = html;
    var banner = $('img.banner', tile);
    if (banner) { banner.onerror = function () { tile.classList.remove('has-banner'); banner.remove(); }; }
    var icon = $('img.icon', tile);
    icon.onerror = function () { icon.remove(); };
    return tile;
  }

  function renderPins() {
    var box = $('#pins');
    var hadFocus = box.contains(document.activeElement) ? document.activeElement.dataset.pkg : null;
    box.innerHTML = '';
    state.pins.forEach(function (pkg) {
      var app = appByPkg(pkg);
      if (app) { box.appendChild(makeTile(app)); }
    });
    if (box.children.length < MAX_PINS) {
      var add = document.createElement('button');
      add.className = 'tile f';
      add.dataset.act = 'apps';
      add.innerHTML = '<span class="name">All apps</span>';
      box.appendChild(add);
    }
    if (hadFocus) {
      var again = $('[data-pkg="' + hadFocus + '"]', box) || box.firstElementChild;
      if (again && state.view === 'home' && !state.panel) { focus(again); }
    }
  }

  function renderAllApps() {
    var grid = $('#app-grid');
    grid.innerHTML = '';
    state.apps.forEach(function (app) {
      var cell = document.createElement('div');
      cell.className = 'app-cell';
      var tile = makeTile(app);
      tile.classList.toggle('pinned', state.pins.indexOf(app.pkg) >= 0);
      var caption = document.createElement('div');
      caption.className = 'caption';
      caption.textContent = app.label;
      cell.appendChild(tile);
      cell.appendChild(caption);
      grid.appendChild(cell);
    });
    $('#grid-scroll').scrollTop = 0;
  }

  function togglePin(pkg) {
    var index = state.pins.indexOf(pkg);
    var app = appByPkg(pkg);
    if (index >= 0) {
      state.pins.splice(index, 1);
      toast((app ? app.label : 'App') + ' taken off Home');
    } else if (state.pins.length >= MAX_PINS) {
      toast('Home holds ' + MAX_PINS + ' apps. Take one off first.');
      return;
    } else {
      state.pins.push(pkg);
      toast((app ? app.label : 'App') + ' added to Home');
    }
    store.set('pins', state.pins);
    renderPins();
    $$('#app-grid .tile').forEach(function (tile) {
      tile.classList.toggle('pinned', state.pins.indexOf(tile.dataset.pkg) >= 0);
    });
    state.feedSig = null;
    loadFeed();
  }

  /* ---------- Rows from other apps ---------- */

  function minutesLeft(ms) {
    if (!(ms > 0)) { return ''; }
    var minutes = Math.round(ms / 60000);
    if (minutes < 1) { return ''; }
    if (minutes < 60) { return minutes + ' min left'; }
    return Math.floor(minutes / 60) + ' h ' + ('0' + (minutes % 60)).slice(-2) + ' min left';
  }

  function makeCard(item, key, withTag) {
    var card = document.createElement('button');
    card.className = 'card f';
    card.dataset.intent = item.intent || '';
    card.dataset.owner = item.pkg || '';
    card.dataset.key = key;
    var sub = [item.sub, minutesLeft(item.left)].filter(Boolean).join(' · ');
    var html = '<div class="poster">';
    if (item.poster) { html += '<img alt="" src="' + esc(item.poster) + '">'; }
    else { html += '<div class="fallback">' + esc(item.title) + '</div>'; }
    if (withTag && item.app) { html += '<div class="tag">' + esc(item.app) + '</div>'; }
    if (item.progress > 0) { html += '<div class="left"><i style="width:' + Math.round(Math.min(1, item.progress) * 100) + '%"></i></div>'; }
    html += '</div><div><div class="title">' + esc(item.title || item.app || '') + '</div>';
    if (sub) { html += '<div class="sub">' + esc(sub) + '</div>'; }
    html += '</div>';
    card.innerHTML = html;
    card.setAttribute('aria-label', item.title || item.app || 'Open');
    var img = $('img', card);
    if (img) {
      img.onerror = function () {
        var fallback = document.createElement('div');
        fallback.className = 'fallback';
        fallback.textContent = item.title || item.app || '';
        img.replaceWith(fallback);
      };
    }
    return card;
  }

  function makeRow(id, heading, aside, cards) {
    var row = document.createElement('section');
    row.className = 'row';
    row.dataset.zone = id;
    row.innerHTML = '<div class="row-head"><h2>' + esc(heading) + '</h2><span>' + esc(aside) + '</span></div>' +
      '<div class="scroller"></div>';
    var scroller = $('.scroller', row);
    cards.forEach(function (card) { scroller.appendChild(card); });
    return row;
  }

  function loadFeed() {
    state.status = callJson('getStatus', {});
    var watchNext = state.status.tvListings ? callJson('getWatchNext', []) : [];
    var previews = state.status.tvListings ? callJson('getPreviews', []) : [];
    var sig = JSON.stringify([watchNext, previews, state.pins, !!state.status.tvListings]);
    if (sig === state.feedSig) { return; }
    state.feedSig = sig;

    var feed = $('#feed');
    var focusedKey = feed.contains(document.activeElement) ? document.activeElement.dataset.key : null;
    feed.innerHTML = '';

    if (!state.status.tvListings) {
      var ask = document.createElement('section');
      ask.className = 'row';
      ask.dataset.zone = 'ask';
      ask.innerHTML = '<div class="row-head"><h2>Continue watching</h2><span></span></div>' +
        '<div class="ask"><p>Home can show what Netflix, SVT Play and your other apps have lined up for you. ' +
        'It needs your permission to read what they share.</p>' +
        '<button class="pill f" data-setup="listings" data-done="0" data-key="ask">Allow</button></div>';
      feed.appendChild(ask);
    }

    if (watchNext.length) {
      var names = [];
      watchNext.forEach(function (item) { if (item.app && names.indexOf(item.app) < 0) { names.push(item.app); } });
      feed.appendChild(makeRow('continue', 'Continue watching', names.slice(0, 3).join(' · '),
        watchNext.map(function (item) { return makeCard(item, 'wn:' + item.id, true); })));
    }

    var rank = function (channel) {
      var index = state.pins.indexOf(channel.pkg);
      return index < 0 ? 99 : index;
    };
    previews.slice().sort(function (a, b) { return rank(a) - rank(b); }).slice(0, 4).forEach(function (channel, n) {
      feed.appendChild(makeRow('preview' + n, channel.app || 'From your apps', channel.name || '',
        channel.items.map(function (item) { return makeCard(item, 'pv:' + item.id, false); })));
    });

    if (focusedKey) {
      var again = $('[data-key="' + focusedKey + '"]', feed);
      if (state.view === 'home' && !state.panel) { focus(again || defaultFocus()); }
    }
  }

  /* ---------- Control Centre ---------- */

  function option(group, value, label, pressed) {
    return '<button class="opt f" data-opt="' + group + ':' + value + '" aria-pressed="' + (pressed ? 'true' : 'false') + '">' +
      esc(label) + '</button>';
  }

  function setupRow(done, title, text, action, actionLabel) {
    return '<button class="setup-row f' + (done ? ' done' : '') + '" data-setup="' + action + '" data-done="' + (done ? '1' : '0') +
      '"><span class="what"><b>' + esc(title) + '</b><span>' + esc(text) + '</span></span>' +
      '<span class="state">' + (done ? 'On' : esc(actionLabel)) + '</span></button>';
  }

  function renderPanel() {
    var body = $('#panel-body');
    var active = document.activeElement;
    var focusedOpt = body.contains(active) ? (active.dataset.opt || active.dataset.setup || active.dataset.act || active.dataset.input) : null;
    var status = state.status = callJson('getStatus', {});
    var inputs = callJson('getInputs', []);
    var sleep = state.sleep;
    var html = '';

    html += '<div class="group" data-zone="sleep"><h2 data-sleep-heading>Sleep timer</h2><div class="options">' +
      option('sleep', 'off', 'Off', sleep.mode === 'off') +
      option('sleep', '15', '15 min', sleep.mode === 'timer' && sleep.minutes === 15) +
      option('sleep', '30', '30 min', sleep.mode === 'timer' && sleep.minutes === 30) +
      option('sleep', '60', '60 min', sleep.mode === 'timer' && sleep.minutes === 60) +
      option('sleep', 'track', 'End of track', sleep.mode === 'track') + '</div></div>';

    html += '<div class="group" data-zone="auto"><h2>When music starts from your phone</h2><div class="options">' +
      option('auto', 'on', 'Open the player', prefs.autoOpen) +
      option('auto', 'off', 'Stay where I am', !prefs.autoOpen) + '</div></div>';

    html += '<div class="group" data-zone="idle"><h2>Clock screen while music plays</h2><div class="options">' +
      option('idle', '2', 'After 2 min', prefs.idleMin === 2) +
      option('idle', '5', 'After 5 min', prefs.idleMin === 5) +
      option('idle', '0', 'Never', prefs.idleMin === 0) + '</div></div>';

    if (inputs.length) {
      html += '<div class="group" data-zone="inputs"><h2>Input</h2><div class="options">';
      inputs.forEach(function (input) {
        html += '<button class="opt f" data-input="' + esc(input.id) + '">' + esc(input.label) + '</button>';
      });
      html += '</div></div>';
    }

    html += '<div class="group" data-zone="shortcuts"><h2>Shortcuts</h2><div class="options">' +
      '<button class="opt f" data-act="apps">All apps</button>' +
      '<button class="opt f" data-act="settings">TV settings</button></div></div>';

    html += '<div class="group" data-zone="setup"><h2>Setup</h2>' +
      setupRow(!!status.nowPlayingAccess, 'Now playing',
        status.nowPlayingAccess ? 'Shows the current track from Spotify and other apps.'
          : 'Needs notification access. Use the setup guide, or try the button.', 'notifications', 'Open settings') +
      setupRow(!!status.homeButton, 'Home button',
        status.homeButton ? 'The remote’s Home button opens this screen.'
          : 'Not sending the Home button here yet. Use the setup guide, or try the button.', 'accessibility', 'Open settings') +
      setupRow(!!status.tvListings, 'Continue watching',
        status.tvListings ? 'Shows what your apps share for Home.'
          : 'Lets Home show what Netflix and other apps share.', 'listings', 'Allow') +
      '</div>';

    var web = /Chrome\/(\d+)/.exec(navigator.userAgent);
    html += '<div class="about">TV Home ' + esc(status.version || '') + ' · ' + esc(status.device || '') +
      ' · Android ' + esc(status.android || '') + (web ? ' · WebView ' + web[1] : '') + '</div>';

    body.innerHTML = html;
    renderSleepLabel();

    if (state.panel) {
      var again = null;
      if (focusedOpt) {
        again = $('[data-opt="' + focusedOpt + '"]', body) || $('[data-setup="' + focusedOpt + '"]', body) ||
          $('[data-act="' + focusedOpt + '"]', body);
      }
      focus(again || $('.opt[aria-pressed="true"]', body) || $('.f', body));
    }
  }

  function openPanel() {
    if (state.panel) { return; }
    rememberFocus();
    state.panelReturn = document.activeElement;
    state.panel = true;
    stage.classList.add('panel-open');
    $('#panel-body').scrollTop = 0;
    renderPanel();
  }

  function closePanel() {
    if (!state.panel) { return; }
    state.panel = false;
    stage.classList.remove('panel-open');
    var el = state.panelReturn;
    state.panelReturn = null;
    if (el && document.body.contains(el) && isShown(el) && el.closest('.view') === scopeRoot()) { focus(el); }
    else { restoreFocus(); }
  }

  function setOption(group, value) {
    if (group === 'sleep') {
      // The TV side keeps the timer, so it still fires when another app is in front.
      call('setSleep', value);
      var minutes = parseInt(value, 10) || 0;
      state.sleep = value === 'track' ? { mode: 'track', at: 0, minutes: 0 }
        : minutes > 0 ? { mode: 'timer', at: Date.now() + minutes * 60000, minutes: minutes }
        : { mode: 'off', at: 0, minutes: 0 };
    } else if (group === 'auto') {
      prefs.autoOpen = value === 'on';
      store.set('autoOpen', prefs.autoOpen);
      call('setPref', 'autoOpen', prefs.autoOpen ? '1' : '0');
    } else if (group === 'idle') {
      prefs.idleMin = parseInt(value, 10);
      store.set('idleMin', prefs.idleMin);
    }
    renderPanel();
  }

  /* ---------- Acting on a press ---------- */

  function launch(pkg) {
    if (!call('launch', pkg)) { toast('Could not open that app'); }
  }

  function activate(el) {
    var d = el.dataset;
    if (d.opt) { var parts = d.opt.split(':'); setOption(parts[0], parts[1]); return; }
    if (d.input) { if (!call('openInput', d.input)) { toast('Could not switch input'); } return; }
    if (d.setup) {
      if (d.done === '1') { toast('Already on'); }
      else if (d.setup === 'listings') { call('requestTvListings'); }
      else if (!call('openSettings', d.setup)) { toast('This TV has no screen for that. Use the setup guide.'); }
      return;
    }
    if (d.intent !== undefined && el.classList.contains('card')) {
      if (!call('openIntent', d.intent, d.owner || '')) { toast('Could not open that'); }
      return;
    }
    if (d.pkg) { launch(d.pkg); return; }
    var np = state.np || {};
    switch (d.act) {
      case 'prev': media('prev'); break;
      case 'next': media('next'); break;
      case 'toggle': media('toggle'); break;
      case 'back10': media('seek', Math.max(0, (np.position || 0) - 10000)); break;
      case 'fwd10': media('seek', (np.position || 0) + 10000); break;
      case 'player': show('player'); break;
      case 'home': goHome(); break;
      case 'panel': openPanel(); break;
      case 'apps': closePanel(); show('allapps'); break;
      case 'settings': call('openSettings', 'system'); break;
      case 'music':
        var spotify = state.apps.filter(function (a) { return /^spotify/i.test(a.label); })[0];
        if (spotify) { launch(spotify.pkg); } else { show('allapps'); }
        break;
      case 'source-app':
        if (np.active && np.pkg) { launch(np.pkg); } else { activate($('#now [data-act="music"]')); }
        break;
      default:
        if (el.id === 'btn-apps') { show('allapps'); }
        else if (el.id === 'btn-panel') { openPanel(); }
    }
  }

  function hold(el) {
    if (state.view === 'allapps' && el.dataset.pkg) { togglePin(el.dataset.pkg); }
  }

  /* ---------- Keys ---------- */

  var press = null;

  function noteInput() { state.lastInput = Date.now(); }

  function onKeyDown(e) {
    noteInput();
    var key = okKey(e) ? 'Enter' : e.key;
    var known = ['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown', 'Enter', ' ', 'Escape', 'Backspace', 'ContextMenu'];
    if (known.indexOf(key) < 0) { return; }
    e.preventDefault();
    if (state.view === 'ambient') {
      if (!e.repeat) { leaveAmbient(); press = { swallow: true }; }
      return;
    }
    switch (key) {
      case 'ArrowLeft': move('left'); break;
      case 'ArrowRight': move('right'); break;
      case 'ArrowUp': move('up'); break;
      case 'ArrowDown': move('down'); break;
      case 'Enter':
      case ' ':
        if (!e.repeat && !press) {
          var el = document.activeElement;
          if (el && el.classList && el.classList.contains('f')) {
            var p = { el: el, held: false, timer: 0 };
            p.timer = setTimeout(function () { p.held = true; hold(el); }, 650);
            press = p;
          }
        }
        break;
      case 'ContextMenu':
        if (!e.repeat) { if (state.panel) { closePanel(); } else { openPanel(); } }
        break;
      default:
        if (!e.repeat) { back(); }
    }
  }

  /** The remote's OK button, under any of the names different TVs give it. */
  function okKey(e) {
    return e.key === 'Enter' || e.key === 'Select' || e.key === 'Accept' || e.keyCode === 13 || e.keyCode === 23;
  }

  function onKeyUp(e) {
    if (!okKey(e) && e.key !== ' ') { return; }
    e.preventDefault();
    var p = press;
    press = null;
    if (!p || p.swallow) { return; }
    clearTimeout(p.timer);
    if (!p.held && document.activeElement === p.el) { activate(p.el); }
  }

  /* ---------- Small things ---------- */

  var toastTimer = 0;
  function toast(text) {
    var el = $('#toast');
    el.textContent = text;
    el.classList.add('on');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { el.classList.remove('on'); }, 2600);
  }

  function tick() {
    renderClock();
    if (!state.visible) { return; }
    pull();
    renderSleepLabel();

    var np = state.np || {};
    var idleFor = Date.now() - state.lastInput;
    if (np.active && np.playing && prefs.idleMin > 0 && !state.panel &&
        (state.view === 'home' || state.view === 'player') && idleFor > prefs.idleMin * 60000) {
      show('ambient');
    }
    // Once the music has been off for a while, step aside so the TV can sleep as usual.
    if (state.view === 'ambient' && state.stoppedSince && Date.now() - state.stoppedSince > 90000) {
      leaveAmbient();
    }
  }

  /* ---------- Messages from the TV side ---------- */

  window.TVHome = {
    emit: function (name, detail) {
      switch (name) {
        case 'media': pull(); break;
        case 'input': noteInput(); break;
        case 'back': noteInput(); back(); break;
        case 'menu':
          noteInput();
          if (state.view === 'ambient') { leaveAmbient(); }
          else if (state.panel) { closePanel(); }
          else { openPanel(); }
          break;
        case 'pause':
          state.visible = false;
          leaveAmbient();
          updateAwake();
          break;
        case 'resume':
          state.visible = true;
          noteInput();
          loadApps();
          loadFeed();
          pull();
          if (state.panel) { renderPanel(); }
          break;
        case 'permissions':
          state.feedSig = null;
          loadFeed();
          if (state.panel) { renderPanel(); }
          break;
        case 'show':
          noteInput();
          if (detail === 'player') { closePanel(); show('player'); }
          else { goHome(); }
          break;
        default:
          break;
      }
    }
  };

  /* ---------- Start ---------- */

  fit();
  window.addEventListener('resize', fit);
  document.addEventListener('keydown', onKeyDown, true);
  document.addEventListener('keyup', onKeyUp, true);
  stage.addEventListener('click', function (e) {
    var el = e.target.closest ? e.target.closest('.f') : null;
    if (el) { noteInput(); focus(el); activate(el); }
  });

  call('setPref', 'autoOpen', prefs.autoOpen ? '1' : '0');
  $('#home').classList.add('on');
  renderClock();
  loadApps();
  pull();
  loadFeed();
  var first = call('takePendingView');
  if (first === 'player') { show('player'); } else { focus(defaultFocus()); }
  setInterval(tick, 1000);
  setInterval(function () { if (state.visible) { loadFeed(); } }, 5 * 60 * 1000);
})();
