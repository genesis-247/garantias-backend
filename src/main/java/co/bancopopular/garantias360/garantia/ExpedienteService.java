package co.bancopopular.garantias360.garantia;

import co.bancopopular.garantias360.auditoria.AuditoriaService;
import co.bancopopular.garantias360.cobertura.CalculoCobertura;
import co.bancopopular.garantias360.cobertura.CoberturaRepositorios;
import co.bancopopular.garantias360.cobertura.CoberturaVigente;
import co.bancopopular.garantias360.configuracion.TipoGarantiaService;
import co.bancopopular.garantias360.obligacion.Obligacion;
import co.bancopopular.garantias360.obligacion.ObligacionRepositorio;
import co.bancopopular.garantias360.tablero.AlertaService;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/** Consultas del maestro: listado con filtros (RF-0407) y Expediente 360 (RF-0408). */
@Service
public class ExpedienteService {

    private final Repositorios.Garantias garantias;
    private final Repositorios.Participantes participantes;
    private final Repositorios.Vinculos vinculos;
    private final Repositorios.Valoraciones valoraciones;
    private final ObligacionRepositorio obligaciones;
    private final CoberturaRepositorios.Vigentes vigentes;
    private final CoberturaRepositorios.Calculos calculos;
    private final TipoGarantiaService tipos;
    private final AuditoriaService auditoria;
    private final AlertaService alertas;
    private final JdbcTemplate jdbc;

    public ExpedienteService(Repositorios.Garantias garantias, Repositorios.Participantes participantes,
                             Repositorios.Vinculos vinculos, Repositorios.Valoraciones valoraciones,
                             ObligacionRepositorio obligaciones, CoberturaRepositorios.Vigentes vigentes,
                             CoberturaRepositorios.Calculos calculos, TipoGarantiaService tipos,
                             AuditoriaService auditoria, AlertaService alertas, JdbcTemplate jdbc) {
        this.garantias = garantias;
        this.participantes = participantes;
        this.vinculos = vinculos;
        this.valoraciones = valoraciones;
        this.obligaciones = obligaciones;
        this.vigentes = vigentes;
        this.calculos = calculos;
        this.tipos = tipos;
        this.auditoria = auditoria;
        this.alertas = alertas;
        this.jdbc = jdbc;
    }

    public record Filtros(String texto, String tipo, Macroestado macroestado, String idoneidad, String segmento,
                          String producto, String cliente) {
    }

    @Transactional(readOnly = true)
    public Page<Garantia> buscar(Filtros f, int pagina, int tamano, String orden) {
        Specification<Garantia> spec = (root, q, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (f.texto() != null && !f.texto().isBlank()) {
                String t = "%" + f.texto().toLowerCase() + "%";
                p.add(cb.or(cb.like(cb.lower(root.get("codigo")), t), cb.like(cb.lower(root.get("clienteNombre")), t),
                        cb.like(root.get("clienteDocumento"), t), cb.like(cb.lower(root.get("referenciaExterna")), t)));
            }
            igual(p, cb, root.get("tipoCodigo"), f.tipo());
            igual(p, cb, root.get("idoneidad"), f.idoneidad());
            igual(p, cb, root.get("segmento"), f.segmento());
            igual(p, cb, root.get("producto"), f.producto());
            igual(p, cb, root.get("clienteDocumento"), f.cliente());
            if (f.macroestado() != null) {
                p.add(cb.equal(root.get("macroestado"), f.macroestado()));
            }
            return cb.and(p.toArray(Predicate[]::new));
        };
        Sort sort = switch (Objects.requireNonNullElse(orden, "")) {
            case "valor" -> Sort.by(Sort.Order.desc("valorComercial").nullsLast());
            case "proximaValoracion" -> Sort.by(Sort.Order.asc("fechaProximaValoracion").nullsLast());
            case "cliente" -> Sort.by("clienteNombre");
            default -> Sort.by(Sort.Direction.DESC, "codigo");
        };
        return garantias.findAll(spec, PageRequest.of(Math.max(pagina, 0), Math.min(Math.max(tamano, 1), 200), sort));
    }

    private static void igual(List<Predicate> p, jakarta.persistence.criteria.CriteriaBuilder cb,
                              jakarta.persistence.criteria.Path<Object> campo, String valor) {
        if (valor != null && !valor.isBlank()) {
            p.add(cb.equal(campo, valor));
        }
    }

    /** Coberturas vigentes por garantía para el listado (obligaciones que respalda). */
    @Transactional(readOnly = true)
    public Map<UUID, Map<String, Object>> resumenCobertura(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Map<String, Object>> r = new HashMap<>();
        jdbc.query("""
                        select v.garantia_id, count(*) as obligaciones, min(c.ratio) as ratio_minimo,
                               sum(c.exposicion) as exposicion, sum(c.brecha) as brecha
                        from vinculo_garantia_obligacion v left join cobertura_vigente c on c.obligacion_id = v.obligacion_id
                        where v.vigente and v.garantia_id = any(?::uuid[]) group by v.garantia_id""",
                rs -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("obligaciones", rs.getInt("obligaciones"));
                    m.put("ratioMinimo", rs.getBigDecimal("ratio_minimo"));
                    m.put("exposicion", rs.getBigDecimal("exposicion"));
                    m.put("brecha", rs.getBigDecimal("brecha"));
                    r.put((UUID) rs.getObject("garantia_id"), m);
                }, "{" + String.join(",", ids.stream().map(UUID::toString).toList()) + "}");
        return r;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> expediente(Garantia g) {
        var tipo = tipos.porVersion(g.tipoVersionId);
        List<Vinculo> vs = vinculos.findByGarantiaIdAndVigenteTrue(g.id);
        Map<UUID, Obligacion> obls = new HashMap<>();
        obligaciones.findByIdIn(vs.stream().map(v -> v.obligacionId).toList()).forEach(o -> obls.put(o.id, o));
        Map<UUID, CoberturaVigente> cob = new HashMap<>();
        vigentes.findByObligacionIdIn(obls.keySet()).forEach(c -> cob.put(c.obligacionId, c));

        List<Map<String, Object>> obligacionesVista = vs.stream().map(v -> {
            Obligacion o = obls.get(v.obligacionId);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("vinculo", v);
            m.put("obligacion", o);
            m.put("exposicion", o.exposicion());
            m.put("cobertura", cob.get(o.id));
            m.put("otrasGarantias", vinculos.findByObligacionIdAndVigenteTrue(o.id).stream()
                    .filter(x -> !x.garantiaId.equals(g.id))
                    .map(x -> garantias.findById(x.garantiaId).map(y -> y.codigo).orElse("?")).toList());
            return m;
        }).toList();

        List<CalculoCobertura> ultimos = calculos.deGarantia(g.id, 10);
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("garantia", g);
        e.put("tipo", tipo.tipo());
        e.put("tipoVersion", tipo.version().numero);
        e.put("campos", tipo.campos());
        e.put("siguientesEstados", g.macroestado.siguientes());
        e.put("participantes", participantes.findByGarantiaId(g.id));
        e.put("obligaciones", obligacionesVista);
        e.put("valoraciones", valoraciones.findByGarantiaIdOrderByFechaDescCreatedAtDesc(g.id));
        e.put("calculos", ultimos.stream().map(c -> Map.of("id", c.id, "fechaCorte", c.fechaCorte, "estado", c.estado,
                "disparador", c.disparador, "hashResultado", c.hashResultado)).toList());
        e.put("alertas", alertas.alertas(g.codigo));
        e.put("auditoria", auditoria.consultar("Garantia", g.codigo, null, null, 100));
        e.put("eventos", jdbc.queryForList("""
                select id, tipo, estado, correlation_id, creado_en, publicado_en from evento_outbox
                where agregado_id = ? order by creado_en desc limit 50""", g.codigo));
        return e;
    }
}
