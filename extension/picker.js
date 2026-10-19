// The hover picker. Hover a photo or video and a round checkbox appears at its top right. Tick
// several and the bar at the bottom saves them all at once, as original files. Runs in every frame:
// the top frame shows the bar, and the service worker relays picks from embedded frames to it.
(() => {
  if (globalThis.__lodiPicker || !globalThis.__lodiItemFor) return;
  globalThis.__lodiPicker = true;
  const TOP = window === window.top;
  const MIN = 64; // px; smaller media (icons, avatars) gets no checkbox
  const MAX = 100;
  const COPY = {
    en: { pick: 'Pick', unpick: 'Unpick', picked: (n) => `${n} picked`, save: (n) => `Save ${n}`, saving: (i, n) => `Saving… ${i}/${n}`, done: (n) => `Saved ${n}!`, failed: (n) => `${n} failed`, partly: (n) => `${n} only partly loaded`, show: 'Show', clear: 'Clear' },
    tl: { pick: 'Piliin', unpick: 'Alisin', picked: (n) => `${n} napili`, save: (n) => `I-save ang ${n}`, saving: (i, n) => `Sine-save… ${i}/${n}`, done: (n) => `Na-save ang ${n}!`, failed: (n) => `${n} pumalya`, partly: (n) => `${n} hindi buo`, show: 'Ipakita', clear: 'Burahin' },
  };
  const prefs = { hover: true, lang: 'en', motion: 'on' };
  const say = (k, ...a) => { const v = COPY[prefs.lang === 'tl' ? 'tl' : 'en'][k]; return typeof v === 'function' ? v(...a) : v };
  const CHECK = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M5 12.5l4.5 4.5L19 7.5"/></svg>';
  const LOCK = '<svg viewBox="0 0 24 24" aria-hidden="true"><rect x="5.5" y="11" width="13" height="9" rx="2"/><path d="M8.5 11V8.5a3.5 3.5 0 0 1 7 0V11"/></svg>';
  const FACE = '<svg class="face" viewBox="20 22 68 68" aria-hidden="true"><defs><radialGradient id="f" cx=".38" cy=".3" r=".78"><stop offset="0" stop-color="#F8DDB8"/><stop offset=".55" stop-color="#E8B37A"/><stop offset="1" stop-color="#C98E52"/></radialGradient></defs><circle cx="36" cy="36" r="9" fill="url(#f)"/><circle cx="72" cy="36" r="9" fill="url(#f)"/><path d="M54 30C72 30 84 42 84 60C84 76 71 86 54 86C37 86 24 76 24 60C24 42 36 30 54 30Z" fill="url(#f)"/><ellipse cx="54" cy="73" rx="15" ry="10" fill="#FBEBD6"/><g class="cheeks"><ellipse cx="29" cy="66" rx="11" ry="10" fill="#F3CFA3"/><ellipse cx="79" cy="66" rx="11" ry="10" fill="#F3CFA3"/></g><circle cx="44" cy="56" r="5" fill="#141414"/><circle cx="64" cy="56" r="5" fill="#141414"/><circle cx="45.8" cy="54" r="1.8" fill="#fff"/><circle cx="65.8" cy="54" r="1.8" fill="#fff"/><path d="M51 64.6Q54 63 57 64.6Q55.8 67.6 54 68Q52.2 67.6 51 64.6Z" fill="#EE7A6E"/></svg>';
  const FLOAT = 'linear(0,0.034,0.119,0.236,0.373,0.515,0.649,0.774,0.883,0.969,1.038,1.088,1.119,1.135,1.139,1.134,1.121,1.104,1.086,1.066,1.047,1.03,1.015,1.003,0.994,0.987,0.983,0.981,0.981,0.982,0.983,0.986,0.988,0.991,0.994,1)';
  const SNAP = 'linear(0,0.058,0.182,0.32,0.459,0.582,0.681,0.764,0.829,0.876,0.913,0.94,0.959,0.972,0.982,0.988,0.993,1)';

  // The overlay lives in a shadow root, so page styles can't reach it, and covers the viewport
  // without catching the pointer except on its own buttons.
  const host = document.createElement('lodisave-picker');
  for (const [k, v] of Object.entries({ all: 'initial', position: 'fixed', inset: '0', 'pointer-events': 'none', 'z-index': '2147483647', display: 'block' })) host.style.setProperty(k, v, 'important');
  const root = host.attachShadow({ mode: 'open' });
  root.innerHTML = `<style>
    :host { color-scheme: dark }
    * { box-sizing: border-box }
    .check { position: absolute; width: 28px; height: 28px; padding: 0; display: grid; place-items: center; border: 2px solid #fff; border-radius: 50%;
      background: rgb(15 15 16 / .42); color: transparent; box-shadow: 0 2px 10px rgb(0 0 0 / .35); cursor: pointer; pointer-events: auto;
      transition: transform .222s ${SNAP}, background-color .222s ${SNAP}, color .222s ${SNAP}; animation: pop .678s ${FLOAT} both }
    .check::before { content: ''; position: absolute; inset: -10px; border-radius: 50% }
    .check:hover { transform: scale(1.12) }
    .check svg { pointer-events: none; width: 15px; height: 15px; fill: none; stroke: currentColor; stroke-width: 3.2; stroke-linecap: round; stroke-linejoin: round }
    .check.on { background: #FF8A3D; color: #0F0F10 }
    .check.lock { background: rgb(15 15 16 / .78); color: #F4F3F1; cursor: help }
    .check.lock svg { stroke-width: 2.2 }
    .ring { position: absolute; border-radius: 10px; box-shadow: 0 0 0 3px #FF8A3D, 0 0 0 7px rgb(255 138 61 / .22); pointer-events: none; animation: ring .632s ${FLOAT} both }
    .bar { position: fixed; left: 50%; bottom: 20px; display: flex; align-items: center; gap: 10px; padding: 7px 7px 7px 10px; border-radius: 999px;
      background: #18181A; color: #F4F3F1; box-shadow: 0 18px 44px rgb(0 0 0 / .45), 0 0 0 1px #2C2C2F; pointer-events: auto; transform: translateX(-50%);
      font: 600 14px/1.2 'Segoe UI Variable Text', 'Segoe UI', system-ui, sans-serif; animation: rise .678s ${FLOAT} both; white-space: nowrap }
    .bar[hidden] { display: none }
    .lodi { position: relative; display: grid; flex: none }
    .face { width: 34px; height: 34px; animation: bob 3.6s ease-in-out infinite }
    .bar[data-state="saving"] .face { animation: squish 1.2s ease-in-out infinite }
    .bar[data-state="saving"] .face.chomp { animation: chomp .36s ${SNAP}, squish 1.2s ease-in-out .36s infinite }
    .bar[data-state="done"] .face { animation: hop .7s ${FLOAT} }
    .cheeks ellipse { transform-origin: center; transform-box: fill-box; transition: transform .678s ${FLOAT} }
    .bar[data-state="saving"] .cheeks ellipse { transform: scale(calc(1 + var(--fill, 0) * .3)) }
    .burst i { position: absolute; left: 50%; top: 50%; width: 5px; height: 5px; margin: -2.5px; border-radius: 50%; background: #FF8A3D; opacity: 0; pointer-events: none }
    .burst i:nth-child(even) { background: #F8DDB8; width: 4px; height: 4px; margin: -2px }
    .bar[data-state="done"] .burst i { animation: burst .72s cubic-bezier(.2, .7, .3, 1) both }
    .tray { display: flex; align-items: center; padding-left: 2px }
    .bar:not([data-state="saving"]) .tray { display: none }
    .t { position: relative; width: 30px; height: 30px; flex: none; margin-left: -9px; border-radius: 9px; background: #2C2C2F; box-shadow: 0 0 0 2px #18181A;
      animation: deal .5s ${FLOAT} both; animation-delay: calc(var(--i) * 45ms); transition: box-shadow .222s ${SNAP} }
    .t:first-child { margin-left: 0 }
    .t img { display: block; width: 100%; height: 100%; object-fit: cover; border-radius: inherit }
    .t::after { content: ''; position: absolute; inset: 0; border-radius: inherit; background: linear-gradient(100deg, transparent 30%, rgb(255 255 255 / .22) 50%, transparent 70%) 0 0 / 250% 100%;
      animation: shine 1.1s linear infinite; animation-delay: calc(var(--i) * 120ms) }
    .t.ok { box-shadow: 0 0 0 2px #FF8A3D; animation: gulp .62s ${FLOAT} forwards }
    .t.bad { box-shadow: 0 0 0 2px #FF5A4E; animation: shake .42s ${SNAP} }
    .t.ok::after, .t.bad::after { animation: none; background: none }
    .t.bad::before { content: '!'; position: absolute; z-index: 1; right: -4px; top: -4px; width: 14px; height: 14px; display: grid; place-items: center; border-radius: 50%;
      background: #FF5A4E; color: #0F0F10; font: 800 10px/1 system-ui }
    .still .t.ok { opacity: .3 }
    .more { margin-left: 6px; color: #A3A19C; font: 600 12px/1 ui-monospace, 'Cascadia Mono', monospace }
    .line { position: absolute; left: 26px; right: 26px; bottom: 2px; height: 2px; border-radius: 2px; background: #FF8A3D; opacity: 0;
      transform: scaleX(var(--fill, 0)); transform-origin: left; transition: transform .678s ${FLOAT}, opacity .222s ${SNAP} }
    .bar[data-state="saving"] .line { opacity: 1 }
    .text { padding-right: 4px }
    .note { color: #A3A19C; font-weight: 400 }
    .bar button { font: inherit; border: 0; cursor: pointer; transition: transform .222s ${SNAP} }
    .bar button:active { transform: scale(.94) }
    .save { min-height: 40px; padding: 0 18px; border-radius: 999px; background: #FF8A3D; color: #0F0F10 }
    .save[hidden], .x[hidden] { display: none }
    .x { width: 40px; height: 40px; border-radius: 50%; background: #202023; color: #F4F3F1; font-size: 18px; line-height: 1 }
    .still * { animation: none !important; transition: none !important }
    @keyframes pop { from { opacity: 0; transform: scale(.5) } }
    @keyframes ring { from { opacity: 0; transform: scale(1.04) } }
    @keyframes rise { from { opacity: 0; transform: translate(-50%, 24px) scale(.96) } }
    @keyframes bob { 50% { transform: translateY(-2px) } }
    @keyframes squish { 0%, 100% { transform: scale(1.04, .96) } 50% { transform: scale(.97, 1.03) } }
    @keyframes chomp { 40% { transform: scale(1.16, .86) } }
    @keyframes hop { 30% { transform: translateY(-7px) rotate(-7deg) } 60% { transform: translateY(0) rotate(3deg) } }
    @keyframes burst { 0% { opacity: 1; transform: rotate(var(--a)) translateY(-8px) scale(1) } 100% { opacity: 0; transform: rotate(var(--a)) translateY(-28px) scale(.3) } }
    @keyframes deal { from { opacity: 0; transform: translate(-14px, 10px) rotate(-12deg) scale(.6) } }
    @keyframes shine { from { background-position: 150% 0 } to { background-position: -100% 0 } }
    @keyframes gulp { 30% { transform: translateY(-5px) scale(1.12) } 100% { opacity: 0; transform: translate(var(--dx, 0), 2px) scale(.15) } }
    @keyframes shake { 25% { transform: translateX(-3px) } 50% { transform: translateX(3px) } 75% { transform: translateX(-2px) } }
  </style><div class="layer"></div>`;
  const layer = root.querySelector('.layer');
  const hoverBox = Object.assign(document.createElement('button'), { className: 'check', type: 'button' });
  hoverBox.dataset.act = 'toggle';

  // Media under the point: the hit-testable stack first, then media hidden under an overlay or
  // behind pointer-events: none.
  const big = (r) => r.width >= MIN && r.height >= MIN;
  const isMedia = (el) => {
    const tag = el.localName;
    if (tag === 'img' || tag === 'video' || tag === 'canvas') return true;
    if (tag === 'svg') return !el.parentElement?.closest('svg');
    if (tag === 'html' || tag === 'body') return false;
    return /url\(/.test(getComputedStyle(el).backgroundImage);
  };
  function mediaAt(x, y) {
    for (const el of document.elementsFromPoint(x, y).slice(0, 12)) {
      if (el !== host && isMedia(el) && big(el.getBoundingClientRect())) return el;
    }
    let found = null, area = Infinity;
    for (const el of document.querySelectorAll('img,video,canvas')) {
      const r = el.getBoundingClientRect();
      if (big(r) && x >= r.left && x <= r.right && y >= r.top && y <= r.bottom && r.width * r.height < area) {
        found = el;
        area = r.width * r.height;
      }
    }
    return found;
  }

  // Keyed by what gets saved, not by element: sites like Instagram rebuild their elements when you go
  // back, so a pick follows its media to the new element instead of being picked a second time.
  const picked = new Map(); // item.url -> { item, el, src, thumb }
  let hovered = null; // { el, item }
  const srcOf = (el) => el.currentSrc || el.src || '';
  const shows = (p) => p.el.isConnected && (!p.src || srcOf(p.el) === p.src);
  const pickOf = (el) => [...picked.values()].find((p) => p.el === el && shows(p));

  // A small picture of the pick for the saving bar.
  function thumbOf(el) {
    try {
      if (el.localName === 'img') return srcOf(el);
      if (el.localName === 'video' && el.poster) return el.poster;
      if (el.localName === 'video' || el.localName === 'canvas') {
        const w = el.videoWidth || el.width, h = el.videoHeight || el.height;
        const c = Object.assign(document.createElement('canvas'), { width: 96, height: Math.max(1, Math.round(96 * h / w) || 96) });
        c.getContext('2d').drawImage(el, 0, 0, c.width, c.height);
        return c.toDataURL('image/jpeg', 0.7); // throws on a cross-origin video, which gets a plain tile
      }
      return /url\(["']?(.*?)["']?\)/.exec(getComputedStyle(el).backgroundImage)?.[1] || '';
    } catch { return '' }
  }

  function report() {
    chrome.runtime.sendMessage({ type: 'picked', count: picked.size }).catch(() => {});
  }

  function toggle(el, item) {
    const mine = pickOf(el);
    if (mine) picked.delete(mine.item.url);
    else if (item && picked.has(item.url)) Object.assign(picked.get(item.url), { el, src: srcOf(el) });
    else if (item && !item.unsupported && picked.size < MAX) picked.set(item.url, { item, el, src: srcOf(el), thumb: thumbOf(el) });
    report();
    render();
  }

  // Finds the new element for picks whose element the page replaced or reused for other media.
  // ponytail: matches by src only, so a lost canvas or CSS background stays unhighlighted (it still saves)
  function rebind() {
    let bySrc = null;
    for (const p of picked.values()) {
      if (shows(p) || !p.src) continue;
      bySrc ??= new Map([...document.querySelectorAll('img,video')].map((el) => [srcOf(el), el]));
      const el = bySrc.get(p.src);
      if (el) p.el = el;
    }
  }

  // The visible part of an element, or null when it is off screen or gone.
  function boxOf(el) {
    if (!el.isConnected) return null;
    const r = el.getBoundingClientRect();
    const left = Math.max(r.left, 0), top = Math.max(r.top, 0);
    const right = Math.min(r.right, innerWidth), bottom = Math.min(r.bottom, innerHeight);
    return right - left >= 24 && bottom - top >= 24 ? { left, top, width: right - left, height: bottom - top } : null;
  }
  const place = (node, b, size) => Object.assign(node.style, b ? { left: `${b.left + b.width - size - 8}px`, top: `${b.top + 8}px` } : {});

  // The host joins the page only once there is something to show, after the page has built itself.
  const ensure = () => { if (!host.isConnected) document.documentElement.append(host) };

  const pool = [];
  function render() {
    layer.classList.toggle('still', prefs.motion === 'off');
    // One ring and checked box per visible pick. A recycled element showing other media gets none.
    let n = 0;
    rebind();
    for (const p of picked.values()) {
      const el = p.el, b = shows(p) && boxOf(el);
      if (!b) continue;
      let slot = pool[n];
      if (!slot) {
        slot = { ring: Object.assign(document.createElement('div'), { className: 'ring' }), box: Object.assign(document.createElement('button'), { className: 'check on', type: 'button', innerHTML: CHECK }) };
        slot.box.dataset.act = 'toggle';
        pool.push(slot);
      }
      Object.assign(slot.ring.style, { left: `${b.left}px`, top: `${b.top}px`, width: `${b.width}px`, height: `${b.height}px` });
      place(slot.box, b, 28);
      slot.box.title = say('unpick');
      slot.el = el;
      ensure();
      if (!slot.ring.isConnected) layer.append(slot.ring, slot.box);
      n++;
    }
    for (const slot of pool.slice(n)) { slot.ring.remove(); slot.box.remove(); slot.el = null }

    const h = prefs.hover && hovered && !pickOf(hovered.el) && boxOf(hovered.el);
    if (h) {
      const lock = !!hovered.item?.unsupported;
      hoverBox.className = lock ? 'check lock' : 'check';
      // Only on change: replacing the icon mid-press would cancel the click.
      if (hoverBox.dataset.icon !== String(lock)) { hoverBox.dataset.icon = lock; hoverBox.innerHTML = lock ? LOCK : CHECK }
      hoverBox.title = lock ? hovered.item.unsupported : say('pick');
      place(hoverBox, h, 28);
      ensure();
      if (!hoverBox.isConnected) layer.append(hoverBox);
    } else hoverBox.remove();
    if (n || h) schedule();
  }

  // Positions follow scrolling and layout changes while anything is on screen.
  let frame = 0;
  const schedule = () => { if (!frame) frame = requestAnimationFrame(() => { frame = 0; render() }) };

  let px = -1, py = -1, moved = false;
  function onMove() {
    moved = false;
    if (!prefs.hover) return;
    const r = hovered?.el.isConnected && hovered.el.getBoundingClientRect();
    if (r && px >= r.left && px <= r.right && py >= r.top && py <= r.bottom) return;
    const el = mediaAt(px, py);
    if (el !== hovered?.el) {
      hovered = el ? { el, item: globalThis.__lodiItemFor(el) } : null;
      // Media picked before, now on an element the page rebuilt: show it picked again.
      const p = hovered?.item && picked.get(hovered.item.url);
      if (p && !shows(p)) Object.assign(p, { el, src: srcOf(el) });
    }
    render();
  }
  window.addEventListener('pointermove', (e) => {
    px = e.clientX;
    py = e.clientY;
    if (!moved) { moved = true; requestAnimationFrame(onMove) }
  }, { capture: true, passive: true });
  document.addEventListener('mouseout', (e) => {
    if (!e.relatedTarget && hovered) { hovered = null; render() }
  }, true);
  window.addEventListener('scroll', schedule, { capture: true, passive: true });

  // Clicks on the overlay never reach the page. Registered at document_start, so this runs first.
  const guard = (e) => {
    if (e.target !== host) return;
    e.stopImmediatePropagation();
    e.preventDefault();
    if (e.type !== 'click') return;
    const btn = e.composedPath()[0]?.closest?.('[data-act]');
    if (!btn) return;
    if (btn === hoverBox && hovered) toggle(hovered.el, hovered.item);
    else if (btn.dataset.act === 'toggle') { const slot = pool.find((s) => s.box === btn); if (slot?.el) toggle(slot.el) }
    else if (btn.dataset.act === 'save') saveAll();
    else if (btn.dataset.act === 'clear') chrome.runtime.sendMessage({ type: 'clear-picked' }).catch(() => {});
    else if (btn.dataset.act === 'show' && bar.last) chrome.runtime.sendMessage({ type: 'show-download', id: bar.last }).catch(() => {});
  };
  for (const t of ['pointerdown', 'pointerup', 'mousedown', 'mouseup', 'click', 'dblclick', 'auxclick', 'contextmenu']) window.addEventListener(t, guard, true);

  // The bar, top frame only. It counts the picks of every frame in the tab.
  const counts = new Map(); // frameId -> picks
  const bar = { node: null, state: 'pick', progress: null, done: null, last: null, timer: 0 };
  function paintBar() {
    if (!TOP) return;
    const total = [...counts.values()].reduce((a, b) => a + b, 0);
    if (!bar.node) {
      bar.node = Object.assign(document.createElement('div'), { className: 'bar', hidden: true });
      const burst = [0, 60, 120, 180, 240, 300].map((a) => `<i style="--a:${a}deg"></i>`).join('');
      bar.node.innerHTML = `<span class="lodi">${FACE}<span class="burst">${burst}</span></span><span class="tray"></span><span class="text"></span><button type="button" class="save" data-act="save"></button><button type="button" class="x" data-act="clear">×</button><i class="line"></i>`;
      layer.append(bar.node);
    }
    if (!bar.node.isConnected) layer.append(bar.node);
    const show = bar.state !== 'pick' || total > 0;
    if (show) ensure();
    bar.node.hidden = !show;
    bar.node.dataset.state = bar.state;
    host.dataset.state = show ? bar.state : 'idle';
    host.dataset.total = total;
    const text = bar.node.querySelector('.text'), save = bar.node.querySelector('.save'), x = bar.node.querySelector('.x');
    x.title = say('clear');
    x.hidden = bar.state !== 'pick';
    if (bar.state === 'pick') {
      text.textContent = say('picked', total);
      save.hidden = false;
      save.dataset.act = 'save';
      save.textContent = say('save', total);
    } else if (bar.state === 'saving') {
      text.textContent = say('saving', ...(bar.progress || [0, total]));
      bar.node.style.setProperty('--fill', bar.progress ? bar.progress[0] / bar.progress[1] : 0);
      save.hidden = true;
    } else {
      const { ok, bad, warn } = bar.done;
      const notes = [bad && say('failed', bad), warn && say('partly', warn)].filter(Boolean).join(' · ');
      text.innerHTML = '';
      text.append(say('done', ok), ...(notes ? [' ', Object.assign(document.createElement('span'), { className: 'note', textContent: notes })] : []));
      save.hidden = !bar.last;
      save.dataset.act = 'show';
      save.textContent = say('show');
    }
    render();
  }

  // The saving tray: up to six picks deal into the bar, then Lodi stuffs each one into his cheeks as it saves.
  const SHOWN = 6;
  function startTray(thumbs) {
    const tray = bar.node.querySelector('.tray');
    tray.replaceChildren(...thumbs.slice(0, SHOWN).map((src, i) => {
      const t = Object.assign(document.createElement('span'), { className: 't' });
      t.style.setProperty('--i', i);
      if (src) t.append(Object.assign(document.createElement('img'), { src, alt: '', referrerPolicy: 'no-referrer', onerror() { this.remove() } }));
      return t;
    }));
    if (thumbs.length > SHOWN) tray.append(Object.assign(document.createElement('span'), { className: 'more', textContent: `+${thumbs.length - SHOWN}` }));
    bar.progress = [0, thumbs.length];
    paintBar();
  }
  function landTray(i, ok) {
    if (!bar.progress) return;
    bar.progress = [bar.progress[0] + 1, bar.progress[1]];
    const t = bar.node.querySelectorAll('.tray .t')[i];
    const face = bar.node.querySelector('.face');
    if (t) {
      const f = face.getBoundingClientRect(), r = t.getBoundingClientRect();
      t.style.setProperty('--dx', `${f.left + f.width / 2 - r.left - r.width / 2}px`);
      t.classList.add(ok ? 'ok' : 'bad');
    }
    if (ok) { face.classList.remove('chomp'); void face.getBoundingClientRect(); face.classList.add('chomp') }
    paintBar();
  }

  async function saveAll() {
    bar.state = 'saving';
    bar.progress = null;
    bar.node.querySelector('.tray').replaceChildren();
    paintBar();
    const frames = [...counts].filter(([, n]) => n > 0).map(([f]) => f);
    const r = await chrome.runtime.sendMessage({ type: 'save-picked', frames }).catch((e) => ({ ok: 0, bad: 1, error: e.message }));
    if (prefs.motion !== 'off') await new Promise((done) => setTimeout(done, 700)); // let the last one land in Lodi's cheeks
    counts.clear();
    bar.state = 'done';
    bar.done = r;
    bar.last = r.last;
    paintBar();
    clearTimeout(bar.timer);
    bar.timer = setTimeout(() => { bar.state = 'pick'; paintBar() }, 6000);
  }

  chrome.runtime.onMessage.addListener((msg, _sender, reply) => {
    if (msg.type === 'take-picked') reply([...picked.values()].map((p) => ({ ...p.item, thumb: p.thumb })));
    else if (msg.type === 'clear-picked') {
      picked.clear();
      if (TOP) { counts.clear(); if (bar.state === 'pick') paintBar() }
      render();
    } else if (TOP && msg.type === 'frame-picked') {
      counts.set(msg.frameId, msg.count);
      if (bar.state !== 'saving') { bar.state = 'pick'; clearTimeout(bar.timer) }
      paintBar();
    } else if (TOP && msg.type === 'bar-start') startTray(msg.thumbs);
    else if (TOP && msg.type === 'bar-item') landTray(msg.i, msg.ok);
  });

  chrome.storage.local.get(['hover', 'lang', 'motion']).then((p) => { Object.assign(prefs, p); render() }).catch(() => {});
  chrome.storage.onChanged.addListener((c) => {
    for (const k of Object.keys(c)) prefs[k] = c[k].newValue;
    if (prefs.hover === false) hovered = null;
    if (TOP && bar.node) paintBar();
    render();
  });
})();
