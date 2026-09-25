package co.bancopopular.garantias360.garantia;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "participante_garantia")
public class Participante {
    @Id
    public UUID id;
    @Column(name = "garantia_id")
    public UUID garantiaId;
    public String rol;
    @Column(name = "tipo_documento")
    public String tipoDocumento;
    @Column(name = "numero_documento")
    public String numeroDocumento;
    public String nombre;
    public BigDecimal porcentaje;
}
