package co.bancopopular.garantias360.reglas;

import co.bancopopular.garantias360.auditoria.AuditoriaService;
import co.bancopopular.garantias360.comun.Contexto;
import co.bancopopular.garantias360.comun.Errores;
import co.bancopopular.garantias360.comun.Json;
import co.bancopopular.garantias360.eventos.OutboxService;
import co.bancopopular.garantias360.reglas.ReglaVersion.EstadoRegla;
import co.bancopopular.garantias360.reglas.motor.EvaluadorTabla;
import co.bancopopular.garantias360.reglas.motor.TablaDecision;
import co.bancopopular.garantias360.reglas.motor.TipoRegla;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * Ciclo de vida de reglas (anexo B, B.4): Borrador → En revisión → Aprobada → Activa, con
 * maker–checker (RF-1405): quien crea o edita una versión no puede aprobarla.
 */
@Service
public class ReglaService {

    private final ReglaRepositorios.Reglas reglas;
    private final ReglaRepositorios.Versiones versiones;
    private final AuditoriaService auditoria;
    private final OutboxService outbox;
    private final ApplicationEventPublisher eventos;
    private final EvaluadorTabla evaluador = new EvaluadorTabla();

    public ReglaService(ReglaRepositorios.Reglas reglas, ReglaRepositorios.Versiones versiones,
                        AuditoriaService auditoria, OutboxService outbox, ApplicationEventPublisher eventos) {
        this.reglas = reglas;
        this.versiones = versiones;
        this.auditoria = auditoria;
        this.outbox = outbox;
        this.eventos = eventos;
    }

    /** Se publica tras activar una regla para que cobertura e idoneidad se recalculen. */
    public record ReglaActivada(TipoRegla tipo, String codigo, int version) {
    }

    public record NuevaRegla(String codigo, String nombre, String descripcion, TipoRegla tipo,
                             TablaDecision definicion, List<TablaDecision.CasoPrueba> casosPrueba) {
    }

    public record EdicionVersion(TablaDecision definicion, List<TablaDecision.CasoPrueba> casosPrueba,
                                 LocalDate vigenteDesde, LocalDate vigenteHasta) {
    }

    public record ResultadoPruebas(boolean ok, List<String> erroresValidacion, List<EvaluadorTabla.ResultadoPrueba> casos) {
    }

    // ------------------------------------------------------------------ consultas

    @Transactional(readOnly = true)
    public List<Regla> listar() {
        return reglas.findAllByOrderByTipoAscCodigoAsc();
    }

    @Transactional(readOnly = true)
    public Regla regla(String codigo) {
        return reglas.findByCodigo(codigo).orElseThrow(() -> Errores.noEncontrado("La regla " + codigo));
    }

    @Transactional(readOnly = true)
    public List<ReglaVersion> versiones(String codigo) {
        return versiones.findByReglaIdOrderByNumeroDesc(regla(codigo).id);
    }

    @Transactional(readOnly = true)
    public ReglaVersion version(String codigo, int numero) {
        return versiones.findByReglaIdAndNumero(regla(codigo).id, numero)
                .orElseThrow(() -> Errores.noEncontrado("La versión " + numero + " de " + codigo));
    }

    // ------------------------------------------------------------------ edición (maker)

    @Transactional
    public ReglaVersion crear(NuevaRegla nueva) {
        if (reglas.findByCodigo(nueva.codigo()).isPresent()) {
            throw Errores.conflicto("REGLA_EXISTE", "Ya existe una regla con código " + nueva.codigo());
        }
        reglas.findAll().stream().filter(r -> r.tipo == nueva.tipo()).findFirst().ifPresent(r -> {
            throw Errores.conflicto("TIPO_CON_REGLA", "Ya existe la regla " + r.codigo + " de tipo " + nueva.tipo()
                    + ". Crea una versión nueva de esa regla en lugar de otra regla del mismo tipo.");
        });
        validarDefinicion(nueva.tipo(), nueva.definicion());
        Regla r = new Regla();
        r.id = UUID.randomUUID();
        r.codigo = nueva.codigo();
        r.nombre = nueva.nombre();
        r.descripcion = nueva.descripcion();
        r.tipo = nueva.tipo();
        reglas.save(r);
        ReglaVersion v = nuevaVersion(r, 1, nueva.definicion(), nueva.casosPrueba());
        auditoria.registrar("CREAR_REGLA", "Regla", r.codigo, null, resumen(r, v), null);
        return v;
    }

    /** Nueva versión en borrador a partir de otra (clonar, RF-1404). */
    @Transactional
    public ReglaVersion nuevaVersionDesde(String codigo, int base) {
        Regla r = regla(codigo);
        ReglaVersion origen = version(codigo, base);
        int siguiente = versiones.findByReglaIdOrderByNumeroDesc(r.id).getFirst().numero + 1;
        ReglaVersion v = nuevaVersion(r, siguiente, Json.leer(origen.definicion, TablaDecision.class), casos(origen));
        auditoria.registrar("NUEVA_VERSION_REGLA", "Regla", r.codigo, null, resumen(r, v), "Clonada de v" + base);
        return v;
    }

    @Transactional
    public ReglaVersion editar(String codigo, int numero, EdicionVersion edicion) {
        Regla r = regla(codigo);
        ReglaVersion v = version(codigo, numero);
        exigirEstado(v, EstadoRegla.BORRADOR, EstadoRegla.RECHAZADA);
        validarDefinicion(r.tipo, edicion.definicion());
        Object antes = resumen(r, v);
        v.definicion = Json.arbol(edicion.definicion());
        v.casosPrueba = Json.arbol(Objects.requireNonNullElse(edicion.casosPrueba(), List.of()));
        v.vigenteDesde = edicion.vigenteDesde();
        v.vigenteHasta = edicion.vigenteHasta();
        v.estado = EstadoRegla.BORRADOR;
        v.creadoPor = Contexto.usuario();
        v.motivo = null;
        auditoria.registrar("EDITAR_REGLA", "Regla", r.codigo, antes, resumen(r, v), null);
        return v;
    }

    @Transactional(readOnly = true)
    public ResultadoPruebas probar(String codigo, int numero) {
        Regla r = regla(codigo);
        ReglaVersion v = version(codigo, numero);
        TablaDecision tabla = Json.leer(v.definicion, TablaDecision.class);
        List<String> errores = evaluador.validar(r.tipo, tabla);
        List<EvaluadorTabla.ResultadoPrueba> resultados = errores.isEmpty()
                ? evaluador.probar(r.tipo, tabla, casos(v)) : List.of();
        boolean ok = errores.isEmpty() && !resultados.isEmpty()
                && resultados.stream().allMatch(EvaluadorTabla.ResultadoPrueba::ok);
        return new ResultadoPruebas(ok, errores, resultados);
    }

    /** Enviar a revisión exige casos de prueba en verde (B.4). */
    @Transactional
    public ReglaVersion enviar(String codigo, int numero) {
        Regla r = regla(codigo);
        ReglaVersion v = version(codigo, numero);
        exigirEstado(v, EstadoRegla.BORRADOR);
        ResultadoPruebas pruebas = probar(codigo, numero);
        if (!pruebas.ok()) {
            List<String> detalles = new ArrayList<>(pruebas.erroresValidacion());
            if (pruebas.casos().isEmpty() && pruebas.erroresValidacion().isEmpty()) {
                detalles.add("La versión no tiene casos de prueba");
            }
            pruebas.casos().stream().filter(c -> !c.ok())
                    .forEach(c -> detalles.add("Caso '" + c.nombre() + "': esperado " + c.esperado() + ", obtenido "
                            + Objects.requireNonNullElse(c.obtenido(), c.error())));
            throw Errores.invalido("PRUEBAS_FALLIDAS", "La regla no pasa sus casos de prueba", detalles);
        }
        v.estado = EstadoRegla.EN_REVISION;
        v.enviadaEn = Instant.now();
        v.hash = Json.hash(Map.of("definicion", v.definicion, "casos", v.casosPrueba, "tipo", r.tipo.name()));
        auditoria.registrar("ENVIAR_REGLA", "Regla", r.codigo, null, resumen(r, v), null);
        return v;
    }

    // ------------------------------------------------------------------ aprobación (checker)

    @Transactional
    public ReglaVersion aprobar(String codigo, int numero) {
        Regla r = regla(codigo);
        ReglaVersion v = version(codigo, numero);
        exigirEstado(v, EstadoRegla.EN_REVISION);
        exigirOtroUsuario(v);
        v.estado = EstadoRegla.APROBADA;
        v.aprobadoPor = Contexto.usuario();
        v.aprobadaEn = Instant.now();
        auditoria.registrar("APROBAR_REGLA", "Regla", r.codigo, null, resumen(r, v), null);
        return v;
    }

    @Transactional
    public ReglaVersion rechazar(String codigo, int numero, String motivo) {
        if (motivo == null || motivo.isBlank()) {
            throw Errores.invalido("MOTIVO_REQUERIDO", "El rechazo exige un motivo", List.of());
        }
        Regla r = regla(codigo);
        ReglaVersion v = version(codigo, numero);
        exigirEstado(v, EstadoRegla.EN_REVISION);
        exigirOtroUsuario(v);
        v.estado = EstadoRegla.RECHAZADA;
        v.motivo = motivo;
        auditoria.registrar("RECHAZAR_REGLA", "Regla", r.codigo, null, resumen(r, v), motivo);
        return v;
    }

    @Transactional
    public ReglaVersion activar(String codigo, int numero) {
        Regla r = regla(codigo);
        ReglaVersion v = version(codigo, numero);
        exigirEstado(v, EstadoRegla.APROBADA);
        if (v.vigenteDesde != null && v.vigenteDesde.isAfter(LocalDate.now())) {
            throw Errores.conflicto("VIGENCIA_FUTURA", "La versión entra en vigencia el " + v.vigenteDesde);
        }
        versiones.findByReglaIdOrderByNumeroDesc(r.id).stream()
                .filter(x -> x.estado == EstadoRegla.ACTIVA)
                .forEach(x -> {
                    x.estado = EstadoRegla.REEMPLAZADA;
                    versiones.saveAndFlush(x);
                });
        v.estado = EstadoRegla.ACTIVA;
        v.activadaEn = Instant.now();
        versiones.saveAndFlush(v);
        auditoria.registrar("ACTIVAR_REGLA", "Regla", r.codigo, null, resumen(r, v), null);
        outbox.publicar("ReglaActivada", "Regla", r.codigo, Map.of("regla", r.codigo, "tipo", r.tipo.name(),
                "version", v.numero, "hash", v.hash, "aprobadoPor", v.aprobadoPor));
        eventos.publishEvent(new ReglaActivada(r.tipo, r.codigo, v.numero));
        return v;
    }

    @Transactional
    public ReglaVersion desactivar(String codigo, int numero, String motivo) {
        Regla r = regla(codigo);
        ReglaVersion v = version(codigo, numero);
        exigirEstado(v, EstadoRegla.ACTIVA);
        v.estado = EstadoRegla.INACTIVA;
        v.motivo = motivo;
        auditoria.registrar("DESACTIVAR_REGLA", "Regla", r.codigo, null, resumen(r, v), motivo);
        return v;
    }

    // ------------------------------------------------------------------ internos

    private ReglaVersion nuevaVersion(Regla r, int numero, TablaDecision definicion, List<TablaDecision.CasoPrueba> casos) {
        ReglaVersion v = new ReglaVersion();
        v.id = UUID.randomUUID();
        v.reglaId = r.id;
        v.numero = numero;
        v.estado = EstadoRegla.BORRADOR;
        v.definicion = Json.arbol(definicion);
        v.casosPrueba = Json.arbol(Objects.requireNonNullElse(casos, List.of()));
        v.creadoPor = Contexto.usuario();
        return versiones.save(v);
    }

    private void validarDefinicion(TipoRegla tipo, TablaDecision definicion) {
        List<String> errores = evaluador.validar(tipo, definicion);
        if (!errores.isEmpty()) {
            throw Errores.invalido("REGLA_INVALIDA", "La tabla de decisión tiene errores", errores);
        }
    }

    private static List<TablaDecision.CasoPrueba> casos(ReglaVersion v) {
        JsonNode nodo = v.casosPrueba;
        if (nodo == null || !nodo.isArray()) {
            return List.of();
        }
        return Arrays.asList(Json.leer(nodo, TablaDecision.CasoPrueba[].class));
    }

    private static void exigirEstado(ReglaVersion v, EstadoRegla... permitidos) {
        if (!Arrays.asList(permitidos).contains(v.estado)) {
            throw Errores.conflicto("ESTADO_REGLA", "La versión " + v.numero + " está en " + v.estado
                    + "; la operación exige " + Arrays.toString(permitidos));
        }
    }

    private static void exigirOtroUsuario(ReglaVersion v) {
        if (Contexto.usuario().equals(v.creadoPor)) {
            throw Errores.prohibido("MAKER_CHECKER",
                    "Quien creó o editó la versión (" + v.creadoPor + ") no puede aprobarla ni rechazarla");
        }
    }

    private static Map<String, Object> resumen(Regla r, ReglaVersion v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("regla", r.codigo);
        m.put("tipo", r.tipo.name());
        m.put("version", v.numero);
        m.put("estado", v.estado.name());
        m.put("hash", v.hash);
        m.put("definicion", v.definicion);
        return m;
    }
}
