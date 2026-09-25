package co.bancopopular.garantias360.cobertura.motor;

import co.bancopopular.garantias360.cobertura.motor.ModeloCobertura.*;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/** Casos del anexo A (A.5 y A.6) de la especificación. */
class MotorCoberturaTest {

    private final MotorCobertura motor = new MotorCobertura();

    // ------------------------------------------------------------------ utilidades

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    private static GarantiaEntrada garantia(String id, String vb, String haircut, String gravamenes,
                                            MetodoDistribucion metodo, boolean excedente) {
        return new GarantiaEntrada(id, "GAR-" + id, "T", "COP", vb == null ? null : d(vb),
                null, haircut == null ? null : d(haircut), "HC v1", gravamenes == null ? null : d(gravamenes),
                true, 100, metodo, "DIST v1", excedente);
    }

    private static GarantiaEntrada garantia(String id, String vb, String haircut) {
        return garantia(id, vb, haircut, "0", MetodoDistribucion.SECUENCIAL, false);
    }

    private static ObligacionEntrada obligacion(String id, String exposicion, String objetivo, LocalDate desembolso) {
        return new ObligacionEntrada(id, "OB-" + id, exposicion == null ? null : d(exposicion),
                objetivo == null ? null : d(objetivo), "OBJ v1", 100, desembolso);
    }

    private static ObligacionEntrada obligacion(String id, String exposicion, String objetivo) {
        return obligacion(id, exposicion, objetivo, LocalDate.of(2025, 1, 1));
    }

    private static VinculoEntrada vinculo(String g, String o) {
        return new VinculoEntrada(g, o, null, null, 100);
    }

    private ResultadoCobertura calcular(List<GarantiaEntrada> g, List<ObligacionEntrada> o, List<VinculoEntrada> v) {
        return motor.calcular(new EntradaCobertura(g, o, v));
    }

    private static ResultadoObligacion ob(ResultadoCobertura r, String id) {
        return r.obligaciones().stream().filter(o -> o.id().equals(id)).findFirst().orElseThrow();
    }

    private static ResultadoGarantia ga(ResultadoCobertura r, String id) {
        return r.garantias().stream().filter(g -> g.id().equals(id)).findFirst().orElseThrow();
    }

    private static void assertValor(BigDecimal real, String esperado) {
        assertThat(real).isEqualByComparingTo(esperado);
    }

    // ------------------------------------------------------------------ A.5 ejemplo resuelto

    @Nested
    class EjemploAnexoA5 {

        private ResultadoCobertura ejemplo(MetodoDistribucion metodo) {
            GarantiaEntrada inmueble = garantia("G1", "500000000", "0.30", "50000000", metodo, false);
            GarantiaEntrada cdt = garantia("G2", "80000000", "0", "0", metodo, false);
            ObligacionEntrada o1 = obligacion("O1", "200000000", "1.25", LocalDate.of(2025, 3, 10));
            ObligacionEntrada o2 = obligacion("O2", "150000000", "1.00", LocalDate.of(2025, 8, 1));
            return calcular(List.of(inmueble, cdt), List.of(o1, o2),
                    List.of(vinculo("G1", "O1"), vinculo("G1", "O2"), vinculo("G2", "O2")));
        }

        @Test
        void secuencial_reproduce_86_67_por_ciento() {
            ResultadoCobertura r = ejemplo(MetodoDistribucion.SECUENCIAL);

            assertValor(ga(r, "G1").valorAdmisible(), "350000000");
            assertValor(ga(r, "G1").valorNeto(), "300000000");
            assertValor(ob(r, "O1").asignado(), "250000000");
            assertValor(ob(r, "O1").ratio(), "1.25");
            assertValor(ob(r, "O2").asignado(), "130000000");
            assertValor(ob(r, "O2").ratio(), "0.866667");
            assertValor(ob(r, "O2").descubierto(), "20000000");
            assertValor(ob(r, "O2").brecha(), "20000000");
            assertValor(ga(r, "G1").disponible(), "0");
            assertThat(r.estado()).isEqualTo(EstadoCalculo.COMPLETO);
        }

        @Test
        void prorrata_reproduce_117_19_y_97_08_por_ciento() {
            ResultadoCobertura r = ejemplo(MetodoDistribucion.PRORRATA);

            assertValor(ob(r, "O1").asignado(), "234375000");
            assertValor(ob(r, "O2").asignado(), "145625000");
            assertThat(ob(r, "O1").ratio().setScale(4, java.math.RoundingMode.HALF_EVEN)).isEqualByComparingTo("1.1719");
            assertThat(ob(r, "O2").ratio().setScale(4, java.math.RoundingMode.HALF_EVEN)).isEqualByComparingTo("0.9708");
        }

        @Test
        void la_traza_explica_cada_cifra() {
            ResultadoCobertura r = ejemplo(MetodoDistribucion.SECUENCIAL);

            assertThat(r.traza()).extracting(PasoTraza::descripcion)
                    .contains("Valor admisible de GAR-G1", "Valor neto de GAR-G1", "Requerido de la obligación OB-O2",
                            "Cobertura de la obligación OB-O2");
            assertThat(r.traza()).filteredOn(p -> "ASIGNACION".equals(p.ambito())).hasSize(3);
        }
    }

    // ------------------------------------------------------------------ A.6 casos de prueba mínimos

    @Test
    void CT01_obligacion_sin_exposicion_no_recibe_asignacion_ni_ratio() {
        ResultadoCobertura r = calcular(List.of(garantia("G", "100", "0")), List.of(obligacion("O", "0", "1")),
                List.of(vinculo("G", "O")));
        assertThat(ob(r, "O").estado()).isEqualTo(EstadoObligacion.SIN_EXPOSICION);
        assertThat(ob(r, "O").ratio()).isNull();
        assertThat(r.asignaciones()).isEmpty();
    }

    @Test
    void CT02_valor_bruto_cero_es_valido_y_no_aporta() {
        ResultadoCobertura r = calcular(List.of(garantia("G", "0", "0")), List.of(obligacion("O", "100", "1")),
                List.of(vinculo("G", "O")));
        assertValor(ob(r, "O").ratio(), "0");
        assertThat(r.estado()).isEqualTo(EstadoCalculo.COMPLETO);
    }

    @Test
    void CT03_sin_valoracion_excluye_la_garantia_y_deja_calculo_parcial() {
        ResultadoCobertura r = calcular(List.of(garantia("G", null, "0")), List.of(obligacion("O", "100", "1")),
                List.of(vinculo("G", "O")));
        assertThat(ga(r, "G").estado()).isEqualTo(EstadoElemento.INCOMPLETA);
        assertThat(ob(r, "O").estado()).isEqualTo(EstadoObligacion.PARCIAL);
        assertThat(r.estado()).isEqualTo(EstadoCalculo.PARCIAL);
    }

    @Test
    void CT04_haircut_en_los_limites() {
        ResultadoCobertura cero = calcular(List.of(garantia("G", "100", "0")), List.of(), List.of());
        ResultadoCobertura cien = calcular(List.of(garantia("G", "100", "1")), List.of(), List.of());
        assertValor(ga(cero, "G").valorAdmisible(), "100");
        assertValor(ga(cien, "G").valorAdmisible(), "0");
    }

    @Test
    void CT05_haircut_invalido_no_se_corrige_en_silencio() {
        ResultadoCobertura negativo = calcular(List.of(garantia("G", "100", "-0.05")), List.of(), List.of());
        ResultadoCobertura mayor = calcular(List.of(garantia("G", "100", "1.2")), List.of(), List.of());
        assertThat(ga(negativo, "G").estado()).isEqualTo(EstadoElemento.INVALIDA);
        assertThat(ga(mayor, "G").estado()).isEqualTo(EstadoElemento.INVALIDA);
        assertThat(ga(mayor, "G").motivo()).contains("HC v1");
        assertThat(mayor.estado()).isEqualTo(EstadoCalculo.INVALIDO);
    }

    @Test
    void CT06_cobertura_exacta_de_100() {
        ResultadoCobertura r = calcular(List.of(garantia("G", "100", "0")), List.of(obligacion("O", "100", "1")),
                List.of(vinculo("G", "O")));
        assertValor(ob(r, "O").ratio(), "1");
        assertValor(ob(r, "O").brecha(), "0");
    }

    @Test
    void CT07_sin_garantias_cobertura_cero_y_descubierto_total() {
        ResultadoCobertura r = calcular(List.of(), List.of(obligacion("O", "100", "1")), List.of());
        assertValor(ob(r, "O").ratio(), "0");
        assertValor(ob(r, "O").descubierto(), "100");
    }

    @Test
    void CT08_excedente_activo_permite_mas_de_100() {
        GarantiaEntrada deposito = garantia("G", "110", "0", "0", MetodoDistribucion.SECUENCIAL, true);
        ResultadoCobertura r = calcular(List.of(deposito), List.of(obligacion("O", "100", "1")), List.of(vinculo("G", "O")));
        assertValor(ob(r, "O").ratio(), "1.1");
        assertThat(r.asignaciones()).extracting(Asignacion::fase).contains(Fase.EXCEDENTE);
    }

    @Test
    void CT09_excedente_inactivo_limita_al_objetivo_y_deja_disponible() {
        ResultadoCobertura r = calcular(List.of(garantia("G", "110", "0")), List.of(obligacion("O", "100", "1")),
                List.of(vinculo("G", "O")));
        assertValor(ob(r, "O").ratio(), "1");
        assertValor(ga(r, "G").disponible(), "10");
    }

    @Test
    void CT12_prorrata_con_residuos_usa_mayor_residuo_y_suma_exacta() {
        GarantiaEntrada g = garantia("G", "100", "0", "0", MetodoDistribucion.PRORRATA, false);
        ResultadoCobertura r = calcular(List.of(g),
                List.of(obligacion("A", "1000", "1"), obligacion("B", "1000", "1"), obligacion("C", "1000", "1")),
                List.of(vinculo("G", "A"), vinculo("G", "B"), vinculo("G", "C")));
        List<BigDecimal> valores = r.obligaciones().stream().map(ResultadoObligacion::asignado).sorted().toList();
        assertThat(valores).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(d("33"), d("33"), d("34"));
        assertValor(ga(r, "G").utilizado(), "100");
    }

    @Test
    void CT13_tope_de_vinculo_limita_y_el_resto_se_reasigna() {
        GarantiaEntrada g = garantia("G", "100", "0", "0", MetodoDistribucion.SECUENCIAL, false);
        VinculoEntrada conTope = new VinculoEntrada("G", "A", d("30"), null, 100);
        ResultadoCobertura r = calcular(List.of(g),
                List.of(obligacion("A", "100", "1", LocalDate.of(2024, 1, 1)), obligacion("B", "100", "1")),
                List.of(conTope, vinculo("G", "B")));
        assertValor(ob(r, "A").asignado(), "30");
        assertValor(ob(r, "B").asignado(), "70");
    }

    @Test
    void CT13b_tope_en_prorrata_redistribuye_con_llenado_iterativo() {
        GarantiaEntrada g = garantia("G", "100", "0", "0", MetodoDistribucion.PRORRATA, false);
        VinculoEntrada conTope = new VinculoEntrada("G", "A", d("10"), null, 100);
        ResultadoCobertura r = calcular(List.of(g),
                List.of(obligacion("A", "100", "1"), obligacion("B", "100", "1")),
                List.of(conTope, vinculo("G", "B")));
        assertValor(ob(r, "A").asignado(), "10");
        assertValor(ob(r, "B").asignado(), "90");
    }

    @Test
    void CT14_gravamen_mayor_que_admisible_deja_valor_neto_cero() {
        ResultadoCobertura r = calcular(List.of(garantia("G", "100", "0.5", "80", MetodoDistribucion.SECUENCIAL, false)),
                List.of(), List.of());
        assertValor(ga(r, "G").valorNeto(), "0");
    }

    @Test
    void CT15_moneda_extranjera_con_y_sin_trm() {
        GarantiaEntrada usd = new GarantiaEntrada("G", "GAR-G", "T", "USD", d("1000"), d("4000.50"), d("0.1"),
                "HC v1", d("0"), true, 1, MetodoDistribucion.SECUENCIAL, "D", false);
        GarantiaEntrada sinTrm = new GarantiaEntrada("H", "GAR-H", "T", "USD", d("1000"), null, d("0.1"),
                "HC v1", d("0"), true, 1, MetodoDistribucion.SECUENCIAL, "D", false);
        ResultadoCobertura r = calcular(List.of(usd, sinTrm), List.of(), List.of());
        assertValor(ga(r, "G").valorBruto(), "4000500");
        assertValor(ga(r, "G").valorAdmisible(), "3600450");
        assertThat(ga(r, "H").estado()).isEqualTo(EstadoElemento.INCOMPLETA);
    }

    @Test
    void CT20_exposicion_negativa_se_trata_como_sin_exposicion() {
        ResultadoCobertura r = calcular(List.of(garantia("G", "100", "0")), List.of(obligacion("O", "-5", "1")),
                List.of(vinculo("G", "O")));
        assertThat(ob(r, "O").estado()).isEqualTo(EstadoObligacion.SIN_EXPOSICION);
        assertThat(ob(r, "O").motivo()).contains("negativa");
    }

    @Test
    void asignacion_pactada_se_respeta_primero() {
        GarantiaEntrada g = garantia("G", "100", "0", "0", MetodoDistribucion.SECUENCIAL, false);
        VinculoEntrada pactado = new VinculoEntrada("G", "B", null, d("60"), 1);
        ResultadoCobertura r = calcular(List.of(g),
                List.of(obligacion("A", "100", "1", LocalDate.of(2020, 1, 1)), obligacion("B", "100", "1")),
                List.of(vinculo("G", "A"), pactado));
        assertValor(ob(r, "B").asignado(), "60");
        assertValor(ob(r, "A").asignado(), "40");
    }

    @Test
    void cobertura_idonea_solo_cuenta_garantias_idoneas() {
        GarantiaEntrada noIdonea = new GarantiaEntrada("N", "GAR-N", "PAGARE", "COP", d("50"), null, d("0"), "HC",
                d("0"), false, 1, MetodoDistribucion.SECUENCIAL, "D", false);
        ResultadoCobertura r = calcular(List.of(garantia("G", "50", "0"), noIdonea), List.of(obligacion("O", "100", "1")),
                List.of(vinculo("G", "O"), vinculo("N", "O")));
        assertValor(ob(r, "O").ratio(), "1");
        assertValor(ob(r, "O").ratioIdoneo(), "0.5");
    }

    // ------------------------------------------------------------------ CT-19 propiedades

    @RepeatedTest(200)
    void CT19_propiedades_invariantes_con_datos_aleatorios() {
        Random rnd = new Random();
        long semilla = rnd.nextLong();
        rnd.setSeed(semilla);
        List<GarantiaEntrada> gs = new ArrayList<>();
        List<ObligacionEntrada> os = new ArrayList<>();
        List<VinculoEntrada> vs = new ArrayList<>();
        int ng = 1 + rnd.nextInt(5);
        int no = 1 + rnd.nextInt(5);
        for (int i = 0; i < ng; i++) {
            gs.add(new GarantiaEntrada("g" + i, "GAR-" + i, "T", "COP", BigDecimal.valueOf(rnd.nextInt(1_000_000)),
                    null, BigDecimal.valueOf(rnd.nextInt(101), 2), "HC", BigDecimal.valueOf(rnd.nextInt(100_000)),
                    rnd.nextBoolean(), rnd.nextInt(5),
                    rnd.nextBoolean() ? MetodoDistribucion.PRORRATA : MetodoDistribucion.SECUENCIAL, "D", rnd.nextBoolean()));
        }
        for (int i = 0; i < no; i++) {
            os.add(new ObligacionEntrada("o" + i, "OB-" + i, BigDecimal.valueOf(rnd.nextInt(800_000)),
                    BigDecimal.valueOf(50 + rnd.nextInt(100), 2), "OBJ", rnd.nextInt(3), LocalDate.of(2024, 1, 1 + i)));
        }
        for (int i = 0; i < ng; i++) {
            for (int j = 0; j < no; j++) {
                if (rnd.nextInt(3) > 0) {
                    BigDecimal tope = rnd.nextInt(4) == 0 ? BigDecimal.valueOf(rnd.nextInt(300_000)) : null;
                    BigDecimal pactado = rnd.nextInt(6) == 0 ? BigDecimal.valueOf(rnd.nextInt(200_000)) : null;
                    vs.add(new VinculoEntrada("g" + i, "o" + j, tope, pactado, rnd.nextInt(3)));
                }
            }
        }
        ResultadoCobertura r1 = calcular(gs, os, vs);
        ResultadoCobertura r2 = calcular(gs, os, vs);

        String contexto = "semilla " + semilla;
        assertThat(r1).as("determinismo, " + contexto).isEqualTo(r2);
        for (ResultadoGarantia g : r1.garantias()) {
            assertThat(g.utilizado()).as(contexto).isLessThanOrEqualTo(g.valorNeto());
            assertThat(g.disponible().signum()).as(contexto).isGreaterThanOrEqualTo(0);
            assertThat(g.utilizado().add(g.disponible())).as(contexto).isEqualByComparingTo(g.valorNeto());
        }
        for (Asignacion a : r1.asignaciones()) {
            assertThat(a.valor().signum()).as(contexto).isPositive();
            assertThat(a.valor().scale()).as(contexto).isLessThanOrEqualTo(0);
        }
        Map<String, BigDecimal> porObligacion = new HashMap<>();
        r1.asignaciones().forEach(a -> porObligacion.merge(a.obligacionId(), a.valor(), BigDecimal::add));
        for (ResultadoObligacion o : r1.obligaciones()) {
            assertThat(o.asignado()).as(contexto)
                    .isEqualByComparingTo(porObligacion.getOrDefault(o.id(), BigDecimal.ZERO));
            assertThat(o.descubierto().signum()).as(contexto).isGreaterThanOrEqualTo(0);
        }
        for (VinculoEntrada v : vs) {
            if (v.tope() != null) {
                BigDecimal asignado = r1.asignaciones().stream()
                        .filter(a -> a.garantiaId().equals(v.garantiaId()) && a.obligacionId().equals(v.obligacionId()))
                        .map(Asignacion::valor).reduce(BigDecimal.ZERO, BigDecimal::add);
                assertThat(asignado).as("tope respetado, " + contexto).isLessThanOrEqualTo(v.tope());
            }
        }
    }
}
