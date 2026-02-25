const BASE_DATE_UTC = '2026-02-24';
const GENERATED_AT_UTC = '2026-02-24T12:15:00Z';

const HORIZON_ORDER = ['today', 'tomorrow', 'dayAfter'];
const HORIZON_LABELS = {
  today: 'Hoy',
  tomorrow: 'Mañana',
  dayAfter: 'Pasado'
};
const HORIZON_OFFSETS = {
  today: 0,
  tomorrow: 1,
  dayAfter: 2
};

const CITY_SEEDS = [
  { id: 'miami', name: 'Miami', metarCode: 'KMIA', zoneId: 'America/New_York', unit: 'F', localTime: '07:14', metar: 58, station: 82, mm: 66.9, mma: 67.4, delta: 0, pattern: ['today'], dominantModel: 'GFS', dominantWeightPct: 32.1 },
  { id: 'london', name: 'London', metarCode: 'EGLC', zoneId: 'Europe/London', unit: 'C', localTime: '12:14', metar: 11, station: 12, mm: 12.2, mma: 12.3, delta: 0, pattern: ['today', 'tomorrow'], dominantModel: 'Open-Meteo ECMWF IFS', dominantWeightPct: 46.7 },
  { id: 'toronto', name: 'Toronto', metarCode: 'CYYZ', zoneId: 'America/Toronto', unit: 'C', localTime: '07:14', metar: -2, station: 1, mm: -1.8, mma: -1.2, delta: -1, pattern: ['tomorrow'], dominantModel: 'Open-Meteo ECMWF IFS 0.25', dominantWeightPct: 38.8 },
  { id: 'seattle', name: 'Seattle', metarCode: 'KSEA', zoneId: 'America/Los_Angeles', unit: 'F', localTime: '04:14', metar: 43, station: 52, mm: 47.6, mma: 48.1, delta: 0, pattern: ['today', 'dayAfter'], dominantModel: 'NOAA NWS Blend', dominantWeightPct: 28.4 },
  { id: 'dallas', name: 'Dallas', metarCode: 'KDAL', zoneId: 'America/Chicago', unit: 'F', localTime: '06:14', metar: 41, station: 59, mm: 58.5, mma: 59.0, delta: 1, pattern: ['today', 'tomorrow', 'dayAfter'], dominantModel: 'Windy ECMWF IFS', dominantWeightPct: 35.6 },
  { id: 'wellington', name: 'Wellington', metarCode: 'NZWN', zoneId: 'Pacific/Auckland', unit: 'C', localTime: '01:14', metar: 17, station: 22, mm: 19.4, mma: 19.8, delta: 0, pattern: [], closedBySchedule: true, dominantModel: 'Open-Meteo ECMWF IFS', dominantWeightPct: 31.2 },
  { id: 'ankara', name: 'Ankara', metarCode: 'LTAC', zoneId: 'Europe/Istanbul', unit: 'C', localTime: '15:14', metar: 7, station: 8, mm: 8.3, mma: 8.7, delta: 2, pattern: ['today'], dominantModel: 'OpenWeatherMap', dominantWeightPct: 24.1 },
  { id: 'seoul', name: 'Seoul', metarCode: 'RKSI', zoneId: 'Asia/Seoul', unit: 'C', localTime: '21:14', metar: 3, station: 4, mm: 3.1, mma: 3.4, delta: 0, pattern: [], closedBySchedule: true, dominantModel: 'Open-Meteo ECMWF IFS', dominantWeightPct: 29.7 },
  { id: 'new-york', name: 'New York', metarCode: 'KLGA', zoneId: 'America/New_York', unit: 'F', localTime: '07:14', metar: 31, station: 36, mm: 35.8, mma: 36.6, delta: 0, pattern: ['tomorrow', 'dayAfter'], dominantModel: 'Open-Meteo ECMWF IFS', dominantWeightPct: 41.9 },
  { id: 'chicago', name: 'Chicago', metarCode: 'KORD', zoneId: 'America/Chicago', unit: 'F', localTime: '06:14', metar: 23, station: 29, mm: 31.2, mma: 32.1, delta: -1, pattern: ['today'], dominantModel: 'NOAA NWS Blend', dominantWeightPct: 33.5 },
  { id: 'atlanta', name: 'Atlanta', metarCode: 'KATL', zoneId: 'America/New_York', unit: 'F', localTime: '07:14', metar: 34, station: 48, mm: 42.5, mma: 43.0, delta: -2, pattern: ['today', 'tomorrow'], dominantModel: 'Windy GFS', dominantWeightPct: 26.8 },
  { id: 'paris', name: 'Paris', metarCode: 'LFPG', zoneId: 'Europe/Paris', unit: 'C', localTime: '13:14', metar: 12, station: 13, mm: 12.4, mma: 12.6, delta: 0, pattern: ['today'], dominantModel: 'Open-Meteo ECMWF IFS', dominantWeightPct: 44.3 },
  { id: 'buenos-aires', name: 'Buenos Aires', metarCode: 'SAEZ', zoneId: 'America/Argentina/Buenos_Aires', unit: 'C', localTime: '09:14', metar: 24, station: 30, mm: 27.2, mma: 27.9, delta: 1, pattern: ['dayAfter'], dominantModel: 'OpenWeatherMap', dominantWeightPct: 22.9 },
  { id: 'sao-paulo', name: 'Sao Paulo', metarCode: 'SBGR', zoneId: 'America/Sao_Paulo', unit: 'C', localTime: '09:14', metar: 22, station: 28, mm: 26.3, mma: 26.8, delta: 1, pattern: ['today', 'dayAfter'], dominantModel: 'Open-Meteo ECMWF IFS 0.25', dominantWeightPct: 39.6 }
];

function addDays(dateStr, days) {
  const d = new Date(`${dateStr}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() + days);
  return d.toISOString().slice(0, 10);
}

function round(value, digits = 1) {
  const p = 10 ** digits;
  return Math.round(value * p) / p;
}

function tempDisplay(value, unit) {
  return `${round(value, unit === 'F' ? 1 : 1)}°${unit}`;
}

function convertTemp(value, unit) {
  if (unit === 'C') {
    return { primary: tempDisplay(value, 'C'), secondary: tempDisplay(value * 9 / 5 + 32, 'F') };
  }
  return { primary: tempDisplay(value, 'F'), secondary: tempDisplay((value - 32) * 5 / 9, 'C') };
}

function deltaDisplay(value, unit) {
  const prefix = value > 0 ? '+' : value < 0 ? '' : '±';
  if (value === 0) return `±0°${unit}`;
  return `${prefix}${Math.abs(value)}°${unit}`;
}

function horizonRefTag(keys) {
  const map = { today: 'H', tomorrow: 'M', dayAfter: 'P' };
  const tag = HORIZON_ORDER.filter((k) => keys.includes(k)).map((k) => map[k]).join('');
  return tag && tag !== 'H' ? `[${tag}]` : '';
}

function tickerSuffix(horizonKey) {
  if (horizonKey === 'tomorrow') return ' (Mañana)';
  if (horizonKey === 'dayAfter') return ' (Pasado)';
  return '';
}

function buildQuestion(cityName, label, dateStr) {
  return `Will the highest temperature in ${cityName} be ${label} on ${dateStr}?`;
}

function mkOpportunity({
  cityName,
  dateStr,
  label,
  direction,
  actionSide,
  marketYesPct,
  modelYesPct,
  askYesCents,
  askNoCents,
  executableEdgePct,
  fillPct,
  liquidityBook,
  volumeBucket,
  costPct,
  signal,
  reason
}) {
  const spreadPct = round(askYesCents + askNoCents - 100, 1);
  return {
    id: `${cityName.toLowerCase().replace(/\s+/g, '-')}-${dateStr}-${label}`,
    bucketLabel: label,
    question: buildQuestion(cityName, label, dateStr),
    direction,
    actionSide,
    signal,
    marketProbabilityYesPct: round(marketYesPct, 1),
    modelProbabilityYesPct: round(modelYesPct, 1),
    executableEdgePct: round(executableEdgePct, 1),
    fillProbabilityPct: round(fillPct, 1),
    totalCostPct: round(costPct, 1),
    spreadPct,
    liquidityBook: Math.round(liquidityBook),
    volumeBucket: Math.round(volumeBucket),
    yesAskCents: round(askYesCents, 1),
    noAskCents: round(askNoCents, 1),
    shouldTrade: executableEdgePct >= 2.5 && fillPct >= 55 && costPct <= 22 && liquidityBook >= 250 && spreadPct <= 12,
    reasonSimple: reason
  };
}

function buildHorizonOpportunities(seed, horizonKey) {
  if (!seed.pattern.includes(horizonKey)) return [];
  const offset = HORIZON_OFFSETS[horizonKey];
  const dateStr = addDays(BASE_DATE_UTC, offset);
  const isF = seed.unit === 'F';
  const center = seed.mma;
  if (horizonKey === 'today') {
    if (seed.id === 'london') {
      return [
        mkOpportunity({ cityName: seed.name, dateStr, label: '12°C', direction: 'RANGE', actionSide: 'YES', marketYesPct: 24, modelYesPct: 31, askYesCents: 24, askNoCents: 79, executableEdgePct: 8.3, fillPct: 67.5, liquidityBook: 1482, volumeBucket: 12309, costPct: 4.0, signal: 'GREEN', reason: 'MMA 12.3°C muy cerca del bucket y spread estrecho.' }),
        mkOpportunity({ cityName: seed.name, dateStr, label: '13°C', direction: 'UNDER', actionSide: 'NO', marketYesPct: 66, modelYesPct: 48, askYesCents: 67, askNoCents: 36, executableEdgePct: 18.7, fillPct: 61.7, liquidityBook: 1188, volumeBucket: 11452, costPct: 5.8, signal: 'YELLOW', reason: 'Entrada posible con cuidado: edge alto pero ejecución menos cómoda.' })
      ];
    }
    const lowLabel = isF ? `${Math.floor(center - 1)}-${Math.floor(center)}°F` : `${Math.floor(center)}°C`;
    const hiLabel = isF ? `${Math.floor(center + 1)}-${Math.floor(center + 2)}°F` : `${Math.floor(center + 1)}°C`;
    return [
      mkOpportunity({ cityName: seed.name, dateStr, label: lowLabel, direction: 'RANGE', actionSide: 'YES', marketYesPct: 32, modelYesPct: 39, askYesCents: 34, askNoCents: 69, executableEdgePct: 6.4 + (seed.id.length % 3), fillPct: 66 - (seed.id.length % 7), liquidityBook: 900 + seed.id.length * 38, volumeBucket: 1700 + seed.id.length * 410, costPct: 5.4 + (seed.id.length % 3), signal: 'GREEN', reason: 'Coincide con MMA y mantiene costes contenidos.' }),
      mkOpportunity({ cityName: seed.name, dateStr, label: hiLabel, direction: 'UNDER', actionSide: 'NO', marketYesPct: 54, modelYesPct: 42, askYesCents: 57, askNoCents: 47, executableEdgePct: 4.1 + (seed.id.length % 4), fillPct: 58 + (seed.id.length % 6), liquidityBook: 620 + seed.id.length * 27, volumeBucket: 1100 + seed.id.length * 250, costPct: 7.1 + (seed.id.length % 4), signal: 'YELLOW', reason: 'Mercado todavía operable, pero con peor ejecución que la mejor opción.' })
    ];
  }

  const baseLabel = isF
    ? `${Math.floor(center + offset)}-${Math.floor(center + offset + 1)}°F`
    : `${Math.floor(center + offset)}°C`;
  return [
    mkOpportunity({ cityName: seed.name, dateStr, label: baseLabel, direction: offset % 2 === 0 ? 'OVER' : 'UNDER', actionSide: offset % 2 === 0 ? 'YES' : 'NO', marketYesPct: 28 + ((seed.id.length + offset) % 21), modelYesPct: 34 + ((seed.id.length + offset * 3) % 19), askYesCents: 30 + ((seed.id.length + offset * 4) % 16), askNoCents: 63 + ((seed.id.length + offset * 2) % 10), executableEdgePct: 3.2 + ((seed.id.length + offset) % 7), fillPct: 55 + ((seed.id.length + offset) % 15), liquidityBook: 420 + seed.id.length * 33 + offset * 40, volumeBucket: 900 + seed.id.length * 210 + offset * 120, costPct: 8.0 + ((seed.id.length + offset) % 6), signal: offset === 1 ? 'YELLOW' : 'GREEN', reason: 'Oportunidad de horizonte futuro: útil para vigilancia y planificación.' })
  ];
}

function buildDiscardedTraces(seed, horizonKey, targetDate) {
  const traces = [];
  const horizonOpen = seed.pattern.includes(horizonKey);
  if (!horizonOpen) {
    traces.push({
      stage: 'EXECUTION_CONTROL',
      status: 'DISCARDED',
      reason: 'Sin oportunidades en este horizonte',
      details: horizonKey === 'today'
        ? ['Señal base PASS o filtros de ejecución no superados para los buckets activos.']
        : ['No se han seleccionado buckets ejecutables en este horizonte con el mock actual.']
    });
    return traces;
  }

  traces.push({
    stage: 'INPUT',
    status: 'KEPT',
    reason: 'MM/MMA disponibles para evaluar mercados',
    details: [`MMA ${tempDisplay(seed.mma, seed.unit)} · MM ${tempDisplay(seed.mm, seed.unit)}`]
  });
  traces.push({
    stage: 'EXECUTION_CONTROL',
    status: 'DISCARDED',
    reason: 'Ejemplo de bucket descartado por spread/coste',
    details: ['Spread estimado 15.0% > límite duro 12.0%', 'Coste total 23.1% > límite duro 22.0%']
  });
  return traces;
}

function buildCityRecord(seed) {
  const horizons = HORIZON_ORDER.map((key) => {
    const targetDate = addDays(BASE_DATE_UTC, HORIZON_OFFSETS[key]);
    const opportunities = buildHorizonOpportunities(seed, key);
    const top = opportunities.slice().sort((a, b) => b.executableEdgePct - a.executableEdgePct)[0] || null;
    return {
      key,
      label: HORIZON_LABELS[key],
      targetDate,
      metarAvailable: key === 'today',
      statusMessage: opportunities.length === 0
        ? (key === 'today' ? 'Sin oportunidades en Hoy' : `Sin oportunidades en ${HORIZON_LABELS[key]}`)
        : `${opportunities.length} oportunidad(es) visible(s)` ,
      referenceTop: top,
      opportunities,
      decisionTrace: buildDiscardedTraces(seed, key, targetDate)
    };
  });

  const horizonsWithOpportunities = horizons.filter((h) => h.opportunities.length > 0).map((h) => h.key);
  const allOps = horizons.flatMap((h) => h.opportunities.map((op) => ({ ...op, horizonKey: h.key, horizonLabel: h.label, targetDate: h.targetDate })));
  const topGlobal = allOps.slice().sort((a, b) => b.executableEdgePct - a.executableEdgePct)[0] || null;
  const topToday = allOps.filter((op) => op.horizonKey === 'today').sort((a, b) => b.executableEdgePct - a.executableEdgePct)[0] || null;

  const metarTemps = convertTemp(seed.metar, seed.unit);
  const stationTemps = convertTemp(seed.station, seed.unit);
  const mmTemps = convertTemp(seed.mm, seed.unit);
  const mmaTemps = convertTemp(seed.mma, seed.unit);
  const observedMax = Math.max(seed.metar, seed.station);
  const mmInvalid = seed.mm < observedMax - 0.4;
  const mmaInvalid = seed.mma < observedMax - 0.4;

  const topSummary = topGlobal
    ? {
        horizonKey: topGlobal.horizonKey,
        horizonLabel: topGlobal.horizonLabel,
        direction: topGlobal.direction,
        actionSide: topGlobal.actionSide,
        signal: topGlobal.signal,
        executableEdgePct: topGlobal.executableEdgePct,
        shortText: `${topGlobal.direction} · BET ${topGlobal.actionSide} ${topGlobal.executableEdgePct.toFixed(1)}%`,
        tickerText: `${seed.name}${tickerSuffix(topGlobal.horizonKey)} ${topGlobal.direction} ${topGlobal.actionSide} ${topGlobal.executableEdgePct.toFixed(1)}%`
      }
    : null;

  return {
    id: seed.id,
    city: {
      id: seed.id,
      name: seed.name,
      metarCode: seed.metarCode,
      zoneId: seed.zoneId,
      displayUnit: seed.unit
    },
    source: 'MOCK',
    generatedAtUtc: GENERATED_AT_UTC,
    localTime: seed.localTime,
    isClosedBySchedule: !!seed.closedBySchedule,
    horizonAvailability: {
      keys: horizonsWithOpportunities,
      tag: horizonRefTag(horizonsWithOpportunities)
    },
    summary: {
      metar: { primary: metarTemps.primary, secondary: metarTemps.secondary },
      delta: { value: seed.delta, display: deltaDisplay(seed.delta, seed.unit) },
      station: { primary: stationTemps.primary, secondary: stationTemps.secondary },
      mm: { primary: mmTemps.primary, secondary: mmTemps.secondary, invalidToday: mmInvalid },
      mma: { primary: mmaTemps.primary, secondary: mmaTemps.secondary, invalidToday: mmaInvalid, status: 'READY' }
    },
    topSummary,
    topTodaySummary: topToday
      ? {
          horizonKey: topToday.horizonKey,
          horizonLabel: topToday.horizonLabel,
          direction: topToday.direction,
          actionSide: topToday.actionSide,
          signal: topToday.signal,
          executableEdgePct: topToday.executableEdgePct,
          shortText: `${topToday.direction} · BET ${topToday.actionSide} ${topToday.executableEdgePct.toFixed(1)}%`
        }
      : null,
    metarDetail: {
      observedAtLocal: `${BASE_DATE_UTC.split('-').reverse().join('-')} ${seed.localTime}`,
      previousObservedAtLocal: `${BASE_DATE_UTC.split('-').reverse().join('-')} ${String(Math.max(0, parseInt(seed.localTime.slice(0, 2), 10) - 1)).padStart(2, '0')}:${seed.localTime.slice(3)}`,
      previousAgeHours: 1,
      metarCurrent: metarTemps,
      metarPrevious: convertTemp(seed.metar - (seed.delta || 0), seed.unit),
      tafSummary: 'TAF disponible (mock): sin fenómeno crítico para el horizonte inmediato.',
      stationControl: stationTemps,
      observedMax: { primary: tempDisplay(observedMax, seed.unit), secondary: convertTemp(observedMax, seed.unit).secondary }
    },
    premium: {
      dominantModel: seed.dominantModel,
      dominantWeightPct: seed.dominantWeightPct,
      source: 'PREFERENCES_CACHE',
      cacheStatus: 'CACHED',
      verifiedDays: 1512 + (seed.id.length % 9),
      pendingDays: 0,
      updatedAtLocal: `${BASE_DATE_UTC.split('-').reverse().join('-')} ${seed.localTime}`
    },
    horizons,
    warnings: seed.closedBySchedule ? ['Ciudad cerrada por horario local (>18h) en la lógica operativa.'] : []
  };
}

const CITY_RECORDS = CITY_SEEDS.map(buildCityRecord);

function clone(data) {
  return JSON.parse(JSON.stringify(data));
}

export function getMeta() {
  return {
    app: 'polyMeteo',
    apiVersion: 'v1',
    source: 'MOCK',
    generatedAtUtc: GENERATED_AT_UTC,
    baseDateUtc: BASE_DATE_UTC,
    mode: 'fase-b1-scaffold',
    notes: [
      'Backend y frontend web en scaffold local para Fase B1.',
      'No usa aún WeatherRepository real ni scraping/proveedores en backend.'
    ]
  };
}

export function listCitiesSummary() {
  return clone(CITY_RECORDS.map((city) => ({
    id: city.id,
    city: city.city,
    source: city.source,
    generatedAtUtc: city.generatedAtUtc,
    localTime: city.localTime,
    isClosedBySchedule: city.isClosedBySchedule,
    horizonAvailability: city.horizonAvailability,
    summary: city.summary,
    topSummary: city.topSummary,
    topTodaySummary: city.topTodaySummary,
    warnings: city.warnings
  })));
}

export function getCityDetail(cityId) {
  const city = CITY_RECORDS.find((item) => item.id === cityId);
  return city ? clone(city) : null;
}

export function listCityIds() {
  return CITY_RECORDS.map((c) => c.id);
}

export function listCityManifest() {
  return clone(CITY_RECORDS.map((city) => ({
    id: city.id,
    name: city.city.name,
    metarCode: city.city.metarCode,
    displayUnit: city.city.displayUnit,
    zoneId: city.city.zoneId
  })));
}

export function getAllCityDetails() {
  return clone(CITY_RECORDS);
}
