package co.bancopopular.garantias360.configuracion;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Tipo de garantía configurable (M16). Los atributos de comportamiento reflejan la versión publicada
 * vigente (se copian al publicar). Campos públicos: entidad sin lógica, sin asociaciones perezosas.
 */
@Entity
@Table(name = "tipo_garantia")
public class TipoGarantia {
    @Id
    public UUID id;
    public String codigo;
    public String nombre;
    public String descripcion;
    public String clase;
    @Column(name = "requiere_avaluo")
    public boolean requiereAvaluo;
    @Column(name = "requiere_poliza")
    public boolean requierePoliza;
    @Column(name = "registro_publico")
    public String registroPublico;
    @Column(name = "admite_multiples")
    public boolean admiteMultiples;
    public boolean activo = true;
    @Column(name = "inactivado_por")
    public String inactivadoPor;
    @Column(name = "inactivado_en")
    public Instant inactivadoEn;
    @Column(name = "motivo_inactivacion")
    public String motivoInactivacion;
    @Column(name = "created_at")
    public Instant createdAt = Instant.now();
}
