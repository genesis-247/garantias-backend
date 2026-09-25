package co.bancopopular.garantias360.api;

import co.bancopopular.garantias360.auditoria.AuditoriaService;
import co.bancopopular.garantias360.comun.Contexto;
import co.bancopopular.garantias360.comun.Errores;
import co.bancopopular.garantias360.configuracion.TipoGarantiaService;
import co.bancopopular.garantias360.eventos.EventosConsulta;
import co.bancopopular.garantias360.eventos.RelayOutbox;
import co.bancopopular.garantias360.integracion.FlexcubeService;
import co.bancopopular.garantias360.seguridad.Roles;
import co.bancopopular.garantias360.tablero.AlertaService;
import co.bancopopular.garantias360.tablero.TableroService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Consultas y gobierno", description = "Centro de mando, monitoreo, auditoría, core transaccional e integraciones")
public class ConsultaController {

    private final TableroService tablero;
    private final AlertaService alertas;
    private final AuditoriaService auditoria;
    private final EventosConsulta eventos;
    private final RelayOutbox relay;
    private final FlexcubeService flexcube;
    private final TipoGarantiaService tipos;

    public ConsultaController(TableroService tablero, AlertaService alertas, AuditoriaService auditoria,
                              EventosConsulta eventos, RelayOutbox relay, FlexcubeService flexcube,
                              TipoGarantiaService tipos) {
        this.tablero = tablero;
        this.alertas = alertas;
        this.auditoria = auditoria;
        this.eventos = eventos;
        this.relay = relay;
        this.flexcube = flexcube;
        this.tipos = tipos;
    }

    @GetMapping("/sesion")
    @PreAuthorize(Roles.LECTURA)
    @Operation(summary = "Usuario y roles de la sesión")
    public Map<String, Object> sesion() {
        return Map.of("usuario", Contexto.usuario(), "roles", Contexto.roles());
    }

    @GetMapping("/tablero")
    @PreAuthorize(Roles.LECTURA)
    @Operation(summary = "Centro de mando de riesgo crediticio (M01)")
    public Map<String, Object> tablero() {
        return tablero.tablero();
    }

    @GetMapping("/alertas")
    @PreAuthorize(Roles.LECTURA)
    @Operation(summary = "Alertas del monitoreo (M10)")
    public List<AlertaService.Alerta> alertas(@RequestParam(required = false) String garantia) {
        return alertas.alertas(garantia);
    }

    @GetMapping("/tipos-garantia")
    @PreAuthorize(Roles.LECTURA)
    @Operation(summary = "Tipos de garantía con su versión publicada y campos")
    public List<TipoGarantiaService.TipoConVersion> tipos() {
        return tipos.listar();
    }

    @GetMapping("/tipos-garantia/{codigo}")
    @PreAuthorize(Roles.LECTURA)
    public TipoGarantiaService.TipoConVersion tipo(@PathVariable String codigo) {
        return tipos.vigente(codigo);
    }

    @PostMapping("/tipos-garantia")
    @PreAuthorize("hasRole('ADMIN_FUNCIONAL')")
    public TipoGarantiaService.TipoConVersion crearTipo(@RequestBody TipoGarantiaService.NuevoTipo nuevo) {
        return tipos.crear(nuevo);
    }

    @GetMapping("/auditoria")
    @PreAuthorize(Roles.LECTURA)
    @Operation(summary = "Consultar la auditoría inmutable")
    public List<AuditoriaService.Registro> auditoria(@RequestParam(required = false) String entidad,
                                                     @RequestParam(required = false) String entidadId,
                                                     @RequestParam(required = false) String correlationId,
                                                     @RequestParam(required = false) String usuario,
                                                     @RequestParam(defaultValue = "200") int limite) {
        return auditoria.consultar(entidad, entidadId, correlationId, usuario, limite);
    }

    @GetMapping("/auditoria/verificacion")
    @PreAuthorize(Roles.LECTURA)
    @Operation(summary = "Verificar la integridad de la cadena de auditoría (SHA-256)")
    public AuditoriaService.Verificacion verificar() {
        return auditoria.verificar();
    }

    @GetMapping("/eventos")
    @PreAuthorize(Roles.LECTURA)
    @Operation(summary = "Core transaccional: eventos recibidos y publicados (M09)")
    public List<Map<String, Object>> eventos(@RequestParam(required = false) String direccion,
                                             @RequestParam(required = false) String tipo,
                                             @RequestParam(required = false) String estado,
                                             @RequestParam(required = false) String texto,
                                             @RequestParam(defaultValue = "200") int limite) {
        return eventos.eventos(direccion, tipo, estado, texto, limite);
    }

    @GetMapping("/eventos/resumen")
    @PreAuthorize(Roles.LECTURA)
    public Map<String, Object> resumenEventos() {
        return eventos.resumen();
    }

    @GetMapping("/eventos/{eventoId}/linaje")
    @PreAuthorize(Roles.LECTURA)
    public List<Map<String, Object>> linaje(@PathVariable String eventoId) {
        return eventos.linaje(eventoId);
    }

    @PostMapping("/eventos/{id}/reproceso")
    @PreAuthorize("hasAnyRole('ADMIN_FUNCIONAL','SISTEMA')")
    @Operation(summary = "Reprocesar un evento publicado en error (DLQ)")
    public Map<String, Object> reprocesar(@PathVariable String id) {
        if (relay.reprocesar(id) == 0) {
            throw Errores.conflicto("NO_REPROCESABLE", "El evento no existe o no está en ERROR");
        }
        return Map.of("evento", id, "estado", "PENDIENTE");
    }

    @GetMapping("/correlaciones/{correlationId}")
    @PreAuthorize(Roles.LECTURA)
    @Operation(summary = "Línea de tiempo de extremo a extremo por Correlation ID")
    public List<Map<String, Object>> correlacion(@PathVariable String correlationId) {
        return eventos.lineaTiempo(correlationId);
    }

    @PostMapping("/integraciones/flexcube/eventos")
    @PreAuthorize("hasAnyRole('SISTEMA','ADMIN_FUNCIONAL')")
    @Operation(summary = "Recibir un evento de obligación de Flexcube (idempotente por eventoId)")
    public FlexcubeService.Resultado flexcube(@Valid @RequestBody FlexcubeService.EventoObligacion evento) {
        return flexcube.procesar(evento);
    }

    @GetMapping("/integraciones/flexcube/tipos")
    @PreAuthorize(Roles.LECTURA)
    public Set<String> tiposFlexcube() {
        return FlexcubeService.TIPOS;
    }
}
