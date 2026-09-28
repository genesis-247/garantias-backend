package co.bancopopular.garantias360.configuracion;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Versión del formulario y del comportamiento de un tipo (RF-1601, RF-1607). Una versión publicada
 * es inmutable: editar un tipo crea una versión nueva que pasa por maker–checker.
 */
@Entity
@Table(name = "tipo_garantia_version")
public class TipoGarantiaVersion {
    @Id
    public UUID id;
    @Column(name = "tipo_id")
    public UUID tipoId;
    public int numero;
    @JdbcTypeCode(SqlTypes.JSON)
    public JsonNode comportamiento;
    @JdbcTypeCode(SqlTypes.JSON)
    public JsonNode campos;
    @Column(name = "checklist_juridico")
    @JdbcTypeCode(SqlTypes.JSON)
    public JsonNode checklistJuridico;
    @JdbcTypeCode(SqlTypes.JSON)
    public JsonNode actividades;
    public String estado;
    public String motivo;
    public String hash;
    @Column(name = "creado_por")
    public String creadoPor;
    @Column(name = "aprobado_por")
    public String aprobadoPor;
    @Column(name = "created_at")
    public Instant createdAt = Instant.now();
    @Column(name = "enviada_en")
    public Instant enviadaEn;
    @Column(name = "aprobada_en")
    public Instant aprobadaEn;
}
