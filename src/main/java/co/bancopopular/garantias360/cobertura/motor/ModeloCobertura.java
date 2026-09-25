package co.bancopopular.garantias360.cobertura.motor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Tipos de entrada y salida del motor de cobertura (anexo A de la especificación).
 * Todos los montos llegan y salen en COP; la conversión con TRM ocurre dentro del motor.
 */
public final class ModeloCobertura {

    private ModeloCobertura() {
    }

    public enum MetodoDistribucion { SECUENCIAL, PRORRATA }

    public enum EstadoElemento { VALIDA, INCOMPLETA, INVALIDA }

    public enum EstadoObligacion { COMPLETO, PARCIAL, SIN_EXPOSICION, INVALIDA }

    public enum EstadoCalculo { COMPLETO, PARCIAL, INVALIDO }

    public enum Fase { PACTADA, EXCLUSIVA, COMPARTIDA_SECUENCIAL, COMPARTIDA_PRORRATA, EXCEDENTE }

    /**
     * @param valorBruto   valor de la valoración vigente en la moneda de la garantía; null = sin valoración
     * @param trm          tasa de cambio a COP; requerida si la moneda no es COP
     * @param haircut      fracción en [0, 1] resuelta por la regla HAIRCUT
     * @param prioridad    menor = se usa primero (regla PRIORIDAD_GARANTIA)
     */
    public record GarantiaEntrada(
            String id,
            String codigo,
            String tipo,
            String moneda,
            BigDecimal valorBruto,
            BigDecimal trm,
            BigDecimal haircut,
            String reglaHaircut,
            BigDecimal gravamenes,
            boolean idonea,
            int prioridad,
            MetodoDistribucion metodo,
            String reglaMetodo,
            boolean asignarExcedente) {
    }

    public record ObligacionEntrada(
            String id,
            String numero,
            BigDecimal exposicion,
            BigDecimal coberturaObjetivo,
            String reglaObjetivo,
            int prioridad,
            LocalDate fechaDesembolso) {
    }

    /** @param tope null = sin tope; valorPactado null = sin asignación pactada. */
    public record VinculoEntrada(
            String garantiaId,
            String obligacionId,
            BigDecimal tope,
            BigDecimal valorPactado,
            int prioridad) {
    }

    public record EntradaCobertura(
            List<GarantiaEntrada> garantias,
            List<ObligacionEntrada> obligaciones,
            List<VinculoEntrada> vinculos) {
    }

    public record ResultadoGarantia(
            String id,
            String codigo,
            EstadoElemento estado,
            String motivo,
            boolean idonea,
            boolean exclusiva,
            BigDecimal valorBruto,
            BigDecimal haircut,
            BigDecimal valorAdmisible,
            BigDecimal gravamenes,
            BigDecimal valorNeto,
            BigDecimal utilizado,
            BigDecimal disponible) {
    }

    /** ratio y ratioIdoneo son null cuando no hay exposición (no se divide por cero). */
    public record ResultadoObligacion(
            String id,
            String numero,
            EstadoObligacion estado,
            String motivo,
            BigDecimal exposicion,
            BigDecimal coberturaObjetivo,
            BigDecimal requerido,
            BigDecimal asignado,
            BigDecimal asignadoIdoneo,
            BigDecimal ratio,
            BigDecimal ratioIdoneo,
            BigDecimal descubierto,
            BigDecimal brecha) {
    }

    public record Asignacion(
            int orden,
            String garantiaId,
            String obligacionId,
            BigDecimal valor,
            Fase fase) {
    }

    public record PasoTraza(
            int orden,
            String ambito,
            String garantiaId,
            String obligacionId,
            String descripcion,
            String formula,
            String resultado,
            String regla) {
    }

    public record ResultadoCobertura(
            EstadoCalculo estado,
            List<ResultadoGarantia> garantias,
            List<ResultadoObligacion> obligaciones,
            List<Asignacion> asignaciones,
            List<PasoTraza> traza) {
    }
}
