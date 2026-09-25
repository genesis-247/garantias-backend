package co.bancopopular.garantias360.garantia;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Valoración inmutable (RF-0701, RF-0702). */
@Entity
@Table(name = "valoracion")
public class Valoracion {
    @Id
    public UUID id;
    @Column(name = "garantia_id")
    public UUID garantiaId;
    public String tipo;
    public LocalDate fecha;
    @Column(name = "valor_comercial")
    public BigDecimal valorComercial;
    @Column(name = "valor_tecnico")
    public BigDecimal valorTecnico;
    public String moneda = "COP";
    public String perito;
    public String raa;
    public String metodologia;
    @Column(name = "vigencia_hasta")
    public LocalDate vigenciaHasta;
    @Column(name = "soporte_ref")
    public String soporteRef;
    public String motivo;
    @Column(name = "registrado_por")
    public String registradoPor;
    @Column(name = "created_at")
    public Instant createdAt = Instant.now();
}
