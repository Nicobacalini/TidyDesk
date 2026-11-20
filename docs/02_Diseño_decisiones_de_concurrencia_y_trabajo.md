# 2. Diseño, Decisiones de Concurrencia y Work-Stealing

## Índice de Contenidos

1. [Cómo diseñé el rol del Supervisor frente a los Hilos Trabajadores](#1-cómo-diseñé-el-rol-del-supervisor-frente-a-los-hilos-trabajadores)
2. [Modelo de Memoria: Heap vs. Stack, Referencias y Concurrencia Lock-Free](#2-modelo-de-memoria-heap-vs-stack-referencias-y-concurrencia-lock-free)
3. [Resumen de Estructuras de Datos y por qué las elegí](#3-resumen-de-estructuras-de-datos-y-por-qué-las-elegí)
4. [Algoritmos y Geometría del Disco: ¿Por qué DFS (Pila) y Deque de Doble Punta?](#4-algoritmos-y-geometría-del-disco-por-qué-dfs-pila-y-deque-de-doble-punta)
5. [Sincronización del Hilo Principal (`join`) y Mecánica del robo de trabajo](#5-sincronización-del-hilo-principal-join-y-mecánica-del-robo-de-trabajo)


## 1. Cómo diseñé el rol del Supervisor frente a los Hilos Trabajadores

Para modelarlo pensé en un capataz y obreros con mochilas de doble punta:
* **Los Hilos Trabajadores** abren carpetas en el disco y operan (insertan y extraen) por **ARRIBA** de su mochila (`push` / `pollFirst`), limitando el acceso al extremo inferior a la inspección de solo lectura `peekLast()`.
* **El Supervisor** conoce todas las mochilas (`colasDeHilos`), coordina el robo por **ABAJO** (`pollLast`) cuando un obrero se queda sin trabajo, y decreta el fin (`finalizado = true`) cuando todos están ociosos.

### Matriz de Distribución de Responsabilidades

| Responsabilidad en el Código | Trabajador | Supervisor | Por qué lo diseñé así |
| :--- | :---: | :---: | :--- |
| **Lectura física del disco (`newDirectoryStream` + `readAttributes`)** | **SÍ** | NO | Como el acceso a disco es intensivo en I/O, necesitaba que los N hilos ejecutaran las syscalls en paralelo. |
| **Ignorar symlinks (`attrs.isSymbolicLink()`) y rutas virtuales** | **SÍ** | NO | Lo filtro localmente en cada hilo antes de apilar (`push`) para evitar ciclos infinitos o nodos inútiles en las colas. |
| **Comparar el nombre (`equalsIgnoreCase`)** | **SÍ** | NO | Es la lógica de negocio específica de la subclase obrera. |
| **Trabajar por arriba de su cola (`push` / `pollFirst`)** | **SÍ** | NO | Operación sin bloqueos $\mathcal{O}(1)$ que preserva el recorrido DFS y mantiene calientes las líneas de caché L1/L2 del núcleo. |
| **Robar por abajo de otra cola (`pollLast`)** | *Solicita* | **Ejecuta** | El supervisor busca una carpeta en el fondo de las colas vecinas y se la entrega al hilo ocioso (ejecutándose sobre ese mismo hilo, sin frenar a los demás). |
| **Monitorear ociosidad (`hilosSinTrabajo`)** | *Consulta* | **SÍ** | El supervisor centraliza el conteo atómico (`AtomicInteger`) y el trabajador consulta `hayHilosSinTrabajo()` para decidir cuándo emitir avisos. |
| **Decretar el fin absoluto de la búsqueda** | *Consulta* | **Decide** | Solo el supervisor posee la visión global de los contadores para confirmar el fin; ante errores o interrupciones, los trabajadores o `BuscadorLocal` solicitan el corte temprano mediante `abortar()`. |

---

## 2. Modelo de Memoria: Heap vs. Stack, Referencias y Concurrencia Lock-Free

### ¿Por qué diseñé `TableroBusqueda` inyectando la referencia y cacheando `nombreObjetivo`?

Pensé esta estructura de memoria compartida entre los hilos para aprovechar un único tablero central, evitar duplicaciones y reducir indirecciones en la comparación de archivos:

#### 1. Una única instancia física en memoria dinámica (Heap)
En `BuscadorLocal` instancio el tablero una sola vez antes de lanzar los hilos:
```java
TableroBusqueda tablero = new TableroBusqueda(archivoBuscado);
```
Esto reserva **una única instancia de `TableroBusqueda` en el Heap**.

#### 2. Paso de referencias por valor en el constructor sin duplicar el objeto
Cuando inicializo los trabajadores en el bucle, Java pasa **las referencias de objeto por valor** a cada instancia de `HiloTrabajadorBuscador` le llega una copia de la referencia, sin duplicar el objeto `TableroBusqueda` en el Heap. Así, todos los trabajadores apuntan exactamente a la misma instancia compartida.

#### 3. Publicación segura en el Heap y referencia directa a `nombreObjetivo`

```java
public class HiloTrabajadorBuscador extends HiloExploradorBase {
    private final TableroBusqueda tablero;
    private final String nombreObjetivo;

    public HiloTrabajadorBuscador(SupervisorExploracion supervisor, TableroBusqueda tablero) {
        super(supervisor);
        this.tablero = tablero;                            // Referencia compartida al tablero
        this.nombreObjetivo = tablero.getNombreObjetivo(); // Referencia directa al String objetivo
    }
}
```

* **¿Por qué guardo `this.tablero = tablero` en un campo private final?**: El parámetro tablero existe solo en el *stack frame* del constructor. Al guardarlo en un campo private final dentro del objeto trabajador en el Heap, garantizo una publicación segura (*safe publication* según el Modelo de Memoria de Java) para que esté disponible durante toda la ejecución del hilo. Además, gracias a que el *Garbage Collector* gestiona la memoria por alcanzabilidad (*reachability*), el objeto TableroBusqueda permanece vivo en el Heap mientras algún trabajador activo o BuscadorLocal conserve esa referencia.
* **¿Por qué guardo `this.nombreObjetivo = tablero.getNombreObjetivo()`?**: El `String` inmutable con el nombre buscado ya reside en el Heap; guardar su referencia directa en un campo `private final` del trabajador evita invocar `tablero.getNombreObjetivo()` y atravesar la indirección hacia `tablero` en cada archivo revisado, accediendo al tablero compartido únicamente cuando aparece una coincidencia (`registrarHallazgo`).


---

## 3. Resumen de Estructuras de Datos y por qué las elegí

| Estructura | Ubicación | Por qué la elegí |
| :--- | :--- | :--- |
| `ConcurrentLinkedDeque<Path>` | Mochila de cada hilo y arreglo del supervisor | Permite que el dueño trabaje por **arriba** y los compañeros roben por **abajo** de forma concurrente y sin cerrojos. |
| `Deque<Path>[]` | `colasDeHilos` en `SupervisorExploracion` | Un arreglo fijo directo en memoria, sin envoltorios extra como `ArrayList` (requiere `@SuppressWarnings("unchecked")` en el constructor porque Java no permite `new Deque<Path>[]`; es seguro al inicializar cada posición con `ConcurrentLinkedDeque<Path>`). |
| `ConcurrentLinkedQueue<String>` | `rutasEncontradas` en `TableroBusqueda` | Cola concurrente donde los hilos guardan las rutas encontradas en memoria. |
| `AtomicInteger` | Contadores (`siguienteCola`, `hilosSinTrabajo`, `carpetasInaccesibles`) | Modificaciones numéricas atómicas y seguras entre hilos sin usar `synchronized` en los contadores. |
| `volatile boolean` | `finalizado` y `abortado` en `SupervisorExploracion` | `finalizado` avisa la detención a todos los trabajadores (en fin normal o aborto); `abortado` registra si el cierre fue irregular para que `BuscadorLocal` lo reporte vía `huboAborto()`. |
| `Object` (`monitor` + `ESPERA_POLL_MS = 50`) | `SupervisorExploracion` (`dormirHastaHayaTrabajo`, `despertarHilosSinTrabajo`) | Monitor privado usado únicamente con `synchronized (monitor)`, `wait(50)` y `notifyAll()` para suspender y despertar hilos ociosos (con timeout de seguridad de 50 ms ante avisos previos), dejando el apilado y robo sin bloqueos. |
| `Set<Path>` (`Set.of`) | `RUTAS_VIRTUALES_EXCLUIDAS` en `HiloExploradorBase` | Conjunto inmutable para descartar al instante carpetas virtuales de Linux (`/proc`, `/sys`, `/dev`, `/run`). |



## 4. Algoritmos y Geometría del Disco: ¿Por qué DFS (Pila) y Deque de Doble Punta?

### A. La elección de `Deque` (`ConcurrentLinkedDeque`) frente a la clase `Stack`

Al momento de elegir la estructura para las carpetas pendientes, surgió en clase con el profesor la comparación entre `Stack` y `Deque`. Investigar a fondo la definición de *Double-Ended Queue* (cola de dos puntas) fue justamente el disparador que me hizo dar el salto, me di cuenta de que si tenía dos extremos libres, no necesitaba una cola central intermedia ni hacer que el hilo ocupado frene a repartir. Podía usar un extremo para que el dueño trabaje y el otro para que los demás le roben.

### B. Por qué Búsqueda en Profundidad (DFS / Pila) ahorra memoria frente a Anchura (BFS / Cola)


* **El problema de buscar en anchura (BFS / Cola)**: Si un hilo explorara por niveles, tendría que cargar en memoria todas las carpetas de un nivel entero antes de bajar al siguiente, haciendo que la cola explote en tamaño y gaste cientos de megabytes de RAM.
* **La ventaja de buscar en profundidad (DFS / Pila)**: Al avanzar hasta el fondo de una rama y luego retroceder, el hilo solo mantiene en su mochila las subcarpetas pendientes de los niveles a lo largo de la rama vertical activa. Esto mantiene el consumo de memoria RAM plano y mínimo, sin importar que el disco tenga millones de archivos.

## 5. Sincronización del Hilo Principal (`join`) y Mecánica del robo de trabajo

### A. Sincronización del Hilo Principal (`join`) y Manejo de Interrupciones

En la fachada `BuscadorLocal`, una vez encendidos los hilos, el hilo principal espera a que todos finalicen antes de mostrar los resultados:

```java
boolean interrumpido = false;
for (Thread hilo : hilos) {
    while (true) {
        try {
            hilo.join();
            break;
        } catch (InterruptedException excepcion) {
            if (!interrumpido) {
                interrumpido = true;
                supervisor.abortar();
                for (Thread t : hilos) {
                    t.interrupt();
                }
            }
        }
    }
}
if (interrumpido) {
    Thread.currentThread().interrupt();
}
```

* **¿Por qué es indispensable `hilo.join()`?**: Los trabajadores corren en segundo plano. Sin `join()`, el hilo principal seguiría de largo sin esperar, mostraría 0 resultados y volvería al menú mientras los hilos siguen leyendo el disco de fondo.
* **Manejo de interrupción (`InterruptedException`)**: Si el hilo principal es interrumpido mientras espera, marca `interrumpido = true`, notifica una única vez a todos los trabajadores mediante `supervisor.abortar()` e interrumpe cada hilo (`t.interrupt()`), pero reintenta `hilo.join()` en un bucle `while` hasta confirmar que cada trabajador terminó realmente. Recién al finalizar el bucle restaura el estado de interrupción con `Thread.currentThread().interrupt()`, evitando que los `join()` restantes salten de inmediato y dejen hilos ejecutándose en segundo plano.

---

### B. Mecánica del Bucle de Exploración (`run`) y Aviso Temprano

Cada hilo ejecuta de forma autónoma su método `run()` consumiendo primero de su propia mochila y saliendo a robar solo cuando se queda vacío:

```java
@Override
public final void run() {
    try {
        while (!supervisor.estaFinalizado()) {
            Path carpeta = colaLocal.pollFirst();
            if (carpeta != null) {
                procesarDirectorio(carpeta);
                continue;
            }

            Path carpetaRobada = supervisor.robarCarpeta(indiceCola);
            if (carpetaRobada != null) {
                procesarDirectorio(carpetaRobada);
            }
        }
    } catch (InterruptedException excepcion) {
        Thread.currentThread().interrupt();
        supervisor.abortar();
    } catch (RuntimeException | Error excepcion) {
        // Fail-fast: evita que los demas hilos esperen indefinidamente si uno falla
        supervisor.abortar();
        throw excepcion;
    }
}
```

Al recorrer una carpeta y apilar nuevas subcarpetas, apliqué tres optimizaciones clave:

* **Cancelación rápida (`if (supervisor.estaFinalizado()) return;`)**: Si un hilo está recorriendo un directorio grande mientras otro hilo aborta la búsqueda, comprobar `supervisor.estaFinalizado()` en cada entrada permite salir del `try-with-resources` y cerrar el `DirectoryStream` de inmediato (Aunque implica leer un volatile en cada iteración, su costo es despreciable y evita complejizar el bucle con chequeos por lotes).
* **Aviso temprano al encontrar carpetas**: En lugar de esperar a leer las 20.000 entradas de un directorio gigante para avisar que hay trabajo, dentro de `procesarDirectorio` el hilo dispara `supervisor.despertarHilosSinTrabajo()` en cuanto `colaLocal` acumula al menos dos carpetas, siempre que `!avisado && supervisor.hayHilosSinTrabajo()` (como máximo una vez dentro del bucle). Al cerrar el `DirectoryStream` se repite la misma comprobación por si algún compañero quedó ocioso mientras se iteraba.
* **Chequeo (`peekFirst() != peekLast()`) en $\mathcal{O}(1)$**: `ConcurrentLinkedDeque` no almacena un contador de tamaño fijo; invocar `.size()` obliga a recorrer nodo por nodo toda la lista enlazada en tiempo $\mathcal{O}(N)$. Para evitarlo, comparo `peekFirst() != peekLast()` en $\mathcal{O}(1)$: Aunque las dos lecturas consecutivas no son atómicas, este enfoque permite detectar rápidamente si hay trabajo disponible para robar sin penalizar el bucle. En el peor caso, si un ladrón extrae el único elemento justo entre ambas lecturas, solo se produce una notificación extra segura.

#### En `SupervisorExploracion`: Robo aleatorio desde el fondo (`pollLast`) omitiendo la propia cola
```java
private Path intentarRobarDeCompanero(int indiceLadron) {
    if (totalHilos <= 1) {
        return null;
    }
    int inicio = ThreadLocalRandom.current().nextInt(totalHilos);
    for (int i = 0; i < totalHilos; i++) {
        int idx = (inicio + i) % totalHilos;
        if (idx == indiceLadron) {
            continue;
        }
        Path robada = colasDeHilos[idx].pollLast();
        if (robada != null) {
            return robada;
        }
    }
    return null;
}
```

#### ¿Por qué robar exactamente 1 carpeta por vez (`pollLast()`) en lugar de varias o la mitad?

1. **Evitar acaparar ramas grandes**: En el fondo de la cola están las carpetas más viejas y cercanas a la raíz (como `Documentos` o `Proyectos`). Cada una no es un archivo suelto, sino la entrada a un subárbol con cientos de carpetas adentro. Si el primer ladrón se llevara varias, acapararía ramas enteras para él solo y dejaría a los demás hilos sin nada. Robando de a 1 todos consiguen una rama grande.
2. **Calcular "la mitad" costaría $\mathcal{O}(N)$**: Como `.size()` es $\mathcal{O}(N)$ en `ConcurrentLinkedDeque` (explicado arriba), particionar la cola a la mitad penalizaría cada robo, mientras que extraer una sola con `pollLast()` es $\mathcal{O}(1)$.
3. **Robar en memoria es baratísimo comparado con leer el disco**: Sacar una carpeta de la memoria de un compañero toma nanosegundos, mientras que leer los archivos de esa carpeta en el disco toma miles de veces más tiempo. Como robar cuesta casi nada, no hay necesidad de robar en lotes.

### C. Protocolo de Detección de Terminación y Consenso Global

En este sistema que la cola local de un hilo se vacíe no significa que la búsqueda haya terminado, otros hilos pueden estar procesando subárboles que generarán nuevos directorios. Para detectar el cierre real de forma segura, `SupervisorExploracion.robarCarpeta(int indiceLadron)` coordina el consenso mediante la condición global `todosOciosos()` ($\text{hilosSinTrabajo} == \text{totalHilos}$):

1. **Registro de ociosidad protegido por `try / finally`**: Un trabajador entra a `robarCarpeta()` únicamente cuando su `colaLocal` está vacía, incrementa `hilosSinTrabajo` y marca la bandera local `contadoComoOcioso = true` (el bloque `finally` garantiza descontar `hilosSinTrabajo` al salir del método, incluso ante `InterruptedException`).
2. **Chequeo inicial de cierre**: Al comenzar cada iteración del bucle, verifica `if (finalizado) return null;` y, si `todosOciosos()` ya se cumple, ejecuta de inmediato `terminar()` (`finalizado = true` + `despertarHilosSinTrabajo()`) y retorna `null`.
3. **Descuento previo al robo**: Si todavía hay hilos activos o intentando robar, el hilo se descuenta (`hilosSinTrabajo.decrementAndGet()` y `contadoComoOcioso = false`) antes de invocar `intentarRobarDeCompanero(indiceLadron)` eliminando la ventana de carpetas "en tránsito"; si consigue una carpeta, la retorna directamente.
4. **Cierre directo o suspensión pasiva tras un robo fallido**: Si el intento de robo devuelve `null`, el hilo vuelve a sumarse a `hilosSinTrabajo` (`contadoComoOcioso = true`) y reevalúa `todosOciosos()` si alcanzó `totalHilos`, ejecuta directamente `terminar(); return null;` para cerrar la exploración sin una vuelta extra; de lo contrario, se suspende en `dormirHastaHayaTrabajo()` (`monitor.wait(ESPERA_POLL_MS)` con un límite de `50 ms`) hasta recibir un aviso o agotar el tiempo de seguridad.

La demostración formal de los 3 invariantes de seguridad que garantizan que este protocolo nunca produzca terminación prematura, carreras de datos ni procesamiento duplicado se encuentra en [05_complejidad_alg_y_Demostración_concurrente.md](05_complejidad_alg_y_Demostración_concurrente.md#a-seguridad).

---