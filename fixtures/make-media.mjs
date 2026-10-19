// Writes the fixture media once. Needs ffmpeg on PATH, or FFMPEG=<path to ffmpeg>.
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const media = path.join(path.dirname(fileURLToPath(import.meta.url)), 'media');
const ff = (...args) => execFileSync(process.env.FFMPEG || 'ffmpeg', ['-hide_banner', '-loglevel', 'error', '-y', ...args], { cwd: media, stdio: 'inherit' });
for (const dir of ['hls/low', 'hls/high']) fs.mkdirSync(path.join(media, dir), { recursive: true });

const still = (src, size, out) => ff('-f', 'lavfi', '-i', `${src}=size=${size}`, '-frames:v', '1', out);
still('testsrc2', '800x600', 'a.jpg');
still('testsrc2', '400x300', 'small.jpg');
still('testsrc2', '1600x1200', 'large.jpg');
still('smptebars', '640x360', 'b.png');
still('rgbtestsrc', '320x240', 'c.png');
fs.writeFileSync(path.join(media, 'spacer.gif'), Buffer.from('R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7', 'base64'));

// VP9 WebM, because Playwright's Chromium has no H.264 decoder for MediaSource.
ff('-f', 'lavfi', '-i', 'testsrc2=size=320x180:rate=25', '-t', '2', '-c:v', 'libvpx-vp9', '-b:v', '300k', '-an', 'clip.webm');

for (const [name, size] of [['low', '320x180'], ['high', '640x360']]) {
  ff('-f', 'lavfi', '-i', `testsrc2=size=${size}:rate=25`, '-t', '4', '-c:v', 'libx264', '-g', '25', '-sc_threshold', '0', '-an',
    '-f', 'hls', '-hls_time', '1', '-hls_playlist_type', 'vod', '-hls_segment_filename', `hls/${name}/%d.ts`, `hls/${name}/index.m3u8`);
}
fs.writeFileSync(path.join(media, 'hls/master.m3u8'), [
  '#EXTM3U',
  '#EXT-X-STREAM-INF:BANDWIDTH=300000,RESOLUTION=320x180', 'low/index.m3u8',
  '#EXT-X-STREAM-INF:BANDWIDTH=900000,RESOLUTION=640x360', 'high/index.m3u8', '',
].join('\n'));

// Video and audio as separate fragmented MP4 streams: how DASH, HLS fMP4 and MediaSource players get them.
for (const dir of ['av', 'hls2/v', 'hls2/a', 'dash']) fs.mkdirSync(path.join(media, dir), { recursive: true });
const h264 = ['-c:v', 'libx264', '-g', '25', '-sc_threshold', '0', '-pix_fmt', 'yuv420p'];
const aac = ['-c:a', 'aac', '-b:a', '96k'];
ff('-f', 'lavfi', '-i', 'testsrc2=size=320x180:rate=25', '-t', '3', ...h264, '-an', '-movflags', 'frag_keyframe+empty_moov+default_base_moof', 'av/video.mp4');
ff('-f', 'lavfi', '-i', 'sine=frequency=440:sample_rate=48000:duration=3', ...aac, '-movflags', 'empty_moov+default_base_moof', '-frag_duration', '1000000', 'av/audio.mp4');

const hlsFmp4 = (dir) => ['-f', 'hls', '-hls_time', '1', '-hls_playlist_type', 'vod', '-hls_segment_type', 'fmp4', '-hls_fmp4_init_filename', 'init.mp4', '-hls_segment_filename', `hls2/${dir}/%d.m4s`, `hls2/${dir}/index.m3u8`];
ff('-f', 'lavfi', '-i', 'testsrc2=size=640x360:rate=25', '-t', '4', ...h264, '-an', ...hlsFmp4('v'));
ff('-f', 'lavfi', '-i', 'sine=frequency=330:sample_rate=48000:duration=4', ...aac, ...hlsFmp4('a'));
fs.writeFileSync(path.join(media, 'hls2/master.m3u8'), [
  '#EXTM3U', '#EXT-X-VERSION:7',
  '#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="Main",DEFAULT=YES,AUTOSELECT=YES,URI="a/index.m3u8"',
  '#EXT-X-STREAM-INF:BANDWIDTH=900000,RESOLUTION=640x360,CODECS="avc1.64001e,mp4a.40.2",AUDIO="aud"', 'v/index.m3u8', '',
].join('\n'));

ff('-f', 'lavfi', '-i', 'testsrc2=size=640x360:rate=25', '-f', 'lavfi', '-i', 'sine=frequency=550:sample_rate=48000', '-t', '4',
  '-map', '0:v', '-map', '1:a', ...h264, ...aac, '-f', 'dash', '-seg_duration', '1', '-use_template', '1', '-use_timeline', '1',
  '-init_seg_name', 'init-$RepresentationID$.m4s', '-media_seg_name', 'chunk-$RepresentationID$-$Number%05d$.m4s',
  '-adaptation_sets', 'id=0,streams=v id=1,streams=a', 'dash/manifest.mpd');
console.log('Fixture media written to', media);
