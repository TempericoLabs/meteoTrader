const GAMMA_BASE = 'https://gamma-api.polymarket.com';
const POLY_TIMEOUT_MS = 4500;

const CITY_OVERRIDES = {
  'new-york': {
    slugAliases: ['nyc', 'new-york', 'new-york-city']
  }
};

function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms));
}

function normalizePhrase(raw) {
  return String(raw || '')
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .toLowerCase()
    .replace(/[^a-z0-9\s-]/g, ' ')
    .replace(/-/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

function slugify(raw) {
  return normalizePhrase(raw).replace(/\s+/g, '-');
}

function monthSlug(dateIso) {
  const [y, m, d] = dateIso.split('-').map(Number);
  const dt = new Date(Date.UTC(y, m - 1, d));
  return dt.toLocaleString('en-US', { month: 'long', timeZone: 'UTC' }).toLowerCase();
}

function buildEventSlugCandidates(city, targetDate) {
  const id = city.id;
  const overrides = CITY_OVERRIDES[id] || { slugAliases: [] };
  const aliases = new Set([
    slugify(city.name),
    slugify(id.replace(/-/g, ' ')),
    ...overrides.slugAliases.map(slugify)
  ]);
  const [year, , day] = targetDate.split('-').map(Number);
  const mon = monthSlug(targetDate);
  return Array.from(aliases).map((alias) => `highest-temperature-in-${alias}-on-${mon}-${day}-${year}`);
}

function isHighestTemperatureQuestion(question) {
  const q = normalizePhrase(question);
  return q.includes('highest temperature') || q.includes('highest temp') || q.includes('temperatura mas alta') || q.includes('temperatura más alta');
}

function questionMatchesCity(question, city) {
  const q = normalizePhrase(question);
  const candidates = new Set([
    normalizePhrase(city.name),
    normalizePhrase(city.id.replace(/-/g, ' '))
  ]);
  if (city.id === 'new-york') {
    ['nyc', 'new york', 'new york city'].forEach((v) => candidates.add(normalizePhrase(v)));
  }
  for (const c of candidates) {
    if (c && q.includes(c)) return true;
  }
  return false;
}

function parseJsonArrayString(raw) {
  if (typeof raw !== 'string' || !raw.trim()) return [];
  try {
    const arr = JSON.parse(raw);
    return Array.isArray(arr) ? arr : [];
  } catch {
    return [];
  }
}

function parseNumber(v) {
  if (typeof v === 'number' && Number.isFinite(v)) return v;
  if (typeof v === 'string' && v.trim()) {
    const n = Number(v);
    if (Number.isFinite(n)) return n;
  }
  return null;
}

function parseTargetDateFromQuestion(question) {
  const m = question.match(/on\s+([A-Za-z]+\s+\d{1,2}(?:st|nd|rd|th)?(?:,\s*\d{4})?)/i);
  if (!m) return null;
  const raw = m[1]
    .replace(/(\d+)(st|nd|rd|th)/gi, '$1')
    .replace(/,/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
  const withYear = /\b\d{4}\b/.test(raw) ? raw : `${raw} ${new Date().getUTCFullYear()}`;
  const dt = new Date(`${withYear} UTC`);
  if (!Number.isFinite(dt.getTime())) return null;
  return dt.toISOString().slice(0, 10);
}

function parseConditionFromQuestion(question, fallbackTargetDate) {
  const between = question.match(/be\s*between\s*(-?\d+(?:\.\d+)?)\s*[-–]\s*(-?\d+(?:\.\d+)?)\s*[°º]?\s*([CF])\b/i);
  if (between) {
    const a = Number(between[1]);
    const b = Number(between[2]);
    const lower = Math.min(a, b);
    const upper = Math.max(a, b);
    const unit = between[3].toUpperCase();
    return {
      type: 'BETWEEN',
      unit,
      threshold: lower,
      upperThreshold: upper,
      targetDate: parseTargetDateFromQuestion(question) || fallbackTargetDate,
      bucketLabel: `${lower}-${upper}°${unit}`
    };
  }

  const ge = question.match(/be\s*(-?\d+(?:\.\d+)?)\s*[°º]?\s*([CF])\s*or\s*higher\b/i);
  if (ge) {
    const t = Number(ge[1]);
    const unit = ge[2].toUpperCase();
    return {
      type: 'GREATER_OR_EQUAL', unit, threshold: t, upperThreshold: null,
      targetDate: parseTargetDateFromQuestion(question) || fallbackTargetDate,
      bucketLabel: `${t}°${unit}+`
    };
  }

  const le = question.match(/be\s*(-?\d+(?:\.\d+)?)\s*[°º]?\s*([CF])\s*or\s*below\b/i);
  if (le) {
    const t = Number(le[1]);
    const unit = le[2].toUpperCase();
    return {
      type: 'LESS_OR_EQUAL', unit, threshold: t, upperThreshold: null,
      targetDate: parseTargetDateFromQuestion(question) || fallbackTargetDate,
      bucketLabel: `${t}°${unit}-`
    };
  }

  const exact = question.match(/be\s*(-?\d+(?:\.\d+)?)\s*[°º]?\s*([CF])\s+on\b/i);
  if (exact) {
    const t = Number(exact[1]);
    const unit = exact[2].toUpperCase();
    return {
      type: 'EXACT', unit, threshold: t, upperThreshold: null,
      targetDate: parseTargetDateFromQuestion(question) || fallbackTargetDate,
      bucketLabel: `${t}°${unit}`
    };
  }

  return null;
}

function inferSpread(obj, yesPrice, noPrice) {
  const raw = parseNumber(obj?.spread);
  if (raw != null) return raw;
  const bestBid = parseNumber(obj?.bestBid);
  const bestAsk = parseNumber(obj?.bestAsk);
  if (bestBid != null && bestAsk != null && bestAsk >= bestBid) return bestAsk - bestBid;
  if (yesPrice != null && noPrice != null) {
    const implied = yesPrice + noPrice - 1;
    if (Number.isFinite(implied) && implied >= 0) return implied;
  }
  return null;
}

function parseMarketObject(obj, city, fallbackTargetDate) {
  const question = typeof obj?.question === 'string' ? obj.question : null;
  if (!question) return null;
  if (!isHighestTemperatureQuestion(question)) return null;
  if (!questionMatchesCity(question, city)) return null;

  const condition = parseConditionFromQuestion(question, fallbackTargetDate);
  if (!condition) return null;

  const outcomes = parseJsonArrayString(obj?.outcomes);
  const prices = parseJsonArrayString(obj?.outcomePrices).map(parseNumber).filter((n) => n != null);
  const yesIdx = outcomes.findIndex((o) => String(o).toLowerCase() === 'yes');
  const noIdx = outcomes.findIndex((o) => String(o).toLowerCase() === 'no');

  let yesPrice = yesIdx >= 0 && yesIdx < prices.length ? prices[yesIdx] : (prices[0] ?? parseNumber(obj?.lastTradePrice) ?? parseNumber(obj?.bestAsk));
  if (yesPrice == null) yesPrice = 0.5;
  yesPrice = Math.max(0.001, Math.min(0.999, yesPrice));

  let noPrice = noIdx >= 0 && noIdx < prices.length ? prices[noIdx] : (prices[1] ?? (1 - yesPrice));
  if (Math.abs((yesPrice + noPrice) - 1) > 0.18) noPrice = 1 - yesPrice;
  noPrice = Math.max(0.001, Math.min(0.999, noPrice));

  const bestBid = parseNumber(obj?.bestBid);
  const bestAsk = parseNumber(obj?.bestAsk);
  const spread = inferSpread(obj, yesPrice, noPrice);

  return {
    id: String(obj?.id ?? ''),
    question,
    bucketLabel: condition.bucketLabel,
    condition,
    active: obj?.active !== false,
    closed: obj?.closed === true,
    acceptingOrders: obj?.acceptingOrders !== false,
    yesPrice,
    noPrice,
    bestBid,
    bestAsk,
    spread,
    liquidity: parseNumber(obj?.liquidityNum) ?? parseNumber(obj?.liquidity),
    volumeBucket: parseNumber(obj?.volume),
    volume24h: parseNumber(obj?.volume24hr) ?? parseNumber(obj?.volume24hrClob)
  };
}

async function fetchJson(url) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), POLY_TIMEOUT_MS);
  try {
    const resp = await fetch(url, {
      signal: controller.signal,
      headers: { 'accept': 'application/json', 'user-agent': 'polyMeteo-web/0.1' }
    });
    if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
    return { ok: true, json: await resp.json(), url: resp.url || url };
  } catch (error) {
    return { ok: false, error: String(error?.message || error), url };
  } finally {
    clearTimeout(timeout);
  }
}

async function fetchEventMarketsForDate(city, targetDate) {
  const slugs = buildEventSlugCandidates(city, targetDate);
  const errors = [];
  for (const slug of slugs) {
    const url = `${GAMMA_BASE}/events?slug=${encodeURIComponent(slug)}`;
    const result = await fetchJson(url);
    if (!result.ok) {
      errors.push(`${slug}: ${result.error}`);
      continue;
    }
    const root = Array.isArray(result.json) ? result.json : [];
    const event = root[0];
    if (!event || !Array.isArray(event.markets)) {
      errors.push(`${slug}: evento sin mercados`);
      continue;
    }
    const markets = event.markets
      .map((m) => parseMarketObject(m, city, targetDate))
      .filter(Boolean)
      .filter((m) => !m.closed)
      .filter((m) => m.condition.targetDate === targetDate)
      .sort((a, b) => (b.volumeBucket ?? 0) - (a.volumeBucket ?? 0));
    if (markets.length) {
      return {
        ok: true,
        slug,
        eventUrl: `https://polymarket.com/es/event/${slug}`,
        markets,
        errors: []
      };
    }
    errors.push(`${slug}: sin buckets detectables`);
    await sleep(50);
  }
  return { ok: false, slug: slugs[0] || null, eventUrl: slugs[0] ? `https://polymarket.com/es/event/${slugs[0]}` : null, markets: [], errors };
}

function toHorizonKey(targetDate, cityToday) {
  const t = new Date(`${targetDate}T00:00:00Z`).getTime();
  const c = new Date(`${cityToday}T00:00:00Z`).getTime();
  const diff = Math.round((t - c) / 86400000);
  if (diff <= 0) return 'today';
  if (diff === 1) return 'tomorrow';
  return 'dayAfter';
}

function formatMarketBoardMarket(m) {
  const yesAsk = (m.bestAsk ?? m.yesPrice) * 100;
  const noAsk = m.noPrice * 100;
  const bestBid = m.bestBid != null ? m.bestBid * 100 : null;
  const spreadPct = m.spread != null ? m.spread * 100 : ((yesAsk + noAsk) - 100);
  return {
    id: m.id,
    question: m.question,
    bucketLabel: m.bucketLabel,
    targetDate: m.condition.targetDate,
    unit: m.condition.unit,
    type: m.condition.type,
    threshold: m.condition.threshold,
    upperThreshold: m.condition.upperThreshold,
    active: m.active,
    closed: m.closed,
    acceptingOrders: m.acceptingOrders,
    marketProbabilityYesPct: m.yesPrice * 100,
    yesAskCents: yesAsk,
    noAskCents: noAsk,
    bestBidCents: bestBid,
    bestAskCents: m.bestAsk != null ? m.bestAsk * 100 : null,
    spreadPct,
    liquidityBook: m.liquidity,
    volumeBucket: m.volumeBucket,
    volume24h: m.volume24h
  };
}

function overlayMockOpportunitiesWithReal(cityDetail, board) {
  if (!cityDetail?.horizons || !board?.horizons) return cityDetail;
  const cloned = JSON.parse(JSON.stringify(cityDetail));
  const boardByHorizon = new Map(board.horizons.map((h) => [h.key, h]));
  cloned.horizons = cloned.horizons.map((h) => {
    const realH = boardByHorizon.get(h.key);
    if (!realH?.markets?.length) return h;
    const realByBucket = new Map(realH.markets.map((m) => [String(m.bucketLabel).toLowerCase(), m]));
    const opportunities = (h.opportunities || []).map((op) => {
      const real = realByBucket.get(String(op.bucketLabel || '').toLowerCase());
      if (!real) return op;
      return {
        ...op,
        marketProbabilityYesPct: real.marketProbabilityYesPct,
        yesAskCents: real.yesAskCents,
        noAskCents: real.noAskCents,
        liquidityBook: real.liquidityBook,
        volumeBucket: real.volumeBucket,
        spreadPct: real.spreadPct,
        realMarketOverlay: {
          applied: true,
          marketId: real.id,
          volume24h: real.volume24h,
          bestBidCents: real.bestBidCents,
          bestAskCents: real.bestAskCents
        }
      };
    });
    return { ...h, opportunities };
  });
  return cloned;
}

export async function fetchPolymarketBoardsForCity(city) {
  const cityToday = (() => {
    try {
      const fmt = new Intl.DateTimeFormat('en-GB', { timeZone: city.zoneId, day: '2-digit', month: '2-digit', year: 'numeric' });
      const parts = Object.fromEntries(fmt.formatToParts(new Date()).filter((p) => p.type !== 'literal').map((p) => [p.type, p.value]));
      return `${parts.year}-${parts.month}-${parts.day}`;
    } catch {
      return new Date().toISOString().slice(0, 10);
    }
  })();
  const targetDates = [cityToday, addDays(cityToday, 1), addDays(cityToday, 2)];
  const fetches = await Promise.all(targetDates.map((date) => fetchEventMarketsForDate(city, date)));
  const horizons = fetches.map((result, idx) => {
    const targetDate = targetDates[idx];
    const key = toHorizonKey(targetDate, cityToday);
    return {
      key,
      label: key === 'today' ? 'Hoy' : key === 'tomorrow' ? 'Mañana' : 'Pasado',
      targetDate,
      slug: result.slug,
      eventUrl: result.eventUrl,
      status: result.ok ? 'SUCCESS' : 'ERROR',
      error: result.ok ? null : result.errors.join(' | '),
      markets: (result.markets || []).map(formatMarketBoardMarket)
    };
  });
  return {
    source: 'POLYMARKET_GAMMA',
    fetchedAtUtc: new Date().toISOString(),
    cityId: city.id,
    cityName: city.name,
    cityToday,
    horizons
  };
}

function addDays(dateIso, days) {
  const d = new Date(`${dateIso}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() + days);
  return d.toISOString().slice(0, 10);
}

export function applyPolymarketOverlayToCityDetail(cityDetail, board) {
  const cloned = overlayMockOpportunitiesWithReal(cityDetail, board);
  cloned.polymarketReal = board;
  cloned.warnings = [...(cloned.warnings || [])];
  const failedHorizons = (board?.horizons || []).filter((h) => h.status !== 'SUCCESS');
  if (failedHorizons.length) {
    cloned.warnings.push(`Polymarket (lectura) parcial: ${failedHorizons.map((h) => `${h.label}: ${h.error || 'sin datos'}`).join(' | ')}`);
  }
  return cloned;
}
