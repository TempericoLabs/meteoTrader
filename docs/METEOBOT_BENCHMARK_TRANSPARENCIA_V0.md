# MeteoBot Benchmark Transparencia v0

Fecha: 2026-02-27  
Estado: Draft listo para implementación (M5.4)  
Ámbito: Dashboard `read-only` (sin live execution)

## 1) Objetivo

Definir un bloque **Benchmark externo** para comparar `meteoBot` frente a una referencia pública de transparencia (ej. [polycopy.dev/transparency](https://www.polycopy.dev/transparency)) en términos de:

- latencia
- velocidad de señal
- estabilidad operativa
- calidad de muestra

El objetivo no es marketing; es control operativo y mejora continua con reglas auditables.

## 2) Principios de comparabilidad

Para evitar conclusiones falsas:

1. Solo comparar métricas definidas 1:1.
2. Mostrar siempre tamaño de muestra (`n`) y ventana temporal.
3. Marcar `NO_COMPARABLE` cuando falten datos mínimos.
4. Separar claramente:
   - `paper/sim` (nuestro estado actual)
   - `live` (futuro)

## 3) Métricas comparables (núcleo)

## 3.1 Detection Latency (ms)

Definición `meteoBot`:
- `event_ingest_ts` -> `decision_emitted_ts`

Agregados:
- `avg`, `p50`, `p95`, `p99`, `min`, `max`, `n`

## 3.2 Execution Latency (ms)

Definición `meteoBot`:
- `decision_emitted_ts` -> `fill_or_ack_ts`

Notas:
- En `paper/sim` usar resultado del fill-model.
- Flag obligatorio: `execution_mode = simulated|real`.

## 3.3 Total Latency (ms)

Definición:
- `event_ingest_ts` -> `fill_or_ack_ts`

Agregados:
- `avg`, `p50`, `p95`, `p99`, `n`

## 3.4 Signals per minute

Definición:
- `decisions_emitted / minutes(window)`

## 3.5 Error/Drop rate

Definición:
- `failed_events + dropped_events` / `received_events`

## 4) Ventanas temporales

Ventanas soportadas (alineadas con transparencia pública):

- `1h`
- `6h`
- `24h`
- `7d`

Regla:
- Toda métrica debe ir etiquetada con su `window`.
- No mezclar ventanas en el mismo ratio.

## 5) Score de comparación

## 5.1 Ratio primario

Para cada métrica de latencia:

`ratio = our_p95_ms / ref_p95_ms`

Interpretación:
- `< 1.00` mejor que referencia
- `= 1.00` empate
- `> 1.00` peor que referencia

## 5.2 Score agregado (opcional v0)

`score = 0.5 * ratio_total_p95 + 0.3 * ratio_detection_p95 + 0.2 * ratio_execution_p95`

Más bajo es mejor.

## 6) Semáforo visual (umbral duro)

Por métrica:

- `VERDE`: ratio <= 1.05
- `AMARILLO`: 1.05 < ratio <= 1.25
- `ROJO`: ratio > 1.25
- `GRIS`: `NO_COMPARABLE`

## 7) Criterio de comparabilidad mínima

Una métrica sale como `NO_COMPARABLE` si:

- `our_n < 500` en `1h`, o
- `our_n < 1500` en `6h`, o
- no existe dato de referencia para la misma ventana, o
- timestamps fuera de rango > 2% de la muestra.

## 8) Contrato de datos (backend)

Endpoint nuevo recomendado:

- `GET /dashboard/benchmark?window=1h|6h|24h|7d`

Respuesta (ejemplo):

```json
{
  "ok": true,
  "generated_at_utc_iso": "2026-02-27T10:00:00.000Z",
  "window": "24h",
  "source_mode": "paper_live_shadow",
  "execution_mode": "simulated",
  "our": {
    "detection": {"p95_ms": 85.1, "avg_ms": 42.3, "n": 12234},
    "execution": {"p95_ms": 160.0, "avg_ms": 73.0, "n": 6400},
    "total": {"p95_ms": 238.8, "avg_ms": 121.5, "n": 6400},
    "signals_per_min": 3.8,
    "error_rate": 0.004
  },
  "reference": {
    "name": "polycopy_transparency",
    "fetched_at_utc_iso": "2026-02-27T09:58:00.000Z",
    "detection": {"p95_ms": 100.0},
    "execution": {"p95_ms": 210.0},
    "total": {"p95_ms": 290.0},
    "signals_per_min": 3.2
  },
  "compare": {
    "detection_ratio_p95": 0.851,
    "execution_ratio_p95": 0.762,
    "total_ratio_p95": 0.824,
    "status_detection": "GREEN",
    "status_execution": "GREEN",
    "status_total": "GREEN",
    "comparable": true,
    "reason": null
  }
}
```

## 9) Integración UI (dashboard)

Bloque: `Benchmark externo`

Cards mínimas:

1. `Detection p95` (our/ref/ratio + color)
2. `Execution p95` (our/ref/ratio + color)
3. `Total p95` (our/ref/ratio + color)
4. `Signals/min` (our/ref + delta)
5. `Error rate` (solo our, con objetivo)
6. `Sample quality` (`n`, ventana, comparable sí/no)

Reglas UX:

- Tooltip obligatorio en cada métrica con fórmula exacta.
- Etiqueta visible `SIMULATED` mientras no haya live.
- Si `NO_COMPARABLE`, render gris y razón explícita.

## 10) Fuente de referencia externa

En v0:

- modo `manual_snapshot` (carga manual de valores de referencia), o
- modo `public_fetch` solo si existe endpoint estable parseable.

Requisito:

- guardar siempre `reference_fetched_at_utc_iso` y `reference_source`.

## 11) Persistencia local recomendada

Tabla sugerida: `benchmark_reference_snapshots`

Campos:

- `id`
- `source_name`
- `window`
- `fetched_at_utc_iso`
- `detection_p95_ms`
- `execution_p95_ms`
- `total_p95_ms`
- `signals_per_min`
- `raw_json`

## 12) Alertas operativas

Alerta `benchmark_regression` cuando:

- `total_ratio_p95 > 1.25` durante 3 ventanas consecutivas, o
- `error_rate > 2%` en ventana `1h`.

## 13) Checklist de aceptación (M5.4)

1. Endpoint `/dashboard/benchmark` responde en < 120ms p95 (sin fetch externo en hot path).
2. Dashboard muestra las 6 cards con tooltips y semáforo.
3. `NO_COMPARABLE` se aplica automáticamente por muestra insuficiente.
4. Se conserva trazabilidad (`source`, `fetched_at`, `window`).
5. No introduce locks/contención en recorder SQLite.

## 14) Fuera de alcance v0

- Claims de “sub-X ms” en marketing.
- comparación legal/comercial automática sin revisión humana.
- benchmarking por ciudad/mercado (eso sería v1).

## 15) Siguiente iteración (v1)

- benchmark por ciudad (`London/NY/Seoul/Paris`)
- benchmark por tipo de mercado (binary/range)
- curvas temporales de ratio (`1h rolling`)
- control chart para drift de latencia

