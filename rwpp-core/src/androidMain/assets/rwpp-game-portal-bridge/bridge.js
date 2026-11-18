/* Copyright 2023-2025 RWPP contributors. GNU AGPLv3; https://github.com/Minxyzgo/RWPP/blob/main/LICENSE */
(() => {
  'use strict';
  if (window.top !== window) return;
  const port = browser.runtime.connectNative('rwppGamePortal');
  const pending = new Map(), subscribers = new Set();
  let initialized = false, disconnected = false;
  const transport = {
    request: envelope => new Promise((resolve, reject) => {
      if (disconnected || !initialized || pending.size >= 32) { reject(new Error('Portal bridge unavailable')); return; }
      const timer = setTimeout(() => { pending.delete(envelope.id); reject(new Error('Portal request timed out')); }, 120000);
      pending.set(envelope.id, { resolve, reject, timer });
      port.postMessage({ type: 'request', payload: envelope });
    }),
    subscribe: listener => subscribers.add(listener),
  };
  function expose() {
    const page = window.wrappedJSObject;
    if (!page || !page.Promise || !page.Object) return;
    const api = createRWGame(transport), output = new page.Object();
    for (const method of ['getPlayer', 'authorize', 'getServers', 'joinServer', 'cancelJoin']) {
      exportFunction((arg) => new page.Promise(exportFunction((resolve, reject) => {
        let safe;
        try {
          if (typeof arg === 'string' || arg === undefined) safe = arg;
          else if (arg && typeof arg === 'object') safe = method === 'authorize' ? { nonce: arg.nonce } : { serverId: arg.serverId, requestId: arg.requestId };
          Promise.resolve(api[method](safe)).then(value => resolve(cloneInto(value, page)), error => {
            const failure = new page.Error(error?.message || 'Portal request failed'); failure.code = error?.code || 'request_failed'; reject(failure);
          });
        } catch (_) { reject(new page.Error('Invalid portal request')); }
      }, page)), output, { defineAs: method });
    }
    exportFunction(listener => {
      if (typeof listener !== 'function') throw new page.TypeError('A listener function is required');
      const unsubscribe = api.onJoinStateChanged(value => listener(cloneInto(value, page)));
      return exportFunction(unsubscribe, page);
    }, output, { defineAs: 'onJoinStateChanged' });
    Object.defineProperty(page, 'rwGame', { value: output, writable: false, configurable: false });
    page.dispatchEvent(new page.Event('rwGameReady'));
  }
  port.onMessage.addListener(message => {
    if (disconnected) return;
    if (message?.type === 'init') {
      if (initialized || message.origin !== location.origin) return;
      initialized = true; expose(); return;
    }
    if (!initialized) return;
    const envelope = message?.payload;
    if (message.type === 'reply') {
      const request = pending.get(envelope?.id); if (!request) return;
      pending.delete(envelope.id); clearTimeout(request.timer); request.resolve(envelope);
    } else if (message.type === 'event') {
      for (const listener of subscribers) listener(envelope);
    }
  });
  port.onDisconnect.addListener(() => {
    disconnected = true;
    for (const request of pending.values()) { clearTimeout(request.timer); request.reject(new Error('Portal page closed')); }
    pending.clear(); subscribers.clear();
  });
  port.postMessage({ type: 'hello' });
})();
