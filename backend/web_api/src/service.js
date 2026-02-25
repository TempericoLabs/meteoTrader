import { createMockProvider } from './providers/mockProvider.js';
import { createHybridMetarProvider } from './providers/hybridMetarProvider.js';
import {
  fetchPolymarketAccountSnapshot,
  fetchPolymarketPublicActivityByWallet,
  fetchPolymarketUserIntelSnapshot,
  resolvePolymarketUserProfile
} from './providers/polymarketUserIntel.js';

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

export class PolymeteoApiService {
  constructor({ provider = createProviderFromEnv() } = {}) {
    this.provider = provider;
    this.userIntelCache = new Map();
    this.accountCache = new Map();
  }

  async health() {
    const meta = await this.provider.getMeta();
    const providerHealth = await this.provider.health();
    return {
      ok: !!providerHealth.ok,
      service: 'polymeteo-web-api',
      mode: this.provider.sourceId || 'unknown',
      generatedAtUtc: meta.generatedAtUtc,
      provider: providerHealth
    };
  }

  async getMeta() {
    return this.provider.getMeta();
  }

  async getCitiesPayload() {
    const cities = this.provider.listCitiesSummary
      ? await this.provider.listCitiesSummary()
      : await this._loadCitiesByIds();
    return {
      meta: await this.provider.getMeta(),
      cities
    };
  }

  async getCitiesManifestPayload() {
    return {
      meta: await this.provider.getMeta(),
      manifest: await this.provider.listCityManifest()
    };
  }

  async getCityIdsPayload() {
    return { ids: await this.provider.listCityIds() };
  }

  async getCityDetailPayload(cityId) {
    const city = await this.provider.getCityDetail(cityId);
    if (!city) return null;
    return {
      meta: await this.provider.getMeta(),
      city
    };
  }

  async getUserIntelPayload(username, { limit = 40 } = {}) {
    const key = String(username || '').trim().toLowerCase();
    if (!key) throw new Error('username requerido');
    const ttlMs = 15_000;
    const cached = this.userIntelCache.get(key);
    if (cached && Date.now() - cached.ts <= ttlMs) {
      return { meta: await this.provider.getMeta(), intel: cached.value, cache: { hit: true, ttlMs } };
    }
    const intel = await fetchPolymarketUserIntelSnapshot(username, { limit });
    this.userIntelCache.set(key, { ts: Date.now(), value: intel });
    return { meta: await this.provider.getMeta(), intel, cache: { hit: false, ttlMs } };
  }

  async getPolymarketAccountPayload(walletAddress, { limit = 30 } = {}) {
    const key = String(walletAddress || '').trim().toLowerCase();
    if (!key) throw new Error('wallet requerido');
    const ttlMs = 10_000;
    const cached = this.accountCache.get(key);
    if (cached && Date.now() - cached.ts <= ttlMs) {
      return { meta: await this.provider.getMeta(), account: cached.value, cache: { hit: true, ttlMs } };
    }
    const account = await fetchPolymarketAccountSnapshot(walletAddress, { limit });
    this.accountCache.set(key, { ts: Date.now(), value: account });
    return { meta: await this.provider.getMeta(), account, cache: { hit: false, ttlMs } };
  }

  async streamCopyTrade({
    username,
    signal,
    onEvent,
    pollMs = 3000,
    limit = 20,
    config = {}
  }) {
    const normalizedPollMs = Number.isFinite(pollMs) ? Math.max(2000, Math.min(15000, pollMs)) : 3000;
    const normalizedLimit = Number.isFinite(limit) ? Math.max(5, Math.min(120, limit)) : 20;
    const normalizedConfig = {
      alertBuys: config.alertBuys !== false,
      alertSells: config.alertSells !== false,
      alertOthers: !!config.alertOthers,
      onlyWeather: config.onlyWeather !== false,
      onlyTrackedCities: !!config.onlyTrackedCities,
      buyMinUsdc: Number.isFinite(config.buyMinUsdc) ? Math.max(0, config.buyMinUsdc) : 0,
      sellMinUsdc: Number.isFinite(config.sellMinUsdc) ? Math.max(0, config.sellMinUsdc) : 0
    };

    const resolved = await resolvePolymarketUserProfile(username);
    onEvent('config', {
      queryUsername: resolved.queryUsername,
      profile: resolved.profile,
      warnings: resolved.warnings,
      polling: { pollMs: normalizedPollMs, limit: normalizedLimit },
      filters: normalizedConfig
    });

    let baselineDone = false;
    const seen = new Set();
    let loopCount = 0;

    while (!signal?.aborted) {
      loopCount += 1;
      const startedAt = Date.now();
      const activityResult = await fetchPolymarketPublicActivityByWallet(resolved.profile.proxyWallet, { limit: normalizedLimit });
      const items = Array.isArray(activityResult.items) ? activityResult.items : [];
      const error = activityResult.error || null;
      const nowIso = new Date().toISOString();

      if (error) {
        onEvent('poll', { ok: false, atUtc: nowIso, error, loopCount });
      } else {
        onEvent('poll', { ok: true, atUtc: nowIso, count: items.length, loopCount });
      }

      const ids = items.map(copyTradeEventId).filter(Boolean);
      if (!baselineDone) {
        ids.forEach((id) => seen.add(id));
        baselineDone = true;
        onEvent('baseline', {
          atUtc: nowIso,
          seen: ids.length,
          lastActivityAt: items[0]?.timestamp || null
        });
      } else if (!error) {
        const fresh = [];
        for (const item of items) {
          const id = copyTradeEventId(item);
          if (!id || seen.has(id)) continue;
          seen.add(id);
          fresh.push(item);
        }
        // Keep the set bounded.
        if (seen.size > 5000) {
          const recent = ids.slice(0, 400);
          seen.clear();
          recent.forEach((id) => seen.add(id));
        }
        const alerts = fresh
          .sort((a, b) => Number(a.timestamp || 0) - Number(b.timestamp || 0))
          .map((item) => buildCopyTradeAlert(item, normalizedConfig))
          .filter(Boolean);
        for (const alert of alerts) {
          onEvent('alert', alert);
        }
      }

      const elapsed = Date.now() - startedAt;
      const waitMs = Math.max(250, normalizedPollMs - elapsed);
      if (signal?.aborted) break;
      await sleep(waitMs);
    }
  }

  async streamCities({ onEvent, signal, paceMs = 70 }) {
    const meta = await this.provider.getMeta();
    const manifest = await this.provider.listCityManifest();

    onEvent('meta', meta);
    onEvent('manifest', manifest);
    onEvent('reset', { total: manifest.length });

    for (let index = 0; index < manifest.length; index += 1) {
      if (signal?.aborted) return;
      const manifestItem = manifest[index];
      const city = this.provider.getCitySummary
        ? await this.provider.getCitySummary(manifestItem.id)
        : (await this._loadCitiesByIds()).find((item) => item.id === manifestItem.id);
      if (city) {
        onEvent('city', { index, city });
      }
      if (paceMs > 0) {
        await sleep(paceMs);
      }
    }

    if (!signal?.aborted) {
      onEvent('done', {
        total: manifest.length,
        source: meta.source,
        generatedAtUtc: meta.generatedAtUtc
      });
    }
  }

  async _loadCitiesByIds() {
    const ids = await this.provider.listCityIds();
    const cities = [];
    for (const id of ids) {
      if (this.provider.getCitySummary) {
        const city = await this.provider.getCitySummary(id);
        if (city) cities.push(city);
      } else {
        const detail = await this.provider.getCityDetail(id);
        if (detail) cities.push(detail);
      }
    }
    return cities;
  }
}

function copyTradeEventId(item) {
  const tx = String(item?.transactionHash || '').trim();
  const ts = Number(item?.timestamp || 0);
  const side = String(item?.side || item?.type || '').toUpperCase();
  const outcome = String(item?.outcome || '').toUpperCase();
  const title = String(item?.title || '').trim();
  if (tx) return `${tx}|${ts}|${side}|${outcome}`;
  if (!ts && !title) return null;
  return `${ts}|${side}|${outcome}|${title}`;
}

function normalizeText(value) {
  return String(value || '')
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .toLowerCase();
}

function isWeatherLikeActivity(item) {
  const slug = normalizeText(item?.eventSlug || item?.marketSlug || '');
  const title = normalizeText(item?.title || '');
  return slug.includes('highest-temperature') || (title.includes('temperature') && (title.includes('highest') || title.includes('temperatura mas alta')));
}

function detectTrackedCityId(item) {
  const text = `${normalizeText(item?.eventSlug)} ${normalizeText(item?.marketSlug)} ${normalizeText(item?.title)}`;
  const cityAliases = [
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
  for (const [cityId, aliases] of cityAliases) {
    if (aliases.some((a) => text.includes(a))) return cityId;
  }
  return null;
}

function buildCopyTradeAlert(item, config) {
  const side = String(item?.side || '').toUpperCase();
  const type = String(item?.type || '').toUpperCase();
  const isBuy = side === 'BUY';
  const isSell = side === 'SELL';
  const isOther = !isBuy && !isSell;
  if ((isBuy && !config.alertBuys) || (isSell && !config.alertSells) || (isOther && !config.alertOthers)) {
    return null;
  }
  if (config.onlyWeather && !isWeatherLikeActivity(item)) return null;
  const trackedCityId = detectTrackedCityId(item);
  if (config.onlyTrackedCities && !trackedCityId) return null;

  const usdcSize = Number(item?.usdcSize);
  const size = Number(item?.size);
  const price = Number(item?.price);
  const notional = Number.isFinite(usdcSize) ? usdcSize : (Number.isFinite(size) && Number.isFinite(price) ? size * price : null);
  if (isBuy && Number.isFinite(notional) && notional < config.buyMinUsdc) return null;
  if (isSell && Number.isFinite(notional) && notional < config.sellMinUsdc) return null;

  const id = copyTradeEventId(item);
  return {
    id,
    atUtc: new Date().toISOString(),
    activityTimestamp: Number.isFinite(Number(item?.timestamp)) ? Number(item.timestamp) : null,
    side: side || null,
    type: type || null,
    outcome: item?.outcome || null,
    title: item?.title || '',
    eventSlug: item?.eventSlug || null,
    marketSlug: item?.marketSlug || null,
    price: Number.isFinite(price) ? price : null,
    size: Number.isFinite(size) ? size : null,
    usdcSize: Number.isFinite(notional) ? notional : null,
    trackedCityId,
    weatherLike: isWeatherLikeActivity(item)
  };
}

export function createProviderFromEnv() {
  const raw = (process.env.POLYMETEO_WEB_PROVIDER || 'hybrid-metar').trim().toLowerCase();
  if (raw === 'mock') return createMockProvider();
  if (raw === 'hybrid-metar' || raw === 'hybrid') return createHybridMetarProvider();
  return createMockProvider();
}
