package co.bancopopular.garantias360.api;

import co.bancopopular.garantias360.configuracion.DefinicionTipo;
import co.bancopopular.garantias360.configuracion.TipoGarantiaService;
import co.bancopopular.garantias360.configuracion.TipoGarantiaService.*;
import co.bancopopular.garantias360.configuracion.TipoGarantiaVersion;
import co.bancopopular.garantias360.seguridad.Roles;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/tipos-garantia")
@Tag(name = "Tipos de garantía", description = "Configuración sin desarrollo con versiones y maker–checker (M16)")
public class TipoGarantiaController {

    private static final String ADMINISTRACION = "hasAnyRole('ADMIN_FUNCIONAL','AUDITOR','CUMPLIMIENTO')";

    private final TipoGarantiaService tipos;

    public TipoGarantiaController(TipoGarantiaService tipos) {
        this.tipos = tipos;
    }

    public record Motivo(String motivo) {
    }

    public record CambioEstado(boolean activo, String motivo) {
    }

    @GetMapping
    @PreAuthorize(Roles.LECTURA)
    @Operation(summary = "Tipos de garantía con su versión publicada y campos")
    public List<TipoConVersion> listar(@RequestParam(defaultValue = "false") boolean incluirInactivos) {
        return tipos.listar(incluirInactivos);
    }

    @GetMapping("/administracion")
    @PreAuthorize(ADMINISTRACION)
    @Operation(summary = "Vista de administración: versión publicada, versión en curso y garantías que usan cada tipo")
    public List<TipoAdministracion> administracion() {
        return tipos.administracion();
    }

    @GetMapping("/registros-publicos")
    @PreAuthorize(Roles.LECTURA)
    public List<String> registrosPublicos() {
        return DefinicionTipo.REGISTROS;
    }

    @GetMapping("/{codigo}")
    @PreAuthorize(Roles.LECTURA)
    public TipoConVersion tipo(@PathVariable String codigo) {
        return tipos.vigente(codigo);
    }

    @GetMapping("/{codigo}/esquema")
    @PreAuthorize(Roles.LECTURA)
    @Operation(summary = "JSON Schema de los atributos del tipo en su versión publicada (RF-1609)")
    public ObjectNode esquema(@PathVariable String codigo) {
        return tipos.esquema(codigo);
    }

    @GetMapping("/{codigo}/versiones")
    @PreAuthorize(ADMINISTRACION)
    public List<TipoGarantiaVersion> versiones(@PathVariable String codigo) {
        return tipos.versiones(codigo);
    }

    @GetMapping("/{codigo}/versiones/{numero}")
    @PreAuthorize(ADMINISTRACION)
    public TipoConVersion version(@PathVariable String codigo, @PathVariable int numero) {
        return tipos.version(codigo, numero);
    }

    @PostMapping
    @PreAuthorize(Roles.CONFIGURA_TIPOS)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Crear un tipo (versión 1 en borrador; se publica con la aprobación de otro administrador)")
    public TipoGarantiaVersion crear(@RequestBody NuevoTipo nuevo) {
        return tipos.crear(nuevo);
    }

    @PostMapping("/{codigo}/versiones")
    @PreAuthorize(Roles.CONFIGURA_TIPOS)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Nueva versión en borrador a partir de la publicada")
    public TipoGarantiaVersion nuevaVersion(@PathVariable String codigo) {
        return tipos.nuevaVersion(codigo);
    }

    @PutMapping("/{codigo}/versiones/{numero}")
    @PreAuthorize(Roles.CONFIGURA_TIPOS)
    @Operation(summary = "Editar un borrador: comportamiento, campos, checklist jurídico y plantilla de constitución")
    public TipoGarantiaVersion editar(@PathVariable String codigo, @PathVariable int numero,
                                     @RequestBody DefinicionTipo.Edicion edicion) {
        return tipos.editar(codigo, numero, edicion);
    }

    @DeleteMapping("/{codigo}/versiones/{numero}")
    @PreAuthorize(Roles.CONFIGURA_TIPOS)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void descartar(@PathVariable String codigo, @PathVariable int numero) {
        tipos.descartar(codigo, numero);
    }

    @PostMapping("/{codigo}/versiones/{numero}/envio")
    @PreAuthorize(Roles.CONFIGURA_TIPOS)
    @Operation(summary = "Enviar a aprobación (maker)")
    public TipoGarantiaVersion enviar(@PathVariable String codigo, @PathVariable int numero) {
        return tipos.enviar(codigo, numero);
    }

    @PostMapping("/{codigo}/versiones/{numero}/aprobacion")
    @PreAuthorize(Roles.CONFIGURA_TIPOS)
    @Operation(summary = "Aprobar y publicar (checker distinto de quien editó, RF-1607)")
    public TipoGarantiaVersion aprobar(@PathVariable String codigo, @PathVariable int numero) {
        return tipos.aprobar(codigo, numero);
    }

    @PostMapping("/{codigo}/versiones/{numero}/rechazo")
    @PreAuthorize(Roles.CONFIGURA_TIPOS)
    public TipoGarantiaVersion rechazar(@PathVariable String codigo, @PathVariable int numero, @RequestBody Motivo m) {
        return tipos.rechazar(codigo, numero, m.motivo());
    }

    @GetMapping("/{codigo}/impacto")
    @PreAuthorize(ADMINISTRACION)
    @Operation(summary = "Garantías activas que usan el tipo (advertencia al inactivar)")
    public Map<String, Object> impacto(@PathVariable String codigo) {
        return Map.of("tipo", codigo, "garantiasActivas", tipos.garantiasActivas(codigo));
    }

    @PostMapping("/{codigo}/estado")
    @PreAuthorize(Roles.CONFIGURA_TIPOS)
    @Operation(summary = "Inactivar o reactivar un tipo (advertencia no bloqueante, RF-1601)")
    public TipoAdministracion estado(@PathVariable String codigo, @RequestBody CambioEstado c) {
        return tipos.cambiarActivo(codigo, c.activo(), c.motivo());
    }
}
