package co.bancopopular.garantias360.cobertura.motor;

import co.bancopopular.garantias360.cobertura.motor.ModeloCobertura.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Motor de cobertura determinista (anexo A).
 *
 * <p>Fases: preparación y validación → asignaciones pactadas → garantías exclusivas → garantías
 * compartidas (secuencial o prorrata) → excedente → resultados. Aritmética decimal exacta; los
 * montos se redondean a pesos (HALF_EVEN) al final de cada asignación y los repartos prorrata usan
 * el método del mayor residuo para que la suma repartida sea exacta.</p>
 *
 * <p>El motor no consulta reglas ni base de datos: recibe los parámetros ya resueltos (haircut,
 * cobertura objetivo, método) con el código y la versión de la regla que los produjo, para la traza.</p>
 */
public final class MotorCobertura {

    private static final int ESCALA_INTERNA = 10;
    private static final int ESCALA_RATIO = 6;
    private static final String COP = "COP";

    public ResultadoCobertura calcular(EntradaCobertura entrada) {
        return new Ejecucion(entrada).ejecutar();
    }

    private static final class Ejecucion {
        private final EntradaCobertura entrada;
        private final List<PasoTraza> traza = new ArrayList<>();
        private final List<Asignacion> asignaciones = new ArrayList<>();
        private final Map<String, EstadoGarantia> garantias = new LinkedHashMap<>();
        private final Map<String, EstadoOblig> obligaciones = new LinkedHashMap<>();
        private final List<EstadoVinculo> vinculos = new ArrayList<>();

        Ejecucion(EntradaCobertura entrada) {
            this.entrada = entrada;
        }

        ResultadoCobertura ejecutar() {
            prepararGarantias();
            prepararObligaciones();
            prepararVinculos();
            fasePactada();
            faseExclusivas();
            faseCompartidasSecuencial();
            faseCompartidasProrrata();
            faseExcedente();
            return resultados();
        }

        // ---------------------------------------------------------------- preparación

        private void prepararGarantias() {
            entrada.garantias().stream()
                    .sorted(Comparator.comparing(GarantiaEntrada::id))
                    .forEach(g -> garantias.put(g.id(), prepararGarantia(g)));
        }

        private EstadoGarantia prepararGarantia(GarantiaEntrada g) {
            EstadoGarantia e = new EstadoGarantia(g);
            if (g.valorBruto() == null) {
                return e.marcar(EstadoElemento.INCOMPLETA, "Sin valoración vigente");
            }
            if (g.valorBruto().signum() < 0) {
                return e.marcar(EstadoElemento.INVALIDA, "Valor bruto negativo");
            }
            if (g.haircut() == null || g.haircut().signum() < 0 || g.haircut().compareTo(BigDecimal.ONE) > 0) {
                return e.marcar(EstadoElemento.INVALIDA,
                        "Haircut fuera de [0, 1]: " + g.haircut() + " (regla " + g.reglaHaircut() + ")");
            }
            if (g.gravamenes() != null && g.gravamenes().signum() < 0) {
                return e.marcar(EstadoElemento.INVALIDA, "Gravámenes negativos");
            }
            BigDecimal trm = BigDecimal.ONE;
            if (!COP.equals(g.moneda())) {
                if (g.trm() == null || g.trm().signum() <= 0) {
                    return e.marcar(EstadoElemento.INCOMPLETA, "Moneda " + g.moneda() + " sin TRM");
                }
                trm = g.trm();
            }
            e.valorBruto = pesos(g.valorBruto().multiply(trm));
            e.valorAdmisible = pesos(e.valorBruto.multiply(BigDecimal.ONE.subtract(g.haircut())));
            e.gravamenes = pesos(Objects.requireNonNullElse(g.gravamenes(), BigDecimal.ZERO));
            e.valorNeto = e.valorAdmisible.subtract(e.gravamenes).max(BigDecimal.ZERO);
            e.disponible = e.valorNeto;

            if (!COP.equals(g.moneda())) {
                paso("GARANTIA", g.id(), null, "Conversión a COP de " + g.codigo(),
                        g.valorBruto() + " " + g.moneda() + " × TRM " + trm, e.valorBruto, null);
            }
            paso("GARANTIA", g.id(), null, "Valor admisible de " + g.codigo(),
                    "VA = VB × (1 − haircut) = " + e.valorBruto + " × (1 − " + g.haircut() + ")",
                    e.valorAdmisible, g.reglaHaircut());
            paso("GARANTIA", g.id(), null, "Valor neto de " + g.codigo(),
                    "VN = max(0, VA − gravámenes) = max(0, " + e.valorAdmisible + " − " + e.gravamenes + ")",
                    e.valorNeto, null);
            return e;
        }

        private void prepararObligaciones() {
            entrada.obligaciones().stream()
                    .sorted(Comparator.comparing(ObligacionEntrada::id))
                    .forEach(o -> obligaciones.put(o.id(), prepararObligacion(o)));
        }

        private EstadoOblig prepararObligacion(ObligacionEntrada o) {
            EstadoOblig e = new EstadoOblig(o);
            if (o.exposicion() == null) {
                return e.marcar(EstadoObligacion.INVALIDA, "Sin exposición informada por Flexcube");
            }
            if (o.coberturaObjetivo() == null || o.coberturaObjetivo().signum() < 0) {
                return e.marcar(EstadoObligacion.INVALIDA,
                        "Cobertura objetivo inválida (regla " + o.reglaObjetivo() + ")");
            }
            e.coberturaObjetivo = o.coberturaObjetivo();
            if (o.exposicion().signum() < 0) {
                return e.marcar(EstadoObligacion.SIN_EXPOSICION,
                        "Exposición negativa (" + o.exposicion() + "): dato inválido, se trata como sin exposición");
            }
            e.exposicion = pesos(o.exposicion());
            if (e.exposicion.signum() == 0) {
                return e.marcar(EstadoObligacion.SIN_EXPOSICION, "Obligación sin exposición");
            }
            e.requerido = pesos(e.exposicion.multiply(o.coberturaObjetivo()));
            paso("OBLIGACION", null, o.id(), "Requerido de la obligación " + o.numero(),
                    "R = E × C* = " + e.exposicion + " × " + o.coberturaObjetivo(), e.requerido, o.reglaObjetivo());
            return e;
        }

        private void prepararVinculos() {
            for (VinculoEntrada v : entrada.vinculos()) {
                EstadoGarantia g = garantias.get(v.garantiaId());
                EstadoOblig o = obligaciones.get(v.obligacionId());
                if (g == null || o == null) {
                    continue;
                }
                if (g.estado != EstadoElemento.VALIDA) {
                    if (o.estado == EstadoObligacion.COMPLETO) {
                        o.marcar(EstadoObligacion.PARCIAL, "Garantía " + g.entrada.codigo() + ": " + g.motivo);
                    }
                    continue;
                }
                if (o.estado != EstadoObligacion.COMPLETO && o.estado != EstadoObligacion.PARCIAL) {
                    continue;
                }
                vinculos.add(new EstadoVinculo(v, g, o));
            }
            Map<String, Long> activosPorGarantia = vinculos.stream()
                    .collect(Collectors.groupingBy(v -> v.garantia.entrada.id(), Collectors.counting()));
            garantias.values().forEach(g -> g.exclusiva = activosPorGarantia.getOrDefault(g.entrada.id(), 0L) == 1);
        }

        // ---------------------------------------------------------------- fases

        private void fasePactada() {
            vinculos.stream()
                    .filter(v -> v.entrada.valorPactado() != null)
                    .sorted(Comparator.comparingInt((EstadoVinculo v) -> v.entrada.prioridad())
                            .thenComparing(v -> v.garantia, ORDEN_GARANTIA)
                            .thenComparing(v -> v.obligacion.entrada.id()))
                    .forEach(v -> asignarHasta(v, v.entrada.valorPactado(), Fase.PACTADA));
        }

        private void faseExclusivas() {
            vinculos.stream()
                    .filter(v -> v.garantia.exclusiva)
                    .sorted(Comparator.comparing((EstadoVinculo v) -> v.garantia, ORDEN_GARANTIA))
                    .forEach(v -> asignarHasta(v, null, Fase.EXCLUSIVA));
        }

        private void faseCompartidasSecuencial() {
            List<EstadoVinculo> compartidos = vinculos.stream()
                    .filter(v -> !v.garantia.exclusiva && v.garantia.metodo() == MetodoDistribucion.SECUENCIAL)
                    .toList();
            compartidos.stream()
                    .map(v -> v.obligacion)
                    .distinct()
                    .sorted(ORDEN_OBLIGACION)
                    .forEach(o -> compartidos.stream()
                            .filter(v -> v.obligacion == o)
                            .sorted(Comparator.comparing((EstadoVinculo v) -> v.garantia, ORDEN_GARANTIA))
                            .forEach(v -> asignarHasta(v, null, Fase.COMPARTIDA_SECUENCIAL)));
        }

        private void faseCompartidasProrrata() {
            Map<EstadoGarantia, List<EstadoVinculo>> porGarantia = vinculos.stream()
                    .filter(v -> !v.garantia.exclusiva && v.garantia.metodo() == MetodoDistribucion.PRORRATA)
                    .collect(Collectors.groupingBy(v -> v.garantia, LinkedHashMap::new, Collectors.toList()));
            porGarantia.keySet().stream()
                    .sorted(ORDEN_GARANTIA)
                    .forEach(g -> repartirProrrata(g, porGarantia.get(g)));
        }

        private void faseExcedente() {
            vinculos.stream()
                    .filter(v -> v.garantia.exclusiva && v.garantia.entrada.asignarExcedente())
                    .sorted(Comparator.comparing((EstadoVinculo v) -> v.garantia, ORDEN_GARANTIA))
                    .forEach(v -> {
                        BigDecimal valor = v.garantia.disponible.min(v.topeRestante());
                        if (valor.signum() > 0) {
                            registrar(v, valor, Fase.EXCEDENTE,
                                    "Excedente de la garantía exclusiva " + v.garantia.entrada.codigo()
                                            + " (regla " + v.garantia.entrada.reglaMetodo() + ")",
                                    "a = min(D, tope restante) = min(" + v.garantia.disponible + ", " + texto(v.topeRestante()) + ")");
                        }
                    });
        }

        // ---------------------------------------------------------------- asignación

        /** a = min(D_g, tope restante, necesidad, límite opcional). */
        private void asignarHasta(EstadoVinculo v, BigDecimal limite, Fase fase) {
            BigDecimal necesidad = v.obligacion.necesidad();
            BigDecimal valor = v.garantia.disponible.min(v.topeRestante()).min(necesidad);
            String formula = "a = min(D " + v.garantia.disponible + ", tope " + texto(v.topeRestante())
                    + ", necesidad " + necesidad;
            if (limite != null) {
                valor = valor.min(pesos(limite));
                formula += ", pactado " + pesos(limite);
            }
            formula += ")";
            if (valor.signum() > 0) {
                registrar(v, valor, fase, descripcionFase(fase, v), formula);
            }
        }

        /**
         * Reparte D_g entre las obligaciones del grupo en proporción a su necesidad, sin superar
         * topes ni necesidades (llenado iterativo), y redondea con el método del mayor residuo.
         */
        private void repartirProrrata(EstadoGarantia g, List<EstadoVinculo> grupo) {
            Map<EstadoVinculo, BigDecimal> exactos = new LinkedHashMap<>();
            Map<EstadoVinculo, BigDecimal> necesidades = new LinkedHashMap<>();
            grupo.forEach(v -> necesidades.put(v, v.obligacion.necesidad()));
            BigDecimal disponibleInicial = g.disponible;
            List<EstadoVinculo> activos = grupo.stream()
                    .filter(v -> v.obligacion.necesidad().signum() > 0 && v.topeRestante().signum() > 0)
                    .sorted(Comparator.comparing(v -> v.obligacion.entrada.id()))
                    .collect(Collectors.toCollection(ArrayList::new));
            BigDecimal restante = g.disponible;
            while (!activos.isEmpty() && restante.signum() > 0) {
                BigDecimal totalNecesidad = activos.stream()
                        .map(v -> v.obligacion.necesidad().subtract(exactos.getOrDefault(v, BigDecimal.ZERO)))
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                if (totalNecesidad.signum() <= 0) {
                    break;
                }
                List<EstadoVinculo> saturados = new ArrayList<>();
                Map<EstadoVinculo, BigDecimal> ronda = new LinkedHashMap<>();
                for (EstadoVinculo v : activos) {
                    BigDecimal ya = exactos.getOrDefault(v, BigDecimal.ZERO);
                    BigDecimal necesidad = v.obligacion.necesidad().subtract(ya);
                    BigDecimal cupo = v.topeRestante().subtract(ya);
                    BigDecimal cuota = restante.multiply(necesidad).divide(totalNecesidad, ESCALA_INTERNA, RoundingMode.HALF_EVEN);
                    BigDecimal limite = necesidad.min(cupo);
                    if (cuota.compareTo(limite) >= 0) {
                        saturados.add(v);
                        ronda.put(v, limite);
                    } else {
                        ronda.put(v, cuota);
                    }
                }
                if (saturados.isEmpty()) {
                    ronda.forEach((v, c) -> exactos.merge(v, c, BigDecimal::add));
                    break;
                }
                // Solo se fijan los saturados; el resto se recalcula con lo que quede.
                for (EstadoVinculo v : saturados) {
                    exactos.merge(v, ronda.get(v), BigDecimal::add);
                    restante = restante.subtract(ronda.get(v));
                    activos.remove(v);
                }
            }
            Map<EstadoVinculo, BigDecimal> redondeados = mayorResiduo(exactos);
            BigDecimal totalNecesidad = necesidades.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            redondeados.forEach((v, valor) -> {
                if (valor.signum() > 0) {
                    registrar(v, valor, Fase.COMPARTIDA_PRORRATA,
                            "Prorrata de " + g.entrada.codigo() + " según necesidad (regla " + g.entrada.reglaMetodo() + ")",
                            "a = D × N_o / ΣN = " + disponibleInicial + " × " + necesidades.get(v) + " / " + totalNecesidad
                                    + " (con topes, llenado iterativo y mayor residuo)");
                }
            });
        }

        private Map<EstadoVinculo, BigDecimal> mayorResiduo(Map<EstadoVinculo, BigDecimal> exactos) {
            Map<EstadoVinculo, BigDecimal> resultado = new LinkedHashMap<>();
            BigDecimal total = pesos(exactos.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add));
            BigDecimal suma = BigDecimal.ZERO;
            for (var e : exactos.entrySet()) {
                BigDecimal piso = e.getValue().setScale(0, RoundingMode.FLOOR);
                resultado.put(e.getKey(), piso);
                suma = suma.add(piso);
            }
            int unidades = total.subtract(suma).intValueExact();
            exactos.entrySet().stream()
                    .sorted(Comparator.comparing((Map.Entry<EstadoVinculo, BigDecimal> e) ->
                                    e.getValue().subtract(e.getValue().setScale(0, RoundingMode.FLOOR))).reversed()
                            .thenComparing(e -> e.getKey().obligacion.entrada.id()))
                    .limit(Math.max(unidades, 0))
                    .forEach(e -> resultado.merge(e.getKey(), BigDecimal.ONE, BigDecimal::add));
            return resultado;
        }

        private void registrar(EstadoVinculo v, BigDecimal valor, Fase fase, String descripcion, String formula) {
            v.asignado = v.asignado.add(valor);
            v.garantia.disponible = v.garantia.disponible.subtract(valor);
            v.garantia.utilizado = v.garantia.utilizado.add(valor);
            v.obligacion.asignado = v.obligacion.asignado.add(valor);
            if (v.garantia.entrada.idonea()) {
                v.obligacion.asignadoIdoneo = v.obligacion.asignadoIdoneo.add(valor);
            }
            asignaciones.add(new Asignacion(asignaciones.size() + 1, v.garantia.entrada.id(),
                    v.obligacion.entrada.id(), valor, fase));
            paso("ASIGNACION", v.garantia.entrada.id(), v.obligacion.entrada.id(), descripcion, formula, valor,
                    v.garantia.entrada.reglaMetodo());
        }

        private String descripcionFase(Fase fase, EstadoVinculo v) {
            String g = v.garantia.entrada.codigo();
            String o = v.obligacion.entrada.numero();
            return switch (fase) {
                case PACTADA -> "Asignación pactada de " + g + " a " + o;
                case EXCLUSIVA -> "Garantía exclusiva " + g + " a " + o;
                case COMPARTIDA_SECUENCIAL -> "Garantía compartida " + g + " a " + o + " (secuencial por prioridad)";
                default -> fase + " " + g + " → " + o;
            };
        }

        // ---------------------------------------------------------------- resultados

        private ResultadoCobertura resultados() {
            List<ResultadoObligacion> resObligaciones = obligaciones.values().stream()
                    .map(this::resultadoObligacion)
                    .toList();
            List<ResultadoGarantia> resGarantias = garantias.values().stream()
                    .map(g -> new ResultadoGarantia(g.entrada.id(), g.entrada.codigo(), g.estado, g.motivo,
                            g.entrada.idonea(), g.exclusiva, g.valorBruto, g.entrada.haircut(), g.valorAdmisible,
                            g.gravamenes, g.valorNeto, g.utilizado, g.disponible))
                    .toList();
            return new ResultadoCobertura(estadoGlobal(), resGarantias, resObligaciones,
                    List.copyOf(asignaciones), List.copyOf(traza));
        }

        private ResultadoObligacion resultadoObligacion(EstadoOblig o) {
            BigDecimal ratio = null;
            BigDecimal ratioIdoneo = null;
            BigDecimal descubierto = BigDecimal.ZERO;
            BigDecimal brecha = BigDecimal.ZERO;
            if (o.exposicion.signum() > 0 && o.estado != EstadoObligacion.INVALIDA) {
                ratio = o.asignado.divide(o.exposicion, ESCALA_RATIO, RoundingMode.HALF_EVEN);
                ratioIdoneo = o.asignadoIdoneo.divide(o.exposicion, ESCALA_RATIO, RoundingMode.HALF_EVEN);
                descubierto = o.exposicion.subtract(o.asignado).max(BigDecimal.ZERO);
                brecha = o.requerido.subtract(o.asignado).max(BigDecimal.ZERO);
                paso("OBLIGACION", null, o.entrada.id(), "Cobertura de la obligación " + o.entrada.numero(),
                        "CR = A / E = " + o.asignado + " / " + o.exposicion + "; brecha = max(0, R − A) = max(0, "
                                + o.requerido + " − " + o.asignado + ")",
                        ratio, o.entrada.reglaObjetivo());
            }
            return new ResultadoObligacion(o.entrada.id(), o.entrada.numero(), o.estado, o.motivo, o.exposicion,
                    o.coberturaObjetivo, o.requerido, o.asignado, o.asignadoIdoneo, ratio, ratioIdoneo,
                    descubierto, brecha);
        }

        private EstadoCalculo estadoGlobal() {
            boolean invalido = garantias.values().stream().anyMatch(g -> g.estado == EstadoElemento.INVALIDA)
                    || obligaciones.values().stream().anyMatch(o -> o.estado == EstadoObligacion.INVALIDA);
            if (invalido) {
                return EstadoCalculo.INVALIDO;
            }
            boolean parcial = garantias.values().stream().anyMatch(g -> g.estado == EstadoElemento.INCOMPLETA)
                    || obligaciones.values().stream().anyMatch(o -> o.estado == EstadoObligacion.PARCIAL);
            return parcial ? EstadoCalculo.PARCIAL : EstadoCalculo.COMPLETO;
        }

        private void paso(String ambito, String garantiaId, String obligacionId, String descripcion, String formula,
                          BigDecimal resultado, String regla) {
            traza.add(new PasoTraza(traza.size() + 1, ambito, garantiaId, obligacionId, descripcion, formula,
                    resultado == null ? null : resultado.toPlainString(), regla));
        }
    }

    // -------------------------------------------------------------------- órdenes

    /** Idóneas primero, luego prioridad (liquidez) y código: regla PRIORIDAD_GARANTIA por defecto. */
    private static final Comparator<EstadoGarantia> ORDEN_GARANTIA = Comparator
            .comparing((EstadoGarantia g) -> !g.entrada.idonea())
            .thenComparingInt(g -> g.entrada.prioridad())
            .thenComparing(g -> g.entrada.codigo())
            .thenComparing(g -> g.entrada.id());

    /** Prioridad, fecha de desembolso y número: regla PRIORIDAD_OBLIGACION por defecto. */
    private static final Comparator<EstadoOblig> ORDEN_OBLIGACION = Comparator
            .comparingInt((EstadoOblig o) -> o.entrada.prioridad())
            .thenComparing(o -> Objects.requireNonNullElse(o.entrada.fechaDesembolso(), LocalDate.MAX))
            .thenComparing(o -> o.entrada.numero())
            .thenComparing(o -> o.entrada.id());

    // -------------------------------------------------------------------- estado mutable interno

    private static final class EstadoGarantia {
        final GarantiaEntrada entrada;
        EstadoElemento estado = EstadoElemento.VALIDA;
        String motivo;
        boolean exclusiva;
        BigDecimal valorBruto = BigDecimal.ZERO;
        BigDecimal valorAdmisible = BigDecimal.ZERO;
        BigDecimal gravamenes = BigDecimal.ZERO;
        BigDecimal valorNeto = BigDecimal.ZERO;
        BigDecimal utilizado = BigDecimal.ZERO;
        BigDecimal disponible = BigDecimal.ZERO;

        EstadoGarantia(GarantiaEntrada entrada) {
            this.entrada = entrada;
        }

        EstadoGarantia marcar(EstadoElemento estado, String motivo) {
            this.estado = estado;
            this.motivo = motivo;
            return this;
        }

        MetodoDistribucion metodo() {
            return Objects.requireNonNullElse(entrada.metodo(), MetodoDistribucion.SECUENCIAL);
        }
    }

    private static final class EstadoOblig {
        final ObligacionEntrada entrada;
        EstadoObligacion estado = EstadoObligacion.COMPLETO;
        String motivo;
        BigDecimal exposicion = BigDecimal.ZERO;
        BigDecimal coberturaObjetivo = BigDecimal.ZERO;
        BigDecimal requerido = BigDecimal.ZERO;
        BigDecimal asignado = BigDecimal.ZERO;
        BigDecimal asignadoIdoneo = BigDecimal.ZERO;

        EstadoOblig(ObligacionEntrada entrada) {
            this.entrada = entrada;
        }

        EstadoOblig marcar(EstadoObligacion estado, String motivo) {
            this.estado = estado;
            this.motivo = motivo;
            return this;
        }

        BigDecimal necesidad() {
            return requerido.subtract(asignado).max(BigDecimal.ZERO);
        }
    }

    private static final class EstadoVinculo {
        final VinculoEntrada entrada;
        final EstadoGarantia garantia;
        final EstadoOblig obligacion;
        BigDecimal asignado = BigDecimal.ZERO;

        EstadoVinculo(VinculoEntrada entrada, EstadoGarantia garantia, EstadoOblig obligacion) {
            this.entrada = entrada;
            this.garantia = garantia;
            this.obligacion = obligacion;
        }

        /** Sin tope = el valor neto completo de la garantía. */
        BigDecimal topeRestante() {
            BigDecimal tope = entrada.tope() == null ? garantia.valorNeto : pesos(entrada.tope());
            return tope.subtract(asignado).max(BigDecimal.ZERO);
        }
    }

    private static BigDecimal pesos(BigDecimal valor) {
        return valor.setScale(0, RoundingMode.HALF_EVEN);
    }

    private static String texto(BigDecimal valor) {
        return valor.toPlainString();
    }
}
