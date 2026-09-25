package co.bancopopular.garantias360.reglas;

import co.bancopopular.garantias360.reglas.motor.TipoRegla;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "regla")
public class Regla {
    @Id
    public UUID id;
    public String codigo;
    public String nombre;
    public String descripcion;
    @Enumerated(EnumType.STRING)
    public TipoRegla tipo;
    @Column(name = "created_at")
    public Instant createdAt = Instant.now();
}
