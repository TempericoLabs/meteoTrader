# PolyMeteo Android

App Android (Jetpack Compose) para mercados de temperatura máxima diaria en Polymarket.

## Incluye en esta versión

- Pantalla principal responsive con grid fijo `7x2` para 14 ciudades.
- Cada tarjeta muestra:
  - Ciudad
  - Hora local
  - Temperatura METAR actual
  - Delta vs METAR anterior del mismo día
  - Temperatura de estación de control (scraping Wunderground)
  - `PolyTEMP` (media de máximas diarias de las fuentes que sí respondieron)
  - Señal trader (`OVER` / `UNDER` / `RANGE`) + edge estimado
- Pantalla detalle por ciudad con:
  - Bloque completo METAR
  - Lista de fuentes de forecast con `Now`, `Max`, estado y error
  - Errores acumulados por proveedor
  - Trader Mode: mercados Polymarket detectados, edge por mercado, recomendación `YES/NO`, semáforo y top edge de ciudad
- Banner `Top Edges` en pantalla principal con las mejores oportunidades globales detectadas.
- Refresco por botón y pull-to-refresh.
- Pantalla `Backtesting` con:
  - PnL acumulado, ROI, hit-rate, Brier score y log-loss.
  - Ranking por ciudad y por estrategia (`direction + signal`).
  - Historial de liquidaciones recientes.
  - Persistencia local de snapshots para evaluar edge real en el tiempo.
  - Importador histórico inicial (últimos 7 días por ciudad) usando:
    - forecast histórico Open-Meteo `previous-runs` como señal modelo,
    - precio de entrada histórico vía CLOB `prices-history`,
    - liquidación contra máxima diaria real de Wunderground.

## Fuentes conectadas

- METAR: `aviationweather.gov` (JSON, últimas 24h).
- Control station: scraping Wunderground (`history/daily` + `dashboard/pws` fallback).
- Forecasts para PolyTEMP:
  - Windy (ECMWF/GFS/ICON)
  - Open-Meteo (ECMWF IFS + AIFS)
  - OpenWeather
  - NOAA Weather.gov (solo ciudades de EE.UU.)
- Trader markets:
  - Polymarket Gamma API (`/markets`) con filtro dinámico por ciudad de mercados de máxima diaria.
  - Resolución principal por `slug` de evento (`/events?slug=highest-temperature-in-{city}-on-{month}-{day}-{year}`) para hoy, mañana y pasado mañana.
  - Parser de preguntas de tipo `or higher`, `or below`, `exact`, `between X-Y`.

## Configuración

Las claves se leen desde `local.properties`.

Ejemplo en `local.properties.example`.

## Compilar

```bash
./gradlew :app:assembleDebug
```

APK generado en:

- `app/build/outputs/apk/debug/app-debug.apk`

## Nota técnica

Wunderground y algunos proveedores pueden cambiar HTML/contratos; el app mantiene fallback y deja trazabilidad del error por fuente para que PolyTEMP use solo datos válidos.

El backtesting liquida mercados históricos usando la máxima diaria observada en Wunderground (`/history/daily/.../date/YYYY-MM-DD`), con reintentos y deduplicación por mercado/bucket temporal para no degradar rendimiento.
