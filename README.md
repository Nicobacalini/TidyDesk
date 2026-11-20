# TidyDesk Motor de Búsqueda de Archivos Multihilo

**TidyDesk** es un motor de búsqueda y exploración de archivos en consola, que diseñé desde cero. Su objetivo es recorrer directorios a máxima velocidad aplicando conceptos de Arquitectura de Computadoras y Sistemas Operativos como aprovechar los núcleos del procesador evitando cuellos de botella por sincronización y mantener un consumo de memoria acotado.

Para evitar la contención de una cola compartida única y evitar que los hilos ocupados pierdan tiempo repartiendo tareas, TidyDesk resuelve el recorrido de directorios mediante  un mecanismo que diseñe que trata de robo de trabajo y sin cerrojos: cada hilo dueño opera por arriba de su propia cola en recorrido en profundidad (DFS), mientras que los hilos ociosos roban trabajo por el fondo a un compañero. De esta forma, el hilo activo mantiene calientes sus líneas de caché sin detenerse a coordinar y los ladrones toman las ramas más cercanas a la raíz. La evolución completa de este diseño desde `v0` hasta `v3` se detalla en [4. Evolución de Diseño (v0 a v3) y el Porqué de cada Decisión](docs/04_evolucion_y_el_porque.md).

---

## Decisiones y por qué lo diseñé así

1. **Algoritmo Asimétrico Libre de Cerrojos (`ConcurrentLinkedDeque<Path>`)**:
   * **El hilo dueño opera por arriba (`pollFirst`) en $\mathcal{O}(1)$**: Mantiene calientes las líneas de caché L1/L2 gracias a la localidad espacial y temporal del recorrido en profundidad y manteniendo en memoria solo la rama activa (además de las coincidencias encontradas).
   * **Los hilos libres roban por abajo (`pollLast`) en $\mathcal{O}(1)$**: Extraen la carpeta más antigua del compañero, que corresponde a las carpetas mas cerca de la raíz del disco, minimizando la cantidad de robos y evitando contención con la cabeza de la cola.
2. **Espera sin desperdiciar CPU**:
   * **Suspensión segura:** si al quedarse sin trabajo un hilo no logra robar de ninguna cola compañera, se marca inactivo y se suspende (`monitor.wait(ESPERA_POLL_MS)`) hasta que otro compañero avise que apiló trabajo nuevo (`notifyAll()`), consumiendo 0 % de CPU en reposo (con un sondeo de seguridad cada 50 ms).
3. **Eficiencia a Nivel de Sistema Operativo y Kernel (Java NIO.2)**:
   * En lugar de `File.listFiles()`, que carga todo el directorio de golpe en el heap y hace múltiples consultas al sistema operativo por archivo, uso `Files.newDirectoryStream` (lectura en *streaming*) junto con `Files.readAttributes(..., LinkOption.NOFOLLOW_LINKS)`, resolviendo tipo y atributos en **una sola *syscall* por entrada**.
   * Descarte de enlaces simbólicos antes de apilar (previniendo ciclos infinitos) y exclusión de rutas virtuales y volátiles de Linux (`/proc`, `/sys`, `/dev`, `/run`).
4. **Cola de resultados sin bloqueos (Patrón *Blackboard*)**:
   * Los hilos exploradores dejan las rutas encontradas en una `ConcurrentLinkedQueue<String>` sin usar cerrojos y siguen buscando de inmediato.

---

## Demostración de Uso en Consola

```text
========== TidyDesk ==========
1. Buscar archivo
0. Salir
Elige una opcion: 1
Introduce el nombre del archivo: 01_arquitectura_y_patrones.md
Introduce la ruta inicial (presiona Enter para usar '/'): 
No se especifico ruta. Buscando desde: /
Buscando '01_arquitectura_y_patrones.md' en '/'...

Coincidencia encontrada! -> /home/nico/Escritorio/TidyDesk-main/docs/01_arquitectura_y_patrones.md

------------------------------------------------
Busqueda completada en: 204 ms
Total de coincidencias halladas: 1
------------------------------------------------
```

---

## Estructura del Proyecto

```text
TidyDesk-main/
├── README.md                  # Resumen y guía rápida de ejecución
├── docs/                      # Documentación técnica
└── src/                       # Código
    ├── Main.java              # Punto de entrada de la aplicación
    ├── menu/
    │   └── Menu.java          # Interfaz interactiva CLI
    ├── motor/                 # Motor genérico y extensible de exploración multihilo
    │   ├── HiloExploradorBase.java      # Clase abstracta base (Template Method + DFS local + NIO.2)
    │   ├── SupervisorExploracion.java   # Mediador y árbitro de parada
    │   └── ValidadorRuta.java           # Valida que la carpeta existe y permisos
    └── buscadorArchivo/       # Especialización concreta de búsqueda por nombre
        ├── BuscadorLocal.java           # Fachada que orquesta el ciclo de vida y join()
        ├── HiloTrabajadorBuscador.java  # Trabajador que compara cada archivo y anota las coincidencias
        ├── ResultadoBusqueda.java       # Record que almacena el resultado de la búsqueda
        └── TableroBusqueda.java         # Pizarra concurrente
```

---

## Requisitos, Compilación y Ejecución

* **Requisitos**: **Java JDK 16 o superior** (utiliza `record` y Java NIO.2). Sin dependencias externas.

### En Linux:

```bash
# 1. Crear el directorio de salida y compilar todos los paquetes
mkdir -p bin
javac -d bin src/Main.java src/menu/Menu.java src/motor/*.java src/buscadorArchivo/*.java

# 2. Ejecutar TidyDesk
java -cp bin Main
```

---

## Documentación Técnica Detallada

Consulta las siguientes guías para profundizar en las decisiones de diseño, los fundamentos de **Arquitectura de Computadoras y Sistemas Operativos**, la evolución del algoritmo (v0 a v3) y las demostraciones formales de concurrencia:

- [1. Arquitectura de Software y Patrones de Diseño](docs/01_arquitectura_y_patrones.md)
- [2. Diseño, Decisiones de Concurrencia y Work-Stealing](docs/02_Diseño_decisiones_de_concurrencia_y_trabajo.md)
- [3. Decisiones de Optimización: SO, Caché y Concurrencia](docs/03_decisiones_de_opti_SO_cache_Concurrencia.md)
- [4. Evolución de Diseño (v0 a v3) y el Porqué de cada Decisión](docs/04_evolucion_y_el_porque.md)
- [5. Complejidad Algorítmica y Demostración Concurrente](docs/05_complejidad_alg_y_Demostración_concurrente.md)