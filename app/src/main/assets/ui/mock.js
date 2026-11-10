/* Sample content for previewing the interface in a desktop browser (add ?mock=1 to the address).
   Never used on the TV: there the real `Native` object exists before this file runs.
   All titles and names below are invented. */
(function () {
  'use strict';
  if (window.Native || !/[?&]mock=1/.test(location.search)) { return; }

  function param(name, fallback) {
    var m = new RegExp('[?&]' + name + '=([^&]*)').exec(location.search);
    return m ? decodeURIComponent(m[1]) : fallback;
  }
  function svg(body, w, h) {
    return 'data:image/svg+xml,' + encodeURIComponent(
      '<svg xmlns="http://www.w3.org/2000/svg" width="' + w + '" height="' + h + '" viewBox="0 0 ' + w + ' ' + h + '">' + body + '</svg>');
  }

  var cover = svg('<rect width="600" height="600" fill="#23324F"/><circle cx="385" cy="207" r="129" fill="#E9E1CF"/>' +
    '<rect y="398" width="600" height="34" fill="#E3A08C"/><rect y="432" width="600" height="168" fill="#C8553D"/>', 600, 600);

  var cover2 = svg('<rect width="600" height="600" fill="#5B2A3A"/><rect x="90" y="90" width="420" height="420" rx="210" fill="#E3A08C"/>' +
    '<rect x="0" y="300" width="600" height="300" fill="#2C4A3E" opacity="0.85"/>', 600, 600);

  function banner(label, bg, fg) {
    return svg('<rect width="640" height="360" fill="' + bg + '"/><text x="320" y="204" text-anchor="middle" ' +
      'font-family="sans-serif" font-size="64" font-weight="700" fill="' + fg + '">' + label + '</text>', 640, 360);
  }
  function poster(bg, shapes) { return svg('<rect width="828" height="466" fill="' + bg + '"/>' + shapes, 828, 466); }

  var apps = [
    { pkg: 'com.spotify.tv.android', label: 'Spotify', tv: true, banner: true, art: banner('Spotify', '#16261C', '#EFE7DA') },
    { pkg: 'com.google.android.youtube.tv', label: 'YouTube', tv: true, banner: true, art: banner('YouTube', '#2B1614', '#EFE7DA') },
    { pkg: 'com.netflix.ninja', label: 'Netflix', tv: true, banner: true, art: banner('Netflix', '#1B1414', '#EFE7DA') },
    { pkg: 'se.svt.android.svtplay', label: 'SVT Play', tv: true, banner: true, art: banner('SVT Play', '#14233A', '#EFE7DA') },
    { pkg: 'sample.films', label: 'Film Club', tv: true, banner: true, art: banner('Film Club', '#34504A', '#EFE7DA') },
    { pkg: 'sample.radio', label: 'Radio', tv: true, banner: true, art: banner('Radio', '#5B2A3A', '#EFE7DA') },
    { pkg: 'sample.photos', label: 'Photos', tv: true, banner: false },
    { pkg: 'sample.files', label: 'Files', tv: false, banner: false },
    { pkg: 'sample.browser', label: 'Browser', tv: true, banner: true, art: banner('Browser', '#2A313B', '#EFE7DA') },
    { pkg: 'sample.games', label: 'Arcade', tv: true, banner: true, art: banner('Arcade', '#6B4A33', '#EFE7DA') },
    { pkg: 'sample.weather', label: 'Weather', tv: true, banner: false },
    { pkg: 'sample.store', label: 'App Store', tv: true, banner: true, art: banner('Store', '#23324F', '#EFE7DA') }
  ];

  var mode = param('state', 'playing');
  var sonos = param('source', 'tv') === 'sonos';
  var np = {
    access: mode !== 'setup', active: mode === 'playing' || mode === 'paused', playing: mode === 'playing',
    source: sonos ? 'sonos' : 'tv', room: sonos ? 'Vardagsrum' : '',
    pkg: 'com.spotify.tv.android', app: 'Spotify',
    title: param('title', 'Vinterhav'), artist: 'Nattljus', album: 'Sena kvällar',
    duration: 221000, position: 84000, art: cover, canNext: true, canPrev: true, canSeek: true,
    lossless: param('lossless', '0') === '1'
  };
  var startedAt = Date.now();
  var listings = param('listings', '1') === '1';
  var log = window.MockLog = [];

  function position() { return np.playing ? Math.min(np.duration, np.position + (Date.now() - startedAt)) : np.position; }

  window.MockImages = function (kind, pkg) {
    var app = apps.filter(function (a) { return a.pkg === pkg; })[0];
    if (kind === 'banner') { return app && app.art ? app.art : ''; }
    return svg('<rect width="192" height="192" rx="36" fill="#3A322A"/><circle cx="96" cy="96" r="44" fill="#D9A441"/>', 192, 192);
  };

  var sleep = { mode: 'off', at: 0, minutes: 0 };
  var update = param('update', '') === 'ready'
    ? { state: 'ready', installed: 2, latest: 3, notes: 'Smoother track changes, Sonos, and a new clock screen', progress: 100, error: '', mayInstall: true }
    : { state: 'current', installed: 3, latest: 3, notes: '', progress: 0, error: '', mayInstall: true };

  // What the scheduled Claude task writes each morning (invented titles), unless ?landing=0.
  function square(bg, fg) {
    return svg('<rect width="300" height="300" fill="' + bg + '"/><circle cx="150" cy="150" r="88" fill="' + fg + '" opacity="0.85"/>' +
      '<circle cx="150" cy="150" r="20" fill="' + bg + '"/>', 300, 300);
  }
  function picks(list) {
    return list.map(function (p, i) {
      return { title: p[0], subtitle: p[1], link: 'spotify:playlist:sample' + p[0].length + i, image: square(p[2], p[3]) };
    });
  }
  var d = new Date();
  window.MockLanding = param('landing', '1') === '1' ? {
    version: 1, name: 'Viggo',
    date: d.getFullYear() + '-' + ('0' + (d.getMonth() + 1)).slice(-2) + '-' + ('0' + d.getDate()).slice(-2),
    parts: {
      morning: { greeting: 'Good morning, Viggo', note: 'Something light to ease into Wednesday.', picks: picks([
        ['Morning Acoustic', 'Playlist', '#5E7A64', '#F0E6CF'], ['Kaffe och vinyl', 'Playlist', '#7A5A3E', '#EAD9B8'], ['Soft Pop Sunrise', 'Playlist', '#A6745A', '#F6E3C8']]) },
      afternoon: { greeting: 'Good afternoon, Viggo', note: 'Focus music for the rest of the day.', picks: picks([
        ['Deep Focus Piano', 'Playlist', '#2F4250', '#C9D7DF'], ['Lo-fi Study', 'Playlist', '#3E3352', '#D8C9E6']]) },
      evening: { greeting: 'Good evening, Viggo', note: 'A quiet Tuesday night. How about some evening jazz?', picks: picks([
        ['Evening Jazz Vibes', 'Playlist', '#23324F', '#E3A08C'], ['Late Night Piano Bar', 'Playlist', '#3A2430', '#E8C9A9'],
        ['Blue Hour', 'Album · Nattljus', '#1F3A3A', '#9CC7B8'], ['Velvet Soul', 'Playlist', '#4A2A22', '#F0B98E']]) },
      night: { greeting: 'Good night, Viggo', note: 'Something soft to wind down with.', picks: picks([
        ['Sleepy Strings', 'Playlist', '#1B2233', '#8FA3C7'], ['Ambient Nights', 'Playlist', '#14161C', '#6E7B8C']]) }
    }
  } : null;

  // A sample reading, so the preview does not depend on the network.
  if (param('weather', '1') === '1') { window.MockWeather = { temp: 6.4, code: 3, day: false }; }

  window.Native = {
    takePendingView: function () { return param('view', ''); },
    setSleep: function (mode) {
      log.push('sleep ' + mode);
      var minutes = parseInt(mode, 10) || 0;
      sleep = mode === 'track' ? { mode: 'track', at: 0, minutes: 0 }
        : minutes > 0 ? { mode: 'timer', at: Date.now() + minutes * 60000, minutes: minutes }
        : { mode: 'off', at: 0, minutes: 0 };
    },
    getApps: function () {
      return JSON.stringify(apps.map(function (a) { return { pkg: a.pkg, label: a.label, tv: a.tv, banner: a.banner }; }));
    },
    launch: function (pkg) { log.push('launch ' + pkg); return true; },
    getNowPlaying: function () {
      var copy = JSON.parse(JSON.stringify(np));
      copy.position = position();
      copy.key = np.title + '\n' + np.artist;
      copy.sleep = sleep;
      copy.sleepFired = false;
      return JSON.stringify(copy);
    },
    media: function (command, argument) {
      log.push('media ' + command + ' ' + argument);
      if (command === 'toggle' || command === 'play' || command === 'pause') {
        var want = command === 'toggle' ? !np.playing : command === 'play';
        np.position = position();
        startedAt = Date.now();
        np.playing = want;
      } else if (command === 'next') {
        // Like the real thing: the new title first, the cover a moment later.
        np.title = np.title === 'Stilla vatten' ? 'Vinterhav' : 'Stilla vatten';
        np.position = 0; startedAt = Date.now(); np.duration = 242000;
        var nextArt = np.art === cover ? cover2 : cover;
        np.art = '';
        setTimeout(function () { np.art = nextArt; }, 350);
      } else if (command === 'prev') {
        np.position = 0; startedAt = Date.now();
      } else if (command === 'seek') {
        np.position = Math.min(np.duration, Number(argument) || 0); startedAt = Date.now();
      }
    },
    getWatchNext: function () {
      if (!listings) { return '[]'; }
      return JSON.stringify([
        { id: 1, pkg: 'com.netflix.ninja', app: 'Netflix', title: 'Nattåget', sub: 'S1 · E4', progress: 0.62, left: 18 * 60000, intent: 'intent:#1',
          poster: poster('#23324F', '<circle cx="640" cy="130" r="56" fill="#E9E1CF"/><rect y="300" width="828" height="8" fill="#E9E1CF"/><rect x="120" y="244" width="440" height="56" rx="12" fill="#D9A441"/>') },
        { id: 2, pkg: 'se.svt.android.svtplay', app: 'SVT Play', title: 'Hamnen', sub: 'Avsnitt 3', progress: 0.14, left: 41 * 60000, intent: 'intent:#2',
          poster: poster('#34504A', '<rect x="300" y="264" width="192" height="88" fill="#C8553D"/><rect x="504" y="264" width="192" height="88" fill="#D9A441"/><rect x="400" y="164" width="192" height="88" fill="#E9E1CF"/><rect y="352" width="828" height="114" fill="#223732"/>') },
        { id: 3, pkg: 'sample.films', app: 'Film Club', title: 'Restoring a 1970s turntable', sub: 'Verkstan', progress: 0.45, left: 12 * 60000, intent: 'intent:#3',
          poster: poster('#6B4A33', '<circle cx="414" cy="233" r="160" fill="#0E0C0A"/><circle cx="414" cy="233" r="48" fill="#D9A441"/>') },
        { id: 4, pkg: 'com.netflix.ninja', app: 'Netflix', title: 'Brooklyn, 3 a.m.', sub: 'Film', progress: 0.3, left: 62 * 60000, intent: 'intent:#4',
          poster: poster('#14171C', '<rect x="240" y="140" width="140" height="340" fill="#2A313B"/><rect x="400" y="80" width="180" height="400" fill="#363F4B"/><rect x="600" y="192" width="120" height="280" fill="#2A313B"/><rect x="436" y="128" width="32" height="40" fill="#D9A441"/><rect x="508" y="224" width="32" height="40" fill="#D9A441"/>') },
        { id: 5, pkg: 'se.svt.android.svtplay', app: 'SVT Play', title: 'Skymning', sub: 'S2 · E1', progress: 0.08, left: 44 * 60000, intent: 'intent:#5', poster: '' },
        { id: 6, pkg: 'sample.films', app: 'Film Club', title: 'The Long Platform', sub: 'Film', progress: 0.71, left: 31 * 60000, intent: 'intent:#6',
          poster: poster('#5B2A3A', '<rect x="0" y="330" width="828" height="136" fill="#3A1B25"/><circle cx="200" cy="200" r="70" fill="#F0D5C8"/>') }
      ]);
    },
    getPreviews: function () {
      if (!listings) { return '[]'; }
      var colours = ['#2C4A3E', '#23324F', '#6B4A33', '#5B2A3A', '#34504A', '#2A313B'];
      function items(prefix, titles) {
        return titles.map(function (title, i) {
          return { id: prefix + i, pkg: prefix, title: title, intent: 'intent:#' + prefix + i,
            poster: poster(colours[i % colours.length], '<circle cx="' + (200 + i * 70) + '" cy="233" r="110" fill="#E9E1CF" opacity="0.9"/>') };
        });
      }
      return JSON.stringify([
        { pkg: 'com.spotify.tv.android', app: 'Spotify', name: 'Recently played', items: items('com.spotify.tv.android',
          ['Daily Mix 1', 'Discover Weekly', 'Release Radar']) },
        { pkg: 'com.netflix.ninja', app: 'Netflix', name: 'Top picks for you', items: items('com.netflix.ninja',
          ['The Lighthouse Keeper', 'Cold Harbour', 'Seven Bridges', 'After the Rain', 'Night Shift']) },
        { pkg: 'se.svt.android.svtplay', app: 'SVT Play', name: 'Populärt just nu', items: items('se.svt.android.svtplay',
          ['Skärgårdsliv', 'Fjällen i vinter', 'Kustvägen', 'Gamla hus']) },
        { pkg: 'sample.films', app: 'Film Club', name: 'New this week', items: items('sample.films',
          ['Paper Lanterns', 'Salt Road', 'Understudy', 'North of Nine', 'Quiet Hours']) },
        { pkg: 'com.google.android.youtube.tv', app: 'YouTube', name: 'Recommended', items: items('com.google.android.youtube.tv',
          ['Building a hi-fi shelf', 'One hour of rain on a tin roof', 'Night bus through the city', 'How records are pressed', 'Small kitchen, big dinner', 'Ferry crossing at dawn']) }
      ]);
    },
    openIntent: function (uri, pkg) { log.push('intent ' + uri + ' ' + pkg); return true; },
    getInputs: function () {
      return JSON.stringify([{ id: 'hdmi1', label: 'HDMI 1' }, { id: 'hdmi2', label: 'HDMI 2' }, { id: 'hdmi3', label: 'HDMI 3' }, { id: 'hdmi4', label: 'HDMI 4' }]);
    },
    openInput: function (id) { log.push('input ' + id); return true; },
    openSettings: function (which) { log.push('settings ' + which); return true; },
    getStatus: function () {
      var home = param('home', 'on');
      return JSON.stringify({ nowPlayingAccess: np.access, homeButton: mode !== 'setup' && home !== 'off',
        homeButtonRunning: home === 'on', canFixHomeButton: param('fix', '1') === '1', tvListings: listings,
        version: 'preview', device: 'Sample TV', android: '11', pkg: 'se.tvhome.launcher' });
    },
    requestTvListings: function () { listings = true; setTimeout(function () { window.TVHome.emit('permissions', ''); }, 50); },
    setPref: function (key, value) { log.push('pref ' + key + '=' + value); },
    getUpdate: function () { return JSON.stringify(update); },
    checkUpdate: function (force) { log.push('check ' + force); },
    installUpdate: function () { log.push('install'); update.state = 'installing'; },
    keepAwake: function (on) { log.push('awake ' + on); },
    refreshContent: function () { log.push('refresh content'); },
    openSpotify: function (link) { log.push('spotify ' + link); return true; },
    fixHomeButton: function () { log.push('fix home'); return param('fix', '1') === '1'; }
  };

  window.Mock = {
    set: function (changes) { Object.keys(changes).forEach(function (k) { np[k] = changes[k]; }); startedAt = Date.now(); }
  };
})();
