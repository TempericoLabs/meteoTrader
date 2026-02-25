import { mkdir, readFile, rename, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);
const DEFAULT_STATE_PATH = resolve(__dirname, '..', 'data', 'copytrade_webpush_state.json');
const ENV_STATE_PATH = String(process.env.POLYMETEO_WEBPUSH_STATE_FILE || '').trim();
const RESOLVED_DEFAULT_STATE_PATH = ENV_STATE_PATH
  ? (ENV_STATE_PATH.startsWith('/') ? ENV_STATE_PATH : resolve(process.cwd(), ENV_STATE_PATH))
  : DEFAULT_STATE_PATH;
const AUDIT_LIMIT = 600;
const PERSIST_DEBOUNCE_MS = 250;

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function safeJsonParse(text) {
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

function normalizeConfig(input = {}) {
  return {
    alertBuys: input.alertBuys !== false,
    alertSells: input.alertSells !== false,
    alertOthers: !!input.alertOthers,
    onlyWeather: input.onlyWeather !== false,
    onlyTrackedCities: !!input.onlyTrackedCities,
    buyMinUsdc: Number.isFinite(Number(input.buyMinUsdc)) ? Math.max(0, Number(input.buyMinUsdc)) : 0,
    sellMinUsdc: Number.isFinite(Number(input.sellMinUsdc)) ? Math.max(0, Number(input.sellMinUsdc)) : 0,
    pollSec: Number.isFinite(Number(input.pollSec)) ? Math.max(2, Math.min(15, Number(input.pollSec))) : 3
  };
}

function subscriptionIdFromEndpoint(endpoint) {
  const raw = String(endpoint || '');
  const short = raw.slice(-40).replace(/[^a-zA-Z0-9]/g, '').slice(-24);
  return `push_${short || 'sub'}`;
}

function buildPushPayload({ username, alert }) {
  const side = String(alert?.side || alert?.type || 'ACT').toUpperCase();
  const amt = Number.isFinite(Number(alert?.usdcSize)) ? Number(alert.usdcSize) : null;
  const amtText = amt == null
    ? ''
    : ` ${new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 2 }).format(amt)}`;
  const city = alert?.trackedCityId ? ` · ${alert.trackedCityId}` : '';
  return {
    type: 'copytrade-alert',
    title: `polyMeteo CopyTrade · ${side}${amtText}`,
    body: `${String(alert?.title || 'Actividad detectada').slice(0, 180)}${city}`,
    tag: String(alert?.id || `${Date.now()}`),
    data: {
      url: '/app',
      username,
      alert
    }
  };
}

function nowIso() {
  return new Date().toISOString();
}

function summarizeSubscriptionRecord(record) {
  return {
    id: record.id,
    username: record.username,
    endpoint: record.endpoint,
    config: record.config,
    createdAtUtc: record.createdAtUtc,
    updatedAtUtc: record.updatedAtUtc,
    lastPollAtUtc: record.lastPollAtUtc || null,
    lastAlertAtUtc: record.lastAlertAtUtc || null,
    lastPushAtUtc: record.lastPushAtUtc || null,
    pushesSent: record.pushesSent || 0,
    lastError: record.lastError || null,
    monitorActive: !!record.monitorActive
  };
}

function normalizePersistedRecord(record) {
  const endpoint = String(record?.endpoint || '').trim();
  const username = String(record?.username || '').trim().replace(/^@/, '');
  const p256dh = String(record?.subscription?.keys?.p256dh || '').trim();
  const auth = String(record?.subscription?.keys?.auth || '').trim();
  if (!endpoint || !username || !p256dh || !auth) return null;
  return {
    id: String(record?.id || subscriptionIdFromEndpoint(endpoint)),
    endpoint,
    username,
    subscription: {
      endpoint,
      expirationTime: record?.subscription?.expirationTime ?? null,
      keys: { p256dh, auth }
    },
    config: normalizeConfig(record?.config || {}),
    createdAtUtc: String(record?.createdAtUtc || nowIso()),
    updatedAtUtc: String(record?.updatedAtUtc || nowIso()),
    lastPollAtUtc: record?.lastPollAtUtc || null,
    lastAlertAtUtc: record?.lastAlertAtUtc || null,
    lastPushAtUtc: record?.lastPushAtUtc || null,
    pushesSent: Number.isFinite(Number(record?.pushesSent)) ? Number(record.pushesSent) : 0,
    lastError: record?.lastError ? String(record.lastError) : null
  };
}

export class CopyTradeWebPushManager {
  constructor({ service, stateFilePath = RESOLVED_DEFAULT_STATE_PATH } = {}) {
    this.service = service;
    this.stateFilePath = stateFilePath;
    this.subscriptions = new Map(); // endpoint -> record
    this.monitors = new Map(); // endpoint -> { abortController, startedAtUtc }
    this.auditLog = [];
    this.transportInitPromise = null;
    this.readyPromise = null;
    this.persistTimer = null;
    this.persistRunning = false;
    this.persistQueued = false;
    this.tmpStateFilePath = `${this.stateFilePath}.tmp`;
    this.transport = {
      available: false,
      reason: 'No inicializado',
      webpush: null,
      publicKey: null,
      subject: null
    };
    this.readyPromise = this._bootstrap();
  }

  async _bootstrap() {
    await this._loadPersistedState();
    this._restoreAllMonitors();
  }

  async _ensureReady() {
    await this.readyPromise;
  }

  async getConfig() {
    await this._ensureReady();
    await this._ensureTransport();
    return {
      ok: true,
      webPushServerAvailable: !!this.transport.available,
      reason: this.transport.reason || null,
      vapidPublicKey: this.transport.publicKey || null,
      subject: this.transport.subject || null,
      subscriptionsCount: this.subscriptions.size,
      monitorsCount: this.monitors.size,
      auditCount: this.auditLog.length,
      stateFilePath: this.stateFilePath
    };
  }

  async listSubscriptions() {
    await this._ensureReady();
    return {
      ok: true,
      subscriptions: Array.from(this.subscriptions.values())
        .map((record) => summarizeSubscriptionRecord({
          ...record,
          monitorActive: this.monitors.has(record.endpoint)
        }))
        .sort((a, b) => String(b.updatedAtUtc || '').localeCompare(String(a.updatedAtUtc || '')))
    };
  }

  async getAuditLog({ limit = 100 } = {}) {
    await this._ensureReady();
    const n = Number.isFinite(Number(limit)) ? Math.max(1, Math.min(500, Number(limit))) : 100;
    return {
      ok: true,
      events: this.auditLog.slice(0, n)
    };
  }

  async subscribe({ username, subscription, copyTradeConfig }) {
    await this._ensureReady();
    const endpoint = String(subscription?.endpoint || '').trim();
    const p256dh = String(subscription?.keys?.p256dh || '').trim();
    const auth = String(subscription?.keys?.auth || '').trim();
    const normalizedUser = String(username || '').trim().replace(/^@/, '');
    if (!normalizedUser) throw new Error('username requerido');
    if (!endpoint || !p256dh || !auth) throw new Error('subscription invalida');

    await this._ensureTransport();

    const now = nowIso();
    const config = normalizeConfig(copyTradeConfig || {});
    const key = endpoint;
    const prev = this.subscriptions.get(key);
    const record = {
      id: subscriptionIdFromEndpoint(endpoint),
      endpoint,
      username: normalizedUser,
      subscription: {
        endpoint,
        expirationTime: subscription?.expirationTime || null,
        keys: { p256dh, auth }
      },
      config,
      createdAtUtc: prev?.createdAtUtc || now,
      updatedAtUtc: now,
      lastPollAtUtc: prev?.lastPollAtUtc || null,
      lastAlertAtUtc: prev?.lastAlertAtUtc || null,
      lastPushAtUtc: prev?.lastPushAtUtc || null,
      pushesSent: prev?.pushesSent || 0,
      lastError: prev?.lastError || null
    };
    this.subscriptions.set(key, record);
    this._appendAudit('subscribe', {
      subscriptionId: record.id,
      username: record.username,
      endpointTail: record.endpoint.slice(-24),
      config: record.config
    });
    this._schedulePersist();
    this._startMonitorForEndpoint(key, { reason: 'subscribe' });

    return {
      ok: true,
      subscriptionId: record.id,
      username: record.username,
      webPushServerAvailable: !!this.transport.available,
      reason: this.transport.reason || null,
      monitorActive: this.monitors.has(key),
      config: record.config
    };
  }

  async unsubscribe({ subscription }) {
    await this._ensureReady();
    const endpoint = String(subscription?.endpoint || '').trim();
    if (!endpoint) throw new Error('subscription.endpoint requerido');
    const existing = this.subscriptions.get(endpoint);
    const had = !!existing;
    this.subscriptions.delete(endpoint);
    this._stopMonitorForEndpoint(endpoint, 'unsubscribe');
    this._appendAudit('unsubscribe', {
      removed: had,
      subscriptionId: existing?.id || subscriptionIdFromEndpoint(endpoint),
      username: existing?.username || null,
      endpointTail: endpoint.slice(-24)
    });
    this._schedulePersist();
    return { ok: true, removed: had };
  }

  async sendTest({ subscription, username = 'test-user' }) {
    await this._ensureReady();
    const endpoint = String(subscription?.endpoint || '').trim();
    if (!endpoint) throw new Error('subscription.endpoint requerido');
    await this._ensureTransport();
    if (!this.transport.available) {
      throw new Error(this.transport.reason || 'Web Push no disponible');
    }
    const payload = {
      type: 'copytrade-alert',
      title: 'polyMeteo Web Push · TEST',
      body: `Prueba de notificación para @${String(username).replace(/^@/, '')}`,
      tag: `test-${Date.now()}`,
      data: { url: '/app', username }
    };
    await this._sendPush(subscription, payload);
    this._appendAudit('test_push_sent', {
      username: String(username).replace(/^@/, ''),
      endpointTail: endpoint.slice(-24)
    });
    this._schedulePersist();
    return { ok: true };
  }

  async shutdown() {
    for (const endpoint of Array.from(this.monitors.keys())) {
      this._stopMonitorForEndpoint(endpoint, 'shutdown');
    }
    if (this.persistTimer) {
      clearTimeout(this.persistTimer);
      this.persistTimer = null;
    }
    await this._flushPersistState();
  }

  _restoreAllMonitors() {
    let restored = 0;
    for (const endpoint of this.subscriptions.keys()) {
      this._startMonitorForEndpoint(endpoint, { reason: 'startup-restore', silentAudit: true });
      restored += 1;
    }
    this._appendAudit('startup_restore', {
      restoredSubscriptions: restored
    }, { persist: restored > 0 });
    if (restored > 0) this._schedulePersist();
  }

  _stopMonitorForEndpoint(endpoint, reason = 'manual') {
    const monitor = this.monitors.get(endpoint);
    if (!monitor) return;
    try { monitor.abortController.abort(); } catch {}
    this.monitors.delete(endpoint);
    const record = this.subscriptions.get(endpoint);
    this._appendAudit('monitor_stopped', {
      subscriptionId: record?.id || subscriptionIdFromEndpoint(endpoint),
      username: record?.username || null,
      reason
    }, { persist: false });
  }

  _startMonitorForEndpoint(endpoint, { reason = 'manual', silentAudit = false } = {}) {
    const record = this.subscriptions.get(endpoint);
    if (!record) return;
    this._stopMonitorForEndpoint(endpoint, 'restart');

    const abortController = new AbortController();
    const startedAtUtc = nowIso();
    this.monitors.set(endpoint, { abortController, startedAtUtc });
    if (!silentAudit) {
      this._appendAudit('monitor_started', {
        subscriptionId: record.id,
        username: record.username,
        reason,
        pollSec: record.config.pollSec
      });
      this._schedulePersist();
    }

    const run = async () => {
      while (!abortController.signal.aborted && this.subscriptions.has(endpoint)) {
        const current = this.subscriptions.get(endpoint);
        if (!current) break;
        try {
          await this.service.streamCopyTrade({
            username: current.username,
            pollMs: Math.round((current.config.pollSec || 3) * 1000),
            limit: 40,
            config: current.config,
            signal: abortController.signal,
            onEvent: (event, payload) => {
              if (event === 'alert') {
                void this._handleAlert(endpoint, payload);
              }
              if (event === 'poll') {
                const rec = this.subscriptions.get(endpoint);
                if (rec) {
                  rec.lastPollAtUtc = payload?.atUtc || nowIso();
                  if (payload?.ok === false) rec.lastError = payload?.error || 'poll error';
                }
              }
            }
          });
          break;
        } catch (error) {
          if (abortController.signal.aborted) break;
          const rec = this.subscriptions.get(endpoint);
          const message = String(error?.message || error);
          if (rec) rec.lastError = message;
          this._appendAudit('monitor_error', {
            subscriptionId: rec?.id || subscriptionIdFromEndpoint(endpoint),
            username: rec?.username || null,
            error: message
          });
          this._schedulePersist();
          await sleep(2500);
        }
      }
      this.monitors.delete(endpoint);
    };

    void run();
  }

  async _handleAlert(endpoint, alert) {
    const record = this.subscriptions.get(endpoint);
    if (!record) return;
    record.lastAlertAtUtc = alert?.atUtc || nowIso();
    const payload = buildPushPayload({ username: record.username, alert });
    try {
      await this._sendPush(record.subscription, payload);
      record.pushesSent = (record.pushesSent || 0) + 1;
      record.lastPushAtUtc = nowIso();
      record.lastError = null;
      this._appendAudit('push_sent', {
        subscriptionId: record.id,
        username: record.username,
        alertId: alert?.id || null,
        side: alert?.side || alert?.type || null,
        trackedCityId: alert?.trackedCityId || null,
        usdcSize: Number.isFinite(Number(alert?.usdcSize)) ? Number(alert.usdcSize) : null
      });
      this._schedulePersist();
    } catch (error) {
      const message = String(error?.message || error);
      record.lastError = message;
      this._appendAudit('push_error', {
        subscriptionId: record.id,
        username: record.username,
        alertId: alert?.id || null,
        error: message
      });
      this._schedulePersist();
      const statusCode = Number(error?.statusCode || error?.status || 0);
      if (statusCode === 404 || statusCode === 410) {
        this.subscriptions.delete(endpoint);
        this._stopMonitorForEndpoint(endpoint, `expired-${statusCode}`);
        this._appendAudit('subscription_removed_expired', {
          subscriptionId: record.id,
          username: record.username,
          statusCode
        });
        this._schedulePersist();
      }
    }
  }

  _appendAudit(type, data = {}, { persist = false } = {}) {
    this.auditLog.unshift({
      atUtc: nowIso(),
      type,
      data
    });
    if (this.auditLog.length > AUDIT_LIMIT) {
      this.auditLog.length = AUDIT_LIMIT;
    }
    if (persist) this._schedulePersist();
  }

  _serializeState() {
    const subscriptions = Array.from(this.subscriptions.values()).map((r) => ({
      id: r.id,
      endpoint: r.endpoint,
      username: r.username,
      subscription: r.subscription,
      config: r.config,
      createdAtUtc: r.createdAtUtc,
      updatedAtUtc: r.updatedAtUtc,
      lastPollAtUtc: r.lastPollAtUtc || null,
      lastAlertAtUtc: r.lastAlertAtUtc || null,
      lastPushAtUtc: r.lastPushAtUtc || null,
      pushesSent: r.pushesSent || 0,
      lastError: r.lastError || null
    }));
    return {
      version: 1,
      savedAtUtc: nowIso(),
      subscriptions,
      auditLog: this.auditLog.slice(0, AUDIT_LIMIT)
    };
  }

  _schedulePersist() {
    if (this.persistTimer) return;
    this.persistTimer = setTimeout(() => {
      this.persistTimer = null;
      void this._persistState();
    }, PERSIST_DEBOUNCE_MS);
  }

  async _persistState() {
    if (this.persistRunning) {
      this.persistQueued = true;
      return;
    }
    this.persistRunning = true;
    try {
      await this._writeStateAtomically();
    } catch (error) {
      this._appendAudit('persist_error', { error: String(error?.message || error) }, { persist: false });
    } finally {
      this.persistRunning = false;
      if (this.persistQueued) {
        this.persistQueued = false;
        this._schedulePersist();
      }
    }
  }

  async _flushPersistState() {
    // If a debounced write is pending, cancel timer and force one final atomic write.
    if (this.persistTimer) {
      clearTimeout(this.persistTimer);
      this.persistTimer = null;
    }
    // Wait any in-flight write to finish to avoid racing writes on shutdown.
    while (this.persistRunning) {
      await sleep(25);
    }
    this.persistQueued = false;
    await this._writeStateAtomically();
  }

  async _writeStateAtomically() {
    const payload = JSON.stringify(this._serializeState(), null, 2);
    await mkdir(dirname(this.stateFilePath), { recursive: true });
    await writeFile(this.tmpStateFilePath, payload, 'utf8');
    await rename(this.tmpStateFilePath, this.stateFilePath);
  }

  async _loadPersistedState() {
    try {
      const raw = await readFile(this.stateFilePath, 'utf8');
      const parsed = safeJsonParse(raw);
      if (!parsed || typeof parsed !== 'object') {
        this._appendAudit('state_load_invalid', { reason: 'json-invalido' }, { persist: false });
        return;
      }
      const subs = Array.isArray(parsed.subscriptions) ? parsed.subscriptions : [];
      let restored = 0;
      for (const item of subs) {
        const rec = normalizePersistedRecord(item);
        if (!rec) continue;
        this.subscriptions.set(rec.endpoint, rec);
        restored += 1;
      }
      const auditLog = Array.isArray(parsed.auditLog) ? parsed.auditLog : [];
      this.auditLog = auditLog
        .filter((e) => e && typeof e === 'object' && typeof e.type === 'string')
        .slice(0, AUDIT_LIMIT)
        .map((e) => ({
          atUtc: String(e.atUtc || nowIso()),
          type: String(e.type),
          data: e.data && typeof e.data === 'object' ? e.data : {}
        }));
      this._appendAudit('state_loaded', {
        restoredSubscriptions: restored,
        previousAuditCount: this.auditLog.length
      }, { persist: false });
    } catch (error) {
      const code = String(error?.code || '');
      if (code === 'ENOENT') {
        this._appendAudit('state_missing', { path: this.stateFilePath }, { persist: false });
        return;
      }
      this._appendAudit('state_load_error', { error: String(error?.message || error) }, { persist: false });
    }
  }

  async _sendPush(subscription, payload) {
    await this._ensureTransport();
    if (!this.transport.available || !this.transport.webpush) {
      throw new Error(this.transport.reason || 'Web Push no disponible');
    }
    return this.transport.webpush.sendNotification(subscription, JSON.stringify(payload), {
      TTL: 60
    });
  }

  async _ensureTransport() {
    if (this.transportInitPromise) {
      await this.transportInitPromise;
      return;
    }
    this.transportInitPromise = this._initTransport();
    await this.transportInitPromise;
  }

  async _initTransport() {
    const publicKey = String(process.env.POLYMETEO_WEBPUSH_VAPID_PUBLIC_KEY || '').trim();
    const privateKey = String(process.env.POLYMETEO_WEBPUSH_VAPID_PRIVATE_KEY || '').trim();
    const subject = String(process.env.POLYMETEO_WEBPUSH_VAPID_SUBJECT || 'mailto:digitalclaw8@gmail.com').trim();
    if (!publicKey || !privateKey) {
      this.transport = {
        available: false,
        reason: 'Faltan VAPID keys (POLYMETEO_WEBPUSH_VAPID_PUBLIC_KEY/PRIVATE_KEY)',
        webpush: null,
        publicKey: publicKey || null,
        subject
      };
      return;
    }
    try {
      const mod = await import('web-push');
      const webpush = mod?.default || mod;
      if (!webpush?.setVapidDetails || !webpush?.sendNotification) {
        throw new Error('Modulo web-push invalido');
      }
      webpush.setVapidDetails(subject, publicKey, privateKey);
      this.transport = {
        available: true,
        reason: null,
        webpush,
        publicKey,
        subject
      };
    } catch (error) {
      this.transport = {
        available: false,
        reason: `web-push no disponible: ${String(error?.message || error)}`,
        webpush: null,
        publicKey,
        subject
      };
    }
  }
}

export function validatePushSubscriptionShape(body) {
  const data = typeof body === 'string' ? safeJsonParse(body) : body;
  const subscription = data?.subscription || data;
  const endpoint = String(subscription?.endpoint || '').trim();
  const p256dh = String(subscription?.keys?.p256dh || '').trim();
  const auth = String(subscription?.keys?.auth || '').trim();
  if (!endpoint || !p256dh || !auth) {
    throw new Error('subscription invalida (endpoint / keys.p256dh / keys.auth requeridos)');
  }
  return data;
}
