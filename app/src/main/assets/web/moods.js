/* Ambiance : catalogue des humeurs + petites scènes animées en SVG.
   Partagé entre l'écran TV et la télécommande. */
(function () {
  const MOODS = [
    { id: 'foret-pluie', name: 'Forêt sous la pluie', line: 'Pins, mousse et averse douce', q: 'rain in forest ambience 4k', tint: '#2f5d4a' },
    { id: 'chalet', name: 'Chalet en montagne', line: 'Neige dehors, bois qui craque', q: 'cozy mountain cabin ambience snow fireplace', tint: '#5a4a7a' },
    { id: 'iles', name: 'Îles paradisiaques', line: 'Lagon, palmiers, soleil bas', q: 'tropical island beach 4k relaxing waves', tint: '#2a8a8f' },
    { id: 'feu', name: 'Feu de bois', line: 'Braises et crépitements', q: 'fireplace crackling 4k ambience', tint: '#a4512a' },
    { id: 'orage', name: 'Orage nocturne', line: 'Tonnerre au loin, pluie lourde', q: 'thunderstorm night ambience rain', tint: '#3c4a66' },
    { id: 'neige', name: 'Neige qui tombe', line: 'Le monde au ralenti', q: 'snowfall ambience 4k winter', tint: '#5f7ea3' },
    { id: 'ocean', name: 'Océan au clair de lune', line: 'Vagues lentes sous la lune', q: 'ocean waves night moonlight 4k', tint: '#24476e' },
    { id: 'cafe', name: 'Café parisien', line: 'Tasses, murmures, pluie sur la vitre', q: 'paris cafe ambience rain jazz', tint: '#7a5236' },
    { id: 'aurores', name: 'Aurores boréales', line: 'Ciel vert sur la toundra', q: 'northern lights 4k relaxing', tint: '#2f7a62' },
    { id: 'bibliotheque', name: 'Bibliothèque ancienne', line: 'Pages, bougies, silence', q: 'old library ambience rain candles', tint: '#6b4a2e' }
  ];

  let uid = 0;
  function rng(seed) {
    let s = seed;
    return () => { s = (s * 9301 + 49297) % 233280; return s / 233280; };
  }

  // Lignes de pluie dupliquées sur deux hauteurs pour une boucle sans couture
  function rain(seed, n, color, op) {
    const r = rng(seed); let out = '';
    for (let i = 0; i < n; i++) {
      const x = r() * 180 - 10, y = r() * 100, l = 6 + r() * 6;
      for (const dy of [0, -100]) out += `<line x1="${x}" y1="${y + dy}" x2="${x - 2}" y2="${y + dy + l}" stroke="${color}" stroke-width="0.5" opacity="${op}"/>`;
    }
    return `<g class="sc-rain">${out}</g>`;
  }
  function snow(seed, n, op) {
    const r = rng(seed); let out = '';
    for (let i = 0; i < n; i++) {
      const x = r() * 160, y = r() * 100, s = 0.4 + r() * 0.9;
      for (const dy of [0, -100]) out += `<circle cx="${x}" cy="${y + dy}" r="${s}" fill="#fff" opacity="${op}"/>`;
    }
    return `<g class="sc-snow">${out}</g>`;
  }
  function stars(seed, n, maxY) {
    const r = rng(seed); let out = '';
    for (let i = 0; i < n; i++) out += `<circle cx="${r() * 160}" cy="${r() * maxY}" r="${0.2 + r() * 0.5}" fill="#fff" opacity="${0.3 + r() * 0.6}" class="${i % 3 === 0 ? 'sc-twinkle' : ''}"/>`;
    return out;
  }
  function pines(seed, n, baseY, h, color) {
    const r = rng(seed); let out = '';
    for (let i = 0; i < n; i++) {
      const x = i * (170 / n) - 5 + r() * 6, hh = h * (0.7 + r() * 0.5), w = hh * 0.38;
      out += `<polygon points="${x},${baseY - hh} ${x - w},${baseY} ${x + w},${baseY}" fill="${color}"/>`;
    }
    return out + `<rect x="0" y="${baseY - 0.5}" width="160" height="${100 - baseY + 1}" fill="${color}"/>`;
  }
  function grad(id, stops, vertical = true) {
    return `<linearGradient id="${id}" x1="0" y1="0" x2="${vertical ? 0 : 1}" y2="${vertical ? 1 : 0}">${stops.map((c, i) => `<stop offset="${i / (stops.length - 1)}" stop-color="${c}"/>`).join('')}</linearGradient>`;
  }

  const SCENES = {
    'foret-pluie': (u) => `
      <defs>${grad('g' + u, ['#1d3b36', '#3d6b5a', '#8fb39a'])}</defs>
      <rect width="160" height="100" fill="url(#g${u})"/>
      <g opacity=".35">${pines(3, 12, 78, 46, '#2c5246')}</g>
      <g opacity=".7">${pines(7, 9, 88, 52, '#1c3a31')}</g>
      ${pines(11, 7, 100, 60, '#10261f')}
      ${rain(5, 70, '#d8ecf0', 0.55)}`,
    chalet: (u) => `
      <defs>${grad('g' + u, ['#1b1934', '#3f3a66', '#8a7fae'])}<radialGradient id="w${u}"><stop offset="0" stop-color="#ffcc7a"/><stop offset="1" stop-color="#ffcc7a" stop-opacity="0"/></radialGradient></defs>
      <rect width="160" height="100" fill="url(#g${u})"/>${stars(2, 30, 40)}
      <polygon points="-10,80 35,28 62,58 92,22 135,62 170,40 170,100 -10,100" fill="#2a2748"/>
      <polygon points="35,28 27,38 33,36 38,40 44,35" fill="#e9e6f5"/><polygon points="92,22 83,33 90,31 96,35 101,30" fill="#e9e6f5"/>
      <path d="M-10 86 Q40 74 80 82 T170 78 V100 H-10Z" fill="#eef0f7"/>
      <polygon points="96,84 112,72 128,84" fill="#3b2a24"/><rect x="99" y="83" width="26" height="10" fill="#5a3e30"/>
      <rect x="108" y="86" width="6" height="5" fill="#ffc46b"/>
      <circle cx="111" cy="88" r="14" fill="url(#w${u})" class="sc-glow"/>
      ${snow(4, 40, 0.8)}`,
    iles: (u) => `
      <defs>${grad('g' + u, ['#2b7f93', '#f2b48a', '#ffd9a8'])}${grad('s' + u, ['#1d8a92', '#0e4f63'])}</defs>
      <rect width="160" height="66" fill="url(#g${u})"/>
      <circle cx="104" cy="60" r="13" fill="#ffe7b8" class="sc-glow"/>
      <rect y="64" width="160" height="36" fill="url(#s${u})"/>
      <g class="sc-sway"><line x1="0" y1="72" x2="160" y2="72" stroke="#ffe7b8" stroke-width=".6" opacity=".5"/><line x1="20" y1="80" x2="150" y2="80" stroke="#ffe7b8" stroke-width=".5" opacity=".35"/><line x1="70" y1="68" x2="140" y2="68" stroke="#ffe7b8" stroke-width=".8" opacity=".6"/></g>
      <path d="M8 66 Q30 54 58 66Z" fill="#0f3a3a"/>
      <path d="M33 62 Q35 46 31 36" stroke="#0f3a3a" stroke-width="1.6" fill="none"/>
      <g fill="#0f3a3a" class="sc-palm"><path d="M31 36 Q22 32 14 38 Q22 34 31 37Z"/><path d="M31 36 Q40 30 48 36 Q40 33 31 37Z"/><path d="M31 36 Q28 28 20 27 Q28 30 31 37Z"/><path d="M31 36 Q36 27 44 28 Q36 31 31 37Z"/></g>`,
    feu: (u) => `
      <defs><radialGradient id="r${u}" cx=".5" cy=".85" r=".7"><stop offset="0" stop-color="#ff9a3c"/><stop offset=".45" stop-color="#7a2e14"/><stop offset="1" stop-color="#1e0d08"/></radialGradient></defs>
      <rect width="160" height="100" fill="url(#r${u})" class="sc-glow"/>
      <g transform="translate(80 88)">
        <path class="sc-flame f1" d="M0 0 C-16 -6 -14 -30 -2 -48 C-4 -32 8 -30 6 -18 C14 -24 14 -6 0 0Z" fill="#ff7a1a"/>
        <path class="sc-flame f2" d="M2 0 C-10 -4 -10 -22 0 -34 C0 -22 10 -20 8 -10 C12 -12 10 -2 2 0Z" fill="#ffb347"/>
        <path class="sc-flame f3" d="M0 0 C-5 -3 -5 -12 0 -20 C1 -12 6 -10 4 -4 C5 -3 4 0 0 0Z" fill="#ffe2a0"/>
      </g>
      <rect x="52" y="86" width="56" height="7" rx="3.5" fill="#3a1d12" transform="rotate(-8 80 89)"/>
      <rect x="52" y="86" width="56" height="7" rx="3.5" fill="#4a2616" transform="rotate(9 80 89)"/>`,
    orage: (u) => `
      <defs>${grad('g' + u, ['#141a2a', '#2c3650', '#4a5470'])}</defs>
      <rect width="160" height="100" fill="url(#g${u})"/>
      <rect width="160" height="100" fill="#cfd9ff" class="sc-flash"/>
      <path d="M-10 30 Q10 14 32 22 Q46 6 70 16 Q92 4 112 18 Q136 8 172 26 V0 H-10Z" fill="#1a2033"/>
      <polyline class="sc-bolt" points="98,20 90,42 98,42 88,66" stroke="#eef3ff" stroke-width="1.4" fill="none"/>
      <path d="M-10 90 Q50 80 90 88 T170 84 V100 H-10Z" fill="#0d111c"/>
      ${rain(9, 90, '#b8c6e8', 0.45)}`,
    neige: (u) => `
      <defs>${grad('g' + u, ['#2c4466', '#6f8fb8', '#c9d7ea'])}</defs>
      <rect width="160" height="100" fill="url(#g${u})"/>
      <path d="M-10 74 Q40 60 90 70 T170 64 V100 H-10Z" fill="#dfe8f3"/>
      <path d="M-10 86 Q60 76 110 84 T170 82 V100 H-10Z" fill="#f4f7fb"/>
      <g opacity=".55">${pines(13, 6, 74, 22, '#3b5677')}</g>
      ${snow(8, 60, 0.95)}`,
    ocean: (u) => `
      <defs>${grad('g' + u, ['#0b1730', '#1d3a63', '#36598a'])}<radialGradient id="m${u}"><stop offset=".55" stop-color="#f3efe0"/><stop offset="1" stop-color="#f3efe0" stop-opacity="0"/></radialGradient></defs>
      <rect width="160" height="100" fill="url(#g${u})"/>${stars(6, 34, 50)}
      <circle cx="112" cy="28" r="16" fill="url(#m${u})" class="sc-glow"/>
      <rect y="62" width="160" height="38" fill="#0c1a33"/>
      <g class="sc-sway">${[66, 71, 77, 84, 92].map((y, i) => `<line x1="${112 - 6 - i * 3}" y1="${y}" x2="${112 + 6 + i * 3}" y2="${y}" stroke="#f3efe0" stroke-width=".9" opacity="${0.75 - i * 0.12}"/>`).join('')}</g>
      <path class="sc-wave" d="M-20 70 Q-10 67 0 70 T20 70 T40 70 T60 70 T80 70 T100 70 T120 70 T140 70 T160 70 T180 70" stroke="#5a7fb0" stroke-width=".6" fill="none" opacity=".6"/>
      <path class="sc-wave w2" d="M-20 82 Q-10 79 0 82 T20 82 T40 82 T60 82 T80 82 T100 82 T120 82 T140 82 T160 82 T180 82" stroke="#5a7fb0" stroke-width=".6" fill="none" opacity=".45"/>`,
    cafe: (u) => `
      <defs>${grad('g' + u, ['#3a2418', '#6b4229', '#a8744a'])}<radialGradient id="l${u}"><stop offset="0" stop-color="#ffd59a" stop-opacity=".9"/><stop offset="1" stop-color="#ffd59a" stop-opacity="0"/></radialGradient></defs>
      <rect width="160" height="100" fill="url(#g${u})"/>
      <rect x="14" y="10" width="62" height="58" rx="2" fill="#26344a"/><line x1="45" y1="10" x2="45" y2="68" stroke="#3a2418" stroke-width="2"/><line x1="14" y1="39" x2="76" y2="39" stroke="#3a2418" stroke-width="2"/>
      <clipPath id="k${u}"><rect x="14" y="10" width="62" height="58"/></clipPath>
      <g clip-path="url(#k${u})"><g opacity=".8">${rain(12, 22, '#9fb3cf', 0.6).replace('sc-rain', 'sc-rain slow')}</g></g>
      <circle cx="122" cy="20" r="26" fill="url(#l${u})" class="sc-glow"/>
      <rect y="78" width="160" height="22" fill="#2a1a12"/>
      <path d="M104 64 h26 v6 q0 10 -13 10 q-13 0 -13 -10Z" fill="#efe6d8"/><path d="M130 66 q7 0 7 5 q0 4 -7 4" stroke="#efe6d8" stroke-width="2" fill="none"/>
      <path class="sc-steam" d="M112 58 q-4 -6 0 -12 q4 -6 0 -12" stroke="#efe6d8" stroke-width="1.2" fill="none" opacity=".5"/>
      <path class="sc-steam s2" d="M121 58 q-4 -6 0 -12 q4 -6 0 -12" stroke="#efe6d8" stroke-width="1.2" fill="none" opacity=".4"/>`,
    aurores: (u) => `
      <defs>${grad('g' + u, ['#060f1c', '#10263a', '#1d3a46'])}<filter id="b${u}"><feGaussianBlur stdDeviation="3"/></filter></defs>
      <rect width="160" height="100" fill="url(#g${u})"/>${stars(10, 50, 70)}
      <g filter="url(#b${u})">
        <path class="sc-aurora" d="M-10 44 Q30 18 70 38 T170 26" stroke="#6ff0b4" stroke-width="10" fill="none" opacity=".75"/>
        <path class="sc-aurora a2" d="M-10 30 Q40 8 80 26 T170 14" stroke="#9a7cff" stroke-width="7" fill="none" opacity=".5"/>
      </g>
      <polygon points="-10,84 22,66 44,78 70,60 98,76 124,64 170,82 170,100 -10,100" fill="#08131c"/>`,
    bibliotheque: (u) => {
      const r = rng(21); let books = '';
      const cols = ['#7a2f2a', '#2f4a3c', '#8a6a2e', '#3a3f5c', '#5c2f45', '#9c8a6a'];
      for (const y of [10, 38, 66]) {
        let x = 6;
        while (x < 154) { const w = 3 + r() * 4, h = 18 + r() * 6; books += `<rect x="${x}" y="${y + 24 - h}" width="${w}" height="${h}" fill="${cols[Math.floor(r() * cols.length)]}"/>`; x += w + 0.6; }
        books += `<rect x="0" y="${y + 24}" width="160" height="3" fill="#2a1a10"/>`;
      }
      return `<defs><radialGradient id="c${u}" cx=".78" cy=".8" r=".55"><stop offset="0" stop-color="#ffbe6a" stop-opacity=".55"/><stop offset="1" stop-color="#ffbe6a" stop-opacity="0"/></radialGradient></defs>
      <rect width="160" height="100" fill="#3a2516"/>${books}
      <rect width="160" height="100" fill="#120a05" opacity=".35"/>
      <rect width="160" height="100" fill="url(#c${u})" class="sc-glow"/>
      <rect x="122" y="80" width="5" height="12" fill="#efe2c4"/><path class="sc-flame f3" d="M124.5 80 q-3 -4 0 -9 q3 5 0 9Z" fill="#ffd27a"/>`;
    }
  };

  function sceneSVG(id) {
    const u = 'm' + (uid++);
    const body = (SCENES[id] || SCENES.feu)(u);
    return `<svg class="scene" viewBox="0 0 160 100" preserveAspectRatio="xMidYMid slice" aria-hidden="true">${body}</svg>`;
  }

  const css = `
  .scene{display:block;width:100%;height:100%}
  .scene *{animation-play-state:paused}
  .alive .scene *{animation-play-state:running}
  .sc-rain{animation:scRain .7s linear infinite}
  .sc-rain.slow{animation-duration:2.4s}
  .sc-snow{animation:scSnow 9s linear infinite}
  .sc-glow{animation:scGlow 3.2s ease-in-out infinite}
  .sc-flame{transform-box:fill-box;transform-origin:50% 100%;animation:scFlame .9s ease-in-out infinite alternate}
  .sc-flame.f2{animation-duration:.7s;animation-delay:-.3s}.sc-flame.f3{animation-duration:.5s}
  .sc-flash{opacity:0;animation:scFlash 5s linear infinite}.sc-bolt{opacity:0;animation:scFlash 5s linear infinite}
  .sc-sway{animation:scSway 4s ease-in-out infinite alternate}
  .sc-wave{animation:scWave 6s linear infinite}.sc-wave.w2{animation-duration:9s}
  .sc-steam{animation:scSteam 3s ease-in-out infinite}.sc-steam.s2{animation-delay:-1.4s}
  .sc-aurora{animation:scAur 7s ease-in-out infinite alternate}.sc-aurora.a2{animation-duration:9s}
  .sc-twinkle{animation:scGlow 2.6s ease-in-out infinite}
  .sc-palm{transform-box:fill-box;transform-origin:50% 100%;animation:scPalm 3s ease-in-out infinite alternate}
  @keyframes scRain{to{transform:translate(-4px,100px)}}
  @keyframes scSnow{0%{transform:translate(0,0)}50%{transform:translate(3px,50px)}100%{transform:translate(0,100px)}}
  @keyframes scGlow{0%,100%{opacity:.85}50%{opacity:1}}
  @keyframes scFlame{from{transform:scale(1,1) skewX(0)}to{transform:scale(.92,1.12) skewX(3deg)}}
  @keyframes scFlash{0%,88%,93%,100%{opacity:0}89%{opacity:.55}91%{opacity:.1}92%{opacity:.4}}
  @keyframes scSway{from{transform:translateX(-2px)}to{transform:translateX(2px)}}
  @keyframes scWave{to{transform:translateX(-20px)}}
  @keyframes scSteam{0%{transform:translateY(0);opacity:0}30%{opacity:.5}100%{transform:translateY(-10px);opacity:0}}
  @keyframes scAur{from{transform:translate(-6px,0) scaleY(1)}to{transform:translate(6px,4px) scaleY(1.2)}}
  @keyframes scPalm{from{transform:rotate(-3deg)}to{transform:rotate(3deg)}}
  @media (prefers-reduced-motion: reduce){.alive .scene *{animation-play-state:paused}}`;
  const st = document.createElement('style'); st.textContent = css; document.head.appendChild(st);

  window.Ambiance = { MOODS, sceneSVG, mood: (id) => MOODS.find((m) => m.id === id) };
})();
