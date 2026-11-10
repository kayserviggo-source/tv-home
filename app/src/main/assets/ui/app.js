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

  var MAX_PINS = 12;
  /* Apps whose shelf is the music rather than what they share. Spotify's shared rows are its
     own suggestions, which have nothing to do with what is playing. */
  var MUSIC_APPS = /^com\.spotify\.|sonos|tidal|deezer|soundcloud|music|\bradio\b/i;
  /* Accent colours. "cover" takes its colour from the album that is playing; the rest are fixed,
     chosen to sit well on the warm dark background. */
  var ACCENTS = {
    cover: { name: 'Cover' },
    brass: { name: 'Brass', colour: '#D3A55A' },
    clay: { name: 'Clay', colour: '#DB8B6E' },
    sage: { name: 'Sage', colour: '#A8BE97' },
    dusk: { name: 'Dusk', colour: '#97AFD3' },
    ivory: { name: 'Ivory', colour: '#E6DCCB' }
  };
  var FALLBACK_ACCENT = '#D3A55A';
  /* Where the weather is for. A fixed place for now; there is no keyboard-friendly way to pick one yet. */
  var PLACE = { name: 'Halmstad', lat: 56.674, lon: 12.857 };
  var prefs = {
    autoOpen: store.get('autoOpen', true),
    idleMin: store.get('idleMin', 2),
    accent: store.get('accent2', 'cover'),
    weather: store.get('weather', true),
    clockDate: store.get('clockDate', true)
  };
  /* Written once a day by a scheduled Claude task that uses the Spotify connector: a greeting,
     a line for each part of the day, and music picked for it. Public, so it holds nothing private. */
  // Off until it is decided where that file may live (it names the owner and their music).
  // Example: 'https://raw.githubusercontent.com/kayserviggo-source/tv-home/landing/landing.json'
  var LANDING_URL = '';
  if (!ACCENTS[prefs.accent]) { prefs.accent = 'cover'; }
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
    content: { watchNext: [], previews: [] },
    contentSig: null,
    shelf: 'landing',       // what the shelf shows: 'landing', an app's package, or 'ask:' + package
    shelfLayers: {},        // each app's shelf, built once and kept
    landing: store.get('landing', null),
    landingSig: '',
    spotify: {},            // TV Home's own Spotify sign-in and the owner's music
    spotifySig: '',
    connectStarted: false,
    lastMoveAt: 0,
    shelfFocus: {},         // the poster last focused in each app's shelf
    dockKey: null,          // the dock tile last focused
    lastDir: '',
    moving: null,           // the dock tile being moved, if any
    lastInput: Date.now(),
    lastCommandAt: 0,
    stoppedSince: 0,
    awake: null,
    sleep: { mode: 'off', at: 0, minutes: 0 },
    lastFocus: {},
    panelReturn: null,
    coverAccent: null,
    pausedAt: 0,
    update: {}
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
  function isShown(el) { return el.offsetParent !== null && !el.disabled && !el.closest('.shelf-layer:not(.on)'); }
  function focusables() { return $$('.f', scopeRoot()).filter(isShown); }

  function defaultFocus() {
    var root = scopeRoot();
    var pick = null;
    if (state.panel) {
      pick = $('.opt[aria-pressed="true"], .swatch[aria-pressed="true"]', root);
    } else if (state.view === 'home') {
      pick = state.shelf === 'landing' ? landingEntry() || dockFocus() : dockFocus();
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
    focusChanged(el);
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

  /* Lists never scroll natively. They are slid with a transform, which the TV's graphics chip
     animates on its own, so movement stays smooth even when the page is busy. */
  var VIEW_WIDTH = 1728; // the 1920 layout minus the 96px margin on each side

  function slide(el, axis, value) {
    value = Math.max(0, Math.round(value));
    if (el['_' + axis] === value) { return; }
    el['_' + axis] = value;
    el.style.transform = axis === 'x' ? 'translate3d(' + (-value) + 'px,0,0)' : 'translate3d(0,' + (-value) + 'px,0)';
  }

  /** An item's position inside one of its ancestors, in layout pixels (unaffected by slides or focus zoom). */
  function offsetWithin(el, ancestor) {
    var x = 0, y = 0;
    while (el && el !== ancestor) { x += el.offsetLeft; y += el.offsetTop; el = el.offsetParent; }
    return { x: x, y: y };
  }

  /** Slides whatever needs sliding so the focused item is comfortably on screen. */
  function reveal(el) {
    var track = el.closest('.track');
    if (track) {
      // A row moves only when the item would reach its edge, and then just far enough that the
      // next item still shows beside it, so there is always something to move on to.
      var x = track._x || 0;
      var left = offsetWithin(el, track).x;
      var right = left + el.offsetWidth;
      var lead = 150;
      if (left - x < 0) { x = left - lead; }
      if (right - x > VIEW_WIDTH - lead) { x = right - VIEW_WIDTH + lead; }
      var last = track.lastElementChild;
      var end = last ? offsetWithin(last, track).x + last.offsetWidth : 0;
      slide(track, 'x', Math.min(x, Math.max(0, end - VIEW_WIDTH)));
      return;
    }
    var box = el.closest('.scroll-inner');
    if (box) {
      var port = box.parentElement.clientHeight;
      var at = offsetWithin(el, box).y;
      var height = el.offsetHeight;
      var margin = 70;
      var y = box._y || 0;
      if (at - margin < y) { y = at - margin; }
      else if (at + height + margin > y + port) { y = at + height + margin - port; }
      slide(box, 'y', Math.min(y, Math.max(0, box.offsetHeight - port)));
    }
  }

  /** Where an item sits in the 1920x1080 layout, as the eye sees it along a row. */
  function layoutRect(el) {
    var p = offsetWithin(el, stage);
    var track = el.closest('.track');
    var left = p.x - (track ? (track._x || 0) : 0);
    return { left: left, right: left + el.offsetWidth, top: p.y, bottom: p.y + el.offsetHeight };
  }

  function move(direction) {
    var items = focusables();
    if (!items.length) { return; }
    var current = document.activeElement;
    if (!current || items.indexOf(current) < 0) { focus(defaultFocus() || items[0]); return; }
    state.lastDir = direction;
    if (!state.panel && state.view === 'home' && homeMove(current, direction)) { return; }

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
        if (c.distance > nearest + 40) { return; }
        var b = c.rect;
        var side = Math.max(0, Math.max(a.left, b.left) - Math.min(a.right, b.right));
        var centre = Math.abs((a.left + a.right) / 2 - (b.left + b.right) / 2);
        var score = side + centre * 0.05 + c.distance * 0.5;
        if (score < bestScore) { bestScore = score; best = c.el; }
      });
    }

    if (best) { focus(best); } else { bump(current, direction); }
  }

  /** Nothing further that way: the item gives a small nudge, so the press is not lost. */
  function bump(el, direction) {
    var name = 'bump-' + direction;
    el.classList.remove('bump-left', 'bump-right', 'bump-up', 'bump-down');
    void el.offsetWidth;
    el.classList.add(name);
    clearTimeout(el._bump);
    el._bump = setTimeout(function () { el.classList.remove(name); }, 360);
  }

  /** Home's own moves between the top bar, the shelf and the dock. Up from an app goes into
      that app's shelf; down from the shelf goes back to the app it belongs to. */
  function homeMove(current, direction) {
    var inDock = !!current.closest('.dock');
    var inShelf = !!current.closest('#shelf');
    var inTop = !!current.closest('.topbar');
    if (inDock && direction === 'up') {
      var owner = shelfOwner(current);
      // An app with nothing to browse: Up goes straight to the landing page.
      if (owner.indexOf('empty:') === 0 && !(isSpotify(owner.slice(6)) && state.spotify.configured && !state.spotify.connected)) {
        owner = 'landing';
      }
      setShelf(owner, true);
      var entry = shelfEntry();
      if (entry) { focus(entry); } else { bump(current, 'up'); }
      return true;
    }
    if (inShelf && direction === 'up' && state.shelf !== 'landing') {
      // Up from an app's shelf: back to the landing page.
      setShelf('landing', true);
      focus(landingEntry() || dockFocus());
      return true;
    }
    if (inShelf && direction === 'down') {
      focus(dockFocus());
      return true;
    }
    if (inTop && direction === 'down') {
      focus(shelfEntry() || dockFocus());
      return true;
    }
    return false;
  }

  /** The dock tile last used, or the first. */
  function dockFocus() {
    return (state.dockKey && $('#dock [data-key="' + state.dockKey + '"]')) || $('#dock .app');
  }

  /** Where focus rests on the landing page: play/pause, or the first pick, or the button. */
  function landingEntry() {
    var layer = $('#shelf-music');
    var toggle = $('[data-act="toggle"]', layer);
    if (toggle && isShown(toggle)) { return toggle; }
    return $$('.f', layer).filter(isShown)[0] || null;
  }

  /* ---------- Views ---------- */

  function show(view) {
    if (state.view === view) { return; }
    rememberFocus();
    stopMoving();
    if (view === 'ambient') { state.returnView = state.view; }
    if (view === 'allapps') { renderAllApps(); }
    state.view = view;
    stage.dataset.view = view;
    $('#home').classList.remove('arrive');
    $$('.view').forEach(function (v) { v.classList.toggle('on', v.id === view); });
    if (view === 'ambient') {
      if (document.activeElement) { document.activeElement.blur(); }
    } else {
      if (view === 'allapps') { state.lastFocus.allapps = null; }
      restoreFocus();
    }
    updateBackdrop();
    updateAwake();
  }

  function leaveAmbient() {
    if (state.view === 'ambient') { show(state.returnView || 'home'); }
  }

  /** The landing page: the music (or the greeting and picks) on the shelf, focus on it. The
      dock keeps its place, so Down goes back to the app last used. */
  function goLanding() {
    closePanel();
    stopMoving();
    if (state.view !== 'home') { show('home'); }
    setShelf('landing', true);
    focus(landingEntry() || dockFocus());
  }

  /** Home, as pressing Home on Home leaves it: the landing page, and the dock back at its start. */
  function goHome() {
    var first = $('#dock .app');
    slide($('#dock'), 'x', 0);
    state.dockKey = first ? first.dataset.key : null;
    goLanding();
  }

  function back() {
    if (state.moving) { stopMoving(); return; }
    if (state.panel) { closePanel(); return; }
    if (state.view === 'ambient') { leaveAmbient(); return; }
    if (state.view === 'connect') { closeConnect(); return; }
    if (state.view === 'home') {
      var active = document.activeElement;
      if (state.shelf !== 'landing' || !active || !active.closest('#shelf-music')) { goLanding(); }
      else if (active) { bump(active, 'up'); }
      return;
    }
    goLanding();
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

    // Music started from a phone (on the TV, or on a Sonos speaker): bring up the player.
    var startedNow = before && np.active && np.playing && !(before.active && before.playing);
    if (startedNow && prefs.autoOpen && state.visible && state.view === 'home' && !state.panel &&
        (np.source === 'sonos' || isMusicApp(np.pkg)) && Date.now() - state.lastCommandAt > 4000) {
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

    var newTrack = !!key && !!state.npKey && key !== state.npKey;
    state.npKey = key;
    if (newTrack) { beginTrackChange(); }
    renderNow();
    if (newTrack) { announceTrack(); }
    updateAwake();
  }

  /* ---------- Changing track: the record slides into its sleeve, the new cover fades in over
     the old one, and the record slides out again with the new label. ---------- */

  var change = { at: 0, timer: 0 };

  function beginTrackChange() {
    change.at = Date.now();
    stage.classList.add('changing');
    clearTimeout(change.timer);
    // If no cover arrives for the new track, settle on a blank sleeve rather than the old cover.
    change.timer = setTimeout(function () {
      if (art.pending === '') { showArt(''); } else { endTrackChange(); }
    }, 2600);
  }

  function endTrackChange() {
    clearTimeout(change.timer);
    stage.classList.remove('changing');
  }

  var art = { url: null, pending: null, token: 0 };

  /** Asks for a cover. Nothing on screen changes until the new picture has fully loaded. */
  function setArt(url) {
    url = url || '';
    if (url === art.url) {
      // Same cover as before (the next track on the same album, say): nothing to swap.
      if (art.pending !== null) {
        art.pending = null;
        art.token++;
        if (stage.classList.contains('changing')) {
          setTimeout(endTrackChange, Math.max(0, 600 - (Date.now() - change.at)));
        }
      }
      return;
    }
    if (url === art.pending) { return; }
    art.pending = url;
    var token = ++art.token;
    if (!url) {
      // A blank cover only replaces one when the track changes (handled by the change timer),
      // or when nothing at all is playing any more.
      if (!stage.classList.contains('changing') && !(state.np && state.np.active)) { showArt(''); }
      return;
    }
    var probe = new Image();
    probe.onload = function () {
      if (token !== art.token) { return; }
      // During a track change, wait until the record is tucked away before swapping.
      var wait = stage.classList.contains('changing') ? Math.max(0, 480 - (Date.now() - change.at)) : 0;
      setTimeout(function () { if (token === art.token) { showArt(url, probe); } }, wait);
    };
    probe.onerror = function () { if (token === art.token) { art.pending = null; } };
    probe.src = url;
  }

  function showArt(url, loaded) {
    art.url = url;
    art.pending = null;
    $$('.sleeve').forEach(function (sleeve) {
      var layers = $$('img[data-art]', sleeve);
      var front = layers.filter(function (img) { return img.classList.contains('top'); })[0] || layers[1];
      var back = front === layers[0] ? layers[1] : layers[0];
      if (!url) {
        layers.forEach(function (img) { img.classList.remove('in'); });
        return;
      }
      back.src = url;
      var reveal = function () {
        front.classList.remove('top');
        back.classList.add('top', 'in');
        // Once the new cover covers the old one, hide the old one underneath.
        setTimeout(function () { if (!front.classList.contains('top')) { front.classList.remove('in'); } }, 750);
      };
      if (back.decode) { back.decode().then(reveal, reveal); } else { reveal(); }
    });
    $$('img[data-art-label]').forEach(function (img) {
      if (url) { img.src = url; img.classList.add('in'); } else { img.classList.remove('in'); }
    });
    $$('img[data-mini-art]').forEach(function (img) { if (url) { img.src = url; } });
    if (url && loaded) { colourFrom(loaded); } else { clearColour(); }
    // Let the record slide back out a moment after the new cover has started to show.
    setTimeout(endTrackChange, url ? 120 : 0);
  }

  /** A new track: let its title and artist rise into place rather than just switch. */
  var swapTimer = 0;
  function announceTrack() {
    var boxes = $$('.music-info, .player-info, .ambient-text');
    boxes.forEach(function (box) { box.classList.remove('swap'); });
    void stage.offsetWidth; // restart the animation if one is still running
    boxes.forEach(function (box) { box.classList.add('swap'); });
    clearTimeout(swapTimer);
    swapTimer = setTimeout(function () { boxes.forEach(function (box) { box.classList.remove('swap'); }); }, 900);
  }

  function setAll(name, text) {
    $$('[data-np="' + name + '"]').forEach(function (el) {
      if (el.textContent !== text) { el.textContent = text; }
      // Long titles drop to a smaller size over two lines; each place says how many letters fit.
      if (el.dataset.long) { el.classList.toggle('long', text.length > +el.dataset.long); }
    });
  }

  function greeting() {
    var hour = new Date().getHours();
    if (hour >= 5 && hour < 12) { return 'Good morning'; }
    if (hour >= 12 && hour < 18) { return 'Good afternoon'; }
    return 'Good evening';
  }

  var lastFraction = 0;

  /** Moves the progress bars. While playing they glide for a second towards where the track
      will be by the next update; a seek or a new track moves them quickly instead. */
  function setBars(fraction, gliding) {
    var jump = !gliding || Math.abs(fraction - lastFraction) > 0.03;
    lastFraction = fraction;
    $$('[data-np="bar"]').forEach(function (el) {
      el.classList.toggle('jump', jump);
      el.style.transform = 'scaleX(' + fraction.toFixed(4) + ')';
    });
  }

  function renderNow() {
    var np = state.np || { access: false, active: false };
    var mode = !np.access ? 'setup' : (np.active ? 'active' : 'idle');
    stage.classList.toggle('np-setup', mode === 'setup');
    stage.classList.toggle('np-idle', mode === 'idle');
    stage.classList.toggle('playing', mode === 'active' && !!np.playing);
    stage.classList.toggle('paused', !(mode === 'active' && np.playing));

    var title, homeTitle, artist = '', status, hint = '', source = '', art = '', position = '', duration = '', fraction = 0;
    if (mode === 'setup') {
      status = 'Setup';
      title = homeTitle = 'Almost there';
      hint = 'One permission is missing before your music can show up here.';
    } else if (mode === 'idle') {
      var part = landingPart();
      status = new Date().toLocaleDateString('en-GB', { weekday: 'long', day: 'numeric', month: 'long' });
      title = 'Nothing playing';
      var name = part.name || ((state.spotify.library || {}).name || '');
      homeTitle = part.greeting || greeting() + (name ? ', ' + name : '');
      hint = part.note || (landingPicks().length && !part.picks.length ? 'Pick up where you left off.'
        : 'Start some music on your phone and it shows up here.');
    } else {
      status = (np.playing ? 'Now playing' : 'Paused') + (np.room ? ' · ' + np.room : '');
      title = homeTitle = np.title || '';
      artist = np.artist || '';
      source = [np.album, np.app].filter(function (x) { return x && x !== np.title; }).join(' · ');
      art = np.art || '';
      position = clock(np.position);
      duration = np.duration > 0 ? clock(np.duration) : '';
      // Aim a second ahead while playing, so the glide ends where the track really is.
      fraction = np.duration > 0 ? Math.min(1, (np.position + (np.playing ? 1000 : 0)) / np.duration) : 0;
    }

    setAll('status', status);
    setAll('title', title);
    setAll('htitle', homeTitle);
    setAll('artist', artist);
    setAll('hint', hint);
    setAll('source', source);
    setAll('pos', position);
    setAll('dur', duration);
    var appName = mode === 'active' && np.app && np.app !== 'Sonos' && np.app !== 'Radio' ? np.app : 'Spotify';
    setAll('open-app', 'Open ' + appName);
    setAll('room', mode === 'active' && np.room ? 'Playing on ' + np.room : '');
    $$('[data-room]').forEach(function (el) { el.hidden = !(mode === 'active' && np.room); });
    setBars(fraction, mode === 'active' && !!np.playing);
    setArt(art);
    var lossless = mode === 'active' && !!np.lossless;
    $$('[data-lossless]').forEach(function (el) { if (el.hidden === lossless) { el.hidden = !lossless; } });
    updateMini();
    if (partName() !== picksPart) { renderPicks(); }
    if ((mode === 'active' && np.playing ? np.pkg || '' : '') !== soundingPkg) { markSounding(); }

    // If the focused control just disappeared (music stopped, say), move to a sensible one.
    var active = document.activeElement;
    if (state.view !== 'ambient' && (!active || active === document.body || !isShown(active))) {
      var fallback = defaultFocus();
      if (fallback) { focus(fallback); }
    }
  }

  /* ---------- Backdrop: the colours of the cover, or of the poster in focus ---------- */

  // What each part of the interface wants behind it: { key, img } or null.
  var wash = { music: null, poster: null };
  var backdrop = { layer: 0, key: null };

  /** Draws a picture into the hidden layer, blurred, and fades it in over the shown one. The
      layers are canvases, so a poster from another site can be shown even though its pixels
      cannot be read. */
  function paintBackdrop(entry) {
    var key = entry ? entry.key : '';
    if (key === backdrop.key) { return; }
    backdrop.key = key;
    var layers = $$('#backdrop canvas');
    var next = layers[1 - backdrop.layer];
    if (entry) {
      try {
        var ctx = next.getContext('2d');
        var w = next.width, h = next.height;
        var iw = entry.img.naturalWidth || entry.img.width || 1;
        var ih = entry.img.naturalHeight || entry.img.height || 1;
        var s = Math.max((w + 10) / iw, (h + 10) / ih); // fill the frame, with spare for the blur
        ctx.clearRect(0, 0, w, h);
        try { ctx.filter = 'blur(3px) saturate(1.3)'; } catch (ignored) { /* older WebView: the stretch alone softens it */ }
        ctx.drawImage(entry.img, (w - iw * s) / 2, (h - ih * s) / 2, iw * s, ih * s);
        try { ctx.filter = 'none'; } catch (ignored) { /* as above */ }
      } catch (e) {
        entry = null;
      }
    }
    backdrop.layer = 1 - backdrop.layer;
    next.classList.toggle('on', !!entry);
    layers[1 - backdrop.layer].classList.remove('on');
  }

  /** Home shows the poster's colours while an app's shelf is up; everywhere else, the cover's. */
  function updateBackdrop() {
    var posterShelf = state.view === 'home' && state.shelf !== 'landing' && state.shelf.indexOf('ask:') !== 0;
    if (!posterShelf) { paintBackdrop(wash.music); return; }
    if (wash.poster && wash.poster.key === 'poster:' + posterWash.url) { paintBackdrop(wash.poster); }
    else if (!posterWash.url) { paintBackdrop(wash.music); } // no picture to take colour from
    // Otherwise the poster is still loading: keep what is showing rather than dip to nothing.
  }

  var posterWash = { url: null, timer: 0 };
  /** Takes the backdrop from a poster, once it has loaded. Quick moves along a row only change
      it when they stop. */
  function washFromPoster(url) {
    if (url === posterWash.url) { return; }
    posterWash.url = url;
    clearTimeout(posterWash.timer);
    if (!url) { wash.poster = null; updateBackdrop(); return; }
    posterWash.timer = setTimeout(function () {
      var img = new Image();
      img.onload = function () {
        if (posterWash.url !== url) { return; }
        // Decode off the main thread first, so drawing it never stalls a key press.
        var ready = function () {
          if (posterWash.url !== url) { return; }
          wash.poster = { key: 'poster:' + url, img: img };
          updateBackdrop();
        };
        if (img.decode) { img.decode().then(ready, ready); } else { ready(); }
      };
      img.onerror = function () { if (posterWash.url === url) { wash.poster = null; updateBackdrop(); } };
      img.src = url;
    }, 220);
  }

  function clearColour() {
    stage.style.removeProperty('--label');
    stage.style.removeProperty('--glow');
    wash.music = null;
    updateBackdrop();
  }

  /** The cover's most characteristic colour, made bright enough to read on the dark background.
      Picks the strongest hue by how much of the cover carries it and how saturated it is, so a
      small red sticker on a grey cover still wins over the grey. Null for black-and-white covers. */
  function vividFrom(img) {
    var size = 24;
    var canvas = document.createElement('canvas');
    canvas.width = canvas.height = size;
    var ctx = canvas.getContext('2d');
    ctx.drawImage(img, 0, 0, size, size);
    var data = ctx.getImageData(0, 0, size, size).data;
    var weight = [], hueX = [], hueY = [], sat = [];
    for (var k = 0; k < 12; k++) { weight[k] = 0; hueX[k] = 0; hueY[k] = 0; sat[k] = 0; }
    var total = 0;
    for (var i = 0; i < data.length; i += 4) {
      var r = data[i] / 255, g = data[i + 1] / 255, b = data[i + 2] / 255;
      var max = Math.max(r, g, b), min = Math.min(r, g, b), d = max - min, l = (max + min) / 2;
      if (d < 0.08 || l < 0.12 || l > 0.92) { continue; }
      var s = d / (1 - Math.abs(2 * l - 1));
      var h = max === r ? ((g - b) / d) % 6 : max === g ? (b - r) / d + 2 : (r - g) / d + 4;
      h = h * 60; if (h < 0) { h += 360; }
      var w = s * s * (1 - Math.abs(l - 0.5));
      var bucket = Math.floor(h / 30) % 12;
      weight[bucket] += w;
      hueX[bucket] += Math.cos(h * Math.PI / 180) * w;
      hueY[bucket] += Math.sin(h * Math.PI / 180) * w;
      sat[bucket] += s * w;
      total += w;
    }
    var best = 0;
    for (k = 1; k < 12; k++) { if (weight[k] > weight[best]) { best = k; } }
    if (total < 4 || weight[best] < 2) { return null; }
    var hue = Math.atan2(hueY[best], hueX[best]) * 180 / Math.PI;
    if (hue < 0) { hue += 360; }
    var saturation = Math.max(0.42, Math.min(0.72, sat[best] / weight[best]));
    // Blues and purples need more lightness than yellows to look equally bright.
    var lightness = hue > 190 && hue < 300 ? 0.72 : 0.66;
    return 'hsl(' + Math.round(hue) + ',' + Math.round(saturation * 100) + '%,' + Math.round(lightness * 100) + '%)';
  }

  /** Takes colour from the cover: the wash behind everything, the record's label colour, and
      the accent. */
  function colourFrom(img) {
    wash.music = { key: 'cover:' + img.src, img: img };
    updateBackdrop();
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
      var hsl = h + ',' + Math.round(s * 100) + '%,' + Math.round(l * 100) + '%';
      stage.style.setProperty('--label', 'hsl(' + hsl + ')');
      stage.style.setProperty('--glow', 'hsla(' + hsl + ',0.2)');
    } catch (e) {
      stage.style.removeProperty('--label');
      stage.style.removeProperty('--glow');
    }
    try { state.coverAccent = vividFrom(img); } catch (e) { state.coverAccent = null; }
    applyLook();
  }

  /* ---------- Accent colour ---------- */

  function accentNow() {
    if (prefs.accent === 'cover') { return state.coverAccent || FALLBACK_ACCENT; }
    return ACCENTS[prefs.accent].colour;
  }

  function applyLook() {
    stage.style.setProperty('--accent', accentNow());
  }

  function applyClockDate() {
    $$('.ambient-date').forEach(function (el) { el.classList.toggle('no-day', !prefs.clockDate); });
  }

  /* ---------- Weather ---------- */

  var weather = { at: 0, data: null, asked: 0 };
  var WEATHER_ICONS = {
    sun: '<circle cx="12" cy="12" r="4"/><path d="M12 3v2M12 19v2M3 12h2M19 12h2M5.6 5.6l1.4 1.4M17 17l1.4 1.4M5.6 18.4L7 17M17 7l1.4-1.4"/>',
    moon: '<path d="M19.5 14.5A8 8 0 0 1 9.5 4.5a8 8 0 1 0 10 10z"/>',
    partly: '<circle cx="8" cy="9" r="3"/><path d="M8 3.5v1M3 9h1M4.5 5.5l.7.7M11.5 5.5l-.7.7"/><path d="M9 19h8a3.5 3.5 0 0 0 .4-7 4.5 4.5 0 0 0-8.6 1.3A3 3 0 0 0 9 19z"/>',
    cloud: '<path d="M7 18h10a4 4 0 0 0 .5-8 5.5 5.5 0 0 0-10.6 1.5A3.3 3.3 0 0 0 7 18z"/>',
    fog: '<path d="M7 13h10a3.5 3.5 0 0 0 .4-7 5 5 0 0 0-9.6 1.4A3 3 0 0 0 7 13z"/><path d="M5 17h14M7 20.5h10"/>',
    rain: '<path d="M7 14h10a3.5 3.5 0 0 0 .4-7 5 5 0 0 0-9.6 1.4A3 3 0 0 0 7 14z"/><path d="M8.5 17.5l-1 2.5M12.5 17.5l-1 2.5M16.5 17.5l-1 2.5"/>',
    snow: '<path d="M7 14h10a3.5 3.5 0 0 0 .4-7 5 5 0 0 0-9.6 1.4A3 3 0 0 0 7 14z"/><path d="M8 18h.01M12 18h.01M16 18h.01M10 21h.01M14 21h.01"/>',
    storm: '<path d="M7 14h10a3.5 3.5 0 0 0 .4-7 5 5 0 0 0-9.6 1.4A3 3 0 0 0 7 14z"/><path d="M12.5 15.5l-2 3h3l-2 3"/>'
  };

  /** Maps the forecast service's weather code (the WMO scale) to one of the icons above. */
  function weatherIcon(code, day) {
    if (code === 0) { return day ? 'sun' : 'moon'; }
    if (code <= 2) { return day ? 'partly' : 'moon'; }
    if (code === 3) { return 'cloud'; }
    if (code === 45 || code === 48) { return 'fog'; }
    if ((code >= 71 && code <= 77) || code === 85 || code === 86) { return 'snow'; }
    if (code >= 95) { return 'storm'; }
    return 'rain';
  }

  function renderWeather() {
    var fresh = prefs.weather && weather.data && Date.now() - weather.at < 2 * 60 * 60 * 1000;
    $$('[data-weather]').forEach(function (el) {
      el.hidden = !fresh;
      if (!fresh) { return; }
      var html = '<svg viewBox="0 0 24 24" aria-hidden="true">' + WEATHER_ICONS[weatherIcon(weather.data.code, weather.data.day)] +
        '</svg><span>' + Math.round(weather.data.temp) + '° ' + esc(PLACE.name) + '</span>';
      if (el._html !== html) { el._html = html; el.innerHTML = html; }
    });
  }

  function loadWeather() {
    if (!prefs.weather) { renderWeather(); return; }
    if (Date.now() - weather.asked < 20 * 60 * 1000) { renderWeather(); return; }
    weather.asked = Date.now();
    if (window.MockWeather) { weather.data = window.MockWeather; weather.at = Date.now(); renderWeather(); return; }
    if (!window.fetch) { return; }
    fetch('https://api.open-meteo.com/v1/forecast?latitude=' + PLACE.lat + '&longitude=' + PLACE.lon +
        '&current=temperature_2m,weather_code,is_day')
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (d) {
        var c = d && d.current;
        if (c && typeof c.temperature_2m === 'number') {
          weather.data = { temp: c.temperature_2m, code: c.weather_code, day: !!c.is_day };
          weather.at = Date.now();
        }
        renderWeather();
      })
      .catch(function () { weather.asked = Date.now() - 15 * 60 * 1000; renderWeather(); }); // try again in five minutes
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
      if (pins.length < 6 && app.tv && pins.indexOf(app.pkg) < 0) { pins.push(app.pkg); }
    });
    return pins;
  }

  function loadApps() {
    state.apps = callJson('getApps', []);
    var saved = store.get('pins', null);
    var pins = saved ? saved.filter(function (pkg) { return !!appByPkg(pkg); }) : defaultPins();
    var changed = JSON.stringify(pins) !== JSON.stringify(state.pins);
    state.pins = pins;
    if (changed || !$('#dock').children.length) { renderDock(); }
  }

  var ICONS = {
    apps: '<svg viewBox="0 0 24 24" aria-hidden="true"><rect x="4" y="4" width="6.5" height="6.5" rx="1.5"/><rect x="13.5" y="4" width="6.5" height="6.5" rx="1.5"/>' +
      '<rect x="4" y="13.5" width="6.5" height="6.5" rx="1.5"/><rect x="13.5" y="13.5" width="6.5" height="6.5" rx="1.5"/></svg>',
    panel: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 7h10M18 7h2M4 17h2M10 17h10"/><circle cx="16" cy="7" r="2"/><circle cx="8" cy="17" r="2"/></svg>'
  };

  /** One app: its own banner when it has one, otherwise its icon and name on a calm tint. */
  function appTile(app, index) {
    var tile = document.createElement('button');
    tile.className = 'app f' + (app.banner ? ' has-banner' : '');
    tile.dataset.pkg = app.pkg;
    tile.dataset.key = 'app:' + app.pkg;
    tile.style.setProperty('--i', index || 0);
    tile.style.setProperty('--tint', tintFor(app.label));
    tile.setAttribute('aria-label', app.label);
    var html = '<span class="face">';
    if (app.banner) { html += '<img class="banner" alt="" src="' + esc(imageUrl('banner', app.pkg)) + '">'; }
    html += '<img class="icon" alt="" src="' + esc(imageUrl('icon', app.pkg)) + '"><span class="name">' + esc(app.label) + '</span>' +
      '<i class="glint"></i></span><span class="label">' + esc(app.label) + '<span class="tip">Hold OK to move</span></span><span class="badge">On Home</span>' +
      '<span class="eqbadge" aria-hidden="true"><span class="eq"><i></i><i></i><i></i></span></span>';
    tile.innerHTML = html;
    var banner = $('img.banner', tile);
    if (banner) {
      banner.onerror = function () { tile.classList.remove('has-banner'); banner.remove(); };
      banner.onload = function () { shadeFrom(banner, tile); };
    }
    var icon = $('img.icon', tile);
    icon.onerror = function () { icon.remove(); };
    return tile;
  }

  /** The soft coloured light under a focused tile, taken from its banner. */
  function shadeFrom(img, tile) {
    try {
      var canvas = document.createElement('canvas');
      canvas.width = canvas.height = 6;
      var ctx = canvas.getContext('2d');
      ctx.drawImage(img, 0, 0, 6, 6);
      var data = ctx.getImageData(0, 0, 6, 6).data;
      var r = 0, g = 0, b = 0, n = data.length / 4;
      for (var i = 0; i < data.length; i += 4) { r += data[i]; g += data[i + 1]; b += data[i + 2]; }
      tile.style.setProperty('--shade', 'rgba(' + Math.round(r / n) + ',' + Math.round(g / n) + ',' + Math.round(b / n) + ',0.4)');
    } catch (e) { /* a picture from elsewhere: no coloured light, the shadow alone */ }
  }

  function utilTile(act, label, index) {
    var tile = document.createElement('button');
    tile.className = 'app util f';
    tile.dataset.act = act;
    tile.dataset.key = 'util:' + act;
    tile.style.setProperty('--i', index);
    tile.setAttribute('aria-label', label);
    tile.innerHTML = '<span class="face">' + ICONS[act] + '<i class="glint"></i></span><span class="label">' + esc(label) + '</span>';
    return tile;
  }

  /** The dock: the apps on Home, then All apps and Control Centre. */
  function renderDock() {
    var dock = $('#dock');
    var focusedKey = dock.contains(document.activeElement) ? document.activeElement.dataset.key : null;
    dock.innerHTML = '';
    var n = 0;
    state.pins.forEach(function (pkg) {
      var app = appByPkg(pkg);
      if (app) { dock.appendChild(appTile(app, n++)); }
    });
    dock.appendChild(utilTile('apps', 'All apps', n++));
    dock.appendChild(utilTile('panel', 'Control Centre', n++));
    markSounding();
    if (focusedKey && state.view === 'home' && !state.panel) {
      focus($('[data-key="' + focusedKey + '"]', dock) || dock.firstElementChild);
    }
  }

  /** Marks the tile of the app the music is coming from. */
  var soundingPkg = null;
  function markSounding() {
    var np = state.np || {};
    var pkg = np.active && np.playing ? np.pkg || '' : '';
    soundingPkg = pkg;
    $$('#dock .app').forEach(function (tile) { tile.classList.toggle('sounding', !!pkg && tile.dataset.pkg === pkg); });
  }

  function renderAllApps() {
    var grid = $('#app-grid');
    grid.innerHTML = '';
    state.apps.forEach(function (app) {
      var cell = document.createElement('div');
      cell.className = 'app-cell';
      var tile = appTile(app, 0);
      tile.classList.toggle('pinned', state.pins.indexOf(app.pkg) >= 0);
      var caption = document.createElement('div');
      caption.className = 'caption';
      caption.textContent = app.label;
      cell.appendChild(tile);
      cell.appendChild(caption);
      grid.appendChild(cell);
    });
    slide($('#grid-inner'), 'y', 0);
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
    renderDock();
    $$('#app-grid .app').forEach(function (tile) {
      tile.classList.toggle('pinned', state.pins.indexOf(tile.dataset.pkg) >= 0);
    });
  }

  /* ---------- Moving an app along the dock (hold OK on it) ---------- */

  function startMoving(tile) {
    store.set('movedOnce', true);
    tile.classList.remove('tipping');
    state.moving = tile;
    tile.classList.add('moving');
    $('#home .dock').classList.add('moving');
  }

  function stopMoving() {
    if (!state.moving) { return; }
    state.moving.classList.remove('moving');
    $('#home .dock').classList.remove('moving');
    state.moving = null;
    store.set('pins', state.pins);
  }

  /** Swaps the moving app with its neighbour. The neighbour glides across; the moving app
      keeps jiggling in its new place. */
  function shiftMoving(step) {
    var tile = state.moving;
    var pkg = tile.dataset.pkg;
    var from = state.pins.indexOf(pkg);
    var to = from + step;
    if (to < 0 || to >= state.pins.length) { return; }
    var other = step < 0 ? tile.previousElementSibling : tile.nextElementSibling;
    if (!other) { return; }
    state.pins.splice(from, 1);
    state.pins.splice(to, 0, pkg);
    var before = other.offsetLeft;
    if (step < 0) { tile.parentNode.insertBefore(tile, other); } else { tile.parentNode.insertBefore(other, tile); }
    other.style.transition = 'none';
    other.style.transform = 'translate3d(' + (before - other.offsetLeft) + 'px,0,0)';
    void other.offsetWidth;
    other.style.transition = '';
    other.style.transform = '';
    $$('#dock .app').forEach(function (el, i) { el.style.setProperty('--i', i); });
    reveal(tile);
  }

  function removeMoving() {
    var tile = state.moving;
    var next = tile.nextElementSibling && tile.nextElementSibling.dataset.pkg ? tile.nextElementSibling : tile.previousElementSibling;
    var nextKey = next ? next.dataset.key : null;
    stopMoving();
    togglePin(tile.dataset.pkg);
    focus((nextKey && $('#dock [data-key="' + nextKey + '"]')) || $('#dock .app'));
  }

  /* ---------- The shelf above the dock ---------- */

  function minutesLeft(ms) {
    if (!(ms > 0)) { return ''; }
    var minutes = Math.round(ms / 60000);
    if (minutes < 1) { return ''; }
    if (minutes < 60) { return minutes + ' min left'; }
    return Math.floor(minutes / 60) + ' h ' + ('0' + (minutes % 60)).slice(-2) + ' min left';
  }

  /** A calm two-tone background for a poster that has no picture, the same for the same title. */
  function tintFor(text) {
    var hash = 0;
    String(text || '').split('').forEach(function (c) { hash = (hash * 31 + c.charCodeAt(0)) | 0; });
    var hue = Math.abs(hash) % 360;
    return 'linear-gradient(140deg, hsl(' + hue + ',26%,30%), hsl(' + ((hue + 28) % 360) + ',22%,17%))';
  }

  function musicLike(pkg) { return MUSIC_APPS.test(pkg || ''); }
  function isSpotify(pkg) { return /^com\.spotify\./.test(pkg || ''); }

  /** A picture from elsewhere, shrunk on the TV side to the size it is shown at. */
  function posterSrc(url, width) {
    if (!url) { return ''; }
    if (window.MockImages || /^(data:|poster\?|https:\/\/tvhome\.local\/)/.test(url)) { return url; }
    return 'poster?w=' + width + '&u=' + encodeURIComponent(url);
  }

  /* ---------- Picks for the time of day (written by Claude, see LANDING_URL) ---------- */

  function partName() {
    var hour = new Date().getHours();
    if (hour >= 5 && hour < 12) { return 'morning'; }
    if (hour >= 12 && hour < 17) { return 'afternoon'; }
    if (hour >= 17 && hour < 23) { return 'evening'; }
    return 'night';
  }

  function today() {
    var d = new Date();
    return d.getFullYear() + '-' + ('0' + (d.getMonth() + 1)).slice(-2) + '-' + ('0' + d.getDate()).slice(-2);
  }

  /** This part of the day's greeting, line and picks. The words only count on the day they were
      written for; the picks keep for a few days. */
  function landingPart() {
    var d = state.landing;
    var out = { greeting: '', note: '', picks: [], name: d && d.name ? d.name : '' };
    if (!d || !d.parts) { return out; }
    var part = d.parts[partName()] || {};
    var age = (Date.parse(today()) - Date.parse(d.date || '')) / 86400000;
    if (age === 0) { out.greeting = part.greeting || ''; out.note = part.note || ''; }
    if (age >= 0 && age <= 3) { out.picks = (part.picks || []).filter(function (p) { return p && p.title && p.link; }); }
    return out;
  }

  /** What the landing page offers with nothing playing: Claude's picks for now, or else what
      was played recently on Spotify. */
  function landingPicks() {
    var picks = landingPart().picks;
    if (picks.length) { return picks; }
    return ((state.spotify.library || {}).jump || []).filter(function (p) { return p && p.title && p.link; });
  }

  /** Picks from the other parts of the day, for the Spotify shelf. */
  function otherPicks() {
    var d = state.landing;
    if (!d || !d.parts) { return []; }
    var seen = {};
    landingPart().picks.forEach(function (p) { seen[p.link] = true; });
    var more = [];
    ['morning', 'afternoon', 'evening', 'night'].forEach(function (name) {
      if (name === partName()) { return; }
      ((d.parts[name] || {}).picks || []).forEach(function (p) {
        if (p && p.title && p.link && !seen[p.link]) { seen[p.link] = true; more.push(p); }
      });
    });
    return more.slice(0, 12);
  }

  function pickItem(p) {
    return { id: p.link, title: p.title, sub: p.subtitle || '', poster: posterSrc(p.image, 320), link: p.link };
  }

  var picksPart = '';
  /** The picks on the landing page, when nothing plays. */
  function renderPicks() {
    picksPart = partName();
    var box = $('#picks');
    var picks = landingPicks().slice(0, 5);
    var sig = JSON.stringify(picks);
    if (box._sig === sig) { return; }
    box._sig = sig;
    var had = box.contains(document.activeElement) ? document.activeElement.dataset.link : null;
    box.innerHTML = picks.map(function (p) {
      var img = p.image ? '<img alt="" decoding="async" src="' + esc(posterSrc(p.image, 240)) + '">'
        : '<div class="fallback" style="background:' + tintFor(p.title) + '">' + esc(p.title) + '</div>';
      return '<button class="pick f" data-link="' + esc(p.link) + '" aria-label="' + esc(p.title) + '"><span class="art">' + img +
        '<i class="glint"></i></span><span class="cap">' + esc(p.title) + (p.subtitle ? '<span>' + esc(p.subtitle) + '</span>' : '') + '</span></button>';
    }).join('');
    $$('img', box).forEach(function (img) { img.onerror = function () { img.remove(); }; });
    box.classList.toggle('has', picks.length > 0);
    $('#shelf-music').classList.toggle('has-picks', picks.length > 0);
    if (had) { focus($('[data-link="' + had + '"]', box) || landingEntry() || dockFocus()); }
  }

  var landingAsked = 0;
  function loadLanding(force) {
    if (window.MockLanding !== undefined) { setLanding(window.MockLanding); return; }
    if (!LANDING_URL || !window.fetch || (!force && Date.now() - landingAsked < 30 * 60 * 1000)) { return; }
    landingAsked = Date.now();
    fetch(LANDING_URL + '?t=' + Math.floor(Date.now() / 600000), { cache: 'no-store' })
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (d) { if (d && d.parts) { store.set('landing', d); setLanding(d); } })
      .catch(function () { landingAsked = Date.now() - 25 * 60 * 1000; }); // try again in five minutes
  }

  function setLanding(d) {
    var sig = JSON.stringify(d || null);
    if (sig === state.landingSig) { return; }
    state.landingSig = sig;
    state.landing = d;
    renderPicks();
    renderNow();
    state.contentSig = null;
    loadContent();
  }

  /* ---------- TV Home's own Spotify connection ---------- */

  function loadSpotify() {
    var sp = callJson('getSpotify', null);
    if (!sp) { return; }
    var before = state.spotify;
    state.spotify = sp;
    var sig = JSON.stringify(sp.library || {});
    if (sig !== state.spotifySig || before.connected !== sp.connected || before.configured !== sp.configured) {
      state.spotifySig = sig;
      renderPicks();
      renderNow();
      state.contentSig = null;
      loadContent();
      if (state.panel) { renderPanel(); }
    }
    if (state.view === 'connect') { renderConnect(); }
  }

  function openConnect() {
    closePanel();
    state.connectStarted = true;
    $('#qr').innerHTML = '';
    $('#qr')._url = '';
    call('spotifyConnect');
    show('connect');
    loadSpotify();
    renderConnect();
  }

  function closeConnect() {
    if (state.spotify.state === 'waiting') { call('spotifyCancel'); }
    state.connectStarted = false;
    goLanding();
  }

  /** The connect screen follows the sign-in: QR code while waiting, then done or what went wrong. */
  function renderConnect() {
    var sp = state.spotify;
    var status = $('#connect-status');
    var retry = $('#connect [data-act="connect-retry"]');
    var qr = $('#qr');
    if (sp.state === 'connected' && state.connectStarted) {
      state.connectStarted = false;
      toast('Spotify is connected');
      goLanding();
      return;
    }
    if (sp.state === 'waiting' && sp.url) {
      if (qr._url !== sp.url && window.qrcode) {
        var code = window.qrcode(0, 'L');
        code.addData(sp.url);
        code.make();
        qr.innerHTML = code.createSvgTag({ cellSize: 4, margin: 0, scalable: true });
        qr._url = sp.url;
      }
      status.textContent = 'Waiting for your phone…';
      retry.hidden = true;
      return;
    }
    if (!sp.configured) {
      status.textContent = 'Spotify is not set up for TV Home yet.';
    } else {
      status.textContent = sp.message || 'Not connected.';
    }
    qr.innerHTML = '';
    qr._url = '';
    retry.hidden = !sp.configured;
    if (!isShown(document.activeElement)) { focus(defaultFocus()); }
  }

  /* ---------- What each app has lined up ---------- */

  /** An app's shelf: Continue watching first, then the app's own rows. Spotify's starts with
      the picks for this time of day. */
  function contentFor(pkg) {
    var groups = [];
    var square = musicLike(pkg);
    if (isSpotify(pkg)) {
      // The owner's own music, read by TV Home from Spotify. Spotify's rows for the TV's home
      // screen are left out: they are general suggestions, not the owner's music.
      var lib = state.spotify.library || {};
      var picks = landingPart().picks;
      if (picks.length) {
        groups.push({ title: partName() === 'night' ? 'For tonight' : 'For this ' + partName(), items: picks.map(pickItem), square: true });
      }
      if (lib.jump && lib.jump.length) { groups.push({ title: 'Jump back in', items: lib.jump.map(pickItem), square: true }); }
      if (lib.playlists && lib.playlists.length) { groups.push({ title: 'Your playlists', items: lib.playlists.map(pickItem), square: true }); }
      if (lib.artists && lib.artists.length) { groups.push({ title: 'Your top artists', items: lib.artists.map(pickItem), square: true, round: true }); }
      var more = otherPicks();
      if (more.length) { groups.push({ title: 'More for today', items: more.map(pickItem), square: true }); }
      return groups;
    }
    var next = state.content.watchNext.filter(function (item) { return item.pkg === pkg; });
    if (next.length) { groups.push({ title: 'Continue watching', items: next.slice(0, 12), square: square }); }
    state.content.previews.filter(function (channel) { return channel.pkg === pkg && channel.items && channel.items.length; })
      .slice(0, 3).forEach(function (channel) {
        groups.push({ title: channel.name || 'From ' + (channel.app || 'the app'), items: channel.items.slice(0, 12), square: square });
      });
    return groups;
  }

  /** Which shelf an item in focus asks for. Apps with something to show get their own; the
      landing page is the shelf for everything else. */
  function shelfOwner(el) {
    if (!el || !el.closest('.dock')) { return state.shelf; }
    var pkg = el.dataset.pkg;
    if (!pkg) { return 'landing'; } // All apps, Control Centre
    if (!state.status.tvListings && !isSpotify(pkg) && !musicLike(pkg)) {
      var app = appByPkg(pkg);
      if (app && app.tv) { return 'ask:' + pkg; }
    }
    // Every app gets its own shelf; one with nothing to show gets its card.
    return contentFor(pkg).length ? pkg : 'empty:' + pkg;
  }

  var shelfTimer = 0;
  var tipTimer = 0;
  /** Called whenever focus moves. Moving along the dock changes the shelf once focus rests. */
  function focusChanged(el) {
    if (state.view !== 'home' || state.panel || !el.closest('#home')) { return; }
    clearTimeout(tipTimer);
    $$('#dock .tipping').forEach(function (tile) { tile.classList.remove('tipping'); });
    if (el.closest('.dock')) {
      state.dockKey = el.dataset.key;
      var owner = shelfOwner(el);
      clearTimeout(shelfTimer);
      if (owner !== state.shelf) { shelfTimer = setTimeout(function () { setShelf(owner); }, 300); }
      // Until an app has been moved once, say how after a moment on a tile.
      if (el.dataset.pkg && !store.get('movedOnce', false)) {
        tipTimer = setTimeout(function () { if (document.activeElement === el) { el.classList.add('tipping'); } }, 2200);
      }
    } else if (el.classList.contains('poster')) {
      state.shelfFocus[state.shelf] = el.dataset.key;
      washFromPoster(el.dataset.poster || '');
    }
  }

  /** An app's shelf layer: built the first time, then kept, so moving along the dock again
      only switches which one shows. */
  function layerFor(owner) {
    var layer = state.shelfLayers[owner];
    if (!layer || !layer.parentNode) {
      layer = document.createElement('div');
      layer.className = 'shelf-layer shelf-app';
      layer.dataset.zone = 'posters';
      $('#shelf').appendChild(layer);
      buildShelf(layer, owner);
      state.shelfLayers[owner] = layer;
    }
    return layer;
  }

  /** Forgets built shelves (their content changed), except the one on screen. */
  function dropShelves(keep) {
    Object.keys(state.shelfLayers).forEach(function (owner) {
      if (owner === keep) { return; }
      var layer = state.shelfLayers[owner];
      if (layer && layer.parentNode) { layer.parentNode.removeChild(layer); }
      delete state.shelfLayers[owner];
    });
  }

  /** Shows a shelf. A new app's shelf slides in from the side the remote moved towards. */
  function setShelf(owner, now) {
    clearTimeout(shelfTimer);
    if (owner === state.shelf) { return; }
    state.shelf = owner;
    var landing = $('#shelf-music');
    var shown = $$('#shelf .shelf-app.on');
    if (owner === 'landing') {
      shown.forEach(function (layer) { layer.classList.remove('on'); });
      landing.classList.add('on');
      washFromPoster(null);
    } else {
      var layer = layerFor(owner);
      layer.classList.remove('enter-right', 'enter-left', 'enter-up');
      void layer.offsetWidth;
      layer.classList.add(now ? 'enter-up' : state.lastDir === 'left' ? 'enter-left' : 'enter-right');
      layer.classList.add('on');
      shown.forEach(function (other) { if (other !== layer) { other.classList.remove('on'); } });
      landing.classList.remove('on');
      var first = $('[data-poster]', layer);
      washFromPoster(first ? first.dataset.poster || '' : null);
    }
    updateBackdrop();
    updateMini();
  }

  function buildShelf(layer, owner) {
    if (owner.indexOf('empty:') === 0) {
      layer.innerHTML = appCardHtml(owner.slice(6));
      var banner = $('img', layer);
      if (banner) { banner.onerror = function () { banner.remove(); }; }
      return;
    }
    if (owner.indexOf('ask:') === 0) {
      var app = appByPkg(owner.slice(4));
      layer.innerHTML = '<div class="shelf-ask"><h2>See what’s next in ' + esc(app ? app.label : 'your apps') + '</h2>' +
        '<p>Home can show what Netflix, SVT Play and your other apps have lined up for you. It needs your permission to read what they share.</p>' +
        '<button class="pill f glass" data-setup="listings" data-done="0" data-key="ask">Allow</button></div>';
      return;
    }
    var html = '<div class="track">';
    contentFor(owner).forEach(function (group, g) {
      html += '<div class="pgroup"><h3>' + esc(group.title) + '</h3><div class="items">';
      group.items.forEach(function (item, i) {
        html += posterHtml(item, owner + ':' + g + ':' + (item.id != null ? item.id : i), group.square, group.round);
      });
      html += '</div></div>';
    });
    layer.innerHTML = html + '</div>';
    $$('.poster img', layer).forEach(function (img) {
      img.onerror = function () {
        var card = img.closest('.poster');
        var fallback = document.createElement('div');
        fallback.className = 'fallback';
        fallback.style.background = tintFor(card.getAttribute('aria-label'));
        fallback.textContent = card.getAttribute('aria-label');
        img.replaceWith(fallback);
        card.dataset.poster = '';
      };
    });
  }

  /** The shelf of an app with nothing to show: its banner, a line, and a way in. */
  function appCardHtml(pkg) {
    var app = appByPkg(pkg) || { label: 'This app', pkg: pkg };
    var banner = app.banner ? imageUrl('banner', pkg) : '';
    var text, buttons = '';
    if (isSpotify(pkg)) {
      var sp = state.spotify;
      if (sp.connected) {
        text = 'Your music is on its way from Spotify.';
      } else if (sp.configured) {
        text = 'Connect your Spotify to see what you’ve been playing and your own playlists here.';
        buttons = '<button class="pill f glass" data-act="spotify-connect">Connect Spotify</button>';
      } else {
        text = 'Your own music shows up here once TV Home is connected to Spotify.';
      }
    } else {
      text = 'Nothing to continue in ' + app.label + ' yet. When it has something for you, it shows up here.';
    }
    buttons += '<button class="pill f glass" data-open="' + esc(pkg) + '">Open ' + esc(app.label) + '</button>';
    return '<div class="app-card"><div class="app-card-art" style="--tint:' + tintFor(app.label) + '" data-poster="' + esc(banner) + '">' +
      (banner ? '<img alt="" decoding="async" src="' + esc(banner) + '">' : '<span>' + esc(app.label) + '</span>') + '</div>' +
      '<div class="app-card-text"><h2>' + esc(app.label) + '</h2><p>' + esc(text) + '</p><div class="controls">' + buttons + '</div></div></div>';
  }

  function posterHtml(item, key, square, round) {
    var title = item.title || item.app || '';
    var sub = [item.sub, minutesLeft(item.left)].filter(Boolean).join(' · ');
    var html = '<button class="poster f' + (square ? ' square' : '') + (round ? ' round' : '') + '" data-key="' + esc(key) + '" data-intent="' + esc(item.intent || '') +
      '" data-owner="' + esc(item.pkg || '') + '" data-poster="' + esc(item.poster || '') + '"' +
      (item.link ? ' data-link="' + esc(item.link) + '"' : '') + ' aria-label="' + esc(title) + '"><div class="art">';
    html += item.poster ? '<img alt="" decoding="async" src="' + esc(item.poster) + '">'
      : '<div class="fallback" style="background:' + tintFor(title) + '">' + esc(title) + '</div>';
    if (item.progress > 0) { html += '<div class="left"><i style="width:' + Math.round(Math.min(1, item.progress) * 100) + '%"></i></div>'; }
    html += '<i class="glint"></i></div><div class="cap"><b>' + esc(title) + '</b><span>' + esc(sub || item.app || '') + '</span></div></button>';
    return html;
  }

  /** Where Up from the dock lands: the landing page's own entry, or the poster last used. */
  function shelfEntry() {
    if (state.shelf === 'landing') { return landingEntry(); }
    var layer = state.shelfLayers[state.shelf];
    if (!layer) { return null; }
    var pick = state.shelfFocus[state.shelf] ? $('[data-key="' + state.shelfFocus[state.shelf] + '"]', layer) : null;
    if (pick && isShown(pick)) { return pick; }
    return $$('.f', layer).filter(isShown)[0] || null;
  }

  /** The mini player in the top bar: on whenever music plays and the shelf shows something else. */
  function updateMini() {
    var np = state.np || {};
    var on = state.view === 'home' && !!np.active && state.shelf !== 'landing';
    var mini = $('#mini');
    if (mini.classList.contains('on') !== on) { mini.classList.toggle('on', on); }
  }

  /** Reads what the apps share (the TV side keeps it ready) and refreshes the shelf on screen
      if any of it changed. */
  function loadContent() {
    state.status = callJson('getStatus', {});
    var allowed = !!state.status.tvListings;
    var watchNext = allowed ? callJson('getWatchNext', []) : [];
    var previews = allowed ? callJson('getPreviews', []) : [];
    var sig = JSON.stringify([watchNext, previews, allowed, state.landingSig, state.spotifySig,
      !!state.spotify.connected, !!state.spotify.configured]);
    if (sig === state.contentSig) { return; }
    state.contentSig = sig;
    state.content = { watchNext: watchNext, previews: previews };

    var active = document.activeElement;
    var inShelf = active && active.closest && active.closest('#shelf .shelf-app');
    var owner = active && active.closest && active.closest('#dock') ? shelfOwner(active) : state.shelf;
    if (owner !== 'landing' && owner.indexOf(':') < 0 && !contentFor(owner).length) { owner = 'empty:' + owner; }
    dropShelves(owner === state.shelf ? owner : null);
    if (owner === state.shelf && owner !== 'landing') {
      // Same app: refill its shelf in place and keep focus on the same poster.
      var layer = layerFor(owner);
      var key = inShelf ? active.dataset.key : null;
      buildShelf(layer, owner);
      if (inShelf) { focus((key && $('[data-key="' + key + '"]', layer)) || shelfEntry() || dockFocus()); }
    } else {
      setShelf(owner, true);
      if (inShelf) { focus(shelfEntry() || dockFocus()); }
    }
    schedulePrebuild();
  }

  /** Builds the other apps' shelves while the remote rests, so the first visit is smooth too. */
  var prebuildTimer = 0;
  function schedulePrebuild() {
    clearTimeout(prebuildTimer);
    var step = function () {
      if (!state.visible || state.view !== 'home') { return; }
      if (Date.now() - state.lastInput < 1500) { prebuildTimer = setTimeout(step, 900); return; }
      var tiles = $$('#dock .app[data-pkg]');
      for (var i = 0; i < tiles.length; i++) {
        var owner = shelfOwner(tiles[i]);
        if (owner !== 'landing' && owner.indexOf(':') < 0 && !state.shelfLayers[owner]) {
          layerFor(owner);
          prebuildTimer = setTimeout(step, 500);
          return;
        }
      }
    };
    prebuildTimer = setTimeout(step, 1500);
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

  /** On, stopped by Android, or off. With the permission for it, one press mends it. */
  function homeButtonRow(status) {
    var fix = !!status.canFixHomeButton;
    if (status.homeButton && status.homeButtonRunning !== false) {
      return setupRow(true, 'Home button', 'The remote’s Home button opens this screen.', 'accessibility', '');
    }
    if (status.homeButton) {
      return setupRow(false, 'Home button', fix ? 'Android has stopped it. Choose this to start it again.'
        : 'Android has stopped it. In the TV’s settings, under Accessibility, turn TV Home off and on again.',
        fix ? 'fix-home' : 'accessibility', fix ? 'Restart' : 'Open settings');
    }
    return setupRow(false, 'Home button', fix ? 'The Home button goes to the stock home screen. Choose this to bring it here.'
      : 'Not sending the Home button here yet. In the TV’s settings, under Accessibility, turn on TV Home.',
      fix ? 'fix-home' : 'accessibility', fix ? 'Turn on' : 'Open settings');
  }

  function renderPanel() {
    var body = $('#panel-inner');
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

    if (inputs.length) {
      html += '<div class="group" data-zone="inputs"><h2>Input</h2><div class="options">';
      inputs.forEach(function (input) {
        html += '<button class="opt f" data-input="' + esc(input.id) + '">' + esc(input.label) + '</button>';
      });
      html += '</div></div>';
    }

    var sp = state.spotify;
    var spName = (sp.library || {}).name || '';
    html += '<div class="group" data-zone="spotify"><h2>Spotify</h2>' + (
      !sp.configured ? '<div class="setup-row static"><span class="what"><b>Not set up yet</b><span>TV Home needs its own key from ' +
        'developer.spotify.com to read your music. See the setup notes.</span></span></div>'
      : sp.connected ? '<button class="setup-row f" data-act="spotify-disconnect"><span class="what"><b>Connected' + (spName ? ' as ' + esc(spName) : '') +
        '</b><span>What you play and your playlists are on Home.</span></span><span class="state">Disconnect</span></button>'
      : '<button class="setup-row f" data-act="spotify-connect"><span class="what"><b>Connect Spotify</b><span>Show what you have been playing ' +
        'and your playlists on Home. You approve it on your phone.</span></span><span class="state">Connect</span></button>') + '</div>';

    html += '<div class="group" data-zone="auto"><h2>When music starts from your phone</h2><div class="options">' +
      option('auto', 'on', 'Open the player', prefs.autoOpen) +
      option('auto', 'off', 'Stay where I am', !prefs.autoOpen) + '</div></div>';

    html += '<div class="group" data-zone="idle"><h2>Clock screen</h2>' +
      '<div class="sub">Shows while music plays</div><div class="options">' +
      option('idle', '2', 'After 2 min', prefs.idleMin === 2) +
      option('idle', '5', 'After 5 min', prefs.idleMin === 5) +
      option('idle', '0', 'Never', prefs.idleMin === 0) + '</div>' +
      '<div class="sub">Day and date</div><div class="options">' +
      option('clockdate', 'on', 'Show', prefs.clockDate) +
      option('clockdate', 'off', 'Hide', !prefs.clockDate) + '</div></div>';

    var check = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M5 12.5l4.5 4.5L19 7.5"/></svg>';
    html += '<div class="group" data-zone="accent"><h2>Look</h2>' +
      '<div class="sub">' + (prefs.accent === 'cover' ? 'Accent colour: follows the cover of what is playing' : 'Accent colour') + '</div>' +
      '<div class="swatches">' +
      Object.keys(ACCENTS).map(function (name) {
        var a = ACCENTS[name];
        var chip = name === 'cover' ? '<i class="chip cover"><b></b>' + check + '</i>'
          : '<i class="chip" style="background:' + a.colour + '">' + check + '</i>';
        return '<button class="swatch f" data-opt="accent:' + name + '" aria-label="' + a.name + '" aria-pressed="' +
          (prefs.accent === name ? 'true' : 'false') + '">' + chip + '<span>' + a.name + '</span></button>';
      }).join('') + '</div>' +
      '<div class="sub">Weather in ' + esc(PLACE.name) + '</div><div class="options">' +
      option('weather', 'on', 'Show', prefs.weather) +
      option('weather', 'off', 'Hide', !prefs.weather) + '</div></div>';

    html += updateGroup();

    // Setup only lists what still needs doing; once all is done it is one quiet line.
    var homeOk = status.homeButton && status.homeButtonRunning !== false;
    var rows = '';
    if (!status.nowPlayingAccess) {
      rows += setupRow(false, 'Now playing', 'Needs notification access to show the current track.', 'notifications', 'Open settings');
    }
    if (!homeOk) { rows += homeButtonRow(status); }
    if (!status.tvListings) {
      rows += setupRow(false, 'Continue watching', 'Lets Home show what Netflix and other apps share.', 'listings', 'Allow');
    }
    html += '<div class="group" data-zone="setup"><h2>Setup</h2>' +
      (rows || '<div class="all-set">Everything is set up: now playing, the Home button and Continue watching.</div>') +
      '<button class="setup-row f" data-act="settings"><span class="what"><b>TV settings</b>' +
      '<span>Picture, sound, network and everything else on the TV.</span></span><span class="state">Open</span></button></div>';

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
      focus(again || $('.opt[aria-pressed="true"], .swatch[aria-pressed="true"]', body) || $('.f', body));
    }
  }

  /* ---------- Updates from GitHub ---------- */

  function updateGroup() {
    var u = state.update || {};
    var title, text, action, label, done = false, meter = '';
    if (u.state === 'ready' && !u.mayInstall) {
      title = 'Build ' + u.latest + ' is ready';
      text = 'First allow TV Home to install apps: the button opens that setting.';
      action = 'allow-install'; label = 'Open settings';
    } else if (u.state === 'ready') {
      title = 'Build ' + u.latest + ' is ready';
      text = u.error ? u.error : (u.notes || 'A newer version of TV Home.');
      action = 'install'; label = 'Install';
    } else if (u.state === 'downloading') {
      title = 'Downloading build ' + u.latest;
      text = (u.progress || 0) + '%';
      meter = '<span class="meter"><i style="width:' + (u.progress || 0) + '%"></i></span>';
      action = 'none'; label = '…';
    } else if (u.state === 'installing') {
      title = 'Installing build ' + u.latest;
      text = 'Confirm the question on the screen with OK.';
      action = 'none'; label = '…';
    } else if (u.state === 'checking') {
      title = 'Checking for updates';
      text = 'Looking at GitHub.';
      action = 'none'; label = '…';
    } else if (u.state === 'error') {
      title = 'Could not update';
      text = u.error || 'Something went wrong.';
      action = 'check'; label = 'Try again';
    } else {
      title = 'Up to date';
      text = 'TV Home build ' + (u.installed || '?') + '. New builds are found by themselves.';
      action = 'check'; label = 'Check now'; done = false;
    }
    return '<div class="group" data-zone="updates"><h2>Updates</h2>' +
      '<button class="setup-row f' + (done ? ' done' : '') + '" data-update="' + action + '"><span class="what"><b>' + esc(title) +
      '</b><span>' + esc(text) + '</span>' + meter + '</span><span class="state">' + esc(label) + '</span></button></div>';
  }

  var lastUpdateSig = '';
  function pollUpdate() {
    var u = callJson('getUpdate', null);
    if (!u) { return; }
    state.update = u;
    var sig = JSON.stringify(u);
    if (sig === lastUpdateSig) { return; }
    var wasReady = /"state":"ready"/.test(lastUpdateSig);
    lastUpdateSig = sig;
    $$('[data-act="update"]').forEach(function (el) { el.hidden = u.state !== 'ready'; });
    if (u.state === 'ready' && !wasReady && state.view === 'home' && !state.panel) {
      toast('A new version of TV Home is ready');
    }
    if (state.panel) {
      var box = $('#panel [data-zone="updates"]');
      if (box) {
        var hadFocus = box.contains(document.activeElement);
        box.outerHTML = updateGroup();
        if (hadFocus) { focus($('#panel [data-zone="updates"] .f')); }
      }
    }
    if (state.view === 'home' && !state.panel && document.activeElement && !isShown(document.activeElement)) {
      focus(defaultFocus());
    }
  }

  function runUpdate(action) {
    if (action === 'install') {
      call('installUpdate');
      toast('Installing. Confirm on the screen with OK.');
    } else if (action === 'allow-install') {
      if (!call('openSettings', 'install')) { toast('This TV has no screen for that. Use the setup guide.'); }
    } else if (action === 'check') {
      call('checkUpdate', true);
    }
    setTimeout(pollUpdate, 300);
  }

  function openPanel() {
    if (state.panel) { return; }
    stopMoving();
    rememberFocus();
    state.panelReturn = document.activeElement;
    state.panel = true;
    stage.classList.add('panel-open');
    slide($('#panel-inner'), 'y', 0);
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
    } else if (group === 'accent' && ACCENTS[value]) {
      prefs.accent = value;
      store.set('accent2', value);
      applyLook();
    } else if (group === 'clockdate') {
      prefs.clockDate = value === 'on';
      store.set('clockDate', prefs.clockDate);
      applyClockDate();
    } else if (group === 'weather') {
      prefs.weather = value === 'on';
      store.set('weather', prefs.weather);
      weather.asked = 0;
      loadWeather();
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
    if (d.update) { runUpdate(d.update); return; }
    if (d.input) { if (!call('openInput', d.input)) { toast('Could not switch input'); } return; }
    if (d.setup) {
      if (d.done === '1') { toast('Already on'); }
      else if (d.setup === 'listings') { call('requestTvListings'); }
      else if (d.setup === 'fix-home') {
        if (call('fixHomeButton')) {
          toast('Starting the Home button again');
          setTimeout(function () { if (state.panel) { renderPanel(); } }, 3000);
        } else { call('openSettings', 'accessibility'); }
      }
      else if (!call('openSettings', d.setup)) { toast('This TV has no screen for that. Use the setup guide.'); }
      return;
    }
    if (d.link) {
      if (!call('openSpotify', d.link)) { toast('Could not open Spotify'); }
      return;
    }
    if (d.open) { launch(d.open); return; }
    if (d.intent !== undefined && el.classList.contains('poster')) {
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
      case 'spotify-connect': openConnect(); break;
      case 'connect-retry': $('#qr').innerHTML = ''; call('spotifyConnect'); loadSpotify(); break;
      case 'connect-cancel': closeConnect(); break;
      case 'spotify-disconnect':
        call('spotifyDisconnect');
        toast('Spotify disconnected');
        loadSpotify();
        break;
      case 'home': goHome(); break;
      case 'panel': openPanel(); break;
      case 'apps': closePanel(); show('allapps'); break;
      case 'settings': call('openSettings', 'system'); break;
      case 'update':
        if (state.update && state.update.mayInstall === false) { openPanel(); } else { runUpdate('install'); }
        break;
      case 'music':
        var spotify = state.apps.filter(function (a) { return /^spotify/i.test(a.label); })[0];
        if (spotify) { launch(spotify.pkg); } else { show('allapps'); }
        break;
      case 'source-app':
        if (np.active && np.pkg) { launch(np.pkg); } else { activate($('#shelf-music [data-act="music"]')); }
        break;
      default:
        break;
    }
  }

  /** Holding OK: in the dock, pick the app up to move it; on All apps, add it to Home or take it off. */
  function hold(el) {
    if (!el.dataset.pkg || state.panel) { return; }
    if (state.view === 'home' && el.closest('.dock')) { startMoving(el); } else { togglePin(el.dataset.pkg); }
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
    if (state.moving) {
      // Moving an app: left and right move it, down takes it off Home, anything else puts it down.
      if (key === 'ArrowLeft') { shiftMoving(-1); }
      else if (key === 'ArrowRight') { shiftMoving(1); }
      else if (key === 'ArrowDown') { if (!e.repeat) { removeMoving(); } }
      else if (key !== 'ArrowUp' && !e.repeat) { stopMoving(); press = { swallow: true }; }
      return;
    }
    if (e.repeat && /^Arrow/.test(key)) {
      if (Date.now() - state.lastMoveAt < 120) { return; }
    }
    if (/^Arrow/.test(key)) { state.lastMoveAt = Date.now(); }
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
    loadWeather();
    if (state.view === 'connect') { loadSpotify(); }
    if (Date.now() % 4000 < 1000) { pollUpdate(); }

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
        case 'content': loadContent(); break;
        case 'spotify': loadSpotify(); break;
        case 'hold':
          // The TV side saw OK held down (more reliable than timing it here).
          noteInput();
          if (press && !press.held && !press.swallow) {
            clearTimeout(press.timer);
            press.held = true;
            hold(press.el);
          }
          break;
        case 'input': noteInput(); break;
        case 'back': noteInput(); back(); break;
        case 'menu':
          noteInput();
          if (state.view === 'ambient') { leaveAmbient(); }
          else if (state.view === 'connect') { closeConnect(); }
          else if (state.panel) { closePanel(); }
          else { openPanel(); }
          break;
        case 'pause':
          stopMoving();
          state.visible = false;
          state.pausedAt = Date.now();
          leaveAmbient();
          updateAwake();
          break;
        case 'resume':
          state.visible = true;
          noteInput();
          loadApps();
          call('refreshContent');
          call('refreshSpotify');
          loadSpotify();
          loadContent();
          loadLanding(false);
          pull();
          loadWeather();
          call('checkUpdate', false);
          pollUpdate();
          if (state.panel) { renderPanel(); }
          else if (state.view === 'home' && Date.now() - state.pausedAt > 2000) {
            // Coming back from another app: Home settles into place instead of just appearing.
            var home = $('#home');
            home.classList.remove('arrive');
            void home.offsetWidth;
            home.classList.add('arrive');
          }
          break;
        case 'permissions':
          state.contentSig = null;
          loadContent();
          if (state.panel) { renderPanel(); }
          break;
        case 'show':
          noteInput();
          if (detail === 'player') { closePanel(); show('player'); }
          else if (!state.visible && Date.now() - state.pausedAt > 1500) {
            // Back from another app: the landing page, with the dock still where it was.
            goLanding();
          } else { goHome(); }
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

  // Browsers scroll a hidden-overflow box to bring a focused item into view. The lists here are
  // moved with transforms instead, so any such scroll is undone straight away.
  document.addEventListener('scroll', function (e) {
    var el = e.target;
    if (el && el !== document && (el.scrollTop || el.scrollLeft)) { el.scrollTop = 0; el.scrollLeft = 0; }
  }, true);

  call('setPref', 'autoOpen', prefs.autoOpen ? '1' : '0');
  applyLook();
  $('#home').classList.add('on', 'arrive');
  renderClock();
  loadApps();
  loadSpotify();
  renderPicks();
  pull();
  loadContent();
  loadLanding(true);
  loadWeather();
  pollUpdate();
  var first = call('takePendingView');
  if (first === 'player') { show('player'); } else { goHome(); }
  setInterval(tick, 1000);
  setInterval(function () { if (state.visible) { call('refreshContent'); loadLanding(false); } }, 5 * 60 * 1000);
  applyClockDate();
})();
