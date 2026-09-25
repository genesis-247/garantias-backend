package co.bancopopular.garantias360.api;

import co.bancopopular.garantias360.cobertura.SimulacionService;
import co.bancopopular.garantias360.reglas.Regla;
import co.bancopopular.garantias360.reglas.ReglaService;
import co.bancopopular.garantias360.reglas.ReglaVersion;
import co.bancopopular.garantias360.reglas.motor.TipoRegla;
import co.bancopopular.garantias360.seguridad.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/v1/reglas")
@Tag(name = "Reglas", description = "Motor de reglas no-code con maker–checker (anexo B)")
public class ReglaController {

    private final ReglaService servicio;
    private final SimulacionService simulacion;

    public ReglaController(ReglaService servicio, SimulacionService simulacion) {
        this.servicio = servicio;
        this.simulacion = simulacion;
    }

    public record Motivo(String motivo) {
    }

    @GetMapping("/tipos")
    @PreAuthorize(Roles.LECTURA)
    @Operation(summary = "Tipos de regla con sus variables y tipo de resultado")
    public List<Map<String, Object>> tipos() {
        return Arrays.stream(TipoRegla.values()).map(t -> Map.<String, Object>of("tipo", t, "descripcion", t.descripcion(),
                "variables", new TreeMap<>(t.variables()), "resultado", t.resultado())).toList();
    }

    @GetMapping
    @PreAuthorize(Roles.LECTURA)
    public List<Map<String, Object>> listar() {
        return servicio.listar().stream().map(r -> {
            List<ReglaVersion> vs = servicio.versiones(r.codigo);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("regla", r);
            m.put("activa", vs.stream().filter(v -> v.estado == ReglaVersion.EstadoRegla.ACTIVA).findFirst().map(v -> v.numero).orElse(null));
            m.put("pendientes", vs.stream().filter(v -> v.estado == ReglaVersion.EstadoRegla.EN_REVISION).count());
            m.put("ultima", vs.isEmpty() ? null : Map.of("numero", vs.getFirst().numero, "estado", vs.getFirst().estado));
            return m;
        }).toList();
    }

    @GetMapping("/{codigo}")
    @PreAuthorize(Roles.LECTURA)
    public Map<String, Object> detalle(@PathVariable String codigo) {
        Regla r = servicio.regla(codigo);
        return Map.of("regla", r, "versiones", servicio.versiones(codigo), "contrato",
                Map.of("variables", new TreeMap<>(r.tipo.variables()), "resultado", r.tipo.resultado()));
    }

    @PostMapping
    @PreAuthorize(Roles.EDITA_REGLAS)
    @ResponseStatus(HttpStatus.CREATED)
    public ReglaVersion crear(@RequestBody ReglaService.NuevaRegla nueva) {
        return servicio.crear(nueva);
    }

    @PostMapping("/{codigo}/versiones")
    @PreAuthorize(Roles.EDITA_REGLAS)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Nueva versión en borrador clonando otra")
    public ReglaVersion nuevaVersion(@PathVariable String codigo, @RequestParam int base) {
        return servicio.nuevaVersionDesde(codigo, base);
    }

    @PutMapping("/{codigo}/versiones/{numero}")
    @PreAuthorize(Roles.EDITA_REGLAS)
    public ReglaVersion editar(@PathVariable String codigo, @PathVariable int numero, @RequestBody ReglaService.EdicionVersion e) {
        return servicio.editar(codigo, numero, e);
    }

    @PostMapping("/{codigo}/versiones/{numero}/pruebas")
    @PreAuthorize(Roles.LECTURA)
    public ReglaService.ResultadoPruebas probar(@PathVariable String codigo, @PathVariable int numero) {
        return servicio.probar(codigo, numero);
    }

    @PostMapping("/{codigo}/versiones/{numero}/simulacion")
    @PreAuthorize(Roles.LECTURA)
    @Operation(summary = "Simular la versión sobre el portafolio real, sin efectos")
    public Map<String, Object> simular(@PathVariable String codigo, @PathVariable int numero) {
        return simulacion.simular(codigo, numero);
    }

    @PostMapping("/{codigo}/versiones/{numero}/envio")
    @PreAuthorize(Roles.EDITA_REGLAS)
    public ReglaVersion enviar(@PathVariable String codigo, @PathVariable int numero) {
        return servicio.enviar(codigo, numero);
    }

    @PostMapping("/{codigo}/versiones/{numero}/aprobacion")
    @PreAuthorize(Roles.APRUEBA_REGLAS)
    public ReglaVersion aprobar(@PathVariable String codigo, @PathVariable int numero) {
        return servicio.aprobar(codigo, numero);
    }

    @PostMapping("/{codigo}/versiones/{numero}/rechazo")
    @PreAuthorize(Roles.APRUEBA_REGLAS)
    public ReglaVersion rechazar(@PathVariable String codigo, @PathVariable int numero, @RequestBody Motivo m) {
        return servicio.rechazar(codigo, numero, m.motivo());
    }

    @PostMapping("/{codigo}/versiones/{numero}/activacion")
    @PreAuthorize(Roles.APRUEBA_REGLAS)
    public ReglaVersion activar(@PathVariable String codigo, @PathVariable int numero) {
        return servicio.activar(codigo, numero);
    }

    @PostMapping("/{codigo}/versiones/{numero}/desactivacion")
    @PreAuthorize(Roles.APRUEBA_REGLAS)
    public ReglaVersion desactivar(@PathVariable String codigo, @PathVariable int numero, @RequestBody Motivo m) {
        return servicio.desactivar(codigo, numero, m.motivo());
    }
}
