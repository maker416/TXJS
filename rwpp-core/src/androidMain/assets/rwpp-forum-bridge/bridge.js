/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

(() => {
  if (window.top !== window) return;
  const port = browser.runtime.connectNative('rwppForum');
  let submitted = false;
  port.onMessage.addListener(message => {
    if (submitted || message?.type !== 'handoff' || location.href !== message.url) return;
    if (message.handoff !== null && (typeof message.handoff !== 'string' || !/^[a-f0-9]{64}$/.test(message.handoff))) return;
    const page = window.wrappedJSObject;
    if (typeof page?.rwForumClient !== 'function') return;
    submitted = true;
    // Only a validated one-use ticket (or guest null) crosses into the page.
    // No arbitrary source code, cookies or long-lived account token is exposed.
    page.rwForumClient(message.handoff);
  });
  port.postMessage({ type: 'ready', hasClient: typeof window.wrappedJSObject?.rwForumClient === 'function' });
})();
