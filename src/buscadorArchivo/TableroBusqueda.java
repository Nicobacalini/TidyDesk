package buscadorArchivo;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Registro compartido y thread-safe donde los trabajadores almacenan
 * las rutas de los archivos coincidentes.
 */
public class TableroBusqueda {
    private final String nombreObjetivo;
    private final Queue<String> rutasEncontradas = new ConcurrentLinkedQueue<>();

    public static boolean esNombreValido(String nombreObjetivo) {
        return nombreObjetivo != null && !nombreObjetivo.isBlank() && !nombreObjetivo.contains("/");
    }

    public TableroBusqueda(String nombreObjetivo) {
        if (!esNombreValido(nombreObjetivo)) {
            throw new IllegalArgumentException("El nombre del archivo buscado no es valido.");
        }
        this.nombreObjetivo = nombreObjetivo;
    }

    public void registrarHallazgo(Path archivo) {
        rutasEncontradas.add(archivo.toString());
    }

    /**
     * Devuelve una copia inmutable de las rutas encontradas, aislada de cambios
     * futuros en la cola y con size() en O(1).
     */
    public Collection<String> getRutasEncontradas() {
        return List.copyOf(rutasEncontradas);
    }

    public String getNombreObjetivo() {
        return nombreObjetivo;
    }
}
