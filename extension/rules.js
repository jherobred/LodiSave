// Full-resolution rules for CDNs that serve a smaller copy than the original.
// upgrade(url) returns the original's URL, or null when no rule applies.
var LodiRules = (() => {
  function upgrade(url) {
    let u;
    try { u = new URL(url) } catch { return null }
    const host = u.hostname, path = u.pathname;

    if (host === 'pbs.twimg.com') {
      // Profile pictures: abc_normal.jpg, abc_400x400.jpg -> abc.jpg
      if (path.startsWith('/profile_images/')) {
        const p = path.replace(/_(normal|bigger|mini|reasonably_small|\d+x\d+)(\.\w+)$/, '$2');
        return p === path ? null : `https://pbs.twimg.com${p}`;
      }
      // Post images and video thumbnails: ?format=jpg&name=small, or the old /media/id.jpg:large
      const legacy = /^(\/[^?]+?)(?:\.(jpe?g|png|webp|gif))?(?::(\w+))?$/.exec(path);
      if (!legacy) return null;
      const format = u.searchParams.get('format') || legacy[2] || 'jpg';
      if (!u.searchParams.has('name') && !legacy[2] && !legacy[3] && !path.startsWith('/media/')) return null;
      const out = new URL(`https://pbs.twimg.com${legacy[1]}`);
      out.searchParams.set('format', format);
      out.searchParams.set('name', 'orig');
      return out.href === url ? null : out.href;
    }

    if (host === 'i.pinimg.com') {
      const m = /^\/(\d+x\d*(?:_RS)?)\/(.+)$/.exec(path);
      return m ? `https://i.pinimg.com/originals/${m[2]}` : null;
    }

    // Reddit's previews are named after the original: preview.redd.it/<title>-v0-<id>.gif?format=png8 is
    // a still of i.redd.it/<id>.gif, the GIF itself. As upgrade in the Android app's lodi.py.
    if (host === 'preview.redd.it') {
      const m = /^\/(?:[\w-]*-v0-)?(\w+)\.(jpe?g|png|gif|webp)$/.exec(path);
      return m ? `https://i.redd.it/${m[1]}.${m[2]}` : null;
    }

    return null;
  }

  return { upgrade };
})();
if (typeof module !== 'undefined') module.exports = LodiRules;
