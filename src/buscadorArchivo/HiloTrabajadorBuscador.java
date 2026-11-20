package buscadorArchivo;

import motor.SupervisorExploracion;
import motor.HiloExploradorBase;
import java.nio.file.Path;

/**
 * Trabajador concreto que compara cada archivo regular contra el nombre
 * buscado.
 */
public class HiloTrabajadorBuscador extends HiloExploradorBase {

    private final TableroBusqueda tablero;
    private final String nombreObjetivo;

    public HiloTrabajadorBuscador(SupervisorExploracion supervisor, TableroBusqueda tablero) {
        super(supervisor);
        this.tablero = tablero;
        this.nombreObjetivo = tablero.getNombreObjetivo();
    }

    /**
     * Compara el nombre del archivo sin distinguir mayusculas y registra
     * coincidencias.
     */
    @Override
    protected void procesarArchivo(Path archivo) {
        Path nombreArchivo = archivo.getFileName();
        if (nombreArchivo != null && nombreArchivo.toString().equalsIgnoreCase(nombreObjetivo)) {
            tablero.registrarHallazgo(archivo);
        }
    }
}
