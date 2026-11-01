"""LodiSave's link saver on Android. Videos go through yt-dlp, whose official zipapp the app
downloads and keeps current, as the Windows app does. Pages with no video fall back to their share
image (og:image).

Kotlin calls save(url, out_dir, ytdlp_zip, progress, fmt, qjs, pick_items, thumbs, cookies) and gets
back JSON: {"files": [...]}, with "join" naming the output when the files are a video-only and an
audio-only track for MediaMuxer to join, or {"error": code}. Codes the app translates: login, nothing,
offline, refused, gone, broken. Anything else is yt-dlp's own message. `cookies` is the user's sign-in
for the link's site: a Netscape cookies.txt file (the format yt-dlp reads), or None. Run this file with
Python 3 to check the pure parts.
"""
import base64
import html
import http.cookiejar
import json
import os
import re
import sys
import threading
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


def meta(page, *groups):
    """The first value of a page's meta tags named in the first of `groups` the page has."""
    found = {}
    for tag in re.findall(r'<meta\b[^>]*>', page, re.I):
        key = re.search(r'''\b(?:property|name)\s*=\s*["']([^"']+)''', tag, re.I)
        content = re.search(r'''\bcontent\s*=\s*["']([^"']*)''', tag, re.I)
        for i, names in enumerate(groups):
            if key and content and key.group(1).lower() in names:
                found.setdefault(i, html.unescape(content.group(1)))
    return next((found[i] for i in range(len(groups)) if i in found), None)


def og_image(page):
    """The page's share image: og:image, else twitter:image."""
    return meta(page, ('og:image', 'og:image:url', 'og:image:secure_url'), ('twitter:image', 'twitter:image:src'))


def og_video(page):
    """The video file the page names for sharing: og:video, else twitter:player:stream."""
    return meta(page, ('og:video', 'og:video:url', 'og:video:secure_url'), ('twitter:player:stream',))


def is_video_page(page):
    """Whether a page says it shows a video, whose share image is only a still of it."""
    return bool(og_video(page)) or (meta(page, ('og:type',)) or '').startswith('video') or meta(page, ('twitter:card',)) == 'player'


def sniff(head):
    """A file's type from its first bytes, as an extension, or None for anything else. Saves are named
    by this, not by the URL or the server's Content-Type, which can be wrong: Android files a save under
    Pictures or Movies by its extension."""
    if head[:3] == b'\xff\xd8\xff':
        return 'jpg'
    if head[:8] == b'\x89PNG\r\n\x1a\n':
        return 'png'
    if head[:6] in (b'GIF87a', b'GIF89a'):
        return 'gif'
    if head[:4] == b'RIFF' and head[8:12] == b'WEBP':
        return 'webp'
    if head[4:8] == b'ftyp':  # MP4 and its kin, told apart by the brands that follow
        brands = head[8:40]
        if b'avif' in brands or b'avis' in brands:
            return 'avif'
        if b'heic' in brands or b'heix' in brands or b'mif1' in brands:
            return 'heic'
        return {b'qt  ': 'mov', b'M4A ': 'm4a'}.get(head[8:12], 'mp4')
    if head[:4] == b'\x1aE\xdf\xa3':
        return 'webm' if b'webm' in head[:64] else 'mkv'
    if head[:3] == b'ID3':
        return 'mp3'
    return None


def file_ext(head, content_type):
    """The extension a download saves under: its bytes decide, the server's Content-Type only for a
    format sniff doesn't know. None for a web page, a login wall or anything else that isn't media."""
    found, said = sniff(head), FILE_TYPES.get(content_type)
    if found == 'mp4' and said in ('m4a', 'mov'):  # the same container, and the server knows which
        return said
    return found or (None if head.lstrip()[:1] == b'<' else said)


# Extensions for one kind of file, so a saved part isn't renamed for nothing.
SAME_KIND = {'jpeg': 'jpg', 'm4a': 'mp4', 'mov': 'mp4', 'm4v': 'mp4', 'mkv': 'webm'}


def settle(path):
    """A file yt-dlp saved, renamed when its bytes are another type than its extension. Failure when
    it is a web page, which some sites send in place of the file."""
    with open(path, 'rb') as f:
        head = f.read(64)
    found = sniff(head)
    stem, ext = os.path.splitext(path)
    if not found and head.lstrip()[:1] == b'<':
        os.remove(path)
        raise Failure('nothing')
    if not found or SAME_KIND.get(found, found) == SAME_KIND.get(ext[1:].lower(), ext[1:].lower()):
        return path
    new = unique(os.path.dirname(path), os.path.basename(stem), found)
    os.replace(path, new)
    return new


def clean_name(s):
    """A file name every file system accepts, from a title."""
    s = ''.join('_' if ord(c) < 32 or c in '<>:"/\\|?*' else c for c in s)[:70]
    return s.strip().rstrip('.').strip() or 'lodisave'


# Social apps by host, named in saved files, as app_of in app/src/links.rs. Other sites stay unnamed.
APPS = [(('instagram.com',), 'Instagram'), (('threads.net', 'threads.com'), 'Threads'), (('facebook.com', 'fb.watch'), 'Facebook'),
        (('x.com', 'twitter.com', 'twimg.com'), 'X'), (('tiktok.com',), 'TikTok'), (('reddit.com', 'redd.it'), 'Reddit'),
        (('pin.it',), 'Pinterest'), (('snapchat.com',), 'Snapchat'), (('youtube.com', 'youtu.be'), 'YouTube'),
        (('tumblr.com',), 'Tumblr'), (('bsky.app',), 'Bluesky'), (('twitch.tv',), 'Twitch'), (('linkedin.com',), 'LinkedIn')]


def app_of(url):
    host = (urllib.parse.urlparse(url).hostname or '').lower()
    if 'pinterest' in host.split('.'):
        return 'Pinterest'
    return next((app for hosts, app in APPS if any(host == h or host.endswith('.' + h) for h in hosts)), '')


def label(who, url, rest):
    """"@who · App · rest": who posted a save and where come first, so saves sort and read by person."""
    app = app_of(url)
    who = f"@{who.lstrip('@')}" if app and who else ''
    return clean_name(' · '.join(p for p in (who, app, rest) if p))


def uploader(info):
    """The poster's handle yt-dlp found: a handle-like uploader_id, channel or uploader first, a display
    name after, never a numeric account id."""
    names = [str(info.get(k) or '').strip().lstrip('@') for k in ('uploader_id', 'channel', 'uploader')]
    names = [n for n in names if n and not n.isdigit()]
    return next((n for n in names if ' ' not in n), names[0] if names else '')


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
SHORT = re.compile(r'https?://(?:(?:www|m|web)\.facebook\.com/share/|fb\.watch/|pin\.it/|v[mt]\.tiktok\.com/|(?:www\.)?tiktok\.com/t/|t\.co/|'
                   r'(?:www\.)?instagram\.com/share/|(?:www\.)?threads\.net/|(?:www\.)?reddit\.com/r/\w+/s/|(?:www\.)?snapchat\.com/t/)')
META_POST = re.compile(r'https?://(?:www\.)?(?:threads\.(?:com|net)/@[\w.]+/post|instagram\.com/(?!stories/)(?:[\w.]+/)?(?:p|reels?|tv))/([\w-]+)')
# Instagram stories: stories/<user>/, stories/<user>/<id>/ and stories/highlights/<id>/. The app shares a
# highlight as s/<base64 of "highlight:<id>">?story_media_id=<id>_<user id>.
IG_STORY = re.compile(r'https?://(?:www\.)?instagram\.com/stories/([\w.]+)(?:/(\d+))?')
IG_SHARED_STORY = re.compile(r'https?://(?:www\.)?instagram\.com/s/([\w=-]+)(?:\?(?:.*&)?story_media_id=(\d+))?')
GIPHY = re.compile(r'https://(?:media\d*|i)\.giphy\.com/')
# What Instagram's website sends to its own API. A phone browser's site has its own app id.
IG_API = {'X-IG-App-ID': '936619743392459', 'X-ASBD-ID': '359341', 'X-IG-WWW-Claim': '0',
          'Origin': 'https://www.instagram.com', 'Accept': '*/*'}
IG_MOBILE_APP = '1217981644879628'
# Public Snapchat stories: snapchat.com/add/<user>, /@<user>, story.snapchat.com/s/<user>, a highlight's link.
SNAP = re.compile(r'https?://(?:www\.|story\.)?snapchat\.com/(?!spotlight/|t/)(?:add/|s/)?@?([\w.-]+)')
TIKTOK_PHOTO = re.compile(r'https?://(?:www\.)?tiktok\.com/@([\w.-]+)/photo/(\d+)')
FB_STORY = re.compile(r'https?://(?:(?:www|m|web)\.)?facebook\.com/stories/(\d+)(?:/([\w=-]+))?')
X_POST = re.compile(r'https?://(?:(?:www|mobile)\.)?(?:x|twitter)\.com/\w+/status(?:es)?/(\d+)')
REDDIT_POST = re.compile(r'https?://(?:[\w-]+\.)?reddit\.com/(?:r/(\w+)/)?(?:comments|gallery)/(\w+)|https?://redd\.it/(\w+)')
REDDIT_VIDEO = re.compile(r'https?://v\.redd\.it/(\w+)')
# Reddit's own preview of a post image, named after the original: preview.redd.it/<title>-v0-<id>.jpeg?width=640
REDDIT_PREVIEW = re.compile(r'https://preview\.redd\.it/(?:[\w-]*-v0-)?(\w+)\.(jpe?g|png|gif|webp)')


def http_code(e):
    """The app's code for an HTTP error, as code_for does for yt-dlp's words."""
    return {401: 'login', 403: 'refused', 429: 'refused', 404: 'gone', 410: 'gone'}.get(getattr(e, 'code', 0), 'offline')


# The save running on this thread: its sign-in cookies and the opener that sends them. Saves run side
# by side on their own threads.
LOCAL = threading.local()


def sign_in(cookies):
    """Makes this thread's requests carry the user's sign-in from a cookies.txt file, or none. The jar
    sends each cookie only to the domains it belongs to. A file that can't be read counts as no
    sign-in, so sites answer with their login walls and the app offers to sign in."""
    jar = http.cookiejar.MozillaCookieJar()
    if cookies:
        try:
            jar.load(cookies, ignore_discard=True)
        except (OSError, http.cookiejar.LoadError):
            jar.clear()
    LOCAL.cookies = cookies if len(jar) else None
    LOCAL.jar = jar
    LOCAL.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))


def cookie(name, domain):
    """The value of a sign-in cookie of `domain`, or None."""
    return next((c.value for c in getattr(LOCAL, 'jar', None) or [] if c.name == name and c.domain.lstrip('.').endswith(domain)), None)


def open_url(url, referer=None, page=False, desktop=False, headers=None, ua=None):
    headers = {'User-Agent': ua or (DESKTOP_UA if desktop else UA), **(PAGE if page else {}), **(headers or {})}
    if referer:
        headers['Referer'] = referer
    if not getattr(LOCAL, 'opener', None):
        sign_in(None)
    return LOCAL.opener.open(urllib.request.Request(url, headers=headers), timeout=30)


def get(url, referer=None, limit=-1, page=False):
    with open_url(url, referer, page) as r:
        return r.read(limit), r.headers.get_content_type()


def page_of(url, desktop=False):
    """A page's HTML and final address, as a phone browser gets them, or a desktop one."""
    try:
        with open_url(url, page=True, desktop=desktop) as r:
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
    return named(media_items(post.get('carousel_media') or [post]), code, (post.get('user') or {}).get('username') or '')


def media_items(media):
    """Each photo, video and GIF in Meta's media data (a post, a carousel, a story), biggest copy first.
    A GIF from Threads' GIF picker has no picture of its own, only Giphy's GIF and WebP of it. A post's
    own page shows visitors a stand-in picture in place of the GIF, which is left out."""
    biggest = lambda xs: max(xs or [{}], key=lambda c: int(c.get('width') or 0) * int(c.get('height') or 0))
    out = []
    for m in media:
        still = biggest((m.get('image_versions2') or {}).get('candidates'))
        moving = biggest(m.get('video_versions'))
        gif = biggest(list(((m.get('giphy_media_info') or {}).get('images') or {}).values()))
        if moving.get('url'):
            out.append({'kind': 'video', 'src': moving['url'], 'thumb': still.get('url')})
        elif GIPHY.match(gif.get('url') or ''):
            out.append({'kind': 'video', 'src': gif['url'], 'alts': [gif['webp']] if GIPHY.match(gif.get('webp') or '') else [],
                        'thumb': gif.get('webp') or gif['url']})
        elif still.get('url'):
            out.append({'kind': 'image', 'src': still['url'], 'thumb': still['url']})
    return out


def threads_gifs(post):
    """The GIFs of a Threads post, from its embed page, which shows Giphy's file through Meta's image
    proxy (proxy?url=<the GIF>) where the post's own page shows visitors a stand-in."""
    page, _ = page_of(post + '/embed')
    found = (urllib.parse.parse_qs(urllib.parse.urlparse(html.unescape(src)).query).get('url', [''])[0]
             for src in re.findall(r'<img class="img" src="([^"]+)"', page))
    return [{'kind': 'video', 'src': u, 'thumb': u} for u in found if GIPHY.match(u)]


def ig_api(path):
    """JSON from the API Instagram's website uses, which answers only a signed-in user. It's asked the
    way the browser that signed in asks: with that browser's user agent and app id, and the CSRF token
    from its cookies. Instagram ties a sign-in to the browser it came from, and refuses a phone's
    sign-in asked for as a desktop. To a sign-in it no longer takes, it answers 401 or, for a profile,
    429. Only reading: nothing here marks a story seen, so its owner's viewer list doesn't show you."""
    ua = getattr(LOCAL, 'ua', None) or DESKTOP_UA
    headers = {**IG_API, 'X-IG-App-ID': IG_MOBILE_APP if 'Mobile' in ua else IG_API['X-IG-App-ID'], 'X-Requested-With': 'XMLHttpRequest',
               'Referer': 'https://www.instagram.com/', 'Sec-Fetch-Site': 'same-origin', 'Sec-Fetch-Mode': 'cors', 'Sec-Fetch-Dest': 'empty'}
    if csrf := cookie('csrftoken', 'instagram.com'):
        headers['X-CSRFToken'] = csrf
    try:
        with open_url('https://www.instagram.com/api/v1/' + path, headers=headers, ua=ua) as r:
            if '/accounts/login' in r.url:
                raise Failure('login')
            return json.loads(r.read(20_000_000))
    except urllib.error.HTTPError as e:
        raise Failure('login' if e.code in (401, 403, 429) else http_code(e))
    except ValueError:  # a login page in place of the JSON
        raise Failure('login')
    except OSError:
        raise Failure('offline')


def story_items(user, highlight=None, only=None):
    """Every photo and video of a user's Instagram stories or of a highlight, or just story `only`, as
    Instagram's website reads them. Stories need the user's sign-in."""
    if not getattr(LOCAL, 'cookies', None):
        raise Failure('login')
    if highlight:
        reel = f'highlight:{highlight}'
    else:
        try:
            reel = str(ig_api(f'users/web_profile_info/?username={user}')['data']['user']['id'])
        except (KeyError, TypeError):
            raise Failure('gone')
    found = (ig_api(f'feed/reels_media/?reel_ids={reel}').get('reels') or {}).get(reel) or {}
    media = [m for m in found.get('items') or [] if not only or str(m.get('pk')) == only]
    if not media:
        # A highlight stays, so Instagram showing none means it no longer takes this sign-in. A story
        # lasts a day.
        raise Failure('login' if highlight else 'gone')
    owner = (found.get('user') or {}).get('username') or user or 'instagram'
    return named(media_items(media), only or highlight or 'story', owner)


def shared_story(url):
    """(highlight id, story id) of a highlight link from Instagram's Share button, else None."""
    m = IG_SHARED_STORY.match(url)
    try:
        said = base64.urlsafe_b64decode(m.group(1) + '==').decode() if m else ''
    except ValueError:
        return None
    h = re.fullmatch(r'highlight:(\d+)', said)
    return (h.group(1), m.group(2)) if h else None


def page_json(page, script_id):
    """The JSON a page carries in <script id=...>, or {}."""
    m = re.search(rf'<script id="{script_id}" type="application/json"[^>]*>(.*?)</script>', page, re.S)
    try:
        return json.loads(m.group(1)) if m else {}
    except ValueError:
        return {}


def snap_items(url):
    """Every snap of a public Snapchat story, from the data its page carries: the story up now, or the
    highlight a link names. Public stories need no sign-in. None when the page has no story."""
    page, _ = page_of(url)
    props = (page_json(page, '__NEXT_DATA__').get('props') or {}).get('pageProps') or {}
    stories = [props.get('story') or {}, *(props.get('curatedHighlights') or [])]
    named_id = re.search(r'[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}', url)
    story = next((s for s in stories if named_id and named_id.group(0) == (s.get('highlightId') or {}).get('value')), stories[0])
    out = [{'kind': 'video' if s.get('snapMediaType') == 1 else 'image', 'src': s['snapUrls']['mediaUrl'],
            'thumb': ((s['snapUrls'].get('mediaPreviewUrl') or {}).get('value'))}
           for s in story.get('snapList') or [] if (s.get('snapUrls') or {}).get('mediaUrl')]
    user = ((props.get('userProfile') or {}).get('publicProfileInfo') or {}).get('username') or 'snapchat'
    return named(out, 'story', user) or None


def tiktok_photos(url):
    """The photos of a TikTok photo post or photo story, from the data its page carries: yt-dlp saves
    only such a post's sound. None when the page doesn't carry it (TikTok sends some networks a check)."""
    page, _ = page_of(url, desktop=True)
    scope = page_json(page, '__UNIVERSAL_DATA_FOR_REHYDRATION__').get('__DEFAULT_SCOPE__') or {}
    item = ((scope.get('webapp.video-detail') or {}).get('itemInfo') or {}).get('itemStruct') or {}
    out = []
    for im in (item.get('imagePost') or {}).get('images') or []:
        urls = (im.get('imageURL') or {}).get('urlList') or []
        if urls:
            out.append({'kind': 'image', 'src': urls[0], 'alts': urls[1:], 'thumb': urls[0]})
    return named(out, str(item.get('id') or ''), (item.get('author') or {}).get('uniqueId') or '') or None


def fb_story_items(url, card=None):
    """The photos and videos of a Facebook story, from the data the story page carries for a signed-in
    desktop browser. Facebook shows stories only to accounts. `card` picks one story of the person's;
    without it, all of them. Only reading: nothing marks the story seen. None when the page carries
    no story media."""
    if not getattr(LOCAL, 'cookies', None):
        raise Failure('login')
    page, final = page_of(url, desktop=True)
    if '/login' in urllib.parse.urlparse(final).path:
        raise Failure('login')
    cards = []

    def media(o, card_id):
        if isinstance(o, dict):
            card_id = o.get('id') if isinstance(o.get('id'), str) and 'story_card_info' in o else card_id
            kind = o.get('__typename')
            src = (o.get('browser_native_hd_url') or o.get('playable_url_quality_hd') or o.get('browser_native_sd_url') or o.get('playable_url')) if kind == 'Video' else None
            thumb = ((o.get('preferred_thumbnail') or {}).get('image') or {}).get('uri') or (o.get('previewImage') or {}).get('uri')
            if src:
                cards.append((card_id, {'kind': 'video', 'src': src, 'thumb': thumb}))
            elif kind == 'Photo' and (o.get('image') or {}).get('uri'):
                cards.append((card_id, {'kind': 'image', 'src': o['image']['uri'], 'thumb': o['image']['uri']}))
            for v in o.values():
                media(v, card_id)
        elif isinstance(o, list):
            for v in o:
                media(v, card_id)

    # Only what's under the stories the page shows, not the profile pictures and ads around them.
    def stories(o):
        if isinstance(o, dict):
            for k, v in o.items():
                if k in ('unified_stories', 'story_bucket', 'story_card_info') or k.startswith('unified_stories'):
                    media(v, None)
                else:
                    stories(v)
        elif isinstance(o, list):
            for v in o:
                stories(v)

    for blob in re.findall(r'<script type="application/json"[^>]*>(.*?)</script>', page, re.S):
        if 'story' in blob and ('playable_url' in blob or 'browser_native' in blob or '"Photo"' in blob):
            try:
                stories(json.loads(blob))
            except ValueError:
                pass
    picked = [m for cid, m in cards if not card or cid == card] or [m for _, m in cards]
    found = list({m['src']: m for m in picked}.values())
    owner = re.search(r'"story_bucket_owner":\{[^{}]*"name":"([^"]+)"', page)
    return named(found, 'story', owner.group(1) if owner else '') or None


def profile(site, user, part='all', cursor=None, cookies=None, ua=None):
    """A profile's stories, highlights and posts, for the app to show and pick from, as JSON:
    {"name", "user", "private", "followed", "stories", "highlights": [{"id", "title"}], "posts",
    "next"}, each story or post {"kind", "src", "thumb", "name"}. `part` "highlight:<id>" answers
    {"items"} of one highlight, and "posts" with `cursor` the next page of posts. It sees what the
    signed-in account sees: a private account's only when that account follows it. Only reading:
    nothing marks a story seen. {"error": code} when it can't."""
    try:
        sign_in(cookies)
        LOCAL.ua = ua
        user = user.strip().lstrip('@').split('/')[0]
        if not re.fullmatch(r'[\w.]{1,30}', user):
            raise Failure('nothing')
        if site == 'threads':
            return json.dumps(threads_profile(user))
        if site == 'instagram':
            return json.dumps(ig_profile(user, part, cursor))
        raise Failure('nothing')
    except Failure as f:
        return json.dumps({'error': str(f)})
    except Exception as e:
        return json.dumps({'error': f'{type(e).__name__}: {e}'[:300]})
    finally:
        LOCAL.opener = LOCAL.cookies = LOCAL.ua = None


def picks(media, stem):
    """Meta's media data as the app's picks: what to save, a preview, a name."""
    return [{'kind': it['kind'], 'src': it['src'], 'thumb': it.get('thumb') or '', 'name': it['name']} for it in named(media_items(media), stem)]


def ig_profile(user, part, cursor):
    if not getattr(LOCAL, 'cookies', None):
        raise Failure('login')
    found = ((ig_api(f'users/web_profile_info/?username={urllib.parse.quote(user)}').get('data') or {}).get('user'))
    if not found:
        raise Failure('gone')
    uid = str(found['id'])
    out = {'name': found.get('full_name') or user, 'user': found.get('username') or user,
           'private': bool(found.get('is_private')), 'followed': bool(found.get('followed_by_viewer'))}
    if part.startswith('highlight:'):
        reel = (ig_api(f'feed/reels_media/?reel_ids={part}').get('reels') or {}).get(part) or {}
        return {**out, 'items': picks(reel.get('items') or [], f"{out['user']} {part.split(':')[1]}")}
    # A private account the signed-in one doesn't follow shows nothing, and asking would only be refused.
    if out['private'] and not out['followed']:
        return {**out, 'stories': [], 'highlights': [], 'posts': [], 'next': None}
    feed = ig_api(f'feed/user/{uid}/?count=12' + (f'&max_id={urllib.parse.quote(cursor)}' if cursor else ''))
    out['posts'] = [p for m in feed.get('items') or [] for p in picks(m.get('carousel_media') or [m], f"{out['user']} {m.get('code') or m.get('pk')}")]
    out['next'] = feed.get('next_max_id') if feed.get('more_available') else None
    if part == 'all':
        reel = (ig_api(f'feed/reels_media/?reel_ids={uid}').get('reels') or {}).get(uid) or {}
        out['stories'] = picks(reel.get('items') or [], f"{out['user']} story")
        tray = ig_api(f'highlights/{uid}/highlights_tray/').get('tray') or []
        out['highlights'] = [{'id': h['id'], 'title': h.get('title') or ''} for h in tray if str(h.get('id', '')).startswith('highlight:')]
    return out


def threads_profile(user):
    """A Threads profile's latest posts with photos or videos, from the data its page carries. Threads
    has no stories."""
    page, _ = page_of(f'https://www.threads.com/@{user}')
    posts = {}

    def walk(o):
        if isinstance(o, dict):
            if o.get('code') and ('image_versions2' in o or o.get('carousel_media')) and ((o.get('user') or {}).get('username') or user) == user:
                posts.setdefault(o['code'], o)
            for v in o.values():
                walk(v)
        elif isinstance(o, list):
            for v in o:
                walk(v)

    for blob in re.findall(r'<script type="application/json"[^>]*>(.*?)</script>', page, re.S):
        if 'image_versions2' in blob:
            try:
                walk(json.loads(blob))
            except ValueError:
                pass
    items = [p for code, post in posts.items() for p in picks(post.get('carousel_media') or [post], f'{user} {code}')]
    return {'name': user, 'user': user, 'private': '"is_private":true' in page and not items, 'followed': False,
            'stories': [], 'highlights': [], 'posts': items, 'next': None}


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
    return named(out, tid, (data.get('user') or {}).get('screen_name') or '') or None


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
    # A GIF or video from another site (Imgur, Giphy): the MP4 of it that Reddit's player plays. Its
    # poster is only a still.
    player = not out and re.search(r'<shreddit-player\b[^>]*?\ssrc="([^"]+)"', page)
    if player and not re.search(r'\.(m3u8|mpd)\b', player.group(1)):
        out.append({'kind': 'video', 'src': html.unescape(player.group(1))})
    if not out:
        # A crosspost shows only a link to the post it shares.
        other = re.search(rf'reddit\.com/(?:r/(\w+)/comments|gallery)/((?!{post}\b)\w+)', page)
        return reddit_items(other.group(1), other.group(2), hops + 1) if other and not hops else None
    return named(out, f'{sub} {post}')


def raise_(e):
    raise e


def named(items, stem, who=''):
    stem = clean_name(stem.strip())
    for i, it in enumerate(items):
        it['name'] = stem if len(items) == 1 else f'{stem} ({i + 1})'
        it['who'] = who
    return items


def page_items(url):
    """What a link yt-dlp found no video in shows: the file itself when it is one, a Threads or
    Instagram post's photos, the video file the page names, else its share image, full size on X and
    Pinterest. A page that says it shows a video never saves its share image, a still of the video."""
    page, final = page_of(url)
    stem = lambda u: os.path.splitext(os.path.basename(urllib.parse.urlparse(u).path))[0]
    code = META_POST.match(final)
    found = meta_items(page, code.group(1)) if code else None
    if found is not None:
        return found
    if moving := og_video(page):
        moving = urllib.parse.urljoin(final, moving)
        return named([{'kind': 'video', 'src': moving}], stem(moving) or 'video')
    if is_video_page(page):
        raise Failure('nothing')
    shown = og_image(page)
    if not shown:
        # The link may be the file itself. Giphy, Tenor, Tumblr and Imgur send a web page in its place
        # to whatever asks the way a browser asks for a page; fetch() asks plainly, and refuses pages.
        return named([{'kind': 'image', 'src': final}], stem(final) or 'lodisave')
    # ponytail: name-based, a site whose logo file is called something else still gets its logo saved
    if SITE_ART.search(urllib.parse.urlparse(shown).path.rsplit('/', 1)[-1]):
        raise Failure('nothing')
    shown = urllib.parse.urljoin(final, shown)
    return named([{'kind': 'image', 'src': upgrade(shown) or shown, 'alts': [shown], 'thumb': shown}], stem(shown) or 'image')


def fetch(item, referer, out_dir, progress, lo, span):
    """Saves an item's file, trying its full-size address first and the shown copy after. Its first
    bytes name it, so a GIF or a video a server sends under another type still saves as what it is."""
    refused = None
    for src in dict.fromkeys([item['src'], *item.get('alts', [])]):
        try:
            with open_url(src, referer) as r:
                chunk = r.read(1 << 16)
                ext = file_ext(chunk, r.headers.get_content_type())
                if not ext:
                    refused = refused or 'nothing'  # a web page or a login wall, not the file
                    continue
                total, done = int(r.headers.get('Content-Length') or 0), 0
                path = unique(out_dir, item['name'], ext)
                with open(path, 'wb') as f:
                    while chunk:
                        f.write(chunk)
                        done += len(chunk)
                        if total:
                            progress.report('saving', lo + span * done / total)
                        chunk = r.read(1 << 16)
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


def download(yt_dlp, info, base, out_dir, progress, fmt, lo, span, name=None):
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
        return settle(files[0])

    stem = name or f"{clean_name(info.get('title') or '')} [{clean_name(str(info.get('id') or ''))}]"
    choice = None if fmt else pick(info)
    if choice and choice[1]:
        tag = clean_name(str(info.get('id') or 'x'))
        parts = [one(choice[0], f'part-{tag}-v.%(ext)s', lo, span * 0.85),
                 one(choice[1], f'part-{tag}-a.%(ext)s', lo + span * 0.85, span * 0.12)]
        return {'files': parts, 'join': f'{stem}.mp4'}
    # yt-dlp reads % in an output template as a field.
    template = f"{stem.replace('%', '%%')}.%(ext)s" if name else '%(title).70B [%(id)s].%(ext)s'
    return {'files': [one(fmt or (choice[0] if choice else None), template, lo, span)]}


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
    if m := IG_STORY.match(url):
        user, story = m.groups()
        if user != 'highlights':
            return story_items(user, only=story)
        if story:
            return story_items(None, highlight=story)
        raise Failure('nothing')
    if shared := shared_story(url):
        return story_items(None, highlight=shared[0], only=shared[1])
    if m := FB_STORY.match(url):
        # yt-dlp has no reader for Facebook stories.
        return fb_story_items(url, m.group(2)) or raise_(Failure('broken'))
    if TIKTOK_PHOTO.match(url) and (items := tiktok_photos(url)):
        return items
    if SNAP.match(url) and (items := snap_items(url)):
        return items
    if m := META_POST.match(url):
        try:
            page, _ = page_of(url)
            items = meta_items(page, m.group(1))
            if items == [] and 'threads.' in url:
                items = named(threads_gifs(m.group(0)), m.group(1), m.group(0).split("@")[-1].split("/")[0])
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
        # A file the generic reader found named in a page is fetched here: yt-dlp asks for it as a
        # browser asks for a page, and Giphy then sends its web page in place of the video.
        file = lambda e: e.get('extractor_key') == 'Generic' and not e.get('formats') and e.get('protocol') in ('http', 'https') and e.get('url')
        items = named([{'kind': 'video', 'src': e['url'], 'thumb': e.get('thumbnail')} if file(e) else
                       {'kind': 'video', 'info': e, 'thumb': e.get('thumbnail')} for e in entries if e],
                      f"{info.get('title') or ''} [{info.get('id') or ''}]", uploader(info))
        if not items:
            raise Failure('nothing')
        return items
    except Failure as f:
        # A YouTube page's share image is the thumbnail, not what anyone asked for.
        if str(f) != 'nothing' or re.match(r'https?://([\w-]+\.)*(youtube\.com|youtu\.be)/', url):
            raise
        return page_items(url)


RECENT = {}


def unsigned_retry(run, cookies):
    """run(cookies), then run(None) when that fails: a sign-in a site no longer takes can make even a
    public post fail (Instagram's reader breaks on it). Answers 'login' when the post needs one."""
    answer = run(cookies)
    if cookies and json.loads(answer).get('error') not in (None, 'offline'):
        again = run(None)
        if json.loads(again).get('error') in (None, 'login'):
            return again
    return answer


def save(url, out_dir, ytdlp_zip, progress, fmt=None, qjs=None, pick_items=None, thumbs=None, cookies=None, ua=None):
    """Saves a link. A post with several photos or videos answers {"choose": [{"kind", "thumb"}]}
    first, with a preview of each written to `thumbs`; save it again with pick_items ("0,2", or "all")
    to save those. Otherwise {"saves": [{"index", "files", "join"?}], "failed": n} or {"error": code}.
    `cookies` signs in to the link's site (see sign_in), and `ua` is the user agent of the browser
    that signed in, which sites that tie a sign-in to its browser get asked with."""
    return unsigned_retry(lambda c: save_signed(url, out_dir, ytdlp_zip, progress, fmt, qjs, pick_items, thumbs, c, ua), cookies)


def save_signed(url, out_dir, ytdlp_zip, progress, fmt, qjs, pick_items, thumbs, cookies, ua=None):
    try:
        sign_in(cookies)
        LOCAL.ua = ua
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
        if LOCAL.cookies:
            base['cookiefile'] = LOCAL.cookies
        # Picking from a post reads it again, so a post read in the last ten minutes isn't fetched twice
        # (Reddit refuses a quick second visit).
        seen = RECENT.get(asked)
        if seen and time.time() - seen[0] < 600:
            url, items = seen[1], seen[2]
        else:
            url = resolve(asked)
            items = [dict(it, name=label(it.get('who', ''), url, it['name'])) for it in items_of(yt_dlp, url, base)]
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
                    saves.append({'index': i, **download(yt_dlp, items[i]['info'], base, out_dir, progress, fmt, lo, span, items[i]['name'])})
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
    finally:
        LOCAL.opener = LOCAL.cookies = LOCAL.ua = None


def save_file(url, out_dir, progress, referer=None, cookies=None, name=None):
    """Saves a photo or video file itself, as the picker finds one on a web page, or a profile's story
    or post picked in the app, full size where the site keeps one. `name` names it, else its URL
    does. Answers as save() does."""
    return unsigned_retry(lambda c: save_file_signed(url, out_dir, progress, referer, c, name), cookies)


def save_file_signed(url, out_dir, progress, referer, cookies, name=None):
    try:
        sign_in(cookies)
        progress.report('saving', -1.0)
        stem = name or os.path.splitext(os.path.basename(urllib.parse.urlparse(url).path))[0]
        item = named([{'src': upgrade(url) or url, 'alts': [url]}], stem or 'image')[0]
        return json.dumps({'saves': [{'index': 0, 'files': [fetch(item, referer, out_dir, progress, 0, 100)]}], 'failed': 0})
    except Failure as f:
        return json.dumps({'error': str(f)})
    except Exception as e:
        return json.dumps({'error': f'{type(e).__name__}: {e}'[:300]})
    finally:
        LOCAL.opener = LOCAL.cookies = None


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
        ('image', 'https://x.test/b.jpg', 'ABC (1)'), ('video', 'https://x.test/v.mp4', 'ABC (2)')]
    assert meta_items(page, 'ABC')[0]['who'] == 'nasa'
    assert label('nasa', 'https://www.threads.com/@nasa/post/ABC', 'ABC') == '@nasa · Threads · ABC'
    assert label('', 'https://v.redd.it/x/DASHPlaylist.mpd', 'reddit [x]') == 'Reddit · reddit [x]'
    assert label('bob', 'https://www.pinterest.ph/pin/1/', 'a') == '@bob · Pinterest · a'
    assert label('bob', 'https://example.com/a', 'a') == 'a'
    assert uploader({'uploader_id': '6800', 'channel': 'Khaby Lame', 'uploader': 'khaby.lame'}) == 'khaby.lame'
    assert uploader({'uploader': 'Jane Doe'}) == 'Jane Doe'
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

    # Files are named by their bytes: a video sent as octet-stream, a GIF under a .jpg name, a web page
    # sent as video/mp4 (Giphy, when asked like a browser asks for a page).
    mp4, gif = b'\x00\x00\x00\x20ftypisom\x00\x00\x02\x00isomiso2avc1mp41', b'GIF89a\x01\x00\x01\x00'
    assert [sniff(h) for h in (b'\xff\xd8\xff\xe0', b'\x89PNG\r\n\x1a\n', gif, b'RIFF\x1e\xc6\x06\x00WEBPVP8X', mp4,
                               b'\x00\x00\x00\x1cftypavif\x00\x00\x00\x00avifmif1', b'\x00\x00\x00\x18ftypheic\x00\x00\x00\x00mif1heic',
                               b'\x00\x00\x00\x14ftypqt  \x00\x00\x02\x00', b'\x1aE\xdf\xa3\x9fB\x86\x81\x01B\xf7\x81\x01B\x82\x84webm',
                               b'<!DOCTYPE html>')] == ['jpg', 'png', 'gif', 'webp', 'mp4', 'avif', 'heic', 'mov', 'webm', None]
    assert file_ext(mp4, 'application/octet-stream') == 'mp4' and file_ext(mp4, 'audio/mp4') == 'm4a'
    assert file_ext(gif, 'image/jpeg') == 'gif' and file_ext(b'<!DOCTYPE html>', 'video/mp4') is None
    assert file_ext(b'\x00\x00\x01\xba', 'video/quicktime') == 'mov' and file_ext(b'\x00\x00\x01\xba', 'binary/octet-stream') is None
    import tempfile
    with tempfile.TemporaryDirectory() as d:
        for name, data in (('page.mp4', b'\n<!doctype html>'), ('moving.jpg', gif), ('a.m4a', mp4)):
            with open(os.path.join(d, name), 'wb') as f:
                f.write(data)
        assert os.path.basename(settle(os.path.join(d, 'moving.jpg'))) == 'moving.gif'
        assert os.path.basename(settle(os.path.join(d, 'a.m4a'))) == 'a.m4a'
        try:
            settle(os.path.join(d, 'page.mp4'))
            assert False, 'a web page saved as a video'
        except Failure as f:
            assert str(f) == 'nothing' and not os.path.exists(os.path.join(d, 'page.mp4'))
    giphy = ('<meta property="og:image" content="https://m.test/giphy.webp"><meta property="og:video" content="https://m.test/giphy.mp4">'
             '<meta property="og:video:secure_url" content="https://m.test/s.mp4"><meta property="og:type" content="video.other">')
    assert og_video(giphy) == 'https://m.test/giphy.mp4' and is_video_page(giphy)
    assert og_video('<meta class="dynamic" name="twitter:player:stream" content="https://m.test/t.mp4">') == 'https://m.test/t.mp4'
    assert is_video_page('<meta content="video.other" property="og:type">') and not is_video_page(page)
    # A Threads GIF post: no picture of its own, only Giphy's GIF and WebP.
    threads_gif = {'code': 'DJcW', 'media_type': 19, 'image_versions2': {'candidates': []}, 'video_versions': None,
                   'giphy_media_info': {'id': '1', 'images': {'fixed_height': {'height': 200, 'width': 327,
                                        'url': 'https://media4.giphy.com/media/v1.x/U1Y/200.gif', 'webp': 'https://media4.giphy.com/media/v1.x/U1Y/200.webp'}}}}
    found = meta_items(f'<script type="application/json">{json.dumps({"x": [threads_gif]})}</script>', 'DJcW')
    assert [(i['kind'], i['src'], i['alts']) for i in found] == [
        ('video', 'https://media4.giphy.com/media/v1.x/U1Y/200.gif', ['https://media4.giphy.com/media/v1.x/U1Y/200.webp'])]
    # The post's own page shows visitors a stand-in picture, which is no save.
    threads_gif['giphy_media_info']['images']['fixed_height'].update(url='https://static.cdninstagram.com/rsrc.php/v4/yr/r/xg.gif', webp=None)
    assert media_items([threads_gif]) == []

    # Stories are Instagram's own links, never posts, and need the user's sign-in.
    assert not META_POST.match('https://www.instagram.com/stories/tv/3170000000000000000/')
    assert IG_STORY.match('https://www.instagram.com/stories/nasa/3170000000000000000?utm_source=ig_story_item_share').groups() == ('nasa', '3170000000000000000')
    assert IG_STORY.match('https://www.instagram.com/stories/highlights/17990651084283579/').groups() == ('highlights', '17990651084283579')
    assert not SHORT.match('https://www.instagram.com/stories/nasa/')
    assert shared_story('https://www.instagram.com/s/aGlnaGxpZ2h0OjE3OTkwNjUxMDg0MjgzNTc?story_media_id=3171_528817151&igsh=x') == ('1799065108428357', '3171')
    assert shared_story('https://www.instagram.com/s/bm90aGluZw') is None
    for story in ('https://www.instagram.com/stories/nasa/', 'https://www.instagram.com/stories/highlights/17990651084283579/'):
        try:
            items_of(None, story, {})
            assert False, 'a story read with no sign-in'
        except Failure as f:
            assert str(f) == 'login'

    # Cookies go only to the site they belong to, and expired ones nowhere.
    with tempfile.TemporaryDirectory() as d:
        jar = os.path.join(d, 'cookies.txt')
        with open(jar, 'w') as f:
            f.write('# Netscape HTTP Cookie File\n#HttpOnly_.instagram.com\tTRUE\t/\tTRUE\t4102444800\tsessionid\ts3cret\n'
                    '.instagram.com\tTRUE\t/\tTRUE\t1000\tds_user_id\told\n')
        sign_in(jar)
        cookies = next(h.cookiejar for h in LOCAL.opener.handlers if isinstance(h, urllib.request.HTTPCookieProcessor))

        def sent(url):
            r = urllib.request.Request(url)
            cookies.add_cookie_header(r)
            return r.get_header('Cookie')
        assert sent('https://www.instagram.com/api/v1/feed/reels_media/') == 'sessionid=s3cret' and LOCAL.cookies == jar
        assert sent('https://x.com/') is None and sent('https://evilinstagram.com/') is None and sent('http://www.instagram.com/') is None
        sign_in(os.path.join(d, 'missing.txt'))
        assert LOCAL.cookies is None
    # A sign-in the site no longer takes: the save runs again without it, and asks for a new one when
    # the post needs it.
    run = lambda signed, unsigned: lambda c: json.dumps(signed if c else unsigned)
    assert json.loads(unsigned_retry(run({'error': 'broken'}, {'saves': []}), 'c.txt')) == {'saves': []}
    assert json.loads(unsigned_retry(run({'error': 'broken'}, {'error': 'login'}), 'c.txt')) == {'error': 'login'}
    assert json.loads(unsigned_retry(run({'error': 'refused'}, {'error': 'nothing'}), 'c.txt')) == {'error': 'refused'}
    assert json.loads(unsigned_retry(run({'error': 'offline'}, {'saves': []}), 'c.txt')) == {'error': 'offline'}
    print('lodi.py checks: ok')
