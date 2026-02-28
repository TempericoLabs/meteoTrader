# MeteoBot - Protocolo de validación de sondeos para mejorar MMA (v0)

Fecha: 2026-02-28  
Estado: listo para ejecución  
Objetivo: validar, con rigor, si features de columna atmosférica mejoran la predicción de Tmax por ciudad/horizonte.

---

## 1) Premisa

No se integra nada en producción sin pasar este protocolo.

Se comparan dos sistemas:

- **Baseline**: `MMA actual` (sin sondeos adicionales).
- **Candidate**: `MMA + features de sondeo`.

La comparación es por:
- ciudad (`London`, `New York`, `Seoul`, `Paris` y luego resto),
- horizonte (`H0/H1/H2`),
- franja local.

---

## 2) Datos mínimos requeridos

Por cada ciudad/horizonte/fecha:

1. Observado target:
- `Tmax_observada_C` (fuente de resolución real del mercado).

2. Predicción baseline:
- `MMA_base_C`,
- `MM_base_C`,
- probs por bucket si existen.

3. Sondeo (preferente observado; si no, model sounding):
- `T925`, `T850`, `T700`,
- `RH925`, `RH850`, `RH700`,
- `wind925`, `wind850`,
- `lapse_1000_850`, `lapse_850_700`,
- indicador inversión baja (`inversion_flag`),
- altura mezcla (`mixing_height_proxy`) si existe.

4. Metadatos:
- `run_utc`,
- `lead_time_h`,
- `station_distance_km`,
- `data_quality_flag`.

---

## 3) Features a usar (compactas)

No usar perfil bruto completo en v0.  
Usar vector compacto:

- `temp_layer_mean = mean(T925, T850)`
- `temp_gradient_low = T925 - T850`
- `temp_gradient_mid = T850 - T700`
- `rh_low_mean = mean(RH925, RH850)`
- `wind_low_mean = mean(wind925, wind850)`
- `inversion_flag`
- `mixing_height_proxy`
- `lead_time_h`
- `month_sin/cos` (estacionalidad)
- `hour_local_sin/cos` (ciclo diurno)

---

## 4) Modelo de corrección

Modelo simple y robusto (v0):

- entrenar `delta_C = Tmax_observada_C - MMA_base_C`
- predictor: features de sección 3
- salida final: `MMA_sondeo_C = MMA_base_C + delta_hat_C`

Recomendado v0:
- `LightGBM` o `XGBoost` con regularización fuerte
- fallback lineal robusto si pocos datos.

---

## 5) Backtest (sin leakage)

Regla obligatoria:
- split temporal rolling (walk-forward), nunca shuffle aleatorio.

Esquema:
1. entrenar en histórico hasta `t-1`
2. validar en ventana siguiente
3. avanzar y repetir

Hacerlo separado por ciudad/horizonte.

---

## 6) KPIs de evaluación

KPIs de temperatura:
- `MAE_C`
- `RMSE_C`
- `Bias_C`

KPIs de mercado (si hay probs buckets):
- `Brier score`
- `Log loss`
- `ECE` (calibración)

KPIs operativos:
- `% días en los que cambia la recomendación BET/PASS`
- `edge ejecutable medio` tras costes
- `drawdown simulado` (paper)

---

## 7) Criterio GO / NO-GO

Se activa `MMA+sondeo` en una ciudad/horizonte solo si cumple TODO:

1. `MAE_C` mejora **>= 0.25°C** (absoluto) frente a baseline.
2. `RMSE_C` no empeora.
3. `Brier` mejora **>= 5% relativo** (si aplica).
4. No aumenta `drawdown` simulado > 10% relativo.
5. Mejora estable en al menos 2 ventanas rolling consecutivas.
6. Calidad de datos sondeo disponible >= 95% días.

Si falla cualquier punto: `NO-GO` (mantener MMA actual).

---

## 8) Significancia (obligatoria)

Usar bootstrap temporal para delta de MAE y Brier:

- 1000 réplicas mínimo
- reportar `mean_delta` y `IC95`

Solo se considera mejora real si:
- `IC95` de la mejora no cruza 0.

---

## 9) Despliegue seguro

Fase 1:
- `shadow mode` 2 semanas (calcula pero no afecta señal real).

Fase 2:
- activar solo ciudades/horizontes `GO`.
- kill-switch por ciudad:
  - si MAE rolling 7d empeora > 0.3°C vs baseline, rollback automático.

Fase 3:
- recalibración semanal.

---

## 10) Registro y trazabilidad

Guardar por predicción:
- `mma_base_C`
- `delta_hat_C`
- `mma_sondeo_C`
- `feature_version`
- `model_version`
- `data_quality_flag`

Dashboard:
- badge `MMA+Sondeo ON/OFF`
- “motivo de activación” y fecha de última recalibración.

---

## 11) Riesgos conocidos

1. Si el sondeo es de modelo y no observado, puede duplicar sesgo del mismo modelo.
2. Estaciones lejanas degradan señal local.
3. Datos incompletos pueden introducir sesgo de selección.

Mitigación:
- quality gates estrictos + fallback automático a MMA base.

---

## 12) Entregables mínimos para cerrar v0

1. Backtest report por ciudad/horizonte (MAE/RMSE/Brier/IC95).
2. Tabla GO/NO-GO.
3. Config de activación por ciudad/horizonte.
4. Plan de monitorización post-activación.

