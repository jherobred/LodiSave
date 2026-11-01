// Shared by the content scripts and the service worker.
var LodiShared = (() => {
  // The Chrome Web Store bans YouTube downloading, so the extension never touches these hosts.
  const YOUTUBE = /(^|\.)(youtube\.com|youtu\.be|youtube-nocookie\.com|googlevideo\.com|ytimg\.com)$/i;
  const isYouTube = (url) => {
    try { return YOUTUBE.test(new URL(url).hostname) } catch { return false }
  };

  const EXT = {
    'image/jpeg': 'jpg', 'image/png': 'png', 'image/gif': 'gif', 'image/webp': 'webp', 'image/avif': 'avif',
    'image/svg+xml': 'svg', 'video/mp4': 'mp4', 'video/webm': 'webm', 'video/mp2t': 'ts',
    'audio/mp4': 'm4a', 'audio/webm': 'weba', 'audio/mpeg': 'mp3', 'audio/aac': 'aac',
  };

  // Social apps by host, named in saved files. Other sites stay unnamed.
  const APPS = [
    [/(^|\.)instagram\.com$/, 'Instagram'], [/(^|\.)threads\.(net|com)$/, 'Threads'], [/(^|\.)(facebook\.com|fb\.watch)$/, 'Facebook'],
    [/(^|\.)(x|twitter)\.com$/, 'X'], [/(^|\.)tiktok\.com$/, 'TikTok'], [/(^|\.)(reddit\.com|redd\.it)$/, 'Reddit'],
    [/(^|\.)(pinterest\.[a-z.]+|pin\.it)$/, 'Pinterest'], [/(^|\.)snapchat\.com$/, 'Snapchat'], [/(^|\.)(youtube\.com|youtu\.be)$/, 'YouTube'],
    [/(^|\.)tumblr\.com$/, 'Tumblr'], [/(^|\.)bsky\.app$/, 'Bluesky'], [/(^|\.)twitch\.tv$/, 'Twitch'], [/(^|\.)linkedin\.com$/, 'LinkedIn'],
  ];
  const appOf = (url) => {
    try { return APPS.find(([re]) => re.test(new URL(url).hostname))?.[1] || '' } catch { return '' }
  };
  // A handle from a profile path, /name or /@name, skipping the app's own pages.
  const NOT_USER = /^(p|reel|reels|tv|explore|stories|i|home|search|hashtag|watch|status|photo|video|videos|share|settings|notifications|messages|direct|accounts|about|login|signup|intent|compose|groups|pages|marketplace|gaming|r|u|t|pin|ideas|following|foryou|live|friends|profile|discover|tag|channel|c|shorts|feed)$/i;
  const handle = (path) => {
    const m = /^\/@?([\w.]{2,30})(\/|$)/.exec(path || '');
    return m && !NOT_USER.test(m[1]) ? m[1] : '';
  };
  // "@who · App · rest": who posted it and where, first, so saves sort and read by person.
  function label(item, rest) {
    const app = appOf(item.page || item.url);
    let who = '';
    if (app) {
      try { who = item.who || handle(new URL(item.page || item.url).pathname) } catch {}
    }
    return [who && `@${who}`, app, rest].filter(Boolean).join(' · ');
  }

  // `ext` is the type of a file LodiSave built (an HLS stream saved as .mp4), which replaces the
  // URL's own extension. `suffix` tells apart two files from one item, like a separate audio track.
  function filenameFor(item, ext, suffix) {
    let name = '', format = '';
    if (/^https?:/.test(item.url)) {
      try {
        const u = new URL(item.url);
        name = decodeURIComponent(u.pathname.split('/').pop());
        format = u.searchParams.get('format') || '';
      } catch {}
    }
    name = name.replace(/[\\/:*?"<>|\x00-\x1f]/g, '_').replace(/^[.\s]+|[.\s]+$/g, '').slice(0, 120);
    const own = /\.([a-z0-9]{2,5})$/i.exec(name);
    const base = (own ? name.slice(0, -own[0].length) : name) || `lodisave-${item.via || item.kind}-${Date.now()}`;
    const mime = item.mime || (item.url.startsWith('data:') ? item.url.slice(5).split(/[;,]/)[0] : '');
    const type = ext || own?.[1] || EXT[mime.split(';')[0].trim()] || format;
    return label(item, base) + (suffix ? `-${suffix}` : '') + (type ? `.${type}` : '');
  }

  const extFor = (mime) => EXT[(mime || '').split(';')[0].trim()] || '';

  // Bytes as base64, the way extension messages carry them.
  function b64(bytes) {
    let s = '';
    for (let i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
    return btoa(s);
  }

  return { isYouTube, filenameFor, extFor, b64, handle, appOf };
})();
if (typeof module !== 'undefined') module.exports = LodiShared;
