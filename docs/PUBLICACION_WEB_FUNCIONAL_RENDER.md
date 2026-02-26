# polyMeteo Web Funcional en Render (backend + frontend)

Guía operativa para publicar la **web funcional** de `polyMeteo` en Render sin tocar la landing pública estática actual.

## Objetivo

Mantener **dos entornos separados**:

- **Landing pública estática** (ya publicada): `https://polymeteo.caplatemp.xyz`
- **Web funcional** (backend + frontend servidos por Node): recomendado `https://app.polymeteo.caplatemp.xyz/app`

Esto permite evolucionar la app funcional sin romper la landing pública ni su SEO/onboarding.

## Qué se publica en la web funcional

Servicio Node (`backend/web_api`) que sirve:

- API (`/api/v1/*`)
- frontend funcional (`/app`)
- SSE (`CopyTrade`)
- Web Push (Service Worker + backend)

## Requisitos importantes (críticos)

1. **Servicio always-on (sin sleep)**
- CopyTrade server-side y Web Push requieren backend vivo.
- Evitar planes que “duerman” la instancia.

2. **Una sola instancia**
- El monitor CopyTrade Web Push server-side no tiene aún coordinación distribuida.
- Si escalas a varias instancias, puedes duplicar alertas.
- Mantener **1 instancia** por ahora.

3. **Disco persistente**
- Las suscripciones Web Push y auditoría se guardan en JSON.
- Sin disco persistente, se pierden tras deploy/restart.

4. **HTTPS**
- Render lo proporciona automáticamente.
- Necesario para Service Worker + PushManager (Web Push real).

## Archivo de referencia

Se incluye una plantilla de servicio en:

- `/Users/emiair/Documents/CodexAPP/meteoTrader/render.functional.yaml`

No sustituye al `render.yaml` actual (landing estática). Es una **plantilla separada** para crear otro servicio.

## Paso 1. Subir el código actual al repo

Antes de desplegar, asegúrate de subir:

- `backend/web_api/`
- `web/`
- `render.functional.yaml`

Comandos típicos:

```bash
cd /Users/emiair/Documents/CodexAPP/meteoTrader
git add backend web render.functional.yaml docs/PUBLICACION_WEB_FUNCIONAL_RENDER.md docs/MANUAL_USO_POLYMETEO.html .gitignore
git commit -m "WEB functional deploy prep (Render)"
git push
```

## Paso 2. Crear un nuevo Web Service en Render (Node)

En Render:

1. `New +` -> `Web Service`
2. Selecciona repo: `TempericoLabs/meteoTrader`
3. Configura:
   - **Name**: `polymeteo-functional` (o similar)
   - **Runtime**: `Node`
   - **Root Directory**: `backend/web_api`
   - **Build Command**: `npm ci`
   - **Start Command**: `npm start`
   - **Health Check Path**: `/healthz`

## Paso 3. Variables de entorno (Render)

Añade estas variables (Environment):

### Obligatorias para funcionamiento base

- `POLYMETEO_WEB_PROVIDER=hybrid-metar`

### Recomendadas (cuenta por defecto en panel web)

- `POLYMARKET_WALLET_ADDRESS=0x...`

### Necesarias para Web Push real

- `POLYMETEO_WEBPUSH_VAPID_PUBLIC_KEY=...`
- `POLYMETEO_WEBPUSH_VAPID_PRIVATE_KEY=...`
- `POLYMETEO_WEBPUSH_VAPID_SUBJECT=mailto:tempericolabs@gmail.com`

### Necesaria para persistencia Web Push (si usas disco)

- `POLYMETEO_WEBPUSH_STATE_FILE=/var/data/copytrade_webpush_state.json`

### Recomendadas para persistencia de calibración MMA premium (si usas disco)

- `POLYMETEO_MMA_PREMIUM_STATE_FILE=/var/data/web_mma_premium_state.json`

## Paso 4. Añadir Persistent Disk (muy recomendable)

En el servicio Render:

1. `Settings` -> `Disks`
2. `Add Disk`
3. Configura:
   - **Mount Path**: `/var/data`
   - **Size**: `1 GB` (suficiente para empezar)

Esto permite conservar:

- suscripciones Web Push
- configuración CopyTrade por suscripción
- auditoría backend
- caché de calibración MMA premium por ciudad/horizonte

## Paso 5. Primer deploy y pruebas técnicas

Cuando Render termine el deploy, prueba la URL temporal `*.onrender.com`:

- `/healthz`
- `/api/v1/openapi-lite`
- `/app`
- `/api/v1/webpush/config`

Checks esperados:

- `/healthz` -> `ok: true`
- `/app` carga UI funcional
- `/api/v1/webpush/config`:
  - `webPushServerAvailable: true` (si VAPID está bien)

## Paso 6. Subdominio funcional (separado de la landing)

Recomendado:

- **Landing** (actual): `polymeteo.caplatemp.xyz`
- **Funcional**: `app.polymeteo.caplatemp.xyz`

En Render (servicio funcional):

1. `Settings` -> `Custom Domains`
2. `Add Custom Domain`
3. Añade `app.polymeteo.caplatemp.xyz`

Render mostrará un CNAME target tipo:

- `polymeteo-functional.onrender.com`

## Paso 7. DNS en DonDominio

Crear registro:

- **Tipo**: `CNAME`
- **Host**: `app.polymeteo`
- **Destino**: `...onrender.com` (el que indique Render)

Después:

- `Verify` en Render
- esperar emisión SSL automática

## Paso 8. Prueba funcional (producción)

Una vez activo:

- `https://app.polymeteo.caplatemp.xyz/app`

Validaciones clave:

1. CIUDADES/DETALLE cargan
2. Polymarket real (lectura) aparece
3. Intel usuario funciona
4. CopyTrade SSE funciona
5. Web Push:
   - `Permiso`
   - `Activar / sync push`
   - `Test push`
6. Reiniciar servicio Render y comprobar persistencia:
   - sigue habiendo suscripciones
   - `startup_restore` en auditoría

## Riesgos / limitaciones actuales (conocidas)

1. **1 sola instancia** (importante)
- Multi-instancia puede duplicar monitores/alertas.

2. **Dependencia de APIs externas**
- Polymarket (Gamma/Data API)
- Open-Meteo
- aviationweather.gov
- Wunderground (scrape)

3. **Rate limits**
- El backend hace polling (CopyTrade + refresh de datos). Controlar frecuencia.

4. **Scraping Wunderground**
- Puede degradarse por cambios HTML. Hay fallback parcial.

## Siguiente fase sugerida (infra)

Para endurecer producción:

1. Persistencia en DB (Postgres/Redis) en vez de JSON
2. Coordinación de monitores (si escalas a varias instancias)
3. Observabilidad (logs/metricas/alertas)
4. Separación de entornos (`staging` vs `prod`)
