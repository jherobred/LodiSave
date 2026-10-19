// Renders the icon PNGs and the Windows .ico from the SVGs in this folder. Small sizes use the
// simplified drawings so the face stays readable. Run from the repo root: npm run icons
import { chromium } from 'playwright';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const source = (size) => (size <= 16 ? 'icon-16.svg' : size <= 32 ? 'icon-32.svg' : size <= 48 ? 'icon-48.svg' : 'icon.svg');

const browser = await chromium.launch({ channel: process.env.CHANNEL || 'msedge' });
const page = await browser.newPage({ deviceScaleFactor: 1 });
const render = async (size) => {
  const svg = fs.readFileSync(path.join(root, 'brand', source(size))).toString('base64');
  await page.setContent(`<body style="margin:0"><img width="${size}" height="${size}" style="display:block" src="data:image/svg+xml;base64,${svg}">`);
  return page.locator('img').screenshot({ omitBackground: true });
};

// An .ico is a directory of PNGs; Windows picks the size it needs.
const ico = (images) => {
  const head = Buffer.alloc(6 + 16 * images.length);
  head.writeUInt16LE(1, 2);
  head.writeUInt16LE(images.length, 4);
  let offset = head.length;
  images.forEach(({ size, png }, i) => {
    const e = 6 + 16 * i;
    head[e] = head[e + 1] = size >= 256 ? 0 : size;
    head.writeUInt16LE(1, e + 4);
    head.writeUInt16LE(32, e + 6);
    head.writeUInt32LE(png.length, e + 8);
    head.writeUInt32LE(offset, e + 12);
    offset += png.length;
  });
  return Buffer.concat([head, ...images.map((i) => i.png)]);
};

const write = (file, data) => {
  fs.mkdirSync(path.dirname(path.join(root, file)), { recursive: true });
  fs.writeFileSync(path.join(root, file), data);
  console.log(file, data.length);
};

for (const size of [16, 32, 48, 128]) write(`extension/icons/${size}.png`, await render(size));
const images = [];
for (const size of [16, 24, 32, 48, 64, 128, 256]) images.push({ size, png: await render(size) });
write('app/icons/icon.ico', ico(images));
write('app/icons/tray.png', await render(32));
await browser.close();
