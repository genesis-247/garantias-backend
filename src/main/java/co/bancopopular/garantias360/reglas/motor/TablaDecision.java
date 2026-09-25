package co.bancopopular.garantias360.reglas.motor;

import java.util.List;
import java.util.Map;

/**
 * Tabla de decisión con política de primera coincidencia (FIRST): gana la primera fila cuyas
 * condiciones se cumplen todas. Una celda vacía, "-" o "*" significa "cualquier valor".
 *
 * <p>Sintaxis de condiciones: {@code valor}, {@code = valor}, {@code != valor}, {@code > n},
 * {@code >= n}, {@code < n}, {@code <= n}, {@code [a..b]} (rango inclusivo),
 * {@code in (A, B)} y {@code not in (A, B)}.</p>
 *
 * @param porDefecto resultado si ninguna fila aplica; null = la regla falla (nunca un valor silencioso)
 */
public record TablaDecision(List<Fila> filas, Salida porDefecto) {

    public record Fila(Map<String, String> condiciones, Salida salida) {
    }

    /** @param valor texto del resultado (número, booleano, método o idoneidad); motivo opcional. */
    public record Salida(String valor, String motivo) {
    }

    public record CasoPrueba(String nombre, Map<String, Object> entradas, String esperado) {
    }
}
