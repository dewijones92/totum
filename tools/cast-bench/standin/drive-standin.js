// Stand-in Cast receiver: Shaka Player (what the Cast receiver plays DASH with) in a private headless
// Edge, pointed at the manifest Totum serves. Run on Windows: node drive-standin.js <manifest-url> [plan]
// plan: comma-separated steps, e.g. "start,seek:300,seek:60,subs:en,subs:off,wait:5"
const { chromium } = require('playwright-core');

const [, , manifest, planArg = 'start,seek:300,seek:60,subs:en'] = process.argv;
const plan = planArg.split(',').filter(Boolean);
const t0 = Date.now();
const say = (o) => console.log(JSON.stringify({ t: ((Date.now() - t0) / 1000).toFixed(2), ...o }));

const page = `<!doctype html><html><body>
<video id="v" muted playsinline style="width:640px"></video>
<script src="https://cdn.jsdelivr.net/npm/shaka-player@4.11.0/dist/shaka-player.compiled.js"></script>
</body></html>`;

(async () => {
  const browser = await chromium.launch({
    channel: 'msedge',
    headless: true,
    args: ['--autoplay-policy=no-user-gesture-required'],
  });
  const tab = await browser.newPage();
  tab.on('console', (m) => say({ console: m.text().slice(0, 300) }));
  tab.on('requestfailed', (r) => say({ requestFailed: r.url().replace(/\/c\/[0-9a-f]+\//, '/c/…/'), why: r.failure()?.errorText }));
  await tab.setContent(page);
  await tab.waitForFunction(() => window.shaka);
  const loaded = await tab.evaluate(async (url) => {
    shaka.polyfill.installAll();
    const video = document.getElementById('v');
    const player = new shaka.Player();
    await player.attach(video);
    window.player = player;
    window.errors = [];
    player.addEventListener('error', (e) => window.errors.push(`${e.detail.code} ${e.detail.message || ''}`));
    const began = performance.now();
    try {
      await player.load(url);
    } catch (e) {
      return { ok: false, error: `${e.code} ${e.message || ''}`, ms: performance.now() - began };
    }
    video.play().catch(() => {});
    const variants = player.getVariantTracks().map((t) => `${t.videoCodec}/${t.audioCodec} ${t.width}x${t.height}`);
    const texts = player.getTextTracks().map((t) => `${t.language}:${t.label}`);
    return { ok: true, ms: Math.round(performance.now() - began), variants, texts, duration: video.duration };
  }, manifest);
  say({ step: 'load', ...loaded });
  if (!loaded.ok) {
    await browser.close();
    process.exit(2);
  }

  const untilPlaying = (from) => tab.evaluate(async (from) => {
    const video = document.getElementById('v');
    const began = performance.now();
    while (performance.now() - began < 60000) {
      if (!video.paused && video.readyState >= 3 && video.currentTime >= from + 0.5) {
        return { ms: Math.round(performance.now() - began), at: video.currentTime.toFixed(2) };
      }
      await new Promise((r) => setTimeout(r, 50));
    }
    return { ms: null, at: video.currentTime.toFixed(2), stuck: true, readyState: video.readyState, errors: window.errors };
  }, from);

  for (const step of plan) {
    const [kind, arg] = step.split(':');
    if (kind === 'start') {
      say({ step, ...(await untilPlaying(0)) });
    } else if (kind === 'seek') {
      const target = Number(arg);
      await tab.evaluate((s) => { document.getElementById('v').currentTime = s; }, target);
      say({ step, ...(await untilPlaying(target)) });
    } else if (kind === 'subs') {
      const result = await tab.evaluate(async (lang) => {
        const player = window.player;
        if (lang === 'off') {
          await player.setTextTrackVisibility(false);
          return { visible: player.isTextTrackVisible() };
        }
        const track = player.getTextTracks().find((t) => t.language === lang || t.language.startsWith(`${lang}-`));
        if (!track) return { error: `no ${lang} track`, have: player.getTextTracks().map((t) => t.language) };
        player.selectTextTrack(track);
        await player.setTextTrackVisibility(true);
        const video = document.getElementById('v');
        const began = performance.now();
        while (performance.now() - began < 15000) {
          const cues = [...video.textTracks].flatMap((tt) => [...(tt.activeCues || [])].map((c) => c.text));
          if (cues.length) return { language: track.language, cue: cues[0].slice(0, 80), ms: Math.round(performance.now() - began) };
          await new Promise((r) => setTimeout(r, 200));
        }
        return { language: track.language, cue: null, note: 'no cue shown within 15s' };
      }, arg);
      say({ step, ...result });
    } else if (kind === 'toend') {
      const result = await tab.evaluate(async (maxS) => {
        const video = document.getElementById('v');
        let stalls = 0;
        let stalledMs = 0;
        let stalledAt = null;
        video.addEventListener('waiting', () => { stalls++; stalledAt = performance.now(); });
        video.addEventListener('playing', () => {
          if (stalledAt !== null) { stalledMs += performance.now() - stalledAt; stalledAt = null; }
        });
        const began = performance.now();
        const from = video.currentTime;
        const jumps = [];
        let lastT = video.currentTime;
        let lastWall = performance.now();
        while (!video.ended && performance.now() - began < maxS * 1000) {
          await new Promise((r) => setTimeout(r, 100));
          const nowWall = performance.now();
          const moved = video.currentTime - lastT;
          const expected = (nowWall - lastWall) / 1000 * video.playbackRate;
          if (Math.abs(moved - expected) > 0.4) {
            jumps.push(`${((nowWall - began) / 1000).toFixed(1)}s: ${lastT.toFixed(2)}->${video.currentTime.toFixed(2)}`);
          }
          lastT = video.currentTime;
          lastWall = nowWall;
        }
        return {
          ended: video.ended, wallS: ((performance.now() - began) / 1000).toFixed(2),
          mediaS: (video.currentTime - from).toFixed(2), stalls, stalledMs: Math.round(stalledMs), jumps, errors: window.errors,
        };
      }, Number(arg));
      say({ step, ...result });
    } else if (kind === 'wait') {
      await tab.waitForTimeout(Number(arg) * 1000);
      say({ step, at: await tab.evaluate(() => document.getElementById('v').currentTime.toFixed(2)), errors: await tab.evaluate(() => window.errors) });
    }
  }
  say({ step: 'done', errors: await tab.evaluate(() => window.errors) });
  await browser.close();
})().catch((e) => {
  say({ fatal: String(e).slice(0, 400) });
  process.exit(1);
});
