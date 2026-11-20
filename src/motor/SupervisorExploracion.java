package motor;

import java.nio.file.Path;
import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Coordina el robo de trabajo entre hilos exploradores usando una cola por
 * trabajador.
 */
public class SupervisorExploracion {
    // Tiempo maximo que espera un hilo sin trabajo antes de reintentar
    private static final long ESPERA_POLL_MS = 50;

    private final Deque<Path>[] colasDeHilos;
    private final AtomicInteger siguienteCola = new AtomicInteger(0);
    private final int totalHilos;

    private final AtomicInteger hilosSinTrabajo = new AtomicInteger(0);
    private volatile boolean finalizado = false;

    private final Object monitor = new Object();

    private volatile boolean abortado = false;

    private final AtomicInteger carpetasInaccesibles = new AtomicInteger(0);

    /** Inicializa el supervisor creando una cola vacia para cada trabajador. */

    // Java no permite arrays genericos
    @SuppressWarnings("unchecked")
    public SupervisorExploracion(int totalHilos) {
        if (totalHilos <= 0) {
            throw new IllegalArgumentException("El total de hilos debe ser mayor a 0: " + totalHilos);
        }
        this.totalHilos = totalHilos;
        this.colasDeHilos = new Deque[totalHilos];
        for (int i = 0; i < totalHilos; i++) {
            colasDeHilos[i] = new ConcurrentLinkedDeque<>();
        }
    }

    /** Registra un nuevo trabajador y devuelve su numero de cola. */
    public int registrarTrabajador() {
        int indice = siguienteCola.getAndIncrement();
        if (indice >= totalHilos) {
            throw new IllegalStateException(
                    "Se crearon mas trabajadores (" + (indice + 1) + ") que el total previsto (" + totalHilos + ")");
        }
        return indice;
    }

    public Deque<Path> obtenerColaLocal(int indice) {
        return colasDeHilos[indice];
    }

    /** Guarda la carpeta inicial en la primera cola antes de arrancar los hilos. */
    public void encolar(Path carpetaInicial) {
        colasDeHilos[0].push(carpetaInicial);
    }

    /**
     * Intenta robar una carpeta del final de la cola de otro hilo; si no hay
     * trabajo disponible, duerme hasta que aparezca mas trabajo o todo termine.
     */
    public Path robarCarpeta(int indiceLadron) throws InterruptedException {
        hilosSinTrabajo.incrementAndGet();
        boolean contadoComoOcioso = true;
        try {
            while (true) {
                if (finalizado) {
                    return null;
                }
                if (todosOciosos()) {
                    terminar();
                    return null;
                }

                // Se descuenta antes de robar para evitar terminar con carpetas en transito
                hilosSinTrabajo.decrementAndGet();
                contadoComoOcioso = false;
                Path robada = intentarRobarDeCompanero(indiceLadron);
                if (robada != null) {
                    return robada;
                }
                hilosSinTrabajo.incrementAndGet();
                contadoComoOcioso = true;

                if (todosOciosos()) {
                    terminar();
                    return null;
                }
                dormirHastaHayaTrabajo();
            }
        } finally {
            if (contadoComoOcioso) {
                hilosSinTrabajo.decrementAndGet();
            }
        }
    }

    /**
     * Recorre las demas colas desde una posicion al azar e intenta sacar una
     * carpeta del fondo.
     */
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

    private void dormirHastaHayaTrabajo() throws InterruptedException {
        synchronized (monitor) {
            if (!finalizado) {
                monitor.wait(ESPERA_POLL_MS);
            }
        }
    }

    private boolean todosOciosos() {
        return hilosSinTrabajo.get() == totalHilos;
    }

    private void terminar() {
        finalizado = true;
        despertarHilosSinTrabajo();
    }

    /** Corta la exploracion inmediatamente si ocurre un error o interrupcion. */
    public void abortar() {
        abortado = true;
        terminar();
    }

    public boolean estaFinalizado() {
        return finalizado;
    }

    public boolean hayHilosSinTrabajo() {
        return hilosSinTrabajo.get() > 0;
    }

    public void despertarHilosSinTrabajo() {
        synchronized (monitor) {
            monitor.notifyAll();
        }
    }

    public boolean huboAborto() {
        return abortado;
    }

    public void registrarCarpetaInaccesible() {
        carpetasInaccesibles.incrementAndGet();
    }

    public int getCarpetasInaccesibles() {
        return carpetasInaccesibles.get();
    }
}
