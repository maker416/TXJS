/* Copyright 2023-2025 RWPP contributors. GNU AGPLv3; https://github.com/Minxyzgo/RWPP/blob/main/LICENSE */
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const test = require('node:test');
const assert = require('node:assert/strict');
const sdkPath = path.join(__dirname, '../../commonMain/resources/game-portal-sdk.js');
const sdk = fs.readFileSync(sdkPath, 'utf8');
const mirror = fs.readFileSync(path.join(__dirname, '../assets/rwpp-game-portal-bridge/sdk.js'), 'utf8');
const driver = fs.readFileSync(path.join(__dirname, '../assets/rwpp-game-portal-bridge/bridge.js'), 'utf8');

function fixture({ iframe = false } = {}) {
  let receive, disconnect;
  const sent = [], events = [];
  const page = { Promise, Object, Error, TypeError, Event: class Event { constructor(type) { this.type = type; } }, dispatchEvent: event => events.push(event.type) };
  const window = { wrappedJSObject: page }; window.top = iframe ? {} : window;
  vm.runInNewContext(sdk + '\n' + driver, {
    window, location: { origin: 'https://game.example' }, setTimeout, clearTimeout, Promise, crypto: require('node:crypto').webcrypto,
    cloneInto: value => structuredClone(value), exportFunction: (fn, target, options) => { if (options) target[options.defineAs] = fn; return fn; },
    browser: { runtime: { connectNative: name => {
      assert.equal(name, 'rwppGamePortal');
      return { onMessage: { addListener: fn => { receive = fn; } }, onDisconnect: { addListener: fn => { disconnect = fn; } }, postMessage: message => sent.push(message) };
    } } },
  });
  return { page, sent, events, receive: message => receive?.(message), disconnect: () => disconnect?.() };
}
test('SDK mirror and manifest keep the portal separate from forum', () => {
  assert.equal(mirror, sdk);
  const manifest = JSON.parse(fs.readFileSync(path.join(__dirname, '../assets/rwpp-game-portal-bridge/manifest.json'), 'utf8'));
  assert.equal(manifest.content_scripts[0].all_frames, false);
  assert.ok(!manifest.permissions.some(value => /cookie|download|upload|webRequest/i.test(value)));
  assert.notEqual(manifest.browser_specific_settings.gecko.id, 'forum-bridge@rwpp');
});
test('iframe and unbound native sessions do not expose rwGame', () => {
  const frame = fixture({ iframe: true }); assert.equal(frame.sent.length, 0); assert.equal(frame.page.rwGame, undefined);
  const page = fixture(); assert.equal(page.page.rwGame, undefined); assert.equal(page.sent[0].type, 'hello');
  page.receive({ type: 'init', origin: 'https://evil.example' }); assert.equal(page.page.rwGame, undefined);
});
test('trusted handshake exposes Promise API and sends only structured parameters', async () => {
  const p = fixture(); p.receive({ type: 'init', origin: 'https://game.example' });
  assert.deepEqual(p.events, ['rwGameReady']);
  const result = p.page.rwGame.getPlayer(); await new Promise(resolve => setImmediate(resolve));
  const envelope = p.sent.at(-1).payload;
  assert.equal(envelope.method, 'getPlayer'); assert.deepEqual(Object.keys(envelope.params), []);
  p.receive({ type: 'reply', payload: { v: 1, id: envelope.id, ok: true, result: { account_id: '9223372036854775807', nickname: '玩家', token: 'must-not-expose', email: 'must-not-expose' } } });
  assert.deepEqual(await result, { accountId: '9223372036854775807', nickname: '玩家' });
  const authorization = p.page.rwGame.authorize({ nonce: 'a'.repeat(64), app_id: 'forged' }); await new Promise(resolve => setImmediate(resolve));
  const auth = p.sent.at(-1).payload; assert.deepEqual(Object.keys(auth.params), ['nonce']);
  p.receive({ type: 'reply', payload: { v: 1, id: auth.id, ok: true, result: { code: 'one-use-code', expires_at: 1234, game_app_id: 'reviewed-game', jwt: 'must-not-expose' } } });
  assert.deepEqual(await authorization, { code: 'one-use-code', expires_at: 1234, game_app_id: 'reviewed-game' });
});
test('join events use camelCase, unsubscribe works, disconnect rejects pending requests', async () => {
  const p = fixture(); p.receive({ type: 'init', origin: 'https://game.example' });
  const values = [], unsubscribe = p.page.rwGame.onJoinStateChanged(value => values.push(value));
  p.receive({ type: 'event', payload: { v: 1, event: 'joinStateChanged', data: { state: 'joining', server_id: 's-1', request_id: 'r-1', endpoint: 'must-not-expose' } } });
  assert.deepEqual(values, [{ state: 'joining', serverId: 's-1', requestId: 'r-1' }]); unsubscribe();
  p.receive({ type: 'event', payload: { v: 1, event: 'joinStateChanged', data: { state: 'joined' } } }); assert.equal(values.length, 1);
  const request = p.page.rwGame.joinServer({ serverId: 's-1', requestId: 'r-1' }); await new Promise(resolve => setImmediate(resolve));
  assert.deepEqual(JSON.parse(JSON.stringify(p.sent.at(-1).payload.params)), { server_id: 's-1', request_id: 'r-1' });
  p.disconnect(); await assert.rejects(request, /closed/);
  await assert.rejects(p.page.rwGame.getServers(), /unavailable/);
});

test('desktop injected SDK has valid syntax, only installs in main frame, and uses the dedicated router', async () => {
  const nativeSource = fs.readFileSync(path.join(__dirname, '../../desktopMain/kotlin/io/github/rwpp/platform/GamePortalBrowser.desktop.kt'), 'utf8');
  const injection = nativeSource.split('internal fun gamePortalDesktopInjection(): String = """')[1].split('""".trimIndent()')[0].replace('${GamePortalBrowserSDK.source}', sdk);
  const records = [], events = [];
  const window = { rwGameQuery: request => { const envelope = JSON.parse(request.request); records.push(envelope); request.onSuccess(JSON.stringify({v:1,id:envelope.id,ok:true,result:{account_id:'101',nickname:'native'}})); }, dispatchEvent: event => events.push(event.type) };
  window.top = window;
  vm.runInNewContext(injection, { window, Event: class {constructor(type){this.type=type;}}, Promise, crypto: require('node:crypto').webcrypto });
  const player = await window.rwGame.getPlayer();
  assert.deepEqual(JSON.parse(JSON.stringify(player)),{accountId:'101',nickname:'native'});
  assert.equal(records[0].method,'getPlayer'); assert.deepEqual(events,['rwGameReady']);
  const frame = { top: {} };
  vm.runInNewContext(injection,{window:frame}); assert.equal(frame.rwGame,undefined);
});
