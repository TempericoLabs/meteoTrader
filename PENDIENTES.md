# PENDIENTES

## Checklist operativo de validación en vivo por ciudad (Polymarket)
- Verificar que para la ciudad y fecha (hoy/mañana/pasado) existe evento por slug directo y por alias de ciudad.
- Confirmar que el número de mercados cargados en app coincide con los buckets abiertos en Polymarket web.
- Validar que los mercados cerrados/resueltos no aparecen en Trader Mode.
- Validar que los mercados todavía abiertos sí aparecen aunque existan otros buckets ya resueltos.
- Confirmar parseo correcto de rango/umbral y unidad (C/F) para cada bucket.
- Confirmar parseo correcto de fecha objetivo del mercado y asignación al tab correcto (hoy/mañana/pasado).
- Confirmar filtrado por máxima observada truncada (regla Polymarket) sin eliminar mercados todavía posibles.
- Confirmar que para mañana/pasado la app muestra "SIN METAR" y usa solo pronósticos del horizonte seleccionado.
- Verificar fallback de búsqueda (search query) cuando el slug directo falla.
- Registrar incidencias por ciudad y hora (captura + payload + decisión esperada).

## Integración de cuenta Polymarket (próximos días)
- Fase 1 (solo lectura): pantalla de Cuenta con cartera, valor total, posiciones y actividad reciente.
- Requiere: dirección de usuario (`user address`) y, si aplica, `proxy/funder wallet`.
- Fase 2 (operativa): integración CLOB para crear/cancelar órdenes desde la app.
- Requiere: credenciales CLOB (`api key`, `secret`, `passphrase`) y flujo de firma wallet seguro.
- Seguridad: nunca introducir ni almacenar seed phrase o clave privada en texto plano.
