import { createMockProvider } from './mockProvider.js';
import { applyPolymarketOverlayToCityDetail, fetchPolymarketBoardsForCity } from './polymarketReadOnly.js';
import {
  MMA_WEIGHTS_VERSION,
  getCityMmaWeights,
  getOpenMeteoModelLabel,
  normalizeWeightsForAvailable
} from './mmaWeights.js';

const METAR_API_BASE = 'https://aviationweather.gov/api/data/metar';
const WUNDERGROUND_TIMEOUT_MS = 5000;
const OPEN_METEO_TIMEOUT_MS = 5000;

const WUNDERGROUND_BY_CITY = {
  'miami': {
    controlUrl: 'https://www.wunderground.com/weather/us/fl/miami/KMIA',
    pwsUrl: 'https://www.wunderground.com/dashboard/pws/KFLHIALE117?cm_ven=localwx_pwsdash'
  },
  'london': {
    controlUrl: 'https://www.wunderground.com/history/daily/gb/london/EGLC',
    pwsUrl: 'https://www.wunderground.com/dashboard/pws/ILONDO288?cm_ven=localwx_pwsdash'
  },
  'toronto': {
    controlUrl: 'https://www.wunderground.com/history/daily/ca/mississauga/CYYZ',
    pwsUrl: 'https://www.wunderground.com/dashboard/pws/IMISSI113?cm_ven=localwx_pwsdash'
  },
  'seattle': {
    controlUrl: 'https://www.wunderground.com/history/daily/us/wa/seatac/KSEA',
    pwsUrl: 'https://www.wunderground.com/dashboard/pws/KWASEATA17?cm_ven=localwx_pwsdash'
  },
  'dallas': {
    controlUrl: 'https://www.wunderground.com/history/daily/us/tx/dallas/KDAL',
    pwsUrl: 'https://www.wunderground.com/dashboard/pws/KTXDALLA1276?cm_ven=localwx_pwsdash'
  },
  'wellington': {
    controlUrl: 'https://www.wunderground.com/history/daily/nz/wellington/NZWN',
    pwsUrl: 'https://www.wunderground.com/dashboard/pws/IWGNLYAL3?cm_ven=localwx_pwsdash'
  },
  'ankara': {
    controlUrl: 'https://www.wunderground.com/history/daily/tr/%C3%A7ubuk/LTAC',
    pwsUrl: 'https://www.wunderground.com/dashboard/pws/IANKAR46?cm_ven=localwx_pwsdash'
  },
  'seoul': {
    controlUrl: 'https://www.wunderground.com/history/daily/kr/incheon/RKSI',
    pwsUrl: 'https://www.wunderground.com/dashboard/pws/IINCHE10?cm_ven=localwx_pwsdash'
  },
  'new-york': {
    controlUrl: 'https://www.wunderground.com/history/daily/us/ny/new-york-city/KLGA',
    pwsUrl: 'https://www.wunderground.com/dashboard/pws/KNYNEWYO1974?cm_ven=localwx_pwsdash'
  },
  'chicago': {
    controlUrl: 'https://www.wunderground.com/history/daily/us/il/chicago/KORD',
    pwsUrl: 'https://www.wunderground.com/dashboard/pws/KILBENSE15?cm_ven=localwx_pwsdash'
  },
  'atlanta': {
    controlUrl: 'https://www.wunderground.com/history/daily/us/ga/atlanta/KATL',
    pwsUrl: 'https://www.wunderground.com/dashboard/pws/KGAHAPEV1?cm_ven=localwx_pwsdash'
  },
  'paris': {
    controlUrl: 'https://www.wunderground.com/history/daily/fr/paris/LFPG',
    pwsUrl: 'https://www.wunderground.com/dashboard/pws/IMITRY1?cm_ven=localwx_pwsdash'
  },
  'buenos-aires': {
    controlUrl: 'https://www.wunderground.com/history/daily/ar/ezeiza/SAEZ',
    pwsUrl: 'https://www.wunderground.com/dashboard/pws/IMONTEGR27?cm_ven=localwx_pwsdash'
  },
  'sao-paulo': {
    controlUrl: 'https://www.wunderground.com/history/daily/br/guarulhos/SBGR',
    pwsUrl: 'https://www.wunderground.com/dashboard/pws/IGUARU12?cm_ven=localwx_pwsdash'
  }
};

const OPEN_METEO_COORDS_BY_CITY = {
  'miami': { lat: 25.857, lon: -80.265 },
  'london': { lat: 51.514, lon: 0.037 },
  'toronto': { lat: 43.733, lon: -79.637 },
  'seattle': { lat: 47.446, lon: -122.276 },
  'dallas': { lat: 32.829, lon: -96.878 },
  'wellington': { lat: -41.325, lon: 174.792 },
  'ankara': { lat: 39.931, lon: 32.899 },
  'seoul': { lat: 37.482, lon: 126.523 },
  'new-york': { lat: 40.764, lon: -73.835 },
  'chicago': { lat: 41.964, lon: -87.946 },
  'atlanta': { lat: 33.661, lon: -84.399 },
  'paris': { lat: 48.974, lon: 2.632 },
  'buenos-aires': { lat: -34.817, lon: -58.467 },
  'sao-paulo': { lat: -23.448, lon: -46.526 }
};

function round1(value) {
  return Math.round(value * 10) / 10;
}

function deepClone(data) {
  return JSON.parse(JSON.stringify(data));
}

function celsiusToFahrenheit(c) {
  return (c * 9) / 5 + 32;
}

function fahrenheitToCelsius(f) {
  return ((f - 32) * 5) / 9;
}

function toTempDisplays(tempC, displayUnit) {
  if (tempC == null || Number.isNaN(tempC)) {
    return { primary: '--', secondary: '--' };
  }
  const tempF = (tempC * 9) / 5 + 32;
  const round1 = (n) => Math.round(n * 10) / 10;
  if (displayUnit === 'F') {
    return {
      primary: `${round1(tempF)}°F`,
      secondary: `${round1(tempC)}°C`
    };
  }
  return {
    primary: `${round1(tempC)}°C`,
    secondary: `${round1(tempF)}°F`
  };
}

function toDeltaDisplay(deltaInDisplayUnit, displayUnit) {
  if (deltaInDisplayUnit == null || Number.isNaN(deltaInDisplayUnit)) {
    return { value: 0, display: `±0°${displayUnit}` };
  }
  const rounded = Math.round(deltaInDisplayUnit);
  if (rounded === 0) return { value: 0, display: `±0°${displayUnit}` };
  const prefix = rounded > 0 ? '+' : '-';
  return { value: rounded, display: `${prefix}${Math.abs(rounded)}°${displayUnit}` };
}

function toDisplayUnitDelta(currentC, previousC, displayUnit) {
  if (currentC == null || previousC == null) return null;
  let delta = currentC - previousC;
  if (displayUnit === 'F') {
    delta *= 9 / 5;
  }
  return delta;
}

function nowLocalTimeString(zoneId) {
  try {
    return new Intl.DateTimeFormat('en-GB', {
      timeZone: zoneId,
      hour: '2-digit',
      minute: '2-digit',
      hour12: false
    }).format(new Date());
  } catch {
    return '--:--';
  }
}

function horizonTickerSuffix(horizonKey) {
  if (horizonKey === 'tomorrow') return ' (Mañana)';
  if (horizonKey === 'dayAfter') return ' (Pasado)';
  return '';
}

function formatLocalDateTime(isoInstant, zoneId) {
  if (!isoInstant) return null;
  try {
    const d = new Date(isoInstant);
    const fmt = new Intl.DateTimeFormat('en-GB', {
      timeZone: zoneId,
      day: '2-digit',
      month: '2-digit',
      year: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
      hour12: false
    });
    const parts = Object.fromEntries(fmt.formatToParts(d).filter((p) => p.type !== 'literal').map((p) => [p.type, p.value]));
    return `${parts.day}-${parts.month}-${parts.year} ${parts.hour}:${parts.minute}`;
  } catch {
    return null;
  }
}

function todayIsoInZone(zoneId) {
  try {
    const fmt = new Intl.DateTimeFormat('en-GB', {
      timeZone: zoneId,
      day: '2-digit',
      month: '2-digit',
      year: 'numeric'
    });
    const parts = Object.fromEntries(fmt.formatToParts(new Date()).filter((p) => p.type !== 'literal').map((p) => [p.type, p.value]));
    return `${parts.year}-${parts.month}-${parts.day}`;
  } catch {
    return new Date().toISOString().slice(0, 10);
  }
}

function hoursAgoFromInstant(isoInstant) {
  if (!isoInstant) return null;
  const t = new Date(isoInstant).getTime();
  if (!Number.isFinite(t)) return null;
  const diffMs = Date.now() - t;
  const hours = Math.max(0, Math.round(diffMs / 3600000));
  return hours;
}

function parseDisplayNumeric(text) {
  if (typeof text !== 'string') return null;
  const m = text.match(/-?\d+(?:\.\d+)?/);
  return m ? Number(m[0]) : null;
}

function displayPairToC(tempPair, displayUnit) {
  if (!tempPair) return null;
  const primary = parseDisplayNumeric(tempPair.primary);
  const secondary = parseDisplayNumeric(tempPair.secondary);
  if (tempPair.secondary && /°C/.test(tempPair.secondary) && Number.isFinite(secondary)) return secondary;
  if (tempPair.primary && /°C/.test(tempPair.primary) && Number.isFinite(primary)) return primary;
  if (Number.isFinite(primary)) return displayUnit === 'C' ? primary : fahrenheitToCelsius(primary);
  return null;
}

function parseAviationMetarJson(raw) {
  let parsed;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return [];
  }
  const array = Array.isArray(parsed) ? parsed : Array.isArray(parsed?.data) ? parsed.data : [];

  const readings = array.map((obj) => {
    const observedAt = parseObservedAt(obj);
    if (!observedAt) return null;
    const tempC = firstNumber(
      obj?.temp,
      obj?.temperature,
      obj?.temperature?.value
    );
    const rawText = asString(obj?.rawOb) || asString(obj?.raw) || null;
    return { observedAt, tempC, rawText };
  }).filter(Boolean);

  readings.sort((a, b) => new Date(b.observedAt).getTime() - new Date(a.observedAt).getTime());
  return readings;
}

function asString(v) {
  return typeof v === 'string' && v.trim() ? v.trim() : null;
}

function firstNumber(...values) {
  for (const value of values) {
    if (typeof value === 'number' && Number.isFinite(value)) return value;
    if (typeof value === 'string') {
      const n = Number(value);
      if (Number.isFinite(n)) return n;
    }
  }
  return null;
}

function parseObservedAt(obj) {
  const stringKeys = ['obsTime', 'observationTime', 'obs_time', 'date'];
  for (const key of stringKeys) {
    const value = asString(obj?.[key]);
    if (!value) continue;
    const t = Date.parse(value);
    if (Number.isFinite(t)) return new Date(t).toISOString();
  }
  const numberKeys = ['obsTime', 'observationTime', 'obs_time'];
  for (const key of numberKeys) {
    const value = obj?.[key];
    if (typeof value !== 'number' || !Number.isFinite(value)) continue;
    const millis = value > 100_000_000_000 ? value : value * 1000;
    const t = new Date(millis).getTime();
    if (Number.isFinite(t)) return new Date(t).toISOString();
  }
  return null;
}

async function fetchMetarSnapshot(metarCode) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 4500);
  const url = `${METAR_API_BASE}?ids=${encodeURIComponent(metarCode)}&format=json&hours=24`;
  try {
    const response = await fetch(url, { signal: controller.signal, headers: { 'accept': 'application/json' } });
    if (!response.ok) {
      throw new Error(`METAR HTTP ${response.status}`);
    }
    const text = await response.text();
    const readings = parseAviationMetarJson(text);
    if (!readings.length) {
      throw new Error('METAR sin lecturas');
    }
    const current = readings[0] || null;
    const previous = readings[1] || null;
    return { current, previous, sourceUrl: url, error: null };
  } catch (error) {
    return { current: null, previous: null, sourceUrl: url, error: String(error?.message || error) };
  } finally {
    clearTimeout(timeout);
  }
}

function stripHtmlToText(html) {
  return html
    .replace(/<script[\s\S]*?<\/script>/gi, ' ')
    .replace(/<style[\s\S]*?<\/style>/gi, ' ')
    .replace(/<[^>]+>/g, ' ')
    .replace(/&nbsp;/gi, ' ')
    .replace(/&amp;/gi, '&')
    .replace(/\s+/g, ' ')
    .trim();
}

function inferUnitFromValue(value, preferredUnit) {
  if (!Number.isFinite(value)) return preferredUnit;
  if (Math.abs(value) > 60) return 'F';
  if (Math.abs(value) > 45 && preferredUnit === 'C') return 'F';
  return preferredUnit;
}

function isPlausibleTemp(value, unit) {
  if (!Number.isFinite(value)) return false;
  if (unit === 'C') return value >= -65 && value <= 65;
  return value >= -85 && value <= 150;
}

function parseWundergroundSummaryHighTemp(text, preferredUnit) {
  const normalized = text.replace(/\s+/g, ' ').trim();
  const regexNormalized = /Temperature\s*\(\s*(?:°|º)?\s*([CF])\s*\)\s*Actual\s*Historic\s*Avg\s*High\s*Temp\s*(-?\d+(?:\.\d+)?)/i;
  const m1 = normalized.match(regexNormalized);
  if (m1) {
    const unit = String(m1[1]).toUpperCase() === 'F' ? 'F' : 'C';
    const value = Number(m1[2]);
    if (isPlausibleTemp(value, unit)) return { value, unit, source: 'summary-normalized' };
  }

  const unitHeaderMatch = text.match(/Temperature\s*\(\s*(?:°|º)?\s*([CF])\s*\)/i);
  const headerUnit = unitHeaderMatch ? (String(unitHeaderMatch[1]).toUpperCase() === 'F' ? 'F' : 'C') : null;
  const idx = text.toLowerCase().indexOf('high temp');
  if (idx < 0) return null;
  const slice = text.slice(idx, idx + 260);
  const explicit = slice.match(/High\s*Temp(?:\s*Actual)?\s*(-?\d+(?:\.\d+)?)\s*(?:°|º)?\s*([CF])?/i);
  if (explicit) {
    const value = Number(explicit[1]);
    const unit = explicit[2] ? (String(explicit[2]).toUpperCase() === 'F' ? 'F' : 'C') : (headerUnit || inferUnitFromValue(value, preferredUnit));
    if (isPlausibleTemp(value, unit)) return { value, unit, source: 'summary-slice' };
  }
  const firstNum = slice.match(/(-?\d+(?:\.\d+)?)/);
  if (firstNum) {
    const value = Number(firstNum[1]);
    const unit = headerUnit || inferUnitFromValue(value, preferredUnit);
    if (isPlausibleTemp(value, unit)) return { value, unit, source: 'summary-first-number' };
  }
  return null;
}

function parseWundergroundEmbeddedMax(html, preferredUnit) {
  const keyRegex = /"(temperatureMaxSince7Am|temperatureMax24Hour)"\s*:\s*(-?\d+(?:\.\d+)?)/gi;
  let match;
  let best = null;
  while ((match = keyRegex.exec(html)) !== null) {
    const value = Number(match[2]);
    const context = html.slice(Math.max(0, match.index - 2500), Math.min(html.length, match.index + 2500));
    let unit = null;
    if (/unit[^a-z0-9]{0,12}(?:[\"'])?e(?:nglish|n-US|imperial)/i.test(context) || /temperatureUnit[^a-z0-9]{0,12}(?:[\"'])?F/i.test(context)) {
      unit = 'F';
    } else if (/unit[^a-z0-9]{0,12}(?:[\"'])?(?:metric|si)/i.test(context) || /temperatureUnit[^a-z0-9]{0,12}(?:[\"'])?C/i.test(context)) {
      unit = 'C';
    }
    if (!unit) {
      // Wunderground history pages often embed weather.com payloads in imperial units (`units=e`)
      // even for cities shown in Celsius in the UI. When no explicit hint is found nearby,
      // prefer F here to avoid false highs like "39°C" that are actually 39°F.
      if (/(?:[?&]|\\u0026)units=(?:e|imperial)\b/i.test(context) || /"units"\s*:\s*"e"/i.test(context)) {
        unit = 'F';
      } else if (/(?:[?&]|\\u0026)units=(?:m|metric)\b/i.test(context) || /"units"\s*:\s*"m"/i.test(context)) {
        unit = 'C';
      } else {
        unit = 'F';
      }
    }
    if (!isPlausibleTemp(value, unit)) continue;
    const valueC = unit === 'C' ? value : fahrenheitToCelsius(value);
    if (!best || valueC > best.valueC) {
      best = { value, unit, valueC, source: `embedded-${match[1]}` };
    }
  }
  return best;
}

function parseWundergroundPwsCurrent(html, preferredUnit) {
  const text = stripHtmlToText(html);
  const idx = text.toLowerCase().indexOf('temperature');
  if (idx < 0) return null;
  const slice = text.slice(Math.max(0, idx - 80), idx + 220);
  const m = slice.match(/(-?\d+(?:\.\d+)?)\s*(?:°|º)?\s*([CF])?/i);
  if (!m) return null;
  const value = Number(m[1]);
  const unit = m[2] ? (String(m[2]).toUpperCase() === 'F' ? 'F' : 'C') : inferUnitFromValue(value, preferredUnit);
  if (!isPlausibleTemp(value, unit)) return null;
  const valueC = unit === 'C' ? value : fahrenheitToCelsius(value);
  return { value, unit, valueC, source: 'pws-current' };
}

function chooseHigherTempCandidate(...candidates) {
  const normalized = candidates.filter(Boolean).map((c) => {
    const valueC = c.valueC ?? (c.unit === 'C' ? c.value : fahrenheitToCelsius(c.value));
    return { ...c, valueC };
  });
  if (!normalized.length) return null;
  return normalized.sort((a, b) => b.valueC - a.valueC)[0];
}

async function fetchHtml(url, { accept = 'text/html,application/xhtml+xml' } = {}) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), WUNDERGROUND_TIMEOUT_MS);
  try {
    const response = await fetch(url, {
      signal: controller.signal,
      headers: {
        'accept': accept,
        'user-agent': 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122 Safari/537.36',
        'referer': 'https://www.wunderground.com/',
        'origin': 'https://www.wunderground.com'
      }
    });
    if (!response.ok) {
      throw new Error(`HTTP ${response.status}`);
    }
    return { ok: true, url: response.url || url, body: await response.text() };
  } catch (error) {
    return { ok: false, url, error: String(error?.message || error) };
  } finally {
    clearTimeout(timeout);
  }
}

async function fetchWundergroundControlSnapshot(cityId, displayUnit) {
  const cfg = WUNDERGROUND_BY_CITY[cityId];
  if (!cfg) return { tempC: null, sourceUrl: null, error: 'Ciudad sin URL Wunderground configurada' };

  const errors = [];
  const attempts = [cfg.controlUrl, cfg.pwsUrl].filter(Boolean);
  for (const url of attempts) {
    const htmlResp = await fetchHtml(url);
    if (!htmlResp.ok) {
      errors.push(`${url} -> ${htmlResp.error}`);
      continue;
    }
    const pageText = stripHtmlToText(htmlResp.body);
    const summary = parseWundergroundSummaryHighTemp(pageText, displayUnit);
    const embedded = parseWundergroundEmbeddedMax(htmlResp.body, displayUnit);
    const pwsCurrent = url.includes('/dashboard/pws/') ? parseWundergroundPwsCurrent(htmlResp.body, displayUnit) : null;
    const chosen = chooseHigherTempCandidate(summary, embedded, pwsCurrent);
    if (chosen) {
      return {
        tempC: chosen.valueC,
        sourceUrl: htmlResp.url || url,
        sourceKind: chosen.source,
        error: null
      };
    }
    errors.push(`${url} -> sin maxima detectable`);
  }
  return {
    tempC: null,
    sourceUrl: cfg.controlUrl || cfg.pwsUrl || null,
    error: errors.join(' | ') || 'Wunderground sin datos'
  };
}

async function fetchOpenMeteoMmSnapshot(cityId, zoneId) {
  const coords = OPEN_METEO_COORDS_BY_CITY[cityId];
  if (!coords) {
    return { dailyMmC: {}, dailyByModelC: {}, sourceUrl: null, models: [], error: 'Ciudad sin coordenadas Open-Meteo' };
  }
  const params = new URLSearchParams({
    latitude: String(coords.lat),
    longitude: String(coords.lon),
    daily: 'temperature_2m_max',
    forecast_days: '3',
    timezone: zoneId,
    models: 'ecmwf_ifs,gfs_global'
  });
  const url = `https://api.open-meteo.com/v1/forecast?${params.toString()}`;
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), OPEN_METEO_TIMEOUT_MS);
  try {
    const response = await fetch(url, { signal: controller.signal, headers: { accept: 'application/json' } });
    if (!response.ok) {
      throw new Error(`Open-Meteo HTTP ${response.status}`);
    }
    const json = await response.json();
    const dates = Array.isArray(json?.daily?.time) ? json.daily.time : [];
    const keys = Object.keys(json?.daily || {}).filter((k) => k.startsWith('temperature_2m_max_'));
    const dailyByModelC = {};
    for (const key of keys) {
      const modelKey = key.replace('temperature_2m_max_', '');
      const series = Array.isArray(json?.daily?.[key]) ? json.daily[key] : [];
      const byDate = {};
      for (let i = 0; i < dates.length; i += 1) {
        const v = series[i];
        if (typeof v === 'number' && Number.isFinite(v)) {
          byDate[dates[i]] = v;
        }
      }
      dailyByModelC[modelKey] = byDate;
    }
    const dailyMmC = {};
    for (let i = 0; i < dates.length; i += 1) {
      const values = keys
        .map((key) => json.daily[key]?.[i])
        .filter((v) => typeof v === 'number' && Number.isFinite(v));
      if (!values.length) continue;
      dailyMmC[dates[i]] = values.reduce((a, b) => a + b, 0) / values.length;
    }
    return {
      dailyMmC,
      dailyByModelC,
      sourceUrl: url,
      models: keys.map((k) => k.replace('temperature_2m_max_', '')),
      error: null
    };
  } catch (error) {
    return { dailyMmC: {}, dailyByModelC: {}, sourceUrl: url, models: [], error: String(error?.message || error) };
  } finally {
    clearTimeout(timeout);
  }
}

function computeWeightedMmaFromForecast(cityId, forecastSnapshot) {
  const dailyByModelC = forecastSnapshot?.dailyByModelC || {};
  const modelKeys = Object.keys(dailyByModelC).filter((key) => dailyByModelC[key] && typeof dailyByModelC[key] === 'object');
  if (!modelKeys.length) {
    return {
      dailyMmaC: {},
      normalizedWeightsByModel: {},
      dominantModelKey: null,
      dominantWeightPct: null,
      source: MMA_WEIGHTS_VERSION
    };
  }

  const baseWeights = getCityMmaWeights(cityId);
  const normalizedWeightsByModel = normalizeWeightsForAvailable(baseWeights, modelKeys);
  const dailyMmaC = {};
  const allDates = new Set();
  for (const key of modelKeys) {
    Object.keys(dailyByModelC[key] || {}).forEach((date) => allDates.add(date));
  }

  for (const date of allDates) {
    const availableForDate = modelKeys.filter((key) => Number.isFinite(dailyByModelC[key]?.[date]));
    if (!availableForDate.length) continue;
    const weights = normalizeWeightsForAvailable(baseWeights, availableForDate);
    let sum = 0;
    let totalWeight = 0;
    for (const key of availableForDate) {
      const tempC = dailyByModelC[key][date];
      const w = weights[key] || 0;
      sum += tempC * w;
      totalWeight += w;
    }
    if (totalWeight > 0) {
      dailyMmaC[date] = sum / totalWeight;
    }
  }

  const dominantEntry = Object.entries(normalizedWeightsByModel)
    .sort((a, b) => b[1] - a[1])[0] || null;

  return {
    dailyMmaC,
    normalizedWeightsByModel,
    dominantModelKey: dominantEntry?.[0] || null,
    dominantWeightPct: dominantEntry ? round1(dominantEntry[1] * 100) : null,
    source: MMA_WEIGHTS_VERSION
  };
}

const WEB_ENGINE_THRESHOLDS = Object.freeze({
  spreadMaxPct: 12,
  liquidityMin: 250,
  volumeBucketMin: 200,
  totalCostMaxPct: 22,
  fillMinPct: 55,
  execMinPctToday: 2.5,
  execMinPctFuture: 2.0,
  priceMinCents: 2,
  priceMaxCents: 90
});

const WEB_STRATEGY_THRESHOLDS = Object.freeze({
  conservadora: {
    executableEdgePctMin: 7,
    fillMinPct: 72,
    liquidityMin: 1200,
    spreadMaxPct: 8,
    totalCostMaxPct: 17,
    requireGreen: true
  },
  agresiva: {
    executableEdgePctMin: 3,
    fillMinPct: 55,
    liquidityMin: 300,
    spreadMaxPct: 12,
    totalCostMaxPct: 21,
    requireGreen: false
  }
});

function clamp(value, min, max) {
  return Math.min(max, Math.max(min, value));
}

function roundTo(value, digits = 1) {
  const p = 10 ** digits;
  return Math.round(value * p) / p;
}

function unitToCelsius(value, unit) {
  if (!Number.isFinite(value)) return null;
  return unit === 'F' ? fahrenheitToCelsius(value) : value;
}

function celsiusToUnit(valueC, unit) {
  if (!Number.isFinite(valueC)) return null;
  return unit === 'F' ? celsiusToFahrenheit(valueC) : valueC;
}

function parseLocalHour(localTimeText) {
  if (typeof localTimeText !== 'string') return null;
  const m = localTimeText.match(/^(\d{1,2}):(\d{2})$/);
  if (!m) return null;
  const hour = Number(m[1]);
  return Number.isFinite(hour) ? hour : null;
}

function erfApprox(x) {
  const sign = x < 0 ? -1 : 1;
  const ax = Math.abs(x);
  const a1 = 0.254829592;
  const a2 = -0.284496736;
  const a3 = 1.421413741;
  const a4 = -1.453152027;
  const a5 = 1.061405429;
  const p = 0.3275911;
  const t = 1 / (1 + p * ax);
  const y = 1 - (((((a5 * t + a4) * t) + a3) * t + a2) * t + a1) * t * Math.exp(-ax * ax);
  return sign * y;
}

function normalCdf(x, mean, sigma) {
  if (!Number.isFinite(x) || !Number.isFinite(mean)) return null;
  const s = Number.isFinite(sigma) && sigma > 0 ? sigma : 1;
  return 0.5 * (1 + erfApprox((x - mean) / (s * Math.SQRT2)));
}

function bucketProbabilityYesPct(market, meanC, sigmaC) {
  if (!Number.isFinite(meanC)) return null;
  const unit = market.unit || 'C';
  const mean = celsiusToUnit(meanC, unit);
  const sigma = unit === 'F' ? sigmaC * 9 / 5 : sigmaC;
  const t = Number(market.threshold);
  const u = Number(market.upperThreshold);
  if (!Number.isFinite(mean) || !Number.isFinite(sigma)) return null;
  const cdf = (x) => clamp(normalCdf(x, mean, sigma) ?? 0.5, 0, 1);
  switch (market.type) {
    case 'EXACT':
      if (!Number.isFinite(t)) return null;
      return clamp((cdf(t + 1) - cdf(t)) * 100, 0.1, 99.9);
    case 'BETWEEN':
      if (!Number.isFinite(t) || !Number.isFinite(u)) return null;
      return clamp((cdf(Math.max(t, u) + 1) - cdf(Math.min(t, u))) * 100, 0.1, 99.9);
    case 'GREATER_OR_EQUAL':
      if (!Number.isFinite(t)) return null;
      return clamp((1 - cdf(t)) * 100, 0.1, 99.9);
    case 'LESS_OR_EQUAL':
      if (!Number.isFinite(t)) return null;
      return clamp(cdf(t + 1) * 100, 0.1, 99.9);
    default:
      return null;
  }
}

function inferDirectionFromMean(market, meanC) {
  const mean = celsiusToUnit(meanC, market.unit || 'C');
  const t = Number(market.threshold);
  const u = Number(market.upperThreshold);
  switch (market.type) {
    case 'GREATER_OR_EQUAL':
      return 'OVER';
    case 'LESS_OR_EQUAL':
      return 'UNDER';
    case 'BETWEEN': {
      const lower = Math.min(t, u);
      const upperExclusive = Math.max(t, u) + 1;
      if (!Number.isFinite(mean)) return 'RANGE';
      if (mean < lower) return 'UNDER';
      if (mean >= upperExclusive) return 'OVER';
      return 'RANGE';
    }
    case 'EXACT':
    default: {
      if (!Number.isFinite(mean)) return 'RANGE';
      if (mean < t) return 'UNDER';
      if (mean >= t + 1) return 'OVER';
      return 'RANGE';
    }
  }
}

function isMarketImpossibleByObservedTrunc(market, observedMaxC) {
  if (!Number.isFinite(observedMaxC)) return false;
  const observedUnit = celsiusToUnit(observedMaxC, market.unit || 'C');
  if (!Number.isFinite(observedUnit)) return false;
  const observedTrunc = Math.trunc(observedUnit);
  const t = Number(market.threshold);
  const u = Number(market.upperThreshold);
  switch (market.type) {
    case 'EXACT':
      return Number.isFinite(t) ? observedTrunc > t : false;
    case 'BETWEEN':
      return Number.isFinite(u) ? observedTrunc > Math.max(t, u) : false;
    case 'LESS_OR_EQUAL':
      return Number.isFinite(t) ? observedTrunc > t : false;
    case 'GREATER_OR_EQUAL':
    default:
      return false;
  }
}

function sigmaCForHorizon(horizonKey) {
  if (horizonKey === 'today') return 1.4;
  if (horizonKey === 'tomorrow') return 2.0;
  return 2.6;
}

function computeExecutionMetrics({ market, modelYesPct, marketYesPct, actionSide, horizonKey, localHour }) {
  const yesAsk = Number(market.yesAskCents);
  const noAsk = Number(market.noAskCents);
  const spreadPct = Number.isFinite(market.spreadPct) ? market.spreadPct : 99;
  const liquidityBook = Number.isFinite(market.liquidityBook) ? market.liquidityBook : 0;
  const volumeBucket = Number.isFinite(market.volumeBucket) ? market.volumeBucket : 0;
  const selectedAsk = actionSide === 'YES' ? yesAsk : noAsk;
  const rawEdgePct = Math.abs(modelYesPct - marketYesPct);

  const feePct = clamp(0.2 + (selectedAsk / 100) * 1.2, 0.2, 1.8);
  const spreadCostPct = clamp(spreadPct * 0.75, 0, 14);
  const liqCostPct = clamp(12 / Math.sqrt(Math.max(1, liquidityBook)), 0.5, 8.5);
  const volumeCostPct = volumeBucket > 0 ? clamp(8 / Math.sqrt(Math.max(1, volumeBucket / 10)), 0.2, 5.5) : 5.5;
  const totalCostPct = feePct + spreadCostPct + liqCostPct + volumeCostPct;

  const fillPct = clamp(
    96
      - spreadPct * 4.2
      - (liquidityBook < 1500 ? (1500 - liquidityBook) / 30 : 0)
      - (volumeBucket < 3000 ? (3000 - volumeBucket) / 90 : 0),
    8,
    96
  );

  const edgeAfterCostPct = rawEdgePct - totalCostPct;
  const executableEdgePct = edgeAfterCostPct * (fillPct / 100);
  const execMinPct = horizonKey === 'today' && Number.isFinite(localHour) && localHour >= 15
    ? WEB_ENGINE_THRESHOLDS.execMinPctToday + 1.0
    : (horizonKey === 'today' ? WEB_ENGINE_THRESHOLDS.execMinPctToday : WEB_ENGINE_THRESHOLDS.execMinPctFuture);

  const checks = {
    price: Number.isFinite(selectedAsk) && selectedAsk >= WEB_ENGINE_THRESHOLDS.priceMinCents && selectedAsk <= WEB_ENGINE_THRESHOLDS.priceMaxCents,
    spread: spreadPct <= WEB_ENGINE_THRESHOLDS.spreadMaxPct,
    liquidity: liquidityBook >= WEB_ENGINE_THRESHOLDS.liquidityMin,
    volumeBucket: volumeBucket >= WEB_ENGINE_THRESHOLDS.volumeBucketMin,
    totalCost: totalCostPct <= WEB_ENGINE_THRESHOLDS.totalCostMaxPct,
    fill: fillPct >= WEB_ENGINE_THRESHOLDS.fillMinPct,
    exec: executableEdgePct >= execMinPct
  };

  const shouldTrade = Object.values(checks).every(Boolean);
  const greenProfile = shouldTrade && executableEdgePct >= 7 && fillPct >= 70 && totalCostPct <= 17 && spreadPct <= 8;
  const signal = shouldTrade ? (greenProfile ? 'GREEN' : 'YELLOW') : 'RED';

  let failReason = null;
  if (!checks.price) failReason = 'Precio fuera de rango operativo';
  else if (!checks.spread) failReason = 'Spread demasiado alto';
  else if (!checks.liquidity) failReason = 'Liquidez libro insuficiente';
  else if (!checks.volumeBucket) failReason = 'Volumen bucket insuficiente';
  else if (!checks.totalCost) failReason = 'Coste total demasiado alto';
  else if (!checks.fill) failReason = 'Fill insuficiente';
  else if (!checks.exec) failReason = 'Ventaja ejecutable insuficiente';

  return {
    selectedAskCents: selectedAsk,
    rawEdgePct: roundTo(rawEdgePct, 1),
    feePct: roundTo(feePct, 1),
    totalCostPct: roundTo(totalCostPct, 1),
    fillPct: roundTo(fillPct, 1),
    executableEdgePct: roundTo(executableEdgePct, 1),
    edgeAfterCostPct: roundTo(edgeAfterCostPct, 1),
    execMinPct: roundTo(execMinPct, 1),
    shouldTrade,
    signal,
    failReason,
    checks
  };
}

function computeStrategyEligibilityFromOpportunity(opportunityLike) {
  const base = !!opportunityLike?.shouldTrade;
  const signal = String(opportunityLike?.signal || 'RED');
  const exec = Number(opportunityLike?.executableEdgePct);
  const fill = Number(opportunityLike?.fillProbabilityPct);
  const liquidity = Number(opportunityLike?.liquidityBook);
  const spread = Number(opportunityLike?.spreadPct);
  const totalCost = Number(opportunityLike?.totalCostPct);

  const checkStrategy = (cfg) => {
    if (!base) return false;
    if (cfg.requireGreen && signal !== 'GREEN') return false;
    if (!cfg.requireGreen && signal === 'RED') return false;
    return Number.isFinite(exec) && exec >= cfg.executableEdgePctMin
      && Number.isFinite(fill) && fill >= cfg.fillMinPct
      && Number.isFinite(liquidity) && liquidity >= cfg.liquidityMin
      && Number.isFinite(spread) && spread <= cfg.spreadMaxPct
      && Number.isFinite(totalCost) && totalCost <= cfg.totalCostMaxPct;
  };

  return {
    conservadora: checkStrategy(WEB_STRATEGY_THRESHOLDS.conservadora),
    agresiva: checkStrategy(WEB_STRATEGY_THRESHOLDS.agresiva)
  };
}

function buildReasonSimple({ market, actionSide, signal, shouldTrade, modelYesPct, marketYesPct, metrics }) {
  if (!shouldTrade) {
    return `${metrics.failReason || 'PASS'}: exec ${roundTo(metrics.executableEdgePct, 1)}%, fill ${roundTo(metrics.fillPct, 1)}%, coste ${roundTo(metrics.totalCostPct, 1)}%.`;
  }
  const diff = roundTo(Math.abs(modelYesPct - marketYesPct), 1);
  const caution = signal === 'GREEN' ? 'Buena oportunidad' : 'Entrada posible con cuidado';
  return `${caution}: ${actionSide === 'YES' ? 'modelo > mercado' : 'mercado sobrevalora YES'} por ${diff} pp, spread ${roundTo(market.spreadPct ?? 0, 1)}% y coste ${roundTo(metrics.totalCostPct, 1)}%.`;
}

function computeObservedMaxCForToday(cityDetail) {
  const displayUnit = cityDetail?.city?.displayUnit;
  if (!displayUnit) return null;
  const metarC = displayPairToC(cityDetail?.summary?.metar, displayUnit);
  const stationC = displayPairToC(cityDetail?.summary?.station, displayUnit);
  const vals = [metarC, stationC].filter((v) => Number.isFinite(v));
  return vals.length ? Math.max(...vals) : null;
}

function forecastMeansForDate(cityDetail, targetDate) {
  const displayUnit = cityDetail.city.displayUnit;
  const liveForecast = cityDetail.liveOverlay?.forecast || {};
  const mmFromDaily = liveForecast.dailyMmCByDate?.[targetDate];
  const mmaFromDaily = liveForecast.dailyMmaCByDate?.[targetDate];
  const mmFallback = displayPairToC(cityDetail.summary.mm, displayUnit);
  const mmaFallback = displayPairToC(cityDetail.summary.mma, displayUnit);
  const mmC = Number.isFinite(mmFromDaily) ? mmFromDaily : mmFallback;
  const mmaC = Number.isFinite(mmaFromDaily) ? mmaFromDaily : mmaFallback;
  return { mmC, mmaC: Number.isFinite(mmaC) ? mmaC : mmC };
}

function buildRealDecisionHorizons(cityDetail, board) {
  if (!board?.horizons?.length || !cityDetail?.horizons?.length) return cityDetail;

  const cloned = deepClone(cityDetail);
  const localHour = parseLocalHour(cloned.localTime);
  if (Number.isFinite(localHour)) {
    cloned.isClosedBySchedule = localHour >= 18;
  }
  const observedMaxTodayC = computeObservedMaxCForToday(cloned);
  const existingByKey = new Map((cloned.horizons || []).map((h) => [h.key, h]));

  cloned.horizons = board.horizons.map((realH) => {
    const baseH = existingByKey.get(realH.key) || {
      key: realH.key,
      label: realH.label,
      targetDate: realH.targetDate,
      metarAvailable: realH.key === 'today'
    };

    const traces = [];
    const markets = Array.isArray(realH.markets) ? realH.markets : [];
    const { mmC, mmaC } = forecastMeansForDate(cloned, realH.targetDate);
    const forecastReady = Number.isFinite(mmC) || Number.isFinite(mmaC);
    const meanC = Number.isFinite(mmaC) ? mmaC : mmC;
    const sigmaC = sigmaCForHorizon(realH.key);

    traces.push({
      stage: 'INPUT',
      status: forecastReady ? 'KEPT' : 'DISCARDED',
      reason: forecastReady
        ? 'MM/MMA disponibles para evaluar mercados reales'
        : 'Sin MM/MMA suficientes para calcular probabilidades',
      details: forecastReady
        ? [
            `MM ${Number.isFinite(mmC) ? roundTo(mmC, 1) : '--'}°C`,
            `MMA ${Number.isFinite(mmaC) ? roundTo(mmaC, 1) : '--'}°C`,
            `Sigma ${roundTo(sigmaC, 1)}°C`
          ]
        : []
    });

    if (realH.status !== 'SUCCESS') {
      traces.push({
        stage: 'MARKET_DATA',
        status: 'DISCARDED',
        reason: 'Polymarket no devolvió buckets en este horizonte',
        details: [realH.error || 'Sin datos']
      });
      return {
        ...baseH,
        key: realH.key,
        label: realH.label,
        targetDate: realH.targetDate,
        metarAvailable: realH.key === 'today',
        opportunities: [],
        referenceTop: null,
        statusMessage: realH.key === 'today' ? 'Sin oportunidades en Hoy' : `Sin oportunidades en ${realH.label}`,
        decisionTrace: traces
      };
    }

    const filteredReasons = {
      impossible: 0,
      data: 0,
      inactive: 0,
      dominance: 0
    };

    const closeByScheduleToday = realH.key === 'today' && Number.isFinite(localHour) && localHour >= 18;
    if (closeByScheduleToday) {
      traces.push({
        stage: 'TIME_WINDOW',
        status: 'DISCARDED',
        reason: 'Ciudad cerrada por horario local (>18h)',
        details: [`Hora local ${cloned.localTime}`]
      });
      return {
        ...baseH,
        key: realH.key,
        label: realH.label,
        targetDate: realH.targetDate,
        metarAvailable: true,
        opportunities: [],
        referenceTop: null,
        statusMessage: 'CERRADO POR HORARIO',
        decisionTrace: traces
      };
    }

    let dominantLockMarketId = null;
    if (realH.key === 'today' && Number.isFinite(localHour) && localHour >= 13) {
      const topMarket = markets
        .filter((m) => !m.closed && m.acceptingOrders !== false && m.active !== false)
        .slice()
        .sort((a, b) => (b.marketProbabilityYesPct ?? 0) - (a.marketProbabilityYesPct ?? 0))[0];
      if (topMarket && Number.isFinite(topMarket.marketProbabilityYesPct) && topMarket.marketProbabilityYesPct >= 95) {
        dominantLockMarketId = topMarket.id;
      }
    }

    const candidates = [];
    for (const market of markets) {
      if (market.closed || market.acceptingOrders === false || market.active === false) {
        filteredReasons.inactive += 1;
        continue;
      }
      if (dominantLockMarketId && market.id !== dominantLockMarketId) {
        filteredReasons.dominance += 1;
        continue;
      }
      if (!forecastReady) {
        filteredReasons.data += 1;
        continue;
      }
      const impossibleByObserved = realH.key === 'today' && isMarketImpossibleByObservedTrunc(market, observedMaxTodayC);
      if (impossibleByObserved) {
        filteredReasons.impossible += 1;
        continue;
      }

      const modelYesPct = bucketProbabilityYesPct(market, meanC, sigmaC);
      if (!Number.isFinite(modelYesPct)) {
        filteredReasons.data += 1;
        continue;
      }
      const marketYesPct = Number.isFinite(market.marketProbabilityYesPct) ? market.marketProbabilityYesPct : 50;
      const actionSide = modelYesPct >= marketYesPct ? 'YES' : 'NO';
      const direction = inferDirectionFromMean(market, meanC);
      const metrics = computeExecutionMetrics({ market, modelYesPct, marketYesPct, actionSide, horizonKey: realH.key, localHour });
      const strategyEligible = computeStrategyEligibilityFromOpportunity({
        shouldTrade: metrics.shouldTrade,
        signal: metrics.signal,
        executableEdgePct: metrics.executableEdgePct,
        fillProbabilityPct: metrics.fillPct,
        liquidityBook: Number(market.liquidityBook ?? 0),
        spreadPct: Number(market.spreadPct ?? 0),
        totalCostPct: metrics.totalCostPct
      });

      candidates.push({
        id: market.id || `${cloned.city.id}-${realH.targetDate}-${market.bucketLabel}`,
        bucketLabel: market.bucketLabel,
        question: market.question,
        direction,
        actionSide,
        signal: metrics.signal,
        marketProbabilityYesPct: roundTo(marketYesPct, 1),
        modelProbabilityYesPct: roundTo(modelYesPct, 1),
        executableEdgePct: metrics.executableEdgePct,
        fillProbabilityPct: metrics.fillPct,
        totalCostPct: metrics.totalCostPct,
        spreadPct: roundTo(Number(market.spreadPct ?? 0), 1),
        liquidityBook: Math.round(Number(market.liquidityBook ?? 0)),
        volumeBucket: Math.round(Number(market.volumeBucket ?? 0)),
        yesAskCents: roundTo(Number(market.yesAskCents ?? 0), 1),
        noAskCents: roundTo(Number(market.noAskCents ?? 0), 1),
        shouldTrade: metrics.shouldTrade,
        strategyEligible,
        reasonSimple: buildReasonSimple({ market, actionSide, signal: metrics.signal, shouldTrade: metrics.shouldTrade, modelYesPct, marketYesPct, metrics }),
        realMarketOverlay: {
          applied: true,
          marketId: market.id,
          volume24h: Number(market.volume24h ?? 0),
          bestBidCents: Number.isFinite(market.bestBidCents) ? roundTo(market.bestBidCents, 1) : null,
          bestAskCents: Number.isFinite(market.bestAskCents) ? roundTo(market.bestAskCents, 1) : null
        },
        _metrics: metrics
      });
    }

    const opportunities = candidates
      .sort((a, b) => {
        if (a.shouldTrade !== b.shouldTrade) return a.shouldTrade ? -1 : 1;
        return (b.executableEdgePct ?? -999) - (a.executableEdgePct ?? -999);
      })
      .slice(0, 12)
      .map(({ _metrics, ...rest }) => rest);

    const topTrade = opportunities.find((op) => op.shouldTrade) || opportunities[0] || null;
    const topFailures = candidates
      .filter((c) => !c.shouldTrade)
      .map((c) => c._metrics?.failReason)
      .filter(Boolean);
    const failCounts = topFailures.reduce((acc, key) => {
      acc[key] = (acc[key] || 0) + 1;
      return acc;
    }, {});
    const failTop = Object.entries(failCounts).sort((a, b) => b[1] - a[1])[0];

    traces.push({
      stage: 'LIVE_VIABILITY',
      status: (filteredReasons.impossible > 0 || filteredReasons.inactive > 0) ? 'DISCARDED' : 'KEPT',
      reason: filteredReasons.impossible > 0
        ? 'Buckets imposibles filtrados por máxima ya observada (truncado Polymarket)'
        : 'Sin buckets imposibles por observación actual',
      details: [
        realH.key === 'today' && Number.isFinite(observedMaxTodayC)
          ? `Obs max hoy ${roundTo(observedMaxTodayC, 1)}°C`
          : 'Obs max no aplica (horizonte futuro)',
        `Imposibles ${filteredReasons.impossible}`,
        `Inactivos ${filteredReasons.inactive}`
      ]
    });

    traces.push({
      stage: 'DOMINANCE',
      status: dominantLockMarketId ? 'DISCARDED' : 'KEPT',
      reason: dominantLockMarketId
        ? 'Bucket dominante >=95% YES: se ocultan alternativas'
        : 'Sin dominancia extrema aplicable',
      details: [
        `Hora local ${cloned.localTime}`,
        `Filtrados por dominancia ${filteredReasons.dominance}`
      ]
    });

    traces.push({
      stage: 'EXECUTION_CONTROL',
      status: opportunities.some((op) => op.shouldTrade) ? 'KEPT' : 'DISCARDED',
      reason: opportunities.some((op) => op.shouldTrade)
        ? `Mercados operables detectados (${opportunities.filter((op) => op.shouldTrade).length})`
        : `Señal base PASS: ${failTop?.[0] || 'ventaja ejecutable insuficiente'}`,
      details: [
        `Buckets reales ${markets.length}`,
        `Evaluados ${candidates.length}`,
        `Visibles ${opportunities.length}`,
        `BET ${opportunities.filter((op) => op.shouldTrade).length}`
      ]
    });

    const statusMessage = opportunities.length === 0
      ? (realH.key === 'today' ? 'Sin oportunidades en Hoy' : `Sin oportunidades en ${realH.label}`)
      : `${opportunities.length} oportunidad(es) visible(s)`;

    const horizonOut = {
      ...baseH,
      key: realH.key,
      label: realH.label,
      targetDate: realH.targetDate,
      metarAvailable: realH.key === 'today',
      opportunities,
      referenceTop: topTrade ? {
        ...topTrade,
        horizonKey: realH.key,
        horizonLabel: realH.label
      } : null,
      statusMessage,
      decisionTrace: traces
    };

    return horizonOut;
  });

  const allOps = cloned.horizons.flatMap((h) => (h.opportunities || []).map((op) => ({
    ...op,
    horizonKey: h.key,
    horizonLabel: h.label,
    targetDate: h.targetDate
  })));
  const topGlobal = allOps
    .slice()
    .sort((a, b) => {
      if (a.shouldTrade !== b.shouldTrade) return a.shouldTrade ? -1 : 1;
      return (b.executableEdgePct ?? -999) - (a.executableEdgePct ?? -999);
    })[0] || null;
  const topToday = allOps
    .filter((op) => op.horizonKey === 'today')
    .sort((a, b) => {
      if (a.shouldTrade !== b.shouldTrade) return a.shouldTrade ? -1 : 1;
      return (b.executableEdgePct ?? -999) - (a.executableEdgePct ?? -999);
    })[0] || null;

  const availableKeys = cloned.isClosedBySchedule
    ? []
    : cloned.horizons.filter((h) => (h.opportunities || []).length > 0).map((h) => h.key);
  const tagMap = { today: 'H', tomorrow: 'M', dayAfter: 'P' };
  const tag = availableKeys.map((k) => tagMap[k]).join('');
  cloned.horizonAvailability = {
    ...(cloned.horizonAvailability || {}),
    keys: availableKeys,
    tag: tag && tag !== 'H' ? `[${tag}]` : ''
  };

  const topGlobalForSummary = cloned.isClosedBySchedule ? null : topGlobal;
  const topTodayForSummary = cloned.isClosedBySchedule ? null : topToday;

  cloned.topSummary = topGlobalForSummary ? {
    horizonKey: topGlobalForSummary.horizonKey,
    horizonLabel: topGlobalForSummary.horizonLabel,
    direction: topGlobalForSummary.direction,
    actionSide: topGlobalForSummary.actionSide,
    signal: topGlobalForSummary.signal,
    executableEdgePct: topGlobalForSummary.executableEdgePct,
    shortText: `${topGlobalForSummary.direction} · BET ${topGlobalForSummary.actionSide} ${topGlobalForSummary.executableEdgePct.toFixed(1)}%`,
    tickerText: `${cloned.city.name}${horizonTickerSuffix(topGlobalForSummary.horizonKey)} ${topGlobalForSummary.direction} ${topGlobalForSummary.actionSide} ${topGlobalForSummary.executableEdgePct.toFixed(1)}%`
  } : null;

  cloned.topTodaySummary = topTodayForSummary ? {
    horizonKey: topTodayForSummary.horizonKey,
    horizonLabel: topTodayForSummary.horizonLabel,
    direction: topTodayForSummary.direction,
    actionSide: topTodayForSummary.actionSide,
    signal: topTodayForSummary.signal,
    executableEdgePct: topTodayForSummary.executableEdgePct,
    shortText: `${topTodayForSummary.direction} · BET ${topTodayForSummary.actionSide} ${topTodayForSummary.executableEdgePct.toFixed(1)}%`
  } : null;

  const buildStrategySummary = (strategyKey) => {
    const stratOps = cloned.isClosedBySchedule
      ? []
      : allOps.filter((op) => op?.strategyEligible?.[strategyKey]);
    const stratTop = stratOps
      .slice()
      .sort((a, b) => (b.executableEdgePct ?? -999) - (a.executableEdgePct ?? -999))[0] || null;
    const stratTopToday = stratOps
      .filter((op) => op.horizonKey === 'today')
      .slice()
      .sort((a, b) => (b.executableEdgePct ?? -999) - (a.executableEdgePct ?? -999))[0] || null;
    const stratKeys = Array.from(new Set(stratOps.map((op) => op.horizonKey)));
    const stratTag = stratKeys.map((k) => tagMap[k]).join('');
    return {
      horizonAvailability: {
        keys: stratKeys,
        tag: stratTag && stratTag !== 'H' ? `[${stratTag}]` : ''
      },
      topSummary: stratTop ? {
        horizonKey: stratTop.horizonKey,
        horizonLabel: stratTop.horizonLabel,
        direction: stratTop.direction,
        actionSide: stratTop.actionSide,
        signal: stratTop.signal,
        executableEdgePct: stratTop.executableEdgePct,
        shortText: `${stratTop.direction} · BET ${stratTop.actionSide} ${stratTop.executableEdgePct.toFixed(1)}%`,
        tickerText: `${cloned.city.name}${horizonTickerSuffix(stratTop.horizonKey)} ${stratTop.direction} ${stratTop.actionSide} ${stratTop.executableEdgePct.toFixed(1)}%`
      } : null,
      topTodaySummary: stratTopToday ? {
        horizonKey: stratTopToday.horizonKey,
        horizonLabel: stratTopToday.horizonLabel,
        direction: stratTopToday.direction,
        actionSide: stratTopToday.actionSide,
        signal: stratTopToday.signal,
        executableEdgePct: stratTopToday.executableEdgePct,
        shortText: `${stratTopToday.direction} · BET ${stratTopToday.actionSide} ${stratTopToday.executableEdgePct.toFixed(1)}%`
      } : null
    };
  };

  cloned.strategyView = {
    conservadora: buildStrategySummary('conservadora'),
    agresiva: buildStrategySummary('agresiva')
  };

  return cloned;
}

function toCitySummaryPayload(cityDetail) {
  return {
    id: cityDetail.id,
    city: deepClone(cityDetail.city),
    source: cityDetail.source,
    generatedAtUtc: cityDetail.generatedAtUtc,
    localTime: cityDetail.localTime,
    isClosedBySchedule: !!cityDetail.isClosedBySchedule,
    horizonAvailability: deepClone(cityDetail.horizonAvailability || { keys: [], tag: '' }),
    summary: deepClone(cityDetail.summary || {}),
    topSummary: cityDetail.topSummary ? deepClone(cityDetail.topSummary) : null,
    topTodaySummary: cityDetail.topTodaySummary ? deepClone(cityDetail.topTodaySummary) : null,
    strategyView: deepClone(cityDetail.strategyView || {}),
    warnings: deepClone(cityDetail.warnings || [])
  };
}

function overlayCitySummary(baseCity, metarSnapshot, controlSnapshot = null, forecastSnapshot = null) {
  const city = deepClone(baseCity);
  city.localTime = nowLocalTimeString(city.city.zoneId);
  city.generatedAtUtc = new Date().toISOString();
  const localHour = parseLocalHour(city.localTime);
  if (Number.isFinite(localHour)) {
    city.isClosedBySchedule = localHour >= 18;
  }

  if (!metarSnapshot?.current || metarSnapshot.current.tempC == null) {
    city.source = 'MOCK';
    city.warnings = [...(city.warnings || []), `METAR live no disponible: ${metarSnapshot?.error || 'sin datos'}`];
    return city;
  }

  const displayUnit = city.city.displayUnit;
  const currentTempC = metarSnapshot.current.tempC;
  const previousTempC = metarSnapshot.previous?.tempC ?? null;
  const currentDisplay = toTempDisplays(currentTempC, displayUnit);
  const deltaDisplay = toDeltaDisplay(toDisplayUnitDelta(currentTempC, previousTempC, displayUnit), displayUnit);

  city.summary = city.summary || {};
  city.summary.metar = currentDisplay;
  city.summary.delta = deltaDisplay;

  if (controlSnapshot?.tempC != null) {
    city.summary.station = toTempDisplays(controlSnapshot.tempC, displayUnit);
  } else if (controlSnapshot?.error) {
    city.warnings = [...(city.warnings || []), `Estación control live no disponible: ${controlSnapshot.error}`];
  }

  const todayIso = todayIsoInZone(city.city.zoneId);
  const mmTodayC = forecastSnapshot?.dailyMmC?.[todayIso];
  const mmaForecast = computeWeightedMmaFromForecast(city.id, forecastSnapshot);
  const mmaTodayC = mmaForecast?.dailyMmaC?.[todayIso];
  if (Number.isFinite(mmTodayC)) {
    city.summary.mm = {
      ...(city.summary.mm || {}),
      ...toTempDisplays(mmTodayC, displayUnit)
    };
  } else if (forecastSnapshot?.error) {
    city.warnings = [...(city.warnings || []), `MM live (Open-Meteo) no disponible: ${forecastSnapshot.error}`];
  }

  if (Number.isFinite(mmaTodayC)) {
    city.summary.mma = {
      ...(city.summary.mma || {}),
      ...toTempDisplays(mmaTodayC, displayUnit),
      status: 'READY'
    };
    city.premium = {
      ...(city.premium || {}),
      dominantModel: getOpenMeteoModelLabel(mmaForecast.dominantModelKey),
      dominantWeightPct: mmaForecast.dominantWeightPct ?? city.premium?.dominantWeightPct ?? null,
      source: MMA_WEIGHTS_VERSION,
      cacheStatus: 'CACHED',
      updatedAtLocal: formatLocalDateTime(new Date().toISOString(), city.city.zoneId) || city.premium?.updatedAtLocal
    };
  } else if (forecastSnapshot?.error) {
    city.warnings = [...(city.warnings || []), `MMA web (pesos backend) no disponible: ${forecastSnapshot.error}`];
  }

  const controlTempC = controlSnapshot?.tempC ?? null;
  const mmC = displayPairToC(city.summary.mm, displayUnit);
  const mmaC = displayPairToC(city.summary.mma, displayUnit);
  const observedMaxC = [currentTempC, controlTempC].filter((v) => Number.isFinite(v));
  if (observedMaxC.length) {
    const maxObserved = Math.max(...observedMaxC);
    if (Number.isFinite(mmC)) city.summary.mm.invalidToday = mmC < maxObserved - 0.4;
    if (Number.isFinite(mmaC)) city.summary.mma.invalidToday = mmaC < maxObserved - 0.4;
  }

  const hasWu = controlSnapshot?.tempC != null;
  const hasOpenMeteoMm = Number.isFinite(mmTodayC);
  const hasOpenMeteoMma = Number.isFinite(mmaTodayC);
  city.source = hasWu && hasOpenMeteoMm ? 'HYBRID_METAR_WU_OM' : hasWu ? 'HYBRID_METAR_WU' : hasOpenMeteoMm ? 'HYBRID_METAR_OM' : 'HYBRID_METAR';
  if (hasOpenMeteoMma) {
    city.source = `${city.source}_MMA`;
  }
  city.liveOverlay = {
    metar: {
      applied: true,
      sourceUrl: metarSnapshot.sourceUrl,
      currentObservedAtUtc: metarSnapshot.current.observedAt,
      previousObservedAtUtc: metarSnapshot.previous?.observedAt || null
    }
  };
  if (controlSnapshot) {
    city.liveOverlay.control = {
      applied: controlSnapshot.tempC != null,
      sourceUrl: controlSnapshot.sourceUrl || null,
      sourceKind: controlSnapshot.sourceKind || null,
      error: controlSnapshot.error || null
    };
  }
  if (forecastSnapshot) {
    city.liveOverlay.forecast = {
      applied: hasOpenMeteoMm,
      sourceUrl: forecastSnapshot.sourceUrl || null,
      models: forecastSnapshot.models || [],
      todayIso,
      todayMmC: Number.isFinite(mmTodayC) ? mmTodayC : null,
      todayMmaC: Number.isFinite(mmaTodayC) ? mmaTodayC : null,
      dailyMmCByDate: forecastSnapshot?.dailyMmC || {},
      dailyMmaCByDate: mmaForecast?.dailyMmaC || {},
      mmaWeightsVersion: mmaForecast?.source || null,
      mmaWeightsByModel: mmaForecast?.normalizedWeightsByModel || {},
      mmaDominantModelKey: mmaForecast?.dominantModelKey || null,
      error: forecastSnapshot.error || null
    };
  }
  return city;
}

function overlayCityDetail(baseCity, metarSnapshot, controlSnapshot = null, forecastSnapshot = null) {
  const city = overlayCitySummary(baseCity, metarSnapshot, controlSnapshot, forecastSnapshot);
  if (!metarSnapshot?.current || metarSnapshot.current.tempC == null) {
    return city;
  }

  const displayUnit = city.city.displayUnit;
  const currentDisplay = toTempDisplays(metarSnapshot.current.tempC, displayUnit);
  const prevDisplay = metarSnapshot.previous?.tempC != null
    ? toTempDisplays(metarSnapshot.previous.tempC, displayUnit)
    : { primary: '--', secondary: '--' };

  city.metarDetail = city.metarDetail || {};
  city.metarDetail.metarCurrent = currentDisplay;
  city.metarDetail.metarPrevious = prevDisplay;
  city.metarDetail.observedAtLocal = formatLocalDateTime(metarSnapshot.current.observedAt, city.city.zoneId) || city.metarDetail.observedAtLocal;
  city.metarDetail.previousObservedAtLocal = formatLocalDateTime(metarSnapshot.previous?.observedAt, city.city.zoneId) || city.metarDetail.previousObservedAtLocal;
  city.metarDetail.previousAgeHours = hoursAgoFromInstant(metarSnapshot.previous?.observedAt) ?? city.metarDetail.previousAgeHours;
  city.metarDetail.metarSourceUrl = metarSnapshot.sourceUrl;

  if (controlSnapshot?.tempC != null) {
    city.metarDetail.stationControl = toTempDisplays(controlSnapshot.tempC, displayUnit);
    city.metarDetail.controlStationSourceUrl = controlSnapshot.sourceUrl || city.metarDetail.controlStationSourceUrl;
  }

  const observedCandidatesC = [metarSnapshot.current.tempC, controlSnapshot?.tempC].filter((v) => Number.isFinite(v));
  if (observedCandidatesC.length) {
    const observedMaxC = Math.max(...observedCandidatesC);
    city.metarDetail.observedMax = {
      primary: toTempDisplays(observedMaxC, displayUnit).primary,
      secondary: toTempDisplays(observedMaxC, displayUnit).secondary
    };
  }

  return city;
}

export function createHybridMetarProvider({ baseProvider = createMockProvider() } = {}) {
  const DETAIL_CACHE_TTL_MS = 20_000;
  const detailCache = new Map();
  const detailInflight = new Map();

  async function buildComputedCityDetail(cityId) {
    const baseCity = await baseProvider.getCityDetail(cityId);
    if (!baseCity) return null;
    const [metarSnapshot, controlSnapshot, forecastSnapshot] = await Promise.all([
      fetchMetarSnapshot(baseCity.city.metarCode),
      fetchWundergroundControlSnapshot(cityId, baseCity.city.displayUnit),
      fetchOpenMeteoMmSnapshot(cityId, baseCity.city.zoneId)
    ]);
    let cityDetail = overlayCityDetail(baseCity, metarSnapshot, controlSnapshot, forecastSnapshot);
    try {
      const board = await fetchPolymarketBoardsForCity({
        id: cityDetail.city.id,
        name: cityDetail.city.name,
        zoneId: cityDetail.city.zoneId
      });
      cityDetail = applyPolymarketOverlayToCityDetail(cityDetail, board);
      cityDetail = buildRealDecisionHorizons(cityDetail, board);
      if (cityDetail?.polymarketReal?.source === 'POLYMARKET_GAMMA') {
        cityDetail.source = `${cityDetail.source}_POLY`;
      }
    } catch (error) {
      cityDetail.warnings = [...(cityDetail.warnings || []), `Polymarket (lectura) no disponible: ${String(error?.message || error)}`];
    }
    return cityDetail;
  }

  async function getComputedCityDetailCached(cityId) {
    const now = Date.now();
    const cached = detailCache.get(cityId);
    if (cached && now - cached.ts <= DETAIL_CACHE_TTL_MS) {
      return deepClone(cached.value);
    }
    if (detailInflight.has(cityId)) {
      const inflightValue = await detailInflight.get(cityId);
      return inflightValue ? deepClone(inflightValue) : null;
    }
    const p = (async () => {
      const value = await buildComputedCityDetail(cityId);
      if (value) {
        detailCache.set(cityId, { ts: Date.now(), value: deepClone(value) });
      }
      return value;
    })();
    detailInflight.set(cityId, p);
    try {
      const value = await p;
      return value ? deepClone(value) : null;
    } finally {
      detailInflight.delete(cityId);
    }
  }

  return {
    sourceId: 'hybrid-metar',

    async health() {
      return {
        ok: true,
        source: 'HYBRID_METAR',
        notes: 'Hora local + METAR live overlay con fallback a mock'
      };
    },

    async getMeta() {
      const meta = await baseProvider.getMeta();
      return {
        ...meta,
        source: 'HYBRID_METAR',
        generatedAtUtc: new Date().toISOString(),
        notes: [
          ...(meta.notes || []),
          'Provider híbrido web: METAR + estación control + MM/MMA (Open-Meteo) en live.',
          'DETALLE web evalúa buckets reales de Polymarket con motor web (Exec/Fill/Coste/Trazabilidad).',
          'CIUDADES/Ticker reutilizan topSummary derivado del mismo cálculo (cache corto por ciudad).'
        ]
      };
    },

    async listCityIds() {
      return baseProvider.listCityIds();
    },

    async listCityManifest() {
      return baseProvider.listCityManifest();
    },

    async getCitySummary(cityId) {
      const detail = await getComputedCityDetailCached(cityId);
      return detail ? toCitySummaryPayload(detail) : null;
    },

    async listCitiesSummary() {
      const ids = await baseProvider.listCityIds();
      const results = [];
      for (const id of ids) {
        results.push(await this.getCitySummary(id));
      }
      return results.filter(Boolean);
    },

    async getCityDetail(cityId) {
      return getComputedCityDetailCached(cityId);
    }
  };
}
