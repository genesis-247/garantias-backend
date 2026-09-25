package co.bancopopular.garantias360.cobertura;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Última cobertura calculada por obligación, para consultas rápidas (M01, M10, FICO). */
@Entity
@Table(name = "cobertura_vigente")
public class CoberturaVigente {
    @Id
    @Column(name = "obligacion_id")
    public UUID obligacionId;
    @Column(name = "calculo_id")
    public UUID calculoId;
    public BigDecimal exposicion;
    @Column(name = "cobertura_objetivo")
    public BigDecimal coberturaObjetivo;
    public BigDecimal requerido;
    public BigDecimal asignado;
    @Column(name = "asignado_idoneo")
    public BigDecimal asignadoIdoneo;
    public BigDecimal ratio;
    @Column(name = "ratio_idoneo")
    public BigDecimal ratioIdoneo;
    public BigDecimal descubierto;
    public BigDecimal brecha;
    public String estado;
    @Column(name = "actualizado_en")
    public Instant actualizadoEn = Instant.now();
}
