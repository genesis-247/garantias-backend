package co.bancopopular.garantias360.configuracion;

import co.bancopopular.garantias360.auditoria.AuditoriaService;
import co.bancopopular.garantias360.comun.Contexto;
import co.bancopopular.garantias360.comun.Errores;
import co.bancopopular.garantias360.comun.Json;
import co.bancopopular.garantias360.configuracion.CampoDefinicion.TipoCampo;
import co.bancopopular.garantias360.configuracion.DefinicionTipo.*;
import co.bancopopular.garantias360.eventos.OutboxService;
import co.bancopopular.garantias360.garantia.Macroestado;
import co.bancopopular.garantias360.seguridad.Roles;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Tipos de garantía, sus versiones y la validación de los campos personalizados (M16).
 * <p>
 * Ciclo de una versión: BORRADOR → EN_REVISION → PUBLICADA (la anterior queda REEMPLAZADA) o
 * RECHAZADA (vuelve a editarse). Quien crea o edita la versión no la aprueba (RF-1607). Las
 * garantías conservan la versión con la que se registraron: los cambios no son retroactivos.
 */
@Service
public class TipoGarantiaService {

    private static final Set<String> RESERVADOS = Set.of("id", "codigo", "tipo", "estado", "macroestado", "cliente",
            "valorComercial", "valorAdmisible", "valorNeto", "moneda", "producto", "segmento", "referencia", "aplicativo");
    private static final Pattern CODIGO = Pattern.compile("^[a-z][A-Za-z0-9]{1,49}$");
    private static final Pattern CODIGO_TIPO = Pattern.compile("^[A-Z][A-Z0-9_]{2,49}$");
    private static final Pattern CODIGO_ITEM = Pattern.compile("^[A-Z][A-Z0-9_]{1,49}$");

    private final TipoGarantiaRepositorio tipos;
    private final TipoGarantiaRepositorio.Versiones versiones;
    private final AuditoriaService auditoria;
    private final OutboxService outbox;
    private final JdbcTemplate jdbc;

    public TipoGarantiaService(TipoGarantiaRepositorio tipos, TipoGarantiaRepositorio.Versiones versiones,
                               AuditoriaService auditoria, OutboxService outbox, JdbcTemplate jdbc) {
        this.tipos = tipos;
        this.versiones = versiones;
        this.auditoria = auditoria;
        this.outbox = outbox;
        this.jdbc = jdbc;
    }

    public record TipoConVersion(TipoGarantia tipo, TipoGarantiaVersion version, List<CampoDefinicion> campos) {
    }

    /** Vista de administración: versión publicada, versión en curso y garantías que usan el tipo. */
    public record TipoAdministracion(TipoGarantia tipo, TipoGarantiaVersion publicada, TipoGarantiaVersion enCurso,
                                     long garantiasActivas) {
    }

    public record NuevoTipo(String codigo, String nombre, String descripcion, String clase, boolean requiereAvaluo,
                            boolean requierePoliza, String registroPublico, boolean admiteMultiples,
                            List<CampoDefinicion> campos, List<ItemChecklist> checklistJuridico,
                            List<PlantillaActividad> actividades) {

        public NuevoTipo(String codigo, String nombre, String clase, boolean requiereAvaluo, boolean requierePoliza,
                         String registroPublico, boolean admiteMultiples, List<CampoDefinicion> campos) {
            this(codigo, nombre, null, clase, requiereAvaluo, requierePoliza, registroPublico, admiteMultiples, campos,
                    List.of(), List.of());
        }

        public NuevoTipo con(List<ItemChecklist> checklist, List<PlantillaActividad> plantilla) {
            return new NuevoTipo(codigo, nombre, descripcion, clase, requiereAvaluo, requierePoliza, registroPublico,
                    admiteMultiples, campos, checklist, plantilla);
        }

        Edicion edicion() {
            return new Edicion(new Comportamiento(nombre, descripcion, clase, requiereAvaluo, requierePoliza,
                    Objects.requireNonNullElse(registroPublico, "NINGUNO"), admiteMultiples), campos, checklistJuridico, actividades);
        }
    }

    // ------------------------------------------------------------------ consultas

    /** Tipos con versión publicada (para registro, API y carga masiva). */
    @Transactional(readOnly = true)
    public List<TipoConVersion> listar(boolean incluirInactivos) {
        return tipos.findAllByOrderByNombre().stream()
                .filter(t -> incluirInactivos || t.activo)
                .map(t -> versiones.publicada(t.id).map(v -> new TipoConVersion(t, v, campos(v))).orElse(null))
                .filter(Objects::nonNull)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<TipoAdministracion> administracion() {
        Map<String, Long> activas = new HashMap<>();
        jdbc.query("select tipo_codigo, count(*) from garantia where macroestado not in ('CIERRE', 'ANULADA') group by tipo_codigo",
                rs -> {
                    activas.put(rs.getString(1), rs.getLong(2));
                });
        return tipos.findAllByOrderByNombre().stream()
                .map(t -> new TipoAdministracion(t, versiones.publicada(t.id).orElse(null), versiones.enCurso(t.id).orElse(null),
                        activas.getOrDefault(t.codigo, 0L)))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<TipoGarantiaVersion> versiones(String codigo) {
        return versiones.findByTipoIdOrderByNumeroDesc(tipo(codigo).id);
    }

    @Transactional(readOnly = true)
    public TipoConVersion version(String codigo, int numero) {
        TipoGarantia t = tipo(codigo);
        TipoGarantiaVersion v = versiones.findByTipoIdAndNumero(t.id, numero)
                .orElseThrow(() -> Errores.noEncontrado("La versión " + numero + " del tipo " + codigo));
        return new TipoConVersion(t, v, campos(v));
    }

    @Transactional(readOnly = true)
    public TipoConVersion vigente(String codigo) {
        TipoGarantia t = tipo(codigo);
        if (!t.activo) {
            throw Errores.conflicto("TIPO_INACTIVO", "El tipo de garantía " + codigo + " está inactivo");
        }
        TipoGarantiaVersion v = versiones.publicada(t.id)
                .orElseThrow(() -> Errores.conflicto("TIPO_SIN_VERSION", "El tipo " + codigo + " no tiene una versión publicada"));
        return new TipoConVersion(t, v, campos(v));
    }

    @Transactional(readOnly = true)
    public TipoConVersion porVersion(UUID versionId) {
        TipoGarantiaVersion v = versiones.findById(versionId).orElseThrow(() -> Errores.noEncontrado("La versión de tipo"));
        TipoGarantia t = tipos.findById(v.tipoId).orElseThrow();
        return new TipoConVersion(t, v, campos(v));
    }

    private TipoGarantia tipo(String codigo) {
        return tipos.findByCodigo(codigo).orElseThrow(() -> Errores.noEncontrado("El tipo de garantía " + codigo));
    }

    // ------------------------------------------------------------------ edición y maker–checker (RF-1601, RF-1607)

    /** Crea el tipo con su versión 1 en borrador. Queda disponible cuando otro administrador la aprueba. */
    @Transactional
    public TipoGarantiaVersion crear(NuevoTipo nuevo) {
        if (nuevo.codigo() == null || !CODIGO_TIPO.matcher(nuevo.codigo()).matches()) {
            throw Errores.invalido("CODIGO_INVALIDO", "El código del tipo debe ir en MAYÚSCULAS_CON_GUIONES (3 a 50 caracteres)", List.of());
        }
        if (tipos.findByCodigo(nuevo.codigo()).isPresent()) {
            throw Errores.conflicto("TIPO_EXISTE", "Ya existe el tipo " + nuevo.codigo());
        }
        Edicion e = nuevo.edicion();
        exigirSinErrores(validar(e, null));
        TipoGarantia t = new TipoGarantia();
        t.id = UUID.randomUUID();
        t.codigo = nuevo.codigo();
        t.activo = true;
        copiarComportamiento(t, e.comportamiento());
        tipos.save(t);
        TipoGarantiaVersion v = new TipoGarantiaVersion();
        v.id = UUID.randomUUID();
        v.tipoId = t.id;
        v.numero = 1;
        v.estado = "BORRADOR";
        aplicar(v, e);
        versiones.save(v);
        auditoria.registrar("CREAR_TIPO_GARANTIA", "TipoGarantia", t.codigo, null, instantanea(v), null);
        return v;
    }

    /** Nueva versión en borrador a partir de la publicada (RF-1601: editar = versionar). */
    @Transactional
    public TipoGarantiaVersion nuevaVersion(String codigo) {
        TipoGarantia t = tipo(codigo);
        versiones.enCurso(t.id).ifPresent(v -> {
            throw Errores.conflicto("VERSION_EN_CURSO", "El tipo ya tiene la versión " + v.numero + " en " + v.estado);
        });
        TipoGarantiaVersion base = versiones.publicada(t.id)
                .orElseThrow(() -> Errores.conflicto("TIPO_SIN_VERSION", "El tipo " + codigo + " no tiene una versión publicada"));
        TipoGarantiaVersion v = new TipoGarantiaVersion();
        v.id = UUID.randomUUID();
        v.tipoId = t.id;
        v.numero = versiones.ultimoNumero(t.id) + 1;
        v.estado = "BORRADOR";
        v.comportamiento = base.comportamiento.deepCopy();
        v.campos = base.campos.deepCopy();
        v.checklistJuridico = base.checklistJuridico.deepCopy();
        v.actividades = base.actividades.deepCopy();
        v.creadoPor = Contexto.usuario();
        versiones.save(v);
        auditoria.registrar("NUEVA_VERSION_TIPO", "TipoGarantia", codigo, null, Map.of("version", v.numero, "base", base.numero), null);
        return v;
    }

    @Transactional
    public TipoGarantiaVersion editar(String codigo, int numero, Edicion e) {
        TipoGarantiaVersion v = versionEditable(codigo, numero, "BORRADOR", "RECHAZADA");
        TipoGarantiaVersion publicada = versiones.publicada(v.tipoId).orElse(null);
        exigirSinErrores(validar(e, publicada));
        Map<String, Object> antes = instantanea(v);
        aplicar(v, e);
        v.estado = "BORRADOR";
        v.motivo = null;
        auditoria.registrar("EDITAR_TIPO_GARANTIA", "TipoGarantia", codigo, antes, instantanea(v), null);
        return v;
    }

    @Transactional
    public TipoGarantiaVersion enviar(String codigo, int numero) {
        TipoGarantiaVersion v = versionEditable(codigo, numero, "BORRADOR");
        TipoGarantiaVersion publicada = versiones.publicada(v.tipoId).orElse(null);
        exigirSinErrores(validar(edicion(v), publicada));
        v.estado = "EN_REVISION";
        v.enviadaEn = Instant.now();
        v.hash = Json.hash(Map.of("comportamiento", v.comportamiento, "campos", v.campos,
                "checklistJuridico", v.checklistJuridico, "actividades", v.actividades));
        auditoria.registrar("ENVIAR_TIPO_GARANTIA", "TipoGarantia", codigo, null, Map.of("version", v.numero, "hash", v.hash), null);
        return v;
    }

    @Transactional
    public TipoGarantiaVersion aprobar(String codigo, int numero) {
        TipoGarantia t = tipo(codigo);
        TipoGarantiaVersion v = versionEditable(codigo, numero, "EN_REVISION");
        exigirOtroUsuario(v);
        versiones.publicada(t.id).ifPresent(anterior -> {
            anterior.estado = "REEMPLAZADA";
            versiones.saveAndFlush(anterior);
        });
        v.estado = "PUBLICADA";
        v.aprobadoPor = Contexto.usuario();
        v.aprobadaEn = Instant.now();
        Map<String, Object> antes = Map.of("nombre", t.nombre, "clase", t.clase, "registroPublico", t.registroPublico);
        copiarComportamiento(t, Json.leer(v.comportamiento, Comportamiento.class));
        auditoria.registrar("PUBLICAR_TIPO_GARANTIA", "TipoGarantia", codigo, antes,
                Map.of("version", v.numero, "hash", v.hash, "creadoPor", v.creadoPor), null);
        outbox.publicar("TipoGarantiaPublicado", "TipoGarantia", codigo,
                Map.of("tipo", codigo, "version", v.numero, "hash", v.hash, "esquema", "/api/v1/tipos-garantia/" + codigo + "/esquema"));
        return v;
    }

    @Transactional
    public TipoGarantiaVersion rechazar(String codigo, int numero, String motivo) {
        exigirMotivo(motivo);
        TipoGarantiaVersion v = versionEditable(codigo, numero, "EN_REVISION");
        exigirOtroUsuario(v);
        v.estado = "RECHAZADA";
        v.motivo = motivo;
        auditoria.registrar("RECHAZAR_TIPO_GARANTIA", "TipoGarantia", codigo, null, Map.of("version", v.numero), motivo);
        return v;
    }

    /** Descarta un borrador nunca publicado. La versión 1 de un tipo sin publicar se conserva para editarla. */
    @Transactional
    public void descartar(String codigo, int numero) {
        TipoGarantiaVersion v = versionEditable(codigo, numero, "BORRADOR", "RECHAZADA");
        if (versiones.publicada(v.tipoId).isEmpty()) {
            throw Errores.conflicto("SIN_VERSION_PUBLICADA", "Es la única versión del tipo: edítala en lugar de descartarla");
        }
        auditoria.registrar("DESCARTAR_VERSION_TIPO", "TipoGarantia", codigo, instantanea(v), null, null);
        versiones.delete(v);
    }

    /** RF-1601: inactivar advierte cuántas garantías activas usan el tipo, pero no lo impide. */
    @Transactional
    public TipoAdministracion cambiarActivo(String codigo, boolean activo, String motivo) {
        exigirMotivo(motivo);
        TipoGarantia t = tipo(codigo);
        if (t.activo == activo) {
            throw Errores.conflicto("SIN_CAMBIO", "El tipo ya está " + (activo ? "activo" : "inactivo"));
        }
        long enUso = garantiasActivas(codigo);
        t.activo = activo;
        t.inactivadoPor = activo ? null : Contexto.usuario();
        t.inactivadoEn = activo ? null : Instant.now();
        t.motivoInactivacion = activo ? null : motivo;
        auditoria.registrar(activo ? "REACTIVAR_TIPO_GARANTIA" : "INACTIVAR_TIPO_GARANTIA", "TipoGarantia", codigo,
                Map.of("activo", !activo), Map.of("activo", activo, "garantiasActivas", enUso), motivo);
        return new TipoAdministracion(t, versiones.publicada(t.id).orElse(null), versiones.enCurso(t.id).orElse(null), enUso);
    }

    public long garantiasActivas(String codigo) {
        Long n = jdbc.queryForObject("select count(*) from garantia where tipo_codigo = ? and macroestado not in ('CIERRE', 'ANULADA')",
                Long.class, codigo);
        return n == null ? 0 : n;
    }

    private TipoGarantiaVersion versionEditable(String codigo, int numero, String... estados) {
        TipoGarantia t = tipo(codigo);
        TipoGarantiaVersion v = versiones.findByTipoIdAndNumero(t.id, numero)
                .orElseThrow(() -> Errores.noEncontrado("La versión " + numero + " del tipo " + codigo));
        if (!Arrays.asList(estados).contains(v.estado)) {
            throw Errores.conflicto("ESTADO_VERSION", "La versión " + numero + " está en " + v.estado
                    + "; la operación exige " + String.join(" o ", estados));
        }
        return v;
    }

    private static void exigirOtroUsuario(TipoGarantiaVersion v) {
        if (!Contexto.roles().contains(Roles.ADMIN_FUNCIONAL)) {
            throw Errores.prohibido("ROL_REQUERIDO", "La publicación la aprueba un Administrador funcional");
        }
        if (Contexto.usuario().equals(v.creadoPor)) {
            throw Errores.prohibido("MAKER_CHECKER",
                    "Quien creó o editó la versión (" + v.creadoPor + ") no puede aprobarla ni rechazarla");
        }
    }

    private static void exigirMotivo(String motivo) {
        if (motivo == null || motivo.isBlank()) {
            throw Errores.invalido("MOTIVO_REQUERIDO", "Esta operación exige un motivo", List.of());
        }
    }

    private static void exigirSinErrores(List<String> errores) {
        if (!errores.isEmpty()) {
            throw Errores.invalido("DEFINICION_INVALIDA", "La definición del tipo tiene errores", errores);
        }
    }

    private void aplicar(TipoGarantiaVersion v, Edicion e) {
        v.comportamiento = Json.arbol(e.comportamiento());
        v.campos = Json.arbol(Objects.requireNonNullElse(e.campos(), List.of()));
        v.checklistJuridico = Json.arbol(Objects.requireNonNullElse(e.checklistJuridico(), List.of()));
        v.actividades = Json.arbol(Objects.requireNonNullElse(e.actividades(), List.of()));
        v.creadoPor = Contexto.usuario();
    }

    private static void copiarComportamiento(TipoGarantia t, Comportamiento c) {
        t.nombre = c.nombre();
        t.descripcion = c.descripcion();
        t.clase = c.clase();
        t.requiereAvaluo = c.requiereAvaluo();
        t.requierePoliza = c.requierePoliza();
        t.registroPublico = Objects.requireNonNullElse(c.registroPublico(), "NINGUNO");
        t.admiteMultiples = c.admiteMultiples();
    }

    public Edicion edicion(TipoGarantiaVersion v) {
        return new Edicion(Json.leer(v.comportamiento, Comportamiento.class), campos(v), checklist(v), actividades(v));
    }

    private static Map<String, Object> instantanea(TipoGarantiaVersion v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", v.numero);
        m.put("estado", v.estado);
        m.put("comportamiento", v.comportamiento);
        m.put("campos", v.campos);
        m.put("checklistJuridico", v.checklistJuridico);
        m.put("actividades", v.actividades);
        return m;
    }

    public List<CampoDefinicion> campos(TipoGarantiaVersion v) {
        return v.campos == null ? List.of() : Arrays.asList(Json.leer(v.campos, CampoDefinicion[].class));
    }

    public List<ItemChecklist> checklist(TipoGarantiaVersion v) {
        return v.checklistJuridico == null ? List.of() : Arrays.asList(Json.leer(v.checklistJuridico, ItemChecklist[].class));
    }

    public List<PlantillaActividad> actividades(TipoGarantiaVersion v) {
        return v.actividades == null ? List.of() : Arrays.asList(Json.leer(v.actividades, PlantillaActividad[].class));
    }

    // ------------------------------------------------------------------ validación de la definición

    /** Errores de la definición completa; con la versión publicada verifica la estabilidad de las opciones. */
    public List<String> validar(Edicion e, TipoGarantiaVersion publicada) {
        List<String> errores = new ArrayList<>();
        Comportamiento c = e.comportamiento();
        if (c == null) {
            errores.add("Falta el comportamiento del tipo");
        } else {
            if (c.nombre() == null || c.nombre().isBlank()) {
                errores.add("El nombre del tipo es obligatorio");
            }
            if (c.clase() == null || !CODIGO_ITEM.matcher(c.clase()).matches()) {
                errores.add("La clase es obligatoria y va en MAYÚSCULAS_CON_GUIONES");
            }
            if (c.registroPublico() != null && !DefinicionTipo.REGISTROS.contains(c.registroPublico())) {
                errores.add("Registro público no soportado: " + c.registroPublico() + " (admitidos: " + DefinicionTipo.REGISTROS + ")");
            }
        }
        List<CampoDefinicion> campos = Objects.requireNonNullElse(e.campos(), List.of());
        errores.addAll(validarDefinicion(campos));
        Set<String> codigosCampo = new HashSet<>();
        campos.forEach(x -> codigosCampo.add(x.codigo()));

        Set<String> items = new HashSet<>();
        for (ItemChecklist i : Objects.requireNonNullElse(e.checklistJuridico(), List.<ItemChecklist>of())) {
            String p = "Checklist '" + i.codigo() + "': ";
            if (i.codigo() == null || !CODIGO_ITEM.matcher(i.codigo()).matches()) {
                errores.add(p + "el código va en MAYÚSCULAS_CON_GUIONES");
            } else if (!items.add(i.codigo())) {
                errores.add(p + "código duplicado");
            }
            if (i.descripcion() == null || i.descripcion().isBlank()) {
                errores.add(p + "falta la descripción");
            }
        }

        Set<String> actividades = new HashSet<>();
        for (PlantillaActividad a : Objects.requireNonNullElse(e.actividades(), List.<PlantillaActividad>of())) {
            String p = "Actividad '" + a.codigo() + "': ";
            if (a.codigo() == null || !CODIGO_ITEM.matcher(a.codigo()).matches()) {
                errores.add(p + "el código va en MAYÚSCULAS_CON_GUIONES");
            } else if (!actividades.add(a.codigo())) {
                errores.add(p + "código duplicado");
            }
            if (a.nombre() == null || a.nombre().isBlank()) {
                errores.add(p + "falta el nombre");
            }
            if (a.diasPlazo() != null && (a.diasPlazo() < 0 || a.diasPlazo() > 365)) {
                errores.add(p + "el plazo debe estar entre 0 y 365 días");
            }
            if (a.rolResponsable() != null && !a.rolResponsable().isBlank() && !Roles.CATALOGO.containsKey(a.rolResponsable())) {
                errores.add(p + "el rol responsable no existe: " + a.rolResponsable());
            }
            for (String campo : Objects.requireNonNullElse(a.campos(), List.<String>of())) {
                if (!codigosCampo.contains(campo)) {
                    errores.add(p + "captura el campo '" + campo + "', que no está definido en el tipo");
                }
            }
        }

        if (publicada != null) {
            // Opciones con identidad estable (RF-1603): una opción publicada se inactiva, nunca se elimina.
            Map<String, CampoDefinicion> nuevos = new HashMap<>();
            campos.forEach(x -> nuevos.put(x.codigo(), x));
            for (CampoDefinicion anterior : campos(publicada)) {
                CampoDefinicion actual = nuevos.get(anterior.codigo());
                if (actual == null || anterior.opciones() == null) {
                    continue;
                }
                Set<String> ids = new HashSet<>();
                Objects.requireNonNullElse(actual.opciones(), List.<CampoDefinicion.Opcion>of()).forEach(o -> ids.add(o.id()));
                anterior.opciones().stream().filter(o -> !ids.contains(o.id())).forEach(o -> errores.add("Campo '"
                        + anterior.codigo() + "': la opción '" + o.id() + "' ya fue publicada; inactívala en lugar de eliminarla"));
            }
        }
        return errores;
    }

    /** Errores de la definición de campos (RF-1603). */
    public List<String> validarDefinicion(List<CampoDefinicion> campos) {
        List<String> errores = new ArrayList<>();
        Set<String> vistos = new HashSet<>();
        for (CampoDefinicion c : Objects.requireNonNullElse(campos, List.<CampoDefinicion>of())) {
            String p = "Campo '" + c.codigo() + "': ";
            if (c.codigo() == null || !CODIGO.matcher(c.codigo()).matches()) {
                errores.add(p + "el código debe ser camelCase alfanumérico");
            } else if (RESERVADOS.contains(c.codigo())) {
                errores.add(p + "el código está reservado para un campo transversal");
            } else if (!vistos.add(c.codigo())) {
                errores.add(p + "código duplicado");
            }
            if (c.etiqueta() == null || c.etiqueta().isBlank()) {
                errores.add(p + "falta la etiqueta");
            }
            if (c.tipo() == null) {
                errores.add(p + "falta el tipo de dato");
            }
            if ((c.tipo() == TipoCampo.LISTA || c.tipo() == TipoCampo.LISTA_MULTIPLE)
                    && (c.opciones() == null || c.opciones().isEmpty())) {
                errores.add(p + "una lista necesita opciones");
            }
            if (c.opciones() != null) {
                Set<String> ids = new HashSet<>();
                c.opciones().forEach(o -> {
                    if (o.id() == null || o.id().isBlank() || !ids.add(o.id())) {
                        errores.add(p + "las opciones necesitan un identificador único");
                    }
                });
            }
            if (c.minimo() != null && c.maximo() != null && c.minimo().compareTo(c.maximo()) > 0) {
                errores.add(p + "el mínimo es mayor que el máximo");
            }
            if (c.obligatorioDesde() != null) {
                try {
                    Macroestado.valueOf(c.obligatorioDesde());
                } catch (IllegalArgumentException e) {
                    errores.add(p + "obligatorioDesde no es un macroestado válido");
                }
            }
            if (c.patron() != null) {
                try {
                    Pattern.compile(c.patron());
                } catch (PatternSyntaxException e) {
                    errores.add(p + "patrón inválido");
                }
            }
        }
        return errores;
    }

    // ------------------------------------------------------------------ JSON Schema (RF-1609)

    /** JSON Schema (draft 2020-12) de los atributos del tipo en su versión publicada. */
    @Transactional(readOnly = true)
    public ObjectNode esquema(String codigo) {
        TipoConVersion t = vigente(codigo);
        ObjectNode s = Json.CANONICO.createObjectNode();
        s.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        s.put("$id", "/api/v1/tipos-garantia/" + codigo + "/esquema?version=" + t.version().numero);
        s.put("title", t.tipo().nombre);
        s.put("description", "Atributos del tipo " + codigo + ", versión " + t.version().numero
                + ". Obligatoriedad al registrar; los campos con x-obligatorioDesde se exigen desde ese macroestado.");
        s.put("type", "object");
        s.put("additionalProperties", false);
        ObjectNode props = s.putObject("properties");
        ArrayNode requeridos = s.putArray("required");
        for (CampoDefinicion c : t.campos()) {
            ObjectNode p = props.putObject(c.codigo());
            p.put("title", c.etiqueta());
            if (c.ayuda() != null) {
                p.put("description", c.ayuda());
            }
            switch (c.tipo()) {
                case NUMERO, MONEDA -> {
                    p.put("type", "number");
                    if (c.minimo() != null) {
                        p.put("minimum", c.minimo());
                    }
                    if (c.maximo() != null) {
                        p.put("maximum", c.maximo());
                    }
                }
                case FECHA -> p.put("type", "string").put("format", "date");
                case BOOLEANO -> p.put("type", "boolean");
                case LISTA -> {
                    p.put("type", "string");
                    ArrayNode e = p.putArray("enum");
                    c.opciones().stream().filter(CampoDefinicion.Opcion::activo).forEach(o -> e.add(o.id()));
                }
                case LISTA_MULTIPLE -> {
                    p.put("type", "array");
                    ArrayNode e = p.putObject("items").put("type", "string").putArray("enum");
                    c.opciones().stream().filter(CampoDefinicion.Opcion::activo).forEach(o -> e.add(o.id()));
                }
                case TEXTO, TEXTO_LARGO -> {
                    p.put("type", "string");
                    if (c.tipo() == TipoCampo.TEXTO) {
                        p.put("maxLength", 200);
                    }
                    if (c.patron() != null) {
                        p.put("pattern", c.patron());
                    }
                }
            }
            if (c.llave()) {
                p.put("x-llaveNatural", true);
            }
            if (c.grupo() != null) {
                p.put("x-grupo", c.grupo());
            }
            if (c.obligatorio() && c.obligatorioDesde() == null) {
                requeridos.add(c.codigo());
            } else if (c.obligatorio()) {
                p.put("x-obligatorioDesde", c.obligatorioDesde());
            }
        }
        return s;
    }

    // ------------------------------------------------------------------ validación de atributos de una garantía

    /**
     * Valida los atributos de una garantía contra los campos de su versión para el macroestado
     * dado: tipos, rangos, patrones, opciones activas y obligatoriedad por estado (RF-1604).
     */
    public List<String> validarAtributos(List<CampoDefinicion> campos, JsonNode atributos, Macroestado estado) {
        List<String> errores = new ArrayList<>();
        Map<String, CampoDefinicion> porCodigo = new HashMap<>();
        campos.forEach(c -> porCodigo.put(c.codigo(), c));
        if (atributos != null) {
            atributos.fieldNames().forEachRemaining(n -> {
                if (!porCodigo.containsKey(n)) {
                    errores.add("'" + n + "' no es un campo del tipo de garantía");
                }
            });
        }
        for (CampoDefinicion c : campos) {
            JsonNode valor = atributos == null ? null : atributos.get(c.codigo());
            boolean vacio = valor == null || valor.isNull() || (valor.isTextual() && valor.asText().isBlank());
            if (vacio) {
                if (c.obligatorio() && exigibleEn(c, estado)) {
                    errores.add("'" + c.etiqueta() + "' es obligatorio" + (c.obligatorioDesde() == null ? ""
                            : " desde " + c.obligatorioDesde()));
                }
                continue;
            }
            validarValor(c, valor, errores);
        }
        return errores;
    }

    private static boolean exigibleEn(CampoDefinicion c, Macroestado estado) {
        if (c.obligatorioDesde() == null) {
            return true;
        }
        return estado.ordinal() >= Macroestado.valueOf(c.obligatorioDesde()).ordinal() && estado != Macroestado.ANULADA;
    }

    private static void validarValor(CampoDefinicion c, JsonNode valor, List<String> errores) {
        String e = "'" + c.etiqueta() + "': ";
        switch (c.tipo()) {
            case NUMERO, MONEDA -> {
                BigDecimal n;
                try {
                    n = valor.isNumber() ? valor.decimalValue() : new BigDecimal(valor.asText());
                } catch (NumberFormatException ex) {
                    errores.add(e + "debe ser numérico");
                    return;
                }
                if (c.minimo() != null && n.compareTo(c.minimo()) < 0) {
                    errores.add(e + "debe ser mayor o igual a " + c.minimo().toPlainString());
                }
                if (c.maximo() != null && n.compareTo(c.maximo()) > 0) {
                    errores.add(e + "debe ser menor o igual a " + c.maximo().toPlainString());
                }
            }
            case FECHA -> {
                try {
                    LocalDate.parse(valor.asText());
                } catch (DateTimeParseException ex) {
                    errores.add(e + "debe ser una fecha AAAA-MM-DD");
                }
            }
            case BOOLEANO -> {
                if (!valor.isBoolean()) {
                    errores.add(e + "debe ser verdadero o falso");
                }
            }
            case LISTA -> validarOpcion(c, valor.asText(), errores);
            case LISTA_MULTIPLE -> {
                if (!valor.isArray()) {
                    errores.add(e + "debe ser una lista");
                } else {
                    valor.forEach(v -> validarOpcion(c, v.asText(), errores));
                }
            }
            case TEXTO, TEXTO_LARGO -> {
                if (c.patron() != null && !Pattern.matches(c.patron(), valor.asText())) {
                    errores.add(e + "no tiene el formato esperado" + (c.ayuda() != null ? " (" + c.ayuda() + ")" : ""));
                }
                if (c.tipo() == TipoCampo.TEXTO && valor.asText().length() > 200) {
                    errores.add(e + "supera 200 caracteres");
                }
            }
        }
    }

    private static void validarOpcion(CampoDefinicion c, String id, List<String> errores) {
        boolean valida = c.opciones().stream().anyMatch(o -> o.id().equals(id) && o.activo());
        if (!valida) {
            errores.add("'" + c.etiqueta() + "': la opción '" + id + "' no existe o está inactiva");
        }
    }
}
