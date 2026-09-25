package co.bancopopular.garantias360.garantia;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Relación N:M garantía–obligación (RF-0403). */
@Entity
@Table(name = "vinculo_garantia_obligacion")
public class Vinculo {
    @Id
    public UUID id;
    @Column(name = "garantia_id")
    public UUID garantiaId;
    @Column(name = "obligacion_id")
    public UUID obligacionId;
    public String tipo;
    public BigDecimal tope;
    @Column(name = "valor_pactado")
    public BigDecimal valorPactado;
    public int prioridad = 100;
    public boolean vigente = true;
    @Column(name = "created_at")
    public Instant createdAt = Instant.now();
}
