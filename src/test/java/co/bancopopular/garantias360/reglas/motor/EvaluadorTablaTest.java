package co.bancopopular.garantias360.reglas.motor;

import co.bancopopular.garantias360.reglas.motor.TablaDecision.CasoPrueba;
import co.bancopopular.garantias360.reglas.motor.TablaDecision.Fila;
import co.bancopopular.garantias360.reglas.motor.TablaDecision.Salida;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvaluadorTablaTest {

    private final EvaluadorTabla evaluador = new EvaluadorTabla();

    private static Fila fila(Map<String, String> condiciones, String valor) {
        return new Fila(condiciones, new Salida(valor, null));
    }

    private final TablaDecision haircut = new TablaDecision(List.of(
            fila(Map.of("tipo", "in (DEPOSITO_CDT, DEPOSITO_AHORRO)", "moneda", "COP"), "0"),
            fila(Map.of("tipo", "in (DEPOSITO_CDT, DEPOSITO_AHORRO)"), "0.10"),
            fila(Map.of("tipo", "HIPOTECA_VIVIENDA", "mesesAntiguedadValoracion", "> 36"), "0.40"),
            fila(Map.of("tipo", "HIPOTECA_VIVIENDA"), "0.30"),
            fila(Map.of("mesesAntiguedadValoracion", "[0..12]"), "0.35")),
            new Salida("0.50", "Sin fila específica"));

    @Test
    void primera_coincidencia_gana() {
        var r = evaluador.evaluar(TipoRegla.HAIRCUT, haircut, Map.of("tipo", "DEPOSITO_CDT", "moneda", "COP"));
        assertThat(r.valor()).isEqualTo("0");
        assertThat(r.fila()).isEqualTo(1);
    }

    @Test
    void comparaciones_numericas_y_rangos() {
        assertThat(evaluador.evaluar(TipoRegla.HAIRCUT, haircut,
                Map.of("tipo", "HIPOTECA_VIVIENDA", "mesesAntiguedadValoracion", 40)).valor()).isEqualTo("0.40");
        assertThat(evaluador.evaluar(TipoRegla.HAIRCUT, haircut,
                Map.of("tipo", "HIPOTECA_VIVIENDA", "mesesAntiguedadValoracion", 12)).valor()).isEqualTo("0.30");
        assertThat(evaluador.evaluar(TipoRegla.HAIRCUT, haircut,
                Map.of("tipo", "OTRO", "mesesAntiguedadValoracion", 12)).valor()).isEqualTo("0.35");
    }

    @Test
    void por_defecto_cuando_ninguna_fila_aplica() {
        var r = evaluador.evaluar(TipoRegla.HAIRCUT, haircut, Map.of("tipo", "OTRO", "mesesAntiguedadValoracion", 20));
        assertThat(r.valor()).isEqualTo("0.50");
        assertThat(r.fila()).isNull();
    }

    @Test
    void sin_por_defecto_la_regla_falla_en_lugar_de_inventar_un_valor() {
        TablaDecision sinDefecto = new TablaDecision(List.of(fila(Map.of("tipo", "X"), "0.1")), null);
        assertThatThrownBy(() -> evaluador.evaluar(TipoRegla.HAIRCUT, sinDefecto, Map.of("tipo", "Y")))
                .isInstanceOf(ReglaException.class);
    }

    @Test
    void not_in_y_booleanos() {
        TablaDecision idoneidad = new TablaDecision(List.of(
                new Fila(Map.of("tipo", "in (PAGARE, AVAL)"), new Salida("NO_IDONEA", "Garantía personal")),
                new Fila(Map.of("perfeccionada", "false"), new Salida("NO_IDONEA", "No perfeccionada")),
                new Fila(Map.of("condicionamientosAbiertos", "> 0"), new Salida("CONDICIONADA", "Condicionamientos"))),
                new Salida("IDONEA", "Cumple"));
        assertThat(evaluador.evaluar(TipoRegla.IDONEIDAD, idoneidad,
                Map.of("tipo", "HIPOTECA", "perfeccionada", true, "condicionamientosAbiertos", 2)).valor())
                .isEqualTo("CONDICIONADA");
        assertThat(evaluador.evaluar(TipoRegla.IDONEIDAD, idoneidad,
                Map.of("tipo", "HIPOTECA", "perfeccionada", false, "condicionamientosAbiertos", 0)).motivo())
                .isEqualTo("No perfeccionada");
        assertThat(EvaluadorTabla.cumpleCondicion(TipoRegla.Variable.TEXTO, "not in (A, B)", "C")).isTrue();
    }

    @Test
    void validacion_detecta_variables_inexistentes_tipos_y_rangos() {
        TablaDecision mala = new TablaDecision(List.of(
                fila(Map.of("color", "rojo"), "0.1"),
                fila(Map.of("tipo", "> 5"), "0.1"),
                fila(Map.of(), "1.5")), null);
        List<String> errores = evaluador.validar(TipoRegla.HAIRCUT, mala);
        assertThat(errores).hasSize(3);
        assertThat(errores.get(0)).contains("color");
        assertThat(errores.get(1)).contains("numéricas");
        assertThat(errores.get(2)).contains("entre 0 y 1");
    }

    @Test
    void idoneidad_exige_motivo() {
        TablaDecision sinMotivo = new TablaDecision(List.of(fila(Map.of(), "IDONEA")), null);
        assertThat(evaluador.validar(TipoRegla.IDONEIDAD, sinMotivo)).anyMatch(e -> e.contains("motivo"));
    }

    @Test
    void casos_de_prueba_comparan_numeros_por_valor() {
        var resultados = evaluador.probar(TipoRegla.HAIRCUT, haircut, List.of(
                new CasoPrueba("CDT en pesos", Map.of("tipo", "DEPOSITO_CDT", "moneda", "COP"), "0.00"),
                new CasoPrueba("Hipoteca reciente", Map.of("tipo", "HIPOTECA_VIVIENDA", "mesesAntiguedadValoracion", 3), "0.25")));
        assertThat(resultados.get(0).ok()).isTrue();
        assertThat(resultados.get(1).ok()).isFalse();
        assertThat(resultados.get(1).obtenido()).isEqualTo("0.30");
    }
}
