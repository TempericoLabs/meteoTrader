# CHECKPOINT MVP v0.3

Fecha (UTC): 2026-02-21 08:01:49 UTC

## Referencia Git
- Rama: `main`
- Commit: `1b015c52f877a3652a16b0b1a4410d3e7874f28b`
- Mensaje: `MVP v0.3`

## Cambios clave consolidados
- Renombrado completo de interfaz:
  - `PolyTemp` -> `Media Modelos (MM)`
  - `PolyTemp PREMIUM` -> `Media Modelos Ajustada (MMA)`
- Pantalla DETALLE:
  - `MMA` alineada a la izquierda, debajo de `MM`.
  - Textos y ayudas actualizados a MM/MMA.
- Mensajería operativa:
  - Warnings y errores visibles actualizados a MM/MMA.
- Documentación:
  - `README.md` y `docs/MANUAL_USO_POLYMETEO.html` sincronizados con MM/MMA.

## Estado funcional en este checkpoint
- Compilación: `./gradlew :app:compileDebugKotlin` OK
- Tests unitarios: `./gradlew :app:testDebugUnitTest` OK
- Working tree: limpio tras commit.

## PENDIENTES para retomar
- Validación operativa en vivo por ciudad (checklist completo en `PENDIENTES.md`).
- Integración cuenta Polymarket en modo lectura (cartera/posiciones/actividad).
- Preparar futura fase operativa (CLOB) cuando haya credenciales.

## Nota de reanudación
Al volver tras reinicio, continuar desde este commit y revisar primero `PENDIENTES.md`.
