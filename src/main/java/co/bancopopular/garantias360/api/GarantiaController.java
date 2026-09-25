package co.bancopopular.garantias360.api;

import co.bancopopular.garantias360.garantia.*;
import co.bancopopular.garantias360.garantia.GarantiaDtos.*;
import co.bancopopular.garantias360.seguridad.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/garantias")
@Tag(name = "Garantías", description = "Registro maestro, ciclo de vida y Expediente 360")
public class GarantiaController {

    private final GarantiaService servicio;
    private final ExpedienteService expedientes;

    public GarantiaController(GarantiaService servicio, ExpedienteService expedientes) {
        this.servicio = servicio;
        this.expedientes = expedientes;
    }

    @PostMapping
    @PreAuthorize(Roles.REGISTRA_GARANTIAS)
    @Operation(summary = "Registrar garantía (aplicativos de producto)",
            description = "Idempotente con la cabecera Idempotency-Key. Valida los campos contra el esquema del tipo.")
    public ResponseEntity<Map<String, Object>> registrar(@Valid @RequestBody RegistroGarantia solicitud,
                                                         @RequestHeader(value = "Idempotency-Key", required = false) String clave) {
        Garantia g = servicio.registrar(solicitud, clave, "API_PRODUCTO");
        return ResponseEntity.created(URI.create("/api/v1/garantias/" + g.codigo)).body(resumen(g));
    }

    @GetMapping
    @PreAuthorize(Roles.LECTURA)
    @Operation(summary = "Buscar garantías con filtros")
    public Map<String, Object> buscar(@RequestParam(required = false) String texto, @RequestParam(required = false) String tipo,
                                      @RequestParam(required = false) Macroestado macroestado,
                                      @RequestParam(required = false) String idoneidad,
                                      @RequestParam(required = false) String segmento,
                                      @RequestParam(required = false) String producto,
                                      @RequestParam(required = false) String cliente,
                                      @RequestParam(defaultValue = "0") int pagina, @RequestParam(defaultValue = "25") int tamano,
                                      @RequestParam(required = false) String orden) {
        Page<Garantia> page = expedientes.buscar(new ExpedienteService.Filtros(texto, tipo, macroestado, idoneidad, segmento,
                producto, cliente), pagina, tamano, orden);
        Map<UUID, Map<String, Object>> cobertura = expedientes.resumenCobertura(page.getContent().stream().map(g -> g.id).toList());
        List<Map<String, Object>> items = page.getContent().stream().map(g -> {
            Map<String, Object> m = resumen(g);
            m.put("cobertura", cobertura.get(g.id));
            return m;
        }).toList();
        return Map.of("items", items, "total", page.getTotalElements(), "pagina", page.getNumber(), "tamano", page.getSize());
    }

    @GetMapping("/{codigo}")
    @PreAuthorize(Roles.LECTURA)
    @Operation(summary = "Expediente 360 de la garantía")
    public Map<String, Object> expediente(@PathVariable String codigo) {
        return expedientes.expediente(servicio.obtener(codigo));
    }

    @PostMapping("/{codigo}/transiciones")
    @PreAuthorize(Roles.OPERA_GARANTIAS)
    @Operation(summary = "Cambiar macroestado (invocado por Appian)")
    public Map<String, Object> transicion(@PathVariable String codigo, @Valid @RequestBody Transicion t) {
        return resumen(servicio.transicion(codigo, t));
    }

    @PutMapping("/{codigo}/estudio-juridico")
    @PreAuthorize("hasAnyRole('JURIDICA_GESTOR','JURIDICA_DIRECTOR','SISTEMA')")
    @Operation(summary = "Registrar resultado del estudio jurídico")
    public Map<String, Object> estudioJuridico(@PathVariable String codigo, @Valid @RequestBody EstudioJuridico e) {
        return resumen(servicio.estudioJuridico(codigo, e));
    }

    @PutMapping("/{codigo}/perfeccionamiento")
    @PreAuthorize(Roles.OPERA_GARANTIAS)
    @Operation(summary = "Registrar constitución y perfeccionamiento")
    public Map<String, Object> perfeccionar(@PathVariable String codigo, @Valid @RequestBody Perfeccionamiento p) {
        return resumen(servicio.perfeccionar(codigo, p));
    }

    @PostMapping("/{codigo}/valoraciones")
    @PreAuthorize(Roles.OPERA_GARANTIAS)
    @Operation(summary = "Registrar una valoración (histórico inmutable)")
    @ResponseStatus(HttpStatus.CREATED)
    public Valoracion valorar(@PathVariable String codigo, @Valid @RequestBody ValoracionSolicitud v) {
        return servicio.valorar(codigo, v);
    }

    @PostMapping("/{codigo}/obligaciones")
    @PreAuthorize(Roles.OPERA_GARANTIAS)
    @Operation(summary = "Vincular la garantía a una obligación")
    @ResponseStatus(HttpStatus.CREATED)
    public Vinculo vincular(@PathVariable String codigo, @Valid @RequestBody VinculoSolicitud v) {
        return servicio.vincular(codigo, v);
    }

    @DeleteMapping("/{codigo}/obligaciones/{numero}")
    @PreAuthorize(Roles.OPERA_GARANTIAS)
    @Operation(summary = "Desvincular una obligación (exige motivo)")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void desvincular(@PathVariable String codigo, @PathVariable String numero, @Valid @RequestBody Desvinculacion d) {
        servicio.desvincular(codigo, numero, d.motivo());
    }

    static Map<String, Object> resumen(Garantia g) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", g.id);
        m.put("codigo", g.codigo);
        m.put("tipo", g.tipoCodigo);
        m.put("clienteDocumento", g.clienteDocumento);
        m.put("clienteNombre", g.clienteNombre);
        m.put("producto", g.producto);
        m.put("segmento", g.segmento);
        m.put("macroestado", g.macroestado);
        m.put("estadoJuridico", g.estadoJuridico);
        m.put("estadoDocumental", g.estadoDocumental);
        m.put("idoneidad", g.idoneidad);
        m.put("idoneidadMotivo", g.idoneidadMotivo);
        m.put("moneda", g.moneda);
        m.put("valorComercial", g.valorComercial);
        m.put("valorAdmisible", g.valorAdmisible);
        m.put("valorNeto", g.valorNeto);
        m.put("fechaUltimaValoracion", g.fechaUltimaValoracion);
        m.put("fechaProximaValoracion", g.fechaProximaValoracion);
        m.put("perfeccionada", g.perfeccionada);
        m.put("fuente", g.fuente);
        m.put("aplicativoOrigen", g.aplicativoOrigen);
        m.put("referenciaExterna", g.referenciaExterna);
        m.put("createdAt", g.createdAt);
        m.put("updatedAt", g.updatedAt);
        return m;
    }
}
