package co.bancopopular.garantias360.reglas;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Versión de una regla: inmutable desde que se envía a revisión (anexo B, B.4). */
@Entity
@Table(name = "regla_version")
public class ReglaVersion {
    @Id
    public UUID id;
    @Column(name = "regla_id")
    public UUID reglaId;
    public int numero;
    @Enumerated(EnumType.STRING)
    public EstadoRegla estado;
    @JdbcTypeCode(SqlTypes.JSON)
    public JsonNode definicion;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "casos_prueba")
    public JsonNode casosPrueba;
    @Column(name = "vigente_desde")
    public LocalDate vigenteDesde;
    @Column(name = "vigente_hasta")
    public LocalDate vigenteHasta;
    @Column(name = "creado_por")
    public String creadoPor;
    @Column(name = "aprobado_por")
    public String aprobadoPor;
    public String motivo;
    public String hash;
    @Column(name = "created_at")
    public Instant createdAt = Instant.now();
    @Column(name = "enviada_en")
    public Instant enviadaEn;
    @Column(name = "aprobada_en")
    public Instant aprobadaEn;
    @Column(name = "activada_en")
    public Instant activadaEn;

    public enum EstadoRegla { BORRADOR, EN_REVISION, APROBADA, ACTIVA, INACTIVA, REEMPLAZADA, RECHAZADA }
}
