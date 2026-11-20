package buscadorArchivo;

import java.util.Collection;

/** Resultado inmutable de la busqueda. */
public record ResultadoBusqueda(
        Collection<String> rutasEncontradas,
        long duracionMs,
        boolean abortado,
        int carpetasInaccesibles) {
}
