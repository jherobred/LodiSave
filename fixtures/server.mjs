// Static server for the fixture pages. Port 8788 is a second origin, standing in for another site's CDN.
// Run: node server.mjs, then open http://localhost:8787/
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.dirname(fileURLToPath(import.meta.url));
const TYPES = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.jpg': 'image/jpeg', '.png': 'image/png',
  '.gif': 'image/gif', '.webm': 'video/webm', '.m3u8': 'application/vnd.apple.mpegurl', '.ts': 'video/mp2t',
  '.mp4': 'video/mp4', '.m4s': 'video/iso.segment', '.mpd': 'application/dash+xml',
};
export const PAGE = 'http://localhost:8787', OTHER = 'http://localhost:8788';

function handle(req, res) {
  let p = decodeURIComponent(new URL(req.url, 'http://x').pathname);
  // Hotlink protection like TikTok's video CDN: no Referer from the page's origin, no file.
  if (p.startsWith('/hotlink/')) {
    if (!(req.headers.referer || '').startsWith(PAGE + '/')) return res.writeHead(403).end('hotlink blocked');
    p = '/media/' + p.slice('/hotlink/'.length);
  }
  if (p === '/') p = '/index.html';
  const file = path.join(root, p);
  if (!file.startsWith(root + path.sep) || file.includes('node_modules')) return res.writeHead(403).end();
  fs.stat(file, (err, st) => {
    if (err || !st.isFile()) return res.writeHead(404).end('not found');
    const headers = { 'content-type': TYPES[path.extname(file)] || 'application/octet-stream', 'accept-ranges': 'bytes', 'cache-control': 'no-store' };
    // Streaming CDNs let players on any site read playlists and segments.
    if (/^\/media\/(hls|hls2|dash|av)\//.test(p)) headers['access-control-allow-origin'] = '*';
    let start = 0, end = st.size - 1, code = 200;
    const m = /^bytes=(\d*)-(\d*)$/.exec(req.headers.range || '');
    if (m && (m[1] || m[2])) {
      start = m[1] ? +m[1] : st.size - +m[2];
      if (m[1] && m[2]) end = Math.min(+m[2], end);
      code = 206;
      headers['content-range'] = `bytes ${start}-${end}/${st.size}`;
    }
    headers['content-length'] = end - start + 1;
    res.writeHead(code, headers);
    if (req.method === 'HEAD') return res.end();
    fs.createReadStream(file, { start, end }).pipe(res);
  });
}

export async function serve() {
  const servers = [];
  for (const port of [8787, 8788]) {
    for (const host of ['127.0.0.1', '::1']) {
      try {
        await new Promise((ok, fail) => {
          const s = http.createServer(handle).once('error', fail).listen(port, host, () => ok(servers.push(s)));
        });
      } catch (e) {
        if (host === '127.0.0.1') throw e; // IPv6 loopback is optional
      }
    }
  }
  return () => servers.forEach((s) => s.close());
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  await serve();
  console.log(`Fixtures at ${PAGE}/ (second origin: ${OTHER})`);
}
