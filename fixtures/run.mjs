// End-to-end check. Loads extension/ unpacked in Playwright's Chromium, opens each fixture page,
// saves through the extension's own code paths and compares the saved bytes with the originals.
// Run from the repo root: npm install, then npm test (needs Microsoft Edge, or see CHANNEL below)
import { chromium } from 'playwright';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createRequire } from 'node:module';
import { execFileSync, spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { serve, PAGE, OTHER } from './server.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const ext = path.resolve(here, '../extension');
const media = path.join(here, 'media');
const require = createRequire(import.meta.url);

if (!fs.existsSync(path.join(media, 'dash/manifest.mpd'))) execFileSync(process.execPath, [path.join(here, 'make-media.mjs')], { stdio: 'inherit' });

// Pure pieces first: fast, no browser.
const HLS = require(path.join(ext, 'hls.js'));
const { isYouTube, filenameFor } = require(path.join(ext, 'shared.js'));
const { largest } = require(path.join(ext, 'finder.js'));
const { upgrade } = require(path.join(ext, 'rules.js'));
const MP4 = require(path.join(ext, 'mp4.js'));

// Stream types in a media file, by ffprobe, plus whether ffmpeg decodes it without errors.
const probe = (file) => {
  const s = spawnSync('ffprobe', ['-v', 'error', '-show_entries', 'stream=codec_type', '-of', 'csv=p=0', file], { encoding: 'utf8' });
  const d = spawnSync('ffmpeg', ['-v', 'error', '-i', file, '-f', 'null', '-'], { encoding: 'utf8' });
  return { streams: s.stdout.trim().split(/\r?\n/).filter(Boolean).sort().join('+'), clean: !d.stderr.trim() };
};
{
  const master = HLS.parse('#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=300000\nlow.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=900000,RESOLUTION=640x360\nhigh.m3u8\n', 'https://cdn.test/v/master.m3u8');
  assert.equal(HLS.best(master.variants).url, 'https://cdn.test/v/high.m3u8');
  const fmp4 = HLS.parse('#EXTM3U\n#EXT-X-MAP:URI="init.mp4",BYTERANGE="100@0"\n#EXTINF:1,\n#EXT-X-BYTERANGE:500@100\nall.mp4\n#EXTINF:1,\n#EXT-X-BYTERANGE:500\nall.mp4\n#EXT-X-ENDLIST\n', 'https://cdn.test/v/a.m3u8');
  assert.deepEqual(fmp4.map, { url: 'https://cdn.test/v/init.mp4', range: '0-99' });
  assert.deepEqual(fmp4.segments.map((s) => s.range), ['100-599', '600-1099']);
  assert.equal(fmp4.live, false);
  assert.equal(HLS.parse('#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI="k"\n#EXTINF:1,\na.ts\n', 'https://x.test/').encrypted, 'AES-128');
  assert.throws(() => HLS.parse('<html>', 'https://x.test/'));

  assert.equal(largest('a.jpg 1x, b.jpg 2x'), 'b.jpg');
  assert.equal(largest('a.jpg 100w, b.jpg 50w'), 'a.jpg');
  assert.equal(largest('a.jpg, b.jpg 2x'), 'b.jpg');
  assert.equal(largest('https://res.cloudinary.com/x/w_400,h_300/a.jpg 400w, https://res.cloudinary.com/x/w_1600,h_1200/a.jpg 1600w'), 'https://res.cloudinary.com/x/w_1600,h_1200/a.jpg');

  for (const u of ['https://www.youtube.com/watch?v=x', 'https://youtu.be/x', 'https://m.youtube.com/', 'https://rr1---sn.googlevideo.com/videoplayback', 'https://i.ytimg.com/vi/x/hq.jpg', 'https://www.youtube-nocookie.com/embed/x']) assert.ok(isYouTube(u), u);
  for (const u of ['https://notyoutube.com/', 'https://example.com/youtube.com/x', 'blob:https://a.test/c']) assert.ok(!isYouTube(u), u);
  assert.equal(filenameFor({ url: 'https://a.test/p/photo%20one.jpg?x=1' }), 'photo one.jpg');
  assert.match(filenameFor({ url: 'mse:3', via: 'mse', mime: 'video/webm; codecs="vp9"' }), /^lodisave-mse-\d+\.webm$/);
  assert.match(filenameFor({ url: 'data:image/svg+xml;charset=utf-8,%3Csvg', via: 'svg' }), /\.svg$/);
  assert.equal(filenameFor({ url: 'https://cdn.test/v/master.m3u8', kind: 'hls' }, 'mp4'), 'master.mp4');
  assert.equal(filenameFor({ url: 'https://cdn.test/v/master.m3u8', kind: 'hls' }, 'aac', 'audio'), 'master-audio.aac');
  assert.equal(filenameFor({ url: 'https://pbs.twimg.com/media/Abc1?format=png&name=orig' }), 'Abc1.png');

  // G4: full-size originals for X and Pinterest.
  assert.equal(upgrade('https://pbs.twimg.com/media/GZx1abc?format=jpg&name=small'), 'https://pbs.twimg.com/media/GZx1abc?format=jpg&name=orig');
  assert.equal(upgrade('https://pbs.twimg.com/media/GZx1abc.png:large'), 'https://pbs.twimg.com/media/GZx1abc?format=png&name=orig');
  assert.equal(upgrade('https://pbs.twimg.com/media/GZx1abc?format=jpg&name=orig'), null);
  assert.equal(upgrade('https://pbs.twimg.com/profile_images/123/abc_normal.jpg'), 'https://pbs.twimg.com/profile_images/123/abc.jpg');
  assert.equal(upgrade('https://i.pinimg.com/236x/ab/cd/ef/abcdef.jpg'), 'https://i.pinimg.com/originals/ab/cd/ef/abcdef.jpg');
  assert.equal(upgrade('https://i.pinimg.com/75x75_RS/ab/cd/x.jpg'), 'https://i.pinimg.com/originals/ab/cd/x.jpg');
  assert.equal(upgrade('https://i.pinimg.com/originals/ab/cd/ef/abcdef.jpg'), null);
  assert.equal(upgrade('https://example.com/236x/a.jpg'), null);

  // G2: the audio playlist that goes with a variant.
  const withAudio = HLS.parse('#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="a",NAME="Alt",URI="alt.m3u8"\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="a",NAME="Main",DEFAULT=YES,URI="main.m3u8"\n#EXT-X-STREAM-INF:BANDWIDTH=900000,AUDIO="a"\nv.m3u8\n', 'https://cdn.test/x/master.m3u8');
  assert.equal(HLS.audioFor(withAudio, HLS.best(withAudio.variants)).url, 'https://cdn.test/x/main.m3u8');
  assert.equal(HLS.audioFor(master, HLS.best(master.variants)), null);

  // G1/G2/G7: separate fragmented-MP4 tracks become one file with both.
  const mergedFile = path.join(os.tmpdir(), `lodisave-merge-${process.pid}.mp4`);
  fs.writeFileSync(mergedFile, MP4.merge(fs.readFileSync(path.join(media, 'av/video.mp4')), fs.readFileSync(path.join(media, 'av/audio.mp4'))));
  assert.deepEqual(probe(mergedFile), { streams: 'audio+video', clean: true });
  fs.rmSync(mergedFile);
  assert.equal(MP4.merge(fs.readFileSync(path.join(media, 'clip.webm')), fs.readFileSync(path.join(media, 'av/audio.mp4'))), null);
  const manifest = JSON.parse(fs.readFileSync(path.join(ext, 'manifest.json'), 'utf8'));
  for (const cs of manifest.content_scripts) assert.ok(cs.exclude_matches.includes('*://*.youtube.com/*'), 'YouTube excluded');
  console.log('Unit checks: ok');
}

const sha = (buf) => crypto.createHash('sha256').update(buf).digest('hex');
const original = (...p) => fs.readFileSync(path.join(media, ...p));
const hlsHigh = () => Buffer.concat(fs.readFileSync(path.join(media, 'hls/high/index.m3u8'), 'utf8')
  .split(/\r?\n/).filter((l) => l && !l.startsWith('#')).map((s) => original('hls/high', s)));

const stopServer = await serve();
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'lodisave-'));
const context = await chromium.launchPersistentContext(path.join(tmp, 'profile'), {
  // Edge by default: Playwright's own Chromium 156 fails to start on some Windows PCs (side-by-side
  // error). Set CHANNEL=chromium on machines where it starts.
  channel: process.env.CHANNEL || 'msedge',
  headless: !process.env.HEADED,
  acceptDownloads: true,
  downloadsPath: path.join(tmp, 'downloads'),
  args: [`--disable-extensions-except=${ext}`, `--load-extension=${ext}`],
});
const sw = context.serviceWorkers()[0] || (await context.waitForEvent('serviceworker'));
const extId = new URL(sw.url()).host;

async function open(name) {
  const page = await context.newPage();
  page.on('pageerror', (e) => console.log(`  [${name}] page error: ${e.message}`));
  await page.goto(`${PAGE}/${name}.html`);
  await page.waitForFunction(() => window.__ready || window.__error, null, { timeout: 15000 });
  const err = await page.evaluate(() => window.__error);
  if (err) throw new Error(`fixture error: ${err}`);
  const tabId = await sw.evaluate((url) => chrome.tabs.query({}).then((ts) => ts.findLast((t) => t.url === url)?.id), page.url());
  return { page, tabId };
}
const scan = (tabId) => sw.evaluate((id) => self.lodi.scanTab(id), tabId);
const save = (tabId, item) => sw.evaluate(([id, it]) => self.lodi.save(id, it), [tabId, item]);
async function rightClick(page, tabId, selector, shift = false) {
  const box = await page.locator(selector).boundingBox();
  if (shift) await page.keyboard.down('Shift');
  await page.mouse.click(box.x + box.width / 2, box.y + box.height / 2, { button: 'right' });
  if (shift) await page.keyboard.up('Shift');
  return sw.evaluate(([id, url]) => self.lodi.menuClick({ frameId: 0, pageUrl: url }, { id, url }), [tabId, page.url()]);
}
const saved = (r) => {
  if (!r.ok) throw new Error(r.error);
  return fs.readFileSync(r.filename);
};
const same = (r, expected) => {
  const got = saved(r);
  if (sha(got) !== sha(expected)) throw new Error(`saved ${got.length} bytes that differ from the ${expected.length}-byte original`);
  return `${got.length} bytes, identical to the original`;
};
const find = (res, pred, what) => {
  const it = res.items?.find(pred);
  if (!it) throw new Error(`missing ${what}. Found: ${(res.items || []).map((i) => `${i.via}:${i.url.slice(0, 50)}`).join(', ')} ${res.error || ''} ${(res.errors || []).join(' ')}`);
  return it;
};

const rows = [];
async function check(blocker, how, fn) {
  try {
    rows.push({ blocker, how, pass: true, detail: await fn() });
  } catch (e) {
    rows.push({ blocker, how, pass: false, detail: e.message.split('\n')[0] });
  }
}

await check('Right-click disabled', 'context menu', async () => {
  const { page, tabId } = await open('rightclick');
  const r = await rightClick(page, tabId, '#target');
  const blocked = await page.evaluate(() => window.__blocked);
  if (!blocked) throw new Error('the page did not block the menu, so the fixture is broken');
  const result = same(r, original('a.jpg'));
  await rightClick(page, tabId, '#target', true);
  if ((await page.evaluate(() => window.__blocked)) !== blocked) throw new Error('Shift+right-click still reached the page handler');
  await page.close();
  return `${result}. Shift+right-click skips the page handler`;
});

await check('Transparent overlay + spacer GIF', 'context menu', async () => {
  const { page, tabId } = await open('overlay');
  const r = await rightClick(page, tabId, '#target');
  await page.close();
  return same(r, original('a.jpg'));
});

await check('CSS background (other origin) + ::before', 'context menu', async () => {
  const { page, tabId } = await open('cssbg');
  const r = await rightClick(page, tabId, '#bg');
  find(await scan(tabId), (i) => i.url.endsWith('c.png?pseudo') && i.via === 'css::before', '::before background');
  await page.close();
  return `${same(r, original('b.png'))}. ::before background listed`;
});

{
  const { page, tabId } = await open('canvas');
  const res = await scan(tabId);
  await check('Canvas (readable)', 'popup scan', async () => {
    const png = saved(await save(tabId, find(res, (i) => i.via === 'canvas' && i.url.startsWith('data:image/png'), 'canvas snapshot')));
    assert.equal(png.subarray(1, 4).toString(), 'PNG', 'PNG signature');
    const w = png.readUInt32BE(16), h = png.readUInt32BE(20);
    if (w !== 640 || h !== 360) throw new Error(`snapshot is ${w}×${h}, expected 640×360`);
    const src = find(res, (i) => i.url === `${PAGE}/media/b.png?canvas` && i.via === 'canvas-src', 'drawImage source');
    return `PNG snapshot ${w}×${h}. Source image: ${same(await save(tabId, src), original('b.png'))}`;
  });
  await check('Canvas (tainted by another origin)', 'popup scan', async () => {
    find(res, (i) => i.via === 'canvas' && i.unsupported, 'tainted canvas flag');
    const src = find(res, (i) => i.url === `${OTHER}/media/a.jpg?taint`, 'drawImage source');
    return `Flagged unreadable. Source image via drawImage hook: ${same(await save(tabId, src), original('a.jpg'))}`;
  });
  await page.close();
}

await check('MediaSource blob: video', 'context menu', async () => {
  const { page, tabId } = await open('mse');
  const r = await rightClick(page, tabId, '#target');
  await page.close();
  return same(r, original('clip.webm'));
});

await check('HLS (other origin, XHR + fetch)', 'popup scan', async () => {
  const { page, tabId } = await open('hls');
  const res = await scan(tabId);
  find(res, (i) => i.kind === 'hls' && i.url.endsWith('/high/index.m3u8'), 'variant playlist loaded by fetch');
  const r = await save(tabId, find(res, (i) => i.kind === 'hls' && i.url.endsWith('/master.m3u8'), 'master playlist loaded by XHR'));
  await page.close();
  return `Best variant, ${same(r, hlsHigh())}`;
});

await check('DRM (EME) refused', 'popup scan', async () => {
  const { page, tabId } = await open('drm');
  const mode = await page.evaluate(() => window.__drm);
  const it = find(await scan(tabId), (i) => i.via === 'mse', 'MediaSource stream');
  if (!it.drm) throw new Error('stream not flagged as DRM');
  const r = await save(tabId, it);
  if (r.ok) throw new Error('the DRM stream was saved');
  await page.close();
  return `Flagged via ${mode}, bytes dropped, save refused`;
});

await check('Revoked blob: image', 'context menu', async () => {
  const { page, tabId } = await open('blob');
  const r = await rightClick(page, tabId, '#target');
  await page.close();
  return same(r, original('a.jpg'));
});

await check('Hotlink protection (Referer)', 'context menu', async () => {
  const { page, tabId } = await open('hotlink');
  const r = await rightClick(page, tabId, '#target');
  await page.close();
  return same(r, original('a.jpg'));
});

// The saved file must hold both a video and an audio stream that ffmpeg decodes cleanly.
const bothTracks = (r) => {
  if (!r.ok) throw new Error(r.error);
  const p = probe(r.filename);
  if (p.streams !== 'audio+video' || !p.clean) throw new Error(`saved file has ${p.streams || 'no streams'}${p.clean ? '' : ' and decode errors'}`);
  return `one file with video + audio, ${fs.statSync(r.filename).size} bytes, decodes cleanly`;
};

await check('G2 HLS with a separate audio playlist', 'popup scan', async () => {
  const { page, tabId } = await open('hls-audio');
  const r = await save(tabId, find(await scan(tabId), (i) => i.kind === 'hls' && i.url.endsWith('/hls2/master.m3u8'), 'HLS master playlist'));
  await page.close();
  return bothTracks(r);
});

await check('G7 DASH (SegmentTimeline, separate audio)', 'popup scan', async () => {
  const { page, tabId } = await open('dash');
  const it = find(await scan(tabId), (i) => i.kind === 'dash', 'DASH manifest');
  if (it.unsupported) throw new Error(it.unsupported);
  const r = await save(tabId, it);
  await page.close();
  return bothTracks(r);
});

await check('G1 MediaSource video + audio buffers', 'context menu', async () => {
  const { page, tabId } = await open('mse-av');
  const r = await rightClick(page, tabId, '#target');
  await page.close();
  return bothTracks(r);
});

await check('G3 Partly buffered stream', 'popup scan', async () => {
  const { page, tabId } = await open('mse-partial');
  const it = find(await scan(tabId), (i) => i.via === 'mse', 'MediaSource stream');
  if (!/^Captured 0:0[01] of 0:02\./.test(it.partial || '')) throw new Error(`no partial warning (${it.partial})`);
  const r = await save(tabId, it);
  await page.close();
  if (!r.ok || r.warning !== it.partial) throw new Error(r.error || 'the save lost the warning');
  return `saves what was buffered and warns: "${it.partial}"`;
});

await check('G5 Memory cap frees finished streams first', 'popup scan', async () => {
  const { page, tabId } = await open('mse-evict');
  const streams = (await scan(tabId)).items.filter((i) => i.via === 'mse');
  await page.close();
  const freed = streams.filter((i) => /Freed/.test(i.unsupported || '')), kept = streams.filter((i) => i.bytes);
  if (freed.length !== 1 || kept.length !== 2) throw new Error(`${freed.length} freed and ${kept.length} kept, expected 1 and 2`);
  return 'the finished stream was freed, both playing ones kept';
});

await check('G4 Full-size URL falls back to the page copy', 'service worker', async () => {
  const r = await save(1, { url: `${PAGE}/media/missing-original.jpg`, alts: [`${PAGE}/media/a.jpg?shown`], kind: 'image', via: 'img', page: `${PAGE}/` });
  return `${same(r, original('a.jpg'))} after the original 404ed`;
});

{
  const { page, tabId } = await open('misc');
  await check('Finder coverage', 'popup scan', async () => {
    const res = await scan(tabId);
    const want = {
      'og:image': 'large.jpg?og', 'srcset largest': 'large.jpg?srcset', picture: 'large.jpg?picture', 'shadow root': 'a.jpg?shadow',
      'same-origin iframe': 'a.jpg?iframe', 'svg <image>': 'a.jpg?svgimage', 'media link': 'large.jpg?link',
      video: 'clip.webm?progressive', poster: 'b.png?poster', 'script-only image': 'a.jpg?jsonly',
    };
    for (const [what, tail] of Object.entries(want)) find(res, (i) => i.url.endsWith(tail), what);
    find(res, (i) => i.via === 'svg', 'inline SVG');
    if (res.items.some((i) => isYouTube(i.url))) throw new Error('the YouTube og:video leaked into results');
    const r = await save(tabId, find(res, (i) => i.url.endsWith('large.jpg?srcset'), 'srcset'));
    same(r, original('large.jpg'));
    return `${Object.keys(want).length + 1} source types found, srcset saved at 1600w, YouTube og:video dropped`;
  });
  await check('Popup grid', 'popup UI', async () => {
    const popup = await context.newPage();
    await popup.setViewportSize({ width: 380, height: 600 });
    await popup.goto(`chrome-extension://${extId}/popup.html?tab=${tabId}`);
    await popup.waitForSelector('figure');
    const tiles = await popup.locator('figure').count();
    const shot = async (name) => {
      if (!process.env.SHOTS) return;
      await popup.waitForTimeout(900);
      for (const colorScheme of ['dark', 'light']) {
        await popup.emulateMedia({ colorScheme });
        await popup.screenshot({ path: path.join(process.env.SHOTS, `popup-${name}-${colorScheme}.png`) });
      }
    };
    await shot('found');
    const button = popup.locator('figure', { hasText: 'srcset' }).first().locator('button.save');
    await button.click();
    await popup.waitForFunction((b) => b.textContent !== 'Saving…', await button.elementHandle());
    if ((await button.textContent()) !== 'Saved') throw new Error(`popup save failed: ${await button.getAttribute('title')}`);
    await shot('saved');
    await popup.close();
    return `${tiles} tiles, Save works from the popup`;
  });
  await page.close();
}

await check('Hover picker: tick 3, save at once', 'in-page UI', async () => {
  const { page } = await open('picker');
  const hover = async (loc) => {
    const b = await loc.boundingBox();
    await page.mouse.move(b.x + b.width / 2, b.y + b.height / 2, { steps: 4 });
  };
  const tick = async (frame, selector) => {
    await hover(frame.locator(selector));
    const box = frame.locator('lodisave-picker .check:not(.on)');
    await box.waitFor({ timeout: 5000 });
    await box.click({ delay: 150 }); // a human-length press, spanning several repaints
    await frame.locator('lodisave-picker .check.on').first().waitFor({ timeout: 3000 });
  };
  await tick(page, '#one img');
  // Like Instagram after going back: the page rebuilds the picked photo. The pick follows it, and
  // ticking it again can't count it twice.
  await page.evaluate(() => { const i = document.querySelector('#one img'); i.replaceWith(i.cloneNode()) });
  await page.locator('lodisave-picker .check.on').first().waitFor({ timeout: 3000 });
  await hover(page.locator('#one img'));
  await page.waitForTimeout(300);
  if (await page.locator('lodisave-picker .check:not(.on)').count()) throw new Error('the rebuilt photo shows as not picked');
  await page.locator('lodisave-picker .bar .text', { hasText: '1 picked' }).waitFor({ timeout: 3000 });
  await tick(page, '#two img'); // under a clear overlay, like Instagram
  await hover(page.locator('#icon'));
  await page.waitForTimeout(400);
  if (await page.locator('lodisave-picker .check:not(.on)').count()) throw new Error('a 32 px icon got a checkbox');
  await tick(page.frameLocator('#embed'), 'img'); // another site's frame
  const bar = page.locator('lodisave-picker .bar');
  await bar.locator('.text', { hasText: '3 picked' }).waitFor({ timeout: 5000 });
  if ((await page.evaluate(() => window.__pageClicks)) || page.url().includes('#opened')) throw new Error('a checkbox click reached the page');
  if (process.env.SHOTS) await page.screenshot({ path: path.join(process.env.SHOTS, 'picker.png') });
  const since = new Date(Date.now() - 1000).toISOString();
  await bar.locator('.save').click();
  await page.waitForFunction(() => document.querySelector('lodisave-picker')?.dataset.state === 'done', null, { timeout: 20000 });
  const text = await bar.locator('.text').textContent();
  const tiles = await bar.locator('.tray .t').count();
  if (process.env.SHOTS) await page.screenshot({ path: path.join(process.env.SHOTS, 'picker-done.png') });
  await page.close();
  if (!text.startsWith('Saved 3!')) throw new Error(`the bar says "${text}"`);
  if (tiles !== 3) throw new Error(`the saving tray showed ${tiles} tiles`);
  const files = await sw.evaluate((t) => chrome.downloads.search({ startedAfter: t, state: 'complete' }).then((d) => d.map((x) => x.filename)), since);
  const got = files.map((f) => sha(fs.readFileSync(f))).sort().join();
  const want = ['a.jpg', 'large.jpg', 'a.jpg'].map((f) => sha(original(f))).sort().join();
  if (got !== want) throw new Error(`saved ${files.length} files that don't match the three originals`);
  return '3 originals saved at once (overlay and cross-site frame included), slow press ticks, rebuilt photo stays picked once, icon skipped, no click reached the page';
});

await check('YouTube refused', 'service worker', async () => {
  const a = await save(1, { url: 'https://rr1---sn.googlevideo.com/videoplayback?id=1', kind: 'video', via: 'network', page: 'https://example.com/' });
  const b = await save(1, { url: 'https://example.com/a.jpg', kind: 'image', via: 'img', page: 'https://www.youtube.com/watch?v=x' });
  if (a.ok || b.ok) throw new Error('a YouTube save went through');
  return 'googlevideo.com URLs and items found on youtube.com are refused';
});

await context.close();
stopServer();

console.log('\n| Blocker | Path | Result | Detail |\n|---|---|---|---|');
for (const r of rows) console.log(`| ${r.blocker} | ${r.how} | ${r.pass ? 'PASS' : 'FAIL'} | ${r.detail.replace(/\|/g, '/')} |`);
const failed = rows.filter((r) => !r.pass).length;
console.log(`\n${rows.length - failed}/${rows.length} passed`);
if (!failed) fs.rmSync(tmp, { recursive: true, force: true });
else console.log(`Downloads kept in ${tmp}`);
process.exit(failed ? 1 : 0);
