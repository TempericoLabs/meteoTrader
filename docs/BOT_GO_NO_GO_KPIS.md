# BOT_GO_NO_GO_KPIS

Version: 0.1 (operational draft)
Date: 2026-02-26
Status: Reference for future `polymeteo-bot`
Audience: Strategy, engineering, and operations

## 0. Purpose

Define a quantitative Go/No-Go framework to decide when `polymeteo-bot` can move between phases:

1. Simulation (Replay)
2. Paper-live (real market data, no real orders)
3. Live minimum (real orders, minimal capital)
4. Capital scaling

This framework exists to prevent subjective decisions ("looks good") and enforce progression by measurable evidence.

## 1. Core Principle

No phase transition is allowed based on intuition alone.

A phase transition requires:
- passing quantitative KPIs,
- no unresolved critical incidents,
- and post-mortem understanding of bad runs (not only good runs).

## 2. KPI Categories (must exist in all phases when applicable)

## 2.1 Data / System Quality KPIs
These determine whether your conclusions are trustworthy.

- `integrity_ok_rate`
  - % of datasets/replays passing integrity checks
- `event_gap_rate`
  - gaps per 10k events (or per dataset)
- `replay_determinism_rate`
  - % identical outputs for same dataset snapshot + same config
- `uptime_session`
  - % session uptime (paper/live)
- `ws_reconnect_success_rate`
  - success ratio for reconnect attempts (paper/live)
- `kill_switch_test_pass`
  - boolean (must be repeatedly validated)

## 2.2 Execution / Microstructure KPIs
These determine whether the bot captures spread in a realistic way.

- `fill_rate_total`
- `fill_rate_buy`
- `fill_rate_sell`
- `partial_fill_rate`
- `cancel_success_rate`
- `quote_update_latency_ms` (p50/p95/p99 if available)
- `stale_quote_rate`
- `effective_spread_captured_gross`
- `effective_spread_captured_net`

## 2.3 Risk / Inventory KPIs
These determine whether the bot can survive adverse conditions.

- `max_inventory_notional`
- `inventory_time_imbalanced_pct`
- `inventory_mean_reversion_time`
- `adverse_selection_rate`
- `post_fill_adverse_move` (bps or cents)
- `max_drawdown`
- `daily_loss_limit_breaches`
- `market_loss_limit_breaches`
- `forced_flatten_count`

## 2.4 Profitability KPIs
These determine if the strategy is economically viable (not just active).

- `pnl_gross`
- `fees_total`
- `pnl_net`
- `expectancy_per_fill`
- `expectancy_per_notional`
- `win_rate_cycles` (secondary metric only)
- `pnl_volatility`
- `profit_factor` (optional)

## 3. KPI Definitions (practical, to avoid ambiguity)

## 3.1 `replay_determinism_rate`
Same dataset snapshot + same config + same random seed (if any) must produce the same:
- event counts
- fill counts
- PnL summary
- inventory final state

Target for deterministic simulation core: `100%`.

## 3.2 `effective_spread_captured_net`
Captured spread after fees and execution penalties.

This is more important than nominal spread or win rate.

## 3.3 `adverse_selection_rate`
Define explicitly in code/docs. Example v1:
- % fills after which market moves against position by threshold X within time window T.

The exact formula can evolve, but the definition must be fixed per experiment.

## 3.4 `sim_vs_paper_gap` (critical bridge KPI)
Difference between simulation and paper-live behavior for comparable conditions.

Track at least:
- fill rate gap
- PnL net gap
- adverse selection gap
- inventory imbalance gap

If this gap is large or unstable, do not move to live.

## 4. Phase Gates (Go/No-Go)

## 4.1 Simulation (Replay) -> Paper-live
Minimum gate (baseline proposal):

### Must pass
- `integrity_ok_rate >= 99.9%`
- `replay_determinism_rate = 100%`
- `kill_switch_test_pass = true`
- `max_drawdown` within defined limit
- `pnl_net > 0` across a representative set of runs (not a single best run)
- `expectancy_per_fill > 0` after fees
- `adverse_selection_rate` measured and understood
- no unexplained inventory drift

### Must be understood (qualitative but mandatory)
- why the strategy wins in good runs
- why it loses in bad runs
- which assumptions in fill model are fragile

### No-Go conditions
- profitable only under one cherry-picked dataset
- deterministic failures / inconsistent replay
- no clear explanation of adverse selection behavior

## 4.2 Paper-live -> Live minimum (real money, minimal capital)
Paper-live validates operational realism (market data, timing, reconnects, stale quotes), not just PnL.

### Must pass (proposal for 7-14 days of sessions)
- `uptime_session >= 99%` (within scheduled windows)
- `ws_reconnect_success_rate >= 99%`
- `kill_switch_test_pass = true` in paper-live environment
- stable `quote_update_latency_ms` (p95 within defined threshold)
- `stale_quote_rate` below defined threshold
- no repeated critical incidents
- `risk limit breaches = 0` (or explicitly tolerated/test-mode only)
- `sim_vs_paper_gap` measured and within accepted range

### Preferable (but not sole criteria)
- `pnl_net_paper >= 0` and improving

### No-Go conditions
- sim says profitable but paper-live diverges badly and unexplained
- stale quotes frequent
- reconnect failures under normal conditions
- guardrails fail or need manual babysitting

## 4.3 Live minimum -> Capital scaling
This phase is about proving stability with real money under strict limits.

### Must pass (proposal for 2-4 weeks minimum)
- no critical incidents (or all incidents root-caused and fixed)
- `max_drawdown` within predefined limit
- `expectancy_per_fill > 0` net
- `adverse_selection_rate` stable (not trending worse with live behavior)
- latency and cancel performance stable
- low manual intervention rate
- risk guardrails respected at all times

### No-Go conditions
- positive PnL but with unstable inventory/risk behavior
- repeated kill-switch events due to system faults
- profitability concentrated in few outlier sessions

## 5. Suggested Initial Thresholds (starting point, not dogma)

These are initial conservative defaults. They must be tuned with real observations.

## 5.1 Risk limits (starting baseline)
- `max_loss_day`: `1%` of bankroll
- `max_loss_market`: `0.25%` to `0.5%` of bankroll
- `max_exposure_bucket`: `10%` to `20%` of bankroll (liquidity dependent)
- `max_net_inventory`: `10%` of bankroll

Kill-switch triggers should include at least:
- daily loss breach
- persistent WS/data disconnection
- latency out of acceptable range
- state reconciliation failure

## 5.2 Execution thresholds (paper/live starter thresholds)
- `cancel_success_rate`: target `>95%` (prefer `>98%`)
- `stale_quote_rate`: explicitly defined and monitored (no implicit tolerance)
- `effective_spread_captured_net`: must be positive over meaningful sample

## 5.3 Simulation quality threshold
- `replay_determinism_rate = 100%` before moving forward
- `sim_vs_paper_gap` must be tracked before live

## 6. Dashboard Semaforo (Go/No-Go Visual)

Recommended high-level status panel:

- `GREEN`: pass, phase progression allowed
- `YELLOW`: degraded / proceed blocked until issue resolved
- `RED`: fail / rollback required

### Minimum fields to show at a glance
- Phase (`SIM`, `PAPER`, `LIVE`)
- `PnL net`
- `Max DD`
- `Adverse selection`
- `Fill quality`
- `Inventory imbalance`
- `Latency p95`
- `Reconnects`
- `Kill-switch status`
- `Last incident`

## 7. Progression and Rollback Rules

## 7.1 Progression Rule
A phase can progress only if:
- KPI thresholds pass,
- no unresolved critical incidents,
- poor runs were reviewed and explained,
- current assumptions are documented.

## 7.2 Rollback Rule (mandatory)
Rollback to prior phase or reduced mode if any of these occur:
- repeated critical incidents
- guardrail breach
- adverse selection worsens beyond threshold
- sim-vs-paper gap widens unexpectedly
- untrusted data/integrity issue

Rollback action should be explicit in ops docs:
- stop bot / pause session
- flatten positions if live
- preserve logs and snapshot state
- run incident review

## 8. Capital Scaling Policy (future live)

Capital scaling should be incremental and KPI-based.

### Suggested policy (example)
- `Level 0`: minimal validation capital
- `Level 1`: x1.5-x2 only after stable KPI window
- `Level 2`: another incremental increase after repeated validation

### Scaling conditions
- positive net expectancy sustained over time
- no risk instability
- no increase in adverse selection beyond tolerance
- no increase in operational incidents

### Freeze/Reduce conditions
- KPI degradation
- higher drawdown than expected
- fill quality deterioration
- market regime change with unexplained behavior

## 9. Phase Templates (operational checklists)

## 9.1 Simulation phase checklist
- [ ] Integrity checker green on datasets used
- [ ] Replay deterministic with same snapshot/config
- [ ] Fill model assumptions documented
- [ ] PnL and adverse metrics exported and reviewed
- [ ] Kill-switch behavior tested in simulation
- [ ] Worst runs reviewed and understood

## 9.2 Paper-live phase checklist
- [ ] WS/data stability measured
- [ ] Reconnects work
- [ ] Stale quote monitoring active
- [ ] sim-vs-paper gap report generated
- [ ] Guardrails observed in real-time
- [ ] Kill-switch tested in paper mode

## 9.3 Live minimum checklist
- [ ] Minimal capital configured
- [ ] Daily loss and market loss limits active
- [ ] Kill-switch tested before session
- [ ] Monitoring + alerting on
- [ ] Incident logging ready
- [ ] Post-session review routine defined

## 10. Metrics Storage Recommendation (future)

For robust comparisons and audits, store KPI snapshots per run/session with:
- `run_id`
- `phase`
- `config_hash`
- `dataset_snapshot_id`
- `timestamp`
- `metrics_payload`

This allows longitudinal comparisons and prevents subjective memory bias.

## 11. What This Framework Does NOT Guarantee

This framework does not guarantee profits.

It guarantees a better process for:
- validating assumptions,
- controlling risk,
- avoiding premature live deployment,
- and identifying when the system is degrading.

## 12. Final Operational Rule

If a metric looks good but risk/execution metrics worsen, do not trust the PnL alone.

For market-making bots, sustainable viability depends more on:
- net expectancy,
- adverse selection control,
- execution quality,
- and inventory discipline
than on win rate or isolated profitable sessions.

