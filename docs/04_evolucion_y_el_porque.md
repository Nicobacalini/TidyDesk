# 4. Evolución de Diseño (v0 a v3) y el Porqué de cada Decisión

## Índice de Contenidos

1. [Decisiones Comparativas: ¿Por qué elegí estas herramientas?](#1-decisiones-comparativas-por-qué-elegí-estas-herramientas)
2. [Historia y Autocrítica: Cómo evolucioné mi Algoritmo desde `v0` hasta `v3`](#2-historia-y-autocrítica-cómo-evolucioné-mi-algoritmo-desde-v0-hasta-v3)
3. [Bitácora de Auditoría de Bajo Nivel (Antes vs. Después en `v3`)](#3-bitácora-de-auditoría-de-bajo-nivel-antes-vs-después-en-v3)
4. [Limitaciones conscientes del diseño actual (`v3`)](#4-limitaciones-conscientes-del-diseño-actual-v3)

---

## 1. Decisiones Comparativas: ¿Por qué elegí estas herramientas?

### 1. ¿Por qué usar un número fijo de hilos (`availableProcessors()`) y no crear un `Thread` por cada subcarpeta?
Lanzar un hilo nuevo por cada carpeta que aparece suena tentador, pero rompe el programa por dos razones concretas:
* **Límite de carpetas abiertas del sistema operativo**: Cada vez que un hilo lee una carpeta (`Files.newDirectoryStream`), el sistema operativo mantiene abierto ese recurso hasta cerrarla. Si miles de hilos abren carpetas a la vez, se agota el límite de archivos abiertos del sistema (`Too many open files`). Con un hilo por núcleo lógico, nunca hay más carpetas abiertas en simultáneo que núcleos disponibles.
* **Consumo de RAM y saturación del procesador**: Cada hilo reserva su propia memoria de pila y, si hay cientos compitiendo al mismo tiempo, el procesador pierde más tiempo saltando de un hilo a otro que buscando archivos (ver [03_decisiones_de_opti_SO_cache_Concurrencia.md](03_decisiones_de_opti_SO_cache_Concurrencia.md#b-el-costo-de-sobrecargar-la-cpu-context-switch-y-cache-thrashing)). Además, abrir un hilo por cada subcarpeta explora el árbol a lo ancho (BFS) en vez de ir en profundidad, disparando la memoria ocupada.

### 2. ¿Por qué `monitor.wait()` / `notifyAll()` en vez de `Thread.sleep()`, `Thread.yield()` o `Semaphore`?
* **Por qué no `Thread.sleep(ms)`**: Duerme a ciegas todo ese tiempo; si aparece una carpeta nueva apenas se duerme, el hilo no se entera hasta que termine el reloj y pierde tiempo esperando de más.
* **Por qué no `Thread.yield()` o esperar en un `while` infinito**: Si no hay otros hilos esperando turno, el sistema operativo vuelve a darle CPU al mismo hilo enseguida, dejándolo gastar energía y competir por procesador con los hilos que sí están trabajando.
* **Por qué no `Semaphore`**: Va acumulando permisos aunque no haya ningún hilo durmiendo o alguien ya haya robado las carpetas, y tampoco tiene una forma directa de despertar a todos los hilos dormidos de golpe cuando termina la búsqueda.
* **Por qué elegí `monitor.wait(ESPERA_POLL_MS)` + `notifyAll()`**: Pausa al hilo en reposo real sin consumir CPU mientras no hay trabajo y, a diferencia de `Thread.sleep(ms)`, lo despierta al instante cuando hay carpetas nuevas o al finalizar. El *timeout* (`ESPERA_POLL_MS`) funciona solo como red de seguridad ante un *lost wakeup* (si un hilo emite `notifyAll()` justo entre que otro falla al robar y entra al `synchronized` para llamar a `wait()`), acotando esa espera excepcional en lugar de quedar bloqueado indefinidamente (ver [03_decisiones_de_opti_SO_cache_Concurrencia.md](03_decisiones_de_opti_SO_cache_Concurrencia.md#c-espera-pasiva-y-sincronización-con-monitor-en-supervisorexploracion)).

### 3. ¿Por qué `ConcurrentLinkedQueue<String>` en `TableroBusqueda` en vez de `Collections.synchronizedList(new ArrayList<>())` o `CopyOnWriteArrayList`?
* **Por qué no `synchronizedList(new ArrayList<>())`**: Hace que los hilos pasen de a uno por un cerrojo cada vez que encuentran un archivo y, cuando el arreglo interno se llena, tiene que crear otro más grande y copiar todo mientras bloquea a los demás.
* **Por qué no `CopyOnWriteArrayList`**: Copia el arreglo entero en cada `add()`, lo que resulta muy costoso si aparecen muchas coincidencias.
* **Por qué elegí `ConcurrentLinkedQueue`**: Permite que varios hilos guarden rutas encontradas al mismo tiempo sin bloquearse (`lock-free`) y sin redimensionar arreglos, evitando que un trabajador se frene al registrar resultados (la copia final con `List.copyOf()` se explica en [01_arquitectura_y_patrones.md](01_arquitectura_y_patrones.md#d-el-patrón-blackboard-tablón-compartido--arquitectura-de-sistemas-concurrentes)).

---

## 2. Historia y Autocrítica: Cómo evolucioné mi Algoritmo desde `v0` hasta `v3`

Para llegar a la arquitectura actual de TidyDesk, evalué varias alternativas en papel y pasé por una implementación previa en código (`v2`). Analizar cada enfoque, detectar sus cuellos de botella antes y después de programar, y rediseñar el motor fue la parte donde más aprendí:

#### Línea de tiempo de mi diseño

| Versión | Estado | Estructuras de Datos | Cómo se repartía el trabajo | Por qué la descarté o evolucioné |
| :-: | :-: | :--- | :--- | :--- |
| **v0** | *En papel* | Una **única cola compartida** | Todos los hilos sacan carpetas y devuelven todas las subcarpetas a esa misma cola única. | **Descartada en papel**: como casi toda operación descubre subcarpetas nuevas, todos los núcleos pelean por sincronizar la misma cola en cada operación y el recorrido se vuelve **BFS**, disparando el uso de RAM. |
| **v1** | *En papel* | `ArrayDeque` privada por hilo, **sin comunicación** | Se reparten las subcarpetas de la raíz al inicio (*partición estática*) y cada hilo explora su rama por separado. | **Descartada en papel**: los sistemas de archivos reales son asimétricos; si una sola subcarpeta de la raíz concentra el 90% de los archivos (ej. `/home` o `/usr`), algunos hilos terminan en milisegundos y quedan ociosos mientras un único hilo carga con todo el trabajo. |
| **v2** | *Implementada en código* | `ArrayDeque` privada por hilo (`pilaLocal`) + **cola central de desborde** (`colaGlobal`) | Cada hilo explora en DFS y, cuando `colaGlobal` está vacía o hay hilos ociosos, conserva 1 subcarpeta y desborda el resto a `colaGlobal`. | **Funcionaba, pero fallaba al escalar**: al analizar todo de vuelta descubrí **7 problemas críticos** (detallados abajo). |
| **v2.5** | *En papel* | `ArrayDeque` por hilo protegida con `synchronized` | Igual que el robo de trabajo, pero usando un cerrojo por cada cola en lugar de colas sin bloqueos. | **Descartada en papel**: aunque elimina la cola central, obliga al dueño a adquirir el cerrojo en cada `push` y `pollFirst`, y lo bloquea cada vez que un ladrón intenta inspeccionar o robar de su cola. |
| **v3** | *Implementada en código (`src/`)* | `ConcurrentLinkedDeque` por hilo, **sin cola central** | El hilo dueño nunca se detiene a repartir: el que queda libre **roba** 1 carpeta del fondo de un compañero (*Work-Stealing* *lock-free*). | **Resuelve los 7 problemas de raíz**, elimina listas intermedias y evita la contención central. |

---

### 1. El motor previo en código (`v2`): pila local + cola central de desborde

En `v2` (la única versión previa que implementé), cada hilo tenía una `pilaLocal` (`ArrayDeque<File>`) privada y el supervisor mantenía una `colaGlobal` de desborde:

1. Cada hilo tomaba una carpeta de `colaGlobal` y exploraba en profundidad (DFS) mientras su `pilaLocal` tuviera elementos, guardando las subcarpetas nuevas de cada directorio en un `List<File> subcarpetas` temporal.
2. Al terminar de leer el directorio, ejecutaba `repartirSubcarpetas(subcarpetas)`:
   * Consultaba `supervisor.convieneCompartirTrabajo()` (`hilosSinTrabajo.get() > 0 || colaGlobal.isEmpty()`).
   * Si daba `true`, conservaba la primera subcarpeta en `pilaLocal` (`pilaLocal.push(subcarpetas.getFirst())`) para aprovechar la caché caliente y enviaba el resto (`subcarpetas.subList(1, subcarpetas.size())`, sin copiar la lista) a `colaGlobal`.
   * Si daba `false`, apilaba todas en su `pilaLocal`.

### 2. Los 7 problemas de `v2` y cómo los resuelve `v3`

| # | Problema en `v2` | Qué pasaba | Cómo lo resuelve `v3` |
| :-: | :--- | :--- | :--- |
| **1** | **Reparto innecesario (`\|\| colaGlobal.isEmpty()`)** | Como `colaGlobal` se vacía mientras los hilos exploran, la condición daba `true` casi siempre y volcaba carpetas aunque todos estuvieran ocupados. | Se elimina `colaGlobal`; solo se transfiere trabajo cuando un hilo queda libre y roba. |
| **2** | **Se compartían carpetas chicas** | Al desbordar desde directorios profundos, iban a `colaGlobal` subcarpetas pequeñas mientras las ramas grandes quedaban al fondo de `pilaLocal`. | El ladrón extrae por el fondo (`pollLast`), llevándose las ramas más cercanas a la raíz. |
| **3** | **Carpetas atrapadas en una pila privada** | Si un hilo apilaba varias subcarpetas y luego entraba a una carpeta sin hijos, `repartirSubcarpetas` no tenía nada nuevo que enviar y las anteriores quedaban ocultas para los demás. | Las colas viven en el supervisor y quedan expuestas para que cualquier hilo ocioso pueda robar de ellas. |
| **4** | **Costo de reparto en el hilo ocupado** | En cada carpeta había que crear un `ArrayList` temporal, consultar al supervisor, partir con `subList` y empujar a `colaGlobal`. | El dueño solo apila y saca de su propia cola en $\mathcal{O}(1)$. |
| **5** | **Cierre frágil entre dos niveles de colas** | Detectar el fin exigía verificar que `colaGlobal` y todas las `pilaLocal` estuvieran vacías a la vez, con riesgo de corte prematuro si una carpeta estaba en tránsito. | Hay una sola cola por hilo y el ladrón sale de ociosidad antes de robar (ver [02_Diseño_decisiones_de_concurrencia_y_trabajo.md](02_Diseño_decisiones_de_concurrencia_y_trabajo.md#c-protocolo-de-detección-de-terminación-y-consenso-global)). |
| **6** | **Contención sobre una única cola** | Al despertar, todos los hilos ociosos competían al mismo tiempo por `colaGlobal`. | Cada ladrón empieza desde un índice aleatorio (`ThreadLocalRandom`), repartiendo los intentos. |
| **7** | **Aviso tardío en carpetas grandes** | Como se repartía recién al terminar todo el `for` del directorio, los hilos ociosos esperaban hasta el final si había miles de archivos. | Se avisa apenas la cola acumula 2 subcarpetas (`peekFirst() != peekLast()`). |

---

## 3. Bitácora de Auditoría de Bajo Nivel (Antes vs. Después en `v3`)

Una vez implementado `v3`, realicé una auditoría de concurrencia, microarquitectura y llamadas al sistema. Esta tabla resume los cambios históricos entre `v2` / la primera versión de `v3` y el código actual (con enlace a la explicación profunda de cada mecanismo):

| # | Componente | Cómo estaba antes (`v2` / primera `v3`) | Qué cambié en la versión final y dónde se explica |
| :-: | :--- | :--- | :--- |
| 1 | **`HiloExploradorBase.run()`** | Si un hilo moría por `RuntimeException \| Error`, salía sin avisar y colgaba `join()`. | Captura y llama a `supervisor.abortar()` ([Ver estrategia Fail-Fast en Doc 03](03_decisiones_de_opti_SO_cache_Concurrencia.md#d-estrategia-de-manejo-de-errores-precondición-vs-fail-soft-vs-fail-fast)). |
| 2 | **`robarCarpeta()` (Cerrojo)** | El método `robarCarpeta()` entero era `synchronized`. | Robo en paralelo puro con CAS; `monitor` acotado solo a dormir/despertar ([Ver Doc 01](01_arquitectura_y_patrones.md#decisiones-de-encapsulamiento-inmutabilidad-y-sincronización-en-las-clases)). |
| 3 | **Lectura de disco y tipos** | Detectado al analizar `v2`: usaba `File.listFiles()` (3 *syscalls* y arreglo `File[]`) o conversiones `toPath()`/`toFile()`. | Unificación a `Path`, `DirectoryStream` $\mathcal{O}(1)$ y `readAttributes` (1 *syscall*) ([Ver NIO.2 en Doc 03](03_decisiones_de_opti_SO_cache_Concurrencia.md#c-de-filelistfiles-a-directorystreampath-menos-syscalls-streaming-o1-y-directoryiteratorexception)). |

---

## 4. Limitaciones conscientes del diseño actual (`v3`)

1. **Directorios planos gigantes**: El robo de trabajo opera a granularidad de **subcarpetas**, no de archivos. Si una única carpeta contiene cientos de miles de archivos y ninguna subcarpeta, `procesarDirectorio()` la recorre en un solo hilo; dividir un `DirectoryStream` entre hilos obligaría a sincronizar el iterador y penalizaría el caso normal de árboles jerárquicos.
