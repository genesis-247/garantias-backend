package co.bancopopular.garantias360.reglas.motor;

import co.bancopopular.garantias360.reglas.motor.TablaDecision.CasoPrueba;
import co.bancopopular.garantias360.reglas.motor.TablaDecision.Fila;
import co.bancopopular.garantias360.reglas.motor.TablaDecision.Salida;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Evalúa tablas de decisión sin ejecutar código arbitrario: la sintaxis es cerrada (ver
 * {@link TablaDecision}) y la regla solo ve las variables de su tipo.
 */
public final class EvaluadorTabla {

    private static final Pattern RANGO = Pattern.compile("^\\[\\s*(-?[\\d.]+)\\s*\\.\\.\\s*(-?[\\d.]+)\\s*]$");
    private static final Pattern LISTA = Pattern.compile("^(not\\s+)?in\\s*\\((.*)\\)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern COMPARACION = Pattern.compile("^(>=|<=|!=|>|<|=)\\s*(.*)$");

    public record Evaluacion(String valor, String motivo, Integer fila) {
    }

    public record ResultadoPrueba(String nombre, boolean ok, String esperado, String obtenido, String error) {
    }

    public Evaluacion evaluar(TipoRegla tipo, TablaDecision tabla, Map<String, Object> contexto) {
        for (int i = 0; i < tabla.filas().size(); i++) {
            Fila fila = tabla.filas().get(i);
            if (cumple(tipo, fila.condiciones(), contexto)) {
                return new Evaluacion(fila.salida().valor(), fila.salida().motivo(), i + 1);
            }
        }
        if (tabla.porDefecto() != null) {
            return new Evaluacion(tabla.porDefecto().valor(), tabla.porDefecto().motivo(), null);
        }
        throw new ReglaException("Ninguna fila aplica y la regla no tiene resultado por defecto. Contexto: " + contexto);
    }

    public List<String> validar(TipoRegla tipo, TablaDecision tabla) {
        List<String> errores = new ArrayList<>();
        if (tabla == null || tabla.filas() == null || tabla.filas().isEmpty()) {
            errores.add("La tabla debe tener al menos una fila");
            return errores;
        }
        for (int i = 0; i < tabla.filas().size(); i++) {
            Fila fila = tabla.filas().get(i);
            String prefijo = "Fila " + (i + 1) + ": ";
            Map<String, String> condiciones = Objects.requireNonNullElse(fila.condiciones(), Map.of());
            for (var c : condiciones.entrySet()) {
                TipoRegla.Variable variable = tipo.variables().get(c.getKey());
                if (variable == null) {
                    errores.add(prefijo + "la variable '" + c.getKey() + "' no existe para reglas " + tipo
                            + ". Disponibles: " + new TreeSet<>(tipo.variables().keySet()));
                    continue;
                }
                try {
                    cumpleCondicion(variable, c.getValue(), valorEjemplo(variable));
                } catch (ReglaException e) {
                    errores.add(prefijo + "condición inválida en '" + c.getKey() + "': " + e.getMessage());
                }
            }
            validarSalida(tipo, fila.salida(), prefijo, errores);
        }
        if (tabla.porDefecto() != null) {
            validarSalida(tipo, tabla.porDefecto(), "Por defecto: ", errores);
        }
        return errores;
    }

    public List<ResultadoPrueba> probar(TipoRegla tipo, TablaDecision tabla, List<CasoPrueba> casos) {
        List<ResultadoPrueba> resultados = new ArrayList<>();
        for (CasoPrueba caso : casos) {
            try {
                String obtenido = evaluar(tipo, tabla, caso.entradas()).valor();
                boolean ok = iguales(tipo, caso.esperado(), obtenido);
                resultados.add(new ResultadoPrueba(caso.nombre(), ok, caso.esperado(), obtenido, null));
            } catch (ReglaException e) {
                resultados.add(new ResultadoPrueba(caso.nombre(), false, caso.esperado(), null, e.getMessage()));
            }
        }
        return resultados;
    }

    // ------------------------------------------------------------------ internos

    private boolean cumple(TipoRegla tipo, Map<String, String> condiciones, Map<String, Object> contexto) {
        if (condiciones == null) {
            return true;
        }
        for (var c : condiciones.entrySet()) {
            TipoRegla.Variable variable = tipo.variables().get(c.getKey());
            if (variable == null) {
                throw new ReglaException("Variable desconocida: " + c.getKey());
            }
            if (!cumpleCondicion(variable, c.getValue(), contexto.get(c.getKey()))) {
                return false;
            }
        }
        return true;
    }

    static boolean cumpleCondicion(TipoRegla.Variable variable, String expresion, Object valor) {
        String e = expresion == null ? "" : expresion.trim();
        if (e.isEmpty() || e.equals("-") || e.equals("*")) {
            return true;
        }
        Matcher rango = RANGO.matcher(e);
        if (rango.matches()) {
            exigirNumero(variable, e);
            BigDecimal v = numero(valor);
            return v != null && v.compareTo(new BigDecimal(rango.group(1))) >= 0
                    && v.compareTo(new BigDecimal(rango.group(2))) <= 0;
        }
        Matcher lista = LISTA.matcher(e);
        if (lista.matches()) {
            boolean negada = lista.group(1) != null;
            boolean contenido = Arrays.stream(lista.group(2).split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .anyMatch(opcion -> igual(variable, opcion, valor));
            return negada != contenido;
        }
        Matcher comparacion = COMPARACION.matcher(e);
        String operador = "=";
        String operando = e;
        if (comparacion.matches()) {
            operador = comparacion.group(1);
            operando = comparacion.group(2).trim();
        }
        return switch (operador) {
            case "=" -> igual(variable, operando, valor);
            case "!=" -> !igual(variable, operando, valor);
            default -> {
                exigirNumero(variable, e);
                BigDecimal v = numero(valor);
                BigDecimal ref = parsearNumero(operando);
                if (v == null) {
                    yield false;
                }
                int cmp = v.compareTo(ref);
                yield switch (operador) {
                    case ">" -> cmp > 0;
                    case ">=" -> cmp >= 0;
                    case "<" -> cmp < 0;
                    default -> cmp <= 0;
                };
            }
        };
    }

    private static boolean igual(TipoRegla.Variable variable, String operando, Object valor) {
        if (valor == null) {
            return operando.equalsIgnoreCase("null");
        }
        return switch (variable) {
            case NUMERO -> {
                BigDecimal v = numero(valor);
                yield v != null && v.compareTo(parsearNumero(operando)) == 0;
            }
            case BOOLEANO -> {
                if (!operando.equalsIgnoreCase("true") && !operando.equalsIgnoreCase("false")) {
                    throw new ReglaException("se esperaba true o false y se recibió '" + operando + "'");
                }
                yield Boolean.parseBoolean(operando) == Boolean.parseBoolean(valor.toString());
            }
            case TEXTO -> operando.equals(valor.toString());
        };
    }

    private static void exigirNumero(TipoRegla.Variable variable, String expresion) {
        if (variable != TipoRegla.Variable.NUMERO) {
            throw new ReglaException("'" + expresion + "' solo aplica a variables numéricas");
        }
    }

    private static BigDecimal numero(Object valor) {
        if (valor == null) {
            return null;
        }
        if (valor instanceof BigDecimal b) {
            return b;
        }
        if (valor instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        return parsearNumero(valor.toString());
    }

    private static BigDecimal parsearNumero(String texto) {
        try {
            return new BigDecimal(texto.trim());
        } catch (NumberFormatException e) {
            throw new ReglaException("'" + texto + "' no es un número");
        }
    }

    private static Object valorEjemplo(TipoRegla.Variable variable) {
        return switch (variable) {
            case NUMERO -> BigDecimal.ONE;
            case BOOLEANO -> Boolean.TRUE;
            case TEXTO -> "X";
        };
    }

    private static void validarSalida(TipoRegla tipo, Salida salida, String prefijo, List<String> errores) {
        if (salida == null || salida.valor() == null || salida.valor().isBlank()) {
            errores.add(prefijo + "falta el resultado");
            return;
        }
        String v = salida.valor().trim();
        try {
            switch (tipo.resultado()) {
                case FRACCION -> {
                    BigDecimal n = new BigDecimal(v);
                    if (n.signum() < 0 || n.compareTo(BigDecimal.ONE) > 0) {
                        errores.add(prefijo + "el haircut debe estar entre 0 y 1 (se recibió " + v + ")");
                    }
                }
                case DECIMAL_NO_NEGATIVO -> {
                    if (new BigDecimal(v).signum() < 0) {
                        errores.add(prefijo + "el resultado no puede ser negativo");
                    }
                }
                case ENTERO -> Integer.parseInt(v);
                case BOOLEANO -> {
                    if (!v.equals("true") && !v.equals("false")) {
                        errores.add(prefijo + "el resultado debe ser true o false");
                    }
                }
                case METODO -> {
                    if (!TipoRegla.Resultado.METODOS.contains(v)) {
                        errores.add(prefijo + "método inválido; use " + TipoRegla.Resultado.METODOS);
                    }
                }
                case IDONEIDAD -> {
                    if (!TipoRegla.Resultado.IDONEIDADES.contains(v)) {
                        errores.add(prefijo + "idoneidad inválida; use " + TipoRegla.Resultado.IDONEIDADES);
                    } else if (salida.motivo() == null || salida.motivo().isBlank()) {
                        errores.add(prefijo + "la idoneidad exige un motivo");
                    }
                }
            }
        } catch (NumberFormatException e) {
            errores.add(prefijo + "'" + v + "' no es un número válido");
        }
    }

    private static boolean iguales(TipoRegla tipo, String esperado, String obtenido) {
        if (esperado == null || obtenido == null) {
            return Objects.equals(esperado, obtenido);
        }
        return switch (tipo.resultado()) {
            case FRACCION, DECIMAL_NO_NEGATIVO, ENTERO ->
                    new BigDecimal(esperado.trim()).compareTo(new BigDecimal(obtenido.trim())) == 0;
            default -> esperado.trim().equals(obtenido.trim());
        };
    }
}
