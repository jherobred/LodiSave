"""LodiSave's link saver on Android. Videos go through yt-dlp, whose official zipapp the app
downloads and keeps current, as the Windows app does. Pages with no video fall back to their share
image (og:image).

Kotlin calls save(url, out_dir, ytdlp_zip, progress, fmt, qjs) and gets back JSON: {"files": [...]}, with
"join" naming the output when the files are a video-only and an audio-only track for MediaMuxer to
join, or {"error": code}. Codes the app translates: login, nothing, offline, refused, gone, broken.
Anything else is yt-dlp's own message. Run this file with Python 3 to check the pure parts.
"""
import html
import json
import os
import re
import sys
import urllib.parse
import urllib.request

UA = ('Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) '
      'Chrome/140.0.0.0 Mobile Safari/537.36')
# Share images that are the site's own art, which pages show when the post itself is hidden.
SITE_ART = re.compile(r'logo|favicon|apple-touch-icon|placeholder|default[-_.]|og[-_]default', re.I)
IMAGE_TYPES = {'image/jpeg': 'jpg', 'image/png': 'png', 'image/webp': 'webp', 'image/gif': 'gif', 'image/avif': 'avif'}


class Failure(Exception):
    pass


def code_for(message):
    """The app's code for a yt-dlp error, as error_code in app/src/links.rs. HTTP statuses come
    before 'offline' because yt-dlp words them "Unable to download webpage: HTTP Error 403"."""
    low = message.lower()
    if any(k in low for k in ('login', 'log in', 'sign in', 'cookies', 'private', 'authentication', 'http error 401')):
        return 'login'
    if any(k in low for k in ('unsupported url', 'no video', 'no media', "there's no video", 'is not a valid url')):
        return 'nothing'
    if any(k in low for k in ('http error 403', 'http error 429', 'ip address is blocked', 'not available in your country',
                              'geo restrict', 'too many requests')):
        return 'refused'
    if 'http error 5' in low:
        return 'offline'
    if any(k in low for k in ('http error 404', 'http error 410', 'not found', 'unavailable', 'removed', 'does not exist', 'deleted')):
        return 'gone'
    if any(k in low for k in ('getaddrinfo', 'timed out', 'unable to download webpage', 'connection',
                              'name resolution', 'network is unreachable')):
        return 'offline'
    if any(k in low for k in ('report this issue', 'unable to extract', 'cannot parse')):
        return 'broken'
    return message.split('ERROR:')[-1].strip()[:300] or 'nothing'


def unwrap(url):
    """The real link inside a redirect wrapper, like the l.facebook.com links Messenger and Facebook
    hand out when you copy a shared link."""
    p = urllib.parse.urlparse(url)
    host = (p.hostname or '').lower()
    if re.fullmatch(r'(l|lm)\.(facebook|instagram|messenger)\.com', host) or (re.fullmatch(r'(www\.)?google\.[a-z.]+', host) and p.path == '/url'):
        q = urllib.parse.parse_qs(p.query)
        inner = (q.get('u') or q.get('q') or q.get('url') or [''])[0]
        if re.match(r'https?://', inner):
            return unwrap(inner)
    return url


def pick(info):
    """(video, audio) format ids to join, (combined, None), or None to let yt-dlp choose. The same
    choice as the Windows app (app/src/links.rs): one file with sound if the site has one at the best
    height, else the best MP4 video and M4A audio. Direct and DASH downloads keep MP4 fragments
    intact, HLS would give MPEG-TS."""
    formats = info.get('formats') or []
    has = lambda f, k: f.get(k) != 'none'
    height = lambda f: f.get('height') or 0
    rate = lambda f: f.get('tbr') or f.get('abr') or 0
    http = lambda f: str(f.get('protocol', '')).startswith('http')

    def best(items, key):
        items = list(items)
        return max(items, key=key) if items else None

    combined = best((f for f in formats if has(f, 'vcodec') and has(f, 'acodec') and f.get('ext') != 'mhtml'),
                    lambda f: (height(f), rate(f)))
    video = best((f for f in formats if has(f, 'vcodec') and not has(f, 'acodec') and f.get('ext') == 'mp4' and http(f)),
                 lambda f: (height(f), rate(f)))
    audio = best((f for f in formats if not has(f, 'vcodec') and has(f, 'acodec') and f.get('ext') in ('m4a', 'mp4') and http(f)),
                 rate)
    if video and audio and (not combined or height(video) > height(combined)):
        return video['format_id'], audio['format_id']
    if combined:
        return combined['format_id'], None
    return None


def upgrade(url):
    """Full-size originals for X and Pinterest, as in extension/rules.js."""
    m = re.match(r'https://pbs\.twimg\.com/(media/[^/?#:.]+)(?:\.(\w+))?(?::\w+)?(?:\?([^#]*))?', url)
    if m:
        query = dict(p.split('=', 1) for p in (m.group(3) or '').split('&') if '=' in p)
        return f"https://pbs.twimg.com/{m.group(1)}?format={query.get('format') or m.group(2) or 'jpg'}&name=orig"
    m = re.match(r'https://i\.pinimg\.com/(\d+x\d*(?:_RS)?)/(.+)$', url)
    return f'https://i.pinimg.com/originals/{m.group(2)}' if m else None


def og_image(page):
    """The page's share image: og:image, else twitter:image."""
    found = None
    for tag in re.findall(r'<meta\b[^>]*>', page, re.I):
        key = re.search(r'''\b(?:property|name)\s*=\s*["']([^"']+)''', tag, re.I)
        content = re.search(r'''\bcontent\s*=\s*["']([^"']*)''', tag, re.I)
        if not key or not content:
            continue
        key = key.group(1).lower()
        if key in ('og:image', 'og:image:url', 'og:image:secure_url'):
            return html.unescape(content.group(1))
        if key in ('twitter:image', 'twitter:image:src') and not found:
            found = html.unescape(content.group(1))
    return found


def clean_name(s):
    """A file name every file system accepts, from a title."""
    s = ''.join('_' if ord(c) < 32 or c in '<>:"/\\|?*' else c for c in s)[:70]
    return s.strip().rstrip('.').strip() or 'lodisave'


def unique(folder, stem, ext):
    path, n = os.path.join(folder, f'{stem}.{ext}'), 2
    while os.path.exists(path):
        path, n = os.path.join(folder, f'{stem} ({n}).{ext}'), n + 1
    return path


def get(url, referer=None, limit=-1):
    headers = {'User-Agent': UA}
    if referer:
        headers['Referer'] = referer
    with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=30) as r:
        return r.read(limit), r.headers.get_content_type()


def image(url, out_dir, progress):
    progress.report('reading', -1.0)
    try:
        page, _ = get(url, limit=8_000_000)
    except Exception:
        raise Failure('offline')
    shown = og_image(page.decode('utf-8', 'replace'))
    # ponytail: name-based, a site whose logo file is called something else still gets its logo saved
    if not shown or SITE_ART.search(urllib.parse.urlparse(shown).path.rsplit('/', 1)[-1]):
        raise Failure('nothing')
    shown = urllib.parse.urljoin(url, shown)
    progress.report('saving', -1.0)
    refused = False
    for src in dict.fromkeys(filter(None, [upgrade(shown), shown])):
        try:
            data, kind = get(src, referer=url)
        except Exception:
            refused = True  # rate limits and blocks: the image is there, the site won't send it
            continue
        if kind in IMAGE_TYPES:
            stem = os.path.splitext(os.path.basename(urllib.parse.urlparse(src).path))[0]
            path = unique(out_dir, clean_name(stem or 'image'), IMAGE_TYPES[kind])
            with open(path, 'wb') as f:
                f.write(data)
            return [path]
    raise Failure('refused' if refused else 'nothing')


def video(yt_dlp, url, out_dir, progress, fmt=None, opts=None):
    progress.report('reading', -1.0)
    base = {'quiet': True, 'no_warnings': True, 'noplaylist': True, 'noprogress': True,
            'updatetime': False, 'cachedir': False, 'paths': {'home': out_dir}, **(opts or {})}

    def read(**extra):
        with yt_dlp.YoutubeDL(dict(base, **extra)) as ydl:
            return ydl.extract_info(url, download=False)

    try:
        info = read()
        if not info:  # the site's extractor found nothing
            raise Failure('nothing')
    except yt_dlp.utils.DownloadError as e:
        if code_for(str(e)) != 'broken':
            raise Failure(code_for(str(e)))
        info = None
    except Failure:
        raise
    except Exception:  # an extractor that crashes outright
        info = None
    if info is None:
        # The site changed under its extractor. The page may still name its video in og:video,
        # JSON-LD or a <video> tag, which the generic extractor reads.
        try:
            info = read(force_generic_extractor=True)
        except Exception:
            pass
        if not info:
            raise Failure('broken')
    if info.get('_type') == 'playlist':
        # ponytail: the first item of a carousel only, save each entry when someone needs carousels
        info = next((e for e in info.get('entries') or [] if e), None)
        if not info:
            raise Failure('nothing')

    def fetch(chosen, template, lo, span):
        def hook(d):
            total = d.get('total_bytes') or d.get('total_bytes_estimate')
            if d.get('status') == 'downloading' and total:
                progress.report('saving', lo + span * d.get('downloaded_bytes', 0) / total)
        opts = dict(base, outtmpl=template, progress_hooks=[hook])
        if chosen:
            opts['format'] = chosen
        try:
            with yt_dlp.YoutubeDL(opts) as ydl:
                # Drop yt-dlp's own earlier pick (requested_formats and friends), as its command line
                # does for --load-info-json, so `chosen` is what downloads.
                done = ydl.process_ie_result(yt_dlp.YoutubeDL.sanitize_info(info, remove_private_keys=True), download=True)
        except yt_dlp.utils.DownloadError as e:
            raise Failure(code_for(str(e)))
        files = [d.get('filepath') for d in done.get('requested_downloads') or []]
        files = [f for f in files if f and os.path.isfile(f)]
        if not files:
            raise Failure('nothing')
        return files[0]

    name = '%(title).70B [%(id)s].%(ext)s'
    choice = None if fmt else pick(info)
    if choice and choice[1]:
        parts = [fetch(choice[0], 'part-v.%(ext)s', 0, 85), fetch(choice[1], 'part-a.%(ext)s', 85, 12)]
        stem = f"{clean_name(info.get('title') or '')} [{clean_name(str(info.get('id') or ''))}]"
        return {'files': parts, 'join': f'{stem}.mp4'}
    return {'files': [fetch(fmt or (choice[0] if choice else None), name, 0, 100)]}


def save(url, out_dir, ytdlp_zip, progress, fmt=None, qjs=None):
    url = unwrap(url.strip())
    try:
        if not re.match(r'https?://\S+$', url):
            raise Failure('nothing')
        if ytdlp_zip not in sys.path:
            sys.path.insert(0, ytdlp_zip)
        import yt_dlp
        opts = {}
        if qjs and os.path.isfile(qjs):
            # YouTube's challenges run in QuickJS, with the solver script the zipapp carries. The
            # cache keeps YouTube's player script between saves.
            opts = {'js_runtimes': {'quickjs': {'path': qjs}},
                    'cachedir': os.path.join(os.path.dirname(ytdlp_zip), 'yt-dlp-cache')}
        try:
            return json.dumps(video(yt_dlp, url, out_dir, progress, fmt, opts))
        except Failure as f:
            # A YouTube page's share image is the thumbnail, not what anyone asked for.
            if str(f) != 'nothing' or re.match(r'https?://([\w-]+\.)*(youtube\.com|youtu\.be)/', url):
                raise
            return json.dumps({'files': image(url, out_dir, progress)})
    except Failure as f:
        return json.dumps({'error': str(f)})
    except Exception as e:  # anything unexpected still reaches the screen as text
        return json.dumps({'error': f'{type(e).__name__}: {e}'[:300]})


if __name__ == '__main__':
    info = {'formats': [
        {'format_id': '18', 'ext': 'mp4', 'vcodec': 'avc1', 'acodec': 'mp4a', 'height': 360, 'protocol': 'https'},
        {'format_id': '137', 'ext': 'mp4', 'vcodec': 'avc1', 'acodec': 'none', 'height': 1080, 'protocol': 'https'},
        {'format_id': '248', 'ext': 'webm', 'vcodec': 'vp9', 'acodec': 'none', 'height': 1080, 'protocol': 'https'},
        {'format_id': '140', 'ext': 'm4a', 'vcodec': 'none', 'acodec': 'mp4a', 'abr': 128.0, 'protocol': 'https'},
        {'format_id': 'sb0', 'ext': 'mhtml', 'vcodec': 'none', 'acodec': 'none', 'protocol': 'mhtml'},
    ]}
    assert pick(info) == ('137', '140')
    assert pick({'formats': [{'format_id': 'h264', 'ext': 'mp4', 'height': 1024, 'protocol': 'https'}]}) == ('h264', None)
    page = ('<meta name="twitter:image" content="https://a.test/t.jpg"><meta property="og:image:width" content="9">'
            '<meta property="og:image" content="https://i.pinimg.com/736x/ab/cd/x.jpg?a=1&amp;b=2">')
    assert og_image(page) == 'https://i.pinimg.com/736x/ab/cd/x.jpg?a=1&b=2'
    assert upgrade('https://i.pinimg.com/736x/ab/cd/x.jpg') == 'https://i.pinimg.com/originals/ab/cd/x.jpg'
    assert upgrade('https://pbs.twimg.com/media/Gx1?format=png&name=small') == 'https://pbs.twimg.com/media/Gx1?format=png&name=orig'
    assert upgrade('https://pbs.twimg.com/media/Gx1.jpg:large') == 'https://pbs.twimg.com/media/Gx1?format=jpg&name=orig'
    assert upgrade('https://example.com/236x/a.jpg') is None
    assert SITE_ART.search('t_logo_2x.png') and SITE_ART.search('og-default.jpg') and not SITE_ART.search('5645318632_09ff5eef3b_b.jpg')
    assert clean_name('a/b: "c"?. ') == 'a_b_ _c__'
    assert code_for('ERROR: [Instagram] x: Requested content is not available, rate-limit reached or login required') == 'login'
    assert code_for('ERROR: Unsupported URL: https://x.test/') == 'nothing'
    assert code_for('ERROR: [Rumble] v1: Unable to download webpage: HTTP Error 403: Forbidden') == 'refused'
    assert code_for('ERROR: [TikTok] 67: Your IP address is blocked from accessing this post') == 'refused'
    assert code_for('ERROR: [vimeo] 56: Unable to download webpage: HTTP Error 404: Not Found') == 'gone'
    assert code_for('ERROR: [twitter] 65: Video #1 is unavailable') == 'gone'
    assert code_for('ERROR: [vimeo] 11: Unable to download webpage: HTTP Error 401: Unauthorized') == 'login'
    assert code_for('ERROR: [facebook] 36: Cannot parse data; please report this issue on https://github.com/yt-dlp/yt-dlp/issues') == 'broken'
    assert code_for('ERROR: [x] 1: Unable to download webpage: <urlopen error [Errno 11001] getaddrinfo failed>') == 'offline'
    assert code_for('ERROR: [x] 1: Unable to download webpage: HTTP Error 503: Service Unavailable') == 'offline'
    assert code_for('ERROR: [youtube] a: Video unavailable. This video is not available in your country') == 'refused'
    assert json.loads(save('not a link', '.', '', None)) == {'error': 'nothing'}
    assert unwrap('https://l.facebook.com/l.php?u=https%3A%2F%2Fwww.tiktok.com%2F%40a%2Fvideo%2F1%3Fx%3D1&h=AT0') == 'https://www.tiktok.com/@a/video/1?x=1'
    assert unwrap('https://www.google.com/url?q=https://x.com/a/status/1&sa=D') == 'https://x.com/a/status/1'
    assert unwrap('https://www.facebook.com/reel/1?u=https://evil.test/') == 'https://www.facebook.com/reel/1?u=https://evil.test/'
    print('lodi.py checks: ok')
