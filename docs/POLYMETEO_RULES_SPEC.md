# POLYMETEO_RULES_SPEC

Version: 1.0 (transfer package)
Date: 2026-02-26
Audience: Nuevo proyecto `polymeteo-bot` (repo separado) y cualquier equipo que necesite reutilizar reglas de meteoTrader sin perder contexto.

## 0. Proposito

Este documento congela las reglas, contratos y edge cases operativos aprendidos en `meteoTrader` que SI deben preservarse al construir sistemas nuevos (bot, backend de ejecucion, dashboard profesional, simulador, recorder, etc.).

Objetivo principal:
- Evitar perder la "memoria de guerra" acumulada en parsing, validacion de mercados y coherencia meteo/mercado.
- Separar reglas reutilizables (observacion/viabilidad/mercado) de logicas de UI o recomendacion manual.

No objetivo:
- Definir la arquitectura del bot (eso va en `METEOBOT_ARCH_V0.md`).

## 1. Fuente de verdad y referencias base

### 1.1 Proyecto origen
- Proyecto: `/Users/emiair/Documents/CodexAPP/meteoTrader`
- Checkpoint funcional: `PolyMeteo v1.0` tag `v1.0.0`
- Commit de checkpoint/tag: `16ea635`

### 1.2 Documentos origen (leer antes de portar)
- `/Users/emiair/Documents/CodexAPP/meteoTrader/MeteoTrader.md`
- `/Users/emiair/Documents/CodexAPP/meteoTrader/PENDIENTES.md`
- `/Users/emiair/Documents/CodexAPP/meteoTrader/app/meteoBot.md` (idea del bot, no implementacion)

### 1.3 Implementaciones de referencia (Kotlin/Android)
- Catalogo de ciudades: `/Users/emiair/Documents/CodexAPP/meteoTrader/app/src/main/java/com/polymeteo/meteotrader/data/CityCatalog.kt`
- Control station / Wunderground: `/Users/emiair/Documents/CodexAPP/meteoTrader/app/src/main/java/com/polymeteo/meteotrader/data/source/WundergroundSource.kt`
- MM (Media Modelos): `/Users/emiair/Documents/CodexAPP/meteoTrader/app/src/main/java/com/polymeteo/meteotrader/data/forecast/PolyTempCalculator.kt`
- MMA premium: `/Users/emiair/Documents/CodexAPP/meteoTrader/app/src/main/java/com/polymeteo/meteotrader/data/premium/PolyTempPremiumEngine.kt`
- Cache de pesos premium: `/Users/emiair/Documents/CodexAPP/meteoTrader/app/src/main/java/com/polymeteo/meteotrader/data/premium/PremiumWeightsPreferencesStore.kt`
- Motor Polymarket (probabilidad/edge/controles): `/Users/emiair/Documents/CodexAPP/meteoTrader/app/src/main/java/com/polymeteo/meteotrader/data/source/PolymarketSource.kt`
- Calibracion por ciudad/horizonte/franja: `/Users/emiair/Documents/CodexAPP/meteoTrader/app/src/main/java/com/polymeteo/meteotrader/data/source/TraderCalibration.kt`

### 1.4 Implementacion web funcional (Node) donde se reprodujeron y corrigieron edge cases
- Backend web provider hibrido: `/Users/emiair/Documents/CodexAPP/meteoTrader/backend/web_api/src/providers/hybridMetarProvider.js`
- Lector Polymarket solo lectura (web): `/Users/emiair/Documents/CodexAPP/meteoTrader/backend/web_api/src/providers/polymarketReadOnly.js`
- MMA premium web (paridad parcial avanzada): `/Users/emiair/Documents/CodexAPP/meteoTrader/backend/web_api/src/providers/mmaPremiumEngine.js`

## 2. Entidades canonicas y semantica obligatoria

### 2.1 Variables visibles y su significado (no confundir)
- `M`: METAR actual (observacion puntual del aeropuerto / estacion METAR)
- `Delta` / `Δ`: cambio vs METAR anterior del mismo dia
- `S`: temperatura maxima observada en estacion de control (Wunderground / weather.com fallback), usada como proxy de resolucion
- `P`: `MM` (Media Modelos) en la UI compacta
- `MM`: Media Modelos (promedio ponderado robusto de pronosticos)
- `MMA`: Media Modelos Ajustada (pesos aprendidos historicamente + blending dominante)

### 2.2 Regla de naming (contracto UI)
Texto visible obligatorio consolidado:
- `Media Modelos (MM)`
- `Media Modelos Ajustada (MMA)`

No reintroducir `PolyTemp` / `PolyTemp PREMIUM` en UI nueva.

### 2.3 Horizontes
- `H0` = hoy
- `H1` = manana
- `H2` = pasado manana

Toda logica de modelo, observacion, mercado y scoring debe ser horizon-aware.

## 3. Catalogo de ciudades (regla estructural)

### 3.1 Lista fija (14 ciudades)
El producto trabaja con un catalogo fijo de 14 ciudades. No asumir ciudad libre por texto.

Ciudades:
- Miami
- London
- Toronto
- Seattle
- Dallas
- Wellington
- Ankara
- Seoul
- New York
- Chicago
- Atlanta
- Paris
- Buenos Aires
- Sao Paulo

### 3.2 Datos minimos por ciudad (contracto)
Cada ciudad debe tener:
- `id` (slug interno estable)
- `name`
- `metarCode` (ICAO)
- `lat`, `lon`
- `zoneId` (IANA timezone)
- `displayUnit` (`C` o `F`)
- `wundergroundControlUrl`
- `wundergroundPwsUrl`

## 4. Reglas no negociables de alineacion mercado-tiempo

### 4.1 Regla de alineacion por fecha (critica)
- Mercados `Hoy` se evaluan con pronosticos H0 + observacion H0
- Mercados `Manana` se evaluan con pronosticos H1 (sin METAR)
- Mercados `Pasado` se evaluan con pronosticos H2 (sin METAR)

Nunca evaluar H1/H2 como si fuesen H0.

### 4.2 Regla de METAR por horizonte
- H0: METAR disponible (si responde el source)
- H1/H2: mostrar / tratar como `SIN METAR`

### 4.3 Cierre por horario local (regla de producto, no de exchange)
- Si hora local de la ciudad `>= 18:00`, la ciudad se considera cerrada para recomendaciones operativas en la home/grid.
- Esto NO significa que Polymarket este cerrado. Significa que la app se autoimpone no recomendar entradas nuevas por degradacion de valor.

## 5. Regla de resolucion Polymarket (precision y truncado)

### 5.1 Precision de resolucion
Polymarket resuelve estos mercados a grados enteros (`whole degrees`).

### 5.2 Truncado operativo obligatorio
Para filtrar mercados imposibles con observacion de maxima:
- Truncar la temperatura observada de estacion de control a entero (no redondear)

Ejemplos:
- `29.9 C` -> `29 C`
- `29.1 C` -> `29 C`
- `29.0 C` -> `29 C`

### 5.3 Separacion de conceptos (error historico corregido)
- `Forecast invalidation` (MM/MMA por debajo de maxima observada) != `Market invalidation`
- Aunque MM/MMA quede invalidado hoy, pueden seguir existiendo mercados posibles (ej. bucket actual o superiores)

## 6. Observacion y estacion de control (`S`) - reglas de parsing criticas

## 6.1 METAR / TAF (aviationweather)
Fuentes:
- METAR/TAF desde `aviationweather.gov`

Reglas:
- METAR actual y anterior deben pertenecer al mismo dia para calcular `Delta` operativo
- Mostrar timestamp de observacion y delta temporal (`Hace X horas`) cuando aplica
- TAF es complementario; puede ser util como contexto de riesgo, no como resolucion

## 6.2 `S` (control station) - prioridad de fuentes y parser robusto

### Regla de negocio
`S` debe representar la **maxima diaria observada** mas fiable para la estacion de control / proxy de resolucion. No un current cualquiera.

### Estrategia robusta consolidada (Kotlin/Web)
Intentos por URL (orden base):
1. `wundergroundControlUrl` (history/daily)
2. `wundergroundPwsUrl` (dashboard PWS)

Para fecha especifica (historico):
1. `control/date/{yyyy-mm-dd}`
2. `control` base
3. `PWS`

### Prioridad interna de candidatos (conceptual)
1. `Summary -> High Temp Actual` (preferente)
2. Observacion/API robusta (`api.weather.com/v3/wx/observations/current`, usando `temperatureMaxSince7Am` / `temperatureMax24Hour` / `temperatureMax`)
3. Candidatos embebidos con scoring fuerte (contexto + `icaoCode` + `units=e/m`)
4. Fallbacks embebidos debiles (solo si no hay nada mejor)

### Regla de seleccion (negocio)
Cuando hay dos candidatos validos (ej. Summary + current-like), se selecciona la temperatura mas alta valida para no perder maxima diaria.

### Parseo de unidades (memoria de guerra)
Error historico recurrente:
- Wunderground mezcla payloads embebidos de otras ciudades/unidades.

Regla corregida:
- Priorizar pistas de unidad `units=e` / `units=m`
- No confiar ciegamente en `temperatureUnit` de contextos embebidos mezclados
- Validar plausibilidad por unidad antes de aceptar el candidato

### Rangos de plausibilidad (referencia actual)
- Celsius: `-80 .. 65`
- Fahrenheit: `-110 .. 160`

### Fallback weather.com (critico)
Cuando embedded WU no es fiable:
- consultar `https://api.weather.com/v3/wx/observations/current`
- probar rutas por `icaoCode` y por `geocode`, en unidades `e` y `m`
- parsear maxima desde:
  - `temperatureMaxSince7Am`
  - `temperatureMax24Hour`
  - `temperatureMax`

### Scoring embebido (Kotlin referencia)
Heuristicas clave:
- `icaoCode` exacto en contexto (peso alto)
- `v3/wx/observations/current` en contexto
- presencia de `temperatureMaxSince7Am`
- `units=` en contexto
- unidad plausible

## 6.3 Edge cases reales ya sufridos (obligatorio preservar)
Casos que NO se deben repetir:
- Miami web mostrando `S=117F` (parser WU contaminado)
- New York web mostrando `S=77F` (embedded wrong pick)
- Buenos Aires web mostrando `S=15C` cuando app mostraba `25-26C`
- Londres usando current inferior y no la maxima del Summary (mercados seguian abiertos cuando ya no debian)
- Seoul `S=39C` por mezcla de payloads de otra ubicacion

Leccion:
- Wunderground puede dar datos plausibles pero incorrectos por contexto cruzado. El parser debe ser conservador y trazable.

## 7. Descubrimiento y parseo de mercados Polymarket

## 7.1 Descubrimiento (regla robusta)
Orden de busqueda:
1. Slugs directos candidatos por ciudad/fecha (`today`, `H1`, `H2`)
2. Fallback por `search` (Gamma API)

### Reglas adicionales
- deduplicar por `marketId`
- filtrar por fecha objetivo parseada de la pregunta
- excluir cerrados/resueltos en flujo operativo (permitir `allowClosed=true` solo en historicos / auditoria)

## 7.2 Parseo de pregunta y condicion (critico)
El parser debe soportar al menos:
- exacto (`17C`, `40-41F` segun formato de mercado)
- `GREATER_OR_EQUAL` / `OVER` (`17°C or higher`)
- `LESS_OR_EQUAL` / `UNDER`
- `BETWEEN` / rangos discretos

Ademas debe extraer correctamente:
- ciudad
- fecha objetivo
- unidad (`C` / `F`)
- threshold(s)

## 7.3 Regla de imposibles (observed floor)
Para H0:
- construir `observedFloor` a partir de observacion disponible (METAR + control station, con precision de resolucion/truncado)
- eliminar buckets matematicamente imposibles

Importante:
- esta regla es independiente de si el modelo (MM/MMA) acierta o no

## 7.4 Mercados dominados (late-day suppression)
Regla de control de producto:
- desde cierta hora local (`DOMINANCE_CHECK_HOUR`, referencia actual `13:00`)
- si un bucket / mercado llega a `YES >= 95%`
- suprimir recomendaciones del resto de mercados de ese dia por dominancia extrema

## 8. MM (Media Modelos) - regla de calculo reutilizable

Implementacion de referencia:
- `/Users/emiair/Documents/CodexAPP/meteoTrader/app/src/main/java/com/polymeteo/meteotrader/data/forecast/PolyTempCalculator.kt`

## 8.1 Entradas validas
Solo usar sources con:
- `status == SUCCESS`
- `maxTempC != null`
- rango valido `[-80, 65] C`

## 8.2 Outlier filtering robusto
- calcular mediana de valores
- calcular MAD (median absolute deviation)
- tolerancia = `max(1.8, mad * 3.2)`
- si >2 fuentes, filtrar outliers fuera de la tolerancia
- si el filtrado deja vacio, recuperar lista original (fail-safe)

## 8.3 Media ponderada + blending dominante
- usar pesos dinamicos por provider (si existen), si no base weights (`ForecastWeights`)
- calcular weighted mean
- si un modelo domina claramente (share/ratio), mezclar hacia el dominante con alpha acotada

Constantes relevantes actuales (Android):
- `PREMIUM_DOMINANT_MIN_SHARE = 0.22`
- `PREMIUM_DOMINANT_TARGET_SHARE = 0.35`
- `PREMIUM_DOMINANT_MIN_RATIO = 1.03`
- `PREMIUM_DOMINANT_TARGET_RATIO = 1.20`
- `PREMIUM_DOMINANT_BASE_ALPHA = 0.45`
- `PREMIUM_DOMINANT_EXTRA_ALPHA = 0.30`
- `PREMIUM_DOMINANT_MAX_ALPHA = 0.75`

## 8.4 Warnings MM (operativos)
Generar warnings si aplica:
- outliers descartados
- pocas fuentes validas (`<3`)
- alta dispersion (spread > `5.5 C`)

## 9. MMA (Media Modelos Ajustada) - reglas premium y caches

Implementacion de referencia:
- `/Users/emiair/Documents/CodexAPP/meteoTrader/app/src/main/java/com/polymeteo/meteotrader/data/premium/PolyTempPremiumEngine.kt`
- cache de pesos por horizonte: `/Users/emiair/Documents/CodexAPP/meteoTrader/app/src/main/java/com/polymeteo/meteotrader/data/premium/PremiumWeightsPreferencesStore.kt`

## 9.1 Concepto
MMA = MM con pesos aprendidos dinamicamente por:
- ciudad
- horizonte (`H0/H1/H2`)
- rendimiento historico por provider vs maxima observada real

## 9.2 Capacidades del engine premium (Android referencia)
- ingesta de snapshots por provider/fecha/horizonte
- verificacion de fechas pendientes contra observados
- bootstrap historico por horizon (H0 desde 2022; H1/H2 desde 2024 en referencia actual)
- cache de pesos resolvidos por horizonte con firma de verificacion
- reporte de progreso / dias verificados / modelo dominante
- exclusion de providers deprecated

## 9.3 Reglas operativas premium a preservar
- No bloquear UI si no hay cache: usar fallback + recalculo en background
- Separar modos de carga:
  - `cache-only` para pantallas rapidas (grid)
  - `ensure` para vista critica (detalle)
- No persistir como "fresh" una calibracion vacia/no-data

## 9.4 Invalidation rule (same-day)
Regla consolidada:
- Un forecast (MM o MMA) queda invalidado hoy si `forecastMax < observedMax` (epsilon aware)
- Si MM invalida pero MMA no, NO mostrar mensaje global de invalidez como si afectara a ambos

## 9.5 Live floor (regla funcional)
En H0, el forecast efectivo no puede quedar por debajo de la maxima observada ya alcanzada (model floor operativo).

## 10. Motor de decision Polymarket (manual trading support) - reglas exactas

Implementacion de referencia:
- `/Users/emiair/Documents/CodexAPP/meteoTrader/app/src/main/java/com/polymeteo/meteotrader/data/source/PolymarketSource.kt`
- Calibracion: `/Users/emiair/Documents/CodexAPP/meteoTrader/app/src/main/java/com/polymeteo/meteotrader/data/source/TraderCalibration.kt`

## 10.1 Modelo de probabilidad por condicion
Usa `mean` + `sigma` calibrados por ciudad/horizonte/franja local y calcula probabilidad `YES` por tipo:
- `GREATER_OR_EQUAL`
- `LESS_OR_EQUAL`
- `EXACT`
- `BETWEEN`

Ajustes de calibracion (`TraderCalibration.resolve`):
- `sigmaMultiplier`
- `confidenceMultiplier`
- `meanBiasC`

Bandas horarias locales:
- `NIGHT` (0-5)
- `MORNING` (6-10)
- `MIDDAY` (11-16)
- `EVENING` (17-23)

## 10.2 Cost-aware edge (no raw edge puro)
Calculo operativo:
- `evYes`, `evNo`
- `rawEdge`
- `feeCost`
- `spreadCost`
- `liquidityCost`
- `totalCost`
- `edgeAfterCosts`
- `executableEdge = edgeAfterCosts * fillProbability`

Esto es clave: el sistema recomienda por **edge ejecutable**, no por edge bruto.

## 10.3 Fill probability heuristica (Android referencia)
`fillProbability` usa blend de:
- liquidez (`liquidity`)
- volumen 24h (`volume24h`)
- spread

No usar un fill fijo ni asumir ejecucion perfecta.

## 10.4 Señales y recomendacion base (referencia actual)
Umbrales en `PolymarketSource.kt`:
- Base tradeable:
  - `MIN_FILL_PROBABILITY_TO_TRADE = 0.50`
  - `MIN_EXECUTABLE_EDGE_TO_TRADE = 0.020`
- `GREEN`:
  - `GREEN_EXECUTABLE_EDGE = 0.06`
  - `GREEN_MIN_FILL_PROBABILITY = 0.65`
- `YELLOW`:
  - `YELLOW_EXECUTABLE_EDGE = 0.025`
  - `YELLOW_MIN_FILL_PROBABILITY = 0.45`

## 10.5 Controles de ejecucion (filtros duros, referencia actual)
Valores actuales en `PolymarketSource.kt` (usar como baseline, no como dogma universal):
- `CONTROL_MIN_EXECUTABLE_EDGE = 0.025`
- `CONTROL_MIN_FILL = 0.55`
- `CONTROL_MIN_LIQUIDITY = 250`
- `CONTROL_MIN_VOLUME_24H = 200`
- `CONTROL_MAX_SPREAD = 0.12`
- `CONTROL_MAX_TOTAL_COST = 0.22`
- `CONTROL_MIN_ENTRY_PRICE = 0.02`
- `CONTROL_MAX_ENTRY_PRICE = 0.90`

Controles temporales:
- `LATE_DAY_HOUR = 15`
- `LATE_DAY_MIN_EXECUTABLE_EDGE = 0.035`
- `DOMINANCE_CHECK_HOUR = 13`
- `DOMINANCE_YES_THRESHOLD = 0.95`

## 10.6 Trazabilidad (decision trace)
Stages estandarizados:
- `INPUT`
- `DOMINANCE`
- `EXECUTION_CONTROL`

Toda exclusion importante debe quedar trazada con:
- `reason`
- `details` (exec, fill, liq, vol24h, spread, costes, thresholds)

## 11. Reglas de UI/semantica que SI afectan al bot (por consistencia de lenguaje)

Aunque el bot sera otro proyecto, mantener semantica consistente en dashboard futuro:
- `M`, `Δ`, `S`, `P/MM`, `MMA`
- "hoy/manana/pasado" como `H0/H1/H2` si se expone tecnico, pero ofrecer traduccion de UX
- diferenciar claramente:
  - mercado activo en Polymarket
  - mercado recomendado por filtros propios

## 12. Reglas de rendimiento y resiliencia heredables

### 12.1 Timeouts/concurrencia (baseline actual de app)
Baselines desde `MeteoTrader.md` (mejorables, pero sirven como punto de partida):
- METAR timeout ~7s
- TAF timeout ~6s
- Control station timeout ~8s
- Forecast provider timeout ~9s
- Polymarket timeout ~6s
- Premium engine timeout ~5s
- Max city concurrency ~4

### 12.2 Fail-safe obligatorio
- Si una fuente falla: seguir con fuentes validas, marcar warning, no inventar datos
- Si faltan inputs criticos para un mercado/horizonte: NO recomendar
- Preferir `NO BET` frente a datos dudosos

## 13. Casos de error y correcciones (memoria de guerra resumida)

### 13.1 Wunderground / control station (`S`)
Incidencias reales:
- `S` absurdas por payload embebido cruzado (otras ciudades)
- `Summary` correcto ignorado por parser y reemplazado por current inferior o contaminado
- Unidades mal inferidas (`C` vs `F`) en embedded

Correcciones clave (web parity commits):
- `000faa9` robust parser WU control
- `0de4e91` unit parsing alineado con movil
- `ba6bc61` scoring embebido alineado con movil
- `178894a` preferir weather.com API cuando embedded es debil
- `829ab1e` prioridad de `S` y semantica de city cards

### 13.2 Invalidation y markets imposibles
Incidencias reales:
- se ocultaban mercados todavia posibles porque MM invalidado se confundia con mercado invalidado
- se eliminaban buckets por redondeo en vez de truncado

Correcciones:
- separar invalidacion de forecast vs viabilidad del mercado
- truncar observacion a entero segun precision de Polymarket

### 13.3 Home vs detalle inconsistentes
Incidencia real:
- grid/ticker mostraban oportunidad de H1/H2 como si fuera H0

Correccion:
- etiquetar horizonte en ticker y grid (`[M]`, `[P]`, etc.) y/o usar mismo motor con cache corto

## 14. Checklist de validacion que debe migrar al nuevo proyecto

Migrar y mantener el checklist operativo de `/Users/emiair/Documents/CodexAPP/meteoTrader/PENDIENTES.md` para QA en vivo.

Minimo por ciudad/horizonte:
- existe evento por slug y por fallback
- numero de buckets coincide con Polymarket web
- mercados cerrados/resueltos no aparecen
- mercados abiertos siguen visibles si son posibles
- parseo de condicion/unidad/fecha correcto
- truncado de maxima observada correcto
- H1/H2 muestran `SIN METAR`
- incidentes se registran con payload + captura + decision esperada

## 15. Que reglas SI son reutilizables para el bot de market-making

Directamente reutilizables (o portables casi 1:1):
- catalogo de ciudades/ICAO/unidades/zonas
- parsing de mercados meteo (condicion + fecha + unidad)
- construccion de `observedFloor` (METAR + `S`) con truncado Polymarket
- schedule-close heuristico por ciudad (>=18h) como filtro de riesgo de producto
- deteccion de ventanas peligrosas por cercania de observacion al bucket
- robust parser de `S` y priorizacion de fuentes (WU/weather.com)
- trazabilidad de decisiones (aunque las razones cambien en MM neutral)

## 16. Que reglas NO se deben reutilizar tal cual en un bot market-making

No trasladar directamente (solo como referencia conceptual):
- `BET/PASS` direccional orientado a apuesta manual
- `OVER/UNDER/RANGE` como accion principal del motor
- scoring de oportunidad basado en prediccion meteorologica pura
- umbrales de UI rookie/experto

Motivo:
- el bot propuesto es market-making neutral + captura de spread, no un modelo direccional de forecast trading.

## 17. Criterio de "paridad suficiente" entre entornos (movil/web/bot)

Paridad suficiente significa:
- mismas reglas de parsing y viabilidad (aunque haya pequenas diferencias por refresh)
- mismas semanticas (`M`, `Δ`, `S`, `P/MM`, `MMA`)
- misma precision y truncado de resolucion
- mismas exclusiones de mercados imposibles/dominados segun reglas de producto

No significa necesariamente:
- valores identicos al segundo (por latencia/refresco)
- mismo set exacto de providers en cada runtime (si se documenta y se audita)

## 18. Recomendacion de transferencia al nuevo proyecto (operativa)

Antes de arrancar `polymeteo-bot`, copiar/referenciar estos activos:
1. Este documento (`POLYMETEO_RULES_SPEC.md`)
2. `METEOBOT_BOUNDARY.md`
3. `METEOBOT_ARCH_V0.md`
4. `/Users/emiair/Documents/CodexAPP/meteoTrader/MeteoTrader.md`
5. `/Users/emiair/Documents/CodexAPP/meteoTrader/PENDIENTES.md`
6. `CityCatalog.kt` (exportar catalogo a JSON/TS/Rust)
7. Fixtures de mercados y payloads problematicos (WU/Polymarket) usados en debugging

## 19. Anexo de commits clave (mapa de aprendizaje)

Checkpoint funcional y web parity relevantes:
- `16ea635` `PolyMeteo v1.0` (tag `v1.0.0`)
- `c9dfc50` Web parity: MM/MMA model math + live criteria
- `0595eb8` Web parity: TraderCalibration + execution controls
- `000faa9` Web parity: robust WU control parser
- `0de4e91` Web parity: embedded unit parsing alignment
- `ba6bc61` Web parity: embedded scoring alignment
- `178894a` Web parity: prefer reliable weather.com API over weak embedded
- `829ab1e` Web parity: city cards + control station priority
- `19ff24b` Web parity: premium MMA engine for web backend

## 20. Instruccion explicita para el equipo del bot

Si una regla parece "excesivamente defensiva" (fallback, scoring, truncado, dominancia, no-bet):
- asumir que existe un incidente real que la justifica.
- no simplificarla sin prueba de regresion contra casos reales (Miami/NY/BA/London/Seoul).

