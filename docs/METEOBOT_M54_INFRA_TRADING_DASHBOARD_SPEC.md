# MeteoBot M5.4 - Infra + Trading Dashboard Spec

Fecha: 2026-02-27  
Estado: Ready for build  
Objetivo: pasar de observabilidad técnica a operación paper-trading útil (sin live execution)

---

## 0) Alcance y principio rector

Primero se construye **infra robusta y auditable**.  
Solo después se habilita el **Trading Dashboard operativo**.

Regla no negociable:
- si la infra no cumple SLOs mínimos, el dashboard debe mostrar `DEGRADED` y bloquear recomendaciones ejecutables (`PASS` only).

Fuera de alcance en esta fase:
- envío de órdenes reales
- firma/wallet real
- promesas de rentabilidad

---

## 1) Fase A - Infra crítica (completa y estable)

## A.1 Pipeline canónico (end-to-end)

Pipeline obligatorio:
1. Feed market data (real/read-only)  
2. Normalización de eventos (schema estable)  
3. Decisión (`BUY_YES`, `BUY_NO`, `PASS`) con trazabilidad  
4. Simulación de ejecución (fill model v2)  
5. Estado de posición y riesgo  
6. Persistencia y métricas agregadas  
7. Exposición API para dashboard

Cada salto debe registrar:
- `event_id` (determinista)
- `source_seq`
- `ingest_ts_utc_iso` + `ingest_ts_epoch_ms`
- `stage_ts_*` por fase
- `trace_reason[]` (reglas aplicadas)

## A.2 Modelo de datos mínimo (persistente)

Tablas obligatorias (SQLite):

1. `market_events` (ya existe)
2. `trade_decisions`
   - `decision_id` PK
   - `event_id`
   - `city`
   - `horizon` (`H0`,`H1`,`H2`)
   - `market_slug`
   - `bucket_label`
   - `action` (`BUY_YES`,`BUY_NO`,`PASS`)
   - `model_prob_yes`
   - `market_prob_yes`
   - `edge_gross_pct`
   - `edge_exec_pct`
   - `spread_pct`
   - `liquidity`
   - `fill_prob_pct`
   - `risk_flags_json`
   - `trace_json`
   - `created_at_*`
3. `paper_orders`
   - `order_id` PK
   - `decision_id`
   - `market_slug`
   - `side` (`YES`,`NO`)
   - `intent_qty_usdc`
   - `limit_price`
   - `status` (`OPEN`,`PARTIAL`,`FILLED`,`CANCELLED`,`REJECTED`,`CLOSED`)
   - `filled_qty_usdc`
   - `avg_fill_price`
   - `opened_at_*`
   - `closed_at_*`
   - `close_reason` (`TP`,`SL`,`TIMEOUT`,`MANUAL`,`RULE_CHANGE`,`EOD`)
4. `paper_positions`
   - `position_id` PK
   - `market_slug`
   - `side`
   - `entry_price_avg`
   - `qty_usdc`
   - `notional_usdc`
   - `unrealized_pnl`
   - `realized_pnl`
   - `max_adverse_excursion`
   - `max_favorable_excursion`
   - `state` (`OPEN`,`CLOSED`)
5. `risk_state`
   - bankroll, used, free, dd actual, dd máximo, kill-switch, límites activos
6. `dashboard_snapshots_1m`
   - snapshot agregado por minuto para cargar dashboard rápido (<150ms)

## A.3 Configuración operativa (runtime)

Variables/env mínimas:
- `BANKROLL_USDC`
- `MAX_STAKE_PER_TRADE_USDC`
- `MAX_STAKE_PER_CITY_USDC`
- `MAX_TOTAL_EXPOSURE_USDC`
- `MAX_DAILY_LOSS_USDC`
- `MAX_DRAWDOWN_PCT`
- `MIN_LIQUIDITY`
- `MAX_SPREAD_PCT`
- `MIN_EDGE_EXEC_PCT`
- `ONLY_CITIES` (lista)
- `ONLY_HORIZONS` (`H0/H1/H2`)
- `PAPER_MODE=true` (hard guardrail)

Validaciones:
- si falta config crítica -> `boot_error`
- si config inconsistente -> arranque denegado

## A.4 SLO/SLA internos

Objetivos mínimos:
- Ingest p95 < 100 ms
- Decision p95 < 80 ms
- Persist write p95 < 50 ms
- Dashboard summary p95 < 150 ms
- Error rate < 1% por ventana 1h
- Stale rate < 1% por ventana 1h

Reglas de degradación:
- incumplimiento 2 ventanas consecutivas -> estado `YELLOW`
- incumplimiento 4 ventanas consecutivas -> `RED` + `PASS only`

## A.5 Observabilidad y trazabilidad

Obligatorio:
- `decisionTrace` por cada mercado evaluado
- top razones de descarte (liquidez/spread/fill/edge/horario/mercado no viable por truncado)
- contador de `false-opportunity prevented`

Logs estructurados JSON:
- `component`
- `run_id`
- `market_slug`
- `decision_id`
- `severity`
- `reason_code`

## A.6 Integridad y consistencia

Checks automáticos cada 5 min:
- secuencia (`source_seq` gaps/duplicates)
- timestamps monotónicos por stream
- integridad de joins (`decision -> order -> position`)
- reconciliación capital:
  - `bankroll_free + bankroll_used + realized_pnl == bankroll_base +/- epsilon`

Si falla:
- alerta `critical_incident`
- freeze de nuevas órdenes paper (`PASS only`) hasta recuperación

## A.7 Pruebas obligatorias (infra)

Test suite mínima:
1. unit: parser + reglas + fill + risk
2. integration: pipeline completo mock->decision->order->position
3. soak: 60 min con auto-refresh dashboard (sin degradar inserts)
4. consistency: reconciliación de capital y PnL
5. determinism: mismo input => misma decisión (excepto campos de tiempo)

Gate de release:
- 100% tests críticos verdes
- 0 panic
- sin `slow INSERT` sostenido

---

## 2) Fase B - Trading Dashboard (operativo)

## B.1 Objetivo funcional

El usuario debe ver, en tiempo real paper:
1. qué mercados se monitorizan
2. qué señal activa tiene cada mercado (SI/NO/PASS)
3. cuánto capital se está usando
4. dónde se abrió cada operación y cómo va
5. condiciones de salida y estado de riesgo

## B.2 Vistas

### B.2.1 Simple (operativa, sin tecnicismos)

Widgets obligatorios:
1. **Capital**
   - bankroll inicial
   - usado
   - libre
   - PnL abierto/cerrado/total
2. **Mercados monitorizados (hoy/mañana/pasado)**
   - ciudad, día (`H0/H1/H2`)
   - mercado/bucket
   - precio SI y NO
   - liquidez y spread
   - estado (`SEGUIR`, `ESPERAR`, `DESCARTADO`)
3. **Señales activas**
   - `Comprar SI`, `Comprar NO`, `No entrar`
   - motivo simple (1 línea)
4. **Operaciones en curso**
   - entrada, precio medio, tamaño, PnL vivo, stop/take
5. **Historial reciente**
   - últimas N operaciones cerradas con resultado

### B.2.2 Expert (auditable)

Incluye todo lo de Simple +:
- edge bruto / ejecutable
- fill probability
- decisión trace expandible
- razones de bloqueo por guardrails
- latencias por etapa

## B.3 Endpoints API (Trading Dashboard)

Nuevos endpoints recomendados:

1. `GET /trading/summary`
   - capital, exposición, PnL, estado riesgo, sesión
2. `GET /trading/markets?city=&horizon=&status=`
   - lista de mercados monitorizados + señal + precio SI/NO
3. `GET /trading/signals?status=active|pass|closed`
4. `GET /trading/orders?state=open|closed&limit=`
5. `GET /trading/positions?state=open|closed`
6. `GET /trading/risk`
7. `GET /trading/trace?market_slug=...`
8. `POST /trading/control` (paper-only)
   - `pause`, `resume`, `pass_only`, `clear_alerts`

Todos deben incluir:
- `generated_at_*`
- `source_mode` (`paper_live_shadow`)
- `execution_mode` (`simulated`)

## B.4 Reglas de UX obligatorias

- Nunca mostrar recomendación sin:
  - precio actual
  - liquidez
  - spread
  - motivo de entrada/salida
- Si el mercado está cerrado o no viable:
  - etiqueta visible `NO OPERABLE`
- Si hay desalineación de datos:
  - etiqueta `DATOS INESTABLES` + bloqueo de entrada

## B.5 Lógica de salida visible

Por posición abierta, mostrar:
- `stop_loss` activo
- `take_profit` activo
- `timeout` (caducidad)
- `salida por cambio de señal` (si aplica)

Si una salida se ejecuta:
- registrar `close_reason`
- registrar precio y slippage simulado

## B.6 Controles de usuario (paper)

Controles mínimos:
- Start/Stop bot
- bankroll base editable
- límites de stake/exposición
- selección de ciudades
- selección de horizontes
- perfil riesgo (`Conservadora` / `Agresiva`)

Cambios de configuración:
- versionados (`config_version`)
- audit log de cambio (quién, cuándo, qué)

---

## 3) Plan de implementación por bloques

### Bloque 1 (Infra hardening)
- tablas nuevas + migraciones
- writes idempotentes
- snapshot 1m
- checks de integridad
- reconciliación de capital

### Bloque 2 (API operativa)
- `/trading/*` read endpoints
- `/trading/control` paper-only
- tests contract JSON

### Bloque 3 (UI Trading Dashboard)
- tarjetas capital/riesgo
- tabla mercados + señales
- tabla órdenes + posiciones
- panel salida/trace

### Bloque 4 (Soak + release)
- test 60 min auto-refresh
- confirmación latencias
- validación de paridad datos
- go/no-go checklist firmado

---

## 4) Criterios de aceptación finales

La fase se considera completada si:

1. Se ven explícitamente mercados monitorizados (hoy/mañana/pasado) con SI/NO.
2. Se ven entradas/salidas paper por mercado con estado y PnL.
3. Capital y exposición se reconcilian sin drift.
4. Cualquier recomendación tiene trazabilidad de decisión.
5. Dashboard útil en < 150 ms p95.
6. Guardrail `live=false` activo y verificable.

---

## 5) Riesgos y mitigaciones

Riesgo: sobrecarga SQLite por fan-out de dashboard  
Mitigación: snapshots agregados + cache + queries livianas

Riesgo: inconsistencia entre señales y órdenes  
Mitigación: `decision_id` canónico en toda la cadena

Riesgo: ilusión de precisión por simulación  
Mitigación: badges visibles `SIMULATED`, métricas de gap vs paper

---

## 6) Entregable esperado para el otro hilo (prompt-ready)

Implementar `M5.4` en dos pasos estrictos:
1. `M5.4A Infra`: tablas, endpoints base, reconciliación, SLO guards.
2. `M5.4B Trading Dashboard`: Simple+Expert operativos con mercados, señales, órdenes, posiciones y riesgo.

Sin live execution.  
Sin cambiar reglas de seguridad de M5.

