package co.bancopopular.garantias360.integracion;

import co.bancopopular.garantias360.auditoria.AuditoriaService;
import co.bancopopular.garantias360.cobertura.CoberturaService;
import co.bancopopular.garantias360.comun.Contexto;
import co.bancopopular.garantias360.comun.Errores;
import co.bancopopular.garantias360.comun.Json;
import co.bancopopular.garantias360.comun.TareasPosteriores;
import co.bancopopular.garantias360.eventos.OutboxService;
import co.bancopopular.garantias360.garantia.Garantia;
import co.bancopopular.garantias360.garantia.Macroestado;
import co.bancopopular.garantias360.garantia.Repositorios;
import co.bancopopular.garantias360.obligacion.Obligacion;
import co.bancopopular.garantias360.obligacion.ObligacionRepositorio;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * Consumo de eventos de Flexcube (RF-2103) con inbox idempotente y linaje (RF-0902). El mismo
 * servicio atiende el consumidor Kafka y el endpoint REST de integración.
 */
@Service
public class FlexcubeService {

    public static final Set<String> TIPOS = Set.of("ObligacionDesembolsada", "SaldoObligacionActualizado",
            "MoraActualizada", "ObligacionCancelada", "ObligacionCastigada");

    private final JdbcTemplate jdbc;
    private final ObligacionRepositorio obligaciones;
    private final Repositorios.Vinculos vinculos;
    private final Repositorios.Garantias garantias;
    private final AuditoriaService auditoria;
    private final OutboxService outbox;
    private final CoberturaService cobertura;
    private final TareasPosteriores posteriores;

    public FlexcubeService(JdbcTemplate jdbc, ObligacionRepositorio obligaciones, Repositorios.Vinculos vinculos,
                           Repositorios.Garantias garantias, AuditoriaService auditoria, OutboxService outbox,
                           CoberturaService cobertura, TareasPosteriores posteriores) {
        this.jdbc = jdbc;
        this.obligaciones = obligaciones;
        this.vinculos = vinculos;
        this.garantias = garantias;
        this.auditoria = auditoria;
        this.outbox = outbox;
        this.cobertura = cobertura;
        this.posteriores = posteriores;
    }

    /** Evento de obligación. Los montos son saldos a la fecha del evento. */
    public record EventoObligacion(@NotBlank String eventoId, @NotBlank String tipo, @NotBlank String numeroObligacion,
                                   String clienteDocumento, String clienteNombre, String producto, String segmento,
                                   String destino, BigDecimal saldoCapital, BigDecimal saldoIntereses,
                                   BigDecimal saldoOtros, Integer diasMora, LocalDate fechaDesembolso,
                                   @NotNull Instant ocurridoEn) {
    }

    public record Resultado(String eventoId, String estado, List<String> efectos) {
    }

    @Transactional
    public Resultado procesar(EventoObligacion e) {
        if (!TIPOS.contains(e.tipo())) {
            throw Errores.invalido("TIPO_EVENTO", "Tipo de evento no soportado: " + e.tipo(), List.copyOf(TIPOS));
        }
        UUID inboxId = UUID.randomUUID();
        int insertado = jdbc.update("""
                        insert into evento_inbox (id, evento_id, tipo, origen, correlation_id, payload, estado)
                        values (?, ?, ?, 'FLEXCUBE', ?, ?::jsonb, 'RECIBIDO') on conflict (evento_id) do nothing""",
                inboxId, e.eventoId(), e.tipo(), Contexto.correlationId(), Json.canonico(e));
        if (insertado == 0) {
            return new Resultado(e.eventoId(), "DUPLICADO", List.of("El evento ya fue procesado; se ignora (idempotencia)"));
        }
        List<String> efectos = new ArrayList<>();
        Obligacion o = obligaciones.findByNumero(e.numeroObligacion()).orElseGet(() -> nueva(e));
        Map<String, Object> antes = foto(o);
        aplicar(o, e);
        obligaciones.save(o);
        linaje(inboxId, "Obligacion", o.numero, e.tipo());
        auditoria.registrar(e.tipo().toUpperCase(), "Obligacion", o.numero, antes, foto(o), "Evento " + e.eventoId(), "FLEXCUBE");
        efectos.add("Obligación " + o.numero + " actualizada (" + o.estado + ")");

        List<UUID> garantiasAfectadas = vinculos.findByObligacionIdAndVigenteTrue(o.id).stream().map(v -> v.garantiaId).toList();
        if ("ObligacionDesembolsada".equals(e.tipo())) {
            for (Garantia g : garantias.findByIdIn(garantiasAfectadas)) {
                if (g.macroestado == Macroestado.PERFECCIONAMIENTO && g.perfeccionada) {
                    g.macroestado = Macroestado.ACTIVA;
                    linaje(inboxId, "Garantia", g.codigo, "ACTIVAR");
                    auditoria.registrar("CAMBIAR_ESTADO", "Garantia", g.codigo, Map.of("macroestado", "PERFECCIONAMIENTO"),
                            Map.of("macroestado", "ACTIVA"), "Desembolso de " + o.numero, "FLEXCUBE");
                    outbox.publicar("GarantiaEstadoCambiado", "Garantia", g.codigo,
                            Map.of("garantia", g.codigo, "desde", "PERFECCIONAMIENTO", "hacia", "ACTIVA",
                                    "motivo", "Desembolso de " + o.numero), e.eventoId());
                    efectos.add("Garantía " + g.codigo + " activada");
                }
            }
        }
        if ("ObligacionCancelada".equals(e.tipo())) {
            for (Garantia g : garantias.findByIdIn(garantiasAfectadas)) {
                boolean todasCanceladas = vinculos.findByGarantiaIdAndVigenteTrue(g.id).stream()
                        .map(v -> obligaciones.findById(v.obligacionId).orElseThrow())
                        .noneMatch(Obligacion::activa);
                if (todasCanceladas && g.macroestado.puedePasarA(Macroestado.LIBERACION)) {
                    Macroestado desde = g.macroestado;
                    g.macroestado = Macroestado.LIBERACION;
                    linaje(inboxId, "Garantia", g.codigo, "INICIAR_LIBERACION");
                    auditoria.registrar("CAMBIAR_ESTADO", "Garantia", g.codigo, Map.of("macroestado", desde.name()),
                            Map.of("macroestado", "LIBERACION"), "Cancelación total de obligaciones (RF-1201)", "FLEXCUBE");
                    outbox.publicar("GarantiaEstadoCambiado", "Garantia", g.codigo,
                            Map.of("garantia", g.codigo, "desde", desde.name(), "hacia", "LIBERACION",
                                    "motivo", "Cancelación total de obligaciones"), e.eventoId());
                    efectos.add("Garantía " + g.codigo + " pasa a liberación (SLA de liberación inicia)");
                }
            }
        }
        jdbc.update("update evento_inbox set estado = 'PROCESADO', procesado_en = ? where id = ?",
                Timestamp.from(Instant.now()), inboxId);
        UUID obligacionId = o.id;
        posteriores.despuesDelCommit(() -> cobertura.recalcularPorObligaciones(List.of(obligacionId), "FLEXCUBE " + e.tipo()));
        efectos.add("Recálculo de cobertura del grupo de " + o.numero);
        return new Resultado(e.eventoId(), "PROCESADO", efectos);
    }

    private static Obligacion nueva(EventoObligacion e) {
        Obligacion o = new Obligacion();
        o.id = UUID.randomUUID();
        o.numero = e.numeroObligacion();
        o.clienteDocumento = Objects.requireNonNullElse(e.clienteDocumento(), "DESCONOCIDO");
        o.clienteNombre = Objects.requireNonNullElse(e.clienteNombre(), "Cliente sin nombre");
        o.producto = Objects.requireNonNullElse(e.producto(), "SIN_PRODUCTO");
        o.segmento = Objects.requireNonNullElse(e.segmento(), "PERSONAS");
        o.estado = "APROBADA";
        return o;
    }

    private static void aplicar(Obligacion o, EventoObligacion e) {
        if (e.saldoCapital() != null) {
            o.saldoCapital = e.saldoCapital();
        }
        if (e.saldoIntereses() != null) {
            o.saldoIntereses = e.saldoIntereses();
        }
        if (e.saldoOtros() != null) {
            o.saldoOtros = e.saldoOtros();
        }
        if (e.diasMora() != null) {
            o.diasMora = e.diasMora();
        }
        if (e.destino() != null) {
            o.destino = e.destino();
        }
        switch (e.tipo()) {
            case "ObligacionDesembolsada" -> {
                o.estado = "VIGENTE";
                o.fechaDesembolso = Objects.requireNonNullElse(e.fechaDesembolso(), LocalDate.now());
            }
            case "ObligacionCancelada" -> {
                o.estado = "CANCELADA";
                o.saldoCapital = BigDecimal.ZERO;
                o.saldoIntereses = BigDecimal.ZERO;
                o.saldoOtros = BigDecimal.ZERO;
            }
            case "ObligacionCastigada" -> o.estado = "CASTIGADA";
            default -> {
            }
        }
        o.actualizadoEn = e.ocurridoEn();
    }

    private void linaje(UUID inboxId, String entidad, String id, String accion) {
        jdbc.update("insert into linaje_evento (inbox_id, entidad, entidad_id, accion) values (?, ?, ?, ?)",
                inboxId, entidad, id, accion);
    }

    private static Map<String, Object> foto(Obligacion o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("estado", o.estado);
        m.put("saldoCapital", o.saldoCapital);
        m.put("saldoIntereses", o.saldoIntereses);
        m.put("saldoOtros", o.saldoOtros);
        m.put("diasMora", o.diasMora);
        return m;
    }
}
