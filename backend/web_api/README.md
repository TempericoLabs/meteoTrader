# polyMeteo Web API (Fase B1 scaffold)

Backend mínimo para la fase web funcional inicial.

## Objetivo

- Exponer contratos HTTP para `CIUDADES` y `DETALLE`.
- Servir datos mock coherentes con la semántica de `meteoTrader`.
- Servir el frontend web local (`/app`) para pruebas rápidas en el Mac.

## Estado

- `MOCK` / lectura solamente.
- No usa aún scraping real ni lógica de `WeatherRepository`.
- Diseñado para sustituir el origen mock por lógica real sin romper la UI web.

## Endpoints

- `GET /healthz`
- `GET /api/v1/meta`
- `GET /api/v1/cities`
- `GET /api/v1/cities/:cityId`
- `GET /api/v1/copytrade/stream` (SSE foreground)
- `GET /api/v1/webpush/config`
- `GET /api/v1/webpush/subscriptions`
- `GET /api/v1/webpush/audit`
- `POST /api/v1/webpush/subscribe`
- `POST /api/v1/webpush/unsubscribe`
- `POST /api/v1/webpush/test`
- `GET /app` (frontend estático)

## Ejecutar local

```bash
cd backend/web_api
npm install
npm run dev
```

Opcional (forzar proveedor):

```bash
POLYMETEO_WEB_PROVIDER=mock npm run dev
POLYMETEO_WEB_PROVIDER=hybrid-metar npm run dev
```

Abrir:

- `http://localhost:8788/app`

## Siguiente integración (fase siguiente)

1. Mover cálculo real al backend (fuente única de verdad).
2. Sustituir mock por adaptador a lógica actual de Android / servicios.
3. Añadir SSE/WebSocket para copytrade y refresh incremental.

## Estado actual del provider híbrido

- `hybrid-metar`: overlay live de `METAR` (aviationweather.gov) + `Temp estación control` (Wunderground, máxima diaria) + `MM parcial` (Open-Meteo: ECMWF IFS + GFS media simple del día) + lectura real de `Polymarket` en DETALLE (Gamma API, solo lectura) + hora local por ciudad
- Fallback automático a mock si falla la red / API
- `MMA` y la lógica final de recomendación/trazabilidad siguen mock temporalmente (aunque el DETALLE ya muestra un bloque de mercado real de Polymarket)

## Web Push (beta, server-side)

La web local puede registrar suscripciones `Web Push` (Service Worker + PushManager) y el backend puede disparar pushes reales por alertas CopyTrade **si** tiene VAPID configurado.

Variables de entorno (backend):

```bash
export POLYMETEO_WEBPUSH_VAPID_PUBLIC_KEY="..."
export POLYMETEO_WEBPUSH_VAPID_PRIVATE_KEY="..."
export POLYMETEO_WEBPUSH_VAPID_SUBJECT="mailto:tu-email@dominio.com"
export POLYMETEO_WEBPUSH_STATE_FILE="/var/data/copytrade_webpush_state.json" # opcional (recomendado en Render)
```

Notas:

- Sin esas variables (o sin `web-push` instalado), el backend seguirá funcionando, pero `GET /api/v1/webpush/config` devolverá `webPushServerAvailable=false`.
- En `localhost` el navegador permite Push/Notification para pruebas. En producción requiere `HTTPS` (ya previsto en `polymeteo.caplatemp.xyz`).
- El backend persiste suscripciones y auditoría básica en `backend/web_api/data/copytrade_webpush_state.json` por defecto (o en la ruta indicada por `POLYMETEO_WEBPUSH_STATE_FILE`) y reintenta levantar monitores al reiniciar.

## Render (web funcional separada de la landing)

- Mantén la **landing estática** en el servicio actual (`render.yaml`).
- Publica la **web funcional** como **segundo servicio Render (Node)** usando `backend/web_api` como `Root Directory`.
- Usa la plantilla de referencia:
  - `/Users/emiair/Documents/CodexAPP/meteoTrader/render.functional.yaml`
- Guía completa:
  - `/Users/emiair/Documents/CodexAPP/meteoTrader/docs/PUBLICACION_WEB_FUNCIONAL_RENDER.md`

Requisitos operativos (importante):

- **1 sola instancia** (evita duplicar monitores/alertas CopyTrade Web Push)
- **servicio sin sleep**
- **Persistent Disk** si quieres conservar suscripciones/auditoría tras restart/deploy
