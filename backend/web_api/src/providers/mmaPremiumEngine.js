import { mkdir, readFile, rename, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);

const DEFAULT_STATE_PATH = resolve(__dirname, '..', '..', 'data', 'web_mma_premium_state.json');
const ENV_STATE_PATH = String(process.env.POLYMETEO_MMA_PREMIUM_STATE_FILE || '').trim();
const RESOLVED_STATE_PATH = ENV_STATE_PATH
  ? (ENV_STATE_PATH.startsWith('/') ? ENV_STATE_PATH : resolve(process.cwd(), ENV_STATE_PATH))
  : DEFAULT_STATE_PATH;

export const MMA_PREMIUM_ENGINE_VERSION = 'WEB_MMA_PREMIUM_ANDROID_PARITY_V1';

const CACHE_TTL_MS = 12 * 60 * 60 * 1000; // 12h
const PERSIST_DEBOUNCE_MS = 500;
const AUDIT_LIMIT = 120;
const HIST_FETCH_TIMEOUT_MS = 8000;

const VALID_TEMP_RANGE_C = { min: -80, max: 65 };
const BOOTSTRAP_START_H0 = '2022-01-01';
const BOOTSTRAP_START_H1_H2 = '2024-01-01';
const BOOTSTRAP_HORIZONS = [0, 1, 2];

const MIN_VERIFIED_DAYS_FOR_STABLE_WEIGHTS = 12;
const ACCURACY_SCALE_C = 1.8;
const RMSE_SPREAD_SCALE_C = 2.4;
const RECENCY_HALF_LIFE_DAYS = 18.0;
const RECENT_WINDOW_DAYS = 21;
const SEASONAL_MONTH_DISTANCE = 1;
const RECENT_DAYS_FOR_FULL_CONFIDENCE = 14.0;
const SEASONAL_DAYS_FOR_FULL_CONFIDENCE = 45.0;
const LONG_DAYS_FOR_FULL_CONFIDENCE = 180.0;
const PRIMARY_HORIZON_FULL_CONFIDENCE_DAYS = 30.0;
const CROSS_HORIZON_FULL_CONFIDENCE_DAYS = 90.0;
const CROSS_HORIZON_MIN_PRIMARY_BLEND = 0.30;
const CROSS_HORIZON_DISTANCE_ONE_WEIGHT = 1.00;
const CROSS_HORIZON_DISTANCE_TWO_WEIGHT = 0.72;
const CROSS_HORIZON_DISTANCE_OTHER_WEIGHT = 0.50;
const CROSS_HORIZON_BLEND_WARNING_THRESHOLD_DAYS = 28;
const BLEND_WEIGHT_RECENCY = 0.55;
const BLEND_WEIGHT_SEASONAL = 0.30;
const BLEND_WEIGHT_LONG = 0.15;
const MIN_BLEND_CONFIDENCE_FACTOR = 0.25;
const MIN_MULTIPLIER = 0.55;
const MAX_MULTIPLIER = 1.65;
const NO_DATA_MULTIPLIER = 0.90;

const FORECAST_BASE_WEIGHTS_BY_SOURCE_ID = Object.freeze({
  'windy-ecmwf': 1.35,
  'windy-icon': 1.15,
  'openmeteo-ifs025': 1.33,
  'openmeteo-ifs': 1.30,
  'openmeteo-aifs': 1.25,
  'windy-icon-eu': 1.15,
  'windy-nam-conus': 1.12,
  'weather-gov': 1.10,
  'windy-gfs': 1.05,
  'openweather': 0.85
});

const CALIBRATION_MODELS = Object.freeze([
  { providerId: 'openmeteo-ifs', providerName: 'Open-Meteo ECMWF IFS', modelId: 'ecmwf_ifs' },
  { providerId: 'openmeteo-ifs025', providerName: 'Open-Meteo ECMWF IFS 0.25', modelId: 'ecmwf_ifs025' },
  { providerId: 'openmeteo-aifs', providerName: 'Open-Meteo ECMWF AIFS', modelId: 'ecmwf_aifs025_single' }
]);

function nowIso() {
  return new Date().toISOString();
}

function round1(value) {
  return Math.round(Number(value) * 10) / 10;
}

function isFiniteNumber(v) {
  return typeof v === 'number' && Number.isFinite(v);
}

function inValidRangeC(v) {
  return isFiniteNumber(v) && v >= VALID_TEMP_RANGE_C.min && v <= VALID_TEMP_RANGE_C.max;
}

function baseWeightFor(providerId) {
  return FORECAST_BASE_WEIGHTS_BY_SOURCE_ID[String(providerId || '')] ?? 0.9;
}

function providerIdToModelKey(providerId) {
  switch (String(providerId || '')) {
    case 'openmeteo-ifs':
      return 'ecmwf_ifs';
    case 'openmeteo-ifs025':
      return 'ecmwf_ifs025';
    case 'openmeteo-aifs':
      return 'ecmwf_aifs025_single';
    case 'windy-gfs':
      return 'gfs_global';
    default:
      return null;
  }
}

function modelKeyToProviderId(modelKey) {
  switch (String(modelKey || '')) {
    case 'ecmwf_ifs':
      return 'openmeteo-ifs';
    case 'ecmwf_ifs025':
      return 'openmeteo-ifs025';
    case 'ecmwf_aifs025_single':
      return 'openmeteo-aifs';
    case 'gfs_global':
      return 'windy-gfs';
    default:
      return String(modelKey || '');
  }
}

function parseIsoDate(value) {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return null;
  const d = new Date(`${value}T00:00:00Z`);
  if (!Number.isFinite(d.getTime())) return null;
  return d;
}

function dateToIsoUtc(d) {
  return new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate())).toISOString().slice(0, 10);
}

function addDaysIso(iso, days) {
  const d = parseIsoDate(iso);
  if (!d) return iso;
  d.setUTCDate(d.getUTCDate() + Number(days || 0));
  return dateToIsoUtc(d);
}

function dayDiffIso(fromIso, toIso) {
  const a = parseIsoDate(fromIso);
  const b = parseIsoDate(toIso);
  if (!a || !b) return 0;
  return Math.round((b.getTime() - a.getTime()) / 86400000);
}

function clamp(value, min, max) {
  return Math.max(min, Math.min(max, value));
}

function confidenceWeight(days, fullConfidenceDays) {
  if (!Number.isFinite(Number(days)) || days <= 0 || !Number.isFinite(Number(fullConfidenceDays)) || fullConfidenceDays <= 0) {
    return 0;
  }
  return clamp(Number(days) / Number(fullConfidenceDays), 0, 1);
}

function monthDistance(a, b) {
  const diff = Math.abs(Number(a) - Number(b));
  return Math.min(diff, 12 - diff);
}

function safeJsonParse(text) {
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

function toYmdInZone(date, zoneId) {
  try {
    const fmt = new Intl.DateTimeFormat('en-CA', {
      timeZone: zoneId,
      year: 'numeric',
      month: '2-digit',
      day: '2-digit'
    });
    const parts = Object.fromEntries(fmt.formatToParts(date).filter((p) => p.type !== 'literal').map((p) => [p.type, p.value]));
    return `${parts.year}-${parts.month}-${parts.day}`;
  } catch {
    return date.toISOString().slice(0, 10);
  }
}

function todayIsoInZone(zoneId) {
  return toYmdInZone(new Date(), zoneId);
}

function buildStateKey(cityId, horizonDays) {
  return `${cityId}|H${horizonDays}`;
}

function normalizeState(raw) {
  const version = Number.isFinite(Number(raw?.version)) ? Number(raw.version) : 1;
  const entries = raw?.entries && typeof raw.entries === 'object' ? raw.entries : {};
  const auditLog = Array.isArray(raw?.auditLog) ? raw.auditLog.slice(0, AUDIT_LIMIT) : [];
  const cleanEntries = {};
  for (const [key, value] of Object.entries(entries)) {
    if (!value || typeof value !== 'object') continue;
    cleanEntries[key] = {
      cityId: String(value.cityId || ''),
      horizonDays: Number.isFinite(Number(value.horizonDays)) ? Number(value.horizonDays) : 0,
      generatedAtUtc: String(value.generatedAtUtc || nowIso()),
      cityTodayIsoAtGeneration: String(value.cityTodayIsoAtGeneration || ''),
      verifiedDays: Number.isFinite(Number(value.verifiedDays)) ? Number(value.verifiedDays) : 0,
      lastVerifiedDateIso: value.lastVerifiedDateIso ? String(value.lastVerifiedDateIso) : null,
      weightsByProviderId: value.weightsByProviderId && typeof value.weightsByProviderId === 'object' ? value.weightsByProviderId : {},
      warnings: Array.isArray(value.warnings) ? value.warnings.map((w) => String(w)).slice(0, 20) : [],
      dominantProviderId: value.dominantProviderId ? String(value.dominantProviderId) : null,
      dominantWeightPct: Number.isFinite(Number(value.dominantWeightPct)) ? Number(value.dominantWeightPct) : null,
      calibrationReady: value.calibrationReady !== false,
      calibrationProgress: Number.isFinite(Number(value.calibrationProgress)) ? Number(value.calibrationProgress) : 1,
      source: String(value.source || MMA_PREMIUM_ENGINE_VERSION)
    };
  }
  return { version, entries: cleanEntries, auditLog };
}

function emptyState() {
  return { version: 1, entries: {}, auditLog: [] };
}

class PremiumStateStore {
  constructor(stateFilePath = RESOLVED_STATE_PATH) {
    this.stateFilePath = stateFilePath;
    this.tmpStateFilePath = `${stateFilePath}.tmp`;
    this.state = emptyState();
    this.readyPromise = null;
    this.persistTimer = null;
    this.persistRunning = false;
    this.persistQueued = false;
  }

  async ready() {
    if (!this.readyPromise) this.readyPromise = this._load();
    await this.readyPromise;
  }

  getEntry(cityId, horizonDays) {
    return this.state.entries[buildStateKey(cityId, horizonDays)] || null;
  }

  setEntries(entries) {
    for (const entry of entries) {
      if (!entry) continue;
      this.state.entries[buildStateKey(entry.cityId, entry.horizonDays)] = entry;
    }
    this._appendAudit('mma_state_updated', { entries: entries.length }, false);
    this._schedulePersist();
  }

  getAudit(limit = 20) {
    return this.state.auditLog.slice(0, limit);
  }

  _appendAudit(type, data = {}, persist = false) {
    this.state.auditLog.unshift({ atUtc: nowIso(), type, data });
    if (this.state.auditLog.length > AUDIT_LIMIT) this.state.auditLog.length = AUDIT_LIMIT;
    if (persist) this._schedulePersist();
  }

  _schedulePersist() {
    if (this.persistTimer) return;
    this.persistTimer = setTimeout(() => {
      this.persistTimer = null;
      void this._persist();
    }, PERSIST_DEBOUNCE_MS);
  }

  async _persist() {
    if (this.persistRunning) {
      this.persistQueued = true;
      return;
    }
    this.persistRunning = true;
    try {
      await mkdir(dirname(this.stateFilePath), { recursive: true });
      const payload = JSON.stringify(this.state, null, 2);
      await writeFile(this.tmpStateFilePath, payload, 'utf8');
      await rename(this.tmpStateFilePath, this.stateFilePath);
    } catch {
      // swallow persistence errors; caller falls back to in-memory state
    } finally {
      this.persistRunning = false;
      if (this.persistQueued) {
        this.persistQueued = false;
        this._schedulePersist();
      }
    }
  }

  async _load() {
    try {
      const raw = await readFile(this.stateFilePath, 'utf8');
      const parsed = safeJsonParse(raw);
      if (parsed && typeof parsed === 'object') {
        this.state = normalizeState(parsed);
        this._appendAudit('mma_state_loaded', { entries: Object.keys(this.state.entries).length }, false);
        return;
      }
      this._appendAudit('mma_state_load_invalid', { reason: 'json-invalido' }, false);
    } catch (error) {
      if (String(error?.code || '') === 'ENOENT') {
        this._appendAudit('mma_state_missing', { path: this.stateFilePath }, false);
      } else {
        this._appendAudit('mma_state_load_error', { error: String(error?.message || error) }, false);
      }
    }
  }
}

const stateStore = new PremiumStateStore();
const inflightByCity = new Map();
const backgroundRefreshQueued = new Set();

function makeController(ms) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), ms);
  return { controller, timeout };
}

async function fetchJson(url, timeoutMs = HIST_FETCH_TIMEOUT_MS) {
  const { controller, timeout } = makeController(timeoutMs);
  try {
    const response = await fetch(url, { signal: controller.signal, headers: { accept: 'application/json' } });
    if (!response.ok) return null;
    return await response.json();
  } catch {
    return null;
  } finally {
    clearTimeout(timeout);
  }
}

function encode(value) {
  return encodeURIComponent(String(value || '')).replace(/\+/g, '%20');
}

function buildArchiveUrl({ lat, lon, zoneId, startDate, endDate }) {
  return `https://archive-api.open-meteo.com/v1/archive?latitude=${lat}&longitude=${lon}&start_date=${startDate}&end_date=${endDate}&daily=temperature_2m_max&timezone=${encode(zoneId)}`;
}

function buildHistoricalDailyUrl({ lat, lon, zoneId, startDate, endDate, modelIds }) {
  return `https://historical-forecast-api.open-meteo.com/v1/forecast?latitude=${lat}&longitude=${lon}&start_date=${startDate}&end_date=${endDate}&daily=temperature_2m_max&timezone=${encode(zoneId)}&models=${encode(modelIds.join(','))}`;
}

function buildPreviousRunsDailyUrl({ lat, lon, zoneId, startDate, endDate, modelIds, horizonDays }) {
  return `https://previous-runs-api.open-meteo.com/v1/forecast?latitude=${lat}&longitude=${lon}&start_date=${startDate}&end_date=${endDate}&daily=temperature_2m_max_previous_day${horizonDays}&timezone=${encode(zoneId)}&models=${encode(modelIds.join(','))}`;
}

function buildPreviousRunsHourlyUrl({ lat, lon, zoneId, startDate, endDate, modelIds, horizonDays }) {
  return `https://previous-runs-api.open-meteo.com/v1/forecast?latitude=${lat}&longitude=${lon}&start_date=${startDate}&end_date=${endDate}&hourly=temperature_2m_previous_day${horizonDays}&timezone=${encode(zoneId)}&models=${encode(modelIds.join(','))}`;
}

function parseDailyDates(daily) {
  const times = Array.isArray(daily?.time) ? daily.time : [];
  return times.filter((t) => typeof t === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(t));
}

function parseDoubleArray(arr) {
  if (!Array.isArray(arr)) return [];
  return arr.map((v) => {
    if (typeof v === 'number' && Number.isFinite(v)) return v;
    const n = Number(v);
    return Number.isFinite(n) ? n : null;
  });
}

function dailyKeysForModel(modelId, baseVariable, horizonDays, onlyOneModel) {
  if (horizonDays <= 0) {
    return [`${baseVariable}_${modelId}`, ...(onlyOneModel ? [baseVariable] : [])];
  }
  const suffix = `previous_day${horizonDays}`;
  return [
    `${baseVariable}_${suffix}_${modelId}`,
    `${baseVariable}_${modelId}_${suffix}`,
    `${baseVariable}_${suffix}`,
    `${baseVariable}_${modelId}`,
    ...(onlyOneModel ? [baseVariable] : [])
  ];
}

function hourlyKeysForModel(modelId, horizonDays, onlyOneModel) {
  const suffix = `previous_day${horizonDays}`;
  return [
    `temperature_2m_${suffix}_${modelId}`,
    `temperature_2m_${modelId}_${suffix}`,
    `temperature_2m_${suffix}`,
    `temperature_2m_${modelId}`,
    ...(onlyOneModel ? ['temperature_2m'] : [])
  ];
}

function parseTimeToLocalDate(raw, zoneId) {
  if (typeof raw !== 'string') return null;
  const offsetTime = Date.parse(raw);
  if (Number.isFinite(offsetTime)) return toYmdInZone(new Date(offsetTime), zoneId);
  // fallback "YYYY-MM-DDTHH:mm"
  const direct = new Date(`${raw}:00Z`);
  if (Number.isFinite(direct.getTime())) return toYmdInZone(direct, zoneId);
  return /^\d{4}-\d{2}-\d{2}$/.test(raw) ? raw : null;
}

function parseDailySeriesFromJson(root, models, baseVariable, horizonDays) {
  const daily = root?.daily;
  if (!daily || typeof daily !== 'object') return {};
  const dates = parseDailyDates(daily);
  if (!dates.length) return {};
  const onlyOneModel = models.length === 1;
  const out = {};
  for (const model of models) {
    const candidates = dailyKeysForModel(model.modelId, baseVariable, horizonDays, onlyOneModel);
    const key = candidates.find((k) => Object.prototype.hasOwnProperty.call(daily, k));
    if (!key) continue;
    const values = parseDoubleArray(daily[key]);
    const series = {};
    const count = Math.min(dates.length, values.length);
    for (let i = 0; i < count; i += 1) {
      const v = values[i];
      if (!inValidRangeC(v)) continue;
      series[dates[i]] = v;
    }
    if (Object.keys(series).length) out[model.providerId] = series;
  }
  return out;
}

function parseHourlySeriesFromJson(root, models, zoneId, horizonDays) {
  const hourly = root?.hourly;
  if (!hourly || typeof hourly !== 'object') return {};
  const timeValues = Array.isArray(hourly.time) ? hourly.time : [];
  if (!timeValues.length) return {};
  const dates = timeValues.map((t) => parseTimeToLocalDate(t, zoneId));
  const onlyOneModel = models.length === 1;
  const out = {};
  for (const model of models) {
    const candidates = hourlyKeysForModel(model.modelId, horizonDays, onlyOneModel);
    const key = candidates.find((k) => Object.prototype.hasOwnProperty.call(hourly, k));
    if (!key) continue;
    const values = parseDoubleArray(hourly[key]);
    const count = Math.min(values.length, dates.length);
    const dailyMax = {};
    for (let i = 0; i < count; i += 1) {
      const date = dates[i];
      const v = values[i];
      if (!date || !inValidRangeC(v)) continue;
      if (!isFiniteNumber(dailyMax[date]) || v > dailyMax[date]) dailyMax[date] = v;
    }
    if (Object.keys(dailyMax).length) out[model.providerId] = dailyMax;
  }
  return out;
}

async function fetchArchiveObservedSeries(city) {
  const endDate = addDaysIso(todayIsoInZone(city.zoneId), -1);
  const startDate = BOOTSTRAP_START_H0;
  if (dayDiffIso(startDate, endDate) < 0) {
    return { valuesByDate: {}, sourceUrl: null, warning: null };
  }
  const url = buildArchiveUrl({
    lat: city.lat,
    lon: city.lon,
    zoneId: city.zoneId,
    startDate,
    endDate
  });
  const json = await fetchJson(url);
  if (!json) return { valuesByDate: {}, sourceUrl: url, warning: 'Open-Meteo Archive sin datos' };
  const dates = parseDailyDates(json.daily || {});
  const values = parseDoubleArray(json?.daily?.temperature_2m_max);
  const map = {};
  const count = Math.min(dates.length, values.length);
  for (let i = 0; i < count; i += 1) {
    const v = values[i];
    if (!inValidRangeC(v)) continue;
    map[dates[i]] = v;
  }
  return { valuesByDate: map, sourceUrl: url, warning: null };
}

async function fetchForecastDailyMaxBatch(city, horizonDays) {
  const endDate = addDaysIso(todayIsoInZone(city.zoneId), -1);
  const startDate = horizonDays === 0 ? BOOTSTRAP_START_H0 : BOOTSTRAP_START_H1_H2;
  if (dayDiffIso(startDate, endDate) < 0) {
    return { valuesByProvider: {}, warnings: [], startDate, endDate };
  }

  const modelIds = CALIBRATION_MODELS.map((m) => m.modelId);
  const warnings = [];

  if (horizonDays === 0) {
    const root = await fetchJson(buildHistoricalDailyUrl({ lat: city.lat, lon: city.lon, zoneId: city.zoneId, startDate, endDate, modelIds }));
    const series = parseDailySeriesFromJson(root, CALIBRATION_MODELS, 'temperature_2m_max', 0);
    if (!Object.keys(series).length) warnings.push(`Open-Meteo histórico H0 sin datos (${city.cityId})`);
    return { valuesByProvider: series, warnings, startDate, endDate };
  }

  const dailyRoot = await fetchJson(buildPreviousRunsDailyUrl({
    lat: city.lat,
    lon: city.lon,
    zoneId: city.zoneId,
    startDate,
    endDate,
    modelIds,
    horizonDays
  }));
  const dailySeries = parseDailySeriesFromJson(dailyRoot, CALIBRATION_MODELS, 'temperature_2m_max', horizonDays);
  if (Object.keys(dailySeries).length) {
    return { valuesByProvider: dailySeries, warnings, startDate, endDate };
  }

  const hourlyRoot = await fetchJson(buildPreviousRunsHourlyUrl({
    lat: city.lat,
    lon: city.lon,
    zoneId: city.zoneId,
    startDate,
    endDate,
    modelIds,
    horizonDays
  }));
  const hourlySeries = parseHourlySeriesFromJson(hourlyRoot, CALIBRATION_MODELS, city.zoneId, horizonDays);
  if (Object.keys(hourlySeries).length) {
    return { valuesByProvider: hourlySeries, warnings, startDate, endDate };
  }

  const proxyRoot = await fetchJson(buildHistoricalDailyUrl({ lat: city.lat, lon: city.lon, zoneId: city.zoneId, startDate, endDate, modelIds }));
  const proxySeries = parseDailySeriesFromJson(proxyRoot, CALIBRATION_MODELS, 'temperature_2m_max', 0);
  if (Object.keys(proxySeries).length) {
    warnings.push(`Previous Runs H${horizonDays} no disponible; usando proxy H0`);
    return { valuesByProvider: proxySeries, warnings, startDate, endDate };
  }

  warnings.push(`Open-Meteo histórico H${horizonDays} sin datos`);
  return { valuesByProvider: {}, warnings, startDate, endDate };
}

function buildVerificationSeriesForHorizon(city, horizonDays, observedSeries, forecastBatch) {
  const cityTodayIso = todayIsoInZone(city.zoneId);
  const verifications = [];
  const observedMap = observedSeries?.valuesByDate || {};
  const allDates = Object.keys(observedMap)
    .filter((date) => dayDiffIso(date, cityTodayIso) > 0 ? false : true)
    .filter((date) => dayDiffIso(date, cityTodayIso) < 0)
    .sort()
    .reverse();
  for (const dateIso of allDates) {
    if (horizonDays > 0 && dayDiffIso(BOOTSTRAP_START_H1_H2, dateIso) < 0) continue;
    if (horizonDays === 0 && dayDiffIso(BOOTSTRAP_START_H0, dateIso) < 0) continue;
    const observedMaxC = observedMap[dateIso];
    if (!inValidRangeC(observedMaxC)) continue;
    const entries = [];
    for (const model of CALIBRATION_MODELS) {
      const forecastMaxC = forecastBatch?.valuesByProvider?.[model.providerId]?.[dateIso];
      const validForecast = inValidRangeC(forecastMaxC);
      const absError = validForecast ? Math.abs(forecastMaxC - observedMaxC) : null;
      entries.push({
        providerId: model.providerId,
        providerName: model.providerName,
        absoluteErrorC: absError,
        squaredErrorC: absError == null ? null : (absError * absError),
        forecastMaxC: validForecast ? forecastMaxC : null
      });
    }
    if (!entries.some((e) => e.absoluteErrorC != null)) continue;
    verifications.push({
      targetDateIso: dateIso,
      horizonDays,
      observedMaxC,
      observedSourceUrl: observedSeries?.sourceUrl || null,
      entries
    });
  }
  return verifications;
}

function aggregateRecencyErrors(errors, cityTodayIso) {
  if (!errors.length) return { mae: null, rmse: null, count: 0 };
  let weightedAbs = 0;
  let weightedSq = 0;
  let weightSum = 0;
  for (const sample of errors) {
    const ageDays = Math.max(0, dayDiffIso(sample.targetDateIso, cityTodayIso));
    const recencyWeight = Math.exp(-ageDays / RECENCY_HALF_LIFE_DAYS);
    weightedAbs += sample.absoluteErrorC * recencyWeight;
    weightedSq += sample.squaredErrorC * recencyWeight;
    weightSum += recencyWeight;
  }
  if (weightSum <= 0) return { mae: null, rmse: null, count: 0 };
  return {
    mae: weightedAbs / weightSum,
    rmse: Math.sqrt(weightedSq / weightSum),
    count: errors.length
  };
}

function aggregateUnweightedErrors(errors) {
  if (!errors.length) return { mae: null, rmse: null, count: 0 };
  const mae = errors.reduce((a, b) => a + b.absoluteErrorC, 0) / errors.length;
  const rmse = Math.sqrt(errors.reduce((a, b) => a + b.squaredErrorC, 0) / errors.length);
  return { mae, rmse, count: errors.length };
}

function addMetricComponent(weightedComponents, metric, days, baseWeight, fullConfidenceDays) {
  if (!isFiniteNumber(metric) || !Number.isFinite(Number(days)) || days <= 0) return;
  const confidence = confidenceWeight(days, fullConfidenceDays);
  const effectiveWeight = baseWeight * (MIN_BLEND_CONFIDENCE_FACTOR + (1 - MIN_BLEND_CONFIDENCE_FACTOR) * confidence);
  weightedComponents.push([metric, effectiveWeight]);
}

function blendMetric({ recencyMetric, recencyDays, seasonalMetric, seasonalDays, longMetric, longDays }) {
  const weighted = [];
  addMetricComponent(weighted, recencyMetric, recencyDays, BLEND_WEIGHT_RECENCY, RECENT_DAYS_FOR_FULL_CONFIDENCE);
  addMetricComponent(weighted, seasonalMetric, seasonalDays, BLEND_WEIGHT_SEASONAL, SEASONAL_DAYS_FOR_FULL_CONFIDENCE);
  addMetricComponent(weighted, longMetric, longDays, BLEND_WEIGHT_LONG, LONG_DAYS_FOR_FULL_CONFIDENCE);
  const denominator = weighted.reduce((acc, [, w]) => acc + w, 0);
  if (denominator <= 0) return null;
  return weighted.reduce((acc, [m, w]) => acc + (m * w), 0) / denominator;
}

function buildProviderRanking(providerIds, verifications, cityTodayIso) {
  if (!providerIds?.length) return [];
  const totalVerifiedDays = verifications.length;
  const cityTodayDate = parseIsoDate(cityTodayIso) || new Date();
  const recentCutoffDate = new Date(cityTodayDate.getTime());
  recentCutoffDate.setUTCDate(recentCutoffDate.getUTCDate() - RECENT_WINDOW_DAYS);
  const recentCutoffIso = dateToIsoUtc(recentCutoffDate);
  const targetMonth = cityTodayDate.getUTCMonth() + 1;

  const intermediate = providerIds.map((providerId) => {
    const errors = [];
    let withinOne = 0;
    for (const verification of verifications) {
      const entry = verification.entries.find((e) => e.providerId === providerId);
      if (!entry || !isFiniteNumber(entry.absoluteErrorC) || !isFiniteNumber(entry.squaredErrorC)) continue;
      errors.push({
        targetDateIso: verification.targetDateIso,
        absoluteErrorC: entry.absoluteErrorC,
        squaredErrorC: entry.squaredErrorC
      });
      if (entry.absoluteErrorC <= 1.0) withinOne += 1;
    }
    const validDays = errors.length;
    const recencyMetrics = aggregateRecencyErrors(errors, cityTodayIso);
    const seasonalMetrics = aggregateUnweightedErrors(
      errors.filter((sample) => {
        const d = parseIsoDate(sample.targetDateIso);
        if (!d) return false;
        const month = d.getUTCMonth() + 1;
        return monthDistance(month, targetMonth) <= SEASONAL_MONTH_DISTANCE;
      })
    );
    const longMetrics = aggregateUnweightedErrors(errors);
    const recentDays = errors.filter((e) => e.targetDateIso >= recentCutoffIso).length;
    const seasonalDays = seasonalMetrics.count;
    const longDays = longMetrics.count;
    const mae = blendMetric({
      recencyMetric: recencyMetrics.mae,
      recencyDays: recentDays,
      seasonalMetric: seasonalMetrics.mae,
      seasonalDays,
      longMetric: longMetrics.mae,
      longDays
    });
    const rmse = blendMetric({
      recencyMetric: recencyMetrics.rmse,
      recencyDays: recentDays,
      seasonalMetric: seasonalMetrics.rmse,
      seasonalDays,
      longMetric: longMetrics.rmse,
      longDays
    });
    const coverage = totalVerifiedDays > 0 ? (validDays / totalVerifiedDays) : null;
    const withinOneRate = validDays > 0 ? (withinOne / validDays) : null;
    const reliabilityRecent = confidenceWeight(recentDays, RECENT_DAYS_FOR_FULL_CONFIDENCE);
    const reliabilitySeasonal = confidenceWeight(seasonalDays, SEASONAL_DAYS_FOR_FULL_CONFIDENCE);
    const reliabilityLong = confidenceWeight(longDays, LONG_DAYS_FOR_FULL_CONFIDENCE);
    const reliability = clamp(
      (reliabilityRecent * 0.45) + (reliabilitySeasonal * 0.30) + (reliabilityLong * 0.25),
      0,
      1
    );
    const accuracy = isFiniteNumber(mae) ? (1 / (1 + (mae / ACCURACY_SCALE_C))) : 0;
    const coverageAdj = clamp(coverage || 0, 0, 1);
    const spreadPenalty = (isFiniteNumber(mae) && isFiniteNumber(rmse)) ? Math.max(0, rmse - mae) : 0;
    const stability = clamp(1 / (1 + (spreadPenalty / RMSE_SPREAD_SCALE_C)), 0.70, 1.0);
    const rawScore = validDays === 0 ? 0 : accuracy * stability * (0.45 + 0.55 * coverageAdj) * (0.40 + 0.60 * reliability);
    return {
      providerId,
      providerName: CALIBRATION_MODELS.find((m) => m.providerId === providerId)?.providerName || providerId,
      baseWeight: baseWeightFor(providerId),
      verifiedDays: totalVerifiedDays,
      validForecastDays: validDays,
      coverage,
      maeC: mae,
      rmseC: rmse,
      withinOneC: withinOneRate,
      rawScore
    };
  });

  const positiveScores = intermediate.map((i) => i.rawScore).filter((v) => v > 0);
  const meanPositiveScore = positiveScores.length
    ? (positiveScores.reduce((a, b) => a + b, 0) / positiveScores.length)
    : 1.0;

  return intermediate.map((item) => {
    const multiplier = item.validForecastDays === 0
      ? NO_DATA_MULTIPLIER
      : clamp(item.rawScore / meanPositiveScore, MIN_MULTIPLIER, MAX_MULTIPLIER);
    const dynamicWeight = item.baseWeight * multiplier;
    return {
      providerId: item.providerId,
      providerName: item.providerName,
      baseWeight: item.baseWeight,
      dynamicWeight,
      multiplier,
      verifiedDays: item.verifiedDays,
      validForecastDays: item.validForecastDays,
      coverage: item.coverage,
      meanAbsoluteErrorC: item.maeC,
      rmseC: item.rmseC,
      withinOneDegreeRate: item.withinOneC,
      score: item.rawScore > 0 ? item.rawScore : null
    };
  }).sort((a, b) => {
    if (b.dynamicWeight !== a.dynamicWeight) return b.dynamicWeight - a.dynamicWeight;
    const aMae = isFiniteNumber(a.meanAbsoluteErrorC) ? a.meanAbsoluteErrorC : Number.POSITIVE_INFINITY;
    const bMae = isFiniteNumber(b.meanAbsoluteErrorC) ? b.meanAbsoluteErrorC : Number.POSITIVE_INFINITY;
    if (aMae !== bMae) return aMae - bMae;
    const aCov = a.coverage || 0;
    const bCov = b.coverage || 0;
    if (bCov !== aCov) return bCov - aCov;
    return String(a.providerName).localeCompare(String(b.providerName));
  });
}

function blendMultiplierWithCrossHorizon(providerId, horizonDays, primaryMultiplier, primaryBlendConfidence, crossHorizonSources) {
  if (!crossHorizonSources.length || primaryBlendConfidence >= 1.0) {
    return clamp(primaryMultiplier, MIN_MULTIPLIER, MAX_MULTIPLIER);
  }
  let weightedSum = 0;
  let totalWeight = 0;
  for (const source of crossHorizonSources) {
    const sourceMultiplier = source.multipliersByProvider[providerId];
    if (!isFiniteNumber(sourceMultiplier)) continue;
    const distance = Math.abs(Number(source.horizonDays) - Number(horizonDays));
    const proximityWeight = distance === 1
      ? CROSS_HORIZON_DISTANCE_ONE_WEIGHT
      : distance === 2
        ? CROSS_HORIZON_DISTANCE_TWO_WEIGHT
        : CROSS_HORIZON_DISTANCE_OTHER_WEIGHT;
    const sampleConfidence = confidenceWeight(source.verifiedDays, CROSS_HORIZON_FULL_CONFIDENCE_DAYS);
    const weight = proximityWeight * sampleConfidence;
    if (weight <= 0) continue;
    weightedSum += sourceMultiplier * weight;
    totalWeight += weight;
  }
  if (totalWeight <= 0) {
    return clamp(primaryMultiplier, MIN_MULTIPLIER, MAX_MULTIPLIER);
  }
  const fallbackMultiplier = weightedSum / totalWeight;
  const primaryWeight = Math.max(primaryBlendConfidence, CROSS_HORIZON_MIN_PRIMARY_BLEND);
  const blended = (primaryMultiplier * primaryWeight) + (fallbackMultiplier * (1 - primaryWeight));
  return clamp(blended, MIN_MULTIPLIER, MAX_MULTIPLIER);
}

function resolveWeightsForHorizon({ verificationsByHorizon, providerIds, horizonDays, cityTodayIso }) {
  const horizonVerifications = (verificationsByHorizon[horizonDays] || []).slice().sort((a, b) => String(b.targetDateIso).localeCompare(String(a.targetDateIso)));
  const ranking = buildProviderRanking(providerIds, horizonVerifications, cityTodayIso);
  const primaryMultipliers = Object.fromEntries(ranking.map((s) => [s.providerId, s.multiplier]));
  const primaryVerifiedDays = horizonVerifications.length;
  const primaryBlendConfidence = confidenceWeight(primaryVerifiedDays, PRIMARY_HORIZON_FULL_CONFIDENCE_DAYS);

  const crossHorizonSources = BOOTSTRAP_HORIZONS
    .filter((h) => h !== horizonDays)
    .map((h) => {
      const candidateVerifications = (verificationsByHorizon[h] || []).slice().sort((a, b) => String(b.targetDateIso).localeCompare(String(a.targetDateIso)));
      if (!candidateVerifications.length) return null;
      const candidateRanking = buildProviderRanking(providerIds, candidateVerifications, cityTodayIso);
      if (!candidateRanking.length) return null;
      return {
        horizonDays: h,
        verifiedDays: candidateVerifications.length,
        multipliersByProvider: Object.fromEntries(candidateRanking.map((s) => [s.providerId, s.multiplier]))
      };
    })
    .filter(Boolean);

  const weightsByProviderId = Object.fromEntries(providerIds.map((providerId) => {
    const primaryMultiplier = primaryMultipliers[providerId] ?? NO_DATA_MULTIPLIER;
    const blendedMultiplier = blendMultiplierWithCrossHorizon(
      providerId,
      horizonDays,
      primaryMultiplier,
      primaryBlendConfidence,
      crossHorizonSources
    );
    return [providerId, baseWeightFor(providerId) * blendedMultiplier];
  }));

  const lastVerifiedDateIso = horizonVerifications.map((v) => v.targetDateIso).sort().pop() || null;
  const warnings = [];
  if (horizonVerifications.length < MIN_VERIFIED_DAYS_FOR_STABLE_WEIGHTS) {
    warnings.push(`MMA H${horizonDays}: muestra corta (${horizonVerifications.length} días)`);
  }
  if (crossHorizonSources.length && primaryBlendConfidence < 1.0 && horizonVerifications.length < CROSS_HORIZON_BLEND_WARNING_THRESHOLD_DAYS) {
    const sourceText = crossHorizonSources
      .slice()
      .sort((a, b) => b.verifiedDays - a.verifiedDays)
      .map((s) => `H${s.horizonDays}:${s.verifiedDays}d`)
      .join(', ');
    warnings.push(`MMA H${horizonDays}: transferencia entre horizontes activa (base H${horizonDays}=${horizonVerifications.length}d, apoyo ${sourceText})`);
  }

  const dominant = Object.entries(weightsByProviderId).sort((a, b) => b[1] - a[1])[0] || null;
  const totalWeight = Object.values(weightsByProviderId).reduce((a, b) => a + (Number(b) || 0), 0);
  const dominantWeightPct = dominant && totalWeight > 0 ? round1((dominant[1] / totalWeight) * 100) : null;

  return {
    weightsByProviderId,
    verifiedDays: horizonVerifications.length,
    lastVerifiedDateIso,
    warnings,
    dominantProviderId: dominant?.[0] || null,
    dominantWeightPct,
    calibrationReady: true,
    calibrationProgress: 1.0
  };
}

async function computeCityCalibrationBundle({ cityId, zoneId, lat, lon, providerIds }) {
  const city = { cityId, zoneId, lat, lon };
  const warnings = [];
  const observedSeries = await fetchArchiveObservedSeries(city);
  if (observedSeries.warning) warnings.push(observedSeries.warning);

  const verificationsByHorizon = {};
  for (const horizonDays of BOOTSTRAP_HORIZONS) {
    const forecastBatch = await fetchForecastDailyMaxBatch(city, horizonDays);
    warnings.push(...(forecastBatch.warnings || []));
    verificationsByHorizon[horizonDays] = buildVerificationSeriesForHorizon(city, horizonDays, observedSeries, forecastBatch);
  }

  const cityTodayIso = todayIsoInZone(zoneId);
  const resolvedEntries = [];
  for (const horizonDays of BOOTSTRAP_HORIZONS) {
    const resolution = resolveWeightsForHorizon({
      verificationsByHorizon,
      providerIds,
      horizonDays,
      cityTodayIso
    });
    resolvedEntries.push({
      cityId,
      horizonDays,
      generatedAtUtc: nowIso(),
      cityTodayIsoAtGeneration: cityTodayIso,
      verifiedDays: resolution.verifiedDays,
      lastVerifiedDateIso: resolution.lastVerifiedDateIso,
      weightsByProviderId: resolution.weightsByProviderId,
      warnings: [...resolution.warnings, ...warnings].slice(0, 40),
      dominantProviderId: resolution.dominantProviderId,
      dominantWeightPct: resolution.dominantWeightPct,
      calibrationReady: resolution.calibrationReady,
      calibrationProgress: resolution.calibrationProgress,
      source: MMA_PREMIUM_ENGINE_VERSION
    });
  }

  return resolvedEntries;
}

function isEntryFresh(entry, zoneId) {
  if (!entry) return false;
  if (!Number.isFinite(Number(entry.verifiedDays)) || Number(entry.verifiedDays) <= 0) return false;
  const ts = Date.parse(entry.generatedAtUtc || '');
  if (!Number.isFinite(ts)) return false;
  if ((Date.now() - ts) > CACHE_TTL_MS) return false;
  // If city day rolled, mark stale so H0/H1/H2 boundaries refresh.
  const cityToday = todayIsoInZone(zoneId);
  if (entry.cityTodayIsoAtGeneration && entry.cityTodayIsoAtGeneration !== cityToday) return false;
  return true;
}

function filterWeightsForProviderIds(entry, providerIds) {
  const ids = Array.from(new Set((providerIds || []).filter(Boolean)));
  const weights = {};
  for (const providerId of ids) {
    const raw = Number(entry?.weightsByProviderId?.[providerId]);
    weights[providerId] = (isFiniteNumber(raw) && raw > 0) ? raw : (baseWeightFor(providerId) * NO_DATA_MULTIPLIER);
  }
  return weights;
}

function buildFallbackBundle({ cityId, providerIds }) {
  const modelKeys = ['ecmwf_ifs', 'ecmwf_ifs025', 'ecmwf_aifs025_single', 'gfs_global'];
  const staticByModel = {};
  // Start from base weights (rough parity) without importing city custom map to avoid cycles.
  for (const key of modelKeys) {
    const sourceId = modelKeyToProviderId(key);
    staticByModel[sourceId] = baseWeightFor(sourceId);
  }
  const horizons = {};
  for (const horizonDays of BOOTSTRAP_HORIZONS) {
    horizons[horizonDays] = {
      cityId,
      horizonDays,
      generatedAtUtc: nowIso(),
      cityTodayIsoAtGeneration: '',
      verifiedDays: 0,
      lastVerifiedDateIso: null,
      weightsByProviderId: filterWeightsForProviderIds({ weightsByProviderId: staticByModel }, providerIds),
      warnings: ['MMA premium web sin calibración aún: usando pesos base'],
      dominantProviderId: 'openmeteo-ifs025',
      dominantWeightPct: null,
      calibrationReady: false,
      calibrationProgress: 0,
      source: `${MMA_PREMIUM_ENGINE_VERSION}_FALLBACK`
    };
  }
  return horizons;
}

function scheduleBackgroundRefresh(params) {
  const key = String(params.cityId || '');
  if (!key || backgroundRefreshQueued.has(key)) return;
  backgroundRefreshQueued.add(key);
  queueMicrotask(() => {
    void ensureCityCalibration(params).finally(() => {
      backgroundRefreshQueued.delete(key);
    });
  });
}

async function ensureCityCalibration(params) {
  const { cityId } = params;
  if (inflightByCity.has(cityId)) return inflightByCity.get(cityId);
  const p = (async () => {
    await stateStore.ready();
    const entries = await computeCityCalibrationBundle(params);
    const hasAnyVerified = entries.some((entry) => Number.isFinite(Number(entry?.verifiedDays)) && Number(entry.verifiedDays) > 0);
    if (hasAnyVerified) {
      stateStore.setEntries(entries);
    }
    return entries;
  })();
  inflightByCity.set(cityId, p);
  try {
    return await p;
  } finally {
    inflightByCity.delete(cityId);
  }
}

function materializeBundle({ cityId, zoneId, providerIds }) {
  const horizons = {};
  let allFresh = true;
  let anyPresent = false;
  for (const horizonDays of BOOTSTRAP_HORIZONS) {
    const entry = stateStore.getEntry(cityId, horizonDays);
    if (entry) anyPresent = true;
    const fresh = isEntryFresh(entry, zoneId);
    allFresh = allFresh && fresh;
    if (entry) {
      horizons[horizonDays] = {
        ...entry,
        weightsByProviderId: filterWeightsForProviderIds(entry, providerIds)
      };
    }
  }
  return {
    anyPresent,
    allFresh,
    horizons: anyPresent ? horizons : null
  };
}

export async function getPremiumWeightsBundleForCity({
  cityId,
  zoneId,
  lat,
  lon,
  providerIds = [],
  mode = 'cache-only'
}) {
  await stateStore.ready();
  const normalizedProviders = Array.from(new Set((providerIds || []).filter(Boolean)));
  const materialized = materializeBundle({ cityId, zoneId, providerIds: normalizedProviders });
  if (materialized.anyPresent && materialized.allFresh) {
    return {
      source: MMA_PREMIUM_ENGINE_VERSION,
      cacheStatus: 'CACHED',
      horizons: materialized.horizons,
      auditTail: stateStore.getAudit(6)
    };
  }

  if (mode === 'ensure' && isFiniteNumber(lat) && isFiniteNumber(lon)) {
    const entries = await ensureCityCalibration({ cityId, zoneId, lat, lon, providerIds: normalizedProviders });
    const horizons = {};
    for (const entry of entries) {
      horizons[entry.horizonDays] = {
        ...entry,
        weightsByProviderId: filterWeightsForProviderIds(entry, normalizedProviders)
      };
    }
    return {
      source: MMA_PREMIUM_ENGINE_VERSION,
      cacheStatus: materialized.anyPresent ? 'REFRESHED' : 'CALCULATED',
      horizons,
      auditTail: stateStore.getAudit(6)
    };
  }

  if (isFiniteNumber(lat) && isFiniteNumber(lon)) {
    scheduleBackgroundRefresh({ cityId, zoneId, lat, lon, providerIds: normalizedProviders });
  }

  if (materialized.anyPresent) {
    return {
      source: MMA_PREMIUM_ENGINE_VERSION,
      cacheStatus: 'STALE',
      horizons: materialized.horizons,
      auditTail: stateStore.getAudit(6)
    };
  }

  return {
    source: MMA_PREMIUM_ENGINE_VERSION,
    cacheStatus: 'FALLBACK_STATIC',
    horizons: buildFallbackBundle({ cityId, providerIds: normalizedProviders }),
    auditTail: stateStore.getAudit(6)
  };
}

export function providerWeightsToModelWeights(weightsByProviderId = {}) {
  const out = {};
  for (const [providerId, weight] of Object.entries(weightsByProviderId || {})) {
    const modelKey = providerIdToModelKey(providerId);
    if (!modelKey) continue;
    if (!isFiniteNumber(Number(weight))) continue;
    out[modelKey] = Number(weight);
  }
  return out;
}
