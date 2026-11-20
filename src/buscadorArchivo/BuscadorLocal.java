package buscadorArchivo;

import motor.SupervisorExploracion;
import motor.ValidadorRuta;

import java.nio.file.Path;

/**
 * Fachada que coordina la ejecucion multihilo de una busqueda local de
 * archivos.
 */
public class BuscadorLocal {

    /**
     * Valida la ruta inicial, lanza los hilos y devuelve el resultado de la
     * busqueda.
     */
    public ResultadoBusqueda ejecutar(String archivoBuscado, String rutaInicialStr) {
        TableroBusqueda tablero = new TableroBusqueda(archivoBuscado);
        Path rutaInicial = ValidadorRuta.validarCarpeta(rutaInicialStr);

        int numHilos = Runtime.getRuntime().availableProcessors();

        SupervisorExploracion supervisor = new SupervisorExploracion(numHilos);
        supervisor.encolar(rutaInicial);

        Thread[] hilos = new Thread[numHilos];
        for (int i = 0; i < numHilos; i++) {
            hilos[i] = new Thread(new HiloTrabajadorBuscador(supervisor, tablero),
                    ("HiloTrabajadorBuscador-" + (i + 1)));
        }

        long tiempoInicio = System.currentTimeMillis();

        for (Thread hilo : hilos) {
            hilo.start();
        }

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

        long tiempoFin = System.currentTimeMillis();

        return new ResultadoBusqueda(
                tablero.getRutasEncontradas(),
                tiempoFin - tiempoInicio,
                supervisor.huboAborto(),
                supervisor.getCarpetasInaccesibles());
    }
}
