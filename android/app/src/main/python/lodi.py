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
import time
import urllib.error
import urllib.parse
import urllib.request

UA = ('Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) '
      'Chrome/140.0.0.0 Mobile Safari/537.36')
# Share images that are the site's own art, which pages show when the post itself is hidden.
DESKTOP_UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36'
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
    """Full-size originals for X and Pinterest, as in extension/rules.js, and Reddit's previews."""
    m = re.match(r'https://pbs\.twimg\.com/(media/[^/?#:.]+)(?:\.(\w+))?(?::\w+)?(?:\?([^#]*))?', url)
    if m:
        query = dict(p.split('=', 1) for p in (m.group(3) or '').split('&') if '=' in p)
        return f"https://pbs.twimg.com/{m.group(1)}?format={query.get('format') or m.group(2) or 'jpg'}&name=orig"
    m = REDDIT_PREVIEW.match(url)
    if m:
        return f'https://i.redd.it/{m.group(1)}.{m.group(2)}'
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


# What a phone browser sends for a page. Threads and Instagram send a post's data only to browsers.
PAGE = {'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8',
        'Accept-Language': 'en-US,en;q=0.9', 'Sec-Fetch-Dest': 'document', 'Sec-Fetch-Mode': 'navigate',
        'Sec-Fetch-Site': 'none', 'Sec-Fetch-User': '?1', 'Upgrade-Insecure-Requests': '1'}
FILE_TYPES = {**IMAGE_TYPES, 'video/mp4': 'mp4', 'video/webm': 'webm', 'video/quicktime': 'mov', 'audio/mp4': 'm4a', 'audio/mpeg': 'mp3'}
# Share links that redirect to the post, from the apps' Share buttons.
SHORT = re.compile(r'https?://(?:(?:www|m|web)\.facebook\.com/share/|fb\.watch/|pin\.it/|v[mt]\.tiktok\.com/|t\.co/|'
                   r'(?:www\.)?instagram\.com/share/|(?:www\.)?threads\.net/|(?:www\.)?reddit\.com/r/\w+/s/)')
META_POST = re.compile(r'https?://(?:www\.)?(?:threads\.(?:com|net)/@[\w.]+/post|instagram\.com/(?:[\w.]+/)?(?:p|reels?|tv))/([\w-]+)')
X_POST = re.compile(r'https?://(?:(?:www|mobile)\.)?(?:x|twitter)\.com/\w+/status(?:es)?/(\d+)')
REDDIT_POST = re.compile(r'https?://(?:[\w-]+\.)?reddit\.com/(?:r/(\w+)/)?(?:comments|gallery)/(\w+)|https?://redd\.it/(\w+)')
REDDIT_VIDEO = re.compile(r'https?://v\.redd\.it/(\w+)')
# Reddit's own preview of a post image, named after the original: preview.redd.it/<title>-v0-<id>.jpeg?width=640
REDDIT_PREVIEW = re.compile(r'https://preview\.redd\.it/(?:[\w-]*-v0-)?(\w+)\.(jpe?g|png|gif|webp)')


def http_code(e):
    """The app's code for an HTTP error, as code_for does for yt-dlp's words."""
    return {401: 'login', 403: 'refused', 429: 'refused', 404: 'gone', 410: 'gone'}.get(getattr(e, 'code', 0), 'offline')


def open_url(url, referer=None, page=False, desktop=False):
    headers = {'User-Agent': DESKTOP_UA if desktop else UA, **(PAGE if page else {})}
    if referer:
        headers['Referer'] = referer
    return urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=30)


def get(url, referer=None, limit=-1, page=False):
    with open_url(url, referer, page) as r:
        return r.read(limit), r.headers.get_content_type()


def page_of(url):
    """A page's HTML and final address, as a phone browser gets them."""
    try:
        with open_url(url, page=True) as r:
            return r.read(8_000_000).decode('utf-8', 'replace'), r.url
    except urllib.error.HTTPError as e:
        raise Failure(http_code(e))
    except Exception:
        raise Failure('offline')


def resolve(url):
    """Where an app's share link goes. Asked as a desktop browser, since Facebook sends a phone
    with no login to a login page instead of the post."""
    if not SHORT.match(url):
        return url
    try:
        with open_url(url, page=True, desktop=True) as r:
            final = r.url
    except Exception:
        return url
    p = urllib.parse.urlparse(final)
    if '/login' in p.path:
        final = urllib.parse.parse_qs(p.query).get('next', [final])[0]
    return unwrap(final)


def meta_items(page, code):
    """Every photo and video of a Threads or Instagram post, from the post data in its page. None
    when the page doesn't carry the post."""
    post = None

    def walk(o):
        nonlocal post
        if isinstance(o, dict):
            if o.get('code') == code and ('image_versions2' in o or 'carousel_media' in o):
                post = o
                return
            for v in o.values():
                if post is None:
                    walk(v)
        elif isinstance(o, list):
            for v in o:
                if post is None:
                    walk(v)

    for blob in re.findall(r'<script type="application/json"[^>]*>(.*?)</script>', page, re.S):
        if code in blob:
            try:
                walk(json.loads(blob))
            except ValueError:
                pass
        if post is not None:
            break
    if post is None:
        return None
    biggest = lambda xs: max(xs or [{}], key=lambda c: (c.get('width') or 0) * (c.get('height') or 0))
    out = []
    for m in post.get('carousel_media') or [post]:
        still = biggest((m.get('image_versions2') or {}).get('candidates'))
        moving = biggest(m.get('video_versions'))
        if moving.get('url'):
            out.append({'kind': 'video', 'src': moving['url'], 'thumb': still.get('url')})
        elif still.get('url'):
            out.append({'kind': 'image', 'src': still['url'], 'thumb': still['url']})
    return named(out, f"{(post.get('user') or {}).get('username') or ''} {code}")


def x_token(tid):
    """The token X's embedded posts send with an id: (id / 1e15 * pi) in base 36, zeros and point dropped."""
    n = int(tid) / 1e15 * 3.141592653589793
    whole, frac, digits = int(n), n - int(n), '0123456789abcdefghijklmnopqrstuvwxyz'
    s = ''
    while whole:
        whole, d = divmod(whole, 36)
        s = digits[d] + s
    f = ''
    for _ in range(11):
        frac *= 36
        f += digits[int(frac)]
        frac -= int(frac)
    return re.sub(r'0+|\.', '', (s or '0') + '.' + f)


def x_items(tid):
    """Every photo and video of a post on X, from the API its embedded posts use. None when the API
    doesn't answer."""
    try:
        data = json.loads(get(f'https://cdn.syndication.twimg.com/tweet-result?id={tid}&lang=en&token={x_token(tid)}')[0])
    except Exception:
        return None
    out = []
    for m in data.get('mediaDetails') or []:
        still = m.get('media_url_https')
        if m.get('type') == 'photo' and still:
            out.append({'kind': 'image', 'src': upgrade(still) or still, 'alts': [still], 'thumb': still})
        else:
            mp4 = [v for v in (m.get('video_info') or {}).get('variants') or [] if v.get('content_type') == 'video/mp4']
            if mp4:
                out.append({'kind': 'video', 'src': max(mp4, key=lambda v: v.get('bitrate') or 0)['url'], 'thumb': still})
    return named(out, f"{(data.get('user') or {}).get('screen_name') or ''} {tid}") or None


def reddit_items(sub, post, hops=0):
    """Every photo and video of a Reddit post, from the embed page Reddit makes for other sites
    (reddit.com itself sends scripts a bot check). A link with no subreddit finds it in the post's
    feed. None when the post can't be read this way."""
    try:
        if not sub:
            for wait in (True, False):
                try:
                    feed, _ = get(f'https://www.reddit.com/comments/{post}/.rss', limit=2_000_000)
                    break
                except urllib.error.HTTPError as e:
                    # Without a login Reddit answers about one feed a second, and says when to come back.
                    if e.code != 429 or not wait:
                        raise
                    time.sleep(min(5, float(e.headers.get('x-ratelimit-reset') or 2)) + 0.5)
            sub = (re.search(rf'reddit\.com/r/(\w+)/comments/{post}'.encode(), feed) or [b'', b''])[1].decode()
        page, _ = page_of(f'https://embed.reddit.com/r/{sub}/comments/{post}/')
    except Exception:
        return None
    out = []
    # A video: Reddit's own MP4s, picture and sound in one file, at several sizes.
    packaged = re.search(r'packaged-media-json="([^"]+)"', page)
    if packaged:
        try:
            sizes = json.loads(html.unescape(packaged.group(1)))['playbackMp4s']['permutations']
            best = max(sizes, key=lambda p: (p['source'].get('dimensions') or {}).get('height') or 0)['source']['url']
            poster = re.search(r'<img alt="media poster"[^>]*src="([^"]+)"', page)
            out.append({'kind': 'video', 'src': html.unescape(best), 'thumb': poster and html.unescape(poster.group(1))})
        except (ValueError, KeyError, TypeError):
            pass
    # Photos, a gallery's in order: the originals on i.redd.it, which the previews are named after.
    if not out:
        gallery = re.search(r'<gallery-carousel.*?</gallery-carousel>', page, re.S)
        for m in dict.fromkeys(REDDIT_PREVIEW.findall(gallery.group(0) if gallery else page)):
            out.append({'kind': 'image', 'src': f'https://i.redd.it/{m[0]}.{m[1]}', 'thumb': f'https://i.redd.it/{m[0]}.{m[1]}'})
    if not out:
        # A crosspost shows only a link to the post it shares.
        other = re.search(rf'reddit\.com/(?:r/(\w+)/comments|gallery)/((?!{post}\b)\w+)', page)
        return reddit_items(other.group(1), other.group(2), hops + 1) if other and not hops else None
    return named(out, f'{sub} {post}')


def named(items, stem):
    stem = clean_name(stem.strip())
    for i, it in enumerate(items):
        it['name'] = stem if len(items) == 1 else f'{stem} ({i + 1})'
    return items


def page_items(url):
    """A page's photos: a Threads or Instagram post's own, else its share image, full size on X and
    Pinterest. For links with no video."""
    page, final = page_of(url)
    code = META_POST.match(final)
    found = meta_items(page, code.group(1)) if code else None
    if found is not None:
        return found
    shown = og_image(page)
    # ponytail: name-based, a site whose logo file is called something else still gets its logo saved
    if not shown or SITE_ART.search(urllib.parse.urlparse(shown).path.rsplit('/', 1)[-1]):
        raise Failure('nothing')
    shown = urllib.parse.urljoin(final, shown)
    stem = os.path.splitext(os.path.basename(urllib.parse.urlparse(shown).path))[0]
    return named([{'kind': 'image', 'src': upgrade(shown) or shown, 'alts': [shown], 'thumb': shown}], stem or 'image')


def fetch(item, referer, out_dir, progress, lo, span):
    """Saves an item's file, trying its full-size address first and the shown copy after."""
    refused = None
    for src in dict.fromkeys([item['src'], *item.get('alts', [])]):
        try:
            with open_url(src, referer) as r:
                kind = r.headers.get_content_type()
                if kind not in FILE_TYPES:
                    refused = refused or 'nothing'  # a web page or a login wall, not the file
                    continue
                total, done = int(r.headers.get('Content-Length') or 0), 0
                path = unique(out_dir, item['name'], FILE_TYPES[kind])
                with open(path, 'wb') as f:
                    while chunk := r.read(1 << 16):
                        f.write(chunk)
                        done += len(chunk)
                        if total:
                            progress.report('saving', lo + span * done / total)
                return path
        except urllib.error.HTTPError as e:
            refused = http_code(e)
        except OSError:
            refused = refused or 'offline'
    raise Failure(refused or 'nothing')


def extract(yt_dlp, url, base):
    """yt-dlp's info for a link. A site reader that crashed or broke gets a second try with the
    generic reader, which finds a video named in og:video, JSON-LD or a <video> tag."""
    def read(**extra):
        with yt_dlp.YoutubeDL(dict(base, **extra)) as ydl:
            return ydl.extract_info(url, download=False)

    try:
        info = read()
        if not info:  # the site's extractor found nothing
            raise Failure('nothing')
        return info
    except yt_dlp.utils.DownloadError as e:
        if code_for(str(e)) != 'broken':
            raise Failure(code_for(str(e)))
    except Failure:
        raise
    except Exception:  # an extractor that crashes outright
        pass
    try:
        info = read(force_generic_extractor=True)
    except Exception:
        info = None
    if not info:
        raise Failure('broken')
    return info


def download(yt_dlp, info, base, out_dir, progress, fmt, lo, span):
    """Downloads one video yt-dlp read: {"files": [...]}, with "join" when the files are a video and
    an audio track for MediaMuxer to join."""
    def one(chosen, template, start, width):
        def hook(d):
            total = d.get('total_bytes') or d.get('total_bytes_estimate')
            if d.get('status') == 'downloading' and total:
                progress.report('saving', start + width * d.get('downloaded_bytes', 0) / total)
        opts = dict(base, outtmpl=template, progress_hooks=[hook], paths={'home': out_dir})
        if chosen:
            opts['format'] = chosen
        try:
            with yt_dlp.YoutubeDL(opts) as ydl:
                # Drop yt-dlp's own earlier pick (requested_formats and friends), as its command line
                # does for --load-info-json, so `chosen` is what downloads.
                done = ydl.process_ie_result(yt_dlp.YoutubeDL.sanitize_info(info, remove_private_keys=True), download=True)
        except yt_dlp.utils.DownloadError as e:
            raise Failure(code_for(str(e)))
        files = [d.get('filepath') for d in (done or {}).get('requested_downloads') or []]
        files = [f for f in files if f and os.path.isfile(f)]
        if not files:
            raise Failure('nothing')
        return files[0]

    stem = f"{clean_name(info.get('title') or '')} [{clean_name(str(info.get('id') or ''))}]"
    choice = None if fmt else pick(info)
    if choice and choice[1]:
        tag = clean_name(str(info.get('id') or 'x'))
        parts = [one(choice[0], f'part-{tag}-v.%(ext)s', lo, span * 0.85),
                 one(choice[1], f'part-{tag}-a.%(ext)s', lo + span * 0.85, span * 0.12)]
        return {'files': parts, 'join': f'{stem}.mp4'}
    return {'files': [one(fmt or (choice[0] if choice else None), '%(title).70B [%(id)s].%(ext)s', lo, span)]}


def thumbnail(item, referer, folder, i):
    """A small copy of an item's preview for the picker, or '' when the site won't send one."""
    if not item.get('thumb'):
        return ''
    try:
        data, kind = get(item['thumb'], referer, limit=4_000_000)
    except Exception:
        return ''
    if kind not in IMAGE_TYPES:
        return ''
    path = os.path.join(folder, f'thumb-{i}.{IMAGE_TYPES[kind]}')
    with open(path, 'wb') as f:
        f.write(data)
    return path


def items_of(yt_dlp, url, base):
    """Every photo and video a link leads to. Site readers come first: they see a post's photos too,
    where yt-dlp sees its videos only."""
    if m := META_POST.match(url):
        try:
            page, _ = page_of(url)
            items = meta_items(page, m.group(1))
        except Failure:
            items = None
        if items == []:
            raise Failure('nothing')  # a post with text only
        if items:
            return items
    elif m := X_POST.match(url):
        if items := x_items(m.group(1)):
            return items
    elif m := REDDIT_POST.match(url):
        if items := reddit_items(m.group(1), m.group(2) or m.group(3)):
            return items
    elif m := REDDIT_VIDEO.match(url):
        # A bare video link: its playlist is public, where the post page sends a bot check.
        url = f'https://v.redd.it/{m.group(1)}/DASHPlaylist.mpd'
    try:
        info = extract(yt_dlp, url, base)
        if m and m.re is REDDIT_VIDEO:
            info.update(title='reddit', id=m.group(1))
        entries = [e for e in info.get('entries') or []] if info.get('_type') == 'playlist' else [info]
        items = named([{'kind': 'video', 'info': e, 'thumb': e.get('thumbnail')} for e in entries if e],
                      f"{info.get('title') or ''} [{info.get('id') or ''}]")
        if not items:
            raise Failure('nothing')
        return items
    except Failure as f:
        # A YouTube page's share image is the thumbnail, not what anyone asked for.
        if str(f) != 'nothing' or re.match(r'https?://([\w-]+\.)*(youtube\.com|youtu\.be)/', url):
            raise
        return page_items(url)


RECENT = {}


def save(url, out_dir, ytdlp_zip, progress, fmt=None, qjs=None, pick_items=None, thumbs=None):
    """Saves a link. A post with several photos or videos answers {"choose": [{"kind", "thumb"}]}
    first, with a preview of each written to `thumbs`; save it again with pick_items ("0,2", or "all")
    to save those. Otherwise {"saves": [{"index", "files", "join"?}], "failed": n} or {"error": code}."""
    try:
        url = unwrap(url.strip())
        if not re.match(r'https?://\S+$', url):
            raise Failure('nothing')
        progress.report('reading', -1.0)
        asked = url
        if ytdlp_zip not in sys.path:
            sys.path.insert(0, ytdlp_zip)
        import yt_dlp
        base = {'quiet': True, 'no_warnings': True, 'noplaylist': True, 'noprogress': True,
                'updatetime': False, 'cachedir': False}
        if qjs and os.path.isfile(qjs):
            # YouTube's challenges run in QuickJS, with the solver script the zipapp carries. The
            # cache keeps YouTube's player script between saves.
            base.update({'js_runtimes': {'quickjs': {'path': qjs}},
                         'cachedir': os.path.join(os.path.dirname(ytdlp_zip), 'yt-dlp-cache')})
        # Picking from a post reads it again, so a post read in the last ten minutes isn't fetched twice
        # (Reddit refuses a quick second visit).
        seen = RECENT.get(asked)
        if seen and time.time() - seen[0] < 600:
            url, items = seen[1], seen[2]
        else:
            url = resolve(asked)
            items = items_of(yt_dlp, url, base)
            RECENT[asked] = (time.time(), url, items)
        if pick_items is None and len(items) > 1:
            folder = thumbs or out_dir
            os.makedirs(folder, exist_ok=True)
            return json.dumps({'choose': [{'kind': it['kind'], 'thumb': thumbnail(it, url, folder, i)} for i, it in enumerate(items)]})
        if pick_items is None:
            chosen = [0]
        elif pick_items == 'all':
            chosen = list(range(len(items)))
        else:
            chosen = [int(i) for i in str(pick_items).split(',') if i.strip().isdigit() and int(i) < len(items)]
        saves, failure = [], None
        for n, i in enumerate(chosen):
            lo, span = 100 * n / len(chosen), 100 / len(chosen)
            try:
                if 'info' in items[i]:
                    saves.append({'index': i, **download(yt_dlp, items[i]['info'], base, out_dir, progress, fmt, lo, span)})
                else:
                    saves.append({'index': i, 'files': [fetch(items[i], url, out_dir, progress, lo, span)]})
            except Failure as f:
                failure = failure or str(f)
        if not saves:
            raise Failure(failure or 'nothing')
        return json.dumps({'saves': saves, 'failed': len(chosen) - len(saves)})
    except Failure as f:
        return json.dumps({'error': str(f)})
    except Exception as e:  # anything unexpected still reaches the screen as text
        return json.dumps({'error': f'{type(e).__name__}: {e}'[:300]})


def save_file(url, out_dir, progress, referer=None):
    """Saves a photo or video file itself, as the picker finds one on a web page, full size where
    the site keeps one. Answers as save() does."""
    try:
        progress.report('saving', -1.0)
        stem = os.path.splitext(os.path.basename(urllib.parse.urlparse(url).path))[0]
        item = named([{'src': upgrade(url) or url, 'alts': [url]}], stem or 'image')[0]
        return json.dumps({'saves': [{'index': 0, 'files': [fetch(item, referer, out_dir, progress, 0, 100)]}], 'failed': 0})
    except Failure as f:
        return json.dumps({'error': str(f)})
    except Exception as e:
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
    assert upgrade('https://preview.redd.it/tiny-room-v0-fatco1py8pwh1.jpeg?width=640&s=1') == 'https://i.redd.it/fatco1py8pwh1.jpeg'
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
    carousel = {'code': 'ABC', 'user': {'username': 'nasa'}, 'carousel_media': [
        {'image_versions2': {'candidates': [{'url': 'https://x.test/s.jpg', 'width': 150, 'height': 150},
                                            {'url': 'https://x.test/b.jpg', 'width': 1080, 'height': 1080}]}},
        {'image_versions2': {'candidates': [{'url': 'https://x.test/t.jpg', 'width': 640, 'height': 640}]},
         'video_versions': [{'url': 'https://x.test/v.mp4', 'width': 720, 'height': 1280}]}]}
    page = f'<script type="application/json" data-sjs>{json.dumps({"a": [{"code": "ABC"}, carousel]})}</script>'
    assert [(i['kind'], i['src'], i['name']) for i in meta_items(page, 'ABC')] == [
        ('image', 'https://x.test/b.jpg', 'nasa ABC (1)'), ('video', 'https://x.test/v.mp4', 'nasa ABC (2)')]
    assert meta_items(page, 'XYZ') is None
    assert META_POST.match('https://www.threads.com/@nasa/post/Decc755lYhC?xmt=AQF0').group(1) == 'Decc755lYhC'
    assert META_POST.match('https://www.instagram.com/reel/C1a2b3/?igsh=x').group(1) == 'C1a2b3'
    assert X_POST.match('https://x.com/elonmusk/status/1585341984679469056?s=46').group(1) == '1585341984679469056'
    assert x_token('1585341984679469056').startswith('3uchycv2wq')
    assert REDDIT_POST.match('https://www.reddit.com/r/aww/comments/90bu6w/heat_index/').groups() == ('aww', '90bu6w', None)
    assert REDDIT_POST.match('https://redd.it/1xb6c3p').groups() == (None, None, '1xb6c3p')
    assert REDDIT_POST.match('https://www.reddit.com/gallery/1xb6c3p').groups() == (None, '1xb6c3p', None)
    assert REDDIT_PREVIEW.findall('src="https://preview.redd.it/tiny-room-v0-fatco1py8pwh1.jpeg?width=640"') == [('fatco1py8pwh1', 'jpeg')]
    assert REDDIT_PREVIEW.findall('src="https://preview.redd.it/abc123.png?width=640"') == [('abc123', 'png')]
    assert SHORT.match('https://www.facebook.com/share/r/1AbC/') and SHORT.match('https://vt.tiktok.com/ZS1/') and not SHORT.match('https://www.tiktok.com/@a/video/1')
    assert unwrap('https://l.facebook.com/l.php?u=https%3A%2F%2Fwww.tiktok.com%2F%40a%2Fvideo%2F1%3Fx%3D1&h=AT0') == 'https://www.tiktok.com/@a/video/1?x=1'
    assert unwrap('https://www.google.com/url?q=https://x.com/a/status/1&sa=D') == 'https://x.com/a/status/1'
    assert unwrap('https://www.facebook.com/reel/1?u=https://evil.test/') == 'https://www.facebook.com/reel/1?u=https://evil.test/'
    print('lodi.py checks: ok')
