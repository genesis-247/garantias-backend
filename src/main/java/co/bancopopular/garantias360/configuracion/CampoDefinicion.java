package co.bancopopular.garantias360.configuracion;

import java.math.BigDecimal;
import java.util.List;

/**
 * Campo personalizado de un tipo de garantía (RF-1603, RF-1604).
 *
 * @param obligatorioDesde macroestado desde el cual el campo es obligatorio; null = desde el registro
 * @param llave            forma parte de la llave natural para detectar duplicados (RF-0406)
 */
public record CampoDefinicion(
        String codigo,
        String etiqueta,
        TipoCampo tipo,
        boolean obligatorio,
        String obligatorioDesde,
        boolean llave,
        String grupo,
        int orden,
        String ayuda,
        BigDecimal minimo,
        BigDecimal maximo,
        String patron,
        List<Opcion> opciones) {

    public enum TipoCampo { TEXTO, TEXTO_LARGO, NUMERO, MONEDA, FECHA, BOOLEANO, LISTA, LISTA_MULTIPLE }

    /** Opción con identificador estable: se inactiva, nunca se borra (Proceder, migración 0011). */
    public record Opcion(String id, String etiqueta, boolean activo) {
    }
}
