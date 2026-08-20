// Mints a YouTube proof-of-origin token, to answer one question: does a token lift the ~1MB wall?
// Runs the BotGuard challenge in a real browser because it needs browser globals. Written from the
// protocol, not copied: fetch a challenge, install the VM it names, snapshot it, exchange the
// snapshot for an integrity token, then mint per-identifier tokens from that.
import { chromium } from '/home/dewi/code/awning/node_modules/playwright/index.mjs';

const API_KEY = 'AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw';
const REQUEST_KEY = 'O43z0dpjhgX20SCx4KAo';
const IDENTIFIER = process.argv[2] ?? 'uSMGENDH_QI';
// The second identifier, minted from the SAME generator right after the streaming one.
const PLAYER_IDENTIFIER = process.argv[3] ?? null;

const post = async (path, body) => {
  const r = await fetch(`https://www.youtube.com/api/jnn/v1/${path}`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json+protobuf',
      'x-goog-api-key': API_KEY,
      'x-user-agent': 'grpc-web-javascript/0.1',
      'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36',
    },
    body: JSON.stringify(body),
  });
  if (!r.ok) throw new Error(`${path} -> HTTP ${r.status}`);
  return r.json();
};

// The second element is base64 with every byte shifted down by 97; undoing that yields the JSON.
const descramble = (s) => {
  const raw = Buffer.from(s.replace(/-/g, '+').replace(/_/g, '/').replace(/\./g, '='), 'base64');
  return Buffer.from(raw.map((b) => (b + 97) & 0xff)).toString('utf8');
};

const challengeOf = (created) => {
  const c = JSON.parse(descramble(created[1]));
  return {
    messageId: c[0],
    interpreterJavascript: (c[1] ?? []).find((v) => typeof v === 'string'),
    interpreterHash: c[3],
    program: c[4],
    globalName: c[5],
  };
};

const created = await post('Create', [REQUEST_KEY]);
const challenge = challengeOf(created);
console.log(`[mint] challenge: global=${challenge.globalName} program=${challenge.program.length} interp=${challenge.interpreterJavascript?.length}`);

const browser = await chromium.launch({
  executablePath: '/home/dewi/.cache/ms-playwright/chromium-1169/chrome-linux/chrome',
  args: ['--no-sandbox'],
});
const page = await browser.newPage();
// A stub served AT the youtube.com origin: the VM checks where it is running.
await page.route('**/*', (route) =>
  route.fulfill({ status: 200, contentType: 'text/html', body: '<!doctype html><html><head></head><body></body></html>' }));
await page.goto('https://www.youtube.com/');
page.on('console', (m) => console.log('[page]', m.text().slice(0, 300)));

const snapshot = await page.evaluate(async (ch) => {
  // Installing the interpreter defines a global object named by the challenge.
  new Function(ch.interpreterJavascript)();
  const vm = window[ch.globalName];
  if (!vm || !vm.a) return { error: `no VM at window.${ch.globalName}` };

  // vm.a hands back its async entry points through a callback rather than a return value, so the
  // snapshot function only exists once the VM has had a turn of the event loop.
  let asyncSnapshot = null;
  const collect = (asyncFn) => { asyncSnapshot = asyncFn; };
  const webPoSignalOutput = [];
  vm.a(ch.program, collect, true, undefined, () => {}, [[], []]);
  for (let waited = 0; waited < 5000 && !asyncSnapshot; waited += 10) {
    await new Promise((r) => setTimeout(r, 10));
  }
  if (!asyncSnapshot) return { error: 'the VM never produced a snapshot function' };

  const botguardResponse = await new Promise((resolve) =>
    asyncSnapshot(resolve, [undefined, undefined, webPoSignalOutput, undefined]));
  window.__signal = webPoSignalOutput;
  return { botguardResponse, signals: webPoSignalOutput.length };
}, challenge);

if (snapshot.error) { console.error('[mint] FAILED:', snapshot.error); await browser.close(); process.exit(1); }
console.log(`[mint] botguard response: ${snapshot.botguardResponse.length} chars, ${snapshot.signals} signal(s)`);

const [integrityB64, ttlSeconds] = await post('GenerateIT', [REQUEST_KEY, snapshot.botguardResponse]);
console.log(`[mint] integrity token: ${integrityB64.length} chars, ttl ${ttlSeconds}s`);

// BOTH tokens from ONE generator, streaming first. SmartTube's provider says so outright:
// "The streaming poToken needs to be generated exactly once before generating any other (player)
// tokens." Minting them in separate processes -- which is what this script used to do, once per run --
// gives two tokens from two unrelated BotGuard sessions, and a token from the wrong session is refused
// exactly like no token at all.
const mintBoth = await page.evaluate(async ({ integrityB64, streamingId, playerId }) => {
  const bytesOf = (b64) => {
    const s = atob(b64.replace(/-/g, '+').replace(/_/g, '/').replace(/\./g, '='));
    return new Uint8Array([...s].map((c) => c.charCodeAt(0)));
  };
  const getMinter = window.__signal[0];
  if (!getMinter) throw new Error('no minter in the signal output');
  const mint = getMinter(bytesOf(integrityB64));
  const b64 = (bytes) => btoa(String.fromCharCode(...bytes)).replace(/\+/g, '-').replace(/\//g, '_');
  // Order is the point: streaming first, exactly once, then the player token.
  const streaming = b64(mint(new TextEncoder().encode(streamingId)));
  const player = playerId ? b64(mint(new TextEncoder().encode(playerId))) : null;
  return { streaming, player };
}, { integrityB64, streamingId: IDENTIFIER, playerId: PLAYER_IDENTIFIER });

console.log(`[mint] streaming token (${IDENTIFIER.slice(0, 24)}…): ${mintBoth.streaming.length} chars`);
if (mintBoth.player) console.log(`[mint] player token (${PLAYER_IDENTIFIER}): ${mintBoth.player.length} chars`);
console.log(mintBoth.streaming);
if (mintBoth.player) console.log(mintBoth.player);
await browser.close();
