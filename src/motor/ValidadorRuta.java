package motor;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/** Valida que una ruta sirva como punto de partida de una exploracion. */
public final class ValidadorRuta {

    private ValidadorRuta() {
    }

    /**
     * Verifica que la ruta sea un directorio existente y legible; lanza
     * IllegalArgumentException si no lo es.
     */
    public static Path validarCarpeta(String ruta) {
        if (ruta == null || ruta.isBlank()) {
            throw new IllegalArgumentException("La ruta inicial no puede ser nula ni estar vacia.");
        }
        try {
            Path carpeta = Path.of(ruta).toAbsolutePath().normalize();

            if (!Files.isDirectory(carpeta)
                    || !Files.isReadable(carpeta) || !Files.isExecutable(carpeta)) {
                throw new IllegalArgumentException(
                        "La ruta inicial no es valida o no tienes permisos de lectura: " + carpeta);
            }
            return carpeta;
        } catch (InvalidPathException excepcion) {
            throw new IllegalArgumentException("La ruta ingresada no tiene un formato valido: " + ruta);
        }
    }
}
