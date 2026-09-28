package co.bancopopular.garantias360.constitucion;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Actividad del plan de constitución y perfeccionamiento de una garantía (RF-0602). */
@Entity
@Table(name = "actividad_constitucion")
public class ActividadConstitucion {
    @Id
    public UUID id;
    @Column(name = "garantia_id")
    public UUID garantiaId;
    @Column(name = "tipo_version_id")
    public UUID tipoVersionId;
    public String codigo;
    public String nombre;
    public String descripcion;
    public int orden;
    public boolean obligatoria;
    @Column(name = "requiere_evidencia")
    public boolean requiereEvidencia;
    @JdbcTypeCode(SqlTypes.JSON)
    public JsonNode campos;
    @Column(name = "rol_responsable")
    public String rolResponsable;
    public String responsable;
    @Column(name = "fecha_limite")
    public LocalDate fechaLimite;
    public String estado;
    @Column(name = "evidencia_ref")
    public String evidenciaRef;
    @Column(name = "evidencia_hash")
    public String evidenciaHash;
    @JdbcTypeCode(SqlTypes.JSON)
    public JsonNode datos;
    public String observacion;
    @Column(name = "completada_por")
    public String completadaPor;
    @Column(name = "completada_en")
    public Instant completadaEn;
    @Column(name = "actualizado_por")
    public String actualizadoPor;
    @Column(name = "created_at")
    public Instant createdAt = Instant.now();
    @Column(name = "updated_at")
    public Instant updatedAt = Instant.now();
    @Version
    public int version;

    public boolean cerrada() {
        return "COMPLETADA".equals(estado) || "NO_APLICA".equals(estado);
    }
}
