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
    return base + (suffix ? `-${suffix}` : '') + (type ? `.${type}` : '');
  }

  return { isYouTube, filenameFor };
})();
if (typeof module !== 'undefined') module.exports = LodiShared;
