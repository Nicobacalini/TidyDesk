# 3. Decisiones de Optimización: SO, Caché y Concurrencia

## Índice de Contenidos

1. [Manejo Eficiente de E/S y Errores en Hilos (NIO.2)](#1-manejo-eficiente-de-es-y-errores-en-hilos-nio2)
2. [Decisiones de Hardware, Memoria Caché y Concurrencia](#2-decisiones-de-hardware-memoria-caché-y-concurrencia)

## 1. Manejo Eficiente de E/S y Errores en Hilos (NIO.2)

Al diseñar `HiloExploradorBase`, reemplacé la vieja API `java.io.File` por **Java NIO.2 (`java.nio.file`)**. Aunque el foco del motor es la concurrencia, la forma de leer el sistema de archivos impacta directamente en los hilos, evita ciclos infinitos, recorridos innecesarios sobre sistemas de archivos virtuales/volátiles, contención en el *Garbage Collector* y caídas silenciosas de trabajadores.

### A. Prevención de Bucles Infinitos por Enlaces Simbólicos (`NOFOLLOW_LINKS`)

* **El problema**: Un enlace simbólico puede apuntar a un directorio ancestro o a sí mismo, atrapando a un hilo en un ciclo infinito.
* **Solución en `HiloExploradorBase`**: `Files.readAttributes(..., LinkOption.NOFOLLOW_LINKS)` consulta los atributos del enlace (`lstat`) sin seguir su destino. Si `attrs.isSymbolicLink()` es `true`, hace `continue` antes de evaluar `isDirectory()` o `isRegularFile()`, evitando tanto apilarlo en la cola (`push`) como procesarlo.

### B. Exclusión de Pseudo-Sistemas de Archivos Virtuales (`/proc`, `/sys`, `/dev`, `/run`)

* **Por qué se excluyen**: En Linux no son árboles persistentes en disco `/proc` y `/sys` exponen estado del kernel, `/dev` agrupa dispositivos y `/run` guarda estado volátil en memoria de servicios (PIDs, *sockets*, *locks*).
* **Cómo se aplica**: `RUTAS_VIRTUALES_EXCLUIDAS` filtra esas 4 rutas al entrar a `procesarDirectorio` y antes de hacer `push`. Así los hilos no pierden tiempo recorriéndolas al explorar desde `/`.

### C. De `File.listFiles()` a `DirectoryStream<Path>`: Menos *Syscalls*, *Streaming* O(1) y `DirectoryIteratorException`

1. **Una sola llamada al sistema por entrada**: `java.io.File` podía disparar múltiples consultas al sistema operativo para determinar si una entrada era enlace simbólico, directorio o archivo regular. Con `Files.readAttributes(..., NOFOLLOW_LINKS)`, el kernel devuelve todos los metadatos en **una única llamada (`lstat`)** y las comprobaciones posteriores (`isSymbolicLink`, `isDirectory`, `isRegularFile`) se resuelven en memoria dentro de la JVM *(referencias: [Java Tutorials — Managing Metadata](https://docs.oracle.com/javase/tutorial/essential/io/fileAttr.html) y [Javadoc de `Files.readAttributes`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/file/Files.html#readAttributes(java.nio.file.Path,java.lang.Class,java.nio.file.LinkOption...)))*.
2. **Lectura en *streaming* ($\mathcal{O}(1)$ en memoria) y uso uniforme de `Path`**: A diferencia de `dir.listFiles()`, que carga todo el `File[]` en el Heap antes de empezar, `DirectoryStream<Path>` lee las entradas bajo demanda (memoria constante) y el `try-with-resources` cierra el descriptor apenas termina. Además, trabajar siempre con `Path` evita convertir entre `File` y `Path` dentro del bucle, reduciendo basura para el *Garbage Collector* en todos los hilos.
3. **Captura de `DirectoryIteratorException`**: `Files.newDirectoryStream(dir)` solo abre el descriptor; la lectura real ocurre dentro del `for`. Si falla el acceso durante la iteración, NIO.2 lanza `DirectoryIteratorException` (una `RuntimeException`). Capturar `catch (IOException | DirectoryIteratorException | SecurityException)` y registrarlo con `supervisor.registrarCarpetaInaccesible()` evita que un error puntual de lectura mate al hilo.

### D. Estrategia de Manejo de Errores: Precondición vs. *Fail-Soft* vs. *Fail-Fast*

Clasifiqué los fallos en tres niveles según si deben detener o no la ejecución concurrente:

1. **Errores de precondición (Rechazo temprano antes de lanzar hilos)**: `ValidadorRuta` (que además exige permisos POSIX de lectura `isReadable` para listar nombres y de ejecución `isExecutable` para atravesar el directorio), `TableroBusqueda` y `SupervisorExploracion` lanzan `IllegalArgumentException` de forma síncrona antes de reservar hilos.
2. **Errores esperables de E/S (*Fail-Soft*)**: Si un archivo desaparece en tránsito (`ENOENT`) se omite con `continue`, y si una carpeta falla por permisos (`EACCES`) se suma al `AtomicInteger` (`supervisor.registrarCarpetaInaccesible()`) sin detener al hilo.
3. **Fallos irrecuperables en un trabajador (*Fail-Fast* con `RuntimeException | Error`)**: Si una subclase o la JVM lanza un error no recuperable (como `OutOfMemoryError`), ese hilo no puede morir en silencio porque los demás esperarían eternamente a que alcance `todosOciosos()`, colgando el `join()` del hilo principal. Por eso `HiloExploradorBase.run()` captura `RuntimeException | Error`, invoca `supervisor.abortar()` (`abortado = true`, `finalizado = true` y `notifyAll()`) y relanza el error.

---

## 2. Decisiones de Hardware, Memoria Caché y Concurrencia

### A. ¿Por qué `Runtime.getRuntime().availableProcessors()` y no un número fijo de hilos?

* **Alineación con el hardware**: Dimensionar los trabajadores con `availableProcessors()` adapta automáticamente el motor a los núcleos lógicos disponibles de cada máquina, sin quedar subutilizado en CPUs con muchos núcleos ni saturar procesadores pequeños.
* **Equilibrio entre E/S y CPU**: Mantiene acotado el número de descriptores de directorio abiertos en simultáneo y evita competir innecesariamente por planificación cuando el sistema de archivos ya está cacheado en memoria por el SO.

---

### B. El costo de sobrecargar la CPU: *Context Switch* y *Cache Thrashing*

Lanzar muchisimos hilos en una CPU de pocos núcleos degrada el rendimiento de un motor concurrente por dos efectos directos:

1. **Sobrecarga de Cambio de Contexto**: Cuando hay muchos más hilos ejecutables que núcleos lógicos, el planificador del kernel debe desalojar e intercambiar hilos continuamente, gastando ciclos de CPU en administración en lugar de avanzar en la exploración.
2. **Contaminación de Caché y ajuste con SMT**: El costo indirecto es mayor que el directo: mientras un hilo recorre su rama, su *working set* (nodos de `colaLocal`, `nombreObjetivo`, buffers) reside en las cachés L1/L2 del núcleo. Si el planificador lo desaloja para correr otro hilo, esas líneas se expulsan (*cache eviction*) y al volver sufre ráfagas de fallos de caché. En procesadores con SMT (*Hyper-Threading*), usar los hilos lógicos de `availableProcessors()` resulta favorable porque el recorrido alterna espera de metadatos con comparaciones en memoria, ocultando latencias sin saturar el planificador.

---

### C. Espera Pasiva y Sincronización con `monitor` en `SupervisorExploracion`

Cuando un hilo vacía su cola local e intenta robar sin encontrar trabajo disponible en las colas vecinas, pasa a espera pasiva sobre un monitor privado (`monitor.wait(ESPERA_POLL_MS)`):

```java
private void dormirHastaHayaTrabajo() throws InterruptedException {
    synchronized (monitor) {
        if (!finalizado) {
            monitor.wait(ESPERA_POLL_MS);
        }
    }
}
```

* **Consumo casi nulo de CPU en espera**: En lugar de quedar girando en un bucle de espera activa gastando procesador cuando las colas están vacías, el hilo consume 0 % de CPU mientras permanece suspendido en `monitor.wait(ESPERA_POLL_MS)`, con solo una breve comprobación periódica cada `50 ms`, cediendo el núcleo a los hilos que están leyendo el disco.
* **Despertar inmediato (`notifyAll()`)**: En `procesarDirectorio()`, si hay hilos dormidos (`hayHilosSinTrabajo()`) y al menos dos carpetas en `colaLocal` (`peekFirst() != peekLast()`), se llama a `despertarHilosSinTrabajo()` una sola vez por directorio (`avisado = true`), con un chequeo de respaldo al cerrar el `DirectoryStream`.
* **¿Por qué `wait(50 ms)` y no un `wait()` infinito?**: Para el cierre, `if (!finalizado)` dentro de `synchronized (monitor)` alcanza porque `terminar()` pone `finalizado = true` antes de `notifyAll()`. El *timeout* de `50 ms` protege específicamente contra avisos perdidos de **nuevo trabajo** (por ejemplo, si un hilo se duerme justo después de que otro marcó `avisado = true` o durante la ventana de robo), reintentando cada `50 ms`.

---

### D. Visibilidad con `volatile`

1. **Primitivo `volatile boolean finalizado` y barreras de memoria**:
   * Como `finalizado` y `abortado` solo cambian de `false` a `true` al cierre y nunca necesitan operaciones de lectura-modificación-escritura (CAS), usar `private volatile boolean` en lugar de `AtomicBoolean` es directo y evita crear un objeto adicional en el Heap.
   * El modificador `volatile` garantiza visibilidad inmediata entre hilos para que todos los núcleos vean al instante cuando la búsqueda terminó o fue abortada.
