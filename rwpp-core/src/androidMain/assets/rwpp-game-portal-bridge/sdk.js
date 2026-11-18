/* Copyright 2023-2025 RWPP contributors. GNU AGPLv3; https://github.com/Minxyzgo/RWPP/blob/main/LICENSE */
// Native-injected SDK factory. No account token, secret, endpoint or cookie enters this API.
function createRWGame(transport) {
  'use strict';
  let sequence = 0;
  const listeners = new Set();
  const own = value => value && typeof value === 'object' && !Array.isArray(value);
  const identifier = () => typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
    ? crypto.randomUUID() : 'request-' + Date.now().toString(36) + '-' + (++sequence);
  function invoke(method, params) {
    const envelope = { v: 1, id: identifier(), method, params };
    return Promise.resolve().then(() => transport.request(envelope)).then(reply => {
      if (!own(reply) || reply.v !== 1 || reply.id !== envelope.id || typeof reply.ok !== 'boolean') throw Object.assign(new Error('Invalid native response'), { code: 'invalid_response' });
      if (!reply.ok) throw Object.assign(new Error(reply.error?.message || 'Request failed'), { code: reply.error?.code || 'request_failed' });
      return reply.result;
    });
  }
  function joinValue(value) {
    if (!own(value)) return value;
    const out = {};
    for (const key of ['state', 'message', 'accepted', 'cancelled']) if (key in value) out[key] = value[key];
    if ('server_id' in value) out.serverId = value.server_id;
    if ('request_id' in value) out.requestId = value.request_id;
    return out;
  }
  transport.subscribe(message => {
    if (!own(message) || message.v !== 1 || message.event !== 'joinStateChanged') return;
    for (const listener of [...listeners]) { try { listener(joinValue(message.data)); } catch (_) {} }
  });
  return Object.freeze({
    getPlayer: () => invoke('getPlayer', {}).then(player => ({ accountId: player.account_id, nickname: player.nickname })),
    authorize: options => {
      if (!own(options) || typeof options.nonce !== 'string' || options.nonce.length < 32 || options.nonce.length > 128) return Promise.reject(Object.assign(new Error('A website nonce is required'), { code: 'invalid_nonce' }));
      return invoke('authorize', { nonce: options.nonce }).then(value => ({ code: value.code, expires_at: value.expires_at, game_app_id: value.game_app_id }));
    },
    getServers: () => invoke('getServers', {}).then(result => result.servers.map(server => ({
      serverId: server.server_id, name: server.name, available: server.available, online: server.online,
      currentPlayers: server.current_players, maxPlayers: server.max_players, closedMessage: server.closed_message,
    }))),
    joinServer: options => {
      const value = typeof options === 'string' ? { serverId: options } : options;
      if (!own(value) || typeof value.serverId !== 'string') return Promise.reject(Object.assign(new Error('A server ID is required'), { code: 'invalid_server' }));
      if (value.requestId !== undefined && typeof value.requestId !== 'string') return Promise.reject(Object.assign(new Error('Invalid request ID'), { code: 'invalid_request_id' }));
      const params = { server_id: value.serverId, request_id: value.requestId || identifier() };
      return invoke('joinServer', params).then(joinValue);
    },
    cancelJoin: options => {
      const params = own(options) && typeof options.requestId === 'string' ? { request_id: options.requestId } : {};
      return invoke('cancelJoin', params).then(joinValue);
    },
    onJoinStateChanged: listener => {
      if (typeof listener !== 'function') throw new TypeError('A listener function is required');
      listeners.add(listener); return () => listeners.delete(listener);
    },
  });
}
