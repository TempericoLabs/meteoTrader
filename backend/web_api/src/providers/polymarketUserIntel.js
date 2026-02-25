const GAMMA_API_BASE = 'https://gamma-api.polymarket.com';
const DATA_API_BASE = 'https://data-api.polymarket.com';
const TIMEOUT_MS = 4500;

const TRACKED_CITIES = [
  ['new-york', ['nyc', 'new york', 'new-york', 'new york city']],
  ['sao-paulo', ['sao paulo', 'sao-paulo']],
  ['buenos-aires', ['buenos aires', 'buenos-aires']],
  ['london', ['london']],
  ['miami', ['miami']],
  ['toronto', ['toronto']],
  ['seattle', ['seattle']],
  ['dallas', ['dallas']],
  ['wellington', ['wellington']],
  ['ankara', ['ankara']],
  ['seoul', ['seoul']],
  ['chicago', ['chicago']],
  ['atlanta', ['atlanta']],
  ['paris', ['paris']]
];

function normalizeText(value) {
  return String(value || '')
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .toLowerCase()
    .trim();
}

function parseNumber(v) {
  if (typeof v === 'number' && Number.isFinite(v)) return v;
  if (typeof v === 'string' && v.trim()) {
    const n = Number(v);
    if (Number.isFinite(n)) return n;
  }
  return null;
}

async function fetchJson(url) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), TIMEOUT_MS);
  try {
    const resp = await fetch(url, {
      signal: controller.signal,
      headers: { accept: 'application/json', 'user-agent': 'polyMeteo-web/0.1' }
    });
    if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
    return { ok: true, json: await resp.json(), url: resp.url || url };
  } catch (error) {
    return { ok: false, error: String(error?.message || error), url };
  } finally {
    clearTimeout(timeout);
  }
}

function pickProfileFromSearch(json, username) {
  const profiles = Array.isArray(json?.profiles) ? json.profiles : [];
  const normalizedUser = normalizeText(username);
  const mapped = profiles
    .map((p) => ({
      username: String(p?.name || '').trim(),
      pseudonym: typeof p?.pseudonym === 'string' ? p.pseudonym.trim() : null,
      bio: typeof p?.bio === 'string' ? p.bio : null,
      proxyWallet: String(p?.proxyWallet || '').trim().toLowerCase(),
      profileImageUrl: p?.profileImageOptimized || p?.profileImage || null,
      displayUsernamePublic: !!p?.displayUsernamePublic
    }))
    .filter((p) => p.proxyWallet);
  if (!mapped.length) return { profile: null, warnings: [] };
  const exactUsername = mapped.find((p) => normalizeText(p.username) === normalizedUser);
  const exactPseudo = mapped.find((p) => normalizeText(p.pseudonym) === normalizedUser);
  const fuzzy = mapped.find((p) => normalizeText(p.username).includes(normalizedUser) || normalizeText(p.pseudonym).includes(normalizedUser));
  const selected = exactUsername || exactPseudo || fuzzy || mapped[0];
  const warnings = mapped.length > 1 ? [`Coincidencias múltiples (${mapped.length}); se seleccionó ${selected.username || selected.proxyWallet}.`] : [];
  return { profile: selected, warnings };
}

function isWeatherLike(item) {
  const slug = normalizeText(item?.eventSlug || item?.marketSlug || '');
  const title = normalizeText(item?.title || '');
  return slug.includes('highest-temperature') || (title.includes('temperature') && (title.includes('highest') || title.includes('temperatura mas alta')));
}

function detectTrackedCity(item) {
  const text = `${normalizeText(item?.eventSlug)} ${normalizeText(item?.marketSlug)} ${normalizeText(item?.title)}`;
  for (const [cityId, aliases] of TRACKED_CITIES) {
    if (aliases.some((a) => text.includes(normalizeText(a)))) return cityId;
  }
  return null;
}

function buildInsights({ trades, activity }) {
  const buyCount = trades.filter((t) => normalizeText(t.side) === 'buy').length;
  const sellCount = trades.filter((t) => normalizeText(t.side) === 'sell').length;
  const yesCount = trades.filter((t) => normalizeText(t.outcome) === 'yes').length;
  const noCount = trades.filter((t) => normalizeText(t.outcome) === 'no').length;
  const tradeNotional = trades
    .map((t) => parseNumber(t.usdcSize) ?? ((parseNumber(t.size) || 0) * (parseNumber(t.price) || 0)))
    .filter((n) => Number.isFinite(n) && n > 0);
  const totalTradeUsdc = tradeNotional.reduce((a, b) => a + b, 0);
  const sorted = tradeNotional.slice().sort((a, b) => a - b);
  const medianTradeUsdc = sorted.length
    ? (sorted.length % 2 ? sorted[(sorted.length - 1) / 2] : (sorted[sorted.length / 2 - 1] + sorted[sorted.length / 2]) / 2)
    : null;
  const avgTradeUsdc = tradeNotional.length ? totalTradeUsdc / tradeNotional.length : null;
  const largestTradeUsdc = sorted.length ? sorted[sorted.length - 1] : null;
  const weatherTrades = trades.filter(isWeatherLike);
  const weatherTradeRatio = trades.length ? (weatherTrades.length / trades.length) : null;
  const topEvents = Object.entries(
    trades.reduce((acc, t) => {
      const key = t.eventSlug || t.marketSlug || t.title || 'unknown';
      const row = acc[key] || { eventSlug: t.eventSlug || null, title: t.title || key, tradesCount: 0, totalUsdc: 0 };
      row.tradesCount += 1;
      row.totalUsdc += parseNumber(t.usdcSize) ?? ((parseNumber(t.size) || 0) * (parseNumber(t.price) || 0));
      acc[key] = row;
      return acc;
    }, {})
  )
    .map(([, v]) => v)
    .sort((a, b) => b.tradesCount - a.tradesCount || b.totalUsdc - a.totalUsdc)
    .slice(0, 6);
  const topTrackedCities = Object.entries(
    trades.reduce((acc, t) => {
      const cityId = detectTrackedCity(t);
      if (!cityId) return acc;
      const row = acc[cityId] || { cityId, count: 0, totalUsdc: 0 };
      row.count += 1;
      row.totalUsdc += parseNumber(t.usdcSize) ?? ((parseNumber(t.size) || 0) * (parseNumber(t.price) || 0));
      acc[cityId] = row;
      return acc;
    }, {})
  )
    .map(([, v]) => v)
    .sort((a, b) => b.count - a.count || b.totalUsdc - a.totalUsdc)
    .slice(0, 6);
  const trackedTradesCount = topTrackedCities.reduce((a, b) => a + b.count, 0);
  const trackedCitiesRatio = trades.length ? trackedTradesCount / trades.length : null;
  const styleNotes = [];
  if ((weatherTradeRatio || 0) >= 0.7) styleNotes.push('Fuerte foco en mercados meteorológicos.');
  if ((trackedCitiesRatio || 0) >= 0.5) styleNotes.push('Opera principalmente en ciudades del radar PolyMeteo.');
  if (buyCount > sellCount * 2 && trades.length >= 6) styleNotes.push('Predominio de compras (acumulación).');
  if (sellCount > 0 && buyCount > 0) styleNotes.push('Hace gestión activa de posiciones (compra y venta).');
  if ((avgTradeUsdc || 0) < 10 && trades.length >= 5) styleNotes.push('Ticket medio pequeño (riesgo por operación bajo).');
  if ((avgTradeUsdc || 0) > 100 && trades.length >= 5) styleNotes.push('Ticket medio alto (convicción/tamaño agresivo).');

  const lastActivityAt = [...activity, ...trades]
    .map((x) => Number(x.timestamp))
    .filter(Number.isFinite)
    .sort((a, b) => b - a)[0] || null;

  return {
    tradeCount: trades.length,
    activityCount: activity.length,
    buyCount,
    sellCount,
    yesCount,
    noCount,
    totalTradeUsdc,
    avgTradeUsdc,
    medianTradeUsdc,
    largestTradeUsdc,
    weatherTradeRatio,
    trackedCitiesRatio,
    topTrackedCities,
    topEvents,
    lastActivityAt,
    styleNotes
  };
}

async function resolveProfile(username) {
  const q = encodeURIComponent(username);
  const url = `${GAMMA_API_BASE}/public-search?q=${q}&search_profiles=true&search_tags=false&limit_per_type=10`;
  const result = await fetchJson(url);
  if (!result.ok) {
    return { profile: null, warnings: [`Public search API: ${result.error}`] };
  }
  return pickProfileFromSearch(result.json, username);
}

export async function resolvePolymarketUserProfile(usernameInput) {
  const normalized = String(usernameInput || '').trim().replace(/^@/, '');
  if (!normalized) throw new Error('Introduce un nombre de usuario de Polymarket');
  const profileRes = await resolveProfile(normalized);
  if (!profileRes.profile) {
    throw new Error(`No se encontró el usuario '${normalized}' en Polymarket`);
  }
  return {
    queryUsername: normalized,
    profile: profileRes.profile,
    warnings: profileRes.warnings || []
  };
}

async function fetchActivity(wallet, limit = 40) {
  const url = `${DATA_API_BASE}/activity?user=${encodeURIComponent(wallet)}&limit=${limit}&offset=0`;
  const result = await fetchJson(url);
  if (!result.ok) return { items: [], error: `Activity API: ${result.error}` };
  const arr = Array.isArray(result.json) ? result.json : [];
  const items = arr.map((x) => ({
    type: x?.type || null,
    side: x?.side || null,
    outcome: x?.outcome || null,
    title: x?.title || '',
    eventSlug: x?.eventSlug || null,
    marketSlug: x?.slug || null,
    size: parseNumber(x?.size),
    usdcSize: parseNumber(x?.usdcSize),
    price: parseNumber(x?.price),
    timestamp: parseNumber(x?.timestamp),
    transactionHash: x?.transactionHash || null
  }));
  return { items, error: null };
}

export async function fetchPolymarketPublicActivityByWallet(walletAddress, { limit = 20 } = {}) {
  const wallet = String(walletAddress || '').trim().toLowerCase();
  if (!wallet) throw new Error('wallet requerido');
  const result = await fetchActivity(wallet, Math.max(5, Math.min(120, limit)));
  return result;
}

async function fetchTrades(wallet, limit = 40) {
  const url = `${DATA_API_BASE}/trades?user=${encodeURIComponent(wallet)}&limit=${limit}&offset=0`;
  const result = await fetchJson(url);
  if (!result.ok) return { items: [], error: `Trades API: ${result.error}` };
  const arr = Array.isArray(result.json) ? result.json : [];
  const items = arr.map((x) => ({
    side: x?.side || null,
    outcome: x?.outcome || null,
    title: x?.title || '',
    eventSlug: x?.eventSlug || null,
    marketSlug: x?.slug || null,
    size: parseNumber(x?.size),
    usdcSize: parseNumber(x?.usdcSize),
    price: parseNumber(x?.price),
    timestamp: parseNumber(x?.timestamp),
    transactionHash: x?.transactionHash || null
  }));
  return { items, error: null };
}

async function fetchOpenPositions(wallet, limit = 30) {
  const url = `${DATA_API_BASE}/positions?user=${encodeURIComponent(wallet)}&sizeThreshold=0.1&limit=${limit}&offset=0`;
  const result = await fetchJson(url);
  if (!result.ok) return { items: [], error: `Positions API: ${result.error}` };
  const arr = Array.isArray(result.json) ? result.json : [];
  const items = arr.map((x) => ({
    title: x?.title || '',
    outcome: x?.outcome || null,
    size: parseNumber(x?.size),
    avgPrice: parseNumber(x?.avgPrice),
    curPrice: parseNumber(x?.curPrice),
    initialValueUsd: parseNumber(x?.initialValue),
    currentValueUsd: parseNumber(x?.currentValue),
    cashPnlUsd: parseNumber(x?.cashPnl),
    percentPnl: parseNumber(x?.percentPnl),
    redeemable: !!x?.redeemable,
    endDate: x?.endDate || null,
    eventSlug: x?.eventSlug || null,
    marketSlug: x?.slug || null
  }));
  return { items, error: null };
}

async function fetchClosedPositions(wallet, limit = 30) {
  const url = `${DATA_API_BASE}/closed-positions?user=${encodeURIComponent(wallet)}&limit=${limit}&offset=0`;
  const result = await fetchJson(url);
  if (!result.ok) return { items: [], error: `Closed positions API: ${result.error}` };
  const arr = Array.isArray(result.json) ? result.json : [];
  const items = arr.map((x) => ({
    title: x?.title || '',
    outcome: x?.outcome || null,
    avgPrice: parseNumber(x?.avgPrice),
    totalBoughtUsd: parseNumber(x?.totalBought),
    realizedPnlUsd: parseNumber(x?.realizedPnl),
    curPrice: parseNumber(x?.curPrice),
    timestamp: parseNumber(x?.timestamp),
    endDate: x?.endDate || null,
    eventSlug: x?.eventSlug || null,
    marketSlug: x?.slug || null
  }));
  return { items, error: null };
}

async function fetchValue(wallet) {
  const url = `${DATA_API_BASE}/value?user=${encodeURIComponent(wallet)}`;
  const result = await fetchJson(url);
  if (!result.ok) return { valueUsdc: null, error: `Value API: ${result.error}` };
  const root = result.json;
  const value = parseNumber(root?.totalValue ?? root?.value ?? root?.portfolioValue ?? root);
  return { valueUsdc: value, error: null };
}

export async function fetchPolymarketAccountSnapshot(walletAddress, { limit = 30 } = {}) {
  const wallet = String(walletAddress || '').trim().toLowerCase();
  if (!wallet) throw new Error('wallet requerido');
  const normalizedLimit = Number.isFinite(limit) ? Math.max(10, Math.min(120, limit)) : 30;

  const [valueRes, openRes, closedRes, tradesRes] = await Promise.all([
    fetchValue(wallet),
    fetchOpenPositions(wallet, normalizedLimit),
    fetchClosedPositions(wallet, normalizedLimit),
    fetchTrades(wallet, normalizedLimit)
  ]);

  const warnings = [];
  if (valueRes.error) warnings.push(valueRes.error);
  if (openRes.error) warnings.push(openRes.error);
  if (closedRes.error) warnings.push(closedRes.error);
  if (tradesRes.error) warnings.push(tradesRes.error);

  const openInitialValueUsd = openRes.items.reduce((sum, x) => sum + (parseNumber(x.initialValueUsd) || 0), 0);
  const openCurrentValueUsd = openRes.items.reduce((sum, x) => sum + (parseNumber(x.currentValueUsd) || 0), 0);
  const closedRealizedPnlUsd = closedRes.items.reduce((sum, x) => sum + (parseNumber(x.realizedPnlUsd) || 0), 0);

  return {
    walletAddress: wallet,
    fetchedAtUtc: new Date().toISOString(),
    summary: {
      totalValueUsd: valueRes.valueUsdc,
      openPositionsCount: openRes.items.length,
      closedPositionsCount: closedRes.items.length,
      recentTradesCount: tradesRes.items.length,
      openInitialValueUsd,
      openCurrentValueUsd,
      openUnrealizedPnlUsd: openCurrentValueUsd - openInitialValueUsd,
      closedRealizedPnlUsd
    },
    openPositions: openRes.items,
    closedPositions: closedRes.items,
    recentTrades: tradesRes.items,
    warnings: Array.from(new Set(warnings))
  };
}

export async function fetchPolymarketUserIntelSnapshot(usernameInput, { limit = 40 } = {}) {
  const normalized = String(usernameInput || '').trim().replace(/^@/, '');
  if (!normalized) {
    throw new Error('Introduce un nombre de usuario de Polymarket');
  }

  const profileRes = await resolveProfile(normalized);
  const warnings = [...(profileRes.warnings || [])];
  if (!profileRes.profile) {
    throw new Error(`No se encontró el usuario '${normalized}' en Polymarket`);
  }
  const profile = profileRes.profile;

  const [activityRes, tradesRes, valueRes] = await Promise.all([
    fetchActivity(profile.proxyWallet, Math.max(10, Math.min(120, limit))),
    fetchTrades(profile.proxyWallet, Math.max(10, Math.min(120, limit))),
    fetchValue(profile.proxyWallet)
  ]);

  if (activityRes.error) warnings.push(activityRes.error);
  if (tradesRes.error) warnings.push(tradesRes.error);
  if (valueRes.error) warnings.push(valueRes.error);

  const snapshot = {
    queryUsername: normalized,
    fetchedAtUtc: new Date().toISOString(),
    profile,
    account: {
      proxyWallet: profile.proxyWallet,
      portfolioValueUsdc: valueRes.valueUsdc,
      recentTradesCount: tradesRes.items.length
    },
    activity: activityRes.items,
    trades: tradesRes.items,
    insights: buildInsights({ trades: tradesRes.items, activity: activityRes.items }),
    warnings: Array.from(new Set(warnings))
  };
  return snapshot;
}
