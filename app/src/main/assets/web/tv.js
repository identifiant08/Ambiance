/* Ambiance : écran TV. Navigation à la croix directionnelle, recherche, lecteur natif (yt-dlp + ExoPlayer). */
(function () {
  const A = window.Android || null;
  const MOCK = !A;
  const $ = (s) => document.querySelector(s);
  const store = {
    get(k, d) { try { const v = localStorage.getItem(k); return v == null ? d : JSON.parse(v); } catch (e) { return d; } },
    set(k, v) { try { localStorage.setItem(k, JSON.stringify(v)); } catch (e) {} },
    del(k) { try { localStorage.removeItem(k); } catch (e) {} },
    keys() { try { return Object.keys(localStorage); } catch (e) { return []; } }
  };
  const { MOODS, sceneSVG, mood: moodById } = window.Ambiance;

  /* ---------- Appels natifs asynchrones ---------- */
  let reqSeq = 0;
  const pending = {};
  function native(method, ...args) {
    return new Promise((res, rej) => {
      const id = 'r' + (++reqSeq);
      pending[id] = { res, rej };
      try { A[method](id, ...args); } catch (e) { delete pending[id]; rej(e); }
    });
  }
  function onNative(id, payload) {
    const p = pending[id]; if (!p) return;
    delete pending[id];
    if (payload && payload.ok) p.res(payload); else p.rej(new Error((payload && payload.error) || 'Erreur inconnue'));
  }

  /* ---------- Configuration ---------- */
  let cfg = { address: '', quality: 1080, ytdlp: '', ready: false };
  const QUALITIES = [720, 1080, 2160];
  function loadCfg() {
    if (A) { try { cfg = Object.assign(cfg, JSON.parse(A.getConfig())); } catch (e) {} }
    else { cfg.address = location.host || ''; cfg.quality = store.get('quality', 1080); cfg.ytdlp = 'maquette'; cfg.ready = true; }
    const url = cfg.address ? 'http://' + cfg.address : '';
    $('#remoteHint').textContent = url ? 'Télécommande : ' + url : '';
    $('#remoteUrl').textContent = url || "l'adresse affichée en haut de l'accueil";
    $('#btnQuality').textContent = 'Qualité maximale : ' + qLabel(cfg.quality);
    $('#ytdlpStatus').textContent = cfg.ready
      ? 'yt-dlp ' + (cfg.ytdlp || '') + ' prêt.'
      : 'Préparation de yt-dlp, quelques secondes au premier lancement.';
    report();
  }
  const qLabel = (h) => (h >= 2160 ? '4K' : h + 'p');

  /* ---------- Écrans et historique ---------- */
  let screen = 'home';
  const hist = [];
  const lastFocus = {};
  function show(id, push = true) {
    if (push && screen !== id) hist.push(screen);
    document.querySelectorAll('.screen').forEach((s) => s.classList.toggle('active', s.id === id));
    screen = id;
    document.body.classList.toggle('playing', id === 'player');
    document.documentElement.classList.toggle('playing', id === 'player');
    const target = lastFocus[id] && lastFocus[id].isConnected && !lastFocus[id].hidden ? lastFocus[id] : navEls()[0];
    focus(target);
    report();
  }
  function back() {
    if (screen === 'player' && osdVisible) { hideOsd(); return true; }
    if (screen === 'player') stopPlayer();
    blurField();
    const prev = hist.pop();
    if (!prev) return false;
    show(prev, false);
    return true;
  }
  function goHome() { if (screen === 'player') stopPlayer(); hist.length = 0; show('home', false); }

  /* ---------- Navigation spatiale ---------- */
  let cur = null;
  function navEls() {
    return [...document.querySelectorAll('#' + screen + ' .nav')].filter((e) => !e.hidden && e.offsetParent !== null);
  }
  function focus(el) {
    if (!el) return;
    if (cur && cur !== el) { cur.classList.remove('focus'); cur.classList.remove('alive'); }
    cur = el;
    el.classList.add('focus');
    lastFocus[screen] = el;
    ensureVisible(el);
    if (el.dataset.mood) {
      el.classList.add('alive');
      const m = moodById(el.dataset.mood);
      setBackdrop(m.id);
      $('#focusLine').textContent = m.name + ', ' + m.line.toLowerCase();
    }
    if (screen === 'player') armOsd();
  }
  function move(dir) {
    const els = navEls();
    if (!cur || !els.includes(cur)) { focus(els[0]); return; }
    const r = cur.getBoundingClientRect();
    const cx = r.left + r.width / 2, cy = r.top + r.height / 2;
    let best = null, bs = Infinity;
    for (const e of els) {
      if (e === cur) continue;
      const q = e.getBoundingClientRect();
      const dx = q.left + q.width / 2 - cx, dy = q.top + q.height / 2 - cy;
      let main, cross, score;
      if (dir === 'LEFT' || dir === 'RIGHT') {
        main = dir === 'LEFT' ? -dx : dx; cross = Math.abs(dy);
        if (main <= 1 || cross > Math.max(r.height, q.height) * 0.6) continue;
        score = main + cross * 4;
      } else {
        main = dir === 'UP' ? -dy : dy; cross = Math.abs(dx);
        if (main <= 1) continue;
        score = main + cross * 0.45;
      }
      if (score < bs) { bs = score; best = e; }
    }
    if (best) { blurField(); focus(best); }
  }
  function ensureVisible(el) {
    const strip = el.closest('.rail, .strip');
    if (strip) strip.scrollLeft = el.offsetLeft - strip.clientWidth / 2 + el.offsetWidth / 2;
    const results = el.closest('.results');
    if (results) {
      const block = el.closest('.block') || el;
      results.scrollTop = Math.max(0, block.offsetTop - 8);
    }
  }
  function blurField() {
    const a = document.activeElement;
    if (a && a.tagName === 'INPUT') { a.blur(); if (A) A.hideKeyboard(); }
  }
  function activate(el) {
    if (!el) return;
    if (el.tagName === 'INPUT') {
      if (document.activeElement === el) { doSearch(el.value); blurField(); }
      else { el.focus(); if (A) A.showKeyboard(); }
      return;
    }
    el.click();
  }

  /* ---------- Touches (télécommande physique, envoyées par Android) ---------- */
  function onKey(k) {
    if (screen === 'player') {
      if (k === 'PLAYPAUSE') { playerAct('toggle'); showOsd(); return true; }
      if (k === 'NEXT') { playerAct('next'); showOsd(); return true; }
      if (k === 'PREV') { playerAct('prev'); showOsd(); return true; }
      if (k !== 'BACK' && !osdVisible) { showOsd(); return true; }
    }
    if (k === 'BACK') return back();
    if (k === 'OK') { activate(cur); return true; }
    if (k === 'UP' || k === 'DOWN' || k === 'LEFT' || k === 'RIGHT') { move(k); return true; }
    return true;
  }
  if (!A) {
    const map = { ArrowUp: 'UP', ArrowDown: 'DOWN', ArrowLeft: 'LEFT', ArrowRight: 'RIGHT', Enter: 'OK', Escape: 'BACK', Backspace: 'BACK', ' ': 'PLAYPAUSE' };
    document.addEventListener('keydown', (e) => {
      const k = map[e.key];
      if (!k) return;
      if (document.activeElement && document.activeElement.tagName === 'INPUT' && (e.key === ' ' || e.key === 'Backspace')) return;
      e.preventDefault(); onKey(k);
    });
  }

  /* ---------- Fond animé ---------- */
  let bdMood = null, bdFlip = 0;
  function setBackdrop(id) {
    if (bdMood === id) return;
    bdMood = id;
    const layers = document.querySelectorAll('.bd-layer');
    const next = layers[bdFlip = 1 - bdFlip], prev = layers[1 - bdFlip];
    next.innerHTML = sceneSVG(id);
    next.classList.add('alive');
    requestAnimationFrame(() => { next.classList.add('on'); prev.classList.remove('on'); });
    setTimeout(() => { if (!prev.classList.contains('on')) { prev.classList.remove('alive'); prev.innerHTML = ''; } }, 1300);
  }

  /* ---------- Accueil ---------- */
  function buildHome() {
    const rail = $('#moodRail');
    rail.innerHTML = MOODS.map((m) =>
      `<button class="nav card" data-mood="${m.id}"><span class="win">${sceneSVG(m.id)}</span><span class="cap">${m.name}</span></button>`).join('');
    rail.querySelectorAll('.card').forEach((b) => {
      b.addEventListener('click', () => openMood(b.dataset.mood));
      b.addEventListener('mouseenter', () => { if (!A) focus(b); });
    });
    $('#btnSearch').onclick = () => show('search');
    $('#btnSettings').onclick = () => show('settings');
    $('#btnResume').onclick = () => { const l = store.get('last'); if (l) play(l, l.mood); };
    refreshResume();
  }
  function refreshResume() {
    const l = store.get('last');
    const b = $('#btnResume');
    b.hidden = !l;
    if (l) b.textContent = 'Reprendre : ' + l.title;
  }
  function tick() {
    const d = new Date(), h = d.getHours();
    $('#clock').textContent = d.toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' });
    $('#greet').textContent = h >= 5 && h < 12 ? 'Bonjour' : h >= 12 && h < 18 ? 'Bon après-midi' : 'Bonsoir';
    if (sleepAt) updateSleepLabel();
  }

  /* ---------- Recherche (côté natif : API interne YouTube, secours yt-dlp) ---------- */
  async function ytSearch(q, kind) {
    const ck = 'cache:' + kind + ':' + q.toLowerCase();
    const c = store.get(ck);
    if (c && Date.now() - c.t < 6 * 3600000 && c.items.length) return c.items;
    let items;
    if (MOCK) items = mockItems(q, kind);
    else items = (await native('search', q, kind)).items;
    if (items.length) store.set(ck, { t: Date.now(), items });
    return items;
  }
  function mockItems(q, kind) {
    const words = ['Nuit calme', 'Heures de détente', 'Version longue', 'Pour dormir', '4K HDR', 'Au coin du feu', 'Sons naturels', 'Sans paroles'];
    return words.map((w, i) => ({ type: kind, id: 'mock' + kind + i, title: q + ', ' + w, channel: 'Chaîne ' + (i + 1), thumb: '', meta: kind === 'playlist' ? (12 + i) + ' vidéos' : (3 + i) + ' h' }));
  }

  /* ---------- Rendu des résultats ---------- */
  let results = [], resultsFor = '', lastError = '';
  const esc = (s) => String(s || '').replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
  function tileHTML(it, i, moodId) {
    const th = it.thumb
      ? `<span class="th" style="background-image:url('${it.thumb.replace(/'/g, '%27')}')">`
      : `<span class="th">${sceneSVG(moodId || 'aurores')}`;
    const badge = it.meta || (it.type === 'playlist' ? 'Playlist' : 'Vidéo');
    return `<button class="nav tile" data-i="${i}">${th}<span class="badge">${esc(badge)}</span></span>
      <span class="tt">${esc(it.title)}</span><span class="ch">${esc(it.channel)}</span></button>`;
  }
  function renderResults(box, pl, vids, moodId) {
    results = pl.concat(vids);
    let html = '', i = 0;
    if (pl.length) html += `<div class="block"><h3>Playlists</h3><div class="strip">${pl.map((it) => tileHTML(it, i++, moodId)).join('')}</div></div>`;
    if (vids.length) html += `<div class="block"><h3>Vidéos longues</h3><div class="strip">${vids.map((it) => tileHTML(it, i++, moodId)).join('')}</div></div>`;
    if (!results.length) html = '<p class="empty">Rien trouvé. Essaie avec des mots en anglais, souvent plus riches pour ce genre de vidéos.</p>';
    box.innerHTML = html;
    box.querySelectorAll('.tile').forEach((b) => b.addEventListener('click', () => play(results[+b.dataset.i], moodId)));
    report();
  }
  function renderError(box, e) {
    lastError = 'La recherche a échoué : ' + e.message + '. Vérifie la connexion, puis réessaie.';
    box.innerHTML = `<p class="empty">${esc(lastError)}</p>`;
    results = []; report();
  }
  async function fetchBoth(q) {
    const [pl, vids] = await Promise.allSettled([ytSearch(q, 'playlist'), ytSearch(q, 'video')]);
    if (pl.status === 'rejected' && vids.status === 'rejected') throw pl.reason;
    return {
      pl: pl.status === 'fulfilled' ? pl.value.slice(0, 12) : [],
      vids: vids.status === 'fulfilled' ? vids.value.slice(0, 12) : []
    };
  }

  /* ---------- Page d'humeur ---------- */
  let currentMood = null, moodToken = 0;
  async function openMood(id, autoplay, shuffle) {
    const m = moodById(id); if (!m) return;
    currentMood = id; resultsFor = m.name; lastError = '';
    setBackdrop(id);
    $('#moodTitle').textContent = m.name;
    $('#moodLine').textContent = m.line;
    const box = $('#moodResults');
    box.innerHTML = '<p class="loading">Je cherche les plus belles vidéos</p>';
    results = [];
    if (screen !== 'mood') show('mood');
    focus($('#btnPlayMood'));
    const tok = ++moodToken;
    try {
      const { pl, vids } = await fetchBoth(m.q);
      if (tok !== moodToken) return;
      renderResults(box, pl, vids, id);
      if (autoplay) playMood(shuffle);
    } catch (e) { if (tok === moodToken) renderError(box, e); }
  }
  function playMood(shuffleIt) {
    const first = results.find((r) => r.type === 'playlist') || results[0];
    if (!first) { toast(lastError ? 'Recherche impossible pour le moment' : 'Encore en chargement, un instant'); return; }
    play(first, currentMood, { shuffle: shuffleIt });
  }

  /* ---------- Recherche libre ---------- */
  let searchToken = 0;
  async function doSearch(q) {
    q = (q || '').trim(); if (!q) return;
    $('#searchInput').value = q;
    currentMood = null; resultsFor = q; lastError = '';
    if (screen !== 'search') show('search');
    const box = $('#searchResults');
    box.innerHTML = '<p class="loading">Recherche en cours</p>';
    const tok = ++searchToken;
    try {
      const { pl, vids } = await fetchBoth(q);
      if (tok !== searchToken) return;
      renderResults(box, pl, vids, null);
      const t = box.querySelector('.tile'); if (t) focus(t);
    } catch (e) { if (tok === searchToken) renderError(box, e); }
  }
  $('#searchForm').addEventListener('submit', (e) => { e.preventDefault(); doSearch($('#searchInput').value); blurField(); });

  /* ---------- Réglages ---------- */
  $('#btnQuality').onclick = () => {
    const i = QUALITIES.indexOf(cfg.quality);
    const q = QUALITIES[(i + 1) % QUALITIES.length];
    setQuality(q);
  };
  function setQuality(q) {
    if (!QUALITIES.includes(q)) return;
    if (A) A.setQuality(q); else store.set('quality', q);
    loadCfg(); toast('Qualité maximale : ' + qLabel(q) + ', appliquée à la prochaine vidéo');
  }
  $('#btnUpdate').onclick = updateYtDlp;
  async function updateYtDlp() {
    if (MOCK) { toast('yt-dlp est à jour'); return; }
    $('#ytdlpStatus').textContent = 'Mise à jour de yt-dlp en cours…';
    try { const r = await native('updateYtDlp'); toast(r.message); }
    catch (e) { toast('Mise à jour impossible : ' + e.message); }
    loadCfg();
  }
  $('#btnClearCache').onclick = () => { store.keys().filter((k) => k.startsWith('cache:')).forEach(store.del); toast('Cache vidé'); };

  /* ---------- Lecteur (ExoPlayer natif sous la page) ---------- */
  let now = null, playing = false, shuffle = false, volume = 80, pstatus = 'idle', pos = { index: 0, count: 0 };
  function play(item, moodId, opts = {}) {
    if (!item) return;
    now = { type: item.type, id: item.id, title: item.title, channel: item.channel || '', thumb: item.thumb || '', mood: moodId || null, video: '' };
    store.set('last', now); refreshResume();
    shuffle = !!opts.shuffle;
    const m = moodId && moodById(moodId);
    $('#osdMood').textContent = m ? m.name : 'Ta sélection';
    $('#osdTitle').textContent = item.title;
    $('#loaderArt').innerHTML = sceneSVG(moodId || 'aurores');
    setStatus('resolving', item.type === 'playlist' ? 'Je récupère la playlist' : 'Je prépare la vidéo');
    show('player');
    focus($('#ctlToggle'));
    showOsd();
    updateLabels();
    if (MOCK) { setTimeout(() => onPlayer({ status: 'playing', title: item.title + ' (maquette)', index: 0, count: 8, shuffle }), 1200); return; }
    A.play(JSON.stringify({ type: item.type, id: item.id, title: item.title, shuffle }));
  }
  // Compteur de secondes pendant le chargement : on voit que ça avance
  let waitStart = 0, waitTimer = null;
  function setStatus(s, msg) {
    pstatus = s;
    const loading = s === 'resolving' || s === 'buffering';
    $('#loader').classList.toggle('show', loading || s === 'error');
    $('#loader').classList.toggle('err', s === 'error');
    if (msg) $('#loaderMsg').textContent = msg;
    if (loading && !waitTimer) {
      waitStart = Date.now();
      waitTimer = setInterval(() => {
        const sec = Math.round((Date.now() - waitStart) / 1000);
        $('#loaderTime').textContent = sec >= 5 ? sec + ' s' : '';
      }, 1000);
    } else if (!loading && waitTimer) {
      clearInterval(waitTimer); waitTimer = null; $('#loaderTime').textContent = '';
    }
  }
  // Appelé par le natif à chaque changement d'état du lecteur
  function onPlayer(p) {
    if (!p) return;
    if (p.status === 'error') { setStatus('error', p.message || 'Lecture impossible'); toast(p.message || 'Lecture impossible'); }
    else if (p.status === 'buffering') setStatus(playing ? 'playing' : 'buffering', p.message || 'Mise en mémoire');
    else if (p.status === 'resolving') setStatus('resolving', p.message || 'Je prépare la vidéo suivante');
    else setStatus(p.status);
    if (typeof p.playing === 'boolean') playing = p.playing; else if (p.status === 'playing') playing = true; else if (p.status === 'paused') playing = false;
    if (typeof p.shuffle === 'boolean') shuffle = p.shuffle;
    if (typeof p.index === 'number') pos = { index: p.index, count: p.count || 0 };
    if (p.title && now) {
      now.video = p.title;
      $('#osdTitle').textContent = p.title;
    }
    $('#osdPos').textContent = pos.count > 1 ? 'Vidéo ' + (pos.index + 1) + ' sur ' + pos.count : '';
    updateLabels(); report();
  }
  function stopPlayer() {
    if (A) A.control('stop', 0);
    playing = false; pstatus = 'idle'; clearSleep(); hideOsd(); setStatus('idle'); report();
  }
  function playerAct(a) {
    if (a === 'moods') { goHome(); return; }
    if (a === 'sleep') { cycleSleep(); return; }
    if (!now || screen !== 'player') return;
    if (a === 'shuffle') { shuffle = !shuffle; toast(shuffle ? 'Lecture aléatoire activée' : 'Lecture dans l’ordre'); }
    if (MOCK) { if (a === 'toggle') playing = !playing; updateLabels(); report(); return; }
    if (a === 'shuffle') A.control('shuffle', shuffle ? 1 : 0);
    else A.control(a, 0);
  }
  function setVolume(v) {
    volume = Math.max(0, Math.min(100, Math.round(v)));
    if (A) A.control('volume', volume);
    report();
  }
  function updateLabels() {
    $('#ctlToggle').textContent = playing ? 'Pause' : 'Lecture';
    $('#ctlShuffle').textContent = 'Aléatoire : ' + (shuffle ? 'oui' : 'non');
    updateSleepLabel();
  }
  document.querySelectorAll('.ctl').forEach((b) => b.addEventListener('click', () => { playerAct(b.dataset.act); armOsd(); }));

  /* Minuterie de mise en veille */
  const SLEEP_STEPS = [0, 30, 60, 90, 120];
  let sleepAt = 0, sleepTimer = null;
  function cycleSleep() {
    const left = sleepAt ? Math.round((sleepAt - Date.now()) / 60000) : 0;
    const next = SLEEP_STEPS.find((s) => s > left);
    setSleep(next === undefined ? 0 : next);
  }
  function setSleep(min) {
    clearSleep();
    if (min > 0) {
      sleepAt = Date.now() + min * 60000;
      sleepTimer = setTimeout(() => { goHome(); toast('Minuterie terminée, bonne nuit'); }, min * 60000);
      toast('Arrêt dans ' + min + ' min');
    }
    updateSleepLabel(); report();
  }
  function clearSleep() { clearTimeout(sleepTimer); sleepTimer = null; sleepAt = 0; }
  function updateSleepLabel() {
    const left = sleepAt ? Math.max(1, Math.round((sleepAt - Date.now()) / 60000)) : 0;
    $('#ctlSleep').textContent = 'Minuterie : ' + (left ? left + ' min' : 'non');
  }

  /* Bandeau d'infos du lecteur */
  let osdVisible = false, osdTimer = null;
  function showOsd() { osdVisible = true; $('#osd').classList.add('show'); if (!navEls().includes(cur)) focus($('#ctlToggle')); armOsd(); }
  function hideOsd() { osdVisible = false; $('#osd').classList.remove('show'); clearTimeout(osdTimer); }
  function armOsd() { clearTimeout(osdTimer); if (screen === 'player') osdTimer = setTimeout(hideOsd, 5000); }
  $('#veil').addEventListener('click', () => (osdVisible ? hideOsd() : showOsd()));

  /* ---------- Commandes de la télécommande téléphone ---------- */
  function parseYouTubeUrl(u) {
    try {
      const url = new URL(u);
      const list = url.searchParams.get('list');
      if (list) return { type: 'playlist', id: list, title: 'Playlist partagée' };
      const v = url.searchParams.get('v') || (url.hostname.includes('youtu.be') ? url.pathname.slice(1) : '');
      if (v) return { type: 'video', id: v, title: 'Vidéo partagée' };
    } catch (e) {}
    return null;
  }
  function onRemote(c) {
    if (!c || !c.a) return;
    switch (c.a) {
      case 'mood':
        if (c.play && currentMood === c.id && results.length) playMood(!!c.shuffle);
        else openMood(c.id, !!c.play, !!c.shuffle);
        break;
      case 'result': play(results[c.i], currentMood); break;
      case 'search': doSearch(c.q); break;
      case 'url': { const it = parseYouTubeUrl(c.url); if (it) play(it, null); else toast('Lien YouTube non reconnu'); break; }
      case 'volume': setVolume(c.v); break;
      case 'sleep': setSleep(+c.min || 0); break;
      case 'quality': setQuality(+c.h); break;
      case 'update': updateYtDlp(); break;
      case 'home': goHome(); break;
      case 'key': onKey(c.k); break;
      default: playerAct(c.a);
    }
  }

  /* ---------- État partagé avec la télécommande ---------- */
  let repTimer = null;
  function state() {
    const m = currentMood && moodById(currentMood);
    return {
      screen, mood: currentMood, moodName: m ? m.name : null,
      now: screen === 'player' ? now : null, status: pstatus, playing, shuffle, volume, pos,
      sleepLeft: sleepAt ? Math.max(1, Math.round((sleepAt - Date.now()) / 60000)) : 0,
      resultsFor, results: results.map((r) => ({ type: r.type, title: r.title, channel: r.channel, thumb: r.thumb, meta: r.meta || '' })),
      quality: cfg.quality, ytdlp: cfg.ytdlp, error: lastError
    };
  }
  function report() {
    clearTimeout(repTimer);
    repTimer = setTimeout(() => { if (A) A.reportState(JSON.stringify(state())); }, 120);
  }

  /* ---------- Petit message ---------- */
  let toastTimer = null;
  function toast(t) {
    const el = $('#toast'); el.textContent = t; el.classList.add('show');
    clearTimeout(toastTimer); toastTimer = setTimeout(() => el.classList.remove('show'), 2800);
  }

  /* ---------- Démarrage ---------- */
  $('#btnPlayMood').onclick = () => playMood(false);
  $('#btnShuffleMood').onclick = () => playMood(true);
  buildHome();
  loadCfg();
  tick(); setInterval(tick, 20000);
  show('home', false);
  const last = store.get('last');
  const startCard = last && last.mood && document.querySelector(`.card[data-mood="${last.mood}"]`);
  focus(startCard || document.querySelector('.card'));

  window.app = { onKey, onRemote, onNative, onPlayer, reloadConfig: loadCfg, toast, state };
})();
