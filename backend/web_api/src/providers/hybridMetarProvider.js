import { createMockProvider } from './mockProvider.js';
import { applyPolymarketOverlayToCityDetail, fetchPolymarketBoardsForCity } from './polymarketReadOnly.js';
import {
  MMA_WEIGHTS_VERSION,
  getCityMmaWeights,
  getOpenMeteoModelLabel,
  normalizeWeightsForAvailable
} from './mmaWeights.js';
import {
  MMA_PREMIUM_ENGINE_VERSION,
  getPremiumWeightsBundleForCity,
  providerWeightsToModelWeights
} from './mmaPremiumEngine.js';

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

const OPEN_METEO_MODELS_MM = ['ecmwf_ifs', 'ecmwf_ifs025', 'ecmwf_aifs025_single', 'gfs_global'];
const FORECAST_INVALID_EPSILON_C = 0.001;
const POLYTEMP_VALID_TEMP_MIN_C = -80.0;
const POLYTEMP_VALID_TEMP_MAX_C = 65.0;
const PREMIUM_DOMINANT_MIN_SHARE = 0.22;
const PREMIUM_DOMINANT_TARGET_SHARE = 0.35;
const PREMIUM_DOMINANT_MIN_RATIO = 1.03;
const PREMIUM_DOMINANT_TARGET_RATIO = 1.20;
const PREMIUM_DOMINANT_BASE_ALPHA = 0.45;
const PREMIUM_DOMINANT_EXTRA_ALPHA = 0.30;
const PREMIUM_DOMINANT_MAX_ALPHA = 0.75;

const FORECAST_BASE_WEIGHTS_BY_SOURCE_ID = Object.freeze({
  'openmeteo-ifs025': 1.33,
  'openmeteo-ifs': 1.30,
  'openmeteo-aifs': 1.25,
  'weather-gov': 1.10,
  'windy-gfs': 1.05,
  'openweather': 0.85
});

function openMeteoModelToSourceId(modelKey) {
  switch (String(modelKey || '')) {
    case 'ecmwf_ifs':
      return 'openmeteo-ifs';
    case 'ecmwf_ifs025':
      return 'openmeteo-ifs025';
    case 'ecmwf_aifs025_single':
      return 'openmeteo-aifs';
    case 'gfs_global':
      return 'windy-gfs'; // proxy aproximado en web para mantener escala de pesos del móvil
    default:
      return String(modelKey || '');
  }
}

function forecastBaseWeightForSourceId(sourceId) {
  return FORECAST_BASE_WEIGHTS_BY_SOURCE_ID[sourceId] ?? 0.9;
}

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

function dayDiffIso(fromIso, toIso) {
  if (typeof fromIso !== 'string' || typeof toIso !== 'string') return 0;
  const a = Date.parse(`${fromIso}T00:00:00Z`);
  const b = Date.parse(`${toIso}T00:00:00Z`);
  if (!Number.isFinite(a) || !Number.isFinite(b)) return 0;
  return Math.round((b - a) / 86400000);
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

function extractInlineScriptsText(html) {
  if (typeof html !== 'string' || !html) return '';
  const regex = /<script\b[^>]*>([\s\S]*?)<\/script>/gi;
  let match;
  let joined = '';
  while ((match = regex.exec(html)) !== null) {
    if (match[1]) joined += `${match[1]}\n`;
  }
  return joined;
}

function parseWundergroundUnitFromContext(context) {
  const ctx = String(context || '');
  // Paridad con app móvil: usar solo pistas `units=e/m` en el contexto inmediato.
  // `temperatureUnit` aparece mezclado en payloads vecinos y puede contaminar la unidad.
  if (/(?:[?&]|\\u0026)units=(?:e|imperial)\b/i.test(ctx) || /"units"\s*:\s*"e"/i.test(ctx)) return 'F';
  if (/(?:[?&]|\\u0026)units=(?:m|metric)\b/i.test(ctx) || /"units"\s*:\s*"m"/i.test(ctx)) return 'C';
  return null;
}

function buildWundergroundEmbeddedScore(context, metarCode, unit, key = null) {
  const ctx = String(context || '');
  const code = String(metarCode || '').toUpperCase();
  let score = 0;
  if (code && new RegExp(`icaoCode=${code}`, 'i').test(ctx)) score += 5;
  if (code && new RegExp(`"icaoCode"\\s*:\\s*"${code}"`, 'i').test(ctx)) score += 5;
  if (/v3\/wx\/observations\/current/i.test(ctx)) score += 2;
  if (key && /temperatureMaxSince7Am/i.test(key)) score += 3;
  if (key && /temperatureMax24Hour/i.test(key)) score += 2;
  if (parseWundergroundUnitFromContext(ctx)) score += 2;
  if (/units=/i.test(ctx) || /"units"\s*:/i.test(ctx)) score += 1;
  if (unit === 'C' || unit === 'F') score += 1;
  return score;
}

function buildWundergroundObservationScoreAppParity(context, metarCode, unit) {
  const ctx = String(context || '');
  const code = String(metarCode || '').toUpperCase();
  let score = 0;
  if (code && new RegExp(`icaoCode=${code}`, 'i').test(ctx)) score += 5;
  if (code && new RegExp(`"icaoCode"\\s*:\\s*"${code}"`, 'i').test(ctx)) score += 5;
  if (/v3\/wx\/observations\/current/i.test(ctx)) score += 2;
  if (/temperatureMaxSince7Am/i.test(ctx)) score += 1;
  if (/units=/i.test(ctx)) score += 1;
  if (unit === 'C' || unit === 'F') score += 1;
  return score;
}

function toWuCandidate(value, unit, source, extras = {}) {
  if (!Number.isFinite(value)) return null;
  if (!isPlausibleTemp(value, unit)) return null;
  const valueC = unit === 'C' ? value : fahrenheitToCelsius(value);
  if (!Number.isFinite(valueC)) return null;
  return { value, unit, valueC, source, ...extras };
}

function findWundergroundEmbeddedObservationHighTemp(text, metarCode, preferredUnit, key) {
  const regex = new RegExp(`"${key}"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)`, 'gi');
  const candidates = [];
  let match;
  while ((match = regex.exec(text)) !== null) {
    const value = Number(match[1]);
    const start = Math.max(0, match.index - 1200);
    const end = Math.min(text.length, match.index + 1200);
    const context = text.slice(start, end);
    const unit = parseWundergroundUnitFromContext(context) || inferUnitFromValue(value, preferredUnit);
    const hasIcaoEq = !!(metarCode && new RegExp(`icaoCode=${String(metarCode || '').toUpperCase()}`, 'i').test(context));
    const hasIcaoJson = !!(metarCode && new RegExp(`"icaoCode"\\s*:\\s*"${String(metarCode || '').toUpperCase()}"`, 'i').test(context));
    const candidate = toWuCandidate(value, unit, `embedded-observation-${key}`, {
      // Paridad exacta con app móvil: mismo scoring de candidatos embebidos de observación.
      score: buildWundergroundObservationScoreAppParity(context, metarCode, unit),
      hasIcaoEq,
      hasIcaoJson
    });
    if (candidate) candidates.push(candidate);
  }
  if (!candidates.length) return null;
  candidates.sort((a, b) => (b.score - a.score) || (b.valueC - a.valueC));
  return candidates[0];
}

function findWundergroundHighTempAfterKeyword(text, preferredUnit) {
  const index = String(text || '').search(/High\s*Temp/i);
  if (index < 0) return null;
  const slice = text.slice(index, Math.min(text.length, index + 260));
  const m = slice.match(/(-?\d+(?:\.\d+)?)/);
  if (!m) return null;
  const value = Number(m[1]);
  const unit = inferUnitFromValue(value, preferredUnit);
  return toWuCandidate(value, unit, 'high-temp-keyword');
}

function findWundergroundAnyEmbeddedMaxTemperature(text, preferredUnit, metarCode) {
  const regex = /"(temperatureMaxSince7Am|temperatureMax24Hour|temperatureMax)"\s*:\s*(-?\d+(?:\.\d+)?)/gi;
  const candidates = [];
  let match;
  while ((match = regex.exec(text)) !== null) {
    const key = match[1];
    const value = Number(match[2]);
    const start = Math.max(0, match.index - 120);
    const end = Math.min(text.length, match.index + 120);
    const context = text.slice(start, end);
    const explicitUnit = parseWundergroundUnitFromContext(context);
    const unit = explicitUnit || inferUnitFromValue(value, preferredUnit);
    const candidate = toWuCandidate(value, unit, `embedded-fallback-${key}`, {
      score: buildWundergroundEmbeddedScore(context, metarCode, unit, key) + (explicitUnit ? 1 : 0)
    });
    if (candidate) candidates.push(candidate);
  }
  if (!candidates.length) return null;
  candidates.sort((a, b) => (b.score - a.score) || (b.valueC - a.valueC));
  return candidates[0];
}

function findWundergroundAnyEmbeddedCurrentTemperature(text, preferredUnit, metarCode) {
  const regex = /"temperature"\s*:\s*(-?\d+(?:\.\d+)?)/gi;
  const candidates = [];
  let match;
  while ((match = regex.exec(text)) !== null) {
    const value = Number(match[1]);
    const start = Math.max(0, match.index - 120);
    const end = Math.min(text.length, match.index + 120);
    const context = text.slice(start, end);
    const unit = parseWundergroundUnitFromContext(context) || inferUnitFromValue(value, preferredUnit);
    const candidate = toWuCandidate(value, unit, 'embedded-current-temperature', {
      score: buildWundergroundEmbeddedScore(context, metarCode, unit, 'temperature')
    });
    if (candidate) candidates.push(candidate);
  }
  if (!candidates.length) return null;
  candidates.sort((a, b) => (b.score - a.score) || (b.valueC - a.valueC));
  return candidates[0];
}

function extractWundergroundObservationApiUrls(html, metarCode) {
  const normalized = String(html || '')
    .replace(/\\\//g, '/')
    .replace(/\\u0026/g, '&')
    .replace(/&amp;/gi, '&');

  const regex = /https:\/\/api\.weather\.com\/v3\/wx\/observations\/current\?[^"'\\s>]+/gi;
  const urls = [];
  let match;
  while ((match = regex.exec(normalized)) !== null) {
    urls.push(match[0]);
  }
  return [...new Set(urls)].sort((a, b) => {
    const score = (candidate) => {
      if (metarCode && new RegExp(`icaoCode=${metarCode}`, 'i').test(candidate)) return 2;
      if (/icaoCode=/i.test(candidate)) return 1;
      return 0;
    };
    return score(b) - score(a);
  });
}

function buildWundergroundFallbackObservationUrls(cityId, metarCode) {
  const coords = OPEN_METEO_COORDS_BY_CITY[cityId];
  const base = 'https://api.weather.com/v3/wx/observations/current';
  const apiKey = 'e1f10a1e78da46f5b10a1e78da96f525';
  const urls = [];
  if (metarCode) {
    urls.push(`${base}?apiKey=${apiKey}&language=en-US&units=e&format=json&icaoCode=${encodeURIComponent(metarCode)}`);
    urls.push(`${base}?apiKey=${apiKey}&language=en-US&units=m&format=json&icaoCode=${encodeURIComponent(metarCode)}`);
  }
  if (coords) {
    const geo = `${coords.lat},${coords.lon}`;
    urls.push(`${base}?apiKey=${apiKey}&language=en-US&units=e&format=json&geocode=${encodeURIComponent(geo)}`);
    urls.push(`${base}?apiKey=${apiKey}&language=en-US&units=m&format=json&geocode=${encodeURIComponent(geo)}`);
  }
  return urls;
}

function parseWundergroundObservationJson(rawJson, requestUrl, preferredUnit) {
  let root;
  try {
    root = JSON.parse(rawJson);
  } catch {
    return null;
  }
  const payload = (root && typeof root === 'object' && root.value && typeof root.value === 'object') ? root.value : root;
  if (!payload || typeof payload !== 'object') return null;
  const keys = ['temperatureMaxSince7Am', 'temperatureMax24Hour', 'temperatureMax'];
  let value = null;
  for (const key of keys) {
    const v = firstNumber(payload[key]);
    if (Number.isFinite(v)) {
      value = v;
      break;
    }
  }
  if (!Number.isFinite(value)) return null;
  let unit = null;
  if (/units=e/i.test(requestUrl)) unit = 'F';
  else if (/units=m/i.test(requestUrl)) unit = 'C';
  else unit = inferUnitFromValue(value, preferredUnit);
  return toWuCandidate(value, unit, 'weather-com-observation-api');
}

async function fetchWundergroundObservationMaxFromApi({ html, cityId, metarCode, preferredUnit, refererUrl }) {
  const urls = [
    ...extractWundergroundObservationApiUrls(html, metarCode),
    ...buildWundergroundFallbackObservationUrls(cityId, metarCode)
  ];
  const uniqueUrls = [...new Set(urls)];
  for (const url of uniqueUrls) {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), WUNDERGROUND_TIMEOUT_MS);
    try {
      const response = await fetch(url, {
        signal: controller.signal,
        headers: {
          accept: 'application/json,text/plain,*/*',
          'user-agent': 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) PolyMeteoWeb/1.0',
          referer: refererUrl || 'https://www.wunderground.com/',
          origin: 'https://www.wunderground.com'
        }
      });
      if (!response.ok) continue;
      const raw = await response.text();
      const parsed = parseWundergroundObservationJson(raw, url, preferredUnit);
      if (parsed) return parsed;
    } catch {
      // ignore and continue
    } finally {
      clearTimeout(timeout);
    }
  }
  return null;
}

async function extractWundergroundHighTempActual(html, { cityId, metarCode, displayUnit, pageUrl }) {
  const pageText = stripHtmlToText(html);
  const scriptsJoined = extractInlineScriptsText(html);
  const summaryRaw = parseWundergroundSummaryHighTemp(pageText, displayUnit);
  const summary = summaryRaw
    ? toWuCandidate(summaryRaw.value, summaryRaw.unit, summaryRaw.source)
    : null;

  const embeddedObsCandidates = [
    findWundergroundEmbeddedObservationHighTemp(scriptsJoined, metarCode, displayUnit, 'temperatureMaxSince7Am'),
    findWundergroundEmbeddedObservationHighTemp(scriptsJoined, metarCode, displayUnit, 'temperatureMax24Hour')
  ].filter(Boolean);
  const embeddedObsBest = embeddedObsCandidates
    .slice()
    .sort((a, b) => (b.score ?? 0) - (a.score ?? 0) || b.valueC - a.valueC)[0] || null;

  const apiObservationCandidate = await fetchWundergroundObservationMaxFromApi({
      html,
      cityId,
      metarCode,
      preferredUnit: displayUnit,
      refererUrl: pageUrl
    });

  // En páginas WU con payloads mezclados, los candidatos embebidos pueden venir de otra ciudad.
  // Si el embedded no tiene suficiente evidencia (score bajo) y sí tenemos API de observación
  // explícita (icaoCode/geocode de la ciudad), preferimos la API.
  let primaryObservationCandidate = embeddedObsBest;
  if (apiObservationCandidate) {
    const embeddedScore = embeddedObsBest?.score ?? -1;
    const embeddedHasIcao = !!(embeddedObsBest?.hasIcaoEq || embeddedObsBest?.hasIcaoJson);
    if (!embeddedObsBest || embeddedHasIcao || embeddedScore >= 8) {
      // Si embedded está bien anclado a la ciudad y puntúa alto, mantenemos paridad de orden.
      primaryObservationCandidate = embeddedObsBest;
    } else {
      // Embedded dudoso: priorizar API weather.com de la ciudad.
      primaryObservationCandidate = apiObservationCandidate;
    }
  }

  const currentCandidate =
    primaryObservationCandidate
    || findWundergroundHighTempAfterKeyword(pageText, displayUnit)
    || findWundergroundAnyEmbeddedMaxTemperature(scriptsJoined, displayUnit, metarCode)
    || apiObservationCandidate
    || findWundergroundAnyEmbeddedCurrentTemperature(scriptsJoined, displayUnit, metarCode);

  const chosen = chooseHigherTempCandidate(summary, currentCandidate);
  return { chosen, summary, currentCandidate };
}

function parseControlCurrentFromHeaderText(pageText, preferredUnit) {
  const normalized = String(pageText || '').replace(/\s+/g, ' ').trim();
  const todayIndex = normalized.search(/\bTODAY\b/i);
  const slice = todayIndex >= 0
    ? normalized.slice(Math.max(0, todayIndex - 280), Math.min(normalized.length, todayIndex + 150))
    : normalized.slice(0, 420);
  const regex = /(-?\d+(?:\.\d+)?)\s*(?:°|º)?\s*([CF])?\s*[A-Z0-9\-\s]{3,90}\s+STATION/i;
  const m = slice.match(regex);
  if (!m) return null;
  const value = Number(m[1]);
  if (!Number.isFinite(value)) return null;
  const unit = m[2]
    ? (String(m[2]).toUpperCase() === 'F' ? 'F' : 'C')
    : inferUnitFromValue(value, preferredUnit);
  return toWuCandidate(value, unit, 'control-current-header');
}

function findPwsCurrentFromStructuredText(text, preferredUnit, metarCode) {
  const body = String(text || '');
  const candidates = [];

  const metricRegex = /"metric"\s*:\s*\{[^{}]{0,450}?"temp"\s*:\s*(-?\d+(?:\.\d+)?)/gi;
  let match;
  while ((match = metricRegex.exec(body)) !== null) {
    const value = Number(match[1]);
    const candidate = toWuCandidate(value, 'C', 'pws-current-metric', { score: 7 });
    if (candidate) candidates.push(candidate);
  }

  const imperialRegex = /"imperial"\s*:\s*\{[^{}]{0,450}?"temp"\s*:\s*(-?\d+(?:\.\d+)?)/gi;
  while ((match = imperialRegex.exec(body)) !== null) {
    const value = Number(match[1]);
    const candidate = toWuCandidate(value, 'F', 'pws-current-imperial', { score: 7 });
    if (candidate) candidates.push(candidate);
  }

  const tempCRegex = /"temp_c"\s*:\s*(-?\d+(?:\.\d+)?)/gi;
  while ((match = tempCRegex.exec(body)) !== null) {
    const value = Number(match[1]);
    const candidate = toWuCandidate(value, 'C', 'pws-current-temp_c', { score: 6 });
    if (candidate) candidates.push(candidate);
  }

  const tempFRegex = /"temp_f"\s*:\s*(-?\d+(?:\.\d+)?)/gi;
  while ((match = tempFRegex.exec(body)) !== null) {
    const value = Number(match[1]);
    const candidate = toWuCandidate(value, 'F', 'pws-current-temp_f', { score: 6 });
    if (candidate) candidates.push(candidate);
  }

  const genericCurrent = findWundergroundAnyEmbeddedCurrentTemperature(body, preferredUnit, metarCode);
  if (genericCurrent) {
    candidates.push({ ...genericCurrent, score: Number(genericCurrent.score || 4) });
  }

  if (!candidates.length) return null;
  candidates.sort((a, b) => (b.score - a.score) || (b.valueC - a.valueC));
  return candidates[0];
}

function parsePwsCurrentFromPageText(pageText, preferredUnit) {
  const normalized = String(pageText || '').replace(/\s+/g, ' ').trim();
  const regex = /CURRENT\s*CONDITIONS\s*(-?\d+(?:\.\d+)?)\s*(?:°|º)?\s*([CF])?/i;
  const m = normalized.match(regex);
  if (!m) return null;
  const value = Number(m[1]);
  if (!Number.isFinite(value)) return null;
  const unit = m[2]
    ? (String(m[2]).toUpperCase() === 'F' ? 'F' : 'C')
    : inferUnitFromValue(value, preferredUnit);
  return toWuCandidate(value, unit, 'pws-current-conditions');
}

function parsePwsSummaryHighTemp(pageText, preferredUnit) {
  const normalized = String(pageText || '').replace(/\s+/g, ' ').trim();
  const rowRegex = /Temperature\s*(-?\d+(?:\.\d+)?)\s*(?:°|º)?\s*([CF])?\s*(-?\d+(?:\.\d+)?)\s*(?:°|º)?\s*([CF])?\s*(-?\d+(?:\.\d+)?)/i;
  const row = normalized.match(rowRegex);
  if (row) {
    const high = Number(row[1]);
    const explicit = String(row[2] || '').toUpperCase();
    const unit = explicit === 'F' ? 'F' : explicit === 'C' ? 'C' : inferUnitFromValue(high, preferredUnit);
    const candidate = toWuCandidate(high, unit, 'pws-summary-history');
    if (candidate) return candidate;
  }

  const historyIdx = normalized.search(/Weather\s+History/i);
  if (historyIdx >= 0) {
    const slice = normalized.slice(historyIdx, Math.min(normalized.length, historyIdx + 700));
    const tempRegex = /Temperature\s*(-?\d+(?:\.\d+)?)\s*(?:°|º)?\s*([CF])?/i;
    const m = slice.match(tempRegex);
    if (m) {
      const high = Number(m[1]);
      const explicit = String(m[2] || '').toUpperCase();
      const unit = explicit === 'F' ? 'F' : explicit === 'C' ? 'C' : inferUnitFromValue(high, preferredUnit);
      const candidate = toWuCandidate(high, unit, 'pws-summary-slice');
      if (candidate) return candidate;
    }
  }
  return null;
}

function toStationTemp(valueCandidate) {
  if (!valueCandidate || !Number.isFinite(valueCandidate.valueC)) {
    return { tempC: null, tempF: null, error: 'No se pudo obtener dato' };
  }
  return {
    tempC: valueCandidate.valueC,
    tempF: celsiusToFahrenheit(valueCandidate.valueC),
    error: null
  };
}

function wuCandidateValueC(candidate) {
  if (!candidate) return null;
  if (Number.isFinite(candidate.valueC)) return candidate.valueC;
  if (Number.isFinite(candidate.value)) {
    return String(candidate.unit || 'C').toUpperCase() === 'F'
      ? fahrenheitToCelsius(candidate.value)
      : candidate.value;
  }
  return null;
}

async function fetchWundergroundStationComparisonSnapshot(cityId, displayUnit, metarCode) {
  const cfg = WUNDERGROUND_BY_CITY[cityId];
  if (!cfg) {
    return {
      controlSourceUrl: null,
      nearbySourceUrl: null,
      controlCurrent: { tempC: null, tempF: null, error: 'No se pudo obtener dato' },
      controlMax: { tempC: null, tempF: null, error: 'No se pudo obtener dato' },
      nearbyCurrent: { tempC: null, tempF: null, error: 'No se pudo obtener dato' },
      nearbyMax: { tempC: null, tempF: null, error: 'No se pudo obtener dato' },
      deviationCurrentC: null,
      deviationMaxC: null,
      isCurrentDeviationReliable: false,
      isMaxDeviationReliable: false,
      warnings: ['Ciudad sin URL Wunderground configurada'],
      error: 'No se pudo obtener dato'
    };
  }

  const warnings = [];

  const [controlHtml, nearbyHtml] = await Promise.all([
    fetchHtml(cfg.controlUrl),
    fetchHtml(cfg.pwsUrl)
  ]);

  let controlCurrentCandidate = null;
  let controlMaxCandidate = null;
  let controlSourceUrl = controlHtml.url || cfg.controlUrl;

  if (controlHtml.ok) {
    const pageText = stripHtmlToText(controlHtml.body);
    const scriptsText = extractInlineScriptsText(controlHtml.body);
    const extracted = await extractWundergroundHighTempActual(controlHtml.body, {
      cityId,
      metarCode,
      displayUnit,
      pageUrl: controlHtml.url || cfg.controlUrl
    });
    controlCurrentCandidate =
      parseControlCurrentFromHeaderText(pageText, displayUnit)
      || findWundergroundAnyEmbeddedCurrentTemperature(scriptsText, displayUnit, metarCode);
    controlMaxCandidate =
      extracted.summary
      || extracted.currentCandidate
      || extracted.chosen
      || parseWundergroundSummaryHighTemp(pageText, displayUnit);
    const controlCurrentC = wuCandidateValueC(controlCurrentCandidate);
    const controlMaxC = wuCandidateValueC(controlMaxCandidate);
    if (Number.isFinite(controlCurrentC) && Number.isFinite(controlMaxC) && (controlCurrentC - controlMaxC) > 5) {
      warnings.push(`Control: temperatura actual descartada por inconsistencia (+${(controlCurrentC - controlMaxC).toFixed(1)}°C sobre máxima)`);
      controlCurrentCandidate = null;
    }
    if (!controlCurrentCandidate) warnings.push('Control: no se detectó temperatura actual');
    if (!controlMaxCandidate) warnings.push('Control: no se detectó máxima diaria');
  } else {
    warnings.push(`Control: ${controlHtml.error || 'sin respuesta HTML'}`);
    controlSourceUrl = cfg.controlUrl;
  }

  let nearbyCurrentCandidate = null;
  let nearbyMaxCandidate = null;
  let nearbySourceUrl = nearbyHtml.url || cfg.pwsUrl;

  if (nearbyHtml.ok) {
    const pageText = stripHtmlToText(nearbyHtml.body);
    const scriptsText = extractInlineScriptsText(nearbyHtml.body);
    const extracted = await extractWundergroundHighTempActual(nearbyHtml.body, {
      cityId,
      metarCode,
      displayUnit,
      pageUrl: nearbyHtml.url || cfg.pwsUrl
    });
    nearbyCurrentCandidate =
      parsePwsCurrentFromPageText(pageText, displayUnit)
      || findPwsCurrentFromStructuredText(scriptsText, displayUnit, metarCode);
    nearbyMaxCandidate =
      parsePwsSummaryHighTemp(pageText, displayUnit)
      || extracted.summary
      || extracted.currentCandidate
      || extracted.chosen;
    const nearbyCurrentC = wuCandidateValueC(nearbyCurrentCandidate);
    const nearbyMaxC = wuCandidateValueC(nearbyMaxCandidate);
    if (Number.isFinite(nearbyCurrentC) && Number.isFinite(nearbyMaxC) && (nearbyCurrentC - nearbyMaxC) > 5) {
      warnings.push(`Cercana: temperatura actual descartada por inconsistencia (+${(nearbyCurrentC - nearbyMaxC).toFixed(1)}°C sobre máxima)`);
      nearbyCurrentCandidate = null;
    }
    if (!nearbyCurrentCandidate) warnings.push('Cercana: no se detectó temperatura actual');
    if (!nearbyMaxCandidate) warnings.push('Cercana: no se detectó máxima diaria');
  } else {
    warnings.push(`Cercana: ${nearbyHtml.error || 'sin respuesta HTML'}`);
    nearbySourceUrl = cfg.pwsUrl;
  }

  const controlCurrent = toStationTemp(controlCurrentCandidate);
  const controlMax = toStationTemp(controlMaxCandidate);
  const nearbyCurrent = toStationTemp(nearbyCurrentCandidate);
  const nearbyMax = toStationTemp(nearbyMaxCandidate);

  const deviationCurrentC = Number.isFinite(controlCurrent.tempC) && Number.isFinite(nearbyCurrent.tempC)
    ? controlCurrent.tempC - nearbyCurrent.tempC
    : null;
  const deviationMaxC = Number.isFinite(controlMax.tempC) && Number.isFinite(nearbyMax.tempC)
    ? controlMax.tempC - nearbyMax.tempC
    : null;

  const isCurrentDeviationReliable = Number.isFinite(deviationCurrentC) ? Math.abs(deviationCurrentC) <= 5 : false;
  const isMaxDeviationReliable = Number.isFinite(deviationMaxC) ? Math.abs(deviationMaxC) <= 5 : false;

  if (Number.isFinite(deviationCurrentC) && !isCurrentDeviationReliable) {
    warnings.push(`Desviación ACTUAL fuera de rango (>5°C): ${deviationCurrentC.toFixed(1)}°C`);
  }
  if (Number.isFinite(deviationMaxC) && !isMaxDeviationReliable) {
    warnings.push(`Desviación MAXIMA fuera de rango (>5°C): ${deviationMaxC.toFixed(1)}°C`);
  }

  const allMissing = [controlCurrent.tempC, controlMax.tempC, nearbyCurrent.tempC, nearbyMax.tempC].every((x) => !Number.isFinite(x));

  return {
    controlSourceUrl,
    nearbySourceUrl,
    controlCurrent,
    controlMax,
    nearbyCurrent,
    nearbyMax,
    deviationCurrentC: Number.isFinite(deviationCurrentC) ? deviationCurrentC : null,
    deviationMaxC: Number.isFinite(deviationMaxC) ? deviationMaxC : null,
    isCurrentDeviationReliable,
    isMaxDeviationReliable,
    warnings,
    error: allMissing ? 'No se pudo obtener dato' : null
  };
}

function chooseHigherTempCandidate(...candidates) {
  const normalized = candidates.filter(Boolean).map((c) => {
    const valueC = c.valueC ?? (c.unit === 'C' ? c.value : fahrenheitToCelsius(c.value));
    return { ...c, valueC };
  });
  if (!normalized.length) return null;
  return normalized.sort((a, b) => b.valueC - a.valueC)[0];
}

function wundergroundCandidatePriority(sourceKind) {
  const source = String(sourceKind || '');
  if (source.startsWith('summary-')) return 300;
  if (source === 'pws-current') return 200;
  if (source.startsWith('embedded-')) return 100;
  return 0;
}

function choosePreferredWundergroundCandidate(candidates) {
  const normalized = candidates
    .filter(Boolean)
    .map((entry) => ({
      ...entry,
      valueC: entry.valueC ?? (entry.unit === 'C' ? entry.value : fahrenheitToCelsius(entry.value)),
      priority: wundergroundCandidatePriority(entry.source)
    }))
    .filter((entry) => Number.isFinite(entry.valueC));

  if (!normalized.length) return null;

  normalized.sort((a, b) => {
    if (b.priority !== a.priority) return b.priority - a.priority;
    return b.valueC - a.valueC;
  });
  return normalized[0];
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

async function fetchWundergroundControlSnapshot(cityId, displayUnit, metarCode) {
  const cfg = WUNDERGROUND_BY_CITY[cityId];
  if (!cfg) return { tempC: null, sourceUrl: null, error: 'Ciudad sin URL Wunderground configurada' };

  const errors = [];
  const attempts = [
    { url: cfg.controlUrl, kind: 'control' },
    { url: cfg.pwsUrl, kind: 'pws' }
  ].filter((a) => !!a.url);

  const lateFallbackCandidates = [];
  for (const attempt of attempts) {
    const { url, kind } = attempt;
    const htmlResp = await fetchHtml(url);
    if (!htmlResp.ok) {
      errors.push(`${url} -> ${htmlResp.error}`);
      continue;
    }
    const extracted = await extractWundergroundHighTempActual(htmlResp.body, {
      cityId,
      metarCode,
      displayUnit,
      pageUrl: htmlResp.url || url
    });
    const summary = extracted.summary;
    const embedded = extracted.currentCandidate;

    // Paridad con app móvil / criterio de negocio:
    // 1) Si la URL de control ofrece Summary (High Temp Actual), usarla (máxima oficial de control).
    // 2) Si no hay Summary, intentar candidates de esa misma URL.
    // 3) Solo si no se pudo, probar la siguiente URL.
    if (kind === 'control' && summary) {
      return {
        tempC: summary.valueC ?? (summary.unit === 'C' ? summary.value : fahrenheitToCelsius(summary.value)),
        sourceUrl: htmlResp.url || url,
        sourceKind: summary.source,
        error: null
      };
    }

    const localCandidates = [summary, embedded]
      .filter(Boolean)
      .map((candidate) => ({
        ...candidate,
        sourceUrl: htmlResp.url || url
      }));
    const localChosen = choosePreferredWundergroundCandidate(localCandidates);
    if (localChosen) {
      // Para la URL de control, si no hubo Summary, guardamos embedded-like fallback y
      // permitimos que la siguiente URL ofrezca un candidato mejor.
      if (kind === 'control' && (
        String(localChosen.source || '').startsWith('embedded-') ||
        String(localChosen.source || '') === 'high-temp-keyword' ||
        String(localChosen.source || '') === 'weather-com-observation-api'
      )) {
        lateFallbackCandidates.push(localChosen);
      } else {
        return {
          tempC: localChosen.valueC,
          sourceUrl: localChosen.sourceUrl || htmlResp.url || url,
          sourceKind: localChosen.source,
          error: null
        };
      }
    } else {
      errors.push(`${url} -> sin maxima detectable`);
    }
  }

  const chosen = choosePreferredWundergroundCandidate(lateFallbackCandidates);
  if (chosen) {
    return {
      tempC: chosen.valueC,
      sourceUrl: chosen.sourceUrl || cfg.controlUrl || cfg.pwsUrl || null,
      sourceKind: chosen.source,
      error: null
    };
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
    models: OPEN_METEO_MODELS_MM.join(',')
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
      const entries = keys
        .map((key) => ({
          modelKey: key.replace('temperature_2m_max_', ''),
          valueC: json.daily[key]?.[i]
        }))
        .filter((e) => typeof e.valueC === 'number' && Number.isFinite(e.valueC));
      if (!entries.length) continue;
      const mm = computePolyTempLikeFromModelEntries(entries, {});
      if (Number.isFinite(mm.polyTempC)) {
        dailyMmC[dates[i]] = mm.polyTempC;
      }
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

function computeWeightedMmaFromForecastStatic(cityId, forecastSnapshot) {
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
    const entries = availableForDate.map((key) => ({
      modelKey: key,
      valueC: dailyByModelC[key][date]
    }));
    const mma = computePolyTempLikeFromModelEntries(entries, weights);
    if (Number.isFinite(mma.polyTempC)) {
      dailyMmaC[date] = mma.polyTempC;
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

async function computeWeightedMmaFromForecast(cityId, zoneId, forecastSnapshot, options = {}) {
  const dailyByModelC = forecastSnapshot?.dailyByModelC || {};
  const modelKeys = Object.keys(dailyByModelC).filter((key) => dailyByModelC[key] && typeof dailyByModelC[key] === 'object');
  if (!modelKeys.length) {
    return {
      dailyMmaC: {},
      normalizedWeightsByModel: {},
      dominantModelKey: null,
      dominantWeightPct: null,
      source: MMA_PREMIUM_ENGINE_VERSION,
      cacheStatus: 'NO_MODELS',
      byHorizon: {}
    };
  }

  const coords = OPEN_METEO_COORDS_BY_CITY[cityId];
  const providerIds = modelKeys.map((modelKey) => openMeteoModelToSourceId(modelKey));
  const premiumMode = options?.premiumMode === 'ensure' ? 'ensure' : 'cache-only';

  let premiumBundle = null;
  if (coords) {
    premiumBundle = await getPremiumWeightsBundleForCity({
      cityId,
      zoneId,
      lat: coords.lat,
      lon: coords.lon,
      providerIds,
      mode: premiumMode
    });
  }

  const staticFallback = computeWeightedMmaFromForecastStatic(cityId, forecastSnapshot);
  const allDates = new Set();
  for (const key of modelKeys) {
    Object.keys(dailyByModelC[key] || {}).forEach((date) => allDates.add(date));
  }

  const todayIso = todayIsoInZone(zoneId);
  const byHorizon = {};
  const dailyMmaC = {};
  for (const date of allDates) {
    const horizonDays = clamp(dayDiffIso(todayIso, date), 0, 2);
    const horizonEntry = premiumBundle?.horizons?.[horizonDays] || null;
    const availableForDate = modelKeys.filter((key) => Number.isFinite(dailyByModelC[key]?.[date]));
    if (!availableForDate.length) continue;
    const dynamicByModel = horizonEntry
      ? providerWeightsToModelWeights(horizonEntry.weightsByProviderId)
      : {};
    const fallbackByModel = staticFallback.normalizedWeightsByModel || {};
    const dynamicWeightsRaw = {};
    for (const mk of availableForDate) {
      if (Number.isFinite(dynamicByModel[mk])) dynamicWeightsRaw[mk] = dynamicByModel[mk];
      else if (Number.isFinite(fallbackByModel[mk])) dynamicWeightsRaw[mk] = fallbackByModel[mk];
    }
    const weightsForDate = normalizeWeightsForAvailable(dynamicWeightsRaw, availableForDate);
    const entries = availableForDate.map((key) => ({ modelKey: key, valueC: dailyByModelC[key][date] }));
    const mma = computePolyTempLikeFromModelEntries(entries, weightsForDate);
    if (Number.isFinite(mma.polyTempC)) {
      dailyMmaC[date] = mma.polyTempC;
    }
    if (!byHorizon[horizonDays]) {
      const dominantEntry = Object.entries(weightsForDate).sort((a, b) => b[1] - a[1])[0] || null;
      byHorizon[horizonDays] = {
        horizonDays,
        normalizedWeightsByModel: weightsForDate,
        dominantModelKey: dominantEntry?.[0] || null,
        dominantWeightPct: dominantEntry ? round1(dominantEntry[1] * 100) : null,
        verifiedDays: Number.isFinite(Number(horizonEntry?.verifiedDays)) ? Number(horizonEntry.verifiedDays) : 0,
        lastVerifiedDateIso: horizonEntry?.lastVerifiedDateIso || null,
        calibrationReady: horizonEntry?.calibrationReady === true,
        calibrationProgress: Number.isFinite(Number(horizonEntry?.calibrationProgress)) ? Number(horizonEntry.calibrationProgress) : 0,
        warnings: Array.isArray(horizonEntry?.warnings) ? horizonEntry.warnings : []
      };
    }
  }

  const todayHorizon = byHorizon[0] || null;
  return {
    dailyMmaC,
    normalizedWeightsByModel: todayHorizon?.normalizedWeightsByModel || staticFallback.normalizedWeightsByModel || {},
    dominantModelKey: todayHorizon?.dominantModelKey || staticFallback.dominantModelKey || null,
    dominantWeightPct: todayHorizon?.dominantWeightPct ?? staticFallback.dominantWeightPct ?? null,
    source: premiumBundle?.source || staticFallback.source || MMA_WEIGHTS_VERSION,
    cacheStatus: premiumBundle?.cacheStatus || 'FALLBACK_STATIC',
    byHorizon,
    auditTail: premiumBundle?.auditTail || []
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

function median(values) {
  const arr = (values || []).filter((v) => Number.isFinite(v)).slice().sort((a, b) => a - b);
  if (!arr.length) return 0;
  const mid = Math.floor(arr.length / 2);
  return arr.length % 2 === 0 ? (arr[mid - 1] + arr[mid]) / 2 : arr[mid];
}

function polyTempBlendWithDominant(samples, dynamicWeightsBySourceId = {}) {
  if (!Array.isArray(samples) || !samples.length) return null;
  const weightedSamples = samples.map((sample) => ({
    sample,
    weight: Number.isFinite(dynamicWeightsBySourceId?.[sample.sourceId])
      ? Number(dynamicWeightsBySourceId[sample.sourceId])
      : forecastBaseWeightForSourceId(sample.sourceId)
  }));

  const totalWeight = weightedSamples.reduce((acc, it) => acc + (Number.isFinite(it.weight) ? it.weight : 0), 0);
  const weightedMean = totalWeight > 0
    ? (weightedSamples.reduce((acc, it) => acc + (it.sample.valueC * it.weight), 0) / totalWeight)
    : (weightedSamples.reduce((acc, it) => acc + it.sample.valueC, 0) / weightedSamples.length);

  if (!dynamicWeightsBySourceId || !Object.keys(dynamicWeightsBySourceId).length || weightedSamples.length < 2 || totalWeight <= 0) {
    return weightedMean;
  }

  const sorted = weightedSamples.slice().sort((a, b) => b.weight - a.weight);
  const top = sorted[0];
  const second = sorted[1] || null;
  const topShare = clamp(top.weight / totalWeight, 0, 1);
  const dominanceRatio = (!second || second.weight <= 0) ? Number.POSITIVE_INFINITY : (top.weight / second.weight);

  if (topShare < PREMIUM_DOMINANT_MIN_SHARE && dominanceRatio < PREMIUM_DOMINANT_MIN_RATIO) {
    return weightedMean;
  }

  const ratioFactor = Number.isFinite(dominanceRatio)
    ? clamp((dominanceRatio - 1.0) / (PREMIUM_DOMINANT_TARGET_RATIO - 1.0), 0, 1)
    : 1.0;
  const shareFactor = clamp(topShare / PREMIUM_DOMINANT_TARGET_SHARE, 0, 1);
  const alpha = clamp(
    PREMIUM_DOMINANT_BASE_ALPHA + (PREMIUM_DOMINANT_EXTRA_ALPHA * Math.max(ratioFactor, shareFactor)),
    PREMIUM_DOMINANT_BASE_ALPHA,
    PREMIUM_DOMINANT_MAX_ALPHA
  );

  return (weightedMean * (1 - alpha)) + (top.sample.valueC * alpha);
}

function computePolyTempLikeFromModelEntries(entries, dynamicWeightsByModel = {}) {
  const candidates = (entries || [])
    .filter((entry) => Number.isFinite(entry?.valueC))
    .filter((entry) => entry.valueC >= POLYTEMP_VALID_TEMP_MIN_C && entry.valueC <= POLYTEMP_VALID_TEMP_MAX_C)
    .map((entry) => ({
      sourceId: openMeteoModelToSourceId(entry.modelKey),
      sourceName: getOpenMeteoModelLabel(entry.modelKey),
      modelKey: entry.modelKey,
      valueC: entry.valueC
    }));

  if (!candidates.length) {
    return { polyTempC: null, validValuesC: [], warnings: ['MM: sin fuentes válidas'] };
  }

  const med = median(candidates.map((c) => c.valueC));
  const absDev = candidates.map((c) => Math.abs(c.valueC - med));
  const mad = median(absDev);
  const outlierTolerance = Math.max(1.8, mad * 3.2);
  const filtered = (candidates.length <= 2
    ? candidates
    : candidates.filter((c) => Math.abs(c.valueC - med) <= outlierTolerance)
  );
  const effective = filtered.length ? filtered : candidates;

  const dynamicWeightsBySourceId = {};
  for (const [modelKey, weight] of Object.entries(dynamicWeightsByModel || {})) {
    dynamicWeightsBySourceId[openMeteoModelToSourceId(modelKey)] = weight;
  }
  const weighted = polyTempBlendWithDominant(effective, dynamicWeightsBySourceId);
  const spread = Math.max(...effective.map((c) => c.valueC)) - Math.min(...effective.map((c) => c.valueC));

  const warnings = [];
  const outliers = candidates.length - effective.length;
  if (outliers > 0) warnings.push(`MM: descartados ${outliers} outliers`);
  if (effective.length < 3) warnings.push(`MM: baja confianza (${effective.length} fuentes)`);
  if (spread > 5.5) warnings.push(`MM: alta dispersión (${spread.toFixed(1)}°C)`);

  return {
    polyTempC: Number.isFinite(weighted) ? weighted : null,
    validValuesC: effective.map((c) => c.valueC),
    warnings
  };
}

function estimateSigmaCFromValues(valuesC) {
  const values = (valuesC || []).filter((v) => Number.isFinite(v));
  if (values.length < 2) return 1.8;
  const mean = values.reduce((a, b) => a + b, 0) / values.length;
  const variance = values.map((v) => (v - mean) ** 2).reduce((a, b) => a + b, 0) / values.length;
  return clamp(Math.sqrt(variance), 0.9, 4.5);
}

function isForecastInvalidForTodayWeb(forecastTempC, observedMaxC) {
  if (!Number.isFinite(forecastTempC) || !Number.isFinite(observedMaxC)) return false;
  return forecastTempC < (observedMaxC - FORECAST_INVALID_EPSILON_C);
}

function normalizePolyTempForLiveFloorWeb(preferredForecastC, observedMaxC) {
  if (!Number.isFinite(preferredForecastC)) return Number.isFinite(observedMaxC) ? observedMaxC : null;
  if (!Number.isFinite(observedMaxC)) return preferredForecastC;
  return Math.max(preferredForecastC, observedMaxC);
}

function normalizeForecastInputsForLiveFloorWeb(forecastTemps, observedMaxC) {
  const values = (forecastTemps || []).filter((v) => Number.isFinite(v));
  if (!Number.isFinite(observedMaxC)) return values;
  if (!values.length) return [observedMaxC];
  if (values.some((value) => Math.abs(value - observedMaxC) <= FORECAST_INVALID_EPSILON_C)) return values;
  return [...values, observedMaxC];
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

function applyConfidenceWeightProbability(probability, confidenceMultiplier) {
  const p = clamp(Number(probability) || 0.5, 0.001, 0.999);
  const centered = p - 0.5;
  const weighted = 0.5 + (centered * (Number.isFinite(confidenceMultiplier) ? confidenceMultiplier : 1.0));
  return clamp(weighted, 0.001, 0.999);
}

function bucketProbabilityYesPct(market, meanC, sigmaC, confidenceMultiplier = 1.0) {
  if (!Number.isFinite(meanC)) return null;
  const unit = market.unit || 'C';
  const mean = celsiusToUnit(meanC, unit);
  const sigma = Math.max(0.6, (unit === 'F' ? sigmaC * 9 / 5 : sigmaC));
  const t = Number(market.threshold);
  const u = Number(market.upperThreshold);
  if (!Number.isFinite(mean) || !Number.isFinite(sigma)) return null;
  const cdf = (x) => clamp(normalCdf(x, mean, sigma) ?? 0.5, 0, 1);
  let rawProb = null;
  switch (market.type) {
    case 'EXACT':
      if (!Number.isFinite(t)) return null;
      rawProb = cdf(t + 0.5) - cdf(t - 0.5);
      break;
    case 'BETWEEN':
      if (!Number.isFinite(t) || !Number.isFinite(u)) return null;
      rawProb = cdf(Math.max(t, u) + 0.5) - cdf(Math.min(t, u) - 0.5);
      break;
    case 'GREATER_OR_EQUAL':
      if (!Number.isFinite(t)) return null;
      rawProb = 1 - cdf(t - 0.5);
      break;
    case 'LESS_OR_EQUAL':
      if (!Number.isFinite(t)) return null;
      rawProb = cdf(t + 0.5);
      break;
    default:
      return null;
  }
  const calibrated = applyConfidenceWeightProbability(rawProb, confidenceMultiplier);
  return clamp(calibrated * 100, 0.1, 99.9);
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

function inferDirectionParity(market, meanC, recommendedBuy) {
  const mean = celsiusToUnit(meanC, market.unit || 'C');
  const t = Number(market.threshold);
  const u = Number(market.upperThreshold);
  switch (market.type) {
    case 'GREATER_OR_EQUAL':
      return recommendedBuy === 'YES' ? 'OVER' : 'UNDER';
    case 'LESS_OR_EQUAL':
      return recommendedBuy === 'YES' ? 'UNDER' : 'OVER';
    case 'EXACT':
      if (!Number.isFinite(mean)) return 'RANGE';
      if (mean >= t + 0.35) return 'OVER';
      if (mean <= t - 0.35) return 'UNDER';
      return 'RANGE';
    case 'BETWEEN': {
      if (!Number.isFinite(mean)) return 'RANGE';
      const upper = Number.isFinite(u) ? u : t;
      if (recommendedBuy === 'YES') return 'RANGE';
      if (mean > upper + 0.35) return 'OVER';
      if (mean < t - 0.35) return 'UNDER';
      return 'RANGE';
    }
    default:
      return inferDirectionFromMean(market, meanC);
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

const POLY_ASSUMED_ROUNDTRIP_FEE_RATE = 0.018;
const POLY_DEFAULT_SPREAD_ASSUMPTION = 0.020;
const POLY_MAX_TOTAL_EXECUTION_COST = 0.30;
const POLY_MIN_FILL_PROBABILITY_TO_TRADE = 0.50;
const POLY_MIN_EXECUTABLE_EDGE_TO_TRADE = 0.020;
const POLY_GREEN_MIN_FILL_PROBABILITY = 0.65;
const POLY_GREEN_EXECUTABLE_EDGE = 0.06;
const POLY_YELLOW_MIN_FILL_PROBABILITY = 0.45;
const POLY_YELLOW_EXECUTABLE_EDGE = 0.025;
const POLY_CONTROL_MIN_EXECUTABLE_EDGE = 0.025;
const POLY_CONTROL_MIN_FILL = 0.55;
const POLY_CONTROL_MIN_LIQUIDITY = 250.0;
const POLY_CONTROL_MIN_VOLUME_24H = 200.0;
const POLY_CONTROL_MAX_SPREAD = 0.12;
const POLY_CONTROL_MAX_TOTAL_COST = 0.22;
const POLY_CONTROL_MIN_ENTRY_PRICE = 0.02;
const POLY_CONTROL_MAX_ENTRY_PRICE = 0.90;
const POLY_LATE_DAY_HOUR = 15;
const POLY_LATE_DAY_MIN_EXECUTABLE_EDGE = 0.035;

const CAL_LOCAL_TIME_BAND = Object.freeze({
  NIGHT: 'NIGHT',
  MORNING: 'MORNING',
  MIDDAY: 'MIDDAY',
  EVENING: 'EVENING'
});

const CAL_SIGMA_BAND_MULTIPLIER = Object.freeze({
  [CAL_LOCAL_TIME_BAND.NIGHT]: 1.22,
  [CAL_LOCAL_TIME_BAND.MORNING]: 1.12,
  [CAL_LOCAL_TIME_BAND.MIDDAY]: 0.95,
  [CAL_LOCAL_TIME_BAND.EVENING]: 0.88
});

const CAL_CONFIDENCE_BAND_MULTIPLIER = Object.freeze({
  [CAL_LOCAL_TIME_BAND.NIGHT]: 0.86,
  [CAL_LOCAL_TIME_BAND.MORNING]: 0.93,
  [CAL_LOCAL_TIME_BAND.MIDDAY]: 1.05,
  [CAL_LOCAL_TIME_BAND.EVENING]: 1.12
});

const CAL_BIAS_BAND_C = Object.freeze({
  [CAL_LOCAL_TIME_BAND.NIGHT]: -0.05,
  [CAL_LOCAL_TIME_BAND.MORNING]: 0.00,
  [CAL_LOCAL_TIME_BAND.MIDDAY]: 0.12,
  [CAL_LOCAL_TIME_BAND.EVENING]: 0.18
});

function calProfile(st, st1, st2, ct, ct1, ct2, bt = 0, bt1 = 0, bt2 = 0) {
  return {
    sigmaByHorizon: [st, st1, st2],
    confidenceByHorizon: [ct, ct1, ct2],
    biasByHorizonC: [bt, bt1, bt2]
  };
}

const TRADER_CAL_DEFAULT_PROFILE = calProfile(1.00, 1.14, 1.28, 1.00, 0.94, 0.88);
const TRADER_CAL_CITY_PROFILES = Object.freeze({
  'miami': calProfile(1.08, 1.20, 1.34, 0.98, 0.90, 0.84, 0.20, 0.10, 0.00),
  'london': calProfile(0.96, 1.10, 1.24, 1.02, 0.96, 0.90, 0.00, 0.00, 0.00),
  'toronto': calProfile(1.00, 1.14, 1.28, 1.00, 0.94, 0.88, 0.00, 0.00, 0.00),
  'seattle': calProfile(0.94, 1.08, 1.20, 1.05, 0.98, 0.92, -0.10, -0.10, -0.10),
  'dallas': calProfile(1.07, 1.20, 1.35, 0.99, 0.91, 0.84, 0.20, 0.20, 0.10),
  'wellington': calProfile(1.09, 1.22, 1.36, 0.96, 0.89, 0.83, -0.10, -0.10, 0.00),
  'ankara': calProfile(1.02, 1.16, 1.30, 1.00, 0.93, 0.87, 0.10, 0.10, 0.00),
  'seoul': calProfile(1.01, 1.15, 1.29, 1.00, 0.93, 0.87, 0.10, 0.10, 0.00),
  'new-york': calProfile(1.04, 1.17, 1.31, 1.00, 0.92, 0.86, 0.10, 0.10, 0.00),
  'chicago': calProfile(1.05, 1.18, 1.32, 0.99, 0.91, 0.85, 0.10, 0.10, 0.00),
  'atlanta': calProfile(1.06, 1.19, 1.33, 0.99, 0.91, 0.85, 0.20, 0.10, 0.00),
  'paris': calProfile(0.97, 1.11, 1.25, 1.01, 0.95, 0.89, 0.00, 0.00, 0.00),
  'buenos-aires': calProfile(1.03, 1.16, 1.30, 1.00, 0.93, 0.87, 0.00, 0.00, 0.00),
  'sao-paulo': calProfile(1.02, 1.15, 1.29, 1.00, 0.94, 0.88, 0.00, 0.00, 0.00)
});

function traderCalBandFromHour(hour) {
  const h = ((Number(hour) || 0) % 24 + 24) % 24;
  if (h >= 0 && h <= 5) return CAL_LOCAL_TIME_BAND.NIGHT;
  if (h <= 10) return CAL_LOCAL_TIME_BAND.MORNING;
  if (h <= 16) return CAL_LOCAL_TIME_BAND.MIDDAY;
  return CAL_LOCAL_TIME_BAND.EVENING;
}

function resolveTraderCalibration(cityId, horizonDays, localHour) {
  const profile = TRADER_CAL_CITY_PROFILES[cityId] || TRADER_CAL_DEFAULT_PROFILE;
  const idx = clamp(Number.isFinite(horizonDays) ? horizonDays : 0, 0, 2);
  const band = traderCalBandFromHour(localHour);
  const bandInfluence = idx === 0 ? 1.0 : (idx === 1 ? 0.45 : 0.25);

  const baseSigma = profile.sigmaByHorizon[idx];
  const baseConfidence = profile.confidenceByHorizon[idx];
  const baseBias = profile.biasByHorizonC[idx];
  const sigmaBand = 1.0 + (((CAL_SIGMA_BAND_MULTIPLIER[band] ?? 1.0) - 1.0) * bandInfluence);
  const confidenceBand = 1.0 + (((CAL_CONFIDENCE_BAND_MULTIPLIER[band] ?? 1.0) - 1.0) * bandInfluence);

  return {
    sigmaMultiplier: clamp(baseSigma * sigmaBand, 0.70, 2.20),
    confidenceMultiplier: clamp(baseConfidence * confidenceBand, 0.55, 1.25),
    meanBiasC: clamp(baseBias + ((CAL_BIAS_BAND_C[band] ?? 0.0) * bandInfluence), -1.2, 1.2)
  };
}

function resolveHorizonDaysFromKey(horizonKey) {
  if (horizonKey === 'today') return 0;
  if (horizonKey === 'tomorrow') return 1;
  return 2;
}

function logistic(x) {
  return 1.0 / (1.0 + Math.exp(-x));
}

function estimateFillProbabilityParity(liquidity, volume24h, spreadDecimal) {
  const liq = Math.max(0, Number(liquidity) || 0);
  const vol = Math.max(0, Number(volume24h) || 0);
  const spr = Math.max(0, Number.isFinite(spreadDecimal) ? spreadDecimal : POLY_DEFAULT_SPREAD_ASSUMPTION);
  const liquidityScore = logistic((Math.log(1 + liq) - Math.log(1 + 1800.0)) / 0.9);
  const volumeScore = logistic((Math.log(1 + vol) - Math.log(1 + 1200.0)) / 0.95);
  const spreadScore = clamp(1.0 - (spr / 0.08), 0.0, 1.0);
  const blended = (0.50 * liquidityScore) + (0.30 * volumeScore) + (0.20 * spreadScore);
  return clamp(0.15 + (0.80 * blended), 0.15, 0.98);
}

function estimateSpreadCostParity(spreadDecimal, fillProbability) {
  const effectiveSpread = clamp(Number.isFinite(spreadDecimal) ? spreadDecimal : POLY_DEFAULT_SPREAD_ASSUMPTION, 0.0, 0.12);
  return Math.min(effectiveSpread * (0.50 + ((1.0 - fillProbability) * 0.35)), 0.07);
}

function estimateLiquidityCostParity(liquidity, volume24h, fillProbability) {
  const liq = Math.max(0, Number(liquidity) || 0);
  const vol = Math.max(0, Number(volume24h) || 0);
  const thinBookPenalty = liq < 600 ? 0.020 : (liq < 1500 ? 0.010 : 0.0);
  const weakFlowPenalty = vol < 500 ? 0.015 : (vol < 1200 ? 0.008 : 0.0);
  const lowFillPenalty = clamp(1.0 - fillProbability, 0.0, 1.0) * 0.06;
  return Math.min(thinBookPenalty + weakFlowPenalty + lowFillPenalty, 0.09);
}

function computeExecutionMetrics({ market, modelYesPct, marketYesPct, actionSide }) {
  const yesAsk = Number(market.yesAskCents);
  const noAsk = Number(market.noAskCents);
  const selectedAsk = actionSide === 'YES' ? yesAsk : noAsk;
  const marketYesProb = clamp(Number(marketYesPct) / 100, 0.001, 0.999);
  const modelYesProb = clamp(Number(modelYesPct) / 100, 0.001, 0.999);
  const marketNoProb = clamp(1.0 - marketYesProb, 0.001, 0.999);

  const evYes = modelYesProb - marketYesProb;
  const evNo = (1.0 - modelYesProb) - marketNoProb;
  const rawEdge = Math.max(evYes, evNo);
  const selectedPriceProb = actionSide === 'YES' ? marketYesProb : marketNoProb;

  const spreadDecimal = Number.isFinite(market.spreadPct) ? (market.spreadPct / 100.0) : POLY_DEFAULT_SPREAD_ASSUMPTION;
  const liquidityBook = Number.isFinite(market.liquidityBook) ? market.liquidityBook : 0;
  const volume24h = Number.isFinite(market.realMarketOverlay?.volume24h) ? market.realMarketOverlay.volume24h : (Number(market.volume24h) || 0);
  const volumeBucket = Number.isFinite(market.volumeBucket) ? market.volumeBucket : 0;

  const fillProbability = estimateFillProbabilityParity(liquidityBook, volume24h, spreadDecimal);
  const feeCost = Math.max(0, selectedPriceProb * POLY_ASSUMED_ROUNDTRIP_FEE_RATE);
  const spreadCost = estimateSpreadCostParity(spreadDecimal, fillProbability);
  const liquidityCost = estimateLiquidityCostParity(liquidityBook, volume24h, fillProbability);
  const totalCost = Math.min(feeCost + spreadCost + liquidityCost, POLY_MAX_TOTAL_EXECUTION_COST);
  const edgeAfterCosts = rawEdge - totalCost;
  const executableEdge = edgeAfterCosts * fillProbability;

  const signal = executableEdge >= POLY_GREEN_EXECUTABLE_EDGE && fillProbability >= POLY_GREEN_MIN_FILL_PROBABILITY
    ? 'GREEN'
    : (executableEdge >= POLY_YELLOW_EXECUTABLE_EDGE && fillProbability >= POLY_YELLOW_MIN_FILL_PROBABILITY ? 'YELLOW' : 'RED');
  const baseShouldTrade = executableEdge >= POLY_MIN_EXECUTABLE_EDGE_TO_TRADE && fillProbability >= POLY_MIN_FILL_PROBABILITY_TO_TRADE;

  return {
    selectedAskCents: selectedAsk,
    selectedPriceProb,
    rawEdgePct: roundTo(rawEdge * 100, 1),
    feePct: roundTo(feeCost * 100, 1),
    totalCostPct: roundTo(totalCost * 100, 1),
    fillPct: roundTo(fillProbability * 100, 1),
    executableEdgePct: roundTo(executableEdge * 100, 1),
    edgeAfterCostPct: roundTo(edgeAfterCosts * 100, 1),
    execMinPct: roundTo(POLY_MIN_EXECUTABLE_EDGE_TO_TRADE * 100, 1),
    baseShouldTrade,
    shouldTrade: baseShouldTrade,
    signal,
    failReason: baseShouldTrade ? null : 'Señal base PASS: ventaja ejecutable insuficiente',
    checks: {}
  };
}

function evaluateExecutionControlParity({ metrics, market, actionSide, horizonKey, localHour }) {
  const minEdge = (horizonKey === 'today' && Number.isFinite(localHour) && localHour >= POLY_LATE_DAY_HOUR)
    ? POLY_LATE_DAY_MIN_EXECUTABLE_EDGE
    : POLY_CONTROL_MIN_EXECUTABLE_EDGE;
  const liquidity = Math.max(0, Number(market.liquidityBook) || 0);
  const volume24h = Math.max(0, Number(market.realMarketOverlay?.volume24h ?? market.volume24h) || 0);
  const spread = Math.max(0, Number(market.spreadPct || 0) / 100.0);
  const selectedPrice = Number(metrics.selectedPriceProb);
  const fillProbability = Number(metrics.fillPct) / 100.0;
  const executableEdge = Number(metrics.executableEdgePct) / 100.0;
  const totalCost = Number(metrics.totalCostPct) / 100.0;

  if (!metrics.baseShouldTrade) {
    return { passes: false, reason: 'Señal base PASS: ventaja ejecutable insuficiente', minEdgePct: roundTo(minEdge * 100, 1) };
  }
  if (liquidity < POLY_CONTROL_MIN_LIQUIDITY) {
    return { passes: false, reason: 'Liquidez insuficiente', minEdgePct: roundTo(minEdge * 100, 1) };
  }
  if (volume24h < POLY_CONTROL_MIN_VOLUME_24H) {
    return { passes: false, reason: 'Volumen 24h insuficiente', minEdgePct: roundTo(minEdge * 100, 1) };
  }
  if (spread > POLY_CONTROL_MAX_SPREAD) {
    return { passes: false, reason: 'Spread demasiado alto', minEdgePct: roundTo(minEdge * 100, 1) };
  }
  if (totalCost > POLY_CONTROL_MAX_TOTAL_COST) {
    return { passes: false, reason: 'Costes totales no viables', minEdgePct: roundTo(minEdge * 100, 1) };
  }
  if (fillProbability < POLY_CONTROL_MIN_FILL) {
    return { passes: false, reason: 'Probabilidad de fill baja', minEdgePct: roundTo(minEdge * 100, 1) };
  }
  if (executableEdge < minEdge) {
    return { passes: false, reason: 'Edge ejecutable por debajo del mínimo', minEdgePct: roundTo(minEdge * 100, 1) };
  }
  if (!(selectedPrice >= POLY_CONTROL_MIN_ENTRY_PRICE && selectedPrice <= POLY_CONTROL_MAX_ENTRY_PRICE)) {
    return { passes: false, reason: 'Precio de entrada fuera de rango', minEdgePct: roundTo(minEdge * 100, 1) };
  }
  return { passes: true, reason: 'Mantenido: pasa control de ejecución', minEdgePct: roundTo(minEdge * 100, 1) };
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

function forecastModelValuesForDate(cityDetail, targetDate) {
  const byModel = cityDetail?.liveOverlay?.forecast?.dailyByModelC || {};
  const values = [];
  for (const modelKey of Object.keys(byModel)) {
    const v = byModel[modelKey]?.[targetDate];
    if (Number.isFinite(v)) values.push(v);
  }
  return values;
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
    const rawMeanC = Number.isFinite(mmaC) ? mmaC : mmC;
    const meanC = realH.key === 'today'
      ? normalizePolyTempForLiveFloorWeb(rawMeanC, observedMaxTodayC)
      : rawMeanC;
    const rawForecastInputs = forecastModelValuesForDate(cloned, realH.targetDate);
    const sigmaInputs = realH.key === 'today'
      ? normalizeForecastInputsForLiveFloorWeb(rawForecastInputs, observedMaxTodayC)
      : rawForecastInputs;
    const sigmaC = sigmaInputs.length ? estimateSigmaCFromValues(sigmaInputs) : sigmaCForHorizon(realH.key);

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
            `Mean usada ${Number.isFinite(meanC) ? roundTo(meanC, 1) : '--'}°C`,
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

      const horizonDays = resolveHorizonDaysFromKey(realH.key);
      const calibration = resolveTraderCalibration(cloned.city.id, horizonDays, Number.isFinite(localHour) ? localHour : 12);
      const calibratedMeanC = Number.isFinite(meanC) ? (meanC + calibration.meanBiasC) : meanC;
      const calibratedSigmaC = Number.isFinite(sigmaC) ? (sigmaC * calibration.sigmaMultiplier) : sigmaC;
      const modelYesPct = bucketProbabilityYesPct(
        market,
        calibratedMeanC,
        calibratedSigmaC,
        calibration.confidenceMultiplier
      );
      if (!Number.isFinite(modelYesPct)) {
        filteredReasons.data += 1;
        continue;
      }
      const marketYesPct = Number.isFinite(market.marketProbabilityYesPct) ? market.marketProbabilityYesPct : 50;
      const actionSide = modelYesPct >= marketYesPct ? 'YES' : 'NO';
      const direction = inferDirectionParity(market, calibratedMeanC, actionSide);
      const metrics = computeExecutionMetrics({ market, modelYesPct, marketYesPct, actionSide, horizonKey: realH.key, localHour });
      const controlDecision = evaluateExecutionControlParity({
        metrics,
        market,
        actionSide,
        horizonKey: realH.key,
        localHour
      });
      metrics.shouldTrade = controlDecision.passes;
      metrics.failReason = controlDecision.passes ? null : controlDecision.reason;
      metrics.execMinPct = controlDecision.minEdgePct;
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

function overlayCitySummary(baseCity, metarSnapshot, controlSnapshot = null, forecastSnapshot = null, mmaForecast = null) {
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
      source: mmaForecast?.source || MMA_WEIGHTS_VERSION,
      cacheStatus: mmaForecast?.cacheStatus || 'CACHED',
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
    if (Number.isFinite(mmC)) city.summary.mm.invalidToday = isForecastInvalidForTodayWeb(mmC, maxObserved);
    if (Number.isFinite(mmaC)) city.summary.mma.invalidToday = isForecastInvalidForTodayWeb(mmaC, maxObserved);
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
      dailyByModelC: forecastSnapshot?.dailyByModelC || {},
      mmaWeightsVersion: mmaForecast?.source || null,
      mmaWeightsByModel: mmaForecast?.normalizedWeightsByModel || {},
      mmaDominantModelKey: mmaForecast?.dominantModelKey || null,
      mmaCacheStatus: mmaForecast?.cacheStatus || null,
      mmaByHorizon: mmaForecast?.byHorizon || {},
      error: forecastSnapshot.error || null
    };
  }
  return city;
}

function overlayCityDetail(
  baseCity,
  metarSnapshot,
  controlSnapshot = null,
  forecastSnapshot = null,
  mmaForecast = null,
  stationComparisonSnapshot = null
) {
  const city = overlayCitySummary(baseCity, metarSnapshot, controlSnapshot, forecastSnapshot, mmaForecast);
  const displayUnit = city.city.displayUnit;

  if (stationComparisonSnapshot) {
    city.stationComparison = {
      cityId: city.city.id,
      cityName: city.city.name,
      metarCode: city.city.metarCode,
      zoneId: city.city.zoneId,
      localTime: city.localTime,
      fetchedAtUtc: new Date().toISOString(),
      displayUnit,
      controlSourceUrl: stationComparisonSnapshot.controlSourceUrl || city.city.wundergroundControlUrl || null,
      nearbySourceUrl: stationComparisonSnapshot.nearbySourceUrl || city.city.wundergroundPwsUrl || null,
      controlCurrent: deepClone(stationComparisonSnapshot.controlCurrent),
      controlMax: deepClone(stationComparisonSnapshot.controlMax),
      nearbyCurrent: deepClone(stationComparisonSnapshot.nearbyCurrent),
      nearbyMax: deepClone(stationComparisonSnapshot.nearbyMax),
      deviationCurrentC: stationComparisonSnapshot.deviationCurrentC,
      deviationMaxC: stationComparisonSnapshot.deviationMaxC,
      isCurrentDeviationReliable: !!stationComparisonSnapshot.isCurrentDeviationReliable,
      isMaxDeviationReliable: !!stationComparisonSnapshot.isMaxDeviationReliable,
      warnings: deepClone(stationComparisonSnapshot.warnings || []),
      error: stationComparisonSnapshot.error || null
    };
  }

  if (!metarSnapshot?.current || metarSnapshot.current.tempC == null) {
    return city;
  }

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

  async function buildComputedCityDetail(cityId, { premiumMode = 'cache-only', includeStationComparison = false } = {}) {
    const baseCity = await baseProvider.getCityDetail(cityId);
    if (!baseCity) return null;
    const [metarSnapshot, controlSnapshot, forecastSnapshot, stationComparisonSnapshot] = await Promise.all([
      fetchMetarSnapshot(baseCity.city.metarCode),
      fetchWundergroundControlSnapshot(cityId, baseCity.city.displayUnit, baseCity.city.metarCode),
      fetchOpenMeteoMmSnapshot(cityId, baseCity.city.zoneId),
      includeStationComparison
        ? fetchWundergroundStationComparisonSnapshot(cityId, baseCity.city.displayUnit, baseCity.city.metarCode)
        : Promise.resolve(null)
    ]);
    const mmaForecast = await computeWeightedMmaFromForecast(cityId, baseCity.city.zoneId, forecastSnapshot, { premiumMode });
    let cityDetail = overlayCityDetail(
      baseCity,
      metarSnapshot,
      controlSnapshot,
      forecastSnapshot,
      mmaForecast,
      stationComparisonSnapshot
    );
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

  async function getComputedCityDetailCached(
    cityId,
    { cacheScope = 'summary', premiumMode = 'cache-only', includeStationComparison = false } = {}
  ) {
    const cacheKey = `${cacheScope}:${cityId}:sc=${includeStationComparison ? '1' : '0'}`;
    const now = Date.now();
    const cached = detailCache.get(cacheKey);
    if (cached && now - cached.ts <= DETAIL_CACHE_TTL_MS) {
      return deepClone(cached.value);
    }
    if (detailInflight.has(cacheKey)) {
      const inflightValue = await detailInflight.get(cacheKey);
      return inflightValue ? deepClone(inflightValue) : null;
    }
    const p = (async () => {
      const value = await buildComputedCityDetail(cityId, { premiumMode, includeStationComparison });
      if (value) {
        detailCache.set(cacheKey, { ts: Date.now(), value: deepClone(value) });
      }
      return value;
    })();
    detailInflight.set(cacheKey, p);
    try {
      const value = await p;
      return value ? deepClone(value) : null;
    } finally {
      detailInflight.delete(cacheKey);
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
      const detail = await getComputedCityDetailCached(cityId, {
        cacheScope: 'summary',
        premiumMode: 'cache-only',
        includeStationComparison: false
      });
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
      return getComputedCityDetailCached(cityId, {
        cacheScope: 'detail',
        premiumMode: 'ensure',
        includeStationComparison: true
      });
    }
  };
}
