# Auditoría Astra de funcionalidad y batería

Fecha: 2026-09-08  
Rama: `feat/path-following-ghost`  
HEAD auditado: `917e145cbc87af1b3ae560e84b3bb7799b102c58`  
Merge-base con `main`: `78881c90e36f357f2dfac2dc9060d394d946248e`

## Alcance y límites

Revisión estática, adversarial y de solo lectura del funcionamiento del fantasma, sus pérdidas y
recuperaciones, pausas, lifecycle de streams, persistencia que alimenta el modelo y consumo sostenido.
Se inspeccionaron la implementación, callers, propietarios de estado, tests, documentación y el
`sources.jar` local de `karoo-ext 1.1.9`.

La auditoría no ejecutó Gradle, tests, builds, profiler ni pruebas físicas. Por tanto, los mecanismos
demostrables en fuente se distinguen de las condiciones cuya ocurrencia real depende del firmware,
del orden de callbacks o del hardware K2/K3. No se modificó código de producto.

## Veredicto ejecutivo

No se identificó ningún hallazgo Critical. Quedan siete problemas High que impiden considerar
completamente resueltas la coherencia del fantasma y su recuperación. La separación reciente entre
el gap temporal y la proyección de ruta evita los antiguos teletransportes causados directamente por
`routeDistance - DISTANCE_TO_DESTINATION`, pero aún hay saltos posibles por rollback odométrico,
checkpoint, reloj y lifecycle.

Las mejoras de `287e069`, `5f9de64` y `917e145` están presentes. No se deben revertir ni reabrir como
pendientes la eliminación de `destJob`, el precompute trigonométrico que alcanza `PointGrid`, el modo
lento del mapa sin emitter ni el gzip de los logs.

## Hallazgos High

### H1. Un rollback del odómetro conserva breadcrumbs futuros e invierte la distancia

**Estado:** defecto confirmado por la aritmética del código; no ejecutado.

**Evidencia:**

- `app/src/main/kotlin/com/enderthor/kghost/engine/GhostIntegrator.kt:82`
- `app/src/main/kotlin/com/enderthor/kghost/engine/GhostIntegrator.kt:103`
- `app/src/main/kotlin/com/enderthor/kghost/extension/KGhostExtension.kt:2216`
- `app/src/main/kotlin/com/enderthor/kghost/extension/KGhostExtension.kt:2433`

La rama `dd < 0` sólo cambia `lastRiderDist`; no recorta ni reubica breadcrumbs cuya distancia queda
por delante del odómetro corregido. `place()` sigue interpolándolos en el marco anterior.

Secuencia reducida `(distancia, reloj, pace)`:

1. `(0, 0, null)`: anclaje.
2. `(100, 10, 0.1)`: empate, breadcrumb 100 m/10 s.
3. `(200, 20, null)`: coasting neutral, breadcrumb 200 m/20 s.
4. Recovery `(150, 20, 0.2)`: tiempo `0 s`, distancia `-50 m`.
5. `(160, 21, 0.2)`: tiempo `+1 s` AHEAD, distancia `-20 m` BEHIND.

La extensión usa la distancia del integrador cuando el tiempo es favorable, mientras el mapa coloca
el fantasma por tiempo sobre la curva. El ciclista puede ver tiempo, distancia y mapa contradictorios.

**Hueco de test:** `GhostIntegratorTest` comprueba que `ghostTime` sobrevive al rollback, pero no el
signo, la distancia ni la coherencia posterior.

**Corrección recomendada:** definir una única transformación coherente de los breadcrumbs al cambiar
el marco odométrico. Un clamp a cero sólo escondería el síntoma.

**Tests requeridos:** rollback antes/después de uno o varios breadcrumbs, neutral/coasting/LIVE,
ventaja y déficit, invariantes de signo y ausencia de puntos futuros tras la corrección.

### H2. Error/Complete elimina el consumer del SDK pero deja el Flow suspendido para siempre

**Estado:** confirmado contra la aplicación y `karoo-ext 1.1.9`.

**Evidencia:**

- `app/src/main/kotlin/com/enderthor/kghost/extension/Extensions.kt:46`
- `app/src/main/kotlin/com/enderthor/kghost/extension/Extensions.kt:58`
- `app/src/main/kotlin/com/enderthor/kghost/extension/KGhostExtension.kt:1850`
- `app/src/main/kotlin/com/enderthor/kghost/extension/KGhostExtension.kt:2142`

El SDK elimina el listener al recibir `onError` o `onComplete`. Los wrappers sólo conectan `onEvent`:
no cierran el `callbackFlow`, no propagan el terminal y no activan resuscripción. `awaitClose` continúa
esperando aunque ya no exista un consumer en el host.

Impactos concretos:

- LOCATION terminal: no vuelven los fixes hasta crear otra suscripción.
- RideState terminal: puede perderse Idle y con él guardado/reset de salida.
- DISTANCE terminal: `combine` puede reutilizar indefinidamente el último valor mientras otros streams
  emiten.
- Un estado no `Streaming`, `Searching` o un valor inválido hace return antes de invalidar la salida;
  el gap anterior puede seguir apareciendo como activo.
- SPEED sin primer valor impide que `combine` arranque y nunca se reconoce el primer movimiento.

**Corrección recomendada:** propagar Error/Complete al Flow, resuscribir con backoff acotado y modelar
explícitamente ausencia inicial, stale, Searching y terminal. El watchdog no puede depender sólo del
stream que ya murió.

**Tests requeridos:** Event→Error/Complete en la frontera real, recuperación sin collectors duplicados,
Idle posterior y salida invalidada al perder DISTANCE/ELAPSED.

### H3. Un fallo Binder al emitir el mapa puede escapar de una coroutine raíz

**Estado:** camino de excepción confirmado; frecuencia física no medida.

**Evidencia:**

- `app/src/main/kotlin/com/enderthor/kghost/extension/KGhostExtension.kt:399`
- `app/src/main/kotlin/com/enderthor/kghost/extension/KGhostExtension.kt:1231`
- `app/src/main/kotlin/com/enderthor/kghost/extension/KGhostExtension.kt:1310`
- `karoo-ext 1.1.9 internal/Emitter.kt`: `onNext` llama directamente a `handler.onNext`.

`mapLoopJob` no captura excepciones del emitter. Si el host muere entre frames,
`DeadObjectException`/`RemoteException` puede escapar. `SupervisorJob` evita cancelar hermanos por
propagación estructurada, pero no convierte una excepción no gestionada en una publicación fallida.

**Impacto:** desaparición del mapa; según el handler de excepciones, posible caída del proceso y pérdida
del estado/grabación en memoria.

**Corrección recomendada:** aislar la publicación Binder, retirar sólo el emitter que falló si aún es
el actual y permitir que un nuevo `startMap` recupere el overlay. No intentar reportar el fallo mediante
otro IPC sobre el binder muerto.

**Tests requeridos:** emitter que lanza en Show y Hide; la excepción no sale del propietario de la
carrera; un emitter nuevo vuelve a mostrar sin reiniciar el integrador.

### H4. Un checkpoint puede importar minutos de otra salida y reaparecer después de Idle

**Estado:** defecto confirmado; el test `LOCK 6` no acota el lead general.

**Evidencia:**

- `app/src/main/kotlin/com/enderthor/kghost/extension/KGhostExtension.kt:624`
- `app/src/main/kotlin/com/enderthor/kghost/extension/KGhostExtension.kt:970`
- `app/src/main/kotlin/com/enderthor/kghost/extension/KGhostExtension.kt:2238`
- `app/src/test/kotlin/com/enderthor/kghost/engine/AdvNumPipelineTest.kt:295`

La continuidad acepta un checkpoint cuando coincide el epoch **o** la distancia actual está a menos de
300 m de `lastRiderDist`. Tras muerte de proceso, una salida abortada cerca del inicio y otra nueva en
la misma ruta pueden cumplir ruta, pick, antigüedad y margen.

Ejemplo compatible con los límites de producción: historial a `2 s/m`, ciclista a `10 m/s`, 200 m
después del anclaje. El lead es aproximadamente `400 - 20 = 380 s`. Una salida nueva cerca de cero lo
restaura como unos seis minutos de ventaja. `LOCK 6` sólo prueba un ciclista exactamente al ritmo
histórico, por lo que su lead es cero; no demuestra una cota general.

Hay además una carrera:

1. Un writer captura snapshot antes del mutex.
2. Idle une el tick, pero no necesariamente los writers IO ya lanzados.
3. Idle borra el checkpoint.
4. El writer pendiente adquiere el mutex y recrea el checkpoint antiguo.

**Corrección recomendada:** separar identidad de salida y proximidad odométrica. El `RideState` del SDK
inspeccionado no ofrece un activity ID: no inventarlo. Si la identidad real no puede verificarse, hacer
explícita la política del restore ambiguo. Serializar delete/invalidate con los writers y usar generación.
No aplicar un clamp arbitrario al lead legítimo.

**Tests requeridos:** abandono corto con lead ±380 s, nueva salida misma ruta/pick, resume auténtico,
cambio de ruta y barrera determinista writer→Idle→delete→writer.

### H5. LOCATION repetida conserva una posición congelada como fresca

**Estado:** vulnerabilidad lógica confirmada; la combinación exacta del host requiere telemetría K2/K3.

**Evidencia:**

- `app/src/main/kotlin/com/enderthor/kghost/extension/KGhostExtension.kt:1751`
- `app/src/main/kotlin/com/enderthor/kghost/extension/KGhostExtension.kt:1919`
- `app/src/main/kotlin/com/enderthor/kghost/extension/KGhostExtension.kt:1990`
- `app/src/main/kotlin/com/enderthor/kghost/extension/KGhostExtension.kt:2305`

Cada LOCATION trusted vuelve a fechar `lastFix.ms`, aunque lat/lng no cambien. El contador de coordenadas
repetidas es sólo diagnóstico. Si GPS repite coordenadas con accuracy aceptable mientras SPEED/DISTANCE
de rueda continúan, coast permanece LIVE y `fixAgeOk` true. El mismo pace del punto congelado se aplica
a todos los metros posteriores.

El grabador usa además `lastFix` sin comprobar antigüedad. Con LOCATION silenciosa y odómetro vivo puede
grabar muchos metros en una sola coordenada y contaminar futuros modelos locales.

**Corrección recomendada:** separar edad de recepción y evidencia de actualización espacial. El
`DataPoint` 1.1.9 no contiene timestamp del fix, por lo que no debe proponerse un campo inexistente. La
inmovilidad sólo puede señalar pérdida cuando hay movimiento corroborado, para no castigar una parada.
Aplicar el mismo contrato al grabador.

**Tests requeridos:** LOCATION idéntica con rueda avanzando, LOCATION silenciosa, parada real,
cuantización, accuracy inválida y recuperación; comprobar tier, registro, marker y estado estimated.

### H6. Paused no impide cobrar metros ni grabar muestras

**Estado:** defecto de gating confirmado; emisión de DISTANCE durante pausa requiere dispositivo.

**Evidencia:**

- `app/src/main/kotlin/com/enderthor/kghost/extension/KGhostExtension.kt:962`
- `app/src/main/kotlin/com/enderthor/kghost/extension/KGhostExtension.kt:1856`
- `app/src/main/kotlin/com/enderthor/kghost/extension/KGhostExtension.kt:1990`
- `app/src/main/kotlin/com/enderthor/kghost/extension/KGhostExtension.kt:2216`

Paused sólo cambia `ridePaused` y solicita checkpoint. No hay gate en el tick. Congelar ELAPSED_TIME no
detiene `combine`: DISTANCE o SPEED todavía pueden provocar emisiones.

Si durante la pausa el odómetro avanza 100 m y el historial vale `0.2 s/m`, el integrador puede sumar
20 s de ventaja con reloj congelado. El recorder también consume esos metros. Con todos los inputs
congelados el gap sí se mantiene: no debe afirmarse que toda pausa reproduce el fallo.

**Hueco de test:** el caso de pausa de `Adv2CoastPipelineTest` usa Ghost Pace con distancia y reloj
congelados; no cubre route mode con raw DISTANCE creciente.

**Corrección recomendada:** hacer `RideState` una entrada efectiva de la máquina y definir el baseline
de reanudación. Sólo dejar de llamar al integrador puede cobrar el salto completo al reanudar.

**Tests requeridos:** pausa manual/autopause, rueda activa, SPEED/LOCATION activos con ELAPSED congelado
y resume con salto de odómetro conservando correctamente lead e integrador.

### H7. Un fallo al escribir un track se registra como importación correcta

**Estado:** confirmado por propagación del resultado IO.

**Evidencia:**

- `app/src/main/kotlin/com/enderthor/kghost/geo/AtomicFile.kt:22`
- `app/src/main/kotlin/com/enderthor/kghost/geo/TrackStore.kt:175`
- `app/src/main/kotlin/com/enderthor/kghost/geo/TrackStore.kt:330`
- `app/src/main/kotlin/com/enderthor/kghost/geo/TrackStore.kt:459`
- `app/src/main/kotlin/com/enderthor/kghost/import/HistoryImporter.kt:168`
- `app/src/main/kotlin/com/enderthor/kghost/import/HistoryImporter.kt:320`

`atomicWriteText()` devuelve false al fallar. `save`, `addAll` y `BulkSink.addAll` ignoran el resultado,
incorporan ID/sourceKey y aumentan `added`. El importer marca luego `processed.json` y avanza `lastScan`.

Con un error de IO, espacio o permisos, queda un track declarado como importado que no existe. El ledger
y el dedup pueden impedir reintentos posteriores. El mismo patrón permite que `add()` devuelva true al
grabar aunque no haya archivo.

**Corrección recomendada:** propagar un resultado por track (`stored`, `duplicate`, `enriched`, `failed`)
y no avanzar ledger/dedup para escrituras fallidas. Diseñar recuperación verificable de archivos
ausentes/corruptos sin imponer fsync por track antes de medir el coste.

**Tests requeridos:** fallos inyectados en temp/rename/destino, bookkeeping tras fallo, reapertura y
reimportación, pérdida de track grabado y corte de proceso entre datos y metadata.

## Hallazgos Medium

### M1. ELAPSED_TIME hacia atrás amplifica el siguiente intervalo

`CoastingEstimator` convierte el delta negativo en cero, pero conserva el timestamp atrasado. Después
de `60 → -440 → 61`, el siguiente tick puede integrar 501 s. Route mode también resta el reloj reducido
directamente de `ghostTime`.

Evidencia: `CoastingEstimator.kt:195`, `KGhostExtension.kt:1878`, `KGhostExtension.kt:2216`,
`GhostIntegrator.kt:75`. El test actual termina en el tick atrasado y no prueba el retorno.

### M2. El bootstrap del loop puede elegir el final y ocultar la primera vuelta

El bootstrap global no usa la comprobación de ambigüedad del recovery; sin heading acepta el coin-flip.
El fin se corrobora con el odómetro total de la salida, no con distancia desde la carga de la ruta.

Secuencia: tras 10 km se carga un loop de 2 km; el primer fix cae cerca del segmento final, marca
`crossedFinish`; al corregirse al inicio activa `lap2Started` y oculta el mapa durante la primera vuelta.

Evidencia: `KGhostExtension.kt:2381`, `KGhostExtension.kt:2476`.

### M3. La cancelación tardía de un emitter viejo borra el nuevo

Secuencia: `startMap(A)`, `startMap(B)`, cancelación tardía de A. El callback de A pone
`mapEmitter = null` sin comprobar identidad. B deja de recibir hasta otro `startMap`.

Evidencia: `KGhostExtension.kt:1208`. Corregir sólo si `mapEmitter === emitter` y mantener el lock.

### M4. El guard de match no hace atómica la publicación

A puede pasar `ensureActive` y el guard; Main reclama B/cancela A; B publica; A reanuda la instrucción
siguiente y publica el snapshot antiguo. Los helpers testados no cierran la ventana guard→write.

Evidencia: `KGhostExtension.kt:1431`, `KGhostExtension.kt:1514`, `KGhostExtension.kt:1600`,
`KGhostExtension.kt:2807`.

### M5. Persisten modelos stale entre salidas y hay colisiones de route key

- Idle no limpia `routeMode`/`lastMatchedPolyline`. Una salida siguiente con la misma polyline deduplica
  y conserva un `PacePatch` que no incluye la salida recién guardada.
- `routeKeyOf` usa nombre sanitizado y longitud redondeada a 100 m; dos geometrías con igual clave e
  iguales candidatos pueden reutilizar un aggregate de la ruta equivocada.

Evidencia: `KGhostExtension.kt:980`, `KGhostExtension.kt:1428`, `KGhostExtension.kt:2646`,
`RouteKey.kt:16`, `RouteAggregate.kt:61`.

### M6. El watermark `lastScan` puede excluir para siempre un fallo anterior

El filtro de mtime se aplica antes del ledger. Si falla A antiguo y se importa B más nuevo, el cutoff
máximo puede excluir A aunque nunca se ledgerara. También afecta una cancelación con workers fuera de
orden.

Evidencia: `HistoryImporter.kt:97`, `HistoryImporter.kt:174`, `HistoryImporter.kt:336`.

### M7. El phase slip todavía pierde veredictos reales

Con DISTANCE a media cadencia, el tick intermedio entra en coast neutral y el siguiente sólo cobra parte
de los metros históricos. El test de caracterización pierde aproximadamente la mitad del veredicto en
30 minutos.

Evidencia: `KGhostExtension.kt:1856`, `KGhostExtension.kt:2305`,
`Adv2CoastPipelineTest.kt:265`. No retirar `coast.quality == LIVE`: reabriría el overshoot ratchet.
Primero medir los callbacks reales; luego distinguir metros medidos de estimados.

## Presupuesto estático de batería y hot paths

Esto no es una medida de autonomía ni de mAh.

| Camino | Carga deducible | Evaluación |
|---|---:|---|
| Inputs | LOCATION, GRADE, DISTANCE, ELAPSED y SPEED; `combine` trabaja antes de `sample` | Cadencia host desconocida |
| Tick | hasta ~1 Hz / 3.600 ticks por hora | Razonable, pero sigue activo en pausa/inactivo |
| Mapa visible K3/K2 | ~5/~3 iteraciones por segundo; 18.000/10.800 por hora | El IPC depende de movimiento/heartbeat |
| Mapa sin emitter, VP o pausa | 1 wakeup por segundo | Mejorado frente al antiguo 5 Hz |
| Checkpoint | hasta ~720 snapshots/escrituras por hora | IO aunque no cambie el valor útil |
| Campo estático | heartbeat cada 3 s: **1.200 IPC/h por campo** | Deliberado, medir antes de retirar |
| File log activado | poll/flush cada segundo | ~3.600 wakeups/h, incluso fuera de salida |
| File log desactivado | poll cada 60 s | ~60 wakeups/h |
| Upload | 5 min normal; 1 min offline si hay tick activo | Pausa no lo detiene |

### B1 — Medium: recovery global O(n) reiterado

Después del umbral, cada tick en movimiento puede escanear toda la polyline, crear una colección boxed
de candidatos y ordenarla (`KGhostExtension.kt:2408`, `Polyline.kt:353`). El precompute ahorra
trigonometría, no el recorrido/boxing/sort. Perfilar con rutas largas antes de introducir un índice;
si domina, reintentar sólo con nueva evidencia espacial sin degradar la latencia de rejoin.

### B2 — Medium: 250 tracks no es un límite de memoria

La carga mantiene tracks completos, anchors y mapas. 250 tracks de 100 km decimados a 20 m son del orden
de 1,25 millones de puntos antes de overhead. Es una ilustración, no heap medido. Medir K2 con bibliotecas
y rutas largas; si hace falta, presupuestar por puntos/bytes y conservar cancelación.

### B3 — Medium: logging/red sigue activo en pausa

`FileLogTree` despierta cada segundo cuando está habilitado. Offline se clasifica `RETRY` y vuelve al
minuto mientras `tickJob` siga activo; Paused no lo detiene. Decidir primero si subir durante una pausa
larga es requisito y medir Companion/BLE/Wi-Fi.

### B4 — Low: coste de diagnóstico descartado

- Varios `Timber.d` construyen `.format()`/interpolaciones aunque no haya sink de release.
- `requestFlush()` no interrumpe el `delay`; los 400 ms previos al upload no garantizan tail completo.
- `writerFile` se conserva aunque falle abrir el writer y no se reintenta hasta cambiar de archivo.
- Límites de log por líneas/archivos, no por bytes de una salida.

Son riesgos de eficiencia/diagnóstico, no fallos High de carrera.

## Matriz de riesgos previamente atacados

| Riesgo | Estado en HEAD |
|---|---|
| `routeDistance - DISTANCE_TO_DESTINATION` impulsa el número | **Sound:** camino eliminado |
| Fill fijo fabrica ventaja en terreno desconocido | **Sound:** neutral fill vigente |
| Pace histórico cobra coast inventado | **Sound para la combinación protegida:** conservar `verdictAllowed` |
| Parada normal fabrica metros | **Sound:** no reabrir |
| Parada durante dropout borra metros estimados | **Sound:** conservación vigente |
| NaN/SPEED extrema/coast ilimitado | **Sound en guards:** cap 1.800 s y 30 m/s; máximo teórico 54 km |
| Phase slip | **Open:** M7; frecuencia física sin medir |
| LOCATION repetida | **Open condicionado al host:** H5 |
| Restore de otra salida corta | **Open:** H4 |
| Rollback conserva lead | **Parcial:** tiempo sound, breadcrumbs/distancia fallan en H1 |
| Repick conserva lead | **Sound:** `withPick`; no reconstruir integrador |
| Ownership de match | **Parcial:** helpers mejorados, ventana guard→write abierta |
| Enrichment y archive | **Sound en flujo revisado** |
| `sourcekeys.json` corrupto | **Sound:** recomposición live+archive |
| Archivo perdido con bookkeeping válido | **Open:** H7 |
| Truncation FIT dependía de fixture privado | **Sound:** fixture sintético obligatorio |
| Precompute trig sobre camino equivocado | **Sound:** alcanza `PointGrid` |
| Mapa 5 Hz sin emitter | **Sound:** corregido |
| Consumo/cadencias reales | **Unverified:** exige K2/K3 |

## Áreas que deben preservarse

- `GhostCurve` y `PolylinePath.sampleAt` usan búsqueda binaria.
- `RouteMode` publica path, modelos y curva en un snapshot coherente.
- Cambiar pick conserva lead; cambiar ruta conserva la carrera y reinicia sólo estado espacial.
- GradePace mantiene el promedio aprobado ponderado por metros; no sustituirlo por EMA.
- El builder reinicia ventanas en dropout/spike y filtra antes de contaminar bins.
- FIT es single-pass con gate de actividad; GPX bloquea entidades externas.
- Import usa workers/canales/chunks acotados y commit additions-only.
- Enrichment conserva identidad/geometría/tiempo y sólo rellena altitude admisible.
- DataStore tiene corruption handler y mirror serializado dentro del orden de `edit`.
- Los renderers gráficos son por coroutine y con buffers acotados.
- No se encontró wakelock propio ni alarma periódica adicional.

## Límites de los tests existentes

- Los rigs copian reloj/orquestación y reciben freshness/pace ya resueltos.
- Los helpers compartidos mejoran sensibilidad a revert, pero no prueban las carreras de alrededor.
- `LOCK 6` usa lead cero y sobreafirma el límite.
- El test de reloj hacia atrás no ejecuta el tick siguiente.
- El test de rollback no verifica distancia/signo.
- Algunos tests de caracterización fijan comportamientos incorrectos conocidos.
- Los replays que usan `Assume` son opcionales y no protegen CI sin fixture.
- Los counts/builds históricos no se reejecutaron en esta auditoría.

## Plan de relevo para Claude Code y Codex Sol

1. Capturar H1 en un test puro mínimo y reparar breadcrumbs sin perder lead ni neutral fill.
2. Resolver identidad del checkpoint y la carrera writer→Idle con pruebas de barrera.
3. Reparar terminales y salud de inputs conservando ownership único y el gate LIVE.
4. Hacer Paused/Resume efectivos, incluyendo baseline tras metros recibidos en pausa.
5. Aislar Binder/ownership de emitters y después cerrar la publicación atómica de matches.
6. Propagar fallos de TrackStore hasta ledger/UI y corregir el watermark que oculta reintentos.
7. Probar bootstrap/finish e invalidación de modelos con loops, rutas cargadas tarde y dos salidas.
8. Optimizar batería sólo después de medir recovery global, heap de seed y logging/red en pausa.
9. Ejecutar tests dirigidos con JDK 17, después suite/build/lint y finalmente matriz física K2/K3.

Matriz física mínima: rueda activa con GPS perdido; LOCATION repetida y silenciosa; terminal de stream;
pausa manual/autopause; reconnect y cierre del host; proceso muerto/restaurado; loop, switchback y
out-and-back; shortcut/rejoin; ruta reemplazada; nueva salida sobre la misma ruta; almacenamiento lleno
o escritura fallida.

El relevo debe reutilizar el plan/ledger existente y añadir sólo los hallazgos aceptados. Cada cambio
debe ser pequeño, de causa raíz y acompañado por un test que falle al retirar exactamente esa corrección.
No cambiar thresholds ni quitar shields usando una simulación que omita el pipeline real.

