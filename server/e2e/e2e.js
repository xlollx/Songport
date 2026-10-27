// End-to-end test of the Songport server's web page, driven with Playwright like a user would,
// against a running container and the local Navidrome and Jellyfin of scripts/live_servers.sh.
// Run it with server/e2e/run.sh (it builds the image, starts everything and cleans up), or by hand:
//   SONGPORT_E2E_URL=http://127.0.0.1:18080 SONGPORT_E2E_PASSWORD=... SONGPORT_E2E_CONTAINER=songport-e2e \
//   SONGPORT_IT_...=... node server/e2e/e2e.js
// Not part of CI: it needs docker, two media servers and a browser.
'use strict';
const { chromium } = require('playwright');
const { execFileSync } = require('child_process');
const fs = require('fs');
const path = require('path');
const http = require('http');

const BASE = (process.env.SONGPORT_E2E_URL || 'http://127.0.0.1:18080').replace(/\/$/, '');
const PASSWORD = process.env.SONGPORT_E2E_PASSWORD || 'e2e pässword';
const CONTAINER = process.env.SONGPORT_E2E_CONTAINER || '';
const JF_CONTAINER = process.env.SONGPORT_E2E_JF_CONTAINER || 'songport-it-jf';
const SHOTS = process.env.SONGPORT_E2E_SHOTS || '';
const ND = { url: process.env.SONGPORT_IT_NAVIDROME_URL, user: process.env.SONGPORT_IT_NAVIDROME_USER, pass: process.env.SONGPORT_IT_NAVIDROME_PASS };
const JF = { url: process.env.SONGPORT_IT_JELLYFIN_URL, user: process.env.SONGPORT_IT_JELLYFIN_USER, pass: process.env.SONGPORT_IT_JELLYFIN_PASS };
const ONLY = process.argv.slice(2);
const XSS_PLAYLIST = '<svg onload=alert(3)>"><script>window.__xss2=1</script> XSS list';
const SRC_PLAYLIST = 'E2E Source';
const ROBUST = 'E2E Robust';
const SUFFIX = `${Date.now() % 100000}`;

const results = [];
function check(area, name, ok, detail = '') {
  results.push({ area, name, ok: !!ok, detail });
  console.log(`${ok ? 'PASS' : 'FAIL'}  [${area}] ${name}${detail ? ' :: ' + detail : ''}`);
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const basic = (user, pass) => 'Basic ' + Buffer.from(`${user}:${pass}`, 'utf8').toString('base64');
const AUTH = basic('admin', PASSWORD);

/** A raw request, so headers (Origin, Authorization, odd paths) are exactly what we say. */
function raw(method, p, { headers = {}, body = null } = {}) {
  const u = new URL(BASE);
  return new Promise((resolve, reject) => {
    const req = http.request({ host: u.hostname, port: u.port, method, path: p, headers: { ...(body ? { 'Content-Type': 'application/x-www-form-urlencoded', 'Content-Length': Buffer.byteLength(body) } : {}), ...headers } }, (res) => {
      let data = '';
      res.setEncoding('utf8');
      res.on('data', (c) => (data += c));
      res.on('end', () => resolve({ status: res.statusCode, headers: res.headers, body: data }));
    });
    req.on('error', reject);
    if (body) req.write(body);
    req.end();
  });
}
const authed = (method, p, opts = {}) => raw(method, p, { ...opts, headers: { Authorization: AUTH, ...(opts.headers || {}) } });

// ------------------------------------------------------------------ the media servers' APIs

async function nd(method, params = {}) {
  const q = new URLSearchParams({ u: ND.user, p: ND.pass, v: '1.16.1', c: 'e2e', f: 'json' });
  for (const [k, v] of Object.entries(params)) (Array.isArray(v) ? v : [v]).forEach((x) => q.append(k, x));
  const r = await fetch(`${ND.url}/rest/${method}?${q}`);
  const j = await r.json();
  if (j['subsonic-response'].status !== 'ok') throw new Error(JSON.stringify(j));
  return j['subsonic-response'];
}
async function ndPlaylist(name) {
  const lists = (await nd('getPlaylists')).playlists.playlist || [];
  const p = lists.find((x) => x.name === name);
  return p ? (await nd('getPlaylist', { id: p.id })).playlist : null;
}
async function ndSongs() { return (await nd('search3', { query: '', songCount: 100 })).searchResult3.song; }

let jfToken = null, jfUser = null;
const JF_AUTH = 'MediaBrowser Client="songport-e2e", Device="e2e", DeviceId="songport-e2e", Version="1"';
async function jf(p, opts = {}) {
  if (!jfToken) {
    const r = await fetch(`${JF.url}/Users/AuthenticateByName`, { method: 'POST', headers: { Authorization: JF_AUTH, 'Content-Type': 'application/json' }, body: JSON.stringify({ Username: JF.user, Pw: JF.pass }) });
    const j = await r.json();
    jfToken = j.AccessToken; jfUser = j.User.Id;
  }
  const r = await fetch(`${JF.url}${p}${p.includes('?') ? '&' : '?'}userId=${jfUser}`, { ...opts, headers: { Authorization: `${JF_AUTH}, Token="${jfToken}"`, ...(opts.headers || {}) } });
  if (r.status === 204) return null;
  return r.json();
}
async function jfPlaylists(name) {
  const j = await jf('/Items?IncludeItemTypes=Playlist&Recursive=true');
  return j.Items.filter((x) => x.Name === name);
}
async function jfPlaylistTracks(id) { return (await jf(`/Playlists/${id}/Items`)).Items.map((x) => x.Name); }
async function jfDeletePlaylists(name) { for (const p of await jfPlaylists(name)) await jf(`/Items/${p.Id}`, { method: 'DELETE' }); }

function docker(...args) { return execFileSync('docker', args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }); }
async function waitHealthy(timeoutMs = 60000) {
  const end = Date.now() + timeoutMs;
  while (Date.now() < end) {
    try { if ((await raw('GET', '/health')).status === 200) return true; } catch (_) { /* starting */ }
    await sleep(500);
  }
  return false;
}

// ------------------------------------------------------------------ the browser

let browser;
const dialogs = [];
async function newPage(opts = {}) {
  const ctx = await browser.newContext({ httpCredentials: { username: 'admin', password: PASSWORD }, viewport: { width: 1280, height: 860 }, ...opts });
  const page = await ctx.newPage();
  page.on('dialog', async (d) => { if (d.type() === 'alert') dialogs.push(d.message()); await d.accept(); });
  page.on('pageerror', (e) => dialogs.push('pageerror: ' + e.message));
  return page;
}
async function xssFired(page) {
  return dialogs.length > 0 || (await page.evaluate(() => !!(window.__xss || window.__xss2)));
}

/** Waits until the sync page shows no run in progress, reloading as its refresh would. */
async function waitIdle(page, jobUrl, timeoutMs = 180000) {
  const end = Date.now() + timeoutMs;
  await sleep(1500);
  while (Date.now() < end) {
    await page.goto(jobUrl);
    const running = await page.locator('form[action$="/stop"]').count();
    if (!running) return true;
    await sleep(2000);
  }
  return false;
}
async function lastRun(page) {
  const card = page.locator('h2:has-text("Last runs") ~ .card').first();
  return (await card.count()) ? (await card.innerText()) : '';
}

async function addService(page, value) {
  await page.goto(`${BASE}/connectors`);
  if (await page.locator(`a[href="/connectors/${value}"]`).count()) return;
  await page.selectOption('select[name=service]', value);
  await page.click('form[action="/connectors/add"] button');
}
async function connect(page, value, url, user, pass) {
  await page.goto(`${BASE}/connectors/${value}`);
  if (await page.locator('form[action$="/disconnect"]').count()) return 'already';
  await page.fill('input[name=url]', url);
  await page.fill('input[name=user]', user);
  await page.fill('input[name=secret]', pass);
  await Promise.all([page.waitForNavigation(), page.click(`form[action="/connectors/${value}/connect"] button`)]);
  return (await page.locator('main').innerText());
}

async function createSync(page, { src = 'subsonic', dst = 'jellyfin', playlist, name, schedule = 'MANUAL', mirror = false }) {
  await page.goto(`${BASE}/syncs/new`);
  await page.selectOption('select[name=src]', src);
  await page.selectOption('select[name=dst]', dst);
  await Promise.all([page.waitForNavigation(), page.click('form[action="/syncs/new"] button')]);
  const opt = page.locator('select[name=srcPlaylist] option', { hasText: playlist }).first();
  await page.selectOption('select[name=srcPlaylist]', await opt.getAttribute('value'));
  await page.selectOption('select[name=dstPlaylist]', '');
  if (name) await page.fill('input[name=name]', name);
  await page.selectOption('select[name=schedule]', schedule);
  if (mirror) await page.check('input[name=mirror]');
  await Promise.all([page.waitForNavigation(), page.click('form[action="/syncs"] button')]);
  return page.url().split('?')[0];
}
async function runNow(page, jobUrl) {
  await page.goto(jobUrl);
  await Promise.all([page.waitForNavigation(), page.click('form[action$="/run"] button')]);
  return waitIdle(page, jobUrl);
}

// ------------------------------------------------------------------ the phases

const state = {};

/** The library of live_servers.sh plus the markup song, on both servers, and fresh test playlists. */
async function fixtures() {
  const end = Date.now() + 240000;
  let n = 0, j = 0;
  while (Date.now() < end) {
    n = (await ndSongs()).length;
    j = (await jf('/Items?IncludeItemTypes=Audio&Recursive=true&Limit=0')).TotalRecordCount;
    if (n >= 13 && j >= 13) break;
    await nd('startScan').catch(() => {});
    await jf('/Library/Refresh', { method: 'POST' }).catch(() => {});
    await sleep(5000);
  }
  if (n < 13 || j < 13) throw new Error(`the markup song is missing: Navidrome ${n}, Jellyfin ${j} songs`);
  const songs = await ndSongs();
  const id = (title) => songs.find((s) => s.title.startsWith(title)).id;
  for (const name of [SRC_PLAYLIST, XSS_PLAYLIST, 'E2E Scheduled', ROBUST, 'E2E Delete']) {
    for (const p of ((await nd('getPlaylists')).playlists.playlist || []).filter((x) => x.name === name)) await nd('deletePlaylist', { id: p.id });
    await jfDeletePlaylists(name);
  }
  await nd('createPlaylist', { name: SRC_PLAYLIST, songId: ['Battery', 'Zitti e buoni', 'Café Noir', 'One More Time', 'Énergie'].map(id) });
  await nd('createPlaylist', { name: XSS_PLAYLIST, songId: ['<script>', 'Orion'].map(id) });
}

async function phaseAuth() {
  const A = 'auth';
  const routes = ['/', '/log', '/connectors', '/connectors/subsonic', '/connectors/ytm', '/syncs/new', '/syncs/x', '/style.css', '/callback', '/callback?state=x&code=y', '/nope'];
  for (const r of routes) {
    const res = await raw('GET', r);
    check(A, `GET ${r} without password is refused`, res.status === 401, `HTTP ${res.status}`);
  }
  for (const r of ['/connectors/add', '/syncs', '/connectors/subsonic/connect', '/connectors/subsonic/disconnect', '/syncs/x/run']) {
    const res = await raw('POST', r, { body: 'service=csv' });
    check(A, `POST ${r} without password is refused`, res.status === 401, `HTTP ${res.status}`);
  }
  const h = await raw('GET', '/health');
  check(A, '/health answers without password', h.status === 200 && h.body === 'ok');
  const wrong = await raw('GET', '/', { headers: { Authorization: basic('admin', 'wrong') } });
  check(A, 'wrong password is refused', wrong.status === 401);
  const prefix = await raw('GET', '/', { headers: { Authorization: basic('admin', PASSWORD.slice(0, -1)) } });
  check(A, 'a prefix of the password is refused', prefix.status === 401);
  const anyUser = await raw('GET', '/', { headers: { Authorization: basic('someone', PASSWORD) } });
  check(A, 'right password (any user name, as documented) is accepted', anyUser.status === 200);
  const bearer = await raw('GET', '/', { headers: { Authorization: 'Bearer ' + PASSWORD } });
  check(A, 'a non-Basic scheme is refused', bearer.status === 401);
  const junk = await raw('GET', '/', { headers: { Authorization: 'Basic %%%' } });
  check(A, 'a malformed Basic header is refused, not a 500', junk.status === 401, `HTTP ${junk.status}`);

  // In the browser: the prompt (no credentials) and wrong credentials, then the right ones.
  const anon = await browser.newContext();
  const ap = await anon.newPage();
  const r0 = await ap.goto(BASE + '/');
  check(A, 'browser without credentials gets 401 + Basic challenge', r0.status() === 401 && /Basic/.test(r0.headers()['www-authenticate'] || ''));
  await anon.close();
  const bad = await browser.newContext({ httpCredentials: { username: 'admin', password: 'nope' } });
  const bp = await bad.newPage();
  const r1 = await bp.goto(BASE + '/');
  check(A, 'browser with a wrong password stays out', r1.status() === 401);
  await bad.close();
  const page = await newPage();
  const r2 = await page.goto(BASE + '/');
  check(A, 'browser with the (non-ASCII) password gets in', r2.status() === 200 && (await page.title()).includes('Songport'));
  await page.context().close();
}

async function phaseSetup() {
  const A = 'accounts';
  const page = await newPage();
  await page.goto(BASE + '/');
  check(A, 'first run: home says to connect a service first', (await page.locator('main').innerText()).includes('Connect a service first'));
  await addService(page, 'subsonic');
  check(A, 'Navidrome service added', page.url().includes('/connectors/subsonic'));
  // Bad credentials, an unreachable server, then the right ones.
  let text = await connect(page, 'subsonic', ND.url, ND.user, 'wrong-password');
  check(A, 'Navidrome wrong password: error shown, not connected', /err/.test(await page.locator('main .card').first().getAttribute('class')) && !(await page.locator('form[action$="/disconnect"]').count()), text.split('\n')[0]);
  text = await connect(page, 'subsonic', 'http://127.0.0.1:9', ND.user, ND.pass);
  check(A, 'Navidrome unreachable URL: error shown', /err/.test(await page.locator('main .card').first().getAttribute('class')), text.split('\n')[0]);
  text = await connect(page, 'subsonic', ND.url, ND.user, ND.pass);
  check(A, 'Navidrome connected', await page.locator('form[action$="/disconnect"]').count() === 1, text.split('\n')[0]);
  await addService(page, 'jellyfin');
  text = await connect(page, 'jellyfin', JF.url, JF.user, 'wrong-password');
  check(A, 'Jellyfin wrong password: error shown', /err/.test(await page.locator('main .card').first().getAttribute('class')), text.split('\n')[0]);
  text = await connect(page, 'jellyfin', JF.url, JF.user, JF.pass);
  check(A, 'Jellyfin connected', await page.locator('form[action$="/disconnect"]').count() === 1, text.split('\n')[0]);
  await page.goto(BASE + '/connectors');
  const list = await page.locator('main').innerText();
  check(A, 'services list shows both connected', (list.match(/connected/g) || []).length >= 2, list.replace(/\n/g, ' | ').slice(0, 200));
  await page.context().close();
}

async function phaseSync() {
  const A = 'sync';
  const page = await newPage();
  await jfDeletePlaylists(SRC_PLAYLIST);
  const src = await ndPlaylist(SRC_PLAYLIST);
  check(A, 'source playlist exists on Navidrome', !!src, src ? `${src.songCount} songs` : 'missing: run live_servers.sh and the e2e fixtures');
  const want = (src.entry || []).map((e) => e.title).sort();
  const jobUrl = await createSync(page, { playlist: SRC_PLAYLIST, name: 'E2E sync', schedule: 'MANUAL' });
  check(A, 'sync created from the form', /\/syncs\/[0-9a-f-]{36}$/.test(jobUrl) && (await page.locator('.card.ok').count()) === 1, jobUrl);
  state.jobUrl = jobUrl;
  const t0 = Date.now();
  const done = await runNow(page, jobUrl);
  const run1 = await lastRun(page);
  check(A, 'first run finishes with tracks added', done && new RegExp(`\\+${want.length} added`).test(run1), `${(Date.now() - t0) / 1000}s: ${run1.replace(/\n/g, ' | ')}`);
  let lists = await jfPlaylists(SRC_PLAYLIST);
  const got = lists.length ? (await jfPlaylistTracks(lists[0].Id)).sort() : [];
  check(A, 'Jellyfin has the playlist with the same tracks', lists.length === 1 && JSON.stringify(got) === JSON.stringify(want), `jf=${JSON.stringify(got)}`);
  // Again: nothing to do.
  await runNow(page, jobUrl);
  const run2 = await lastRun(page);
  lists = await jfPlaylists(SRC_PLAYLIST);
  const got2 = lists.length ? (await jfPlaylistTracks(lists[0].Id)).sort() : [];
  check(A, 'second run is idempotent (+0 −0, same playlist)', /\+0 added · −0 removed/.test(run2) && lists.length === 1 && JSON.stringify(got2) === JSON.stringify(want), run2.replace(/\n/g, ' | '));
  const runs = await page.locator('h2:has-text("Last runs") ~ .card').count();
  check(A, 'two runs in the history', runs === 2, `${runs}`);
  await page.goto(BASE + '/');
  check(A, 'home lists the sync with its last result', (await page.locator('main').innerText()).includes('E2E sync'));

  // Edit: schedule and mirror removals, turn off and on.
  await page.goto(jobUrl);
  await page.selectOption('form[action$="/schedule"] select', 'WEEKLY');
  await page.check('form[action$="/schedule"] input[name=mirror]');
  await Promise.all([page.waitForNavigation(), page.click('form[action$="/schedule"] button')]);
  const edited = await page.locator('main').innerText();
  check(A, 'edit: schedule and mirror saved', edited.includes('weekly') && edited.includes('mirror') && edited.includes('Saved.'));
  await Promise.all([page.waitForNavigation(), page.click('form[action$="/toggle"] button')]);
  check(A, 'edit: turned off', (await page.locator('main').innerText()).includes('Turn on'));
  await Promise.all([page.waitForNavigation(), page.click('form[action$="/toggle"] button')]);
  check(A, 'edit: turned back on', (await page.locator('main').innerText()).includes('Turn off'));

  // Mirror: a track removed from the source goes from the destination.
  await nd('updatePlaylist', { playlistId: src.id, songIndexToRemove: '0' });
  await runNow(page, jobUrl);
  const run3 = await lastRun(page);
  lists = await jfPlaylists(SRC_PLAYLIST);
  const got3 = lists.length ? (await jfPlaylistTracks(lists[0].Id)).sort() : [];
  check(A, 'mirror removals: removed track goes from Jellyfin', /−1 removed/.test(run3) && got3.length === want.length - 1, run3.replace(/\n/g, ' | '));
  await nd('updatePlaylist', { playlistId: src.id, songIdToAdd: src.entry[0].id });
  await page.context().close();
}

async function phaseLog() {
  const page = await newPage();
  await page.goto(BASE + '/log');
  const text = await page.locator('pre').innerText();
  check('log', 'log page shows sync starts and ends, newest first', /\[sync\] end E2E sync/.test(text) && text.indexOf('[sync] end') < text.indexOf('[web] listening'));
  check('log', 'log page does not show passwords', !text.includes(ND.pass) && !text.includes(PASSWORD));
  await page.context().close();
}

async function phaseXss() {
  const A = 'security';
  dialogs.length = 0;
  const page = await newPage();
  const jobUrl = await createSync(page, { playlist: 'XSS list', schedule: 'MANUAL' });
  await runNow(page, jobUrl);
  for (const p of [jobUrl, BASE + '/', BASE + '/log', BASE + '/syncs/new?src=subsonic&dst=jellyfin']) {
    await page.goto(p);
    const html = await page.content();
    check(A, `XSS: no script runs from playlist/track names on ${p.replace(BASE, '')}`, !(await xssFired(page)) && !html.includes('<script>window.__xss'), dialogs.join(';'));
  }
  await page.goto(jobUrl);
  const shown = await page.locator('main').innerText();
  check(A, 'XSS: the names are shown as text', shown.includes('<svg onload=alert(3)>'), shown.slice(0, 120).replace(/\n/g, ' | '));
  // Reflected: the ok / err notices of the query string.
  await page.goto(`${BASE}/?err=${encodeURIComponent('<img src=x onerror=alert(5)>')}&ok=${encodeURIComponent('<script>window.__xss=1</script>')}`);
  check(A, 'XSS: ok/err notices from the query string are escaped', !(await xssFired(page)));
  await page.goto(`${BASE}/syncs/new?src=${encodeURIComponent('"><script>window.__xss=1</script>')}&dst=jellyfin`);
  check(A, 'XSS: unknown src/dst parameters are harmless', !(await xssFired(page)));
  state.xssJob = jobUrl;
  await Promise.all([page.waitForNavigation(), page.goto(jobUrl)]);
  await page.context().close();
}

async function phaseCsrf() {
  const A = 'security';
  // Another site's page posting to the server, with the user's saved credentials: refused.
  const evil = http.createServer((req, res) => {
    res.setHeader('Content-Type', 'text/html');
    res.end(`<form id=f method=post action="${BASE}/connectors/add"><input name=service value=plex></form><script>f.submit()</script>`);
  });
  await new Promise((r) => evil.listen(0, '127.0.0.1', r));
  const evilUrl = `http://localhost:${evil.address().port}/`;
  const page = await newPage();
  await page.goto(BASE + '/');
  const resp = page.waitForResponse((r) => r.url().startsWith(BASE + '/connectors/add'));
  await page.goto(evilUrl);
  const r = await resp;
  check(A, 'CSRF: a cross-site form post is refused', r.status() === 403, `HTTP ${r.status()}`);
  // A cross-site GET that would change something (an image tag, a link).
  const before = (await authed('GET', '/connectors/subsonic')).body.includes('/disconnect');
  evil.removeAllListeners('request');
  evil.on('request', (req, res) => { res.setHeader('Content-Type', 'text/html'); res.end(`<img src="${BASE}/connectors/subsonic/disconnect"><img src="${BASE}/connectors/jellyfin/remove">`); });
  await page.goto(evilUrl);
  await sleep(1000);
  const after = (await authed('GET', '/connectors/subsonic')).body.includes('/disconnect');
  const listed = (await authed('GET', '/connectors')).body.includes('href="/connectors/jellyfin"');
  check(A, 'CSRF: a cross-site GET cannot disconnect or remove a service', before && after && listed, `connected before=${before} after=${after}, jellyfin listed=${listed}`);
  evil.close();
  await page.context().close();
  // Direct requests: forged Origin, null Origin, cross-site fetch metadata.
  for (const [name, headers] of [['foreign Origin', { Origin: 'http://evil.example' }], ['null Origin', { Origin: 'null' }], ['Sec-Fetch-Site cross-site without Origin', { 'Sec-Fetch-Site': 'cross-site' }], ['foreign Referer without Origin', { Referer: 'http://evil.example/x' }]]) {
    const res = await authed('POST', '/connectors/add', { headers, body: 'service=plex' });
    check(A, `CSRF: POST with ${name} is refused`, res.status === 403, `HTTP ${res.status}`);
  }
  const same = await authed('POST', '/connectors/add', { headers: { Origin: BASE }, body: 'service=csv' });
  check(A, 'CSRF: same-origin POST still works', same.status === 303, `HTTP ${same.status}`);
  // GET on every state-changing route changes nothing.
  for (const p of ['/connectors/csv/remove', '/connectors/subsonic/disconnect', '/connectors/subsonic/settings']) {
    const res = await authed('GET', p);
    check(A, `GET ${p} does not act`, res.status === 405 || res.status === 404, `HTTP ${res.status}`);
  }
  const still = (await authed('GET', '/connectors/subsonic')).body.includes('/disconnect');
  check(A, 'Navidrome still connected after the GETs', still);
}

async function phaseTraversal() {
  const A = 'security';
  const paths = ['/style.css/../../../etc/passwd', '/..%2f..%2f..%2fetc%2fpasswd', '/%2e%2e/%2e%2e/etc/passwd', '/connectors/..%2F..%2F..%2Fetc%2Fpasswd', '/syncs/..%2F..%2Fdata%2Ftokens.json', '/syncs/..%2Fstore.json', '/songport.log', '/tokens.json', '/data/tokens.json', '/style.css%00.txt'];
  for (const p of paths) {
    const res = await authed('GET', p);
    check(A, `traversal: GET ${p} leaks nothing`, !/root:x:0|accessToken|"jobs"/.test(res.body) && res.status !== 500, `HTTP ${res.status}`);
  }
  // A playlist id that walks out of the CSV folder (a form value, not a link).
  await authed('POST', '/connectors/add', { headers: { Origin: BASE }, body: 'service=csv' });
  const body = new URLSearchParams({ src: 'subsonic', dst: 'csv', srcPlaylist: (await ndPlaylist(SRC_PLAYLIST)).id, dstPlaylist: '../../tmp/e2e-escape', name: 'traversal', schedule: 'MANUAL' }).toString();
  const res = await authed('POST', '/syncs', { headers: { Origin: BASE }, body });
  const loc = res.headers.location || '';
  check(A, 'traversal: a destination playlist id outside the CSV folder is refused', res.status === 303 && loc.includes('err='), `HTTP ${res.status} → ${loc}`);
  const body2 = new URLSearchParams({ src: 'csv', dst: 'jellyfin', srcPlaylist: '../tokens', name: 'traversal2', schedule: 'MANUAL' }).toString();
  const res2 = await authed('POST', '/syncs', { headers: { Origin: BASE }, body: body2 });
  check(A, 'traversal: a source playlist id outside the CSV folder is refused', res2.status === 303 && (res2.headers.location || '').includes('err='), `HTTP ${res2.status} → ${res2.headers.location}`);
  // Clean up whatever got created.
  for (const id of [loc, res2.headers.location || ''].map((l) => (l.match(/\/syncs\/([0-9a-f-]{36})/) || [])[1]).filter(Boolean)) {
    await authed('POST', `/syncs/${id}/delete`, { headers: { Origin: BASE } });
  }
}

/**
 * A proxy in front of Jellyfin that the test can break: 'pass', 'slow' (each answer delayed),
 * 'fail-writes' (POST/DELETE connections cut), 'hang' (requests never answered), 'down' (all cut).
 */
function jellyfinProxy() {
  const target = new URL(JF.url);
  const proxy = { mode: 'pass', hung: [] };
  proxy.server = http.createServer((req, res) => {
    const write = req.method !== 'GET';
    if (proxy.mode === 'down' || (proxy.mode === 'fail-writes' && write)) return req.socket.destroy();
    if (proxy.mode === 'hang') return proxy.hung.push(res);
    const go = () => {
      const up = http.request({ host: target.hostname, port: target.port, method: req.method, path: req.url, headers: { ...req.headers, host: target.host } }, (r) => {
        res.writeHead(r.statusCode, r.headers);
        r.pipe(res);
      });
      up.on('error', () => res.destroy());
      req.pipe(up);
    };
    if (proxy.mode === 'slow') setTimeout(go, 1500); else go();
  });
  proxy.release = () => { proxy.hung.splice(0).forEach((r) => r.socket && r.socket.destroy()); };
  return new Promise((r) => proxy.server.listen(0, '127.0.0.1', () => { proxy.url = `http://127.0.0.1:${proxy.server.address().port}`; r(proxy); }));
}

async function reconnect(page, value, url, user, pass) {
  await page.goto(`${BASE}/connectors/${value}`);
  if (await page.locator('form[action$="/disconnect"]').count()) {
    await Promise.all([page.waitForNavigation(), page.click('form[action$="/disconnect"] button')]);
  }
  return connect(page, value, url, user, pass);
}

async function jobOnHome(name) {
  const m = (await authed('GET', '/')).body.match(new RegExp(`href="(/syncs/[0-9a-f-]{36})"><b>${name}</b>`));
  return m ? BASE + m[1] : null;
}

async function phaseRobust() {
  const A = 'robustness';
  const page = await newPage();
  // Its own playlists: a source of two songs, synced to a new Jellyfin playlist, then grown a song at a time.
  const songs = await ndSongs();
  const songId = (title) => songs.find((s) => s.title === title).id;
  const old = await ndPlaylist(ROBUST);
  if (old) await nd('deletePlaylist', { id: old.id });
  await jfDeletePlaylists(ROBUST);
  await nd('createPlaylist', { name: ROBUST, songId: [songId('Battery'), songId('Zitti e buoni')] });
  const srcList = await ndPlaylist(ROBUST);
  const addToSource = async (title) => nd('updatePlaylist', { playlistId: srcList.id, songIdToAdd: songId(title) });
  const targetTracks = async () => { const l = await jfPlaylists(ROBUST); return { lists: l.length, tracks: l.length ? await jfPlaylistTracks(l[0].Id) : [] }; };
  const jobUrl = await createSync(page, { playlist: ROBUST, name: 'E2E robust' });
  const id = jobUrl.split('/').pop();
  await runNow(page, jobUrl);
  check(A, 'robustness sync: first run', /\+2 added/.test(await lastRun(page)), (await lastRun(page)).replace(/\n/g, ' | '));

  // Concurrent runs: ten posts at once start one run.
  const startsOf = async () => ((await authed('GET', '/log')).body.match(/\[sync\] start E2E robust \(manual\)/g) || []).length;
  const startsBefore = await startsOf();
  await addToSource('Coraline');
  const posts = await Promise.all(Array.from({ length: 10 }, () => authed('POST', `/syncs/${id}/run`, { headers: { Origin: BASE } })));
  check(A, 'concurrent: ten run posts all answered', posts.every((p) => p.status === 303), posts.map((p) => p.status).join(','));
  await waitIdle(page, jobUrl);
  check(A, 'concurrent: only one run started', (await startsOf()) - startsBefore === 1, `${(await startsOf()) - startsBefore} runs`);
  let t = await targetTracks();
  check(A, 'concurrent: no duplicate playlist or tracks on Jellyfin', t.lists === 1 && new Set(t.tracks).size === t.tracks.length && t.tracks.includes('Coraline'), `${t.lists} playlists, ${t.tracks.length} tracks`);

  // A stale Stop (the run had ended before the click) must not stop the next run.
  await authed('POST', `/syncs/${id}/stop`, { headers: { Origin: BASE } });
  await runNow(page, jobUrl);
  check(A, 'a Stop posted when nothing runs does not stop the next run', /\+0 added/.test(await lastRun(page)), (await lastRun(page)).replace(/\n/g, ' | '));

  // Jellyfin through a proxy the test can break.
  const proxy = await jellyfinProxy();
  const re = await reconnect(page, 'jellyfin', proxy.url, JF.user, JF.pass);
  check(A, 'Jellyfin reconnected through the test proxy', await page.locator('form[action$="/disconnect"]').count() === 1, re.split('\n')[0]);

  // Target down from the start: the run fails, and says why.
  proxy.mode = 'down';
  await addToSource('Digital Love');
  await runNow(page, jobUrl);
  let run = await lastRun(page);
  check(A, 'target down: the run fails with the error on the page', !/added/.test(run) && /err/.test(await page.locator('h2:has-text("Last runs") ~ .card p').first().getAttribute('class')), run.replace(/\n/g, ' | '));
  // Target down mid-sync: the reads work, every write is cut. The engine reports the tracks it
  // could not add next to those not found (with the error), and adds them on the next run.
  proxy.mode = 'fail-writes';
  await runNow(page, jobUrl);
  run = await lastRun(page);
  const listed = await page.locator('h2:has-text("Last runs") ~ .card details li').allTextContents();
  check(A, 'target down mid-sync: the track not added is listed with the error', !/\+1 added/.test(run) && listed.some((l) => l.includes('Digital Love') && l.includes('(')), `${run.replace(/\n/g, ' | ')} :: ${listed.join(' ; ')}`);
  const home = await page.goto(BASE + '/');
  check(A, 'target down mid-sync: the server keeps answering', home.status() === 200);
  proxy.mode = 'pass';
  await runNow(page, jobUrl);
  run = await lastRun(page);
  t = await targetTracks();
  check(A, 'target back: the next run catches up, no duplicates', /\+1 added/.test(run) && t.lists === 1 && t.tracks.includes('Digital Love') && new Set(t.tracks).size === t.tracks.length, run.replace(/\n/g, ' | '));

  // Target hanging: the pages still answer, Stop ends the run.
  await addToSource('Home');
  proxy.mode = 'hang';
  await page.goto(jobUrl);
  await Promise.all([page.waitForNavigation(), page.click('form[action$="/run"] button')]);
  await sleep(3000);
  let t0 = Date.now();
  await page.goto(jobUrl);
  check(A, 'target hanging: the page answers at once and shows the run', Date.now() - t0 < 3000 && (await page.locator('form[action$="/stop"]').count()) === 1, `${Date.now() - t0} ms`);
  await Promise.all([page.waitForNavigation(), page.click('form[action$="/stop"] button')]);
  t0 = Date.now();
  const stopped = await waitIdle(page, jobUrl, 200000);
  proxy.release();
  proxy.mode = 'pass';
  run = await lastRun(page);
  check(A, 'target hanging: Stop ends the run within the network timeouts', stopped, `${Math.round((Date.now() - t0) / 1000)} s: ${run.replace(/\n/g, ' | ')}`);

  // Deleting a sync while it runs: it stays deleted.
  proxy.mode = 'slow';
  const delList = await ndPlaylist('E2E Delete');
  if (delList) await nd('deletePlaylist', { id: delList.id });
  await jfDeletePlaylists('E2E Delete');
  await nd('createPlaylist', { name: 'E2E Delete', songId: songs.slice(0, 6).map((x) => x.id) });
  const delUrl = await createSync(page, { playlist: 'E2E Delete', name: 'E2E delete while running' });
  await page.goto(delUrl);
  await Promise.all([page.waitForNavigation(), page.click('form[action$="/run"] button')]);
  await sleep(2500);
  await page.goto(delUrl);
  const wasRunning = (await page.locator('form[action$="/stop"]').count()) === 1;
  await Promise.all([page.waitForNavigation(), page.click('form[action$="/delete"] button')]);
  // Until the run is over (its end line in the log).
  const end = Date.now() + 120000;
  while (Date.now() < end && !/\[sync\] (end )?E2E delete while running/.test((await authed('GET', '/log')).body.split('[sync] start E2E delete while running')[0])) await sleep(2000);
  await sleep(2000);
  const after = await authed('GET', delUrl.replace(BASE, ''));
  check(A, 'deleting a running sync: it stays deleted after the run ends', wasRunning && after.status === 404 && !(await authed('GET', '/')).body.includes('E2E delete while running'), `was running=${wasRunning}, HTTP ${after.status}`);
  proxy.mode = 'pass';

  // Back to Jellyfin directly.
  await reconnect(page, 'jellyfin', JF.url, JF.user, JF.pass);
  proxy.server.close();

  // Bad credentials on a connected service: Jellyfin's token ends behind Songport's back.
  if (CONTAINER) {
    const tokens = JSON.parse(docker('exec', CONTAINER, 'cat', '/data/tokens.json'));
    const tok = tokens.jellyfin.accessToken;
    await fetch(`${JF.url}/Sessions/Logout`, { method: 'POST', headers: { Authorization: `${JF_AUTH}, Token="${tok}"` } });
    await runNow(page, jobUrl);
    run = await lastRun(page);
    check(A, 'revoked credentials: the run fails with a clear error', /401|sign|connect|login|auth/i.test(run) && /err/.test(await page.locator('h2:has-text("Last runs") ~ .card p').first().getAttribute('class')), run.replace(/\n/g, ' | '));
    await reconnect(page, 'jellyfin', JF.url, JF.user, JF.pass);
    await addToSource('Café Noir');
    await runNow(page, jobUrl);
    run = await lastRun(page);
    t = await targetTracks();
    check(A, 'revoked credentials: connecting again fixes it', /\+\d+ added/.test(run) && t.tracks.includes('Café Noir') && new Set(t.tracks).size === t.tracks.length && !/err/.test(await page.locator('h2:has-text("Last runs") ~ .card p').first().getAttribute('class')), run.replace(/\n/g, ' | '));
  }
  await page.context().close();
}

async function phaseLocale() {
  const A = 'locale';
  const ctx = await browser.newContext({ httpCredentials: { username: 'admin', password: PASSWORD }, locale: 'de-DE', extraHTTPHeaders: { 'Accept-Language': 'de-DE,de;q=0.9' } });
  const page = await ctx.newPage();
  for (const p of ['/', '/connectors', '/connectors/subsonic', '/syncs/new', '/log']) {
    const r = await page.goto(BASE + p);
    check(A, `Accept-Language de: ${p} renders`, r.status() === 200 && (await page.locator('h1').count()) === 1, `HTTP ${r.status()}`);
  }
  const lang = await page.getAttribute('html', 'lang');
  const text = await (await page.goto(BASE + '/connectors/subsonic')) && (await page.locator('main').innerText());
  state.localeSample = text.slice(0, 200);
  check(A, 'html lang matches the language of the texts', lang === (process.env.SONGPORT_E2E_LANG || 'en'), `lang=${lang}`);
  await ctx.close();
}

async function phaseYtm() {
  const A = 'ytm';
  const page = await newPage();
  await addService(page, 'ytm');
  await page.goto(BASE + '/connectors/ytm');
  check(A, 'setup page shows the unofficial warning and the cookie form', (await page.locator('textarea[name=cookies]').count()) === 1 && (await page.locator('main').innerText()).includes('unofficial'));
  // Empty: the browser's own check stops it.
  await page.click('form[action="/connectors/ytm/connect"] button');
  check(A, 'empty cookies: the form is not sent', page.url().endsWith('/connectors/ytm'));
  // Empty, forced past the browser: the server refuses it too.
  const empty = await authed('POST', '/connectors/ytm/connect', { headers: { Origin: BASE }, body: 'cookies=&userAgent=' });
  check(A, 'empty cookies posted directly: error, not connected', empty.status === 303 && /err=/.test(empty.headers.location || ''), empty.headers.location);
  for (const bad of ['not cookies at all', 'SID=abc; HSID=def', '# Netscape HTTP Cookie File\n.youtube.com\tTRUE\t/\tTRUE\t0\tSID\tx', '[{"name":"SID","value":"x"}]', '<script>window.__xss=1</script>']) {
    await page.goto(BASE + '/connectors/ytm');
    await page.fill('textarea[name=cookies]', bad);
    await page.fill('input[name=userAgent]', '<b>ua</b>');
    await Promise.all([page.waitForNavigation(), page.click('form[action="/connectors/ytm/connect"] button')]);
    const msg = await page.locator('main .card').first().innerText();
    check(A, `invalid cookies ${JSON.stringify(bad.slice(0, 24))}: error shown, not connected`, /SAPISID/.test(msg) && (await page.locator('form[action$="/disconnect"]').count()) === 0 && !(await xssFired(page)), msg.slice(0, 100));
  }
  const log = (await authed('GET', '/log')).body;
  check(A, 'pasted cookies are not written to the log', !log.includes('HSID=def') && !log.includes('not cookies at all'));
  await page.context().close();
}

async function phaseShots() {
  if (!SHOTS) return;
  fs.mkdirSync(SHOTS, { recursive: true });
  const pages = [['home', '/'], ['services', '/connectors'], ['navidrome', '/connectors/subsonic'], ['ytm-setup', '/connectors/ytm'], ['spotify-setup', '/connectors/spotify'], ['new-sync', '/syncs/new'], ['new-sync-2', '/syncs/new?src=subsonic&dst=jellyfin'], ['sync', state.jobUrl ? state.jobUrl.replace(BASE, '') : null], ['sync-xss', state.xssJob ? state.xssJob.replace(BASE, '') : null], ['log', '/log'], ['not-found', '/nope']].filter((p) => p[1]);
  for (const [w, h, tag] of [[1280, 860, 'desktop'], [390, 844, 'phone']]) {
    const page = await newPage({ viewport: { width: w, height: h }, deviceScaleFactor: tag === 'phone' ? 2 : 1, isMobile: tag === 'phone', hasTouch: tag === 'phone' });
    for (const [name, p] of pages) {
      await page.goto(BASE + p);
      const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
      check('layout', `${name} @${tag}: no horizontal scroll`, overflow <= 0, `overflow ${overflow}px`);
      await page.screenshot({ path: path.join(SHOTS, `${name}-${tag}.png`), fullPage: true });
    }
    await page.context().close();
  }
}

async function phaseSchedule() {
  // An hourly sync that never ran is due at once: the scheduler (every minute, 15 s after start) runs it.
  const A = 'schedule';
  const page = await newPage();
  await jfDeletePlaylists('E2E Scheduled');
  await nd('createPlaylist', { name: 'E2E Scheduled', songId: (await ndSongs()).slice(0, 2).map((s) => s.id) }).catch(() => {});
  const jobUrl = await createSync(page, { playlist: 'E2E Scheduled', name: 'E2E scheduled', schedule: 'HOURLY' });
  const end = Date.now() + 150000;
  let ran = false;
  while (Date.now() < end && !ran) {
    await sleep(5000);
    ran = /\[sync\] start E2E scheduled \(scheduled\)/.test((await authed('GET', '/log')).body);
  }
  check(A, 'an hourly sync never run is started by the scheduler', ran);
  await waitIdle(page, jobUrl);
  const lists = await jfPlaylists('E2E Scheduled');
  check(A, 'the scheduled run created the playlist on Jellyfin', lists.length === 1);
  await page.goto(jobUrl);
  const txt = await page.locator('main').innerText();
  check(A, 'the next run is shown about an hour later', /next \d{4}-\d\d-\d\d \d\d:\d\d/.test(txt), (txt.match(/Last run[^\n]*/) || [''])[0]);
  state.scheduledJob = jobUrl;
  await page.context().close();
}

async function phasePersist() {
  const A = 'persistence';
  if (!CONTAINER) return check(A, 'restart (needs SONGPORT_E2E_CONTAINER)', false, 'skipped');
  const before = (await authed('GET', '/')).body;
  const jobsBefore = (before.match(/href="\/syncs\/[0-9a-f-]{36}"/g) || []).length;
  const runsBefore = state.jobUrl ? ((await authed('GET', state.jobUrl.replace(BASE, ''))).body.match(/<b>\d{4}-\d\d-\d\d/g) || []).length : 0;
  docker('restart', CONTAINER);
  check(A, 'the container comes back after a restart', await waitHealthy());
  const after = (await authed('GET', '/')).body;
  const jobsAfter = (after.match(/href="\/syncs\/[0-9a-f-]{36}"/g) || []).length;
  check(A, 'syncs survive the restart', jobsBefore > 0 && jobsAfter === jobsBefore, `${jobsBefore} → ${jobsAfter}`);
  const svc = (await authed('GET', '/connectors')).body;
  check(A, 'connected accounts survive the restart', (svc.match(/class="ok">connected/g) || []).length >= 2);
  if (state.jobUrl) {
    const runsAfter = ((await authed('GET', state.jobUrl.replace(BASE, ''))).body.match(/<b>\d{4}-\d\d-\d\d/g) || []).length;
    check(A, 'run history survives the restart', runsBefore > 0 && runsAfter === runsBefore, `${runsBefore} → ${runsAfter}`);
    const page = await newPage();
    await runNow(page, state.jobUrl);
    check(A, 'a sync still runs after the restart', /added/.test(await lastRun(page)) && /ok/.test(await page.locator('h2:has-text("Last runs") ~ .card p').first().getAttribute('class')), (await lastRun(page)).replace(/\n/g, ' | '));
    await page.context().close();
  }
  const files = docker('exec', CONTAINER, 'sh', '-c', 'ls -ln /data; stat -c "%a %U %n" /data/tokens.json /data/store.json');
  const tokensMode = (files.match(/(\d+) \S+ \/data\/tokens\.json/) || [])[1];
  check(A, 'tokens.json is readable by its owner only (as the guide says)', tokensMode === '600', files.split('\n').slice(-3).join(' | '));
  const tokens = docker('exec', CONTAINER, 'cat', '/data/tokens.json');
  state.tokensPlain = tokens.includes(ND.pass);
  check(A, 'no secret in store.json or the log', !docker('exec', CONTAINER, 'cat', '/data/store.json').includes(ND.pass) && !docker('exec', CONTAINER, 'cat', '/data/songport.log').includes(ND.pass));
}

async function phaseDelete() {
  const A = 'sync';
  const page = await newPage();
  for (const u of [state.xssJob, state.scheduledJob].filter(Boolean)) {
    await page.goto(u);
    await Promise.all([page.waitForNavigation(), page.click('form[action$="/delete"] button')]);
    const gone = await raw('GET', u.replace(BASE, ''), { headers: { Authorization: AUTH } });
    check(A, 'delete: the sync is gone (the confirm dialog accepted)', gone.status === 404 && page.url() === BASE + '/', `HTTP ${gone.status}`);
  }
  const lists = await jfPlaylists('E2E Scheduled');
  check(A, 'delete: the playlists stay as they are', lists.length === 1);
  await page.context().close();
}

(async () => {
  browser = await chromium.launch();
  await fixtures();
  const phases = { auth: phaseAuth, setup: phaseSetup, sync: phaseSync, log: phaseLog, xss: phaseXss, csrf: phaseCsrf, traversal: phaseTraversal, robust: phaseRobust, locale: phaseLocale, ytm: phaseYtm, schedule: phaseSchedule, shots: phaseShots, persist: phasePersist, delete: phaseDelete };
  for (const [name, fn] of Object.entries(phases)) {
    if (ONLY.length && !ONLY.includes(name)) continue;
    try { await fn(); } catch (e) { check(name, 'phase crashed', false, e.stack.split('\n').slice(0, 3).join(' ')); }
  }
  await browser.close();
  const failed = results.filter((r) => !r.ok);
  console.log(`\n${results.length - failed.length}/${results.length} passed`);
  process.exit(failed.length ? 1 : 0);
})();
