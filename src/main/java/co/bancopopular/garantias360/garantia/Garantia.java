package co.bancopopular.garantias360.garantia;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Registro maestro de la garantía (M04, RF-0402). */
@Entity
@Table(name = "garantia")
public class Garantia {
    @Id
    public UUID id;
    public String codigo;
    @Column(name = "tipo_version_id")
    public UUID tipoVersionId;
    @Column(name = "tipo_codigo")
    public String tipoCodigo;
    @Column(name = "cliente_documento")
    public String clienteDocumento;
    @Column(name = "cliente_nombre")
    public String clienteNombre;
    public String producto;
    public String segmento;
    @Enumerated(EnumType.STRING)
    public Macroestado macroestado;
    @Column(name = "estado_juridico")
    public String estadoJuridico = "SIN_ESTUDIO";
    @Column(name = "estado_documental")
    public String estadoDocumental = "INCOMPLETO";
    public String idoneidad = "NO_EVALUADA";
    @Column(name = "idoneidad_motivo")
    public String idoneidadMotivo;
    @Column(name = "idoneidad_regla")
    public String idoneidadRegla;
    public String moneda = "COP";
    @Column(name = "valor_comercial")
    public BigDecimal valorComercial;
    @Column(name = "valor_admisible")
    public BigDecimal valorAdmisible;
    @Column(name = "valor_neto")
    public BigDecimal valorNeto;
    @Column(name = "gravamenes_previos")
    public BigDecimal gravamenesPrevios = BigDecimal.ZERO;
    @Column(name = "fecha_ultima_valoracion")
    public LocalDate fechaUltimaValoracion;
    @Column(name = "fecha_proxima_valoracion")
    public LocalDate fechaProximaValoracion;
    @Column(name = "fecha_constitucion")
    public LocalDate fechaConstitucion;
    @Column(name = "fecha_perfeccionamiento")
    public LocalDate fechaPerfeccionamiento;
    public boolean perfeccionada;
    @Column(name = "condicionamientos_abiertos")
    public int condicionamientosAbiertos;
    public String fuente;
    @Column(name = "aplicativo_origen")
    public String aplicativoOrigen;
    @Column(name = "referencia_externa")
    public String referenciaExterna;
    @JdbcTypeCode(SqlTypes.JSON)
    public JsonNode atributos;
    @Version
    public int version;
    @Column(name = "created_at")
    public Instant createdAt = Instant.now();
    @Column(name = "updated_at")
    public Instant updatedAt = Instant.now();
}
