package co.bancopopular.garantias360.api;

import co.bancopopular.garantias360.constitucion.ConstitucionService;
import co.bancopopular.garantias360.constitucion.ConstitucionService.Cambio;
import co.bancopopular.garantias360.constitucion.ConstitucionService.Plan;
import co.bancopopular.garantias360.seguridad.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Constitución y registro", description = "Plan de actividades de perfeccionamiento por garantía (M06)")
public class ConstitucionController {

    private final ConstitucionService constitucion;

    public ConstitucionController(ConstitucionService constitucion) {
        this.constitucion = constitucion;
    }

    @GetMapping("/constitucion")
    @PreAuthorize(Roles.LECTURA)
    @Operation(summary = "Bandeja de constitución: avance, vencimientos y bloqueos")
    public Map<String, Object> tablero() {
        return constitucion.tablero();
    }

    @GetMapping("/garantias/{codigo}/actividades-constitucion")
    @PreAuthorize(Roles.LECTURA)
    @Operation(summary = "Plan de actividades de constitución de la garantía")
    public Plan plan(@PathVariable String codigo) {
        return constitucion.plan(codigo);
    }

    @PostMapping("/garantias/{codigo}/actividades-constitucion")
    @PreAuthorize(Roles.CONSTITUYE)
    @Operation(summary = "Generar el plan desde la plantilla del tipo (si la garantía aún no lo tiene)")
    public Plan generar(@PathVariable String codigo) {
        return constitucion.generarPlan(codigo);
    }

    @PatchMapping("/garantias/{codigo}/actividades-constitucion/{actividad}")
    @PreAuthorize(Roles.CONSTITUYE)
    @Operation(summary = "Registrar el avance de una actividad (invocado por Appian o desde la interfaz, RF-0605)")
    public Plan actualizar(@PathVariable String codigo, @PathVariable String actividad, @RequestBody Cambio cambio) {
        return constitucion.actualizar(codigo, actividad, cambio);
    }
}
