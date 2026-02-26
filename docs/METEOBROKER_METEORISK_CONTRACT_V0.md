# METEOBROKER_METEORISK_CONTRACT_V0

Version: 0.1 (propuesta futura)
Date: 2026-02-26
Status: Draft for future implementation (not implemented in `meteoTrader`)
Audience: `polyMeteo`, `polymeteo-bot`, future backend services

## 0. Purpose

Define a clear integration contract between:

- `Meteobroker`: weather data backbone (ingestion + normalization + persistence of weather snapshots)
- `Meteorisk`: market/contract engine (contract semantics + executable risk/edge + auditability)

Goals:
- avoid duplicated logic
- avoid ownership ambiguity
- preserve `polyMeteo` learnings (`M`, `S`, truncation, market viability rules)
- make `polymeteo-bot` consume the right data without depending on a slow path in the hot loop

This document is a technical proposal, not a mandate. It should be implemented incrementally.

## 1. Design Principles

1. Single ownership of each responsibility.
2. Weather normalization and market semantics are separate concerns.
3. Contract rounding/truncation rules live in `Meteorisk` (contract-specific).
4. `Meteobroker` can expose raw/normalized weather and uncertainty, but should not encode Polymarket contract logic.
5. `Meteorisk` can consume `Meteobroker` snapshots and market microstructure to produce actionable, auditable outputs.
6. `polymeteo-bot` must not depend on synchronous calls to these services in the hot path; it should consume cached snapshots/adapters.

## 2. Ownership Matrix (authoritative)

## 2.1 Meteobroker owns
- Weather source connectors (METAR, TAF, model APIs, Wunderground/weather.com fallback paths, etc.)
- Unit normalization (C/F handling)
- Timezone normalization and target-date alignment metadata
- Model run metadata (provider, run time, lead time, horizon)
- Forecast snapshots (raw and normalized)
- Observation snapshots (including `M`, `S` candidates and source trace)
- Optional ensemble/distribution payloads (pre-contract)
- Weather-only quality flags and warnings

## 2.2 Meteorisk owns
- Market contract parsing (question -> date/unit/condition/bucket semantics)
- Contract precision rules (whole-degree, truncation, rounding semantics)
- Mapping weather state to market/bucket probabilities
- `observedFloor` and market impossibility filtering
- Dominance and near-resolution suppression rules
- Executable edge (spread/liquidity/fill/costs)
- BET/PASS (for `polyMeteo`) and market risk flags (for bot and UI)
- Decision trace (why kept/rejected)

## 2.3 Shared by contract (not by implementation)
- City catalog IDs and canonical identifiers
- Canonical timestamp format and IDs
- Error envelope format
- Versioning policy

## 3. Canonical IDs and Types

## 3.1 Canonical city ID
Must match `polyMeteo` catalog IDs (examples):
- `miami`
- `london`
- `seoul`
- `new-york`
- `buenos-aires`

## 3.2 Canonical time format
All timestamps should include both:
- `timestamp_utc_iso` (ISO8601 UTC, e.g. `2026-02-26T11:34:21.123Z`)
- `timestamp_epoch_ms` (Unix epoch ms UTC)

## 3.3 Canonical units
Use enum strings:
- `C`
- `F`

## 3.4 Horizon naming
Use integer + optional label:
- `horizon_days`: `0`, `1`, `2`
- optional labels: `H0`, `H1`, `H2`

## 4. Shared Error Envelope (all services)

All non-2xx responses should return:

```json
{
  "ok": false,
  "error": {
    "code": "INVALID_REQUEST",
    "message": "Missing city_id",
    "details": {
      "field": "city_id"
    }
  },
  "request_id": "req_01HXYZ...",
  "timestamp_utc_iso": "2026-02-26T11:34:21.123Z",
  "timestamp_epoch_ms": 1772105661123
}
```

Recommended error codes:
- `INVALID_REQUEST`
- `NOT_FOUND`
- `UPSTREAM_TIMEOUT`
- `UPSTREAM_INVALID_PAYLOAD`
- `DATA_NOT_READY`
- `RATE_LIMITED`
- `INTERNAL_ERROR`

## 5. Service Health Contract

## 5.1 Meteobroker `GET /healthz`
Response (example):

```json
{
  "ok": true,
  "service": "meteobroker",
  "version": "0.1.0",
  "timestamp_utc_iso": "2026-02-26T11:34:21.123Z",
  "timestamp_epoch_ms": 1772105661123,
  "sources": {
    "metar": { "ok": true, "last_success_ms_ago": 1240 },
    "models": { "ok": true, "last_success_ms_ago": 2830 },
    "control_station": { "ok": true, "last_success_ms_ago": 910 }
  }
}
```

## 5.2 Meteorisk `GET /healthz`
Response (example):

```json
{
  "ok": true,
  "service": "meteorisk",
  "version": "0.1.0",
  "timestamp_utc_iso": "2026-02-26T11:34:21.123Z",
  "timestamp_epoch_ms": 1772105661123,
  "engines": {
    "contract_parser": { "ok": true },
    "viability": { "ok": true },
    "execution_edge": { "ok": true }
  }
}
```

## 6. Meteobroker API (minimum viable contract)

## 6.1 `GET /v1/cities`
Purpose:
- expose canonical city catalog used by downstream systems

Response (shape):

```json
{
  "ok": true,
  "cities": [
    {
      "city_id": "london",
      "name": "London",
      "metar_code": "EGLC",
      "lat": 51.514,
      "lon": 0.037,
      "zone_id": "Europe/London",
      "display_unit": "C"
    }
  ],
  "catalog_version": "2026-02-26-v1"
}
```

## 6.2 `GET /v1/weather/observed-state`
Purpose:
- return normalized observed weather state for a city/date/horizon
- includes `M` and `S` and trace metadata

Query params:
- `city_id` (required)
- `target_date` (required, `YYYY-MM-DD`)
- `include_trace` (optional, default `false`)

Response (shape):

```json
{
  "ok": true,
  "city_id": "seoul",
  "target_date": "2026-02-26",
  "horizon_days": 0,
  "timestamp_utc_iso": "2026-02-26T11:34:21.123Z",
  "timestamp_epoch_ms": 1772105661123,
  "observed": {
    "local_time": {
      "zone_id": "Asia/Seoul",
      "local_date": "2026-02-26",
      "local_time_hhmm": "20:34",
      "local_hour": 20
    },
    "metar": {
      "available": true,
      "metar_code": "RKSI",
      "current_temp_c": 4.0,
      "previous_temp_c": 3.0,
      "delta_c": 1.0,
      "observed_at_utc_iso": "2026-02-26T02:30:00Z",
      "observed_at_epoch_ms": 1772073000000,
      "source_url": "https://aviationweather.gov/..."
    },
    "control_station": {
      "available": true,
      "max_temp_c": 9.4,
      "max_temp_f": 48.9,
      "source_kind": "weather-com-observation-api",
      "source_url": "https://api.weather.com/v3/wx/observations/current?...",
      "trace_quality": "HIGH"
    }
  },
  "warnings": []
}
```

Notes:
- `Meteobroker` returns raw observed values (`S` still decimal).
- `Meteorisk` applies contract truncation/rounding.

## 6.3 `GET /v1/weather/forecast-snapshot`
Purpose:
- return normalized weather forecast snapshot for one city/date/horizon
- includes per-model forecasts and optional distributions/ensembles

Query params:
- `city_id` (required)
- `target_date` (required)
- `horizon_days` (optional if inferable)
- `models` (optional, CSV)
- `include_distribution` (optional, default `false`)
- `include_raw_runs` (optional, default `false`)

Response (shape):

```json
{
  "ok": true,
  "city_id": "london",
  "target_date": "2026-02-26",
  "horizon_days": 0,
  "timestamp_utc_iso": "2026-02-26T11:34:21.123Z",
  "timestamp_epoch_ms": 1772105661123,
  "forecast": {
    "normalization": {
      "temperature_unit": "C",
      "zone_id": "Europe/London"
    },
    "models": [
      {
        "provider_id": "open-meteo-ecmwf_ifs025",
        "provider_name": "Open-Meteo ECMWF IFS 0.25",
        "status": "SUCCESS",
        "target_date": "2026-02-26",
        "horizon_days": 0,
        "max_temp_c": 16.8,
        "run_utc_iso": "2026-02-26T00:00:00Z",
        "run_utc_epoch_ms": 1772064000000,
        "lead_hours": 12,
        "warnings": []
      },
      {
        "provider_id": "open-meteo-gfs_global",
        "provider_name": "Open-Meteo GFS Global",
        "status": "SUCCESS",
        "target_date": "2026-02-26",
        "horizon_days": 0,
        "max_temp_c": 17.5,
        "run_utc_iso": "2026-02-26T00:00:00Z",
        "run_utc_epoch_ms": 1772064000000,
        "lead_hours": 12,
        "warnings": []
      }
    ],
    "distribution": null,
    "quality_flags": []
  }
}
```

## 6.4 `GET /v1/weather/city-snapshot`
Purpose:
- one-stop endpoint for `polyMeteo`/`Meteorisk` to fetch observed + forecast state together

Query params:
- `city_id` (required)
- `target_date` (required)
- `include_trace` (optional)
- `include_distribution` (optional)

Response (shape):

```json
{
  "ok": true,
  "city_id": "paris",
  "target_date": "2026-02-26",
  "horizon_days": 0,
  "timestamp_utc_iso": "2026-02-26T11:34:21.123Z",
  "timestamp_epoch_ms": 1772105661123,
  "observed": { "...": "same as observed-state" },
  "forecast": { "...": "same as forecast-snapshot" },
  "snapshot_id": "mbs_paris_2026-02-26_h0_1772105661123",
  "source_revisions": {
    "metar": "avwx-2026-02-26T11:34:20Z",
    "control_station": "wu-2026-02-26T11:34:19Z",
    "forecast": "om-2026-02-26T11:34:18Z"
  }
}
```

## 6.5 `GET /v1/weather/events/stream` (optional, future)
Purpose:
- emit weather-only change events (forecast changes, observed station changes)

Consumers:
- `polyMeteo` UI updates
- `Meteorisk` async recalculation
- `polymeteo-bot` risk adapter cache refresher (non-hot path)

This should emit **weather events**, not market/trading alerts.

## 7. Meteorisk API (minimum viable contract)

## 7.1 `POST /v1/contracts/parse`
Purpose:
- parse a market question/contract into canonical semantics

Request:

```json
{
  "question": "Will the highest temperature in London be 17°C on February 26?",
  "city_hint": "london",
  "market_id": "123456"
}
```

Response:

```json
{
  "ok": true,
  "contract": {
    "market_id": "123456",
    "city_id": "london",
    "target_date": "2026-02-26",
    "unit": "C",
    "condition_type": "EXACT",
    "threshold": 17,
    "upper_threshold": null,
    "question_normalized": "highest temperature london exact 17c 2026-02-26"
  },
  "warnings": []
}
```

## 7.2 `POST /v1/markets/evaluate`
Purpose:
- evaluate one market contract against weather snapshot + market microstructure
- output viability, probabilities, edge and decision trace

Request (shape):

```json
{
  "contract": {
    "market_id": "123456",
    "city_id": "london",
    "target_date": "2026-02-26",
    "unit": "C",
    "condition_type": "EXACT",
    "threshold": 17,
    "upper_threshold": null
  },
  "weather_snapshot": {
    "snapshot_id": "mbs_london_2026-02-26_h0_1772105661123",
    "city_id": "london",
    "target_date": "2026-02-26",
    "horizon_days": 0,
    "observed": {
      "metar": { "current_temp_c": 15.0, "previous_temp_c": 14.0, "delta_c": 1.0, "available": true },
      "control_station": { "max_temp_c": 19.0, "available": true, "source_kind": "weather-com-observation-api" },
      "local_time": { "local_hour": 17, "zone_id": "Europe/London" }
    },
    "forecast": {
      "models": [
        { "provider_id": "open-meteo-ecmwf_ifs025", "status": "SUCCESS", "max_temp_c": 16.8 },
        { "provider_id": "open-meteo-gfs_global", "status": "SUCCESS", "max_temp_c": 17.5 }
      ]
    }
  },
  "market_microstructure": {
    "yes_price": 0.42,
    "no_price": 0.67,
    "best_bid_yes": 0.40,
    "best_ask_yes": 0.42,
    "spread": 0.02,
    "liquidity": 1500.0,
    "volume24h": 8000.0,
    "volume_bucket": 1200.0
  },
  "policy": {
    "mode": "expert",
    "strategy": "conservadora"
  }
}
```

Response (shape):

```json
{
  "ok": true,
  "evaluation": {
    "market_id": "123456",
    "city_id": "london",
    "target_date": "2026-02-26",
    "viability": {
      "is_impossible": true,
      "is_closed_by_schedule_policy": false,
      "is_dominated": false,
      "observed_floor": {
        "raw_control_station_c": 19.0,
        "contract_truncated_c": 19,
        "precision_rule": "WHOLE_DEGREE_TRUNCATE"
      },
      "reason_codes": ["OBSERVED_FLOOR_EXCEEDS_EXACT_BUCKET"]
    },
    "forecast_context": {
      "mm_c": 17.0,
      "mma_c": 17.2,
      "mm_invalid_today": true,
      "mma_invalid_today": true,
      "dominant_model_id": "open-meteo-ecmwf_ifs025",
      "dominant_model_weight": 0.46,
      "calibration_ready": true
    },
    "probabilities": {
      "model_probability_yes": 0.11,
      "bucket_distribution": null,
      "calibration": {
        "sigma_multiplier": 0.95,
        "confidence_multiplier": 1.05,
        "mean_bias_c": 0.12
      }
    },
    "execution": {
      "recommended_buy": "NO",
      "direction": "UNDER",
      "raw_edge": 0.08,
      "fee_cost": 0.01,
      "spread_cost": 0.02,
      "liquidity_cost": 0.01,
      "total_cost": 0.04,
      "fill_probability": 0.68,
      "executable_edge": 0.027,
      "signal": "YELLOW",
      "should_trade": false,
      "decision": "PASS"
    },
    "risk": {
      "risk_score": 4,
      "alerts": [
        { "code": "NEAR_RESOLUTION_WINDOW", "severity": "HIGH" }
      ]
    },
    "trace": [
      {
        "stage": "LIVE_VIABILITY",
        "status": "DISCARDED",
        "reason": "Mercado imposible por maxima observada",
        "details": ["S floor truncado: 19C", "Bucket exacto: 17C"]
      }
    ]
  }
}
```

## 7.3 `POST /v1/markets/batch-evaluate`
Purpose:
- evaluate a list of markets for one city/date in one request (recommended for `polyMeteo` and `polymeteo-bot` prechecks)

Request should accept:
- one `weather_snapshot`
- many `contracts + microstructure`

Response should return:
- per-market evaluation
- top opportunities (optional convenience field)
- batch-level warnings

## 7.4 `POST /v1/markets/viability-only`
Purpose:
- lightweight contract-specific viability/danger-window check (useful for `polymeteo-bot`)

Returns:
- `is_impossible`
- `observed_floor`
- `is_dominated`
- `danger_window_level`
- `reason_codes`

This endpoint is intentionally cheaper than full edge evaluation.

## 7.5 `GET /v1/policies/{policy_id}` (optional)
Purpose:
- expose current thresholds for `expert`, `rookie`, `conservadora`, `agresiva`, or bot risk presets

Avoid hardcoding thresholds in many clients.

## 8. Shared JSON Models (canonical shapes)

## 8.1 `ObservedCityState`
Canonical shape (from Meteobroker):

```json
{
  "city_id": "buenos-aires",
  "timestamp_utc_iso": "2026-02-26T11:34:21.123Z",
  "timestamp_epoch_ms": 1772105661123,
  "local_time": {
    "zone_id": "America/Argentina/Buenos_Aires",
    "local_date": "2026-02-26",
    "local_time_hhmm": "08:34",
    "local_hour": 8
  },
  "metar": {
    "available": true,
    "metar_code": "SAEZ",
    "current_temp_c": 26.0,
    "previous_temp_c": 25.0,
    "delta_c": 1.0,
    "observed_at_utc_iso": "2026-02-26T11:00:00Z",
    "observed_at_epoch_ms": 1772103600000,
    "source_url": "https://aviationweather.gov/..."
  },
  "control_station": {
    "available": true,
    "max_temp_c": 25.6,
    "max_temp_f": 78.1,
    "source_kind": "weather-com-observation-api",
    "source_url": "https://api.weather.com/v3/wx/observations/current?...",
    "trace_quality": "HIGH"
  },
  "warnings": []
}
```

## 8.2 `ForecastSnapshot`
Canonical shape (from Meteobroker):

```json
{
  "city_id": "new-york",
  "target_date": "2026-02-26",
  "horizon_days": 1,
  "timestamp_utc_iso": "2026-02-26T11:34:21.123Z",
  "timestamp_epoch_ms": 1772105661123,
  "models": [
    {
      "provider_id": "open-meteo-ecmwf_ifs025",
      "provider_name": "Open-Meteo ECMWF IFS 0.25",
      "status": "SUCCESS",
      "max_temp_c": 6.3,
      "run_utc_iso": "2026-02-26T00:00:00Z",
      "run_utc_epoch_ms": 1772064000000,
      "lead_hours": 36,
      "warnings": []
    }
  ],
  "distribution": null,
  "quality_flags": []
}
```

## 8.3 `MarketMicrostructureInput`
Canonical shape (client -> Meteorisk):

```json
{
  "yes_price": 0.42,
  "no_price": 0.67,
  "best_bid_yes": 0.40,
  "best_ask_yes": 0.42,
  "spread": 0.02,
  "liquidity": 1500.0,
  "volume24h": 8000.0,
  "volume_bucket": 1200.0,
  "accepting_orders": true,
  "closed": false,
  "market_status": "ACTIVE"
}
```

## 8.4 `DecisionTraceEntry`
Canonical shape (Meteorisk output):

```json
{
  "stage": "EXECUTION_CONTROL",
  "status": "DISCARDED",
  "reason": "Spread demasiado alto",
  "details": [
    "Exec: 1.2%",
    "Fill: 54.0%",
    "Spread: 0.15",
    "Max spread: 0.12"
  ]
}
```

## 9. Eventing Contract (optional but recommended)

## 9.1 Meteobroker event types (weather-only)
- `FORECAST_CHANGED`
- `OBSERVED_METAR_CHANGED`
- `CONTROL_STATION_MAX_CHANGED`
- `WEATHER_SOURCE_DEGRADED`

These events should carry weather deltas only.

## 9.2 Meteorisk event types (market/risk)
- `MARKET_VIABILITY_CHANGED`
- `RISK_SCORE_CHANGED`
- `DECISION_CHANGED`
- `NEAR_EXPIRY_ALERT`
- `DOMINANCE_ALERT`

These can be consumed by:
- `polyMeteo` UI
- `polymeteo-bot` (risk modulation)
- alerting systems

## 10. Versioning and Compatibility Rules

## 10.1 API versioning
Use explicit versioning in path:
- `/v1/...`

## 10.2 Backward compatibility
- Additive changes only in `v1` (new fields optional)
- Do not rename/remove fields without moving to `v2`
- Keep enum values stable; add new values backward-compatibly

## 10.3 Response metadata
Recommended fields in all 2xx responses:
- `ok: true`
- `request_id`
- `timestamp_utc_iso`
- `timestamp_epoch_ms`
- `service_version`
- `schema_version` (for payload object if relevant)

## 11. Security and Trust Boundaries

## 11.1 Internal service assumption
Initial deployment can assume `Meteobroker` and `Meteorisk` are internal/private services.

Recommended v0/v1 security:
- private network access only (or VPN)
- service auth token between services
- request IDs for tracing

## 11.2 Secrets policy
Never include in payloads:
- CLOB trading secrets
- private keys
- signer material

## 11.3 Bot hot path rule
`polymeteo-bot` should not block hot-path quoting on live HTTP calls to `Meteobroker`/`Meteorisk`.

Recommended pattern:
- async refresh/cache snapshots
- local in-memory risk state
- fail closed or widen/disable quoting if cache stale

## 12. Integration Patterns by Consumer

## 12.1 `polyMeteo` app/web (manual decision support)
Typical flow:
1. Fetch markets (Polymarket)
2. Fetch `Meteobroker city-snapshot` per city/horizon
3. Call `Meteorisk batch-evaluate`
4. Render `M`, `Δ`, `S`, `P/MM`, `MMA`, traces, `BET/PASS`

## 12.2 `polymeteo-bot` (market-making)
Typical flow (non-hot-path):
1. Periodically fetch/subscribe weather snapshots (`Meteobroker`)
2. Build local `WeatherRiskCache` per city/market
3. Optionally call `Meteorisk viability-only` / `batch-evaluate`
4. Convert outputs into bot risk modulation:
   - disable quote
   - widen spread
   - shrink size
   - increase skew

Do not use `Meteorisk` as a synchronous dependency inside quote loop.

## 13. Mapping to Existing `polyMeteo` Concepts (for migration)

## 13.1 Existing `polyMeteo` values -> future contract
- `M` -> `ObservedCityState.metar.current_temp_*`
- `Δ` -> `ObservedCityState.metar.delta_*`
- `S` -> `ObservedCityState.control_station.max_temp_*`
- `MM` -> `Meteorisk.forecast_context.mm_*` (or Meteobroker if ownership chosen there)
- `MMA` -> `Meteorisk.forecast_context.mma_*` (or Meteobroker if ownership chosen there)
- `BET/PASS`, `signal`, `Exec`, `Fill`, `Costes` -> `Meteorisk.execution`
- `trazabilidad` -> `Meteorisk.trace[]`

## 13.2 Ownership decision to settle early (important)
There are two valid options for `MM/MMA`:

Option A (recommended for scale):
- `Meteobroker` computes and owns MM/MMA as forecast intelligence products
- `Meteorisk` consumes MM/MMA and applies contract semantics + execution edge

Option B (valid for tighter market coupling):
- `Meteorisk` computes MM/MMA internally from Meteobroker raw models

Recommendation:
- Start with **Option B** if building faster around current `polyMeteo` logic.
- Move to **Option A** when multiple consumers need shared forecast intelligence independent of Polymarket.

## 14. Implementation Roadmap (future, incremental)

### Phase 1 (minimal split)
- Keep current `polyMeteo` backend logic
- Introduce `Meteobroker-lite` endpoints for observed + forecast snapshots
- Keep `Meteorisk` as library/module inside the same backend

### Phase 2 (service extraction)
- Extract `Meteorisk` to separate service or package
- Add `batch-evaluate` and `viability-only`
- Add explicit schemas and contract tests

### Phase 3 (bot integration)
- `polymeteo-bot` consumes `Meteobroker` snapshots and `Meteorisk` viability/risk feeds
- Cache-based non-hot-path integration
- Add stale-data guardrails in bot

## 15. Contract Tests (recommended from day 1)

Create shared fixtures for:
- London/Seoul/Buenos Aires/New York cases
- Wunderground/weather.com source fallback traces
- impossible market due to observed floor
- dominated market late-day
- truncation edge cases (`29.9 -> 29`)
- tomorrow/day+2 `SIN METAR` cases

Use the same fixtures to validate:
- `polyMeteo`
- `Meteorisk`
- `polymeteo-bot` risk adapter

## 16. Final Rule (to avoid future regressions)

If a responsibility is unclear, default to this question:

- "Is this weather normalization/data fidelity?" -> `Meteobroker`
- "Is this contract semantics, execution realism, or market decisioning?" -> `Meteorisk`

And for the bot:
- "Does this need to happen inside the quote loop in <100 ms?" -> keep it local/in-memory in `polymeteo-bot`.

