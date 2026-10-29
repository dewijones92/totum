// The PC screen in a headless Edge: opens the receiver page the phone serves, clicks once (as a person must, to
// allow sound), then reports the video every 2 s. Run on Windows: node drive-screen.js <page-url> [seconds] [watch]
// With "watch" it photographs the waiting page and presses its ▶️ Watch here, as Dewi does from the private area.
const { chromium } = require('playwright-core');

const [, , pageUrl, secondsArg = '60', mode = 'click'] = process.argv;
const t0 = Date.now();
const say = (o) => console.log(JSON.stringify({ t: ((Date.now() - t0) / 1000).toFixed(1), ...o }));

(async () => {
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  const tab = await browser.newPage({ viewport: { width: 1280, height: 720 } });
  tab.on('console', (m) => say({ console: m.text().slice(0, 200) }));
  tab.on('requestfailed', (r) => say({ requestFailed: r.url().replace(/key=[^&]+/, 'key=…'), why: r.failure()?.errorText }));
  await tab.goto(pageUrl);
  await tab.waitForFunction(() => document.getElementById('who').textContent.includes('Connected'), null, { timeout: 20000 });
  say({ step: 'connected', who: await tab.textContent('#who') });
  if (mode === 'watch') {
    await tab.waitForFunction(() => !document.getElementById('watch').hidden, null, { timeout: 20000 });
    say({ step: 'waiting page', now: await tab.textContent('#now') });
    await tab.screenshot({ path: 'shots/screen-wait.png' });
    await tab.click('#watch');
    say({ step: 'pressed Watch here' });
  } else {
    await tab.mouse.click(640, 360);
  }
  const until = Date.now() + Number(secondsArg) * 1000;
  while (Date.now() < until) {
    const v = await tab.evaluate(() => {
      const video = document.getElementById('video');
      return {
        stage: document.getElementById('stage').classList.contains('on'),
        time: Math.round(video.currentTime * 10) / 10,
        paused: video.paused,
        rate: video.playbackRate,
        size: `${video.videoWidth}x${video.videoHeight}`,
        subs: [...video.textTracks].map((t) => `${t.kind}:${t.language || t.label || '?'}:${t.mode}:${t.activeCues ? t.activeCues.length : 0}`).join(',') || 'no tracks',
        status: document.getElementById('status').textContent,
      };
    });
    say(v);
    await new Promise((r) => setTimeout(r, 2000));
  }
  await tab.screenshot({ path: 'shots/screen.png' });
  say({ step: 'done', shot: 'C:\\edge-cdp\\shots\\screen.png' });
  await browser.close();
})().catch((e) => { say({ error: e.message }); process.exit(1); });
