package co.bancopopular.garantias360.garantia;

import co.bancopopular.garantias360.auditoria.AuditoriaService;
import co.bancopopular.garantias360.cobertura.CoberturaService;
import co.bancopopular.garantias360.comun.Contexto;
import co.bancopopular.garantias360.comun.Errores;
import co.bancopopular.garantias360.comun.Json;
import co.bancopopular.garantias360.comun.TareasPosteriores;
import co.bancopopular.garantias360.configuracion.CampoDefinicion;
import co.bancopopular.garantias360.configuracion.TipoGarantiaService;
import co.bancopopular.garantias360.eventos.OutboxService;
import co.bancopopular.garantias360.garantia.GarantiaDtos.*;
import co.bancopopular.garantias360.obligacion.Obligacion;
import co.bancopopular.garantias360.obligacion.ObligacionRepositorio;
import co.bancopopular.garantias360.reglas.ResolutorReglas;
import co.bancopopular.garantias360.reglas.motor.TipoRegla;
import co.bancopopular.garantias360.seguridad.Roles;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * Registro maestro y ciclo de vida de la garantía (M03, M04, M05 básico, M06 básico, M07).
 * Toda modificación deja auditoría y evento en la misma transacción; los cambios que afectan
 * el respaldo disparan el recálculo de cobertura de su grupo después del commit.
 */
@Service
public class GarantiaService {

    private static final BigDecimal UMBRAL_CAMBIO_VALOR = new BigDecimal("0.10");
    private static final ZoneId BOGOTA = ZoneId.of("America/Bogota");

    private final Repositorios.Garantias garantias;
    private final Repositorios.Participantes participantes;
    private final Repositorios.Vinculos vinculos;
    private final Repositorios.Valoraciones valoraciones;
    private final ObligacionRepositorio obligaciones;
    private final TipoGarantiaService tipos;
    private final IdoneidadService idoneidad;
    private final ResolutorReglas resolutor;
    private final CoberturaService cobertura;
    private final AuditoriaService auditoria;
    private final OutboxService outbox;
    private final JdbcTemplate jdbc;
    private final TareasPosteriores posteriores;

    public GarantiaService(Repositorios.Garantias garantias, Repositorios.Participantes participantes,
                           Repositorios.Vinculos vinculos, Repositorios.Valoraciones valoraciones,
                           ObligacionRepositorio obligaciones, TipoGarantiaService tipos, IdoneidadService idoneidad,
                           ResolutorReglas resolutor, CoberturaService cobertura, AuditoriaService auditoria,
                           OutboxService outbox, JdbcTemplate jdbc, TareasPosteriores posteriores) {
        this.garantias = garantias;
        this.participantes = participantes;
        this.vinculos = vinculos;
        this.valoraciones = valoraciones;
        this.obligaciones = obligaciones;
        this.tipos = tipos;
        this.idoneidad = idoneidad;
        this.resolutor = resolutor;
        this.cobertura = cobertura;
        this.auditoria = auditoria;
        this.outbox = outbox;
        this.jdbc = jdbc;
        this.posteriores = posteriores;
    }

    // ------------------------------------------------------------------ registro (RF-1701)

    @Transactional
    public Garantia registrar(RegistroGarantia r, String idempotencyKey, String fuente) {
        String hashPeticion = Json.hash(r);
        if (idempotencyKey != null) {
            Optional<Garantia> previa = porIdempotencia(idempotencyKey, hashPeticion);
            if (previa.isPresent()) {
                return previa.get();
            }
        }
        var tipo = tipos.vigente(r.tipo());
        List<String> errores = tipos.validarAtributos(tipo.campos(), r.atributos(), Macroestado.REGISTRO);
        if (!errores.isEmpty()) {
            throw Errores.invalido("ATRIBUTOS_INVALIDOS", "Los campos del tipo " + r.tipo() + " tienen errores", errores);
        }
        garantias.findByAplicativoOrigenAndReferenciaExterna(r.origen().aplicativo(), r.origen().referencia())
                .ifPresent(g -> {
                    throw Errores.conflicto("REFERENCIA_DUPLICADA", "La referencia " + r.origen().referencia()
                            + " de " + r.origen().aplicativo() + " ya está registrada como " + g.codigo);
                });
        verificarDuplicado(r.tipo(), tipo.campos(), r.atributos());
        if (!tipo.tipo().admiteMultiples && r.obligaciones() != null && r.obligaciones().size() > 1) {
            throw Errores.invalido("VINCULOS_NO_PERMITIDOS", "El tipo " + r.tipo() + " solo respalda una obligación", List.of());
        }

        Garantia g = new Garantia();
        g.id = UUID.randomUUID();
        g.codigo = siguienteCodigo();
        g.tipoVersionId = tipo.version().id;
        g.tipoCodigo = tipo.tipo().codigo;
        g.clienteDocumento = r.cliente().numeroDocumento();
        g.clienteNombre = r.cliente().nombre();
        g.producto = r.producto();
        g.segmento = r.segmento();
        g.moneda = Objects.requireNonNullElse(r.moneda(), "COP");
        g.gravamenesPrevios = Objects.requireNonNullElse(r.gravamenesPrevios(), BigDecimal.ZERO);
        g.macroestado = Macroestado.REGISTRO;
        g.fuente = fuente;
        g.aplicativoOrigen = r.origen().aplicativo();
        g.referenciaExterna = r.origen().referencia();
        g.atributos = r.atributos() == null ? Json.CANONICO.createObjectNode() : r.atributos();
        garantias.save(g);

        Objects.requireNonNullElse(r.participantes(), List.<ParticipanteSolicitud>of()).forEach(p -> {
            Participante x = new Participante();
            x.id = UUID.randomUUID();
            x.garantiaId = g.id;
            x.rol = p.rol();
            x.tipoDocumento = p.tipoDocumento();
            x.numeroDocumento = p.numeroDocumento();
            x.nombre = p.nombre();
            x.porcentaje = p.porcentaje();
            participantes.save(x);
        });
        Objects.requireNonNullElse(r.obligaciones(), List.<VinculoSolicitud>of())
                .forEach(v -> crearVinculo(g, v, r.cliente()));
        if (r.valoracionInicial() != null) {
            guardarValoracion(g, r.valoracionInicial());
        }
        idoneidad.aplicar(g, resolutor.instantanea());
        garantias.save(g);

        if (idempotencyKey != null) {
            jdbc.update("insert into idempotencia (clave, operacion, hash_peticion, recurso_id) values (?, 'REGISTRAR_GARANTIA', ?, ?)",
                    idempotencyKey, hashPeticion, g.id.toString());
        }
        auditoria.registrar("CREAR_GARANTIA", "Garantia", g.codigo, null, instantanea(g), null);
        outbox.publicar("GarantiaCreada", "Garantia", g.codigo, eventoGarantia(g));
        return g;
    }

    private Optional<Garantia> porIdempotencia(String clave, String hashPeticion) {
        List<Map<String, Object>> filas = jdbc.queryForList("select hash_peticion, recurso_id from idempotencia where clave = ?", clave);
        if (filas.isEmpty()) {
            return Optional.empty();
        }
        if (!hashPeticion.equals(filas.getFirst().get("hash_peticion"))) {
            throw Errores.conflicto("IDEMPOTENCIA_CONFLICTO",
                    "La Idempotency-Key ya se usó con una petición distinta");
        }
        return garantias.findById(UUID.fromString((String) filas.getFirst().get("recurso_id")));
    }

    private void verificarDuplicado(String tipo, List<CampoDefinicion> campos, JsonNode atributos) {
        if (atributos == null) {
            return;
        }
        for (CampoDefinicion c : campos) {
            if (c.llave() && atributos.hasNonNull(c.codigo())) {
                String valor = atributos.get(c.codigo()).asText();
                garantias.porLlaveNatural(tipo, c.codigo(), valor).ifPresent(g -> {
                    throw Errores.conflicto("GARANTIA_DUPLICADA", "Ya existe la garantía " + g.codigo + " con "
                            + c.etiqueta() + " = " + valor + ". Vincúlala a la obligación en lugar de registrarla de nuevo.");
                });
            }
        }
    }

    private String siguienteCodigo() {
        int anio = LocalDate.now(BOGOTA).getYear();
        Integer n = jdbc.queryForObject("""
                insert into consecutivo_garantia (anio, ultimo) values (?, 1)
                on conflict (anio) do update set ultimo = consecutivo_garantia.ultimo + 1
                returning ultimo""", Integer.class, anio);
        return String.format("GAR-%d-%06d", anio, n);
    }

    // ------------------------------------------------------------------ ciclo de vida (M03)

    @Transactional
    public Garantia transicion(String codigo, Transicion t) {
        Garantia g = obtener(codigo);
        Macroestado origen = g.macroestado;
        if (!origen.puedePasarA(t.destino())) {
            throw Errores.conflicto("TRANSICION_INVALIDA", "No se puede pasar de " + origen + " a " + t.destino()
                    + ". Permitidas: " + origen.siguientes());
        }
        List<Obligacion> obls = idoneidad.obligacionesDe(g);
        switch (t.destino()) {
            case CONSTITUCION -> {
                if (!Set.of("APROBADA", "CONDICIONADA").contains(g.estadoJuridico)) {
                    throw Errores.conflicto("SIN_CONCEPTO_JURIDICO", "La constitución exige estudio jurídico aprobado o condicionado (actual: " + g.estadoJuridico + ")");
                }
            }
            case ACTIVA -> {
                if (!g.perfeccionada) {
                    throw Errores.conflicto("NO_PERFECCIONADA", "La garantía no está perfeccionada");
                }
                if (obls.stream().noneMatch(o -> "VIGENTE".equals(o.estado))) {
                    throw Errores.conflicto("SIN_DESEMBOLSO", "Ninguna obligación vinculada está desembolsada en Flexcube");
                }
            }
            case LIBERACION -> validarLiberacion(g, obls, t);
            case EJECUCION -> exigirRol(Roles.JURIDICA_DIRECTOR, "El inicio de la ejecución requiere un Director de Jurídica (RF-1104)");
            case ANULADA, CIERRE -> exigirMotivo(t.motivo());
            default -> {
            }
        }
        var tipo = tipos.porVersion(g.tipoVersionId);
        List<String> faltantes = tipos.validarAtributos(tipo.campos(), g.atributos, t.destino());
        if (!faltantes.isEmpty()) {
            throw Errores.invalido("CAMPOS_REQUERIDOS", "Faltan datos para pasar a " + t.destino(), faltantes);
        }
        Map<String, Object> antes = instantanea(g);
        g.macroestado = t.destino();
        g.updatedAt = Instant.now();
        auditoria.registrar("CAMBIAR_ESTADO", "Garantia", g.codigo, antes, instantanea(g), t.motivo());
        outbox.publicar("GarantiaEstadoCambiado", "Garantia", g.codigo,
                Map.of("garantia", g.codigo, "desde", origen.name(), "hacia", t.destino().name(),
                        "motivo", Objects.requireNonNullElse(t.motivo(), "")));
        if (origen == Macroestado.LIBERACION && t.destino() == Macroestado.CIERRE) {
            outbox.publicar("GarantiaLiberada", "Garantia", g.codigo, eventoGarantia(g));
        }
        if (t.destino() == Macroestado.EJECUCION) {
            outbox.publicar("GarantiaEnEjecucion", "Garantia", g.codigo, eventoGarantia(g));
        }
        recalcularDespues(g.id);
        return g;
    }

    /** RF-1202: no se libera con obligaciones activas sin autorización explícita de un Director. */
    private void validarLiberacion(Garantia g, List<Obligacion> obls, Transicion t) {
        List<String> activas = obls.stream().filter(Obligacion::activa).map(o -> o.numero + " (" + o.estado + ")").toList();
        if (activas.isEmpty()) {
            return;
        }
        if (!t.autorizacionExcepcional()) {
            throw Errores.invalido("OBLIGACIONES_ACTIVAS",
                    "No se puede liberar: la garantía respalda obligaciones activas en Flexcube", activas);
        }
        exigirRol(Roles.OPERACIONES_DIRECTOR, "La liberación con obligaciones activas requiere autorización de un Director");
        exigirMotivo(t.motivo());
    }

    @Transactional
    public Garantia estudioJuridico(String codigo, EstudioJuridico e) {
        Garantia g = obtener(codigo);
        if (Set.of("APROBADA", "CONDICIONADA", "RECHAZADA").contains(e.estado())) {
            exigirRol(Roles.JURIDICA_DIRECTOR, "El concepto jurídico final lo aprueba un Director de Jurídica (RF-0505)");
        }
        if ("CONDICIONADA".equals(e.estado()) && e.condicionamientosAbiertos() == 0) {
            throw Errores.invalido("SIN_CONDICIONAMIENTOS", "Un concepto condicionado debe tener condicionamientos abiertos", List.of());
        }
        Map<String, Object> antes = instantanea(g);
        g.estadoJuridico = e.estado();
        g.condicionamientosAbiertos = e.condicionamientosAbiertos();
        if (g.macroestado == Macroestado.REGISTRO) {
            g.macroestado = Macroestado.ESTUDIO_JURIDICO;
        }
        reevaluar(g);
        auditoria.registrar("ESTUDIO_JURIDICO", "Garantia", g.codigo, antes, instantanea(g), e.concepto());
        if (Set.of("APROBADA", "CONDICIONADA", "RECHAZADA").contains(e.estado())) {
            outbox.publicar("EstudioJuridicoConcluido", "Garantia", g.codigo,
                    Map.of("garantia", g.codigo, "resultado", e.estado(), "condicionamientosAbiertos", e.condicionamientosAbiertos()));
        }
        recalcularDespues(g.id);
        return g;
    }

    @Transactional
    public Garantia perfeccionar(String codigo, Perfeccionamiento p) {
        Garantia g = obtener(codigo);
        if (g.macroestado != Macroestado.CONSTITUCION && g.macroestado != Macroestado.PERFECCIONAMIENTO) {
            throw Errores.conflicto("ESTADO_INVALIDO", "El perfeccionamiento se registra en CONSTITUCION o PERFECCIONAMIENTO");
        }
        if (p.fechaPerfeccionamiento().isBefore(p.fechaConstitucion())) {
            throw Errores.invalido("FECHAS_INVALIDAS", "El perfeccionamiento no puede ser anterior a la constitución", List.of());
        }
        Map<String, Object> antes = instantanea(g);
        ObjectNode atributos = g.atributos.deepCopy();
        if (p.datosRegistro() != null && p.datosRegistro().isObject()) {
            atributos.setAll((ObjectNode) p.datosRegistro());
        }
        // Se valida siempre: los datos de registro (escritura, folio RGM…) son obligatorios desde este estado.
        List<String> errores = tipos.validarAtributos(tipos.porVersion(g.tipoVersionId).campos(), atributos,
                Macroestado.PERFECCIONAMIENTO);
        if (!errores.isEmpty()) {
            throw Errores.invalido("CAMPOS_REQUERIDOS", "Faltan datos del registro", errores);
        }
        g.atributos = atributos;
        g.fechaConstitucion = p.fechaConstitucion();
        g.fechaPerfeccionamiento = p.fechaPerfeccionamiento();
        g.perfeccionada = true;
        g.macroestado = Macroestado.PERFECCIONAMIENTO;
        reevaluar(g);
        auditoria.registrar("PERFECCIONAR", "Garantia", g.codigo, antes, instantanea(g), null);
        outbox.publicar("GarantiaPerfeccionada", "Garantia", g.codigo, eventoGarantia(g));
        recalcularDespues(g.id);
        return g;
    }

    // ------------------------------------------------------------------ valoraciones (M07)

    @Transactional
    public Valoracion valorar(String codigo, ValoracionSolicitud v) {
        Garantia g = obtener(codigo);
        if (g.valorComercial != null && g.valorComercial.signum() > 0) {
            BigDecimal variacion = v.valorComercial().subtract(g.valorComercial).abs()
                    .divide(g.valorComercial, 6, RoundingMode.HALF_EVEN);
            if (variacion.compareTo(UMBRAL_CAMBIO_VALOR) > 0 && !Contexto.roles().contains(Roles.OPERACIONES_DIRECTOR)
                    && !Contexto.roles().contains(Roles.SISTEMA)) {
                throw Errores.prohibido("REQUIERE_DIRECTOR", "Un cambio de valor de " + variacion.movePointRight(2).setScale(1, RoundingMode.HALF_EVEN)
                        + " % supera el umbral de 10 %: debe registrarlo un Director de Operaciones (RF-0709)");
            }
            if (variacion.compareTo(UMBRAL_CAMBIO_VALOR) > 0) {
                exigirMotivo(v.motivo());
            }
        }
        Map<String, Object> antes = instantanea(g);
        Valoracion val = guardarValoracion(g, v);
        reevaluar(g);
        auditoria.registrar("VALORAR", "Garantia", g.codigo, antes, instantanea(g), v.motivo());
        outbox.publicar("GarantiaValorada", "Garantia", g.codigo, Map.of("garantia", g.codigo, "tipo", v.tipo(),
                "fecha", v.fecha().toString(), "valorComercial", v.valorComercial(),
                "proximaValoracion", String.valueOf(g.fechaProximaValoracion)));
        recalcularDespues(g.id);
        return val;
    }

    private Valoracion guardarValoracion(Garantia g, ValoracionSolicitud v) {
        Valoracion val = new Valoracion();
        val.id = UUID.randomUUID();
        val.garantiaId = g.id;
        val.tipo = v.tipo();
        val.fecha = v.fecha();
        val.valorComercial = v.valorComercial();
        val.valorTecnico = v.valorTecnico();
        val.moneda = Objects.requireNonNullElse(v.moneda(), g.moneda);
        val.perito = v.perito();
        val.raa = v.raa();
        val.metodologia = v.metodologia();
        val.vigenciaHasta = v.vigenciaHasta();
        val.soporteRef = v.soporteRef();
        val.motivo = v.motivo();
        val.registradoPor = Contexto.usuario();
        valoraciones.save(val);
        g.valorComercial = v.valorComercial();
        g.fechaUltimaValoracion = v.fecha();
        g.fechaProximaValoracion = proximaValoracion(g, v);
        g.updatedAt = Instant.now();
        return val;
    }

    private LocalDate proximaValoracion(Garantia g, ValoracionSolicitud v) {
        var tipo = tipos.porVersion(g.tipoVersionId).tipo();
        Optional<ResolutorReglas.Resolucion> meses = resolutor.instantanea().intentar(TipoRegla.PERIODICIDAD_VALORACION,
                Map.of("tipo", g.tipoCodigo, "clase", tipo.clase, "segmento", g.segmento));
        LocalDate porRegla = meses.map(r -> v.fecha().plusMonths(Integer.parseInt(r.valor()))).orElse(null);
        if (v.vigenciaHasta() != null && (porRegla == null || v.vigenciaHasta().isBefore(porRegla))) {
            return v.vigenciaHasta();
        }
        return porRegla;
    }

    // ------------------------------------------------------------------ vínculos (RF-0403)

    @Transactional
    public Vinculo vincular(String codigo, VinculoSolicitud v) {
        Garantia g = obtener(codigo);
        var tipo = tipos.porVersion(g.tipoVersionId).tipo();
        if (!tipo.admiteMultiples && !vinculos.findByGarantiaIdAndVigenteTrue(g.id).isEmpty()) {
            throw Errores.conflicto("VINCULOS_NO_PERMITIDOS", "El tipo " + tipo.codigo + " solo respalda una obligación");
        }
        Vinculo nuevo = crearVinculo(g, v, new Cliente("CC", g.clienteDocumento, g.clienteNombre));
        reevaluar(g);
        auditoria.registrar("VINCULAR_OBLIGACION", "Garantia", g.codigo, null,
                Map.of("obligacion", v.numeroObligacion(), "tipo", nuevo.tipo, "prioridad", nuevo.prioridad), null);
        outbox.publicar("GarantiaVinculadaObligacion", "Garantia", g.codigo,
                Map.of("garantia", g.codigo, "obligacion", v.numeroObligacion(), "tipo", nuevo.tipo));
        recalcularDespues(g.id);
        return nuevo;
    }

    @Transactional
    public void desvincular(String codigo, String numeroObligacion, String motivo) {
        exigirMotivo(motivo);
        Garantia g = obtener(codigo);
        Obligacion o = obligaciones.findByNumero(numeroObligacion)
                .orElseThrow(() -> Errores.noEncontrado("La obligación " + numeroObligacion));
        Vinculo v = vinculos.findByGarantiaIdAndObligacionId(g.id, o.id).filter(x -> x.vigente)
                .orElseThrow(() -> Errores.noEncontrado("El vínculo con " + numeroObligacion));
        if (o.activa() && !Contexto.roles().contains(Roles.OPERACIONES_DIRECTOR)) {
            throw Errores.prohibido("REQUIERE_DIRECTOR", "Desvincular una obligación activa requiere un Director de Operaciones");
        }
        v.vigente = false;
        auditoria.registrar("DESVINCULAR_OBLIGACION", "Garantia", g.codigo, Map.of("obligacion", numeroObligacion), null, motivo);
        outbox.publicar("GarantiaDesvinculadaObligacion", "Garantia", g.codigo,
                Map.of("garantia", g.codigo, "obligacion", numeroObligacion, "motivo", motivo));
        UUID garantiaId = g.id;
        UUID obligacionId = o.id;
        despuesDelCommit(() -> {
            cobertura.recalcularPorGarantias(List.of(garantiaId), "DESVINCULACION");
            cobertura.recalcularPorObligaciones(List.of(obligacionId), "DESVINCULACION");
        });
    }

    private Vinculo crearVinculo(Garantia g, VinculoSolicitud v, Cliente cliente) {
        Obligacion o = obligaciones.findByNumero(v.numeroObligacion()).orElseGet(() -> {
            Obligacion nueva = new Obligacion();
            nueva.id = UUID.randomUUID();
            nueva.numero = v.numeroObligacion();
            nueva.clienteDocumento = cliente.numeroDocumento();
            nueva.clienteNombre = cliente.nombre();
            nueva.producto = Objects.requireNonNullElse(v.producto(), g.producto);
            nueva.segmento = Objects.requireNonNullElse(v.segmento(), g.segmento);
            nueva.destino = v.destino();
            nueva.estado = "APROBADA";
            return obligaciones.save(nueva);
        });
        Vinculo x = vinculos.findByGarantiaIdAndObligacionId(g.id, o.id).orElseGet(Vinculo::new);
        if (x.id != null && x.vigente) {
            throw Errores.conflicto("VINCULO_EXISTE", "La garantía ya respalda la obligación " + o.numero);
        }
        if (x.id == null) {
            x.id = UUID.randomUUID();
        }
        x.garantiaId = g.id;
        x.obligacionId = o.id;
        x.tipo = Objects.requireNonNullElse(v.tipo(), "CERRADA");
        x.tope = v.tope();
        x.valorPactado = v.valorPactado();
        x.prioridad = Objects.requireNonNullElse(v.prioridad(), 100);
        x.vigente = true;
        return vinculos.save(x);
    }

    // ------------------------------------------------------------------ utilidades

    @Transactional(readOnly = true)
    public Garantia obtener(String codigoOId) {
        Optional<Garantia> g = garantias.findByCodigo(codigoOId);
        if (g.isEmpty()) {
            try {
                g = garantias.findById(UUID.fromString(codigoOId));
            } catch (IllegalArgumentException ignorado) {
                // no es un UUID: se informa como no encontrada
            }
        }
        return g.orElseThrow(() -> Errores.noEncontrado("La garantía " + codigoOId));
    }

    private void reevaluar(Garantia g) {
        idoneidad.aplicar(g, resolutor.instantanea());
        g.updatedAt = Instant.now();
    }

    private void recalcularDespues(UUID garantiaId) {
        despuesDelCommit(() -> cobertura.recalcularPorGarantias(List.of(garantiaId), "CAMBIO_GARANTIA"));
    }

    private void despuesDelCommit(Runnable tarea) {
        posteriores.despuesDelCommit(tarea);
    }

    private static void exigirRol(String rol, String mensaje) {
        if (!Contexto.roles().contains(rol)) {
            throw Errores.prohibido("ROL_REQUERIDO", mensaje);
        }
    }

    private static void exigirMotivo(String motivo) {
        if (motivo == null || motivo.isBlank()) {
            throw Errores.invalido("MOTIVO_REQUERIDO", "Esta operación exige un motivo", List.of());
        }
    }

    static Map<String, Object> instantanea(Garantia g) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("codigo", g.codigo);
        m.put("tipo", g.tipoCodigo);
        m.put("macroestado", g.macroestado.name());
        m.put("estadoJuridico", g.estadoJuridico);
        m.put("condicionamientosAbiertos", g.condicionamientosAbiertos);
        m.put("perfeccionada", g.perfeccionada);
        m.put("idoneidad", g.idoneidad);
        m.put("idoneidadRegla", g.idoneidadRegla);
        m.put("valorComercial", g.valorComercial);
        m.put("fechaUltimaValoracion", g.fechaUltimaValoracion == null ? null : g.fechaUltimaValoracion.toString());
        m.put("fechaProximaValoracion", g.fechaProximaValoracion == null ? null : g.fechaProximaValoracion.toString());
        m.put("gravamenesPrevios", g.gravamenesPrevios);
        m.put("atributos", g.atributos);
        return m;
    }

    private static Map<String, Object> eventoGarantia(Garantia g) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("garantia", g.codigo);
        m.put("tipo", g.tipoCodigo);
        m.put("cliente", g.clienteDocumento);
        m.put("producto", g.producto);
        m.put("macroestado", g.macroestado.name());
        m.put("idoneidad", g.idoneidad);
        m.put("valorComercial", g.valorComercial);
        m.put("origen", Map.of("aplicativo", Objects.requireNonNullElse(g.aplicativoOrigen, ""),
                "referencia", Objects.requireNonNullElse(g.referenciaExterna, "")));
        return m;
    }
}
