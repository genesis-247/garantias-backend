package co.bancopopular.garantias360.reglas.motor;

import java.util.List;
import java.util.Map;

/**
 * Tipos de regla del anexo B con su contrato: variables de entrada disponibles y tipo de resultado.
 * Una tabla de decisión solo puede usar las variables de su tipo; el resultado se valida al guardar.
 */
public enum TipoRegla {

    HAIRCUT("Haircut por tipo de garantía",
            Map.of("tipo", Variable.TEXTO, "clase", Variable.TEXTO, "moneda", Variable.TEXTO,
                    "segmento", Variable.TEXTO, "valoracionVencida", Variable.BOOLEANO,
                    "mesesAntiguedadValoracion", Variable.NUMERO),
            Resultado.FRACCION),

    COBERTURA_OBJETIVO("Cobertura objetivo por producto",
            Map.of("producto", Variable.TEXTO, "segmento", Variable.TEXTO),
            Resultado.DECIMAL_NO_NEGATIVO),

    METODO_DISTRIBUCION("Método de distribución de garantías compartidas",
            Map.of("tipo", Variable.TEXTO, "segmento", Variable.TEXTO),
            Resultado.METODO),

    ASIGNAR_EXCEDENTE("Asignación de excedente de garantías exclusivas",
            Map.of("tipo", Variable.TEXTO, "producto", Variable.TEXTO),
            Resultado.BOOLEANO),

    PRIORIDAD_GARANTIA("Prioridad de uso de la garantía (menor = primero)",
            Map.of("tipo", Variable.TEXTO, "clase", Variable.TEXTO),
            Resultado.ENTERO),

    PERIODICIDAD_VALORACION("Periodicidad de valoración en meses",
            Map.of("tipo", Variable.TEXTO, "clase", Variable.TEXTO, "segmento", Variable.TEXTO),
            Resultado.ENTERO),

    IDONEIDAD("Idoneidad de la garantía (Decreto 2555/2010, art. 2.1.2.1.3)",
            Map.of("tipo", Variable.TEXTO, "clase", Variable.TEXTO, "estadoJuridico", Variable.TEXTO,
                    "condicionamientosAbiertos", Variable.NUMERO, "perfeccionada", Variable.BOOLEANO,
                    "valoracionVigente", Variable.BOOLEANO, "polizaRequeridaVigente", Variable.BOOLEANO,
                    "destinoCredito", Variable.TEXTO),
            Resultado.IDONEIDAD);

    public enum Variable { TEXTO, NUMERO, BOOLEANO }

    public enum Resultado {
        FRACCION, DECIMAL_NO_NEGATIVO, ENTERO, BOOLEANO, METODO, IDONEIDAD;

        public static final List<String> METODOS = List.of("SECUENCIAL", "PRORRATA");
        public static final List<String> IDONEIDADES = List.of("IDONEA", "CONDICIONADA", "NO_IDONEA");
    }

    private final String descripcion;
    private final Map<String, Variable> variables;
    private final Resultado resultado;

    TipoRegla(String descripcion, Map<String, Variable> variables, Resultado resultado) {
        this.descripcion = descripcion;
        this.variables = variables;
        this.resultado = resultado;
    }

    public String descripcion() {
        return descripcion;
    }

    public Map<String, Variable> variables() {
        return variables;
    }

    public Resultado resultado() {
        return resultado;
    }
}
