package co.bancopopular.garantias360.configuracion;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/** Versión inmutable del formulario de un tipo (modelo Proceder: RF-03.2). */
@Entity
@Table(name = "tipo_garantia_version")
public class TipoGarantiaVersion {
    @Id
    public UUID id;
    @Column(name = "tipo_id")
    public UUID tipoId;
    public int numero;
    @JdbcTypeCode(SqlTypes.JSON)
    public JsonNode campos;
    public String estado;
    @Column(name = "creado_por")
    public String creadoPor;
    @Column(name = "aprobado_por")
    public String aprobadoPor;
    @Column(name = "created_at")
    public Instant createdAt = Instant.now();
}
