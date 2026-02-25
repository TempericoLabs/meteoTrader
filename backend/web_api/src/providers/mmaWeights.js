const DEFAULT_WEIGHTS = Object.freeze({
  ecmwf_ifs: 0.66,
  gfs_global: 0.34
});

const CITY_WEIGHTS = Object.freeze({
  'miami': { ecmwf_ifs: 0.52, gfs_global: 0.48 },
  'london': { ecmwf_ifs: 0.78, gfs_global: 0.22 },
  'toronto': { ecmwf_ifs: 0.74, gfs_global: 0.26 },
  'seattle': { ecmwf_ifs: 0.58, gfs_global: 0.42 },
  'dallas': { ecmwf_ifs: 0.55, gfs_global: 0.45 },
  'wellington': { ecmwf_ifs: 0.71, gfs_global: 0.29 },
  'ankara': { ecmwf_ifs: 0.67, gfs_global: 0.33 },
  'seoul': { ecmwf_ifs: 0.69, gfs_global: 0.31 },
  'new-york': { ecmwf_ifs: 0.72, gfs_global: 0.28 },
  'chicago': { ecmwf_ifs: 0.61, gfs_global: 0.39 },
  'atlanta': { ecmwf_ifs: 0.57, gfs_global: 0.43 },
  'paris': { ecmwf_ifs: 0.76, gfs_global: 0.24 },
  'buenos-aires': { ecmwf_ifs: 0.64, gfs_global: 0.36 },
  'sao-paulo': { ecmwf_ifs: 0.63, gfs_global: 0.37 }
});

export const MMA_WEIGHTS_VERSION = 'WEB_MMA_WEIGHTS_V1';

export const OPEN_METEO_MODEL_LABELS = Object.freeze({
  ecmwf_ifs: 'Open-Meteo ECMWF IFS',
  gfs_global: 'Open-Meteo GFS Global'
});

function clone(obj) {
  return JSON.parse(JSON.stringify(obj));
}

export function getCityMmaWeights(cityId) {
  return clone(CITY_WEIGHTS[cityId] || DEFAULT_WEIGHTS);
}

export function getOpenMeteoModelLabel(modelKey) {
  return OPEN_METEO_MODEL_LABELS[modelKey] || modelKey;
}

export function normalizeWeightsForAvailable(baseWeights, availableModelKeys) {
  const keys = Array.from(new Set((availableModelKeys || []).filter(Boolean)));
  if (!keys.length) return {};
  let sum = 0;
  const out = {};
  for (const key of keys) {
    const raw = Number(baseWeights?.[key]);
    const w = Number.isFinite(raw) && raw > 0 ? raw : 0;
    out[key] = w;
    sum += w;
  }
  if (sum <= 0) {
    const equal = 1 / keys.length;
    for (const key of keys) out[key] = equal;
    return out;
  }
  for (const key of keys) out[key] = out[key] / sum;
  return out;
}
