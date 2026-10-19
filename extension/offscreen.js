// Fetches with the extension's host permissions and hands back a blob: URL chrome.downloads can save.
chrome.runtime.onMessage.addListener((msg, _sender, reply) => {
  if (msg.type === 'offscreen-fetch') {
    fetch(msg.url, { credentials: 'include' })
      .then(async (r) => {
        if (!r.ok) throw new Error(`HTTP ${r.status}`);
        reply({ url: URL.createObjectURL(await r.blob()) });
      })
      .catch((e) => reply({ error: e.message }));
    return true;
  }
  if (msg.type === 'offscreen-revoke') URL.revokeObjectURL(msg.url);
});
