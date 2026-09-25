package co.bancopopular.garantias360.tablero;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * Alertas del centro de monitoreo (M10), derivadas del estado actual de los datos: no se guardan,
 * así que nunca quedan desactualizadas. La gestión (asignar, resolver) llega con Appian (RF-1005).
 */
@Service
public class AlertaService {

    private static final String ACTIVAS = "('ACTIVA','MONITOREO','ACTUALIZACION','EJECUCION')";

    private final JdbcTemplate jdbc;

    public AlertaService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public enum Criticidad { CRITICA, ALTA, MEDIA, BAJA }

    public record Alerta(String codigo, Criticidad criticidad, String categoria, String titulo, String detalle,
                         String garantia, String obligacion, String cliente, LocalDate fecha, String origen) {
    }

    @Transactional(readOnly = true)
    public List<Alerta> alertas(String garantia) {
        LocalDate hoy = LocalDate.now(ZoneId.of("America/Bogota"));
        List<Alerta> a = new ArrayList<>();

        jdbc.query("select codigo, cliente_nombre, fecha_proxima_valoracion from garantia where macroestado in " + ACTIVAS
                        + " and fecha_proxima_valoracion is not null and fecha_proxima_valoracion <= ?" + filtro(garantia),
                rs -> {
                    LocalDate f = rs.getDate("fecha_proxima_valoracion").toLocalDate();
                    long dias = f.toEpochDay() - hoy.toEpochDay();
                    Criticidad c = dias < 0 ? Criticidad.CRITICA : dias <= 30 ? Criticidad.ALTA : Criticidad.MEDIA;
                    a.add(new Alerta(dias < 0 ? "VALORACION_VENCIDA" : "VALORACION_POR_VENCER", c, "Valoración",
                            dias < 0 ? "Valoración vencida hace " + (-dias) + " días" : "Valoración vence en " + dias + " días",
                            "Próxima valoración: " + f, rs.getString("codigo"), null, rs.getString("cliente_nombre"), f,
                            "Regla PERIODICIDAD_VALORACION"));
                }, parametros(garantia, hoy.plusDays(90)));

        jdbc.query("""
                        select o.numero, o.cliente_nombre, c.exposicion, c.brecha, c.ratio, c.cobertura_objetivo, c.estado,
                               (select string_agg(g.codigo, ', ') from vinculo_garantia_obligacion v join garantia g on g.id = v.garantia_id
                                 where v.obligacion_id = o.id and v.vigente) as garantias
                        from cobertura_vigente c join obligacion o on o.id = c.obligacion_id
                        where c.exposicion > 0 and (c.brecha > 0 or c.estado in ('PARCIAL','INVALIDA'))""",
                rs -> {
                    String garantias = rs.getString("garantias");
                    if (garantia != null && (garantias == null || !garantias.contains(garantia))) {
                        return;
                    }
                    double proporcion = rs.getBigDecimal("brecha").doubleValue() / rs.getBigDecimal("exposicion").doubleValue();
                    if (rs.getBigDecimal("brecha").signum() > 0) {
                        Criticidad c = proporcion > 0.20 ? Criticidad.CRITICA : proporcion > 0.05 ? Criticidad.ALTA : Criticidad.MEDIA;
                        a.add(new Alerta(proporcion > 0.20 ? "BRECHA_CRITICA" : "BRECHA_COBERTURA", c, "Cobertura",
                                "Brecha de cobertura de " + String.format(Locale.of("es", "CO"), "%,.0f", rs.getBigDecimal("brecha")),
                                String.format(Locale.of("es", "CO"), "Cobertura %.2f %% frente a objetivo %.0f %%",
                                        rs.getBigDecimal("ratio") == null ? 0 : rs.getBigDecimal("ratio").doubleValue() * 100,
                                        rs.getBigDecimal("cobertura_objetivo").doubleValue() * 100),
                                garantias, rs.getString("numero"), rs.getString("cliente_nombre"), hoy, "Regla ALERTA brecha (anexo B, ej. 9)"));
                    }
                    if (!"COMPLETO".equals(rs.getString("estado"))) {
                        a.add(new Alerta("COBERTURA_" + rs.getString("estado"), Criticidad.ALTA, "Cobertura",
                                "Cálculo de cobertura " + rs.getString("estado").toLowerCase(),
                                "Hay garantías sin valoración o con datos inválidos en el cálculo", garantias,
                                rs.getString("numero"), rs.getString("cliente_nombre"), hoy, "Motor de cobertura"));
                    }
                });

        jdbc.query("select codigo, cliente_nombre, updated_at from garantia where macroestado = 'LIBERACION'" + filtro(garantia),
                rs -> {
                    LocalDate desde = rs.getTimestamp("updated_at").toLocalDateTime().toLocalDate();
                    long dias = hoy.toEpochDay() - desde.toEpochDay();
                    a.add(new Alerta("LIBERACION_PENDIENTE", dias > 15 ? Criticidad.CRITICA : Criticidad.ALTA, "Liberación",
                            "Liberación pendiente hace " + dias + " días", "SLA de liberación (RF-1204): 15 días [supuesto P-15]",
                            rs.getString("codigo"), null, rs.getString("cliente_nombre"), desde, "SLA liberación"));
                }, parametros(garantia, null));

        jdbc.query("""
                        select g.codigo, g.cliente_nombre, g.idoneidad, g.idoneidad_motivo from garantia g
                        where g.macroestado in """ + ACTIVAS + " and g.idoneidad in ('NO_IDONEA','CONDICIONADA')" + filtro(garantia),
                (RowCallbackHandler) rs -> a.add(new Alerta("GARANTIA_" + rs.getString("idoneidad"),
                        "NO_IDONEA".equals(rs.getString("idoneidad")) ? Criticidad.ALTA : Criticidad.MEDIA, "Idoneidad",
                        "NO_IDONEA".equals(rs.getString("idoneidad")) ? "Garantía no idónea" : "Garantía condicionada",
                        rs.getString("idoneidad_motivo"), rs.getString("codigo"), null, rs.getString("cliente_nombre"), hoy,
                        "Regla IDONEIDAD")), parametros(garantia, null));

        jdbc.query("""
                        select g.codigo, g.cliente_nombre from garantia g join tipo_garantia t on t.codigo = g.tipo_codigo
                        where g.macroestado in """ + ACTIVAS + " and t.requiere_poliza and coalesce((g.atributos ->> 'polizaVigente')::boolean, false) = false"
                        + filtro("g", garantia),
                (RowCallbackHandler) rs -> a.add(new Alerta("POLIZA_NO_VIGENTE", Criticidad.ALTA, "Seguros", "Póliza obligatoria no vigente",
                        "Ley 546 de 1999: incendio y terremoto sobre inmuebles hipotecados", rs.getString("codigo"), null,
                        rs.getString("cliente_nombre"), hoy, "N-06")), parametros(garantia, null));

        if (garantia == null) {
            jdbc.query("select id, tipo, agregado_id, error, creado_en from evento_outbox where estado = 'ERROR'",
                    (RowCallbackHandler) rs -> a.add(new Alerta("EVENTO_FALLIDO", Criticidad.CRITICA, "Técnica", "Evento no publicado: " + rs.getString("tipo"),
                            rs.getString("error"), null, null, null, rs.getTimestamp("creado_en").toLocalDateTime().toLocalDate(),
                            "Outbox " + rs.getString("id"))));
            jdbc.query("select evento_id, tipo, error, recibido_en from evento_inbox where estado = 'ERROR'",
                    (RowCallbackHandler) rs -> a.add(new Alerta("EVENTO_ENTRANTE_FALLIDO", Criticidad.CRITICA, "Técnica",
                            "Evento de Flexcube no procesado: " + rs.getString("tipo"), rs.getString("error"), null, null, null,
                            rs.getTimestamp("recibido_en").toLocalDateTime().toLocalDate(), "Inbox " + rs.getString("evento_id"))));
        }

        a.sort(Comparator.comparing(Alerta::criticidad).thenComparing(Alerta::fecha, Comparator.nullsLast(Comparator.naturalOrder())));
        return a;
    }

    private static String filtro(String garantia) {
        return garantia == null ? "" : " and codigo = ?";
    }

    private static String filtro(String alias, String garantia) {
        return garantia == null ? "" : " and " + alias + ".codigo = ?";
    }

    private static Object[] parametros(String garantia, Object primero) {
        List<Object> p = new ArrayList<>();
        if (primero != null) {
            p.add(primero);
        }
        if (garantia != null) {
            p.add(garantia);
        }
        return p.toArray();
    }
}
