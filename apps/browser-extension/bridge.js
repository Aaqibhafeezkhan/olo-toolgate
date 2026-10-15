// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// Only bounded status requests cross the page boundary. No credentials or execution commands.
if (location.pathname.startsWith('/console/')) {
  if (globalThis.toolgateConnectBridge) window.removeEventListener('message',globalThis.toolgateConnectBridge);
  globalThis.toolgateConnectBridge = async event => {
    const m = event.data;
    if (event.source !== window || event.origin !== location.origin || m?.channel !== 'toolgate-connect-request'
      || !/^[a-f0-9-]{36}$/.test(m.id) || !['hello', 'connect', 'status', 'cancel','proof'].includes(m.operation)
      || (m.operation==='proof'&&!/^[a-f0-9]{64}$/.test(m.nonce))) return;
    try {
      const result = await chrome.runtime.sendMessage({operation:m.operation,...(m.operation==='proof'?{nonce:m.nonce}:{})});
      window.postMessage({channel:'toolgate-connect-response',id:m.id,...result},location.origin);
    } catch { /* Reloading/upgrading invalidates old content scripts; Retry recovers. */ }
  };
  window.addEventListener('message',globalThis.toolgateConnectBridge);
}
