# METEOBOT_BOUNDARY

Version: 1.0 (transfer package)
Date: 2026-02-26
Audience: Diseno y arranque de `polymeteo-bot` como proyecto separado.

## 0. Decision arquitectonica (resumen)

Decision recomendada:
- **Proyecto separado** (nuevo repo/carpeta al mismo nivel que `meteoTrader`) para el bot de market-making.
- `meteoTrader` se mantiene como sistema de analisis/decision support.
- El bot puede consumir reglas y datos derivados de `meteoTrader`, pero no debe ejecutar su hot path dentro de la app movil/web actual.

Motivo principal:
- Separar riesgo financiero, latencia, seguridad de claves y operacion 24/7 del producto de analisis.

## 1. Sistemas y responsabilidades

## 1.1 `meteoTrader` (actual)
Rol:
- decision support (forecast + observaciones + microestructura + filtros)
- UI movil/web para experto/rookie
- simulacion/paper orientada a usuario
- intel de usuarios Polymarket + copytrade informativo

No rol:
- market-making autonomo de baja latencia
- gestion de ordenes live 24/7 como hot path

## 1.2 `polymeteo-bot` (nuevo)
Rol:
- motor de market-making / arbitraje de spreads (neutral)
- simulacion/backtest de microestructura
- paper-live / live execution
- risk engine de inventario y ejecucion
- recorder de datos de orderbook/trades
- dashboard de control + observabilidad

No rol inicial:
- forecasting meteorologico avanzado como motor primario de alpha direccional

## 2. Frontera de reutilizacion (que se hereda y como)

## 2.1 Reutilizar SI (directamente o con port 1:1)

### A. Reglas de observacion y resolucion
- `M` (METAR actual/anterior + delta)
- `S` (control station max diaria) con parser robusto y fallback weather.com
- truncado a entero para precision Polymarket
- `observedFloor` para filtrar mercados imposibles

Por que sirve al bot:
- evita cotizar buckets imposibles o casi resueltos
- mejora gestion de riesgo y "ventanas peligrosas"

### B. Catalogo de ciudades
- IDs, ICAO, timezone, lat/lon, unidad de display, URLs WU/PWS

Por que sirve al bot:
- universo de mercados fijo y estable
- calculos de hora local, ventanas de riesgo, geocoding consistente

### C. Parseo de mercados meteo Polymarket
- deteccion de ciudad en pregunta
- parseo de unidad
- parseo de fecha objetivo
- parseo de condicion (`EXACT`, `BETWEEN`, `>=`, `<=`)

Por que sirve al bot:
- mapea correctamente cada asset/bucket a semantica meteorologica/riesgo

### D. Reglas de viabilidad y producto
- cierre por horario local (heuristico de producto)
- dominancia extrema (mercado practicamente resuelto)
- separacion `forecast invalidation` vs `market invalidation`

Por que sirve al bot:
- se puede usar como gating de quoting / size reduction / bot-off window

### E. Trazabilidad operativa
- razones de exclusion / warning / fallback
- eventos auditable con detalles

Por que sirve al bot:
- post-mortem, debugging de fills, validacion de por que se apagaron cotizaciones

## 2.2 Reutilizar SOLO como referencia conceptual (no copiar tal cual)

### A. MM/MMA y engine de forecasting
- `MM`/`MMA` son utiles para contexto y riesgo meteorologico
- pero el bot propuesto es market-making neutral (captura spread), no direccional por defecto

Uso recomendado en bot:
- como modulador de riesgo, no como trigger de lado
- ejemplo: reducir size, widen spread o apagar si forecast + observacion indican zona de resolucion peligrosa

### B. Trader Mode `BET/PASS`, `OVER/UNDER`, `YES/NO`
- este motor esta pensado para apuestas manuales direccionales
- NO usarlo como motor de quoting del bot

Uso recomendado:
- solo como overlay/telemetria en dashboard (opcional)

### C. Modo Rookie/Experto y UI movil
- no mezclar con dashboard del bot
- solo reutilizar patrones de explicabilidad/ayuda contextual

## 2.3 No reutilizar (debe rehacerse para el bot)

### A. Hot path de ejecucion
- order manager actual (paper/manual) no sirve para MM live
- se necesita un engine de cotizacion/cancelacion en Rust + WS + estado en memoria

### B. Seguridad de claves y firma
- la app actual y web funcional no son el lugar para llaves privadas del bot
- el bot live debe firmar server-side con controles de seguridad

### C. Copytrade / notificaciones como infraestructura del bot
- copytrade actual es una funcionalidad de monitorizacion del usuario, no un engine de ejecucion
- puede reutilizarse el concepto UI, no la implementacion como core del bot

## 3. Frontera de seguridad (muy importante)

## 3.1 Principio
Nada de ejecucion real del bot debe depender de frontend o navegador.

## 3.2 Reglas
- Nunca exponer private key en frontend
- Nunca usar MetaMask/WalletConnect como mecanismo unico para un bot autonomo 24/7
- El frontend puede:
  - autenticar al usuario
  - mostrar estado
  - cambiar parametros
  - disparar acciones admin (Start/Stop/Kill)
- El backend del bot debe:
  - mantener conexiones WS
  - firmar ordenes (idealmente con signer/kms)
  - aplicar risk checks
  - persistir logs y estado

## 3.3 Credenciales (tipos)
Separar credenciales por rol:
- Credenciales de mercado (CLOB/trading)
- Credenciales de observacion (APIs weather)
- Credenciales de notificaciones (Telegram/Web Push/SMTP)
- Credenciales de dashboard (auth)

## 4. Frontera de latencia y runtime

## 4.1 Donde SI importa <100 ms
- Recepcion de book WS
- Calculo de quote
- Cancel-before-requote
- Envio de ordenes/cancelaciones
- Actualizacion de inventario en memoria

## 4.2 Donde NO hace falta <100 ms
- UI dashboard
- Logs/auditoria
- Reportes
- Backfill historico
- Config admin

Consecuencia de diseno:
- hot path minimalista y en memoria (Rust)
- telemetria y UX desacopladas

## 5. Frontera de datos y ownership

## 5.1 Datos que debe poseer `polymeteo-bot`
- snapshots del libro (recorder)
- trades/fills del bot
- ordenes/cancelaciones y resultados
- inventario neto por bucket/mercado
- PnL (realizado/no realizado)
- configuracion de estrategia/riesgo por mercado
- eventos de kill-switch / circuit breakers

## 5.2 Datos que puede importar de `meteoTrader` (o recrear siguiendo spec)
- catalogo de ciudades
- reglas de observacion (M/S/truncado)
- estado meteorologico resumido para ventanas de riesgo
- parseo de condicion de mercados meteo

## 5.3 Datos que NO deben ser fuente de verdad compartida
- `MM/MMA` de una app cliente local (movil) como source-of-truth para el bot live
- preferencias locales del usuario de la app para pesos premium del bot live

Si se desea compartir `MMA`, hacerlo via:
- servicio backend canonico, o
- exportacion versionada con firma/fecha y trazabilidad

## 6. Frontera funcional: prediccion vs market-making

## 6.1 Lo que hace `meteoTrader`
- estima probabilidad/edge direccional y decide `BET/PASS`

## 6.2 Lo que debe hacer `meteoBot`
- cotizar ambos lados (neutral) para capturar spread
- gestionar inventario
- minimizar adverse selection
- apagar/reducir riesgo en ventanas peligrosas

## 6.3 Integracion correcta entre ambos mundos
Usar senales meteo como modulador de MM, por ejemplo:
- widen spread cuando `observedFloor` se acerca al bucket
- reducir size si `S` y `M` sugieren alta probabilidad de resolucion cercana
- desactivar quoting en ventanas peligrosas (reglas del documento del bot)

No usar de entrada:
- `OVER/UNDER BET YES/NO` del trader mode para decidir lado del bot

## 7. Contrato minimo de integracion recomendado (meteo -> bot)

Si se decide integrar datos meteoTrader en el bot via API, este es el contrato minimo util.

### 7.1 `ObservedCityState` (propuesto)
Campos minimos:
- `cityId`
- `timestampUtc`
- `localDate`
- `localHour`
- `metarCurrentTempC`
- `metarPreviousTempC`
- `metarDeltaC`
- `controlStationMaxTempC`
- `observedFloorCTruncated` (y/o F segun ciudad)
- `sourceKindControlStation` (`summary`, `weather-com-observation-api`, etc.)
- `qualityWarnings[]`

### 7.2 `MarketMeteoRiskState` (propuesto)
Por mercado/bucket:
- `marketId`
- `cityId`
- `targetDate`
- `conditionType`
- `threshold(s)`
- `unit`
- `isImpossibleByObservedFloor`
- `isLateDayClosedByPolicy`
- `isDominatedByPolicy`
- `dangerWindowLevel` (`NONE`, `LOW`, `HIGH`, `BOT_OFF`)
- `reasonCodes[]`

### 7.3 `ForecastContextState` (opcional)
Solo si el bot usa forecast para modulation:
- `mmC`
- `mmaC`
- `mmInvalidToday`
- `mmaInvalidToday`
- `dominantModelId`
- `dominantModelWeight`
- `calibrationReady`

## 8. Estrategia de transferencia al nuevo repo (sin perder memoria)

## 8.1 Antes de empezar codigo del bot
Copiar/portar a `polymeteo-bot/docs/`:
- `POLYMETEO_RULES_SPEC.md`
- `METEOBOT_BOUNDARY.md`
- `METEOBOT_ARCH_V0.md`
- (opcional) `MeteoTrader.md` como referencia historica

## 8.2 Artefactos de alto valor a exportar desde `meteoTrader`
1. Catalogo de ciudades (`CityCatalog`) a JSON versionado
2. Corpus de payloads problematicos (WU/Polymarket) para tests
3. Casos QA de truncado/viabilidad (fixtures)
4. Lista de commits clave de correcciones (mapa de aprendizaje)

## 8.3 Tests de regresion que deben nacer ya en `polymeteo-bot`
- parseo de mercados meteo (preguntas reales)
- truncado Polymarket (observedFloor)
- `S` parser con payloads problematicos historicos
- ventanas peligrosas por hora local + observacion
- razon de exclusion trazable (reason codes)

## 9. Riesgos si se viola esta frontera

### 9.1 Mezclar bot dentro de `meteoTrader`
Riesgos:
- despliegues de UI afectan hot path de trading
- mayor probabilidad de exponer secretos
- latencia impredecible
- debugging mas dificil (sistema demasiado acoplado)

### 9.2 Reusar logica direccional como si fuera MM
Riesgos:
- bot deriva a apuestas direccionales sin control
- adverse selection mas fuerte
- falsa sensacion de edge por usar MM/MMA fuera de contexto

### 9.3 No portar la memoria de parsing/fallback
Riesgos:
- se repiten errores de `S` (Miami/NY/BA/London/Seoul)
- ventanas peligrosas mal detectadas
- cotizaciones en buckets imposibles

## 10. Decision final recomendada (para el usuario y futuro equipo)

- Crear **nuevo proyecto** `polymeteo-bot` (repo/carpeta al mismo nivel de `meteoTrader`).
- Mantener `meteoTrader` como sistema de analisis, QA de reglas y referencia operativa.
- Compartir conocimiento via docs/specs/tests, no via acoplamiento de runtime.
- Integrar mas adelante via APIs internas o dashboard federado, no via "Modo Bot" incrustado en la app actual.

