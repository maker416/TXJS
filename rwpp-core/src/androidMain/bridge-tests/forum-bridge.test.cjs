/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */
const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const test = require('node:test');
const path = require('node:path');
const code = fs.readFileSync(path.join(__dirname, '../assets/rwpp-forum-bridge/bridge.js'), 'utf8');
const url = 'https://forum.example/sso/client';

function page({ iframe = false, hasClient = true } = {}) {
  const calls = [], sent = [];
  let receive;
  const window = { wrappedJSObject: hasClient ? { rwForumClient: value => calls.push(value) } : {} };
  window.top = iframe ? {} : window;
  const location = { href: url };
  vm.runInNewContext(code, { window, location, browser: { runtime: { connectNative: name => {
    assert.equal(name, 'rwppForum');
    return { onMessage: { addListener: handler => { receive = handler; } }, postMessage: value => sent.push(value) };
  } } } });
  return { calls, sent, location, receive: value => receive?.(value) };
}

test('ready handshake precedes exactly one ticket delivery', () => {
  const p = page();
  assert.equal(p.sent[0].type, 'ready');
  assert.equal(p.sent[0].hasClient, true);
  const message = { type: 'handoff', url, handoff: 'a'.repeat(64) };
  p.receive(message); p.receive(message);
  assert.deepEqual(p.calls, ['a'.repeat(64)]);
});
test('guest handoff remains null', () => {
  const p = page(); p.receive({ type: 'handoff', url, handoff: null });
  assert.deepEqual(p.calls, [null]);
});
test('wrong URLs, queries, malformed payloads and late navigation cannot receive a ticket', () => {
  for (const message of [null, {}, { type: 'handoff', url: url + '?redirect=evil', handoff: 'a'.repeat(64) },
    { type: 'handoff', url, handoff: "');evil();//" }, { type: 'handoff', url, handoff: {} }, { type: 'handoff', url }]) {
    const p = page(); p.receive(message); assert.deepEqual(p.calls, []);
  }
  const p = page(); p.location.href = 'https://evil.example/sso/client';
  p.receive({ type: 'handoff', url, handoff: 'a'.repeat(64) });
  assert.deepEqual(p.calls, []);
});
test('subframes do not connect; an unavailable bootstrap reports not ready', () => {
  assert.deepEqual(page({ iframe: true }).sent, []);
  const p = page({ hasClient: false });
  assert.equal(p.sent[0].hasClient, false);
  p.receive({ type: 'handoff', url, handoff: 'a'.repeat(64) });
  assert.deepEqual(p.calls, []);
});
