package co.bancopopular.garantias360.cobertura;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/** Cálculo inmutable y reproducible (RF-0804): entradas, versiones de reglas, resultado y traza. */
@Entity
@Table(name = "calculo_cobertura")
public class CalculoCobertura {
    @Id
    public UUID id;
    @Column(name = "fecha_corte")
    public Instant fechaCorte;
    public String alcance;
    public String disparador;
    @Column(name = "correlation_id")
    public String correlationId;
    public String estado;
    @Column(name = "hash_entradas")
    public String hashEntradas;
    @Column(name = "hash_resultado")
    public String hashResultado;
    @JdbcTypeCode(SqlTypes.JSON)
    public JsonNode reglas;
    @JdbcTypeCode(SqlTypes.JSON)
    public JsonNode entradas;
    @JdbcTypeCode(SqlTypes.JSON)
    public JsonNode resultado;
    @JdbcTypeCode(SqlTypes.ARRAY)
    public UUID[] obligaciones;
    @JdbcTypeCode(SqlTypes.ARRAY)
    public UUID[] garantias;
    @Column(name = "creado_por")
    public String creadoPor;
    @Column(name = "created_at")
    public Instant createdAt = Instant.now();
}
