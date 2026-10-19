// Minimal DASH (MPD) reader for static manifests: picks the best video and audio and lists their
// segment URLs. DRM-protected and live manifests are reported, never opened. Needs DOMParser.
var LodiDASH = (() => {
  const seconds = (iso) => {
    const m = /^P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:([\d.]+)S)?)?$/.exec(iso || '');
    return m ? (+m[1] || 0) * 86400 + (+m[2] || 0) * 3600 + (+m[3] || 0) * 60 + (+m[4] || 0) : 0;
  };
  const kids = (el, name) => (el ? [...el.children].filter((c) => c.localName === name) : []);
  const first = (el, name) => kids(el, name)[0];
  const base = (el, url) => {
    const b = first(el, 'BaseURL');
    return b ? new URL(b.textContent.trim(), url).href : url;
  };
  // $RepresentationID$, $Number$, $Number%05d$, $Time$, $Bandwidth$ and $$.
  const fill = (tpl, vars) =>
    tpl.replace(/\$(RepresentationID|Number|Time|Bandwidth)(?:%0(\d+)d)?\$|\$\$/g, (m, k, w) => (m === '$$' ? '$' : String(vars[k]).padStart(+w || 0, '0')));

  function track(rep, set, period, url, duration) {
    url = base(rep, base(set, url));
    const vars = { RepresentationID: rep.getAttribute('id'), Bandwidth: rep.getAttribute('bandwidth') };
    const tpls = [first(rep, 'SegmentTemplate'), first(set, 'SegmentTemplate'), first(period, 'SegmentTemplate')].filter(Boolean);
    if (tpls.length) {
      const attr = (n) => tpls.map((t) => t.getAttribute(n)).find((v) => v != null);
      const scale = +attr('timescale') || 1, start = +(attr('startNumber') ?? 1), media = attr('media');
      const timeline = tpls.map((t) => first(t, 'SegmentTimeline')).find(Boolean);
      const segments = [];
      if (timeline) {
        let t = 0, n = start;
        for (const s of kids(timeline, 'S')) {
          if (s.hasAttribute('t')) t = +s.getAttribute('t');
          const d = +s.getAttribute('d');
          let r = +(s.getAttribute('r') || 0);
          if (r < 0) r = Math.ceil((duration * scale - t) / d) - 1; // repeat to the end of the period
          for (let i = 0; i <= r; i++, t += d, n++) segments.push(new URL(fill(media, { ...vars, Number: n, Time: t }), url).href);
        }
      } else {
        const d = +attr('duration');
        for (let i = 0; i < Math.ceil((duration * scale) / d); i++) {
          segments.push(new URL(fill(media, { ...vars, Number: start + i, Time: i * d }), url).href);
        }
      }
      const init = attr('initialization');
      return { init: init ? new URL(fill(init, vars), url).href : null, segments };
    }
    const list = first(rep, 'SegmentList') || first(set, 'SegmentList');
    if (list) {
      const init = first(list, 'Initialization')?.getAttribute('sourceURL');
      return { init: init ? new URL(init, url).href : null, segments: kids(list, 'SegmentURL').map((s) => new URL(s.getAttribute('media'), url).href) };
    }
    return { init: null, segments: [url] }; // SegmentBase: the whole file at BaseURL
  }

  function parse(text, url) {
    const mpd = new DOMParser().parseFromString(text, 'application/xml').documentElement;
    if (!mpd || mpd.localName !== 'MPD') throw new Error('Not a DASH manifest');
    const period = first(mpd, 'Period');
    if (!period) throw new Error('The manifest has no period');
    const duration = seconds(period.getAttribute('duration')) || seconds(mpd.getAttribute('mediaPresentationDuration'));
    const root = base(period, base(mpd, url));
    const pick = (kind) => {
      let top = null;
      for (const set of kids(period, 'AdaptationSet')) {
        for (const rep of kids(set, 'Representation')) {
          const mime = rep.getAttribute('mimeType') || set.getAttribute('mimeType') || '';
          if ((set.getAttribute('contentType') || mime.split('/')[0]) !== kind) continue;
          const bandwidth = +rep.getAttribute('bandwidth') || 0;
          if (!top || bandwidth > top.bandwidth) {
            top = { set, rep, bandwidth, mime, drm: !!(first(set, 'ContentProtection') || first(rep, 'ContentProtection')) };
          }
        }
      }
      return top && { ...track(top.rep, top.set, period, root, duration), mime: top.mime, drm: top.drm };
    };
    const video = pick('video'), audio = pick('audio');
    return { live: mpd.getAttribute('type') === 'dynamic', drm: !!(video?.drm || audio?.drm), video, audio };
  }

  return { parse };
})();
