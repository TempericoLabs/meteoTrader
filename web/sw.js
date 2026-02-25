self.addEventListener('install', (event) => {
  self.skipWaiting();
});

self.addEventListener('activate', (event) => {
  event.waitUntil(self.clients.claim());
});

function normalizePushPayload(raw) {
  const payload = raw && typeof raw === 'object' ? raw : {};
  return {
    title: String(payload.title || 'polyMeteo'),
    body: String(payload.body || 'Nueva alerta'),
    tag: String(payload.tag || `polymeteo-${Date.now()}`),
    data: payload.data && typeof payload.data === 'object' ? payload.data : { url: '/app' }
  };
}

self.addEventListener('push', (event) => {
  let parsed = {};
  try {
    parsed = event.data ? event.data.json() : {};
  } catch {
    parsed = { title: 'polyMeteo', body: event.data ? event.data.text() : 'Nueva alerta' };
  }
  const p = normalizePushPayload(parsed);
  event.waitUntil(
    self.registration.showNotification(p.title, {
      body: p.body,
      tag: p.tag,
      data: p.data,
      renotify: false
    })
  );
});

self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  const targetUrl = event.notification?.data?.url || '/app';
  event.waitUntil((async () => {
    const allClients = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
    for (const client of allClients) {
      if (client.url.includes('/app')) {
        try { await client.focus(); } catch {}
        return;
      }
    }
    await self.clients.openWindow(targetUrl);
  })());
});

