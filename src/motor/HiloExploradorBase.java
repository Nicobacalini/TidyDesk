package motor;

import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Deque;
import java.util.Set;

/**
 * Plantilla base que encapsula el recorrido DFS local y la coordinacion de robo
 * de trabajo.
 */
public abstract class HiloExploradorBase implements Runnable {

    private static final Set<Path> RUTAS_VIRTUALES_EXCLUIDAS = Set.of(
            Path.of("/proc"),
            Path.of("/sys"),
            Path.of("/dev"),
            Path.of("/run"));

    protected final SupervisorExploracion supervisor;
    private final int indiceCola;
    // El dueno opera en LIFO por adelante y los otros hilos roban en FIFO por atras
    private final Deque<Path> colaLocal;

    public HiloExploradorBase(SupervisorExploracion supervisor) {
        this.supervisor = supervisor;
        this.indiceCola = supervisor.registrarTrabajador();
        this.colaLocal = supervisor.obtenerColaLocal(this.indiceCola);
    }

    // Procesa cada archivo regular encontrado durante el recorrido.
    protected abstract void procesarArchivo(Path archivo);

    private boolean esRutaVirtualExcluida(Path ruta) {
        return RUTAS_VIRTUALES_EXCLUIDAS.contains(ruta);
    }

    /**
     * Recorre un directorio, ignora enlaces simbolicos, guarda subcarpetas y
     * procesa archivos.
     */
    private void procesarDirectorio(Path dir) {
        if (esRutaVirtualExcluida(dir)) {
            return;
        }

        boolean avisado = false;

        try (DirectoryStream<Path> flujo = Files.newDirectoryStream(dir)) {
            for (Path entrada : flujo) {
                if (supervisor.estaFinalizado()) {
                    return;
                }
                // Una sola syscall sin seguir symlinks; si falla el acceso o desaparece, se
                // omite
                BasicFileAttributes attrs;
                try {
                    attrs = Files.readAttributes(entrada, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                } catch (IOException | SecurityException ignorada) {
                    continue;
                }

                if (attrs.isSymbolicLink()) {
                    continue;
                }

                if (attrs.isDirectory()) {
                    if (!esRutaVirtualExcluida(entrada)) {
                        colaLocal.push(entrada);

                        // Despierta solo con >= 2 carpetas (peekFirst != peekLast evita size() O(N))
                        if (!avisado && supervisor.hayHilosSinTrabajo()
                                && colaLocal.peekFirst() != colaLocal.peekLast()) {
                            supervisor.despertarHilosSinTrabajo();
                            avisado = true;
                        }
                    }
                } else if (attrs.isRegularFile()) {
                    procesarArchivo(entrada);
                }
            }
        } catch (IOException | DirectoryIteratorException | SecurityException excepcion) {
            // permisos denegados o errores de E/S, registra la carpeta sin detener el hilo
            supervisor.registrarCarpetaInaccesible();
        }

        if (!avisado && supervisor.hayHilosSinTrabajo() && colaLocal.peekFirst() != colaLocal.peekLast()) {
            supervisor.despertarHilosSinTrabajo();
        }
    }

    /**
     * Ejecuta el ciclo del hilo: trabaja en su deque local o roba trabajo cuando se
     * vacia.
     */
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
            // para evitar que los demas hilos esperen indefinidamente si uno falla
            supervisor.abortar();
            throw excepcion;
        }
    }

}
