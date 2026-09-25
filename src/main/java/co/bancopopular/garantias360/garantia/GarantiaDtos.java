package co.bancopopular.garantias360.garantia;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Contratos de entrada de la API de garantías (OpenAPI, RF-1701). */
public final class GarantiaDtos {

    private GarantiaDtos() {
    }

    public record Origen(@NotBlank String aplicativo, @NotBlank String referencia) {
    }

    public record Cliente(@NotBlank String tipoDocumento, @NotBlank String numeroDocumento, @NotBlank String nombre) {
    }

    public record ParticipanteSolicitud(
            @NotBlank @Pattern(regexp = "PROPIETARIO|CONSTITUYENTE|GARANTE|DEUDOR|BENEFICIARIO") String rol,
            @NotBlank String tipoDocumento, @NotBlank String numeroDocumento, @NotBlank String nombre,
            @DecimalMin("0") @DecimalMax("100") BigDecimal porcentaje) {
    }

    /** Vínculo a una obligación por su número en Flexcube; si aún no existe se crea como APROBADA. */
    public record VinculoSolicitud(
            @NotBlank String numeroObligacion,
            @Pattern(regexp = "ABIERTA|CERRADA") String tipo,
            @DecimalMin("0") BigDecimal tope,
            @DecimalMin("0") BigDecimal valorPactado,
            @Min(0) Integer prioridad,
            String producto,
            String segmento,
            String destino) {
    }

    public record ValoracionSolicitud(
            @NotBlank String tipo,
            @NotNull @PastOrPresent LocalDate fecha,
            @NotNull @DecimalMin("0") BigDecimal valorComercial,
            @DecimalMin("0") BigDecimal valorTecnico,
            String moneda,
            String perito,
            String raa,
            String metodologia,
            LocalDate vigenciaHasta,
            String soporteRef,
            String motivo) {
    }

    public record RegistroGarantia(
            @NotBlank String tipo,
            @NotNull @Valid Origen origen,
            @NotNull @Valid Cliente cliente,
            @NotBlank String producto,
            @NotBlank String segmento,
            String moneda,
            @DecimalMin("0") BigDecimal gravamenesPrevios,
            JsonNode atributos,
            @Valid List<ParticipanteSolicitud> participantes,
            @Valid List<VinculoSolicitud> obligaciones,
            @Valid ValoracionSolicitud valoracionInicial) {
    }

    public record Transicion(@NotNull Macroestado destino, String motivo, boolean autorizacionExcepcional) {
    }

    public record EstudioJuridico(
            @NotBlank @Pattern(regexp = "PENDIENTE|EN_ESTUDIO|CON_OBSERVACIONES|APROBADA|CONDICIONADA|RECHAZADA") String estado,
            @Min(0) int condicionamientosAbiertos,
            String concepto) {
    }

    public record Perfeccionamiento(@NotNull LocalDate fechaConstitucion, @NotNull LocalDate fechaPerfeccionamiento,
                                    JsonNode datosRegistro) {
    }

    public record Desvinculacion(@NotBlank String motivo) {
    }
}
