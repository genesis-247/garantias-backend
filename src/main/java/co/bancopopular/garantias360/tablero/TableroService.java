package co.bancopopular.garantias360.tablero;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * Centro de mando (M01). Cada indicador trae su fórmula para que sea explicable (RF-0102).
 * Las fórmulas son las propuestas en la especificación y están pendientes de validación por Riesgos (P-09).
 */
@Service
public class TableroService {

    private static final String ACTIVAS = "('ACTIVA','MONITOREO','ACTUALIZACION','EJECUCION')";

    private final JdbcTemplate jdbc;
    private final AlertaService alertas;

    public TableroService(JdbcTemplate jdbc, AlertaService alertas) {
        this.jdbc = jdbc;
        this.alertas = alertas;
    }

    public record Indicador(String codigo, String nombre, BigDecimal valor, String unidad, String formula, String detalle) {
    }

    public record ComponenteSalud(String nombre, BigDecimal peso, BigDecimal valor) {
    }

    @Transactional(readOnly = true)
    public Map<String, Object> tablero() {
        LocalDate hoy = LocalDate.now(ZoneId.of("America/Bogota"));
        Map<String, Object> g = jdbc.queryForMap("""
                select count(*) filter (where macroestado in %1$s) as activas,
                       coalesce(sum(valor_comercial) filter (where macroestado in %1$s), 0) as valor,
                       count(*) filter (where macroestado in %1$s and idoneidad = 'CONDICIONADA') as condicionadas,
                       count(*) filter (where macroestado in %1$s and idoneidad = 'NO_IDONEA') as no_idoneas,
                       count(*) filter (where macroestado in %1$s and fecha_proxima_valoracion between ? and ?) as por_vencer,
                       count(*) filter (where macroestado in %1$s and fecha_proxima_valoracion < ?) as vencidas,
                       count(*) filter (where macroestado in %1$s and (fecha_proxima_valoracion is null or fecha_proxima_valoracion >= ?)) as vigentes,
                       count(*) filter (where macroestado in %1$s and perfeccionada) as perfeccionadas,
                       count(*) filter (where macroestado in %1$s and estado_documental = 'COMPLETO') as documentadas,
                       count(*) filter (where macroestado in %1$s and idoneidad <> 'NO_EVALUADA') as evaluadas,
                       count(*) as total
                from garantia""".formatted(ACTIVAS), hoy, hoy.plusDays(30), hoy, hoy);
        Map<String, Object> c = jdbc.queryForMap("""
                select coalesce(sum(exposicion), 0) as exposicion,
                       coalesce(sum(least(asignado, exposicion)), 0) as cubierta,
                       coalesce(sum(least(asignado_idoneo, exposicion)), 0) as idonea,
                       count(*) as obligaciones,
                       count(*) filter (where asignado >= requerido) as en_objetivo,
                       count(*) filter (where brecha > 0.20 * exposicion) as brechas_criticas
                from cobertura_vigente where exposicion > 0""");
        Map<String, Object> p = jdbc.queryForMap("""
                select count(*) filter (where t.requiere_poliza) as requieren,
                       count(*) filter (where t.requiere_poliza and coalesce((g.atributos ->> 'polizaVigente')::boolean, false)) as vigentes
                from garantia g join tipo_garantia t on t.codigo = g.tipo_codigo where g.macroestado in """ + ACTIVAS);

        long activas = n(g, "activas");
        BigDecimal exposicion = d(c, "exposicion");
        var listaAlertas = alertas.alertas(null);
        long criticas = listaAlertas.stream().filter(a -> a.criticidad() == AlertaService.Criticidad.CRITICA).count();
        long garantiasConCritica = listaAlertas.stream()
                .filter(a -> a.criticidad() == AlertaService.Criticidad.CRITICA && a.garantia() != null)
                .flatMap(a -> Arrays.stream(a.garantia().split(", "))).distinct().count();

        List<ComponenteSalud> salud = List.of(
                new ComponenteSalud("Obligaciones en su cobertura objetivo", new BigDecimal("0.30"), ratio(n(c, "en_objetivo"), n(c, "obligaciones"))),
                new ComponenteSalud("Valoraciones vigentes", new BigDecimal("0.20"), ratio(n(g, "vigentes"), activas)),
                new ComponenteSalud("Documentación completa", new BigDecimal("0.15"), ratio(n(g, "documentadas"), activas)),
                new ComponenteSalud("Pólizas vigentes", new BigDecimal("0.15"), ratio(n(p, "vigentes"), n(p, "requieren"))),
                new ComponenteSalud("Garantías perfeccionadas", new BigDecimal("0.10"), ratio(n(g, "perfeccionadas"), activas)),
                new ComponenteSalud("Sin alertas críticas", new BigDecimal("0.10"), ratio(activas - garantiasConCritica, activas)));
        BigDecimal indiceSalud = salud.stream().map(s -> s.peso().multiply(s.valor()))
                .reduce(BigDecimal.ZERO, BigDecimal::add).movePointRight(2).setScale(1, RoundingMode.HALF_EVEN);

        List<Map<String, Object>> controles = List.of(
                control("Valoración vigente", "N-02", ratio(n(g, "vigentes"), activas)),
                control("Garantía perfeccionada", "N-03 / N-04", ratio(n(g, "perfeccionadas"), activas)),
                control("Idoneidad evaluada", "N-01", ratio(n(g, "evaluadas"), activas)),
                control("Póliza obligatoria vigente", "N-06", ratio(n(p, "vigentes"), n(p, "requieren"))),
                control("Documentación completa", "N-13", ratio(n(g, "documentadas"), activas)));
        BigDecimal cumplimiento = controles.stream().map(x -> (BigDecimal) x.get("nivel"))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(controles.size()), 4, RoundingMode.HALF_EVEN).movePointRight(2).setScale(1, RoundingMode.HALF_EVEN);

        List<Indicador> kpis = List.of(
                new Indicador("INDICE_SALUD", "Índice de salud del portafolio", indiceSalud, "PUNTOS",
                        "Σ peso × componente (ver componentes); pesos de la regla IDX-SALUD propuesta", null),
                new Indicador("VALOR_GARANTIAS", "Valor total de garantías", d(g, "valor"), "COP",
                        "Σ valor comercial vigente de garantías activas", null),
                new Indicador("GARANTIAS_ACTIVAS", "Garantías activas", BigDecimal.valueOf(activas), "CONTEO",
                        "Garantías en ACTIVA, MONITOREO, ACTUALIZACION o EJECUCION", null),
                new Indicador("EXPOSICION_CUBIERTA", "Exposición cubierta", pct(d(c, "cubierta"), exposicion), "PORCENTAJE",
                        "Σ min(asignado, exposición) ÷ Σ exposición", "Exposición total " + exposicion.toPlainString()),
                new Indicador("COBERTURA_IDONEA", "Cobertura idónea", pct(d(c, "idonea"), exposicion), "PORCENTAJE",
                        "Σ min(asignado por garantías idóneas, exposición) ÷ Σ exposición", null),
                new Indicador("VALORACIONES_POR_VENCER", "Valoraciones próximas a vencer", BigDecimal.valueOf(n(g, "por_vencer")), "CONTEO",
                        "Próxima valoración en los siguientes 30 días", n(g, "vencidas") + " ya vencidas"),
                new Indicador("BRECHAS_CRITICAS", "Brechas críticas", BigDecimal.valueOf(n(c, "brechas_criticas")), "CONTEO",
                        "Obligaciones con brecha > 20 % de su exposición", null),
                new Indicador("CASOS_ATENCION", "Casos que requieren atención", BigDecimal.valueOf(criticas), "CONTEO",
                        "Alertas de criticidad CRÍTICA abiertas", null),
                new Indicador("CONDICIONADAS", "Garantías condicionadas", BigDecimal.valueOf(n(g, "condicionadas")), "CONTEO",
                        "Idoneidad CONDICIONADA (condicionamientos jurídicos abiertos)", null),
                new Indicador("NO_IDONEAS", "Garantías no idóneas", BigDecimal.valueOf(n(g, "no_idoneas")), "CONTEO",
                        "Idoneidad NO_IDONEA según la regla IDONEIDAD activa", null),
                new Indicador("CUMPLIMIENTO", "Cumplimiento normativo", cumplimiento, "PORCENTAJE",
                        "Promedio de los controles del incremento 1 (ver controles)", null),
                new Indicador("ALERTAS", "Alertas abiertas", BigDecimal.valueOf(listaAlertas.size()), "CONTEO",
                        "Alertas derivadas del monitoreo (M10)", criticas + " críticas"));

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("fechaCorte", hoy.toString());
        r.put("indicadores", kpis);
        r.put("componentesSalud", salud);
        r.put("controles", controles);
        r.put("evolucion", jdbc.queryForList("""
                select periodo, exposicion, asignado, asignado_idoneo, valor_garantias, garantias_activas,
                       case when exposicion > 0 then round(least(asignado, exposicion) / exposicion, 4) end as cobertura,
                       case when exposicion > 0 then round(asignado_idoneo / exposicion, 4) end as cobertura_idonea
                from indicador_portafolio where periodo >= ? order by periodo""", hoy.withDayOfMonth(1).minusMonths(11)));
        r.put("composicionPorTipo", jdbc.queryForList("""
                select g.tipo_codigo as tipo, t.nombre, count(*) as garantias, coalesce(sum(g.valor_comercial), 0) as valor
                from garantia g join tipo_garantia t on t.codigo = g.tipo_codigo
                where g.macroestado in """ + ACTIVAS + " group by g.tipo_codigo, t.nombre order by valor desc"));
        r.put("composicionPorSegmento", jdbc.queryForList("""
                select segmento, count(*) as garantias, coalesce(sum(valor_comercial), 0) as valor
                from garantia where macroestado in """ + ACTIVAS + " group by segmento order by valor desc"));
        r.put("porEstado", jdbc.queryForList("select macroestado, count(*) as garantias from garantia group by macroestado order by 2 desc"));
        r.put("atencion", listaAlertas.stream().limit(10).toList());
        return r;
    }

    private static Map<String, Object> control(String nombre, String norma, BigDecimal nivel) {
        return Map.of("control", nombre, "norma", norma, "nivel", nivel,
                "estado", nivel.compareTo(new BigDecimal("0.95")) >= 0 ? "CUMPLE" : nivel.compareTo(new BigDecimal("0.80")) >= 0 ? "EN_RIESGO" : "BRECHA");
    }

    private static long n(Map<String, Object> m, String k) {
        return ((Number) m.get(k)).longValue();
    }

    private static BigDecimal d(Map<String, Object> m, String k) {
        return new BigDecimal(m.get(k).toString());
    }

    private static BigDecimal ratio(long a, long b) {
        return b == 0 ? BigDecimal.ONE : BigDecimal.valueOf(a).divide(BigDecimal.valueOf(b), 4, RoundingMode.HALF_EVEN);
    }

    private static BigDecimal pct(BigDecimal a, BigDecimal b) {
        return b.signum() == 0 ? BigDecimal.ZERO : a.divide(b, 6, RoundingMode.HALF_EVEN).movePointRight(2).setScale(2, RoundingMode.HALF_EVEN);
    }
}
