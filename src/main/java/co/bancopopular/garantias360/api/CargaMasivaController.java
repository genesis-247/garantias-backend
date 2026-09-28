package co.bancopopular.garantias360.api;

import co.bancopopular.garantias360.carga.CargaMasivaService;
import co.bancopopular.garantias360.comun.Errores;
import co.bancopopular.garantias360.seguridad.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/cargas-masivas")
@Tag(name = "Carga masiva", description = "Asistente de 4 pasos con validación por fila y maker–checker (RF-1703)")
public class CargaMasivaController {

    private static final String CONSULTA = "hasAnyRole('OPERACIONES_GESTOR','OPERACIONES_DIRECTOR','AUDITOR','CUMPLIMIENTO')";
    private static final MediaType XLSX = MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final CargaMasivaService cargas;

    public CargaMasivaController(CargaMasivaService cargas) {
        this.cargas = cargas;
    }

    public record Envio(boolean incluirActualizaciones, boolean excluirErrores) {
    }

    public record Motivo(String motivo) {
    }

    @GetMapping("/limites")
    @PreAuthorize(Roles.LECTURA)
    public Map<String, Object> limites() {
        return cargas.limites();
    }

    @GetMapping("/plantilla")
    @PreAuthorize(CONSULTA)
    @Operation(summary = "Paso 1: plantilla .xlsx del tipo en su versión publicada")
    public ResponseEntity<byte[]> plantilla(@RequestParam String tipo) {
        return archivo(cargas.plantilla(tipo), "plantilla-" + tipo.toLowerCase() + ".xlsx", XLSX);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(Roles.CARGA_MASIVA)
    @Operation(summary = "Paso 2: cargar el archivo; valida estructura y cada fila sin afectar el maestro")
    public Map<String, Object> crear(@RequestParam String tipo, @RequestParam("archivo") MultipartFile archivo) throws IOException {
        if (archivo == null || archivo.isEmpty()) {
            throw Errores.invalido("ARCHIVO_VACIO", "Adjunta el archivo diligenciado", List.of());
        }
        return cargas.crear(tipo, archivo.getOriginalFilename(), archivo.getBytes());
    }

    @GetMapping
    @PreAuthorize(CONSULTA)
    @Operation(summary = "Historial de cargas masivas")
    public List<Map<String, Object>> listar(@RequestParam(required = false) String estado,
                                            @RequestParam(defaultValue = "50") int limite) {
        return cargas.listar(estado, limite);
    }

    @GetMapping("/{id}")
    @PreAuthorize(CONSULTA)
    @Operation(summary = "Paso 3: detalle con el resultado de la validación por fila")
    public Map<String, Object> detalle(@PathVariable UUID id, @RequestParam(required = false) String estadoFila,
                                       @RequestParam(defaultValue = "1000") int limite) {
        return cargas.detalle(id, estadoFila, limite);
    }

    @GetMapping("/{id}/reporte")
    @PreAuthorize(CONSULTA)
    @Operation(summary = "Reporte por fila en CSV (errores o completo)")
    public ResponseEntity<byte[]> reporte(@PathVariable UUID id, @RequestParam(defaultValue = "true") boolean soloErrores) {
        return archivo(cargas.reporte(id, soloErrores), "carga-" + id + (soloErrores ? "-errores" : "-resultado") + ".csv",
                new MediaType("text", "csv", StandardCharsets.UTF_8));
    }

    @PostMapping("/{id}/envio")
    @PreAuthorize(Roles.CARGA_MASIVA)
    @Operation(summary = "Paso 3: enviar a aprobación, confirmando aparte las filas que actualizan garantías existentes")
    public Map<String, Object> enviar(@PathVariable UUID id, @RequestBody Envio e) {
        return cargas.enviar(id, e.incluirActualizaciones(), e.excluirErrores());
    }

    @PostMapping("/{id}/aprobacion")
    @PreAuthorize(Roles.APRUEBA_CARGA)
    @Operation(summary = "Paso 4: aprobar (Director de Operaciones distinto de quien creó o envió) y procesar")
    public Map<String, Object> aprobar(@PathVariable UUID id) {
        return cargas.aprobar(id);
    }

    @PostMapping("/{id}/rechazo")
    @PreAuthorize(Roles.APRUEBA_CARGA)
    public Map<String, Object> rechazar(@PathVariable UUID id, @RequestBody Motivo m) {
        return cargas.rechazar(id, m.motivo());
    }

    @PostMapping("/{id}/cancelacion")
    @PreAuthorize(Roles.CARGA_MASIVA)
    public Map<String, Object> cancelar(@PathVariable UUID id, @RequestBody(required = false) Motivo m) {
        return cargas.cancelar(id, m == null ? null : m.motivo());
    }

    private static ResponseEntity<byte[]> archivo(byte[] contenido, String nombre, MediaType tipo) {
        return ResponseEntity.ok()
                .contentType(tipo)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(nombre).build().toString())
                .body(contenido);
    }
}
