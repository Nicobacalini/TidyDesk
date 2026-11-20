# 5. Complejidad Algorítmica y Demostración Concurrente

## Índice de Contenidos

1. [Análisis Formal de Complejidad Temporal (*Big-O*)](#1-análisis-formal-de-complejidad-temporal-big-o)
2. [Demostración de Seguridad Concurrente](#2-demostración-de-seguridad-concurrente)

---

## 1. Análisis Formal de Complejidad Temporal (*Big-O*)

Gracias a `NOFOLLOW_LINKS` y `RUTAS_VIRTUALES_EXCLUIDAS` (`/proc`, `/sys`, `/dev`, `/run`), el recorrido no entra en ciclos por symlinks internos y visita cada entrada del árbol **una sola vez**.

* **En un solo hilo**: $\mathcal{O}(V)$, donde $V$ es el total de directorios y archivos inspeccionados.
* **Con $N$ hilos (*Work-Stealing*)**: $\mathcal{O}(V / N)$ en árboles equilibrados. En casos extremos (una única carpeta con casi todos los archivos o una cadena lineal muy profunda), la parte secuencial domina y el costo vuelve a $\mathcal{O}(V)$.

| Operación | Implementación | Complejidad |
| :--- | :--- | :---: |
| **Colas de trabajo** | `push`, `pollFirst`, `pollLast` (`ConcurrentLinkedDeque`) | $\mathcal{O}(1)$ |
| **Chequeo de $\ge 2$ carpetas** | `peekFirst() != peekLast()` | $\mathcal{O}(1)$ |
| **Leer un directorio** | `Files.newDirectoryStream(dir)` ($K$ entradas) | $\mathcal{O}(K)$ |
| **Metadatos, comparación y registro** | `readAttributes`, `equalsIgnoreCase`, `registrarHallazgo` | $\mathcal{O}(1)$ |
| **Copia final de resultados** | `List.copyOf(rutasEncontradas)` ($C$ hallazgos) | $\mathcal{O}(C)$ |
| **Intento de robo** | `intentarRobarDeCompanero()` | $\mathcal{O}(1)$ si hay colas con trabajo / $\mathcal{O}(N)$ peor caso |

---

## 2. Demostración de Seguridad Concurrente

### A. Seguridad

| Garantía | Cómo se cumple |
| :--- | :--- |
| **Sin terminación prematura** | Un hilo solo entra a `robarCarpeta()` cuando su `colaLocal` está vacía (`pollFirst() == null`). Decrementa `hilosSinTrabajo` **antes** de intentar robar y lo restaura si falla; así `todosOciosos()` solo da `true` cuando todas las colas están vacías y nadie está robando ni procesando. |
| **Sin carreras de datos** | `encolar()` ocurre antes de `hilo.start()`, y `hilo.join()` antes de leer resultados. Todo el estado compartido usa estructuras concurrentes (`ConcurrentLinkedDeque`, `ConcurrentLinkedQueue`, `AtomicInteger`) o `volatile`. |
| **Procesamiento único** | En un árbol sin ciclos, cada directorio se analisa una vez. |


### B. Prevención de *Deadlock* (Condiciones de Coffman)

Las colas y contadores son *lock-free* (sin cerrojos). El único lock del sistema es `monitor` (para dormir y despertar hilos ociosos), donde el *deadlock* es imposible porque se rompen **2 de las 4** condiciones:

* **Sin retención y espera:** dentro de `synchronized (monitor)` no se pide ningún otro recurso y `wait(50 ms)` suelta el lock al suspenderse.
* **Sin espera circular:** al haber un único lock (`monitor`), no pueden formarse ciclos entre dos hilos esperando recursos cruzados.

### C. Vivacidad (*Liveness*) e Interrupciones

| Riesgo | Cómo se evita |
| :--- | :--- |
| **Bloqueo entre trabajadores** | Las colas (`ConcurrentLinkedDeque`) y contadores (`AtomicInteger`) usan CAS (*lock-free*), ningún hilo frena a otro al trabajar o robar. |
| **Contención al robar** | `ThreadLocalRandom` elige una cola inicial al azar para que no todos intenten robarle al mismo hilo. |
| **Avisos perdidos (*lost wakeup*)** | Si un `notifyAll()` ocurre justo antes de dormir, `monitor.wait(50 ms)` despierta al hilo como máximo a los `50 ms` para reintentar. |
| **Cuelgues por error o interrupción** | Ante fallo o `InterruptedException`, los hilos llaman a `supervisor.abortar()`, `robarCarpeta()` restaura `hilosSinTrabajo` en `try / finally` y `BuscadorLocal` completa todos los `join()` antes de salir. |