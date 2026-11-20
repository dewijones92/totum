const { chromium } = require('playwright-core');

const [, , pageUrl, secondsArg = '120'] = process.argv;
const t0 = Date.now();
const say = (o) => console.log(JSON.stringify({ t: ((Date.now() - t0) / 1000).toFixed(1), ...o }));

(async () => {
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  const tab = await browser.newPage({ viewport: { width: 1280, height: 720 } });
  tab.on('requestfailed', (r) => say({ requestFailed: r.url().replace(/key=[^&]+/, 'key=…'), why: r.failure()?.errorText }));
  await tab.goto(pageUrl);
  await tab.waitForFunction(() => document.getElementById('who').textContent.includes('Connected'), null, { timeout: 20000 });
  say({ step: 'connected', who: await tab.textContent('#who') });
  const seen = [];
  const until = Date.now() + Number(secondsArg) * 1000;
  let shots = 0;
  while (Date.now() < until) {
    const v = await tab.evaluate(() => {
      const photo = document.getElementById('photo');
      const img = document.getElementById('picture');
      return {
        on: photo.classList.contains('on'),
        src: (img.getAttribute('src') || '').replace(/\/p\/([0-9a-f]+)\/.*/, '/p/$1'),
        size: img.naturalWidth + 'x' + img.naturalHeight,
        status: document.getElementById('status').textContent,
        waitShown: document.getElementById('wait').style.display !== 'none',
      };
    });
    const key = v.on ? v.src : 'off';
    if (seen[seen.length - 1] !== key) {
      seen.push(key);
      say(v);
      if (v.on && v.size !== '0x0') {
        shots += 1;
        await tab.screenshot({ path: `shots/photo-on-screen-${shots}.png` });
      }
    }
    if (seen.length >= 4 && !v.on) break;
    await new Promise((r) => setTimeout(r, 500));
  }
  say({ step: 'done', sequence: seen });
  await browser.close();
})().catch((e) => { say({ error: e.message }); process.exit(1); });
