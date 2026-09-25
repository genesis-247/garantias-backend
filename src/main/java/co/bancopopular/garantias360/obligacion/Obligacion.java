package co.bancopopular.garantias360.obligacion;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Referencia a la obligación de Flexcube con el último saldo conocido (R-02). */
@Entity
@Table(name = "obligacion")
public class Obligacion {
    @Id
    public UUID id;
    public String numero;
    @Column(name = "cliente_documento")
    public String clienteDocumento;
    @Column(name = "cliente_nombre")
    public String clienteNombre;
    public String producto;
    public String segmento;
    public String moneda = "COP";
    @Column(name = "saldo_capital")
    public BigDecimal saldoCapital = BigDecimal.ZERO;
    @Column(name = "saldo_intereses")
    public BigDecimal saldoIntereses = BigDecimal.ZERO;
    @Column(name = "saldo_otros")
    public BigDecimal saldoOtros = BigDecimal.ZERO;
    public String estado;
    @Column(name = "dias_mora")
    public int diasMora;
    public String destino;
    @Column(name = "fecha_desembolso")
    public LocalDate fechaDesembolso;
    @Column(name = "actualizado_en")
    public Instant actualizadoEn = Instant.now();

    /** Exposición = capital + intereses + otros (regla EXPOSICION por defecto). */
    public BigDecimal exposicion() {
        return saldoCapital.add(saldoIntereses).add(saldoOtros);
    }

    public boolean activa() {
        return "VIGENTE".equals(estado) || "APROBADA".equals(estado);
    }
}
