package menu;

import buscadorArchivo.BuscadorLocal;
import buscadorArchivo.ResultadoBusqueda;
import buscadorArchivo.TableroBusqueda;
import java.nio.file.FileSystems;
import java.util.Scanner;

/** Menu interactivo por consola para configurar y lanzar busquedas. */
public class Menu {

    private final BuscadorLocal buscador;

    public Menu() {
        buscador = new BuscadorLocal();
    }

    private void mostrarMenu() {
        System.out.println();
        System.out.println("========== TidyDesk ==========");
        System.out.println("1. Buscar archivo");
        System.out.println("0. Salir");
        System.out.print("Elige una opcion: ");
    }

    private void mostrarResultados(ResultadoBusqueda resultado) {
        for (String ruta : resultado.rutasEncontradas()) {
            System.out.printf("Coincidencia encontrada! -> %s%n", ruta);
        }

        System.out.println("\n------------------------------------------------");
        if (resultado.abortado()) {
            System.out.printf("Busqueda interrumpida en: %d ms%n", resultado.duracionMs());
            System.out.println("Advertencia: la busqueda se interrumpio y los resultados son parciales.");
        } else {
            System.out.printf("Busqueda completada en: %d ms%n", resultado.duracionMs());
        }
        System.out.printf("Total de coincidencias halladas: %d%n", resultado.rutasEncontradas().size());
        if (resultado.carpetasInaccesibles() > 0) {
            System.out.printf("Carpetas omitidas por permisos o E/S: %d%n",
                    resultado.carpetasInaccesibles());
        }
        System.out.println("------------------------------------------------");
    }

    private void ejecutarBusqueda(Scanner entrada) {
        System.out.print("Introduce el nombre del archivo: ");
        if (!entrada.hasNextLine()) {
            return;
        }
        String nombreArchivo = entrada.nextLine().trim();

        if (!TableroBusqueda.esNombreValido(nombreArchivo)) {
            System.out.println("El nombre del archivo no puede estar vacio ni contener '/'.");
            return;
        }

        System.out.print("Introduce la ruta inicial (presiona Enter para usar '/'): ");
        if (!entrada.hasNextLine()) {
            return;
        }
        String rutaInicial = entrada.nextLine().trim();

        if (rutaInicial.isEmpty()) {
            rutaInicial = FileSystems.getDefault().getRootDirectories().iterator().next().toString();
            System.out.println("No se especifico ruta. Buscando desde: " + rutaInicial);
        }

        try {
            System.out.printf("Buscando '%s' en '%s'...%n%n", nombreArchivo, rutaInicial);
            ResultadoBusqueda resultado = buscador.ejecutar(nombreArchivo, rutaInicial);
            mostrarResultados(resultado);
        } catch (IllegalArgumentException excepcion) {
            System.err.println("Error: " + excepcion.getMessage());
        }
    }

    public void iniciar() {
        try (Scanner entrada = new Scanner(System.in)) {
            boolean salir = false;

            while (!salir) {
                mostrarMenu();

                // Corta el ciclo si se cerro la entrada (Ctrl+D)
                if (!entrada.hasNextLine()) {
                    break;
                }
                String opcion = entrada.nextLine().trim();

                switch (opcion) {
                    case "1" -> ejecutarBusqueda(entrada);
                    case "0" -> salir = true;
                    default -> System.out.println("Opcion no valida. Elige 1 o 0.");
                }
            }

            System.out.println("Programa finalizado.");
        }
    }
}
