# Triaje de la auditoría Astra + plan de corrección

Fecha: 2026-09-08
Rama: `feat/path-following-ghost` @ `917e145`
Fuente auditada: `docs/reviews/2026-09-08-astra-functional-battery-audit.md`
Verificación: tres pases independientes contra el código real (no contra el informe).

## Resultado del triaje

| # | Astra | Veredicto tras verificar | Qué corrigió/añadió la verificación |
|---|---|---|---|
| H3 | High | **CONFIRMADO, y Astra se queda corto** | No hay `CoroutineExceptionHandler` en toda la app y el `transact` de `onNext` NO es `oneway` → no es "posible caída", es caída. Además `publishGhostMarker` tiene 5 call sites, no sólo `mapLoopJob`; y hay dos emits sin guardar que Astra no vio (`GapNumericDataType.kt:108`, `GapGraphicDataType.kt:131`). Ya existe el precedente correcto en `GapStreamDataType.kt:100-109`. |
| H1 | High | **CONFIRMADO en la aritmética; FALSO en el mapa** | Reproducida la secuencia: tiempo `+1 s` ADELANTE con distancia `−20 m` DETRÁS en el mismo campo. Pero `ghostLat/ghostLng` no tiene ningún consumidor en producción: el marcador sale del marco de ruta vía `gapTimeS`, que está limpio. Contradicción de dos vías, no de tres. Sólo afecta a la rama AHEAD. |
| H7 | High | **CONFIRMADO entero** | Cadena completa verificada: `atomicWriteText` → `save`/`add`/`BulkSink.addAll` descartan el Boolean → `ledger.mark` + `lastScan` avanzan igual. El comentario que justifica el descarte (`TrackStore.kt:270-273`) es cierto para corte de corriente y falso para el camino return-false, que es justo el que reporta el Boolean. |
| H4 | High | **(a) CONFIRMADO pero no es hallazgo nuevo; (b) CONFIRMADO** | (a) el repo YA lo documenta en `AdvNumPipelineTest.kt:295-312`, que incluso **asserta** la adopción. Lo genuinamente nuevo: el clamp de pace a `2.0 s/m` (`TrackSamples.kt:38`) permite hasta ~600 s dentro de los 300 m, así que el comentario optimista de LOCK 6 es el que está mal. (b) confirmado: el snapshot se lee FUERA del mutex y `deleteGhostCheckpoint` no lo toma; los writers son root coroutines que `stopTickAndJoin()` no espera. Pero (b) sólo muerde si la salida limpia era ella misma <300 m. |
| H5 | High | **CONFIRMADO, pero ya es una sospecha documentada** | El propio `KGhostExtension.kt:463-467` describe este defecto exacto. `locRepeatMovingCount` es la sonda que se añadió para decidirlo y **aún no se ha leído de una salida limpia**. Astra exagera el impacto: `PacePatch.kt:75-76` deduplica una muestra por track y celda, así que no contamina el modelo como dice; el daño real es al track grabado. |
| H6 | High | **PARCIAL** | El agujero del integrador es real y la asimetría de Option B (`:2216` sólo frena elapsed-avanza/distancia-no) también. Pero el grabador **no** está afectado (`Track.kt:44-51` decima por distancia). `ridePaused` sólo lo lee el bucle del mapa. Requiere que el Karoo avance DISTANCE con ELAPSED pausado, cosa no demostrada. |
| H2 | High | **CONFIRMADO contra las fuentes del SDK, impacto atenuado** | `KarooSystemService.kt:198-207` llama `removeConsumer` incondicionalmente en terminal; los 7 wrappers de `Extensions.kt` sólo cablean `onEvent`. Pero `onConnected()` cancela y relanza todos los jobs en cada rebind, así que el disparador más probable SÍ se recupera. Queda descubierto el terminal por-stream sin rebind. |
| M1 | Medium | **REFUTADO** | `CoastingEstimator.kt:196` actualiza `prevElapsedS` incondicionalmente. El ejemplo de 501 s no puede ocurrir. |
| M5 | Medium | **Hecho cierto, no defecto** | La colisión de `routeKeyOf` ya está documentada como límite aceptado en `RouteKey.kt:9-12`. No limpiar `routeMode` en Idle es deliberado (la ruta sigue cargada tras la salida). |
| M2 | Medium | **Sobredimensionado** | `crossedFinish` está corroborado por odómetro (`:2476`), así que no puede dispararse al inicio. Requiere el bootstrap sin heading tras haber rodado media ruta. Estrecho y sólo-icono. |
| M3 | Medium | **CONFIRMADO (latente)** | `:1215-1221` anula `mapEmitter` sin comprobar identidad. |
| M6 | Medium | **CONFIRMADO** | `HistoryImporter.kt:97` aplica el cutoff de mtime antes de la partición del ledger. |
| M4, M7, B1-B4 | — | **Aparcados** | M7 y batería: Astra misma dice medir primero. De acuerdo. |

## Revisión adversarial de Codex (READ-ONLY, sin cambios en el árbol)

Codex falsificó tres correcciones del plan y rescató dos downgrades. Evaluado contra fuente y **aceptado**:

1. **H1 — el desplazamiento uniforme es INCORRECTO.** Un rollback no reubica el origen del odómetro: corrige sólo el tramo extrapolado. `bcDist[i] += dd` mueve también las breadcrumbs anteriores al período ciego, que eran válidas, y rompe la relación física `bcDist[i] ↔ bcLat[i]`. Lo correcto es **truncar el sufijo fantasma** (las crumbs cuya distancia queda por delante del odómetro corregido) y dejar intactas las anteriores.
2. **H6 — `ridePaused` a secas tampoco basta.** No hay orden total entre RideState (escrito en Main) y el tick (recogido en Default). El fallo fuerte no es el flag sino el **resume**: Recording puede poner `ridePaused=false` antes del primer DISTANCE posterior, y ese valor puede traer el salto entero de la pausa. Hace falta una transición explícita: los ticks en Paused actualizan sólo el baseline, y el primer tick tras Recording rebaselina incondicionalmente.
3. **H2 — `retryWhen` nunca reintenta un `onComplete`.** `close()` termina el Flow normalmente y `retryWhen` sólo observa excepciones, así que la mitad Complete de H2 seguiría abierta. Complete debe cerrarse con una causa reintentable, o los terminales modelarse explícitamente.
4. **H4(a) — el guard propuesto rompe resumes legítimos y un test verde.** `riderDistNow` sale de un `CoastingEstimator()` nuevo por `startTick()`, valor inicial `0.0`; no sobrevive al proceso. `AdvNumPipelineTest.kt:306-313` asserta hoy que `continuous(180, 40, false)` es verdadero. Que el test se rompa es la prueba de que la adopción espuria y el resume corto legítimo **son el mismo caso** y la distancia no puede separarlos. Se retira H4(a) del plan.
5. **La absolución del grabador en H6 era circular.** "Decima por distancia" sólo protege si DISTANCE está congelada, que es justo lo que H6 discute. El grabador entra al plan.
6. **H5 no puede diferirse entero.** El camino del grabador stale es alcanzable vía H2 sin ninguna hipótesis de hardware: LOCATION termina → `lastFix` se congela para siempre → `:1991` sigue alimentando esa coordenada. El gate de antigüedad del grabador entra; la heurística de coordenadas repetidas sigue esperando la sonda.
7. **B y C no son independientes.** `GhostIntegrator.lastRiderDist ↔ integLastRiderDist ↔ pendingCheckpoint.lastRiderDist` son una sola invariante en tres sitios. Se fusionan.

**M1 queda REINSTAURADO.** Verificado en fuente: `CoastingEstimator.kt:195-196` hace `prevElapsedS = elapsedS` incondicionalmente justo tras el clamp, así que `60 → -440 → 61` produce `dt=0` y después `dt = 61-(-440) = 501`. La asignación incondicional es la causa, no la refutación. Astra tenía razón.

**Premisa mía que era falsa, con conclusión que sobrevive:** afirmé que `ELAPSED_TIME` tiene resolución de un segundo. No es cierto — llega como `Double` en segundos (`rawMs / 1000.0`, `:2763-2769`) y ni el SDK ni la app imponen esa resolución; salía de comentarios y tests. Pero `de == 0 && dd > 0` sigue siendo alcanzable (basta un DISTANCE antes del siguiente ELAPSED), así que rechazar el gate `de <= 0` se mantiene.

## Plan (tras la revisión cruzada)

Cada lote: causa raíz, diff mínimo, un test que falle si se retira exactamente esa corrección.

### Lote A — HECHO (sin commitear)
Ambos revisores lo dan por bueno sin objeciones.
- `publishGhostMarker`: `runCatching` alrededor del bloque de emisión; en fallo anular `mapEmitter`/`lastGhostMarker` bajo el lock para que el próximo `startMap` re-enganche. Sin `onError` sobre el binder muerto (mismo idioma que `GapStreamDataType.kt:100-109`).
- Igual en `GapNumericDataType.kt:108` y `GapGraphicDataType.kt:131`.
- M3: `if (mapEmitter === emitter)` en el `setCancellable`.

### Lote B — PARCIAL: sólo H1 (ver estado de ejecución al final)
- **H1**: truncar las breadcrumbs cuya distancia quede por delante del odómetro corregido. NO desplazar. Las anteriores al período ciego no se tocan.
- **H6**: transición explícita de RideState. Tick en Paused → sólo baseline. Primer tick tras Recording → rebaseline incondicional antes de volver a acumular. Gatear también `recorder.onSample` (`:1991`).
- **H4(b)**: leer `pendingCheckpoint` DENTRO del mutex, `deleteGhostCheckpoint` toma el mismo mutex, contador de generación que descarta una escritura en vuelo.
- **M1**: no adoptar el timestamp de un glitch de elapsed hacia atrás.
- **H5 (mitad)**: gate de antigüedad del fix antes de alimentar al grabador.
- Una única operación de rebaseline que actualice integrador, mirror (`integLastRiderDist`) y snapshot bajo el mismo contrato.
- Test integrado: `restore → Paused → metros → Recording → primer tick → checkpoint`, además de los unitarios.

### Lote C — HECHO (sin commitear)
- **H7**: propagar el Boolean por `save`/`add`/ambos `addAll`; no contar, no indexar clave, no ledgerar una escritura fallida.
- **M6**: aplicar el ledger antes del watermark, o no avanzar `lastScan` por encima de un fichero fallido.

### Lote D — streams (el más arriesgado, aislado y al final)
- **H2**: terminales propagados al Flow con `onComplete` cerrando con una causa **reintentable**, y backoff acotado DENTRO del wrapper, nunca en los call sites. `httpRequest().first()` queda fuera de la política.

### Sin código, a propósito
- **H5 (heurística de coordenadas repetidas)**: leer `locRepeatMovingCount` de una salida limpia antes de tocar la frescura del fix. Esa zona ya provocó dos reverts (`61161b2`, `553fc8c`).
- **H4(a)**: no hay arreglo barato correcto. Sólo corregir el comentario optimista de LOCK 6 y su referencia de línea obsoleta, y anotar el techo real (~600 s por el clamp de 2 s/m en `TrackSamples.kt:38`).
- **Batería B1-B4, M2, M4, M5, M7**: medir antes de optimizar; no tocar en esta ronda.

## Estado de ejecución (2026-09-08)

**Lotes A y C aplicados**, sin commitear. 590 tests, 0 fallos; `lintDebug` limpio; compilación sin warnings nuevos.

- A: `publishGhostMarker` envuelto en `runCatching` (5 call sites cubiertos por una sola guarda), los dos `configJob` de `GapNumericDataType`/`GapGraphicDataType`, y el guard de identidad `mapEmitter === emitter` en el cancellable.
- C: `save` devuelve `Boolean`; `add` y ambos `addAll` dejan de contar/indexar/clavar una escritura fallida; `BulkSink.lastFailedIds` (mismo idioma que `lastEnrichedCount`) permite al importer no ledgerar ni pasar el watermark; `DecodedOrFail.Failed` lleva su `lastModified` y `syncLastScan()` acota `lastScan` por debajo del fallo transitorio más antiguo, bajándolo si hace falta.
- El log de fin de salida ya no afirma "duplicado" cuando la causa pudo ser un fallo de escritura.

**Locks verificados como DISCRIMINANTES** revirtiendo cada corrección por separado: quitar el skip de escritura del BulkSink → fallan 2 tests; quitar sólo el clamp del watermark → falla 1.

**Sin test, y a propósito:** el Lote A no lo tiene. Sus tres correcciones viven en métodos privados de un `Service` de Android; el precedente del repo (extraer helpers puros como `rematchActionFor`) no compensa para un `===` y dos `runCatching` — el helper sería trivialmente correcto y el riesgo real está en el call site, que no cubriría. Queda para la matriz física.

**Pendiente:** Lotes B y D, y todo lo marcado como "sin código".

## Ronda adversarial sobre A + C + subconjunto de B (2026-09-08)

Dos pasadas hostiles de Claude, cada una con objetivos concretos que **falsificar**. Encontraron
defectos reales en el código nuevo. Aceptado y corregido:

| Hallazgo | Qué estaba mal | Corrección |
|---|---|---|
| Re-seed de breadcrumbs | `push()` estampa `ghostTime`; con la ventaja acumulada la única miga quedaba por delante de `elapsedS`, clavando `place()` en su rama `lo == 0`: marcador congelado durante `gapTimeS` segundos y `gapDistM` reportando "metros desde el glitch" en vez de la ventaja | Estampar el reloj de carrera. Tras perder el rastro entero no hay base para un gap de distancia; 0 hasta que se acumulen migas es la regla de la casa ("refuse to guess") |
| Watermark del importer | Acotaba también con fallos de **decode**. `Failed` cubre un decode null, que en un fichero corrupto es DETERMINISTA y nunca se ledgerea → se re-listaría y re-decodificaría en cada import, para siempre | Acotar sólo con fallos de **escritura**, que son IO transitorio por definición. Un decode transitorio (fichero a medio escribir) recupera mtime al terminar y vuelve a entrar solo |
| `a failed write is not folded into the spatial index` | **Test vacuo**: `loadByIds` ya salta ids sin fichero (`TrackStore.kt:539`), así que devolvía lista vacía también con el bug | Assertar `rankedCandidateIdsFor`, que lee el snapshot del índice sin tocar ficheros |
| Emitter del mapa | Se soltaba ante CUALQUIER excepción. Nada salvo un `startMap` del host reasigna `mapEmitter`, y `startMapLoop` lee null como "no hay página de mapa" → un throw transitorio dejaba el fantasma sin mapa el resto de la salida | Soltar sólo ante `DeadObjectException` |
| Seeds síncronos | Se guardó el `onNext` del config y quedaron desnudos los dos `updateView` del seed, que corren en el hilo binder del host fuera de todo `try` y son el mismo transact no-oneway | Guardados |
| `lastFailedIds` | Devolvía el set mutable vivo, que se limpia en la llamada siguiente | Copia |
| Cobertura del test del importer | 3 ficheros contra `FLUSH_EVERY = 25`: un solo flush, así que nunca ejercitaba la BAJADA del watermark; mtimes separados 1 ms se colapsan en un filesystem de granularidad 1 s | Caso de 30 ficheros con el fallo más antiguo en el chunk final; mtimes a escala de segundo; más una aserción de que `lastFailedIds` se resetea por llamada |

**REVERTIDO por completo: el gate de frescura del grabador.** `TrackRecorder.kt:34-38` documenta que ②
(grabación en vivo) y ③ (el mismo FIT reimportado) deben decimar la MISMA secuencia para caer en el
mismo bucket de 10 m del `sourceKey`, "else dedup FAILS → duplicate track", y ese gemelo no se
auto-cura nunca (`selectArchivable` deja grupos de ≤3 en paz para siempre) → esa ruta queda
doble-contada en AVERAGE de forma permanente. El gate saltaba `onSample` entero, que además mantiene
`lastFed`. La raíz real sigue siendo el re-estampado incondicional de `lastFix.ms` sobre una
coordenada repetida (`KGhostExtension.kt:1787`), y ésa es justo la que espera la sonda
`locRepeatMovingCount`. Vuelve a su sitio: H5, sin código, hasta tener el dato.

**Corrección a una afirmación mía:** cité que el lock del replay de Sergi1 siguiera verde como
evidencia de que H1 era seguro. No lo es: `FitDecoder.kt:143` clampea la distancia monótona no
decreciente, así que ese test **nunca entra en `dd < 0`**. Además usa `assumeTrue` sobre un fixture
gitignorado, o sea que fuera de la máquina del autor se salta en silencio.

## Revisión adversarial de Codex sobre el resultado (READ-ONLY, árbol intacto)

Veredicto: `needs-attention`. Adjudicó la pregunta abierta y encontró tres huecos de test que las
pasadas de Claude no vieron. Corregido lo que era mío y barato:

- **El test multi-chunk no probaba la BAJADA.** Los workers son paralelos, así que el fallo podía
  observarse antes del primer flush; en ese orden una implementación forward-only pasaba igual. Ahora
  el watermark ARRANCA por encima de todos los ficheros (50 s), así que bajarlo es la única forma de
  pasar, sea cual sea el orden. Verificado: forward-only ahora falla.
- **El test de rollback assertaba el signo, no el valor.** Un arreglo que clampease a cero toda
  distancia AHEAD negativa pasaba igual dejando el campo inútil. Ahora asserta ≈ +5 m.
- **El `addAll` público no tenía test de fallo**, sólo el del `BulkSink` — revertir esa rama sola
  pasaba el fichero entero. Añadido; verificado discriminante.
- **Mi comentario del watermark exageraba.** `FitDecoder` envuelve su lectura en `runCatching`, así
  que agotar descriptores o un error de almacenamiento pasajero también devuelve null **sin tocar el
  mtime**, y un fichero nuevo con éxito puede llevarse `lastScan` por delante para siempre. Reescrito
  como hueco conocido y aceptado, no como prueba.

### ~~RESUELTO CON DATOS~~ — RETIRADO: la medición no podía ver el caso dañino

Medido sobre los logs del propio Karoo (`adb pull /sdcard/KGhost/logs`), salida real del 2026-09-06,
25 km: **578 episodios de gps-loss, los 578 clasificados `COASTING`. Cero LIVE.** Sólo 31 con
excedente; **70 m en toda la salida**, **máximo individual 11 m**. Medido en el origen (`coasted`
menos `rawStep` de cada episodio), sin el throttling que afecta a la línea de tick.

**Codex refutó la conclusión, y tiene razón.** El episodio de gps-loss sólo se registra cuando LIVE
sigue a un estado NO-LIVE. Una secuencia `R → R+400 → R+small` permanece LIVE de principio a fin y
**no genera ningún episodio**. Así que estos 578 episodios miden únicamente el overshoot de
freeze/recovery — el caso benigno — y **no dicen nada** sobre el pico raw-LIVE, que es el dañino.

Lo que sí queda probado: el caso freeze/recovery es despreciable (70 m en toda una salida, máx 11 m),
y ahí restar penalizaría dos veces porque el relleno ya es neutro. Los retrocesos de la línea de tick
(6 en septiembre, 51 en agosto, máx 27 m) están todos rodeados de episodios, coherente con eso.

**El riesgo del pico raw-LIVE queda SIN MEDIR, no descartado.** Para cerrarlo hace falta un
diagnóstico por salida, sin throttling, del máximo paso positivo del odómetro crudo y del retroceso
siguiente **mientras la calidad sigue siendo LIVE**; después, datos de una salida real. No se toca la
invariante de B2 hasta tener ese número.

### HECHO — separación identidad / geometría en el grabador

`TrackRecorder.onSample` acepta ahora `lat`/`lng` **nulables**: una muestra sin posición usable
(sin fix aún, o demasiado viejo) sigue avanzando la identidad pero no aporta geometría.

- El decimador corre en **todas** las muestras. Sus decisiones dependen sólo de la distancia — nunca
  lee lat/lng — así que siguen siendo idénticas a las de ③ y ② no se re-ancla tras un tramo obsoleto.
- Sólo el **append** está condicionado a tener posición.
- `build()` calcula el `sourceKey` con la cola del decimador (`identityTailM`), no con
  `buffer.last()`: difieren exactamente cuando se descartaron muestras por falta de posición, y es la
  del decimador la que ③ reproduce.
- El endpoint sólo se re-añade desde una muestra **posicionada**.

El orden de parámetros se mantiene a propósito: todos son `Double`, así que reordenarlos dejaría cada
llamada existente compilando y significando otra cosa (se probó, y falló justo así).

Sin tramos obsoletos el comportamiento es **idéntico** — los tests previos del grabador pasan sin
tocarlos. Tres tests nuevos fijan las dos mitades, verificados discriminantes contra las dos
implementaciones equivocadas: saltar `onSample` entero rompe la simetría de bucket, y alimentar la
coordenada obsoleta fabrica geometría repetida.

### ABIERTO — pendiente

1. **(High) El rollback conserva ventaja comprada por un pico raw-LIVE.** SIN MEDIR — el cierre por
   datos se retiró (ver arriba). Siguiente paso concreto: añadir el diagnóstico de paso-positivo /
   retroceso bajo LIVE y leerlo de una salida. Codex matiza a los dos
   revisores anteriores: un débito ciego de `ghostTime` sería INCORRECTO, porque durante un dropout
   `paceAt` devuelve null → relleno neutro → los metros estimados suman lo mismo a `ghostTime` y a
   elapsed, sin ventaja no ganada. Pero un pico del odómetro RAW se clasifica LIVE y sí cobra
   historia: 400 m de pico a 0.105 s/m = 42 s retenidos para siempre, y el gap pasa de −37 s a +5 s.
   El arreglo correcto no es un débito sino **procedencia**: deshacer sólo el crédito de veredicto
   histórico que el rollback invalida, conservando la contribución neutra. Cambia una invariante del
   modelo B2.
2. ~~**(High) El grabador escribe coordenadas obsoletas.**~~ HECHO, arriba. Nota: no hizo falta tocar
   `HistoryImporter` — al dejar que el decimador corra siempre, la cola de identidad de ② vuelve a ser
   exactamente la de antes del gate, que es la que ③ ya reproducía.
3. **(Medium) Un fallo de decode transitorio sin cambio de mtime todavía puede quedar detrás del
   watermark.** Requiere que `Failed` lleve el fichero y una clasificación transitorio/determinista.

Codex confirma además que `DeadObjectException` **es** el discriminador correcto (un target Binder
muerto la lanza específicamente; las otras candidatas describen fallos de transacción o de servidor
vivo), y que la truncación total del rastro **no es esperable** en una recuperación monótona normal.

Estado: **594 tests, 0 fallos, lint limpio.** Cada corrección con su lock verificado discriminante
revirtiéndola por separado.

## Segunda ronda adversarial — sobre el grabador (2026-09-08)

Una pasada hostil de Claude y una de Codex sobre el cambio de identidad/geometría. Ambas dijeron
**no-ship**, y con razón. Corregido:

- **Mis tres tests no probaban el cambio.** Confirmado por mutación: sustituir `identityTailM` por
  `buffer.last()` los dejaba a los tres en verde, porque los tres terminaban en una muestra
  posicionada, donde ambos valores coinciden. Faltaba justo el caso que discrimina — la salida que
  **termina** en tramo obsoleto. Es la segunda vez en la misma sesión que escribo un test vacuo.
- **Bug nuevo que introduje: las muestras sin posición robaban los anclajes del decimador.** Con un
  único decimador y 20 m de espaciado, unos nulls cayendo en los múltiplos de 20 descartan **todas**
  las posiciones reales, `build()` devuelve null y se pierde una salida entera en silencio — peor que
  lo que el cambio arreglaba. Ahora hay **dos decimadores**: identidad y geometría.
- **La identidad vuelve a comportarse exactamente como antes del gate**: el decimador de identidad
  avanza en cada muestra una vez ha llegado algún fix, que era la condición de alimentación anterior.
  Así no hay regresión de paridad con ③.
- **El helper `fitTrack` del test no era fiel**: `FitDecoder.kt:126` descarta todo registro sin
  posición, así que un dropout real deja el FIT **sin registros** en ese tramo y ③ se re-ancla al otro
  lado. El test se retiró en vez de fijar una invariante falsa.
- **Límite declarado explícitamente en el código**: ② indexa por odómetro ABSOLUTO mientras
  `FitDecoder` rebasa a su primer registro POSICIONADO, así que una salida con lock tardío de GPS ya
  indexaba distinto en las dos rutas. Es previo a este cambio y sigue abierto.
- `TrackDecimator.shouldKeep` pierde `lat`/`lng`, que no usaba: elimina el `?: 0.0` y hace la trampa
  inconstruible para el siguiente que añada una regla geométrica.
- El log de "no guardado" imprime ahora `points=` e `identityDist=`, para que "40 km rodados, 0
  puntos" no se lea igual que "salida estacionaria".

Los tres mutantes (clave por `buffer.last()`, decimador compartido, coordenada obsoleta alimentada)
**fallan** con los tests nuevos. 599 tests, 0 fallos, lint limpio.

## Estado tras la tercera ronda (2026-09-08)

### #1 pico raw-LIVE — CERRADO por improbabilidad, con la cota medida

Además de los 578 episodios (todos COASTING, 70 m totales, máx 11 m), se acotó el punto ciego:
el log de tick sale cada ~3 s (throttle 2500 ms; media medida 3,0 s, máx 4 s) y a lo largo de dos
salidas (~90 km, ~5.800 intervalos) el **paso positivo máximo del odómetro fue 42 m** (septiembre) y
**41 m** (agosto) — que a 3 s son 14 m/s, o sea una bajada normal, no un pico. El retroceso máximo
fue 27 m, ~2,8 s de ventaja aunque fuera del tipo dañino.

Queda el punto ciego formal: un pico que aparezca y se resuelva dentro de una misma ventana de 3 s no
se vería. Pero si ocurrieran con regularidad, alguno habría caído a caballo de un límite de log; en
5.800 intervalos, ninguno. **Cerrado por improbabilidad, no por prueba de ausencia.** Si alguna vez se
quiere certeza, el camino es un contador sin throttling de paso-positivo-máximo y retroceso bajo LIVE.

### #2 decode transitorio sin cambio de mtime — ANOTADO para el futuro

`FitDecoder` envuelve la lectura en `runCatching`, así que agotar descriptores o un error de
almacenamiento pasajero devuelve null **sin tocar el mtime**; si un fichero más nuevo se importa bien,
`lastScan` lo adelanta y ese FIT queda fuera de todo escaneo `onlyNew` posterior.

Impacto: **una** salida ausente del historial. Recuperable con un rescan completo (`rebuildAll`), que
no aplica el filtro de mtime. Probabilidad baja y con salida manual, por eso no se aborda ahora.

Arreglo cuando se toque el importador: que `DecodedOrFail.Failed` lleve el fichero y una clasificación
transitorio/determinista — ledgerear o poner en cuarentena los corruptos (null determinista) y
reintentar los pasajeros con backoff independiente del mtime. El sitio es `HistoryImporter.decodeOne`,
y el `syncLastScan` actual ya tiene el gancho: bastaría alimentar `minFailedLastModified` también con
los fallos clasificados como transitorios.

### #3 paridad de identidad ② / ③ — INTENTADO Y REVERTIDO. No es arreglable desde ②

Eran **dos** desajustes, no uno. ③ (`FitDecoder.buildTrack`) indexa por `firstEpochMs` — el timestamp
del primer registro POSICIONADO — y por distancia rebasada a ese registro. ② indexaba por
`recordingStartedEpoch` y odómetro ABSOLUTO.

Se implementó alinear ② al dominio de ③: capturar el odómetro y el elapsed de la primera muestra
posicionada e indexar desde ahí. **Las dos revisiones adversariales dijeron no-ship, y tenían razón.**

**La mitad TEMPORAL era una regresión estricta.** El origen de ③ es el primer registro posicionado que
escribe el Karoo, que con GPS fijado cae prácticamente en el arranque. La primera muestra posicionada
de ② llega 1-3 s más tarde: `locationJob` se suscribe dentro de `startTick`, y el tick hace
`sample(1000)` y `drop(1)`. Sumar esa latencia **aleja** a ② de ③. Simulado sobre 20.000 salidas
ordinarias: antes 100 % de dedup, después 96,8 % — 623 salidas que funcionaban se rompían y **ninguna**
mejoraba. Y ni siquiera arreglaba su caso motivador: arrancar sin lock dispara el autopause,
`ELAPSED_TIME` se congela, y `startedAtEpoch + elapsed` deja de ser reloj de pared — 0 % igual que antes.

**La mitad de DISTANCIA también era regresión**, aunque la primera pasada la dio por buena. Si el
ciclista ya rueda al pulsar Start, el FIT tiene su primer registro en d=0 mientras la primera muestra
de ② cae en d=20: restar esos 20 m produce `:98` donde ③ dice `:100`. Sólo era inocua bajo el supuesto
de que la bici está parada al arrancar.

**La raíz, en la que coinciden las dos revisiones:** ② y ③ observan **eventos distintos con desfase no
acotado**. ③ acepta el primer registro con cualquier posición; ② exige `acc <= GPS_GOOD_ACCURACY_M`
sobre un stream suscrito dentro de `startTick` y muestreado a 1 Hz. No existe evento común ni cota
superior. **Ningún rebase dentro de `TrackRecorder` cierra eso**, así que el enfoque estaba equivocado
de raíz, no mal implementado.

Además, aunque funcionara, **no repararía las bibliotecas ya instaladas**: `recomputeSourceKeys` lee el
campo `sourceKey` almacenado y nunca lo re-deriva de los puntos, y `dropSourceKeys` excluye los
RECORDED a propósito. Justo la población que el cambio pretendía arreglar seguiría sin curarse.

Y un hallazgo que sobrevive al revert, **preexistente y mayor que el que se intentaba arreglar**: ②
alimenta su decimador de identidad con muestras sin posición y ③ nunca las ve, así que los enrejados
se re-anclan distinto tras un hueco. Simulado sobre salidas idénticas con orígenes idénticos y sin
relación con este diff: **las claves difieren en el 57 % de las salidas con un dropout**. La premisa
"una clave, dos rutas" ya es falsa para cualquier salida con pérdida de GPS.

**Revertido.** Los dos caminos reales, si algún día se aborda, los nombran las dos revisiones:
(a) hacer de ③ la única fuente — no almacenar el track en vivo cuando su propio FIT va a escanearse —
o (b) hacer la clave tolerante a un bucket adyacente. Ambos son decisiones de producto, no parches.

## El 57 % verificado con la biblioteca real del aparato (2026-09-08)

La cifra del 57 % venía de una simulación. Comprobada contra los 204 tracks vivos y 182 archivados
del Karoo (`adb pull /sdcard/KGhost/tracks`), el fallo es **real y peor de lo simulado**.

Nota metodológica: un track RECORDED **no lleva campo `source`** en el JSON — kotlinx omite el valor
por defecto — así que un primer recuento los daba por `None` y concluía "cero grabaciones en vivo".
Falso. Y los gemelos archivados viven en `tracks/archive`, que hay que mirar aparte.

| | |
|---|---|
| Tracks RECORDED vivos (>500 m) | 30 |
| Con gemelo FIT **también vivo** → doble conteo activo | **7** |
| Con el gemelo ya archivado por tidy | 1 |
| Parejas identificadas en total | 8 |
| **De esas 8, con la misma `sourceKey`** | **0** |

**La clave de deduplicación no ha coincidido ni una sola vez** en este aparato para una salida
almacenada por ambas rutas. Emparejamiento: mismo inicio ±30 min y longitud dentro del 8 %; los casos
fuertes son inequívocos (2026-08-19: Δ0 s, 80,7 km en ambas; 2026-05-25: Δ10 s, 40,4 km).

Desglose de por qué fallan — y las dos mitades fallan por igual:

| Salida | | Causa |
|---|---:|---|
| 2026-08-19 | 80,7 km | distancia +1 bucket (+10 m) |
| 2026-05-25 | 40,4 km | distancia **−8 buckets (−80 m)** |
| 2026-07-12 | 26,1 km | minuto −3 |
| 2026-07-03 | 25,5 km | minuto −1 |
| 2026-06-23 | 11,8 km | minuto −2 |
| 2026-06-24 | 3,4 km | distancia +2 buckets (+20 m) |
| 2026-06-23 | 5,3 km | minuto +28 y distancia +1 (emparejamiento menos seguro) |

**Consecuencia para las dos opciones de arreglo.** La opción (b), hacer la clave tolerante a un bucket
adyacente, **sólo cubriría 3 de los 7**: no salva el −80 m ni los desfases de 2 y 3 minutos. La opción
(a) — que ③ sea la única fuente, no almacenar el track en vivo cuando su propio FIT va a escanearse —
los cubre todos, y además elimina de raíz el problema de que dos tuberías independientes tengan que
coincidir en un origen que ninguna puede observar de la otra.

Esto refuerza el revert: el arreglo correcto no era realinear ②, sino decidir si ② debe almacenar.
