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
- Pantalla detalle por ciudad con:
  - Bloque completo METAR
  - Lista de fuentes de forecast con `Now`, `Max`, estado y error
  - Errores acumulados por proveedor
- Refresco por botón y pull-to-refresh.

## Fuentes conectadas

- METAR: `aviationweather.gov` (JSON, últimas 24h).
- Control station: scraping Wunderground (`history/daily` + `dashboard/pws` fallback).
- Forecasts para PolyTEMP:
  - Windy (ECMWF/GFS/ICON)
  - Open-Meteo (ECMWF IFS + AIFS)
  - OpenWeather
  - Weatherstack
  - NOAA Weather.gov (solo ciudades de EE.UU.)
  - ECMWF Web API marcado como `SKIPPED` (placeholder para integración batch).

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
