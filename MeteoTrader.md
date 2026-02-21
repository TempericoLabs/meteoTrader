# MeteoTrader - Functional and Technical Specification (for a fresh Codex thread)

Version: 0.3 checkpoint consolidation  
Date: 2026-02-21  
Audience: Future Codex thread rebuilding app from zero code, reusing all domain learnings.

---

## 1. Mission, Context, and Product Definition

You are building an Android app to support betting decisions in Polymarket daily maximum temperature markets for a fixed set of 14 cities.

Primary objective:
- Maximize decision quality (not bet volume).
- Show only verified, actionable, execution-aware opportunities.
- Fail safe: if confidence, data quality, or execution quality is low, prefer NO BET.

Secondary objective:
- Keep UI fast, readable, and useful for both advanced and beginner users.

Non-goal:
- Full autonomous trading is not in scope now.

Core business statement:
- This is a decision-support system that converts weather + market microstructure into executable signals.

---

## 2. Product Scope (Current Baseline to Preserve)

### 2.1 Supported cities (fixed list)
Use exact 14-city catalog (id, metarCode, lat/lon, timezone, display unit, Wunderground control URL, Wunderground PWS URL):
- Miami, London, Toronto, Seattle, Dallas, Wellington, Ankara, Seoul, New York, Chicago, Atlanta, Paris, Buenos Aires, Sao Paulo.

Reference implementation source:
- `/Users/emiair/Documents/CodexAPP/meteoTrader/app/src/main/java/com/polymeteo/meteotrader/data/CityCatalog.kt`

### 2.2 Main screens and modes
1. Cities (home) - Expert mode
- 7x2 responsive grid (portrait/landscape).
- Each city card includes: city name, local time, M, Delta, S, P values plus top trade signal.
- Dynamic horizontal ticker replacing static top card.
- Flash behavior for critical opportunities.
- Schedule lock: after local 18:00 city card shows closed state and no market suggestion.

2. City detail
- METAR block, TAF block, control station max temp, MM and MMA.
- Today/tomorrow/day+2 tabs for markets.
- For tomorrow/day+2: show "SIN METAR" and use forecast-only logic for those horizons.
- Full trader metrics per market (raw edge, costs, executable edge, fill, spread, liquidity, recommendation).
- Clickable source labels (METAR/TAF/control) instead of printing raw URLs.
- Inline help on critical terms.

3. MMA dashboard (from detail)
- Learning engine summary, dominant model, dynamic ranking, verified days, bootstrap progress.
- Color-coded model trend in ranking (improved/stable/worse vs previous day).
- Only active providers; excluded/deprecated providers must not appear.

4. Settings
- App mode toggle: Expert (default) / Rookie.
- Strategy toggle: Conservadora / Agresiva, with explanatory dialogs.

5. Rookie mode
- One-screen simplified opportunity feed ordered by executable edge.
- Human-friendly language, no technical overload.
- Each item links directly to Polymarket event URL.

6. Simulation/Risk framework already introduced
- Paper-trading and risk guardrails exist conceptually and must be preserved and hardened.

---

## 3. Terminology Contract (UI Naming Is Mandatory)

All user-facing naming must use:
- MM = "Media Modelos (MM)"
- MMA = "Media Modelos Ajustada (MMA)"

Never reintroduce "PolyTemp" text in user-visible UI.

Reference checkpoint where this naming was consolidated:
- commit `1b015c5` (`MVP v0.3`)

---

## 4. Critical Domain Rules (Non-Negotiable)

### 4.1 Market date alignment
- Today markets -> today forecasts + today observational context.
- Tomorrow markets -> tomorrow forecasts only, METAR unavailable.
- Day+2 markets -> day+2 forecasts only, METAR unavailable.
- Never evaluate tomorrow/day+2 with today market-weather state as if it were same horizon.

### 4.2 Resolution precision (Polymarket)
- Market resolution uses whole degrees.
- Observed temperatures from control station must be truncated to integer for viability filtering.
  - Example: 29.9C resolves as 29C threshold precision.

### 4.3 Observed max floor and impossible markets
For same-day markets:
- Build observed floor from available observational signals.
- Remove markets that are mathematically impossible to win based on observed floor and market condition.

Important separation:
- Forecast invalidation (MM/MMA below observed floor) != market invalidation.
- Even if MM is invalid, some markets may remain tradable.

### 4.4 End-of-day closure by local schedule
- If local city time >= 18:00, city is considered closed for recommendations in the city grid.
- UI must show closed message, no active suggestion.

### 4.5 Dominated late-day markets
- Additional control: suppress recommendations when market is effectively dominated (ex: one bucket already >= 95% late enough).
- Do not recommend entries in dead/dominated books.

---

## 5. Data Sources and Status

## 5.1 Observations
1. METAR + TAF
- Source: `aviationweather.gov`
- METAR endpoint family: `/data/metar/?decoded=1&ids={ICAO}`
- TAF integrated through same family with `taf=1`.

2. Control station (resolution proxy)
- Source: Wunderground history/control pages + PWS fallback.
- Must parse high temp robustly, not only one HTML location.
- Current robust rule:
  - Extract summary "High Temp Actual" candidate.
  - Extract current/embedded candidates.
  - Choose the higher valid temperature candidate.

Reference hardening milestone:
- commit `507cb0c` (`FIX_WUNDERGROUND_HIGH_TEMP_ACTUAL_RESILIENT`)

### 5.2 Forecast providers (active)
- Windy: gfs, iconEu, namConus (coverage checks and skip handling).
- Open-Meteo: ecmwf_ifs, ecmwf_ifs025, ecmwf_aifs025_single.
- OpenWeather.
- NOAA Weather.gov (US only, skip outside US).

Reference provider setup:
- `/Users/emiair/Documents/CodexAPP/meteoTrader/app/src/main/java/com/polymeteo/meteotrader/data/WeatherRepository.kt`

### 5.3 Deprecated/removed providers
- Weatherstack removed from active flow.
- ECMWF Web API bridge removed from app flow.
- NOTAM integration removed.

These must remain deprecated unless explicitly re-approved.

---

## 6. MM and MMA Engine Specification

### 6.1 MM (Media Modelos)
Definition:
- Weighted average of valid provider daily max forecasts.
- Pre-filter with robust outlier logic (median + MAD tolerance).
- Produce quality warnings:
  - no valid sources
  - low source count
  - high spread
  - outlier removals

### 6.2 MMA (Media Modelos Ajustada)
Definition:
- MM-like average but using dynamic weights learned per city and horizon from historical verification.
- Dynamic weighting engine ingests snapshots and compares against observed maxima.
- Includes bootstrap/historical acceleration and horizon transfer support.
- Should expose calibration readiness/progress.

Dominant model blending:
- If one model clearly dominates in weight/share ratio, blend towards it with bounded alpha (not full override).

### 6.3 Active model invalidation rule
- For same day only: a forecast is invalid if forecastMax < observedMax (epsilon aware).
- If active engine is MMA-ready, invalidation message should refer to MMA.
- If MM invalid but MMA valid, do not show global invalidation as if both were invalid.

---

## 7. Polymarket Opportunity Engine (Executable Edge, Not Raw Edge)

### 7.1 Probability model
- Convert MM/MMA forecast mean + sigma into probability per market condition type:
  - GREATER_OR_EQUAL
  - LESS_OR_EQUAL
  - EXACT
  - BETWEEN
- Apply city/horizon/daypart calibration to mean bias, sigma multiplier, confidence multiplier.

### 7.2 Cost-aware edge
Compute:
- rawEdge = max(EV_yes, EV_no)
- feeCost
- spreadCost
- liquidityCost
- edgeAfterCosts = rawEdge - totalCost
- executableEdge = edgeAfterCosts * fillProbability

Recommend trade only if execution controls pass:
- minimum liquidity
- minimum 24h volume
- max spread
- max total cost
- min fill probability
- min executable edge
- entry price range controls

Signal levels:
- GREEN / YELLOW / RED based on executable edge and fill thresholds.

### 7.3 Recommendation control layer
- Apply per-day controls and late-day stricter thresholds.
- Apply dominance suppression (near-resolved books).
- Sort by executableEdge.

Reference core engine:
- `/Users/emiair/Documents/CodexAPP/meteoTrader/app/src/main/java/com/polymeteo/meteotrader/data/source/PolymarketSource.kt`

---

## 8. UI/UX Functional Requirements for Rebuild

### 8.1 Home (Expert)
Must show per city:
- City, local time.
- M (METAR current), Delta (vs previous), S (control station), P (MM).
- Recommendation strip with direction + YES/NO + edge.
- If schedule closed: "CERRADO POR HORARIO".

Layout constraints:
- No clipping at bottom cards.
- Respect safe areas/status bar/navigation bar.
- Consistent typography and contrast in dark theme.

### 8.2 Detail
Fields and behavior:
- METAR current includes observation timestamp in `dd-MM-yyyy HH:mm`.
- METAR previous includes elapsed hint `(Hace X horas)`.
- Control station and MM/MMA show alternate unit in parentheses.
- MM highlighted in green + bold when valid.
- MMA label/value left aligned below MM.
- Tabs: Hoy / Manana / Pasado with market count.
- Tomorrow/day+2 display `SIN METAR`.

### 8.3 Live help
- Every key term clickable with plain-language explanation:
  - OVER, UNDER, RANGE, YES/NO
  - GREEN/YELLOW/RED
  - Costs, fill, liquidity, spread, executable edge
  - TAF
  - MM/MMA concepts

### 8.4 Rookie mode
- Show only filtered opportunities for selected strategy.
- Friendly labels like "Apuesta ahora" etc.
- Keep direct event link to Polymarket.
- Never display recommendations for schedule-closed cities.

---

## 9. Reliability and Performance Requirements

## 9.1 Timeouts and concurrency
Current baseline constants to preserve/improve:
- METAR timeout: 7s
- TAF timeout: 6s
- Control station timeout: 8s
- Forecast provider timeout: 9s
- Polymarket timeout: 6s
- Premium engine timeout: 5s
- Max city concurrency: 4

### 9.2 Retry/fallback policy
- Wunderground HTML parse with retries and multi-URL attempts.
- Polymarket event fetch:
  - direct slug candidates first
  - fallback search query if direct fails
- Provider-level SKIPPED status for unsupported/disabled conditions.

### 9.3 Fail-safe rendering
- If a source fails, show clear warning and continue with available valid sources.
- Never fabricate missing values.
- If model input unavailable for a market day, skip recommendation.

### 9.4 Data integrity and deduplication
- Deduplicate markets by marketId.
- Keep horizon alignment strict.
- Persist premium snapshots/verifications safely (atomic store updates).

---

## 10. Testing and Validation Plan (Must Be Built-In)

### 10.1 Unit tests (mandatory)
- Market condition parser for all formats (exact, range, above, below).
- Date parsing from market question strings.
- Unit conversion correctness C/F.
- Truncation behavior for observed floor.
- Market viability filter logic.
- MM outlier filtering and weighted averaging.
- MMA weight update and dominant blend boundaries.
- Recommendation controls (late-day, dominance, execution guards).

### 10.2 Integration tests
- Simulate city payload end-to-end with mixed provider success/failure.
- Verify today vs tomorrow/day+2 behavior and `SIN METAR`.
- Validate no impossible markets survive filter.
- Validate closed-by-schedule state at local >= 18.

### 10.3 Live operational checklist (copied into product QA)
For each city and day horizon:
- Confirm event discovery by slug and fallback.
- Confirm bucket count matches Polymarket web.
- Confirm closed/resolved markets are excluded.
- Confirm still-open markets remain visible.
- Confirm condition parsing (range/threshold/unit/date).
- Confirm truncation rule in viability filtering.
- Confirm tomorrow/day+2 show `SIN METAR`.
- Record incidents with timestamp, payload, screenshot, expected decision.

Source of truth:
- `/Users/emiair/Documents/CodexAPP/meteoTrader/PENDIENTES.md`

---

## 11. Architecture Recommendation for Fresh Rebuild (V2)

Recommended stack:
- Android: Kotlin + Jetpack Compose + Coroutines + Flow.
- Local persistence: Room + DataStore for settings/state snapshots.
- Networking: Ktor/OkHttp with strict timeout/retry policy.
- DI: Hilt/Koin (prefer Hilt for scale).
- Modularization:
  - `core-model`
  - `core-network`
  - `feature-cities`
  - `feature-detail`
  - `feature-trader`
  - `feature-premium`
  - `feature-rookie`
  - `feature-backtest`
  - `feature-settings`

Architecture pattern:
- Clean architecture + unidirectional data flow.
- Domain use cases should remain pure and fully testable.
- UI layer should only map domain state to composables.

Optional future backend (when needed):
- Add lightweight aggregator service only if mobile-side API fragility becomes bottleneck.
- Keep app functional with direct API mode as baseline fallback.

---

## 12. Security, Risk, and Responsible Operation

### 12.1 Security
- Never store secret keys in plaintext in repo.
- Use `local.properties` + BuildConfig injection.
- If account integration is added, never request seed phrase.

### 12.2 Trading risk policy
- Preserve and harden guardrails:
  - max daily loss
  - max loss per market
  - low-liquidity block
  - auto kill-switch
- Recommendation engine must be conservative by default when data quality drops.

### 12.3 User safety
- Keep demo mode and simulation mode first-class.
- Force explicit opt-in before any future live order capability.

---

## 13. Pending Backlog to Continue (Priority Order)

P0 - Live market validation hardening
- Execute full city-by-city operational checklist continuously.
- Add automated detector for parsing drift in Wunderground/Polymarket schemas.

P1 - Account read-only integration
- Portfolio/positions/cash/activity read from Polymarket account address.
- Keep read-only before any execution permission.

P2 - Future CLOB execution integration (guarded)
- Only after read-only is stable and credential flow is secure.

P3 - Expand calibration datasets/providers
- Continue historical model benchmarking by city and horizon.
- Add new providers only with proven incremental value.

---

## 14. Build Acceptance Criteria (Definition of Done)

A build is releasable only if ALL pass:
1. No impossible market shown after observed-floor filter.
2. No stale horizon mismatch (today data for tomorrow markets, etc.).
3. No city card clipping / no safe-area overlap.
4. MM/MMA labels and semantics consistent everywhere.
5. Wunderground control max robust parse (summary + current candidates + max selection).
6. Executable-edge controls applied before recommendation.
7. Closed-by-schedule behavior correct in local timezone.
8. Unit + integration + smoke tests all green.
9. Manual live checklist sampled across all 14 cities.

---

## 15. Chronology of Key Learnings (Commit-Derived)

Use this as migration map for the new thread:
- `fbfbbd8` ALFA: MVP foundation.
- `33099c0` Trader Mode V1 introduced.
- `9732ef1` UI polish + inline help for trader terms.
- `507cb0c` Wunderground high-temp parsing made resilient.
- `43ec4c2` Critical path hardening for speed/precision.
- `ca0e77a` Day selector (today/tomorrow/day+2) integrated.
- `8d322dd` Executable-edge engine (cost/fill/liquidity/spread aware).
- `c6a5f5b` City/horizon/daypart calibration.
- `c05ba02` End-to-end paper trading simulation.
- `8dbf056` Mandatory risk guardrails + kill-switch.
- `91b3da5` Demo mode and rich manual baseline.
- `95c678c` MMA learning engine + ranking dashboard.
- `c4b124b` MM/MMA separation + Weatherstack removal.
- `97f052d` Dynamic ticker + flash opportunities.
- `1d72826` Remove ECMWF WebAPI placeholder.
- `2621464` Remove NOTAM path, keep METAR+TAF from aviationweather.
- `033683a` Invalidate stale forecast + hide impossible live markets.
- `e34f165` Polymarket detection hardening + live validation checklist.
- `59195ee` Expert/Rookie mode split + strategy settings.
- `523515f` MVP v0.2 checkpoint.
- `66c3aa1` MMA phase2 hybrid calibration + bootstrap acceleration.
- `1b015c5` MVP v0.3 MM/MMA naming and UX alignment.
- `d4fd2fe` Checkpoint commit with pending roadmap snapshot.

---

## 16. Direct Instructions to Future Codex (Execution Style)

If you are the new Codex thread starting from zero:
1. Recreate domain model and critical rules first, before UI polishing.
2. Implement impossible-market filtering and horizon alignment before recommendations.
3. Implement execution-aware ranking before any "top picks" UI.
4. Keep strict no-bet default when data is incomplete, stale, or contradictory.
5. Add full observability:
   - per-source status
   - parse warnings
   - decision trace (why market was kept/rejected)
6. Build tests before adding new providers.
7. Do not chase provider quantity over quality. Reliability beats API count.
8. Preserve rookie readability without degrading expert depth.

Final product bar:
- Fast, robust, explainable, and operationally trustworthy.
- Every shown opportunity must be both meteorologically and execution-wise defendable.

