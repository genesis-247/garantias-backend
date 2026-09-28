package co.bancopopular.garantias360.constitucion;

import co.bancopopular.garantias360.auditoria.AuditoriaService;
import co.bancopopular.garantias360.comun.Contexto;
import co.bancopopular.garantias360.comun.Errores;
import co.bancopopular.garantias360.comun.Json;
import co.bancopopular.garantias360.configuracion.CampoDefinicion;
import co.bancopopular.garantias360.configuracion.DefinicionTipo.PlantillaActividad;
import co.bancopopular.garantias360.configuracion.TipoGarantiaService;
import co.bancopopular.garantias360.eventos.OutboxService;
import co.bancopopular.garantias360.garantia.Garantia;
import co.bancopopular.garantias360.garantia.Macroestado;
import co.bancopopular.garantias360.garantia.Repositorios;
import co.bancopopular.garantias360.seguridad.Roles;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Constitución y registro (M06). Al pasar a CONSTITUCION se genera el plan de actividades desde la
 * plantilla de la versión del tipo con la que se registró la garantía (RF-0601). El avance llega por
 * API (Appian, RF-0605) o desde la interfaz; la garantía solo se perfecciona cuando todas las
 * actividades obligatorias están completadas con su evidencia (RF-0604).
 */
@Service
public class ConstitucionService {

    public static final Set<String> ESTADOS = Set.of("PENDIENTE", "EN_CURSO", "COMPLETADA", "BLOQUEADA", "NO_APLICA");
    private static final Pattern SHA256 = Pattern.compile("^[a-f0-9]{64}$");
    private static final ZoneId BOGOTA = ZoneId.of("America/Bogota");

    private final ActividadRepositorio actividades;
    private final Repositorios.Garantias garantias;
    private final TipoGarantiaService tipos;
    private final AuditoriaService auditoria;
    private final OutboxService outbox;
    private final JdbcTemplate jdbc;

    public ConstitucionService(ActividadRepositorio actividades, Repositorios.Garantias garantias, TipoGarantiaService tipos,
                               AuditoriaService auditoria, OutboxService outbox, JdbcTemplate jdbc) {
        this.actividades = actividades;
        this.garantias = garantias;
        this.tipos = tipos;
        this.auditoria = auditoria;
        this.outbox = outbox;
        this.jdbc = jdbc;
    }

    /** Cambio de una actividad. Los campos nulos no se modifican. */
    public record Cambio(String estado, String responsable, LocalDate fechaLimite, String evidenciaRef, String evidenciaHash,
                         ObjectNode datos, String observacion) {
    }

    public record Resumen(int total, int cerradas, int obligatoriasPendientes, int bloqueadas, int vencidas,
                          boolean completo) {
    }

    public record Plan(String garantia, String macroestado, boolean perfeccionada, boolean editable,
                       List<ActividadConstitucion> actividades, Resumen resumen) {
    }

    // ------------------------------------------------------------------ generación del plan

    /** Genera el plan si la garantía no lo tiene. Se une a la transacción del cambio de estado. */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<ActividadConstitucion> generarPlan(Garantia g) {
        if (actividades.existsByGarantiaId(g.id)) {
            return actividades.findByGarantiaIdOrderByOrdenAscCodigoAsc(g.id);
        }
        var tipo = tipos.porVersion(g.tipoVersionId);
        List<PlantillaActividad> plantilla = tipos.actividades(tipo.version());
        LocalDate hoy = LocalDate.now(BOGOTA);
        List<ActividadConstitucion> plan = new ArrayList<>();
        for (PlantillaActividad p : plantilla) {
            ActividadConstitucion a = new ActividadConstitucion();
            a.id = UUID.randomUUID();
            a.garantiaId = g.id;
            a.tipoVersionId = g.tipoVersionId;
            a.codigo = p.codigo();
            a.nombre = p.nombre();
            a.descripcion = p.descripcion();
            a.orden = p.orden();
            a.obligatoria = p.obligatoria();
            a.requiereEvidencia = p.requiereEvidencia();
            a.campos = Json.arbol(Objects.requireNonNullElse(p.campos(), List.of()));
            a.rolResponsable = p.rolResponsable();
            a.fechaLimite = p.diasPlazo() == null ? null : hoy.plusDays(p.diasPlazo());
            a.estado = "PENDIENTE";
            a.datos = Json.CANONICO.createObjectNode();
            a.actualizadoPor = Contexto.usuario();
            plan.add(actividades.save(a));
        }
        if (!plan.isEmpty()) {
            auditoria.registrar("GENERAR_PLAN_CONSTITUCION", "Garantia", g.codigo, null,
                    Map.of("versionTipo", tipo.version().numero, "actividades", plan.stream().map(a -> a.codigo).toList()), null);
        }
        return plan;
    }

    /** Genera el plan de una garantía que llegó a constitución antes de que su tipo tuviera plantilla. */
    @Transactional
    public Plan generarPlan(String codigo) {
        Garantia g = garantia(codigo);
        exigirEditable(g);
        if (actividades.existsByGarantiaId(g.id)) {
            throw Errores.conflicto("PLAN_EXISTE", "La garantía " + codigo + " ya tiene plan de constitución");
        }
        if (generarPlan(g).isEmpty()) {
            throw Errores.conflicto("SIN_PLANTILLA", "La versión del tipo " + g.tipoCodigo
                    + " con la que se registró la garantía no tiene plantilla de actividades");
        }
        return plan(g);
    }

    // ------------------------------------------------------------------ consulta

    @Transactional(readOnly = true)
    public Plan plan(String codigo) {
        return plan(garantia(codigo));
    }

    private Plan plan(Garantia g) {
        List<ActividadConstitucion> lista = actividades.findByGarantiaIdOrderByOrdenAscCodigoAsc(g.id);
        return new Plan(g.codigo, g.macroestado.name(), g.perfeccionada, editable(g), lista, resumen(lista));
    }

    /** Actividades obligatorias sin completar (vacío si la garantía no tiene plan). */
    @Transactional(readOnly = true)
    public List<String> pendientesObligatorias(UUID garantiaId) {
        return actividades.findByGarantiaIdOrderByOrdenAscCodigoAsc(garantiaId).stream()
                .filter(a -> a.obligatoria && !"COMPLETADA".equals(a.estado))
                .map(a -> a.nombre + " (" + a.estado.toLowerCase().replace('_', ' ') + ")")
                .toList();
    }

    /** Bandeja de constitución: garantías en constitución o perfeccionamiento con el avance de su plan. */
    @Transactional(readOnly = true)
    public Map<String, Object> tablero() {
        LocalDate hoy = LocalDate.now(BOGOTA);
        List<Map<String, Object>> filas = jdbc.queryForList("""
                select g.codigo, g.tipo_codigo as tipo, g.cliente_nombre as "clienteNombre", g.cliente_documento as "clienteDocumento",
                       g.producto, g.macroestado, g.updated_at as "actualizada",
                       count(a.id) as total,
                       count(a.id) filter (where a.estado in ('COMPLETADA', 'NO_APLICA')) as cerradas,
                       count(a.id) filter (where a.obligatoria and a.estado <> 'COMPLETADA') as "obligatoriasPendientes",
                       count(a.id) filter (where a.estado = 'BLOQUEADA') as bloqueadas,
                       count(a.id) filter (where a.fecha_limite < ? and a.estado not in ('COMPLETADA', 'NO_APLICA')) as vencidas,
                       min(a.fecha_limite) filter (where a.estado not in ('COMPLETADA', 'NO_APLICA')) as "proximaFecha"
                from garantia g left join actividad_constitucion a on a.garantia_id = g.id
                where g.macroestado in ('CONSTITUCION', 'PERFECCIONAMIENTO') and not g.perfeccionada
                group by g.id
                order by count(a.id) filter (where a.fecha_limite < ? and a.estado not in ('COMPLETADA', 'NO_APLICA')) desc,
                         min(a.fecha_limite) filter (where a.estado not in ('COMPLETADA', 'NO_APLICA')) nulls last, g.codigo""",
                hoy, hoy);
        long sinPlan = filas.stream().filter(f -> ((Number) f.get("total")).longValue() == 0).count();
        long vencidas = filas.stream().filter(f -> ((Number) f.get("vencidas")).longValue() > 0).count();
        long bloqueadas = filas.stream().filter(f -> ((Number) f.get("bloqueadas")).longValue() > 0).count();
        long listas = filas.stream().filter(f -> ((Number) f.get("total")).longValue() > 0
                && ((Number) f.get("obligatoriasPendientes")).longValue() == 0).count();
        List<Map<String, Object>> porActividad = jdbc.queryForList("""
                select a.nombre, count(*) filter (where a.estado = 'PENDIENTE') as pendientes,
                       count(*) filter (where a.estado = 'EN_CURSO') as "enCurso",
                       count(*) filter (where a.estado = 'BLOQUEADA') as bloqueadas,
                       count(*) filter (where a.fecha_limite < ? and a.estado not in ('COMPLETADA', 'NO_APLICA')) as vencidas
                from actividad_constitucion a join garantia g on g.id = a.garantia_id
                where g.macroestado in ('CONSTITUCION', 'PERFECCIONAMIENTO') and not g.perfeccionada
                  and a.estado not in ('COMPLETADA', 'NO_APLICA')
                group by a.nombre order by count(*) desc""", hoy);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("resumen", Map.of("enCurso", filas.size(), "sinPlan", sinPlan, "conVencidas", vencidas,
                "conBloqueos", bloqueadas, "listasParaPerfeccionar", listas));
        r.put("garantias", filas);
        r.put("porActividad", porActividad);
        return r;
    }

    // ------------------------------------------------------------------ avance (RF-0602, RF-0605)

    @Transactional
    public Plan actualizar(String codigo, String codigoActividad, Cambio c) {
        Garantia g = garantia(codigo);
        exigirEditable(g);
        ActividadConstitucion a = actividades.findByGarantiaIdAndCodigo(g.id, codigoActividad)
                .orElseThrow(() -> Errores.noEncontrado("La actividad " + codigoActividad + " de " + codigo));
        Map<String, Object> antes = foto(a);
        String destino = c.estado() == null ? a.estado : c.estado();
        if (!ESTADOS.contains(destino)) {
            throw Errores.invalido("ESTADO_INVALIDO", "Estado de actividad no soportado: " + destino, List.copyOf(ESTADOS));
        }
        String observacion = blanco(c.observacion()) ? null : c.observacion().trim();
        if ("COMPLETADA".equals(a.estado) && !"COMPLETADA".equals(destino)) {
            if (!Contexto.roles().contains(Roles.OPERACIONES_DIRECTOR)) {
                throw Errores.prohibido("REQUIERE_DIRECTOR", "Reabrir una actividad completada requiere un Director de Operaciones");
            }
            exigir(observacion != null, "MOTIVO_REQUERIDO", "Reabrir una actividad exige una observación");
        }
        if ("BLOQUEADA".equals(destino) && !"BLOQUEADA".equals(a.estado)) {
            exigir(observacion != null, "MOTIVO_REQUERIDO", "Bloquear una actividad exige indicar el motivo del bloqueo");
        }
        if ("NO_APLICA".equals(destino)) {
            exigir(!a.obligatoria, "ACTIVIDAD_OBLIGATORIA", "Una actividad obligatoria no puede marcarse como no aplica");
            exigir(observacion != null, "MOTIVO_REQUERIDO", "Marcar una actividad como no aplica exige una observación");
        }
        if (c.responsable() != null) {
            a.responsable = blanco(c.responsable()) ? null : c.responsable().trim();
        }
        if (c.fechaLimite() != null) {
            a.fechaLimite = c.fechaLimite();
        }
        if (c.evidenciaRef() != null) {
            a.evidenciaRef = blanco(c.evidenciaRef()) ? null : c.evidenciaRef().trim();
        }
        if (c.evidenciaHash() != null) {
            String h = c.evidenciaHash().trim().toLowerCase();
            exigir(h.isEmpty() || SHA256.matcher(h).matches(), "HASH_INVALIDO", "La huella de la evidencia debe ser un SHA-256 (64 caracteres hexadecimales)");
            a.evidenciaHash = h.isEmpty() ? null : h;
        }
        ObjectNode datos = a.datos == null || !a.datos.isObject() ? Json.CANONICO.createObjectNode() : ((ObjectNode) a.datos).deepCopy();
        if (c.datos() != null) {
            Set<String> permitidos = new HashSet<>();
            a.campos.forEach(x -> permitidos.add(x.asText()));
            List<String> ajenos = new ArrayList<>();
            c.datos().fieldNames().forEachRemaining(n -> {
                if (!permitidos.contains(n)) {
                    ajenos.add("'" + n + "' no se captura en esta actividad");
                }
            });
            if (!ajenos.isEmpty()) {
                throw Errores.invalido("DATOS_INVALIDOS", "La actividad no admite esos datos", ajenos);
            }
            c.datos().fields().forEachRemaining(e -> {
                if (e.getValue() == null || e.getValue().isNull() || (e.getValue().isTextual() && e.getValue().asText().isBlank())) {
                    datos.remove(e.getKey());
                } else {
                    datos.set(e.getKey(), e.getValue());
                }
            });
        }
        if ("COMPLETADA".equals(destino)) {
            List<String> faltantes = new ArrayList<>();
            if (a.requiereEvidencia && (a.evidenciaRef == null || a.evidenciaHash == null)) {
                faltantes.add("La actividad exige evidencia: referencia del documento en OnBase y su huella SHA-256");
            }
            faltantes.addAll(validarDatos(g, a, datos));
            if (!faltantes.isEmpty()) {
                throw Errores.invalido("ACTIVIDAD_INCOMPLETA", "No se puede completar " + a.nombre, faltantes);
            }
        }
        a.datos = datos;
        if (observacion != null) {
            a.observacion = observacion;
        }
        boolean completa = "COMPLETADA".equals(destino) && !"COMPLETADA".equals(a.estado);
        a.estado = destino;
        if (completa) {
            a.completadaPor = Contexto.usuario();
            a.completadaEn = Instant.now();
            // Los datos del registro (escritura, folio, radicado…) pasan a los atributos de la garantía.
            if (datos.size() > 0) {
                ObjectNode atributos = g.atributos == null ? Json.CANONICO.createObjectNode() : ((ObjectNode) g.atributos).deepCopy();
                atributos.setAll(datos);
                g.atributos = atributos;
            }
            g.updatedAt = Instant.now();
        } else if (!"COMPLETADA".equals(destino)) {
            a.completadaPor = null;
            a.completadaEn = null;
        }
        a.actualizadoPor = Contexto.usuario();
        a.updatedAt = Instant.now();
        actividades.save(a);
        auditoria.registrar("ACTIVIDAD_CONSTITUCION", "Garantia", g.codigo, antes, foto(a), observacion);
        outbox.publicar("GarantiaActualizada", "Garantia", g.codigo, Map.of("garantia", g.codigo,
                "cambio", "ACTIVIDAD_CONSTITUCION", "actividad", a.codigo, "estado", a.estado));
        return plan(g);
    }

    private List<String> validarDatos(Garantia g, ActividadConstitucion a, ObjectNode datos) {
        Set<String> codigos = new HashSet<>();
        a.campos.forEach(x -> codigos.add(x.asText()));
        if (codigos.isEmpty()) {
            return List.of();
        }
        List<CampoDefinicion> requeridos = tipos.campos(tipos.porVersion(g.tipoVersionId).version()).stream()
                .filter(x -> codigos.contains(x.codigo()))
                .map(x -> new CampoDefinicion(x.codigo(), x.etiqueta(), x.tipo(), true, null, x.llave(), x.grupo(), x.orden(),
                        x.ayuda(), x.minimo(), x.maximo(), x.patron(), x.opciones()))
                .toList();
        // Un dato ya registrado en la garantía cuenta como capturado.
        ObjectNode efectivos = Json.CANONICO.createObjectNode();
        for (CampoDefinicion c : requeridos) {
            JsonNode v = datos.hasNonNull(c.codigo()) ? datos.get(c.codigo())
                    : g.atributos != null && g.atributos.hasNonNull(c.codigo()) ? g.atributos.get(c.codigo()) : null;
            if (v != null) {
                efectivos.set(c.codigo(), v);
            }
        }
        return tipos.validarAtributos(requeridos, efectivos, Macroestado.REGISTRO);
    }

    // ------------------------------------------------------------------ utilidades

    private Garantia garantia(String codigo) {
        return garantias.findByCodigo(codigo).orElseThrow(() -> Errores.noEncontrado("La garantía " + codigo));
    }

    private static boolean editable(Garantia g) {
        return (g.macroestado == Macroestado.CONSTITUCION || g.macroestado == Macroestado.PERFECCIONAMIENTO) && !g.perfeccionada;
    }

    private static void exigirEditable(Garantia g) {
        if (!editable(g)) {
            throw Errores.conflicto("PLAN_CERRADO", "El plan de constitución solo se gestiona en CONSTITUCION o PERFECCIONAMIENTO"
                    + " antes de perfeccionar (estado actual: " + g.macroestado + (g.perfeccionada ? ", perfeccionada" : "") + ")");
        }
    }

    private static void exigir(boolean condicion, String codigo, String mensaje) {
        if (!condicion) {
            throw Errores.invalido(codigo, mensaje, List.of());
        }
    }

    private static boolean blanco(String s) {
        return s == null || s.isBlank();
    }

    static Resumen resumen(List<ActividadConstitucion> lista) {
        LocalDate hoy = LocalDate.now(BOGOTA);
        int cerradas = (int) lista.stream().filter(ActividadConstitucion::cerrada).count();
        int obligatorias = (int) lista.stream().filter(a -> a.obligatoria && !"COMPLETADA".equals(a.estado)).count();
        int bloqueadas = (int) lista.stream().filter(a -> "BLOQUEADA".equals(a.estado)).count();
        int vencidas = (int) lista.stream().filter(a -> !a.cerrada() && a.fechaLimite != null && a.fechaLimite.isBefore(hoy)).count();
        return new Resumen(lista.size(), cerradas, obligatorias, bloqueadas, vencidas, !lista.isEmpty() && obligatorias == 0);
    }

    private static Map<String, Object> foto(ActividadConstitucion a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("actividad", a.codigo);
        m.put("estado", a.estado);
        m.put("responsable", a.responsable);
        m.put("fechaLimite", a.fechaLimite == null ? null : a.fechaLimite.toString());
        m.put("evidenciaRef", a.evidenciaRef);
        m.put("evidenciaHash", a.evidenciaHash);
        m.put("datos", a.datos);
        return m;
    }
}
