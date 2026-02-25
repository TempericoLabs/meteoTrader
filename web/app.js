const API_BASE = (() => {
  const path = window.location.pathname;
  if (path.startsWith('/app')) return '';
  return 'http://localhost:8788';
})();

const POLYMARKET_CITY_SLUG = {
  'new-york': 'nyc',
  'sao-paulo': 'sao-paulo',
  'buenos-aires': 'buenos-aires'
};

const state = {
  mode: 'expert',
  strategy: 'agresiva',
  loadingCities: false,
  loadingDetail: false,
  apiHealthy: true,
  apiError: null,
  backendHealth: null,
  backendHealthFetchedAtUtc: null,
  meta: null,
  cities: [],
  citiesLoadExpected: 0,
  citiesLoadCompleted: 0,
  citiesLoadMode: 'idle',
  selectedCityId: null,
  selectedHorizonKey: 'today',
  cityDetails: new Map(),
  paper: { positions: [], closed: [] },
  account: {
    wallet: '',
    loading: false,
    snapshot: null,
    error: null,
    walletSource: null
  },
  intelQuery: 'ikik111',
  intelLoading: false,
  intelSnapshot: null,
  intelError: null,
  copyTrade: {
    enabled: false,
    connecting: false,
    connected: false,
    statusText: 'Desactivado',
    lastPollAtUtc: null,
    lastBaselineAtUtc: null,
    lastAlertAtUtc: null,
    lastAlertId: null,
    lastError: null,
    alerts: [],
    stats: { polls: 0, alerts: 0, baselined: 0 },
    config: {
      pollSec: 3,
      buyMinUsdc: 0,
      sellMinUsdc: 0,
      alertBuys: true,
      alertSells: true,
      alertOthers: false,
      onlyWeather: true,
      onlyTrackedCities: false,
      browserNotifications: false,
      webPushEnabled: false
    },
    notifyPermission: 'unknown',
    notifySupported: false,
    webPush: {
      swSupported: false,
      pushSupported: false,
      swRegistered: false,
      serverAvailable: false,
      vapidPublicKey: null,
      permission: 'unknown',
      subscribed: false,
      syncing: false,
      statusText: 'Web Push: no inicializado',
      lastSyncAtUtc: null,
      lastError: null
    },
    admin: {
      loading: false,
      error: null,
      lastFetchAtUtc: null,
      subscriptions: [],
      audit: []
    }
  }
};

const el = {
  metaLine: document.getElementById('metaLine'),
  apiStatus: document.getElementById('apiStatus'),
  tickerTrack: document.getElementById('tickerTrack'),
  opsDashboardBody: document.getElementById('opsDashboardBody'),
  gridStats: document.getElementById('gridStats'),
  citiesGrid: document.getElementById('citiesGrid'),
  detailTitle: document.getElementById('detailTitle'),
  detailBody: document.getElementById('detailBody'),
  refreshBtn: document.getElementById('refreshBtn'),
  refreshDetailBtn: document.getElementById('refreshDetailBtn'),
  modeSwitch: document.getElementById('modeSwitch'),
  strategySwitch: document.getElementById('strategySwitch'),
  paperBody: document.getElementById('paperBody'),
  paperClearBtn: document.getElementById('paperClearBtn'),
  accountWalletInput: document.getElementById('accountWalletInput'),
  accountAnalyzeBtn: document.getElementById('accountAnalyzeBtn'),
  accountRefreshBtn: document.getElementById('accountRefreshBtn'),
  accountBody: document.getElementById('accountBody'),
  intelUsernameInput: document.getElementById('intelUsernameInput'),
  intelAnalyzeBtn: document.getElementById('intelAnalyzeBtn'),
  intelRefreshBtn: document.getElementById('intelRefreshBtn'),
  intelBody: document.getElementById('intelBody'),
  copytradeEnabled: document.getElementById('copytradeEnabled'),
  copytradePollSec: document.getElementById('copytradePollSec'),
  copytradeBuyMin: document.getElementById('copytradeBuyMin'),
  copytradeSellMin: document.getElementById('copytradeSellMin'),
  copytradeAlertBuys: document.getElementById('copytradeAlertBuys'),
  copytradeAlertSells: document.getElementById('copytradeAlertSells'),
  copytradeAlertOthers: document.getElementById('copytradeAlertOthers'),
  copytradeOnlyWeather: document.getElementById('copytradeOnlyWeather'),
  copytradeOnlyTracked: document.getElementById('copytradeOnlyTracked'),
  copytradeApplyBtn: document.getElementById('copytradeApplyBtn'),
  copytradeClearAlertsBtn: document.getElementById('copytradeClearAlertsBtn'),
  copytradeBrowserNotify: document.getElementById('copytradeBrowserNotify'),
  copytradeNotifyPermBtn: document.getElementById('copytradeNotifyPermBtn'),
  copytradeNotifyStatus: document.getElementById('copytradeNotifyStatus'),
  copytradeWebPushEnabled: document.getElementById('copytradeWebPushEnabled'),
  copytradeWebPushSyncBtn: document.getElementById('copytradeWebPushSyncBtn'),
  copytradeWebPushTestBtn: document.getElementById('copytradeWebPushTestBtn'),
  copytradeWebPushOffBtn: document.getElementById('copytradeWebPushOffBtn'),
  copytradeWebPushStatus: document.getElementById('copytradeWebPushStatus'),
  copytradeWebPushAdminRefreshBtn: document.getElementById('copytradeWebPushAdminRefreshBtn'),
  copytradeWebPushAdminBody: document.getElementById('copytradeWebPushAdminBody'),
  copytradeStatusLine: document.getElementById('copytradeStatusLine'),
  copytradeStatsLine: document.getElementById('copytradeStatsLine'),
  copytradeAlerts: document.getElementById('copytradeAlerts')
};

const PAPER_STORAGE_KEY = 'polymeteo_web_paper_v1';
const ACCOUNT_STORAGE_KEY = 'polymeteo_web_account_v1';
const COPYTRADE_STORAGE_KEY = 'polymeteo_web_copytrade_v1';
const COPYTRADE_ALERT_LIMIT = 80;

let copyTradeEventSource = null;
let copyTradeReconnectTimer = null;
const copyTradeBrowserNotifiedIds = new Set();
let copyTradeServiceWorkerReg = null;

function setSegmentedActive(container, attrName, value) {
  Array.from(container.querySelectorAll('button')).forEach((btn) => {
    btn.classList.toggle('active', btn.dataset[attrName] === value);
  });
}

function escapeHtml(value) {
  return String(value)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

async function apiGet(path) {
  const response = await fetch(`${API_BASE}${path}`, {
    headers: { 'Accept': 'application/json' },
    cache: 'no-store'
  });
  if (!response.ok) {
    const text = await response.text();
    throw new Error(`${response.status} ${response.statusText}: ${text.slice(0, 300)}`);
  }
  return response.json();
}

async function apiPost(path, payload) {
  const response = await fetch(`${API_BASE}${path}`, {
    method: 'POST',
    headers: {
      'Accept': 'application/json',
      'Content-Type': 'application/json'
    },
    cache: 'no-store',
    body: JSON.stringify(payload)
  });
  if (!response.ok) {
    const text = await response.text();
    throw new Error(`${response.status} ${response.statusText}: ${text.slice(0, 400)}`);
  }
  return response.json();
}

function urlBase64ToUint8Array(base64String) {
  const padding = '='.repeat((4 - (base64String.length % 4)) % 4);
  const base64 = (base64String + padding).replace(/-/g, '+').replace(/_/g, '/');
  const rawData = atob(base64);
  const outputArray = new Uint8Array(rawData.length);
  for (let i = 0; i < rawData.length; ++i) {
    outputArray[i] = rawData.charCodeAt(i);
  }
  return outputArray;
}

function refreshWebPushCapabilityState() {
  const wp = state.copyTrade.webPush;
  wp.swSupported = typeof navigator !== 'undefined' && 'serviceWorker' in navigator;
  wp.pushSupported = wp.swSupported && typeof window !== 'undefined' && 'PushManager' in window;
  wp.permission = (typeof window !== 'undefined' && 'Notification' in window) ? (Notification.permission || 'default') : 'unsupported';
}

async function getCopyTradeServiceWorkerRegistration() {
  refreshWebPushCapabilityState();
  const wp = state.copyTrade.webPush;
  if (!wp.swSupported) throw new Error('Service Worker no soportado en este navegador');
  if (copyTradeServiceWorkerReg) return copyTradeServiceWorkerReg;
  const reg = await navigator.serviceWorker.register('/app/sw.js', { scope: '/app/' });
  copyTradeServiceWorkerReg = reg;
  wp.swRegistered = true;
  return reg;
}

async function getServerWebPushConfig() {
  const wp = state.copyTrade.webPush;
  const cfg = await apiGet('/api/v1/webpush/config');
  wp.serverAvailable = !!cfg?.webPushServerAvailable;
  wp.vapidPublicKey = cfg?.vapidPublicKey || null;
  wp.serverReason = cfg?.reason || null;
  if (!wp.serverAvailable && !wp.syncing) {
    wp.statusText = 'Web Push backend no disponible';
  }
  return cfg;
}

async function refreshWebPushAdmin({ silent = false } = {}) {
  const admin = state.copyTrade.admin;
  if (!silent) {
    admin.loading = true;
    admin.error = null;
    render();
  }
  try {
    const [cfg, subs, audit] = await Promise.all([
      apiGet('/api/v1/webpush/config'),
      apiGet('/api/v1/webpush/subscriptions'),
      apiGet('/api/v1/webpush/audit?limit=20')
    ]);
    state.copyTrade.webPush.serverAvailable = !!cfg?.webPushServerAvailable;
    state.copyTrade.webPush.vapidPublicKey = cfg?.vapidPublicKey || null;
    state.copyTrade.webPush.serverReason = cfg?.reason || null;
    admin.subscriptions = Array.isArray(subs?.subscriptions) ? subs.subscriptions : [];
    admin.audit = Array.isArray(audit?.events) ? audit.events : [];
    admin.lastFetchAtUtc = nowIso();
    admin.error = null;
  } catch (error) {
    admin.error = String(error?.message || error);
  } finally {
    admin.loading = false;
    render();
  }
}

function currentCopyTradeConfigPayload() {
  const c = state.copyTrade.config;
  return {
    pollSec: c.pollSec,
    buyMinUsdc: c.buyMinUsdc,
    sellMinUsdc: c.sellMinUsdc,
    alertBuys: c.alertBuys,
    alertSells: c.alertSells,
    alertOthers: c.alertOthers,
    onlyWeather: c.onlyWeather,
    onlyTrackedCities: c.onlyTrackedCities
  };
}

async function syncWebPushSubscription() {
  readCopyTradeControlsToState();
  refreshWebPushCapabilityState();
  const ct = state.copyTrade;
  const wp = ct.webPush;
  wp.syncing = true;
  wp.lastError = null;
  wp.statusText = 'Web Push: sincronizando…';
  renderCopyTradePanel();
  try {
    const username = currentCopyTradeUsername();
    if (!username) throw new Error('Analiza primero un usuario Polymarket');
    if (wp.permission !== 'granted') {
      await requestBrowserNotificationPermission();
      refreshWebPushCapabilityState();
      if (state.copyTrade.webPush.permission !== 'granted') {
        throw new Error('Permiso de notificaciones no concedido');
      }
    }
    const serverCfg = await getServerWebPushConfig();
    if (!serverCfg?.webPushServerAvailable) {
      throw new Error(serverCfg?.reason || 'Backend Web Push no disponible');
    }
    if (!serverCfg?.vapidPublicKey) {
      throw new Error('Falta VAPID public key en backend');
    }
    const reg = await getCopyTradeServiceWorkerRegistration();
    let sub = await reg.pushManager.getSubscription();
    if (!sub) {
      sub = await reg.pushManager.subscribe({
        userVisibleOnly: true,
        applicationServerKey: urlBase64ToUint8Array(serverCfg.vapidPublicKey)
      });
    }
    const subJson = sub.toJSON ? sub.toJSON() : sub;
    await apiPost('/api/v1/webpush/subscribe', {
      username,
      subscription: subJson,
      copyTradeConfig: currentCopyTradeConfigPayload()
    });
    ct.config.webPushEnabled = true;
    saveCopyTradeStore();
    wp.subscribed = true;
    wp.lastSyncAtUtc = nowIso();
    wp.statusText = `Web Push OK @${username}`;
    wp.lastError = null;
  } catch (error) {
    wp.lastError = String(error?.message || error);
    wp.statusText = 'Web Push: error';
  } finally {
    wp.syncing = false;
    syncCopyTradeControlsFromState();
    refreshWebPushAdmin({ silent: true }).catch(() => {});
    render();
  }
}

async function disableWebPushSubscription() {
  refreshWebPushCapabilityState();
  const ct = state.copyTrade;
  const wp = ct.webPush;
  wp.syncing = true;
  wp.lastError = null;
  wp.statusText = 'Web Push: desactivando…';
  renderCopyTradePanel();
  try {
    let subJson = null;
    if (wp.swSupported) {
      const reg = await getCopyTradeServiceWorkerRegistration().catch(() => null);
      const sub = reg ? await reg.pushManager.getSubscription() : null;
      if (sub) {
        subJson = sub.toJSON ? sub.toJSON() : sub;
        await apiPost('/api/v1/webpush/unsubscribe', { subscription: subJson }).catch(() => null);
        await sub.unsubscribe().catch(() => null);
      }
    }
    ct.config.webPushEnabled = false;
    saveCopyTradeStore();
    wp.subscribed = false;
    wp.lastSyncAtUtc = nowIso();
    wp.statusText = 'Web Push desactivado';
    wp.lastError = null;
  } catch (error) {
    wp.lastError = String(error?.message || error);
    wp.statusText = 'Web Push: error al desactivar';
  } finally {
    wp.syncing = false;
    syncCopyTradeControlsFromState();
    refreshWebPushAdmin({ silent: true }).catch(() => {});
    render();
  }
}

async function sendWebPushTest() {
  refreshWebPushCapabilityState();
  const wp = state.copyTrade.webPush;
  wp.syncing = true;
  wp.lastError = null;
  wp.statusText = 'Web Push: enviando test…';
  renderCopyTradePanel();
  try {
    const reg = await getCopyTradeServiceWorkerRegistration();
    const sub = await reg.pushManager.getSubscription();
    if (!sub) throw new Error('No hay suscripcion push activa. Usa “Activar / sync push”.');
    const username = currentCopyTradeUsername() || 'test-user';
    await apiPost('/api/v1/webpush/test', {
      username,
      subscription: sub.toJSON ? sub.toJSON() : sub
    });
    wp.statusText = 'Web Push test enviado';
    wp.lastSyncAtUtc = nowIso();
  } catch (error) {
    wp.lastError = String(error?.message || error);
    wp.statusText = 'Web Push test error';
  } finally {
    wp.syncing = false;
    refreshWebPushAdmin({ silent: true }).catch(() => {});
    render();
  }
}

function horizonLabel(key) {
  return key === 'today' ? 'Hoy' : key === 'tomorrow' ? 'Mañana' : 'Pasado';
}

function isLoadedCity(city) {
  return city && !city._placeholder;
}

function signalClass(signal) {
  if (signal === 'GREEN') return 'green';
  if (signal === 'YELLOW') return 'yellow';
  return 'red';
}

function fmtPct(value) {
  if (value == null || Number.isNaN(value)) return '--';
  return `${Number(value).toFixed(1)}%`;
}

function fmtNum(value) {
  if (value == null || Number.isNaN(value)) return '--';
  return new Intl.NumberFormat('es-ES', { maximumFractionDigits: 0 }).format(value);
}

function fmtUsd(value) {
  if (value == null || Number.isNaN(value)) return '--';
  return new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 2 }).format(value);
}

function nowIso() {
  return new Date().toISOString();
}

function loadPaperStore() {
  try {
    const raw = localStorage.getItem(PAPER_STORAGE_KEY);
    if (!raw) return { positions: [], closed: [] };
    const parsed = JSON.parse(raw);
    return {
      positions: Array.isArray(parsed?.positions) ? parsed.positions : [],
      closed: Array.isArray(parsed?.closed) ? parsed.closed : []
    };
  } catch {
    return { positions: [], closed: [] };
  }
}

function savePaperStore() {
  try {
    localStorage.setItem(PAPER_STORAGE_KEY, JSON.stringify(state.paper));
  } catch {
    // ignore quota/private mode errors
  }
}

function loadAccountStore() {
  try {
    const raw = localStorage.getItem(ACCOUNT_STORAGE_KEY);
    if (!raw) return { wallet: '' };
    const parsed = JSON.parse(raw);
    return {
      wallet: String(parsed?.wallet || '').trim()
    };
  } catch {
    return { wallet: '' };
  }
}

function saveAccountStore() {
  try {
    localStorage.setItem(ACCOUNT_STORAGE_KEY, JSON.stringify({ wallet: state.account.wallet || '' }));
  } catch {
    // ignore quota/private mode errors
  }
}

function currentOpportunityIndex() {
  const map = new Map();
  state.cityDetails.forEach((cityDetail) => {
    (cityDetail.horizons || []).forEach((h) => {
      (h.opportunities || []).forEach((op) => {
        map.set(`${cityDetail.city.id}::${h.key}::${op.id}::${op.actionSide}`, {
          cityId: cityDetail.city.id,
          cityName: cityDetail.city.name,
          horizonKey: h.key,
          horizonLabel: h.label,
          targetDate: h.targetDate,
          opportunity: op
        });
      });
    });
  });
  return map;
}

function markPriceCentsForPosition(position, idx) {
  const key = `${position.cityId}::${position.horizonKey}::${position.opportunityId}::${position.side}`;
  const live = idx.get(key);
  if (!live?.opportunity) return position.entryPriceCents;
  const op = live.opportunity;
  return position.side === 'YES' ? Number(op.yesAskCents || position.entryPriceCents) : Number(op.noAskCents || position.entryPriceCents);
}

function enrichPaperPositions() {
  const idx = currentOpportunityIndex();
  const open = state.paper.positions.map((p) => {
    const markPriceCents = markPriceCentsForPosition(p, idx);
    const markValueUsd = (p.shares * markPriceCents) / 100;
    const pnlUsd = markValueUsd - p.amountUsd;
    return { ...p, markPriceCents, markValueUsd, pnlUsd };
  });
  const closed = state.paper.closed.map((p) => ({ ...p }));
  const openPnlUsd = open.reduce((a, b) => a + (b.pnlUsd || 0), 0);
  const closedPnlUsd = closed.reduce((a, b) => a + (b.realizedPnlUsd || 0), 0);
  return { open, closed, openPnlUsd, closedPnlUsd };
}

function paperBuyFromOpportunity({ city, horizon, op }) {
  const raw = window.prompt(`Importe USDC para simular BUY ${op.actionSide} en ${city.city.name} (${op.bucketLabel})`, '25');
  if (raw == null) return;
  const amountUsd = Number(String(raw).replace(',', '.'));
  if (!Number.isFinite(amountUsd) || amountUsd <= 0) return;
  const entryPriceCents = op.actionSide === 'YES' ? Number(op.yesAskCents) : Number(op.noAskCents);
  if (!Number.isFinite(entryPriceCents) || entryPriceCents <= 0) return;
  const shares = amountUsd / (entryPriceCents / 100);
  state.paper.positions.unshift({
    id: `p_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`,
    openedAtUtc: nowIso(),
    cityId: city.city.id,
    cityName: city.city.name,
    horizonKey: horizon.key,
    horizonLabel: horizon.label,
    targetDate: horizon.targetDate,
    opportunityId: op.id,
    bucketLabel: op.bucketLabel,
    question: op.question,
    side: op.actionSide,
    direction: op.direction,
    signalAtEntry: op.signal,
    strategyAtEntry: state.strategy,
    amountUsd,
    entryPriceCents,
    shares
  });
  savePaperStore();
  render();
}

function paperClosePosition(positionId) {
  const enriched = enrichPaperPositions();
  const pos = enriched.open.find((p) => p.id === positionId);
  if (!pos) return;
  state.paper.positions = state.paper.positions.filter((p) => p.id !== positionId);
  state.paper.closed.unshift({
    ...pos,
    closedAtUtc: nowIso(),
    exitPriceCents: pos.markPriceCents,
    realizedPnlUsd: pos.pnlUsd
  });
  if (state.paper.closed.length > 200) {
    state.paper.closed = state.paper.closed.slice(0, 200);
  }
  savePaperStore();
  render();
}

function clearPaperStore() {
  state.paper = { positions: [], closed: [] };
  savePaperStore();
  render();
}

function renderPaperPanel() {
  const data = enrichPaperPositions();
  const selectedCityId = state.selectedCityId;
  const cityOpen = data.open.filter((p) => p.cityId === selectedCityId);
  const cityClosed = data.closed.filter((p) => p.cityId === selectedCityId);
  const cityOpenPnl = cityOpen.reduce((a, b) => a + (b.pnlUsd || 0), 0);
  const bodyTop = `
    <div class="intel-card">
      <div class="intel-grid">
        <div class="kv"><div class="kv-label">Abiertas (global)</div><div class="kv-value">${data.open.length}</div></div>
        <div class="kv"><div class="kv-label">Cerradas (global)</div><div class="kv-value">${data.closed.length}</div></div>
        <div class="kv"><div class="kv-label">PnL abierto</div><div class="kv-value ${data.openPnlUsd >= 0 ? 'good' : 'bad'}">${fmtUsd(data.openPnlUsd)}</div></div>
        <div class="kv"><div class="kv-label">PnL cerrado</div><div class="kv-value ${data.closedPnlUsd >= 0 ? 'good' : 'bad'}">${fmtUsd(data.closedPnlUsd)}</div></div>
      </div>
      <div class="inline-note" style="margin-top:8px;">Ciudad seleccionada: ${selectedCityId ? `${state.cityDetails.get(selectedCityId)?.city?.name || selectedCityId} · abiertas ${cityOpen.length} · cerradas ${cityClosed.length} · PnL ${fmtUsd(cityOpenPnl)}` : 'sin ciudad seleccionada'}</div>
    </div>
  `;
  const openRows = data.open.slice(0, 10).map((p) => `
    <div class="paper-row">
      <div class="paper-col">
        <div class="paper-title">${escapeHtml(p.cityName)} · ${escapeHtml(p.bucketLabel)} · BUY ${escapeHtml(p.side)}</div>
        <div class="paper-sub">${escapeHtml(p.horizonLabel)} · Entry ${p.entryPriceCents.toFixed(1)}c · Mark ${p.markPriceCents.toFixed(1)}c · ${escapeHtml(p.strategyAtEntry)}</div>
      </div>
      <div class="paper-actions">
        <span class="${p.pnlUsd >= 0 ? 'good' : 'bad'}">${fmtUsd(p.pnlUsd)}</span>
        <button class="tiny-btn alt js-paper-close" data-paper-id="${escapeHtml(p.id)}">Cerrar</button>
      </div>
    </div>
  `).join('');
  const closedRows = data.closed.slice(0, 8).map((p) => `
    <div class="paper-row">
      <div class="paper-col">
        <div class="paper-title">${escapeHtml(p.cityName)} · ${escapeHtml(p.bucketLabel)} · ${escapeHtml(p.side)}</div>
        <div class="paper-sub">In ${Number(p.entryPriceCents).toFixed(1)}c · Out ${Number(p.exitPriceCents).toFixed(1)}c</div>
      </div>
      <div class="paper-actions"><span class="${p.realizedPnlUsd >= 0 ? 'good' : 'bad'}">${fmtUsd(p.realizedPnlUsd)}</span></div>
    </div>
  `).join('');
  el.paperBody.innerHTML = `
    ${bodyTop}
    <div class="intel-card">
      <div class="panel-sub">Posiciones abiertas (últimas 10)</div>
      <div class="intel-list" style="margin-top:8px;">${openRows || '<div class="inline-note">Sin posiciones abiertas. Usa “Simular BUY” en DETALLE.</div>'}</div>
    </div>
    <div class="intel-card">
      <div class="panel-sub">Historial cerrado (últimas 8)</div>
      <div class="intel-list" style="margin-top:8px;">${closedRows || '<div class="inline-note">Sin cierres todavía.</div>'}</div>
    </div>
  `;
  el.paperBody.querySelectorAll('.js-paper-close').forEach((btn) => {
    btn.addEventListener('click', () => paperClosePosition(btn.dataset.paperId));
  });
}

function normalizeWalletInput(value) {
  return String(value || '').trim().toLowerCase();
}

async function fetchDefaultAccountWallet() {
  const payload = await apiGet('/api/v1/account/default-wallet');
  return {
    walletAddress: payload?.walletAddress || null,
    source: payload?.source || null,
    ok: !!payload?.ok
  };
}

async function loadPolymarketAccount({ force = false } = {}) {
  const wallet = normalizeWalletInput(el.accountWalletInput?.value || state.account.wallet);
  if (!wallet) {
    state.account.error = 'Introduce una wallet Polymarket (0x...) o define POLYMARKET_WALLET_ADDRESS en local.properties.';
    state.account.snapshot = null;
    render();
    return;
  }
  if (!force && state.account.snapshot && normalizeWalletInput(state.account.snapshot.walletAddress) === wallet) {
    return;
  }
  state.account.wallet = wallet;
  state.account.loading = true;
  state.account.error = null;
  if (el.accountWalletInput) el.accountWalletInput.value = wallet;
  saveAccountStore();
  render();
  try {
    const payload = await apiGet(`/api/v1/account?wallet=${encodeURIComponent(wallet)}&limit=30`);
    state.account.snapshot = payload?.account || null;
    state.account.walletSource = payload?.walletSource || 'query';
    state.account.error = null;
    state.apiHealthy = true;
  } catch (error) {
    state.account.error = String(error?.message || error);
  } finally {
    state.account.loading = false;
    render();
  }
}

async function ensureDefaultAccountWalletPrefilled() {
  if (normalizeWalletInput(state.account.wallet || el.accountWalletInput?.value)) return;
  try {
    const info = await fetchDefaultAccountWallet();
    if (info.walletAddress) {
      state.account.wallet = normalizeWalletInput(info.walletAddress);
      state.account.walletSource = info.source || null;
      if (el.accountWalletInput) el.accountWalletInput.value = state.account.wallet;
      saveAccountStore();
    }
  } catch {
    // ignore, panel can work manually
  }
}

function renderAccountPanel() {
  if (!el.accountBody) return;
  if (state.account.loading) {
    el.accountBody.innerHTML = '<div class="intel-card">Cargando cuenta Polymarket...</div>';
    return;
  }
  if (state.account.error) {
    el.accountBody.innerHTML = `<div class="intel-card"><div class="kv-value bad">${escapeHtml(state.account.error)}</div></div>`;
    return;
  }
  const snapshot = state.account.snapshot;
  if (!snapshot) {
    const walletHint = state.account.wallet
      ? `Wallet lista: ${escapeHtml(state.account.wallet)} · pulsa Cargar.`
      : 'Introduce una wallet Polymarket o pulsa Cargar si ya detectó una por defecto.';
    el.accountBody.innerHTML = `<div class="intel-card"><div class="inline-note">${walletHint}</div></div>`;
    return;
  }

  const summary = snapshot.summary || {};
  const openRows = (snapshot.openPositions || []).slice(0, 6).map((p) => `
    <div class="intel-item">
      <div class="t">${escapeHtml(p.title || '(sin título)')} · ${escapeHtml(String(p.outcome || '--').toUpperCase())}</div>
      <div class="s">In ${fmtUsd(p.initialValueUsd)} · Now ${fmtUsd(p.currentValueUsd)} · PnL <span class="${(p.cashPnlUsd || 0) >= 0 ? 'good' : 'bad'}">${fmtUsd(p.cashPnlUsd)}</span></div>
    </div>
  `).join('');
  const closedRows = (snapshot.closedPositions || []).slice(0, 5).map((p) => `
    <div class="intel-item">
      <div class="t">${escapeHtml(p.title || '(sin título)')} · ${escapeHtml(String(p.outcome || '--').toUpperCase())}</div>
      <div class="s">Realizado ${fmtUsd(p.realizedPnlUsd)} · ${escapeHtml(tsToIso(p.timestamp))}</div>
    </div>
  `).join('');
  const tradeRows = (snapshot.recentTrades || []).slice(0, 5).map((t) => `
    <div class="intel-item">
      <div class="t">${escapeHtml(String(t.side || '--').toUpperCase())} · ${escapeHtml(String(t.outcome || '--').toUpperCase())} · ${fmtUsd(t.usdcSize || ((t.size || 0) * (t.price || 0)))}</div>
      <div class="s">${escapeHtml(t.title || '(sin título)')} · ${escapeHtml(tsToIso(t.timestamp))}</div>
    </div>
  `).join('');

  el.accountBody.innerHTML = `
    <div class="intel-card">
      <div class="detail-topline">
        <div>
          <div class="detail-city-title" style="font-size:1.02rem;">${escapeHtml(snapshot.walletAddress)}</div>
          <div class="inline-note">Fuente wallet: ${escapeHtml(state.account.walletSource || 'query')}</div>
        </div>
        <div class="inline-note">Fetch ${escapeHtml(String(snapshot.fetchedAtUtc || '--').replace('T',' ').replace('Z',' UTC'))}</div>
      </div>
      <div class="metrics-grid">
        <div class="kv"><div class="kv-label">Valor cartera</div><div class="kv-value">${fmtUsd(summary.totalValueUsd)}</div></div>
        <div class="kv"><div class="kv-label">Abiertas</div><div class="kv-value">${fmtNum(summary.openPositionsCount)}</div></div>
        <div class="kv"><div class="kv-label">Cerradas</div><div class="kv-value">${fmtNum(summary.closedPositionsCount)}</div></div>
        <div class="kv"><div class="kv-label">Trades muestra</div><div class="kv-value">${fmtNum(summary.recentTradesCount)}</div></div>
        <div class="kv"><div class="kv-label">PnL abierto</div><div class="kv-value ${(summary.openUnrealizedPnlUsd || 0) >= 0 ? 'good' : 'bad'}">${fmtUsd(summary.openUnrealizedPnlUsd)}</div></div>
        <div class="kv"><div class="kv-label">PnL cerrado</div><div class="kv-value ${(summary.closedRealizedPnlUsd || 0) >= 0 ? 'good' : 'bad'}">${fmtUsd(summary.closedRealizedPnlUsd)}</div></div>
      </div>
      ${(snapshot.warnings || []).length ? `<div class="inline-note" style="margin-top:8px;color:#ffd9a8;">${snapshot.warnings.map(escapeHtml).join(' · ')}</div>` : ''}
    </div>
    <div class="intel-card">
      <div class="panel-sub">Posiciones abiertas (últimas 6)</div>
      <div class="intel-list" style="margin-top:8px;">${openRows || '<div class="inline-note">Sin posiciones abiertas.</div>'}</div>
    </div>
    <div class="intel-card">
      <div class="panel-sub">Cierres recientes (últimos 5)</div>
      <div class="intel-list" style="margin-top:8px;">${closedRows || '<div class="inline-note">Sin cierres recientes.</div>'}</div>
    </div>
    <div class="intel-card">
      <div class="panel-sub">Trades recientes (últimos 5)</div>
      <div class="intel-list" style="margin-top:8px;">${tradeRows || '<div class="inline-note">Sin trades recientes.</div>'}</div>
    </div>
  `;
}

function loadCopyTradeStore() {
  const defaults = {
    enabled: false,
    config: { ...state.copyTrade.config }
  };
  try {
    const raw = localStorage.getItem(COPYTRADE_STORAGE_KEY);
    if (!raw) return defaults;
    const parsed = JSON.parse(raw);
    const config = {
      ...defaults.config,
      ...(parsed?.config || {})
    };
    config.pollSec = Math.max(2, Math.min(15, Number(config.pollSec) || 3));
    config.buyMinUsdc = Math.max(0, Number(config.buyMinUsdc) || 0);
    config.sellMinUsdc = Math.max(0, Number(config.sellMinUsdc) || 0);
    config.alertBuys = config.alertBuys !== false;
    config.alertSells = config.alertSells !== false;
    config.alertOthers = !!config.alertOthers;
    config.onlyWeather = config.onlyWeather !== false;
    config.onlyTrackedCities = !!config.onlyTrackedCities;
    config.browserNotifications = !!config.browserNotifications;
    config.webPushEnabled = !!config.webPushEnabled;
    return {
      enabled: !!parsed?.enabled,
      config
    };
  } catch {
    return defaults;
  }
}

function saveCopyTradeStore() {
  try {
    localStorage.setItem(COPYTRADE_STORAGE_KEY, JSON.stringify({
      enabled: !!state.copyTrade.enabled,
      config: state.copyTrade.config
    }));
  } catch {
    // ignore storage failures
  }
}

function syncCopyTradeControlsFromState() {
  if (!el.copytradeEnabled) return;
  const c = state.copyTrade.config;
  el.copytradeEnabled.checked = !!state.copyTrade.enabled;
  el.copytradePollSec.value = String(c.pollSec ?? 3);
  el.copytradeBuyMin.value = String(c.buyMinUsdc ?? 0);
  el.copytradeSellMin.value = String(c.sellMinUsdc ?? 0);
  el.copytradeAlertBuys.checked = !!c.alertBuys;
  el.copytradeAlertSells.checked = !!c.alertSells;
  el.copytradeAlertOthers.checked = !!c.alertOthers;
  el.copytradeOnlyWeather.checked = !!c.onlyWeather;
  el.copytradeOnlyTracked.checked = !!c.onlyTrackedCities;
  if (el.copytradeBrowserNotify) el.copytradeBrowserNotify.checked = !!c.browserNotifications;
  if (el.copytradeWebPushEnabled) el.copytradeWebPushEnabled.checked = !!c.webPushEnabled;
}

function readCopyTradeControlsToState() {
  if (!el.copytradeEnabled) return;
  state.copyTrade.enabled = !!el.copytradeEnabled.checked;
  state.copyTrade.config = {
    pollSec: Math.max(2, Math.min(15, Number(el.copytradePollSec?.value) || 3)),
    buyMinUsdc: Math.max(0, Number(el.copytradeBuyMin?.value) || 0),
    sellMinUsdc: Math.max(0, Number(el.copytradeSellMin?.value) || 0),
    alertBuys: !!el.copytradeAlertBuys?.checked,
    alertSells: !!el.copytradeAlertSells?.checked,
    alertOthers: !!el.copytradeAlertOthers?.checked,
    onlyWeather: !!el.copytradeOnlyWeather?.checked,
    onlyTrackedCities: !!el.copytradeOnlyTracked?.checked,
    browserNotifications: !!el.copytradeBrowserNotify?.checked,
    webPushEnabled: !!el.copytradeWebPushEnabled?.checked
  };
  saveCopyTradeStore();
  syncCopyTradeControlsFromState();
}

function browserNotificationsSupported() {
  return typeof window !== 'undefined' && 'Notification' in window;
}

function refreshNotificationCapabilityState() {
  state.copyTrade.notifySupported = browserNotificationsSupported();
  if (!state.copyTrade.notifySupported) {
    state.copyTrade.notifyPermission = 'unsupported';
    return;
  }
  state.copyTrade.notifyPermission = Notification.permission || 'default';
}

async function requestBrowserNotificationPermission() {
  refreshNotificationCapabilityState();
  refreshWebPushCapabilityState();
  if (!state.copyTrade.notifySupported) {
    state.copyTrade.lastError = 'Este navegador no soporta Notification API.';
    render();
    return;
  }
  try {
    const result = await Notification.requestPermission();
    state.copyTrade.notifyPermission = result || Notification.permission || 'default';
    state.copyTrade.webPush.permission = state.copyTrade.notifyPermission;
    if (state.copyTrade.notifyPermission === 'granted') {
      state.copyTrade.lastError = null;
    }
  } catch (error) {
    state.copyTrade.lastError = `Permiso notificaciones: ${String(error?.message || error)}`;
  }
  render();
}

function canSendBrowserNotification() {
  refreshNotificationCapabilityState();
  return !!state.copyTrade.config.browserNotifications
    && state.copyTrade.notifySupported
    && state.copyTrade.notifyPermission === 'granted';
}

function notifyCopyTradeAlertInBrowser(alert) {
  if (!canSendBrowserNotification()) return;
  if (!alert?.id || copyTradeBrowserNotifiedIds.has(alert.id)) return;
  copyTradeBrowserNotifiedIds.add(alert.id);
  if (copyTradeBrowserNotifiedIds.size > 500) {
    const keep = Array.from(copyTradeBrowserNotifiedIds).slice(-300);
    copyTradeBrowserNotifiedIds.clear();
    keep.forEach((id) => copyTradeBrowserNotifiedIds.add(id));
  }
  const side = String(alert.side || alert.type || 'ACT').toUpperCase();
  const amount = fmtUsd(alert.usdcSize);
  const cityTag = alert.trackedCityId ? ` · ${alert.trackedCityId}` : '';
  const title = `polyMeteo CopyTrade · ${side} ${amount}`;
  const body = `${alert.title || 'Actividad detectada'}${cityTag}`;
  try {
    const n = new Notification(title, {
      body,
      tag: alert.id,
      renotify: false
    });
    n.onclick = () => {
      try { window.focus(); } catch {}
    };
  } catch (error) {
    state.copyTrade.lastError = `Notification API: ${String(error?.message || error)}`;
  }
}

function currentCopyTradeUsername() {
  const intelUser = state.intelSnapshot?.profile?.username || state.intelSnapshot?.queryUsername;
  return normalizeIntelQuery(intelUser || state.intelQuery || el.intelUsernameInput?.value || '');
}

function stopCopyTradeStream({ userDisabled = false } = {}) {
  if (copyTradeReconnectTimer) {
    clearTimeout(copyTradeReconnectTimer);
    copyTradeReconnectTimer = null;
  }
  if (copyTradeEventSource) {
    try { copyTradeEventSource.close(); } catch {}
    copyTradeEventSource = null;
  }
  state.copyTrade.connecting = false;
  state.copyTrade.connected = false;
  if (userDisabled) {
    state.copyTrade.statusText = 'Desactivado';
    state.copyTrade.lastError = null;
  } else if (!state.copyTrade.enabled) {
    state.copyTrade.statusText = 'Desactivado';
  } else {
    state.copyTrade.statusText = 'Desconectado';
  }
}

function pushCopyTradeAlert(alert) {
  const list = state.copyTrade.alerts;
  if (list.some((x) => x.id === alert.id)) return;
  list.unshift(alert);
  if (list.length > COPYTRADE_ALERT_LIMIT) {
    list.length = COPYTRADE_ALERT_LIMIT;
  }
  state.copyTrade.lastAlertAtUtc = alert.atUtc || nowIso();
  state.copyTrade.lastAlertId = alert.id || null;
  state.copyTrade.stats.alerts = (state.copyTrade.stats.alerts || 0) + 1;
  notifyCopyTradeAlertInBrowser(alert);
}

function scheduleCopyTradeReconnect(ms = 3000) {
  if (!state.copyTrade.enabled) return;
  if (copyTradeReconnectTimer) clearTimeout(copyTradeReconnectTimer);
  copyTradeReconnectTimer = setTimeout(() => {
    copyTradeReconnectTimer = null;
    startCopyTradeStream({ manualRestart: false });
  }, ms);
}

function startCopyTradeStream({ manualRestart = false } = {}) {
  readCopyTradeControlsToState();
  const username = currentCopyTradeUsername();
  if (!state.copyTrade.enabled) {
    stopCopyTradeStream({ userDisabled: true });
    render();
    return;
  }
  if (!username) {
    stopCopyTradeStream();
    state.copyTrade.lastError = 'Analiza primero un usuario Polymarket.';
    state.copyTrade.statusText = 'Sin usuario';
    render();
    return;
  }
  stopCopyTradeStream();
  state.copyTrade.connecting = true;
  state.copyTrade.connected = false;
  state.copyTrade.lastError = null;
  state.copyTrade.statusText = manualRestart ? 'Reiniciando stream…' : 'Conectando SSE…';

  const c = state.copyTrade.config;
  const params = new URLSearchParams({
    username,
    pollMs: String(Math.round(c.pollSec * 1000)),
    limit: '40',
    alertBuys: c.alertBuys ? '1' : '0',
    alertSells: c.alertSells ? '1' : '0',
    alertOthers: c.alertOthers ? '1' : '0',
    onlyWeather: c.onlyWeather ? '1' : '0',
    onlyTrackedCities: c.onlyTrackedCities ? '1' : '0',
    buyMinUsdc: String(c.buyMinUsdc),
    sellMinUsdc: String(c.sellMinUsdc)
  });
  const es = new EventSource(`${API_BASE}/api/v1/copytrade/stream?${params.toString()}`);
  copyTradeEventSource = es;

  es.addEventListener('open', () => {
    state.copyTrade.connecting = false;
    state.copyTrade.connected = true;
    state.copyTrade.statusText = `Conectado @${username}`;
    render();
  });
  es.addEventListener('config', (evt) => {
    const data = JSON.parse(evt.data);
    const profileUser = data?.profile?.username || username;
    state.copyTrade.statusText = `Realtime @${profileUser}`;
    state.copyTrade.lastError = null;
    render();
  });
  es.addEventListener('baseline', (evt) => {
    const data = JSON.parse(evt.data);
    state.copyTrade.lastBaselineAtUtc = data?.atUtc || nowIso();
    state.copyTrade.stats.baselined = Number(data?.seen) || 0;
    render();
  });
  es.addEventListener('poll', (evt) => {
    const data = JSON.parse(evt.data);
    state.copyTrade.lastPollAtUtc = data?.atUtc || nowIso();
    state.copyTrade.stats.polls = (state.copyTrade.stats.polls || 0) + 1;
    if (data?.ok === false) {
      state.copyTrade.lastError = data?.error || 'Error poll';
      state.copyTrade.statusText = 'Poll con error';
    }
    render();
  });
  es.addEventListener('alert', (evt) => {
    const alert = JSON.parse(evt.data);
    pushCopyTradeAlert(alert);
    render();
  });
  es.addEventListener('error', (evt) => {
    let errorMessage = 'Stream cerrado';
    try {
      if (evt?.data) {
        const data = JSON.parse(evt.data);
        errorMessage = data?.message || errorMessage;
      }
    } catch {
      // ignore parse failures
    }
    try { es.close(); } catch {}
    if (copyTradeEventSource === es) {
      copyTradeEventSource = null;
    }
    state.copyTrade.connecting = false;
    state.copyTrade.connected = false;
    state.copyTrade.lastError = errorMessage;
    state.copyTrade.statusText = 'Reconectando…';
    render();
    scheduleCopyTradeReconnect(3000);
  });
}

function clearCopyTradeAlerts() {
  state.copyTrade.alerts = [];
  state.copyTrade.stats.alerts = 0;
  state.copyTrade.lastAlertAtUtc = null;
  state.copyTrade.lastAlertId = null;
  render();
}

function copyTradeFreshAlertWindowMs() {
  return 60_000;
}

function isFreshCopyTradeAlert(alert) {
  const at = Date.parse(alert?.atUtc || '');
  return Number.isFinite(at) && (Date.now() - at) <= copyTradeFreshAlertWindowMs();
}

function renderCopyTradePanel() {
  if (!el.copytradeAlerts) return;
  const ct = state.copyTrade;
  refreshNotificationCapabilityState();
  refreshWebPushCapabilityState();
  const statusBits = [];
  statusBits.push(ct.connected ? 'LIVE' : (ct.connecting ? 'Conectando' : 'OFF'));
  if (ct.lastError) statusBits.push(`Error: ${ct.lastError}`);
  if (ct.lastPollAtUtc) statusBits.push(`Último poll ${ct.lastPollAtUtc.replace('T', ' ').replace('Z', ' UTC')}`);
  if (ct.lastAlertAtUtc) statusBits.push(`Última alerta ${ct.lastAlertAtUtc.replace('T', ' ').replace('Z', ' UTC')}`);
  el.copytradeStatusLine.textContent = ct.statusText || 'Desactivado';
  if (el.copytradeNotifyStatus) {
    const perm = ct.notifyPermission === 'granted'
      ? 'granted'
      : ct.notifyPermission === 'denied'
        ? 'denied'
        : ct.notifyPermission === 'unsupported'
          ? 'no soportado'
          : 'default';
    el.copytradeNotifyStatus.textContent = `Permiso: ${perm}${ct.config.browserNotifications ? ' · ON' : ' · OFF'}`;
  }
  if (el.copytradeNotifyPermBtn) {
    el.copytradeNotifyPermBtn.disabled = !ct.notifySupported || ct.notifyPermission === 'granted';
    el.copytradeNotifyPermBtn.textContent = ct.notifySupported
      ? (ct.notifyPermission === 'granted' ? 'Permiso OK' : 'Permiso notificaciones')
      : 'Sin soporte';
  }
  if (el.copytradeWebPushStatus) {
    const wp = ct.webPush;
    const flags = [
      wp.serverAvailable ? 'backend OK' : (wp.serverReason ? `backend: ${wp.serverReason}` : 'backend --'),
      wp.pushSupported ? 'PushManager OK' : 'PushManager no',
      wp.permission === 'granted' ? 'permiso OK' : `permiso ${wp.permission}`
    ];
    el.copytradeWebPushStatus.textContent = `${wp.statusText || 'Web Push: --'} · ${flags.join(' · ')}`;
  }
  if (el.copytradeWebPushSyncBtn) {
    const wp = ct.webPush;
    el.copytradeWebPushSyncBtn.disabled = !!wp.syncing;
    el.copytradeWebPushTestBtn.disabled = !!wp.syncing;
    el.copytradeWebPushOffBtn.disabled = !!wp.syncing;
  }
  if (el.copytradeWebPushAdminRefreshBtn) {
    el.copytradeWebPushAdminRefreshBtn.disabled = !!ct.admin.loading || !!ct.webPush.syncing;
    el.copytradeWebPushAdminRefreshBtn.textContent = ct.admin.loading ? 'Cargando…' : 'Refrescar backend';
  }
  el.copytradeStatsLine.textContent = `Estado: ${statusBits.join(' · ')} · Polls ${ct.stats.polls || 0} · Alertas ${ct.stats.alerts || 0} · Baseline ${ct.stats.baselined || 0}`;
  if (el.copytradeWebPushAdminBody) {
    const admin = ct.admin;
    if (admin.loading && !admin.lastFetchAtUtc) {
      el.copytradeWebPushAdminBody.innerHTML = '<div class="inline-note">Cargando datos backend…</div>';
    } else if (admin.error && !admin.lastFetchAtUtc) {
      el.copytradeWebPushAdminBody.innerHTML = `<div class="inline-note" style="color:#ffd2d9;">${escapeHtml(admin.error)}</div>`;
    } else {
      const subsRows = (admin.subscriptions || []).slice(0, 8).map((s) => `
        <div class="copytrade-admin-item">
          <div class="t">@${escapeHtml(s.username || '--')} · ${s.monitorActive ? 'monitor ON' : 'monitor OFF'} · pushes ${fmtNum(s.pushesSent || 0)}</div>
          <div class="s">${escapeHtml(s.id || '--')} · poll ${Number(s.config?.pollSec || 0)}s · updated ${escapeHtml(String(s.updatedAtUtc || '--').replace('T', ' ').replace('Z', ' UTC'))}</div>
        </div>
      `).join('');
      const auditRows = (admin.audit || []).slice(0, 8).map((e) => `
        <div class="copytrade-admin-item">
          <div class="t">${escapeHtml(e.type || 'event')}</div>
          <div class="s">${escapeHtml(String(e.atUtc || '--').replace('T', ' ').replace('Z', ' UTC'))} · ${escapeHtml(JSON.stringify(e.data || {}).slice(0, 220))}</div>
        </div>
      `).join('');
      el.copytradeWebPushAdminBody.innerHTML = `
        ${admin.error ? `<div class="inline-note" style="color:#ffd2d9;">${escapeHtml(admin.error)}</div>` : ''}
        <div class="inline-note">Última lectura backend: ${admin.lastFetchAtUtc ? escapeHtml(admin.lastFetchAtUtc.replace('T', ' ').replace('Z', ' UTC')) : '--'}</div>
        <div class="copytrade-admin-box">
          <div class="copytrade-admin-title">Suscripciones activas (${fmtNum((admin.subscriptions || []).length)})</div>
          <div class="copytrade-admin-list">${subsRows || '<div class="inline-note">Sin suscripciones registradas.</div>'}</div>
        </div>
        <div class="copytrade-admin-box">
          <div class="copytrade-admin-title">Auditoría backend (últimos eventos)</div>
          <div class="copytrade-admin-list">${auditRows || '<div class="inline-note">Sin eventos todavía.</div>'}</div>
        </div>
      `;
    }
  }

  if (!ct.alerts.length) {
    el.copytradeAlerts.innerHTML = `<div class="inline-note">${ct.enabled ? 'Monitor activo. Esperando nuevas actividades que cumplan filtros.' : 'Activa CopyTrade para ver alertas en tiempo real.'}</div>`;
    return;
  }

  el.copytradeAlerts.innerHTML = ct.alerts.slice(0, 18).map((a) => {
    const sideClass = (a.side || '').toLowerCase() === 'buy' ? 'buy' : (a.side || '').toLowerCase() === 'sell' ? 'sell' : 'other';
    const freshCls = isFreshCopyTradeAlert(a) ? 'flash' : '';
    const ts = a.activityTimestamp ? tsToIso(a.activityTimestamp) : (a.atUtc || '').replace('T', ' ').replace('Z', ' UTC');
    const tags = [
      a.weatherLike ? 'meteo' : null,
      a.trackedCityId ? a.trackedCityId : null,
      a.outcome ? String(a.outcome).toUpperCase() : null
    ].filter(Boolean).join(' · ');
    return `
      <div class="copytrade-alert-item ${freshCls}">
        <div class="copytrade-alert-top">
          <span class="copytrade-alert-side ${sideClass}">${escapeHtml((a.side || a.type || 'ACT').toUpperCase())}</span>
          <span class="copytrade-alert-amt">${fmtUsd(a.usdcSize)}</span>
        </div>
        <div class="copytrade-alert-title">${escapeHtml(a.title || '(sin título)')}</div>
        <div class="copytrade-alert-sub">${escapeHtml(tags || 'sin tags')} · ${escapeHtml(ts)}</div>
      </div>
    `;
  }).join('');

}

function tsToIso(ts) {
  if (!Number.isFinite(Number(ts))) return '--';
  const ms = Number(ts) > 10_000_000_000 ? Number(ts) : Number(ts) * 1000;
  return new Date(ms).toISOString().replace('T', ' ').replace('Z', ' UTC');
}

function renderIntelPanel() {
  if (state.intelLoading) {
    el.intelBody.innerHTML = '<div class="intel-card">Cargando Intel usuario...</div>';
    return;
  }
  if (state.intelError) {
    el.intelBody.innerHTML = `<div class="intel-card"><div class="kv-value bad">${escapeHtml(state.intelError)}</div></div>`;
    return;
  }
  const intel = state.intelSnapshot;
  if (!intel) {
    el.intelBody.innerHTML = '<div class="intel-card">Introduce un usuario y pulsa Analizar.</div>';
    return;
  }
  const profile = intel.profile || {};
  const ins = intel.insights || {};
  const topCities = (ins.topTrackedCities || []).map((x) =>
    `<div class="intel-item"><div class="t">${escapeHtml(x.cityId)} · ${x.count} trades</div><div class="s">Total aprox ${fmtUsd(x.totalUsdc)}</div></div>`
  ).join('');
  const topEvents = (ins.topEvents || []).slice(0, 5).map((x) =>
    `<div class="intel-item"><div class="t">${escapeHtml(x.title || x.eventSlug || 'evento')}</div><div class="s">${x.tradesCount} trades · ${fmtUsd(x.totalUsdc)}</div></div>`
  ).join('');
  const notes = (ins.styleNotes || []).map((n) => `<div class="intel-item"><div class="t">${escapeHtml(n)}</div></div>`).join('');
  const recentActivity = (intel.activity || []).slice(0, 6).map((a) =>
    `<div class="intel-item"><div class="t">${escapeHtml((a.side || a.type || 'ACT').toUpperCase())} · ${escapeHtml(a.outcome || '--')} · ${fmtUsd(a.usdcSize || ((a.size || 0) * (a.price || 0)))}</div><div class="s">${escapeHtml(a.title || '')} · ${escapeHtml(tsToIso(a.timestamp))}</div></div>`
  ).join('');

  el.intelBody.innerHTML = `
    <div class="intel-card">
      <div class="detail-topline">
        <div>
          <div class="detail-city-title" style="font-size:1.05rem;">@${escapeHtml(profile.username || intel.queryUsername)}</div>
          <div class="inline-note">${escapeHtml(profile.pseudonym || 'sin alias')} · ${escapeHtml(profile.proxyWallet || '--')}</div>
        </div>
        <div class="inline-note">Fetch ${escapeHtml((intel.fetchedAtUtc || '').replace('T',' ').replace('Z',' UTC'))}</div>
      </div>
      <div class="metrics-grid">
        <div class="kv"><div class="kv-label">Valor cartera (si API)</div><div class="kv-value">${fmtUsd(intel.account?.portfolioValueUsdc)}</div></div>
        <div class="kv"><div class="kv-label">Trades muestra</div><div class="kv-value">${fmtNum(ins.tradeCount)}</div></div>
        <div class="kv"><div class="kv-label">BUY / SELL</div><div class="kv-value">${fmtNum(ins.buyCount)} / ${fmtNum(ins.sellCount)}</div></div>
        <div class="kv"><div class="kv-label">YES / NO</div><div class="kv-value">${fmtNum(ins.yesCount)} / ${fmtNum(ins.noCount)}</div></div>
        <div class="kv"><div class="kv-label">Ticket medio</div><div class="kv-value">${fmtUsd(ins.avgTradeUsdc)}</div></div>
        <div class="kv"><div class="kv-label">Total muestra</div><div class="kv-value">${fmtUsd(ins.totalTradeUsdc)}</div></div>
      </div>
      <div class="inline-note" style="margin-top:8px;">Meteo ${fmtPct((ins.weatherTradeRatio || 0) * 100)} · Ciudades PolyMeteo ${fmtPct((ins.trackedCitiesRatio || 0) * 100)} · Última actividad ${escapeHtml(tsToIso(ins.lastActivityAt))}</div>
      ${(intel.warnings || []).length ? `<div class="inline-note" style="margin-top:6px;color:#ffd9a8;">${(intel.warnings || []).map(escapeHtml).join(' · ')}</div>` : ''}
    </div>
    <div class="intel-card">
      <div class="panel-sub">Top ciudades del radar PolyMeteo</div>
      <div class="intel-list" style="margin-top:8px;">${topCities || '<div class="inline-note">Sin foco claro en ciudades PolyMeteo en la muestra.</div>'}</div>
    </div>
    <div class="intel-card">
      <div class="panel-sub">Top eventos (muestra reciente)</div>
      <div class="intel-list" style="margin-top:8px;">${topEvents || '<div class="inline-note">Sin trades recientes.</div>'}</div>
    </div>
    <div class="intel-card">
      <div class="panel-sub">Lectura rápida del estilo</div>
      <div class="intel-list" style="margin-top:8px;">${notes || '<div class="inline-note">Sin patrones claros todavía (muestra pequeña).</div>'}</div>
    </div>
    <div class="intel-card">
      <div class="panel-sub">Actividad reciente (últimos movimientos)</div>
      <div class="intel-list" style="margin-top:8px;">${recentActivity || '<div class="inline-note">Sin actividad disponible.</div>'}</div>
    </div>
  `;
}

function formatGeneratedLine(meta, cities) {
  if (!meta) return 'Cargando metadata...';
  const generated = meta.generatedAtUtc ? meta.generatedAtUtc.replace('T', ' ').replace('Z', ' UTC') : '--';
  const loaded = cities.filter(isLoadedCity).length;
  const total = state.citiesLoadExpected || cities.length;
  const progress = state.loadingCities && total > 0 ? ` · ${loaded}/${total} cargadas` : '';
  return `Fuente ${meta.source} · Generado ${generated} · ${total || cities.length} ciudades${progress} · API ${meta.apiVersion}`;
}

function currentStrategyKey() {
  return state.strategy === 'conservadora' ? 'conservadora' : 'agresiva';
}

function strategyTopSummary(city) {
  const strategyKey = currentStrategyKey();
  return city?.strategyView?.[strategyKey]?.topSummary || city?.topSummary || null;
}

function strategyTopTodaySummary(city) {
  const strategyKey = currentStrategyKey();
  return city?.strategyView?.[strategyKey]?.topTodaySummary || city?.topTodaySummary || null;
}

function strategyHorizonTag(city) {
  const strategyKey = currentStrategyKey();
  return city?.strategyView?.[strategyKey]?.horizonAvailability?.tag || city?.horizonAvailability?.tag || '';
}

function deriveTickerItems(cities) {
  const items = cities
    .filter(isLoadedCity)
    .map((city) => {
      const top = strategyTopSummary(city);
      if (!top) return null;
      return {
        cityId: city.id,
        cityName: city.city.name,
        ...top
      };
    })
    .filter(Boolean)
    .sort((a, b) => b.executableEdgePct - a.executableEdgePct);
  return items;
}

function renderTicker() {
  const loadedCities = state.cities.filter(isLoadedCity);
  if (state.loadingCities && loadedCities.length === 0) {
    el.tickerTrack.textContent = 'Cargando oportunidades...';
    return;
  }
  if (!loadedCities.length) {
    el.tickerTrack.textContent = state.apiError ? `Error API: ${state.apiError}` : 'Sin datos.';
    return;
  }
  const items = deriveTickerItems(loadedCities);
  if (!items.length) {
    el.tickerTrack.textContent = 'Sin oportunidades visibles (web local).';
    return;
  }
  const top = items[0];
  const flash = top.signal === 'GREEN' && top.executableEdgePct >= 15;
  const text = items.slice(0, 5).map((item) => `${item.tickerText}`).join('  •  ');
  el.tickerTrack.innerHTML = `${flash ? '<span class="flash">FLASH</span>' : ''}${escapeHtml(text)}`;
}

function gridMarketLine(city) {
  const strategyTop = strategyTopSummary(city);
  if (city.isClosedBySchedule) {
    return { textLeft: 'CERRADO POR HORARIO', textRight: '', cls: 'neutral' };
  }
  const top = strategyTop;
  if (!top) {
    return { textLeft: 'SIN OPORTUNIDADES', textRight: '', cls: 'neutral' };
  }
  return {
    textLeft: `${top.direction} · BET ${top.actionSide}`,
    textRight: `${top.executableEdgePct.toFixed(1)}%`,
    cls: signalClass(top.signal)
  };
}

function renderCitiesGrid() {
  if (state.loadingCities && state.cities.length === 0) {
    el.citiesGrid.innerHTML = '<div class="skeleton-card">Cargando ciudades...</div>';
    return;
  }

  const loadedCities = state.cities.filter(isLoadedCity);
  const openCount = loadedCities.filter((c) => strategyTopSummary(c)).length;
  const closedCount = loadedCities.filter((c) => c.isClosedBySchedule).length;
  const pendingCount = Math.max(0, (state.citiesLoadExpected || state.cities.length) - loadedCities.length);
  el.gridStats.textContent = state.loadingCities
    ? `${loadedCities.length}/${state.citiesLoadExpected || state.cities.length} cargadas · ${pendingCount} pendientes · ${openCount} con oportunidad`
    : `${state.cities.length} ciudades · ${openCount} con oportunidad · ${closedCount} cerradas por horario`;

  if (state.mode === 'rookie' && loadedCities.length > 0) {
    const rookieItems = loadedCities
      .map((city) => ({ city, top: strategyTopSummary(city) }))
      .filter((item) => item.top)
      .sort((a, b) => b.top.executableEdgePct - a.top.executableEdgePct)
      .slice(0, 14);

    if (!rookieItems.length) {
      el.citiesGrid.innerHTML = `<div class="skeleton-card">No hay oportunidades claras con estrategia ${escapeHtml(state.strategy)}.</div>`;
      return;
    }

    el.citiesGrid.innerHTML = rookieItems.map(({ city, top }) => {
      const active = city.id === state.selectedCityId;
      const horizon = top.horizonLabel || horizonLabel(top.horizonKey);
      const signalCls = signalClass(top.signal);
      const title = top.signal === 'GREEN' ? 'Apuesta posible' : 'Entrada con cuidado';
      const action = `Comprar ${top.actionSide}`;
      const why = `${top.direction} · ventaja ${fmtPct(top.executableEdgePct)} · ${horizon}`;
      return `
        <article class="rookie-city-card ${active ? 'active' : ''}" data-city-id="${city.id}">
          <div class="rookie-city-top">
            <div>
              <div class="rookie-city-name">${escapeHtml(city.city.name)} <span class="rookie-horizon">${escapeHtml(horizon)}</span></div>
              <div class="rookie-city-why">${escapeHtml(why)}</div>
            </div>
            <span class="market-pill ${signalCls}">${escapeHtml(title)}</span>
          </div>
          <div class="rookie-city-metrics">
            <span>M ${escapeHtml(city.summary.metar.primary)}</span>
            <span>S ${escapeHtml(city.summary.station.primary)}</span>
            <span>MM ${escapeHtml(city.summary.mm.primary)}</span>
            <span class="${city.summary.mma.invalidToday ? 'bad' : 'good'}">MMA ${escapeHtml(city.summary.mma.primary)}</span>
          </div>
          <div class="rookie-city-actions">
            <span class="rookie-cta">${escapeHtml(action)}</span>
            <span class="rookie-edge">${fmtPct(top.executableEdgePct)}</span>
          </div>
        </article>
      `;
    }).join('');

    el.citiesGrid.querySelectorAll('.rookie-city-card').forEach((card) => {
      card.addEventListener('click', () => {
        const cityId = card.getAttribute('data-city-id');
        if (cityId) selectCity(cityId, { pushHash: true });
      });
    });
    return;
  }

  el.citiesGrid.innerHTML = state.cities.map((city) => {
    if (!isLoadedCity(city)) {
      const active = city.id === state.selectedCityId;
      return `
        <article class="city-card placeholder ${active ? 'active' : ''}" data-city-id="${city.id}">
          <div class="city-head">
            <div class="city-name">${escapeHtml(city.city?.name || city.name || city.id)}</div>
            <div class="city-time">--:--</div>
          </div>
          <div class="city-metrics">
            <div class="city-metric"><b>M</b>--</div>
            <div class="city-metric"><b>Δ</b>--</div>
            <div class="city-metric"><b>S</b>--</div>
            <div class="city-metric"><b>MMA</b>--</div>
          </div>
          <div class="city-market-line neutral">
            <span>CARGANDO DATOS...</span>
            <span>…</span>
          </div>
        </article>
      `;
    }
    const line = gridMarketLine(city);
    const active = city.id === state.selectedCityId;
    const horizonTag = strategyHorizonTag(city);
    const tag = horizonTag ? `<span class="city-tag">${escapeHtml(horizonTag)}</span>` : '';
    const mmClass = city.summary.mm.invalidToday ? 'bad' : 'good';
    const mmaClass = city.summary.mma.invalidToday ? 'bad' : 'good';
    return `
      <article class="city-card ${active ? 'active' : ''} ${city.isClosedBySchedule ? 'closed' : ''}" data-city-id="${city.id}">
        <div class="city-head">
          <div class="city-name">${escapeHtml(city.city.name)} ${tag}</div>
          <div class="city-time">${escapeHtml(city.localTime)}</div>
        </div>
        <div class="city-metrics">
          <div class="city-metric"><b>M</b>${escapeHtml(city.summary.metar.primary)}</div>
          <div class="city-metric"><b>Δ</b><span class="${city.summary.delta.value === 0 ? '' : city.summary.delta.value > 0 ? 'good' : 'bad'}">${escapeHtml(city.summary.delta.display)}</span></div>
          <div class="city-metric"><b>S</b>${escapeHtml(city.summary.station.primary)}</div>
          <div class="city-metric"><b>MMA</b><span class="${mmaClass}">${escapeHtml(city.summary.mma.primary)}</span></div>
        </div>
        <div class="city-market-line ${line.cls}">
          <span>${escapeHtml(line.textLeft)}</span>
          <span>${escapeHtml(line.textRight)}</span>
        </div>
      </article>
    `;
  }).join('');

  el.citiesGrid.querySelectorAll('.city-card').forEach((card) => {
    card.addEventListener('click', () => {
      const cityId = card.getAttribute('data-city-id');
      if (cityId) {
        selectCity(cityId, { pushHash: true });
      }
    });
  });
}

function renderMeta() {
  el.metaLine.textContent = formatGeneratedLine(state.meta, state.cities);
  const modeLabel = state.citiesLoadMode === 'stream' ? 'stream' : 'full';
  el.apiStatus.textContent = state.apiHealthy ? `API ${state.meta?.source || 'OK'} · ${state.strategy} · ${modeLabel}` : 'API error';
  el.apiStatus.classList.toggle('error', !state.apiHealthy);
}

function renderOpsDashboard() {
  if (!el.opsDashboardBody) return;
  const loadedCities = state.cities.filter(isLoadedCity);
  const openCities = loadedCities.filter((c) => strategyTopSummary(c)).length;
  const closedCities = loadedCities.filter((c) => c.isClosedBySchedule).length;
  const selectedCity = state.selectedCityId ? state.cityDetails.get(state.selectedCityId) : null;
  const selectedCityName = selectedCity?.city?.name || (state.selectedCityId || '--');
  const selectedHorizon = selectedCity?.horizons?.find((h) => h.key === state.selectedHorizonKey) || selectedCity?.horizons?.[0] || null;
  const selectedOpsCount = selectedHorizon
    ? (selectedHorizon.opportunities || []).filter((op) => (op.strategyEligible?.[currentStrategyKey()] ?? true)).length
    : 0;

  const paper = enrichPaperPositions();
  const account = state.account.snapshot;
  const accountSummary = account?.summary || {};
  const intel = state.intelSnapshot;
  const intelProfile = intel?.profile || {};
  const intelInsights = intel?.insights || {};
  const ct = state.copyTrade;
  const wp = ct.webPush;
  const admin = ct.admin;
  const health = state.backendHealth;

  const recentAlerts = ct.alerts.slice(0, 3).map((a) => `
    <div class="ops-item">
      <div class="t">${escapeHtml((a.side || a.type || 'ACT').toUpperCase())} · ${fmtUsd(a.usdcSize)} · ${escapeHtml(a.trackedCityId || 'sin ciudad')}</div>
      <div class="s">${escapeHtml((a.title || '').slice(0, 120))}</div>
    </div>
  `).join('');

  const recentAudit = (admin.audit || []).slice(0, 4).map((e) => `
    <div class="ops-item">
      <div class="t">${escapeHtml(e.type || 'event')}</div>
      <div class="s">${escapeHtml(String(e.atUtc || '--').replace('T', ' ').replace('Z', ' UTC'))}</div>
    </div>
  `).join('');

  el.opsDashboardBody.innerHTML = `
    <div class="ops-grid">
      <section class="ops-card">
        <div class="ops-card-title">Backend / motor web</div>
        <div class="ops-card-sub">${escapeHtml(health?.service || 'polymeteo-web-api')} · ${escapeHtml(health?.mode || state.meta?.source || '--')}</div>
        <div class="ops-kpis">
          <div class="ops-kpi"><div class="k">API</div><div class="v ${state.apiHealthy ? 'good' : 'bad'}">${state.apiHealthy ? 'OK' : 'ERROR'}</div></div>
          <div class="ops-kpi"><div class="k">Provider</div><div class="v">${escapeHtml(health?.provider?.source || '--')}</div></div>
          <div class="ops-kpi"><div class="k">Ciudades</div><div class="v">${fmtNum(loadedCities.length)}/${fmtNum(state.citiesLoadExpected || state.cities.length)}</div></div>
          <div class="ops-kpi"><div class="k">Con oportunidad</div><div class="v">${fmtNum(openCities)}</div></div>
          <div class="ops-kpi"><div class="k">Cerradas</div><div class="v">${fmtNum(closedCities)}</div></div>
          <div class="ops-kpi"><div class="k">Refresco</div><div class="v">${escapeHtml(state.backendHealthFetchedAtUtc ? state.backendHealthFetchedAtUtc.replace('T',' ').replace('Z',' UTC') : '--')}</div></div>
        </div>
      </section>

      <section class="ops-card">
        <div class="ops-card-title">Operativa actual (UI)</div>
        <div class="ops-card-sub">Modo ${escapeHtml(state.mode)} · estrategia ${escapeHtml(state.strategy)}</div>
        <div class="ops-kpis">
          <div class="ops-kpi"><div class="k">Ciudad seleccionada</div><div class="v">${escapeHtml(selectedCityName)}</div></div>
          <div class="ops-kpi"><div class="k">Horizonte</div><div class="v">${escapeHtml(selectedHorizon?.label || '--')}</div></div>
          <div class="ops-kpi"><div class="k">Mercados visibles</div><div class="v">${fmtNum(selectedOpsCount)}</div></div>
          <div class="ops-kpi"><div class="k">Ticker top</div><div class="v">${escapeHtml((deriveTickerItems(loadedCities)[0]?.tickerText || '--').slice(0, 28))}</div></div>
        </div>
        <div class="ops-inline">${escapeHtml(state.apiError ? `API error: ${state.apiError}` : 'Sin errores API visibles en este momento.')}</div>
      </section>

      <section class="ops-card">
        <div class="ops-card-title">Paper Trading (web local)</div>
        <div class="ops-card-sub">Persistencia local (${PAPER_STORAGE_KEY})</div>
        <div class="ops-kpis">
          <div class="ops-kpi"><div class="k">Abiertas</div><div class="v">${fmtNum(paper.open.length)}</div></div>
          <div class="ops-kpi"><div class="k">Cerradas</div><div class="v">${fmtNum(paper.closed.length)}</div></div>
          <div class="ops-kpi"><div class="k">PnL abierto</div><div class="v ${paper.openPnlUsd >= 0 ? 'good' : 'bad'}">${fmtUsd(paper.openPnlUsd)}</div></div>
          <div class="ops-kpi"><div class="k">PnL cerrado</div><div class="v ${paper.closedPnlUsd >= 0 ? 'good' : 'bad'}">${fmtUsd(paper.closedPnlUsd)}</div></div>
        </div>
      </section>

      <section class="ops-card">
        <div class="ops-card-title">Cuenta Polymarket (wallet)</div>
        <div class="ops-card-sub">${escapeHtml(account?.walletAddress || state.account.wallet || 'Sin wallet cargada')}</div>
        <div class="ops-kpis">
          <div class="ops-kpi"><div class="k">Valor cartera</div><div class="v">${fmtUsd(accountSummary.totalValueUsd)}</div></div>
          <div class="ops-kpi"><div class="k">Abiertas</div><div class="v">${fmtNum(accountSummary.openPositionsCount || 0)}</div></div>
          <div class="ops-kpi"><div class="k">Cerradas</div><div class="v">${fmtNum(accountSummary.closedPositionsCount || 0)}</div></div>
          <div class="ops-kpi"><div class="k">Trades</div><div class="v">${fmtNum(accountSummary.recentTradesCount || 0)}</div></div>
          <div class="ops-kpi"><div class="k">PnL abierto</div><div class="v ${(accountSummary.openUnrealizedPnlUsd || 0) >= 0 ? 'good' : 'bad'}">${fmtUsd(accountSummary.openUnrealizedPnlUsd)}</div></div>
          <div class="ops-kpi"><div class="k">PnL cerrado</div><div class="v ${(accountSummary.closedRealizedPnlUsd || 0) >= 0 ? 'good' : 'bad'}">${fmtUsd(accountSummary.closedRealizedPnlUsd)}</div></div>
        </div>
        <div class="ops-inline">${escapeHtml(state.account.error || (account ? `Fuente wallet: ${state.account.walletSource || 'query'} · fetch ${String(account.fetchedAtUtc || '--').replace('T',' ').replace('Z',' UTC')}` : 'Sin cuenta cargada todavía.'))}</div>
      </section>

      <section class="ops-card">
        <div class="ops-card-title">CopyTrade SSE (foreground)</div>
        <div class="ops-card-sub">${escapeHtml(ct.statusText || 'Desactivado')}</div>
        <div class="ops-kpis">
          <div class="ops-kpi"><div class="k">Estado</div><div class="v ${ct.connected ? 'good' : (ct.connecting ? 'warn' : '')}">${ct.connected ? 'LIVE' : (ct.connecting ? 'Conectando' : 'OFF')}</div></div>
          <div class="ops-kpi"><div class="k">Usuario</div><div class="v">${escapeHtml(currentCopyTradeUsername() || '--')}</div></div>
          <div class="ops-kpi"><div class="k">Polls</div><div class="v">${fmtNum(ct.stats.polls || 0)}</div></div>
          <div class="ops-kpi"><div class="k">Alertas</div><div class="v">${fmtNum(ct.stats.alerts || 0)}</div></div>
          <div class="ops-kpi"><div class="k">Baseline</div><div class="v">${fmtNum(ct.stats.baselined || 0)}</div></div>
          <div class="ops-kpi"><div class="k">Último poll</div><div class="v">${escapeHtml(ct.lastPollAtUtc ? ct.lastPollAtUtc.replace('T',' ').replace('Z',' UTC') : '--')}</div></div>
        </div>
        <div class="ops-list">${recentAlerts || '<div class="inline-note">Sin alertas recientes en la página.</div>'}</div>
      </section>

      <section class="ops-card">
        <div class="ops-card-title">Web Push (server-side)</div>
        <div class="ops-card-sub">${escapeHtml(wp.statusText || '--')}</div>
        <div class="ops-kpis">
          <div class="ops-kpi"><div class="k">Backend</div><div class="v ${wp.serverAvailable ? 'good' : 'bad'}">${wp.serverAvailable ? 'OK' : 'NO'}</div></div>
          <div class="ops-kpi"><div class="k">PushManager</div><div class="v ${wp.pushSupported ? 'good' : 'bad'}">${wp.pushSupported ? 'OK' : 'NO'}</div></div>
          <div class="ops-kpi"><div class="k">Permiso</div><div class="v ${wp.permission === 'granted' ? 'good' : (wp.permission === 'denied' ? 'bad' : 'warn')}">${escapeHtml(wp.permission || '--')}</div></div>
          <div class="ops-kpi"><div class="k">Suscripciones</div><div class="v">${fmtNum((admin.subscriptions || []).length)}</div></div>
          <div class="ops-kpi"><div class="k">Monitores ON</div><div class="v">${fmtNum((admin.subscriptions || []).filter((s) => s.monitorActive).length)}</div></div>
          <div class="ops-kpi"><div class="k">Último sync</div><div class="v">${escapeHtml(wp.lastSyncAtUtc ? wp.lastSyncAtUtc.replace('T',' ').replace('Z',' UTC') : '--')}</div></div>
        </div>
        <div class="ops-list">${recentAudit || '<div class="inline-note">Sin auditoría cargada todavía.</div>'}</div>
      </section>

      <section class="ops-card">
        <div class="ops-card-title">Intel usuario Polymarket</div>
        <div class="ops-card-sub">${intel ? `@${intelProfile.username || intel.queryUsername}` : 'Sin análisis cargado'}</div>
        <div class="ops-kpis">
          <div class="ops-kpi"><div class="k">Alias</div><div class="v">${escapeHtml(intelProfile.pseudonym || '--')}</div></div>
          <div class="ops-kpi"><div class="k">Valor cartera</div><div class="v">${fmtUsd(intel?.account?.portfolioValueUsdc)}</div></div>
          <div class="ops-kpi"><div class="k">Trades muestra</div><div class="v">${fmtNum(intelInsights.tradeCount || 0)}</div></div>
          <div class="ops-kpi"><div class="k">BUY/SELL</div><div class="v">${fmtNum(intelInsights.buyCount || 0)}/${fmtNum(intelInsights.sellCount || 0)}</div></div>
          <div class="ops-kpi"><div class="k">Meteo</div><div class="v">${fmtPct((intelInsights.weatherTradeRatio || 0) * 100)}</div></div>
          <div class="ops-kpi"><div class="k">Ciudades PolyMeteo</div><div class="v">${fmtPct((intelInsights.trackedCitiesRatio || 0) * 100)}</div></div>
        </div>
        <div class="ops-inline">${escapeHtml(state.intelError || (intel ? `Último fetch ${String(intel.fetchedAtUtc || '--').replace('T',' ').replace('Z',' UTC')}` : 'Sin datos Intel todavía.'))}</div>
      </section>
    </div>
  `;
}

function render() {
  setSegmentedActive(el.modeSwitch, 'mode', state.mode);
  setSegmentedActive(el.strategySwitch, 'strategy', state.strategy);
  renderMeta();
  renderOpsDashboard();
  renderTicker();
  renderCitiesGrid();
  renderDetail();
  renderPaperPanel();
  renderAccountPanel();
  renderIntelPanel();
  renderCopyTradePanel();
}

function rookieTagline(op) {
  if (!op.shouldTrade) return 'Mejor esperar';
  if (op.signal === 'GREEN') return 'Buena oportunidad';
  if (op.signal === 'YELLOW') return 'Entrada posible con cuidado';
  return 'Alta incertidumbre';
}

function rookieHelp(op) {
  const execution = op.fillProbabilityPct >= 70 ? 'buena' : op.fillProbabilityPct >= 55 ? 'media' : 'difícil';
  return `Acción sugerida: comprar ${op.actionSide}. Ventaja real ${fmtPct(op.executableEdgePct)} con ejecución ${execution}. Coste total ${fmtPct(op.totalCostPct)}.`;
}

function buildPolymarketUrl(city, horizon) {
  const slugBase = POLYMARKET_CITY_SLUG[city.id] || city.id;
  const [year, month, day] = horizon.targetDate.split('-').map(Number);
  const date = new Date(Date.UTC(year, month - 1, day));
  const monthName = date.toLocaleString('en-US', { month: 'long', timeZone: 'UTC' }).toLowerCase();
  return `https://polymarket.com/es/event/highest-temperature-in-${slugBase}-on-${monthName}-${day}-${year}`;
}

function renderDetail() {
  const city = state.selectedCityId ? state.cityDetails.get(state.selectedCityId) : null;
  el.refreshDetailBtn.disabled = !state.selectedCityId || state.loadingDetail;

  if (!city) {
    el.detailTitle.textContent = 'DETALLE';
    el.detailBody.className = 'detail-body empty-state';
    el.detailBody.textContent = state.loadingDetail ? 'Cargando detalle...' : 'Selecciona una ciudad del grid para ver DETALLE.';
    return;
  }

  el.detailTitle.textContent = `DETALLE · ${city.city.name}`;
  el.detailBody.className = 'detail-body';

  const selectedHorizon = city.horizons.find((h) => h.key === state.selectedHorizonKey) || city.horizons[0];
  const strategyKey = state.strategy === 'conservadora' ? 'conservadora' : 'agresiva';
  const visibleOps = (selectedHorizon.opportunities || []).filter((op) => (op.strategyEligible?.[strategyKey] ?? true));
  const topRef = visibleOps
    .slice()
    .sort((a, b) => (b.executableEdgePct ?? -999) - (a.executableEdgePct ?? -999))[0]
    || null;
  const realBoardHorizon = city.polymarketReal?.horizons?.find((h) => h.key === selectedHorizon.key);

  const headerHtml = `
    <section class="detail-card">
      <div class="detail-topline">
        <h3 class="detail-city-title">${escapeHtml(city.city.name)}</h3>
        <div class="detail-meta">${escapeHtml(city.city.metarCode)} · ${escapeHtml(city.localTime)} · ${escapeHtml(city.source)}</div>
      </div>
      <div class="metrics-grid">
        <div class="kv"><div class="kv-label">METAR actual</div><div class="kv-value">${escapeHtml(city.metarDetail.metarCurrent.primary)} <span class="sub">(${escapeHtml(city.metarDetail.observedAtLocal)})</span></div></div>
        <div class="kv"><div class="kv-label">Temp estación control</div><div class="kv-value">${escapeHtml(city.metarDetail.stationControl.primary)} <span class="sub">(${escapeHtml(city.metarDetail.stationControl.secondary)})</span></div></div>
        <div class="kv"><div class="kv-label">Media Modelos (MM)</div><div class="kv-value ${city.summary.mm.invalidToday ? 'bad' : ''}">${escapeHtml(city.summary.mm.primary)} <span class="sub">(${escapeHtml(city.summary.mm.secondary)})</span></div></div>
        <div class="kv"><div class="kv-label">Media Modelos Ajustada (MMA)</div><div class="kv-value ${city.summary.mma.invalidToday ? 'bad' : 'good'}">${escapeHtml(city.summary.mma.primary)} <span class="sub">(${escapeHtml(city.summary.mma.secondary)})</span></div></div>
      </div>
      <div class="inline-note" style="margin-top:8px;">Modelo dominante histórico: ${escapeHtml(city.premium.dominantModel)} · Peso ${fmtPct(city.premium.dominantWeightPct)} · Cache ${escapeHtml(city.premium.cacheStatus)}</div>
      ${state.mode === 'rookie' ? `<div class="rookie-help">Lectura rápida: compara <strong>MMA</strong> con la temperatura ya observada y revisa primero los mercados en verde del horizonte seleccionado.</div>` : ''}
    </section>
  `;

  const tabsHtml = `
    <section class="detail-card">
      <div class="detail-topline">
        <div>
          <div class="panel-sub">Horizonte</div>
          <div class="inline-note">METAR: ${selectedHorizon.metarAvailable ? 'Disponible (Hoy)' : 'SIN METAR (horizonte futuro)'}</div>
        </div>
        <div class="inline-note">${escapeHtml(selectedHorizon.targetDate)}</div>
      </div>
        <div class="horizon-tabs">
        ${city.horizons.map((h) => {
          const count = (h.opportunities || []).filter((op) => (op.strategyEligible?.[strategyKey] ?? true)).length;
          return `<button class="horizon-tab ${h.key === selectedHorizon.key ? 'active' : ''}" data-horizon="${h.key}">${h.label} (${count})</button>`;
        }).join('')}
      </div>
      <div class="inline-note" style="margin-top:8px;">${escapeHtml(visibleOps.length ? `${visibleOps.length} oportunidad(es) visible(s)` : (city.isClosedBySchedule && selectedHorizon.key === 'today' ? 'CERRADO POR HORARIO' : `Sin oportunidades en ${selectedHorizon.label} para estrategia ${state.strategy}`))}</div>
      ${topRef ? `<div class="inline-note" style="margin-top:4px;">Referencia ${horizonLabel(topRef.horizonKey || selectedHorizon.key)}: ${escapeHtml(topRef.direction)} · BET ${escapeHtml(topRef.actionSide)} · ${fmtPct(topRef.executableEdgePct)}</div>` : ''}
    </section>
  `;

  const marketCards = visibleOps.map((op) => {
    const marketUrl = buildPolymarketUrl(city.city, selectedHorizon);
    const signalCls = signalClass(op.signal);
    const rookie = state.mode === 'rookie';
    const title = rookie ? rookieTagline(op) : `${op.direction} · ${op.actionSide} · ${op.signal}`;
    const titleClass = op.shouldTrade ? signalCls : 'red';
    return `
      <article class="market-card">
        <div class="market-top">
          <p class="market-q">${escapeHtml(op.question)}</p>
          <span class="market-pill ${titleClass}">${escapeHtml(title)}</span>
        </div>
        ${rookie ? `<div class="rookie-help">${escapeHtml(rookieHelp(op))}</div>` : ''}
        <div class="market-line">
          <div class="metric-chip"><div class="k">Exec</div><div class="v ${op.executableEdgePct >= 0 ? 'good' : 'bad'}">${fmtPct(op.executableEdgePct)}</div></div>
          <div class="metric-chip"><div class="k">Fill</div><div class="v ${op.fillProbabilityPct >= 55 ? 'good' : 'bad'}">${fmtPct(op.fillProbabilityPct)}</div></div>
          <div class="metric-chip"><div class="k">Coste</div><div class="v ${op.totalCostPct <= 22 ? 'good' : 'bad'}">${fmtPct(op.totalCostPct)}</div></div>
          <div class="metric-chip"><div class="k">Spread</div><div class="v ${op.spreadPct <= 12 ? 'good' : 'bad'}">${fmtPct(op.spreadPct)}</div></div>
          <div class="metric-chip"><div class="k">Liquidez libro</div><div class="v">${fmtNum(op.liquidityBook)}</div></div>
          <div class="metric-chip"><div class="k">Volumen bucket</div><div class="v">${fmtNum(op.volumeBucket)}</div></div>
          ${rookie ? '' : `<div class="metric-chip"><div class="k">Modelo YES</div><div class="v">${fmtPct(op.modelProbabilityYesPct)}</div></div>
          <div class="metric-chip"><div class="k">Mkt YES</div><div class="v">${fmtPct(op.marketProbabilityYesPct)}</div></div>
          <div class="metric-chip"><div class="k">Comprar Sí / No</div><div class="v">${op.yesAskCents.toFixed(1)}c / ${op.noAskCents.toFixed(1)}c</div></div>`}
        </div>
        <div class="market-links">
          <a class="market-link" href="${escapeHtml(marketUrl)}" target="_blank" rel="noopener">Abrir mercado en Polymarket</a>
          <button class="market-link js-paper-buy" data-op-id="${escapeHtml(op.id)}" data-horizon-key="${escapeHtml(selectedHorizon.key)}" title="Paper trading local en navegador">Simular BUY ${escapeHtml(op.actionSide)}</button>
        </div>
        ${op.reasonSimple ? `<div class="inline-note" style="margin-top:6px;">${escapeHtml(op.reasonSimple)}</div>` : ''}
      </article>
    `;
  }).join('');

  const tracesHtml = `
    <section class="detail-card">
      <div class="detail-topline">
        <div>
          <div class="panel-sub">Trazabilidad operativa (${selectedHorizon.label})</div>
          <div class="inline-note">Resumen auditable de por qué se mantienen/descartan mercados.</div>
        </div>
      </div>
      <div class="trace-list">
        ${selectedHorizon.decisionTrace.map((trace) => `
          <div class="trace-item">
            <div class="trace-head">
              <span class="trace-status ${trace.status === 'KEPT' ? 'kept' : 'discarded'}">${trace.status === 'KEPT' ? 'MANTENIDO' : 'DESCARTADO'}</span>
              <span class="trace-stage">${escapeHtml(trace.stage)}</span>
            </div>
            <div class="trace-reason">${escapeHtml(trace.reason)}</div>
            ${trace.details?.length ? `<div class="trace-details">${trace.details.map(escapeHtml).join(' · ')}</div>` : ''}
          </div>
        `).join('')}
      </div>
    </section>
  `;

  const realMarketRows = (realBoardHorizon?.markets || []).map((m) => `
    <article class="market-card">
      <div class="market-top">
        <p class="market-q">${escapeHtml(m.question)}</p>
        <span class="market-pill ${m.acceptingOrders && !m.closed ? 'green' : 'red'}">${m.acceptingOrders && !m.closed ? 'ABIERTO' : 'NO OPERABLE'}</span>
      </div>
      <div class="market-line">
        <div class="metric-chip"><div class="k">Bucket</div><div class="v">${escapeHtml(m.bucketLabel)}</div></div>
        <div class="metric-chip"><div class="k">Mkt YES</div><div class="v">${fmtPct(m.marketProbabilityYesPct)}</div></div>
        <div class="metric-chip"><div class="k">Comprar Sí / No</div><div class="v">${m.yesAskCents?.toFixed?.(1) ?? '--'}c / ${m.noAskCents?.toFixed?.(1) ?? '--'}c</div></div>
        <div class="metric-chip"><div class="k">Spread</div><div class="v ${m.spreadPct <= 12 ? 'good' : 'bad'}">${fmtPct(m.spreadPct)}</div></div>
        <div class="metric-chip"><div class="k">Liquidez libro</div><div class="v">${fmtNum(m.liquidityBook)}</div></div>
        <div class="metric-chip"><div class="k">Volumen bucket</div><div class="v">${fmtNum(m.volumeBucket)}</div></div>
      </div>
    </article>
  `).join('');

  const realBoardHtml = `
    <section class="detail-card">
      <div class="detail-topline">
        <div>
          <div class="panel-sub">Mercado real (lectura) · ${selectedHorizon.label}</div>
          <div class="inline-note">Precios y buckets reales de Polymarket (Gamma API). El panel superior usa el motor web de evaluación (Exec/Fill/Coste) sobre estos mercados.</div>
        </div>
      </div>
      ${
        !city.polymarketReal
          ? `<div class="empty-state" style="min-height:90px;">Sin lectura real de Polymarket en este detalle.</div>`
          : realBoardHorizon?.status !== 'SUCCESS'
            ? `<div class="empty-state" style="min-height:90px;">Polymarket ${selectedHorizon.label}: ${escapeHtml(realBoardHorizon?.error || 'sin datos')}</div>`
            : (realBoardHorizon.markets?.length
              ? `<div class="market-list">${realMarketRows}</div>
                 ${realBoardHorizon.eventUrl ? `<div class="market-links" style="margin-top:8px;"><a class="market-link" href="${escapeHtml(realBoardHorizon.eventUrl)}" target="_blank" rel="noopener">Abrir evento completo en Polymarket</a></div>` : ''}`
              : `<div class="empty-state" style="min-height:90px;">Sin buckets detectados en ${selectedHorizon.label}</div>`)
      }
    </section>
  `;

  const bodyHtml = `
    <div class="detail-stack">
      ${headerHtml}
      ${tabsHtml}
      <section class="detail-card">
        <div class="detail-topline">
          <div>
            <div class="panel-sub">Mercados (${selectedHorizon.label})</div>
            <div class="inline-note">${visibleOps.length ? 'Buckets visibles para revisión (motor web)' : `No hay mercados visibles en este horizonte para estrategia ${state.strategy}.`}</div>
          </div>
        </div>
        ${visibleOps.length ? `<div class="market-list">${marketCards}</div>` : `<div class="empty-state" style="min-height:120px;">Sin oportunidades en ${selectedHorizon.label}</div>`}
      </section>
      ${realBoardHtml}
      ${tracesHtml}
      <section class="detail-card">
        <div class="detail-topline">
          <div>
            <div class="panel-sub">Módulos pendientes en web (fase siguiente)</div>
            <div class="inline-note">Siguientes bloques: Cuenta Polymarket web, CopyTrade avanzado (persistencia multiusuario / alertas push reales) y backtest completo, manteniendo el backend híbrido hasta cerrar la migración total de lógica.</div>
          </div>
        </div>
      </section>
    </div>
  `;

  el.detailBody.innerHTML = bodyHtml;
  el.detailBody.querySelectorAll('.horizon-tab').forEach((btn) => {
    btn.addEventListener('click', () => {
      const key = btn.getAttribute('data-horizon');
      if (!key) return;
      state.selectedHorizonKey = key;
      renderDetail();
    });
  });
  el.detailBody.querySelectorAll('.js-paper-buy').forEach((btn) => {
    btn.addEventListener('click', () => {
      const horizonKey = btn.getAttribute('data-horizon-key');
      const opId = btn.getAttribute('data-op-id');
      const horizon = city.horizons.find((h) => h.key === horizonKey);
      const op = horizon?.opportunities?.find((o) => o.id === opId);
      if (!horizon || !op) return;
      paperBuyFromOpportunity({ city, horizon, op });
    });
  });
}

async function refreshCities() {
  state.loadingCities = true;
  state.citiesLoadMode = 'stream';
  state.apiError = null;
  state.citiesLoadExpected = 0;
  state.citiesLoadCompleted = 0;
  state.cities = [];
  render();
  try {
    const health = await apiGet('/healthz').catch(() => null);
    state.apiHealthy = !!health?.ok;
    state.backendHealth = health || null;
    state.backendHealthFetchedAtUtc = new Date().toISOString();
    if (health?.ok) {
      state.apiError = null;
    }
    await refreshCitiesIncremental();
    if (state.selectedCityId) {
      await loadCityDetail(state.selectedCityId);
    }
  } catch (error) {
    try {
      state.citiesLoadMode = 'full';
      await refreshCitiesFullFallback();
    } catch (fallbackError) {
      state.apiHealthy = false;
      state.apiError = String(fallbackError.message || fallbackError);
    }
  } finally {
    state.loadingCities = false;
    render();
  }
}

function ensureSelectedCityFromLoaded() {
  const loadedCities = state.cities.filter(isLoadedCity);
  if (!loadedCities.length) return;
  const fromHash = parseHashCityId();
  if (!state.selectedCityId) {
    state.selectedCityId = loadedCities.some((c) => c.id === fromHash) ? fromHash : loadedCities[0].id;
    return;
  }
  const existsLoaded = loadedCities.some((c) => c.id === state.selectedCityId);
  if (!existsLoaded && fromHash && loadedCities.some((c) => c.id === fromHash)) {
    state.selectedCityId = fromHash;
  }
}

async function refreshCitiesFullFallback() {
  const payload = await apiGet('/api/v1/cities');
  state.apiHealthy = true;
  state.meta = payload.meta;
  state.cities = payload.cities;
  state.citiesLoadExpected = payload.cities.length;
  state.citiesLoadCompleted = payload.cities.length;
  ensureSelectedCityFromLoaded();
  render();
}

async function refreshCitiesIncremental() {
  if (typeof EventSource === 'undefined') {
    throw new Error('EventSource no disponible en este navegador');
  }
  await new Promise((resolve, reject) => {
    const es = new EventSource(`${API_BASE}/api/v1/cities/stream?paceMs=60`);
    let settled = false;
    let gotManifest = false;

    const safeResolve = () => {
      if (settled) return;
      settled = true;
      es.close();
      resolve();
    };
    const safeReject = (error) => {
      if (settled) return;
      settled = true;
      es.close();
      reject(error);
    };

    es.addEventListener('meta', (evt) => {
      state.meta = JSON.parse(evt.data);
      render();
    });

    es.addEventListener('manifest', (evt) => {
      const manifest = JSON.parse(evt.data);
      gotManifest = true;
      state.citiesLoadExpected = manifest.length;
      state.citiesLoadCompleted = 0;
      state.cities = manifest.map((item) => ({
        id: item.id,
        name: item.name,
        city: { id: item.id, name: item.name },
        _placeholder: true
      }));
      const fromHash = parseHashCityId();
      if (!state.selectedCityId && fromHash && manifest.some((m) => m.id === fromHash)) {
        state.selectedCityId = fromHash;
      }
      render();
    });

    es.addEventListener('reset', (evt) => {
      const data = JSON.parse(evt.data);
      if (Number.isFinite(data.total) && data.total > 0) {
        state.citiesLoadExpected = data.total;
      }
      render();
    });

    es.addEventListener('city', (evt) => {
      const data = JSON.parse(evt.data);
      const incoming = data.city;
      const idx = state.cities.findIndex((c) => c.id === incoming.id);
      if (idx >= 0) {
        state.cities[idx] = incoming;
      } else {
        state.cities.push(incoming);
      }
      state.citiesLoadCompleted = state.cities.filter(isLoadedCity).length;
      ensureSelectedCityFromLoaded();
      render();
    });

    es.addEventListener('done', () => {
      state.apiHealthy = true;
      ensureSelectedCityFromLoaded();
      render();
      safeResolve();
    });

    es.addEventListener('error', (evt) => {
      if (gotManifest && state.citiesLoadCompleted > 0) {
        state.apiHealthy = true;
        safeResolve();
        return;
      }
      safeReject(new Error('Fallo en stream de ciudades'));
    });
  });
}

async function loadCityDetail(cityId, { force = false } = {}) {
  if (!cityId) return;
  if (!force && state.cityDetails.has(cityId)) return;
  state.loadingDetail = true;
  render();
  try {
    const payload = await apiGet(`/api/v1/cities/${cityId}`);
    state.cityDetails.set(cityId, payload.city);
    state.apiHealthy = true;
    state.apiError = null;
  } catch (error) {
    state.apiHealthy = false;
    state.apiError = String(error.message || error);
  } finally {
    state.loadingDetail = false;
    render();
  }
}

async function analyzeIntelUser({ force = false } = {}) {
  const query = String(el.intelUsernameInput?.value || state.intelQuery || '').trim();
  if (!query) return;
  if (!force && state.intelSnapshot && normalizeIntelQuery(state.intelSnapshot.queryUsername) === normalizeIntelQuery(query)) {
    return;
  }
  state.intelQuery = query;
  state.intelLoading = true;
  state.intelError = null;
  render();
  try {
    const payload = await apiGet(`/api/v1/user-intel?username=${encodeURIComponent(query)}&limit=50`);
    state.intelSnapshot = payload.intel || null;
    state.intelError = null;
    state.apiHealthy = true;
    if (state.copyTrade.enabled) {
      startCopyTradeStream({ manualRestart: true });
    }
    if (state.copyTrade.config.webPushEnabled) {
      syncWebPushSubscription().catch(() => {});
    }
  } catch (error) {
    state.intelError = String(error.message || error);
  } finally {
    state.intelLoading = false;
    render();
  }
}

function normalizeIntelQuery(value) {
  return String(value || '').trim().replace(/^@/, '').toLowerCase();
}

async function selectCity(cityId, { pushHash = false } = {}) {
  if (!cityId) return;
  state.selectedCityId = cityId;
  state.selectedHorizonKey = 'today';
  if (pushHash) {
    history.replaceState(null, '', `#city/${cityId}`);
  }
  render();
  await loadCityDetail(cityId);
}

function parseHashCityId() {
  const m = window.location.hash.match(/^#city\/([a-z0-9-]+)$/);
  return m ? m[1] : null;
}

function bindEvents() {
  el.refreshBtn.addEventListener('click', () => refreshCities());
  el.refreshDetailBtn.addEventListener('click', async () => {
    if (!state.selectedCityId) return;
    await loadCityDetail(state.selectedCityId, { force: true });
  });
  el.paperClearBtn?.addEventListener('click', () => clearPaperStore());
  el.accountAnalyzeBtn?.addEventListener('click', () => loadPolymarketAccount({ force: true }));
  el.accountRefreshBtn?.addEventListener('click', () => loadPolymarketAccount({ force: true }));
  el.accountWalletInput?.addEventListener('keydown', (evt) => {
    if (evt.key === 'Enter') {
      evt.preventDefault();
      loadPolymarketAccount({ force: true });
    }
  });
  el.accountWalletInput?.addEventListener('change', () => {
    state.account.wallet = normalizeWalletInput(el.accountWalletInput.value);
    saveAccountStore();
    render();
  });
  el.intelAnalyzeBtn?.addEventListener('click', () => analyzeIntelUser({ force: true }));
  el.intelRefreshBtn?.addEventListener('click', () => analyzeIntelUser({ force: true }));
  el.intelUsernameInput?.addEventListener('keydown', (evt) => {
    if (evt.key === 'Enter') {
      evt.preventDefault();
      analyzeIntelUser({ force: true });
    }
  });
  const copytradeInputsToPersist = [
    el.copytradePollSec,
    el.copytradeBuyMin,
    el.copytradeSellMin,
    el.copytradeAlertBuys,
    el.copytradeAlertSells,
    el.copytradeAlertOthers,
    el.copytradeOnlyWeather,
    el.copytradeOnlyTracked,
    el.copytradeBrowserNotify,
    el.copytradeWebPushEnabled
  ].filter(Boolean);
  copytradeInputsToPersist.forEach((node) => {
    node.addEventListener('change', () => {
      readCopyTradeControlsToState();
      renderCopyTradePanel();
    });
  });
  el.copytradeEnabled?.addEventListener('change', () => {
    readCopyTradeControlsToState();
    if (state.copyTrade.enabled) {
      startCopyTradeStream({ manualRestart: true });
    } else {
      stopCopyTradeStream({ userDisabled: true });
      render();
    }
  });
  el.copytradeApplyBtn?.addEventListener('click', () => {
    readCopyTradeControlsToState();
    const tasks = [];
    if (state.copyTrade.enabled) tasks.push(Promise.resolve(startCopyTradeStream({ manualRestart: true })));
    if (state.copyTrade.config.webPushEnabled) tasks.push(syncWebPushSubscription());
    if (!tasks.length) render();
  });
  el.copytradeClearAlertsBtn?.addEventListener('click', () => clearCopyTradeAlerts());
  el.copytradeNotifyPermBtn?.addEventListener('click', () => requestBrowserNotificationPermission());
  el.copytradeWebPushSyncBtn?.addEventListener('click', () => syncWebPushSubscription());
  el.copytradeWebPushTestBtn?.addEventListener('click', () => sendWebPushTest());
  el.copytradeWebPushOffBtn?.addEventListener('click', () => disableWebPushSubscription());
  el.copytradeWebPushAdminRefreshBtn?.addEventListener('click', () => refreshWebPushAdmin());

  el.modeSwitch.querySelectorAll('button').forEach((btn) => {
    btn.addEventListener('click', () => {
      state.mode = btn.dataset.mode;
      render();
    });
  });

  el.strategySwitch.querySelectorAll('button').forEach((btn) => {
    btn.addEventListener('click', () => {
      state.strategy = btn.dataset.strategy;
      render();
    });
  });

  window.addEventListener('hashchange', async () => {
    const cityId = parseHashCityId();
    if (cityId && cityId !== state.selectedCityId) {
      await selectCity(cityId, { pushHash: false });
    }
  });
  window.addEventListener('beforeunload', () => stopCopyTradeStream());
}

async function init() {
  state.paper = loadPaperStore();
  const accountStore = loadAccountStore();
  state.account.wallet = normalizeWalletInput(accountStore.wallet);
  const ctStore = loadCopyTradeStore();
  state.copyTrade.enabled = ctStore.enabled;
  state.copyTrade.config = ctStore.config;
  refreshNotificationCapabilityState();
  refreshWebPushCapabilityState();
  if (el.intelUsernameInput) el.intelUsernameInput.value = state.intelQuery;
  if (el.accountWalletInput) el.accountWalletInput.value = state.account.wallet || '';
  syncCopyTradeControlsFromState();
  bindEvents();
  render();
  getServerWebPushConfig().catch((error) => {
    state.copyTrade.webPush.lastError = String(error?.message || error);
    state.copyTrade.webPush.statusText = 'Web Push backend no disponible';
    render();
  }).then(() => render());
  refreshWebPushAdmin({ silent: true }).catch(() => {});
  await refreshCities();
  await ensureDefaultAccountWalletPrefilled();
  if (state.account.wallet) {
    await loadPolymarketAccount({ force: true }).catch(() => {});
  } else {
    render();
  }
  await analyzeIntelUser({ force: true }).catch(() => {});
  if (state.copyTrade.enabled && !copyTradeEventSource && !state.copyTrade.connecting) {
    startCopyTradeStream({ manualRestart: false });
  }
  if (state.copyTrade.config.webPushEnabled) {
    syncWebPushSubscription().catch(() => {});
  }
}

init();
