package co.bancopopular.garantias360.carga;

import co.bancopopular.garantias360.auditoria.AuditoriaService;
import co.bancopopular.garantias360.comun.Contexto;
import co.bancopopular.garantias360.comun.Errores;
import co.bancopopular.garantias360.comun.Json;
import co.bancopopular.garantias360.eventos.OutboxService;
import co.bancopopular.garantias360.garantia.GarantiaDtos.RegistroGarantia;
import co.bancopopular.garantias360.garantia.GarantiaDtos.ValoracionSolicitud;
import co.bancopopular.garantias360.garantia.GarantiaService;
import co.bancopopular.garantias360.seguridad.Roles;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.Executors;

/**
 * Procesa una carga masiva aprobada, fila por fila y cada una en su propia transacción: una fila
 * que falla no revierte las demás. El registro es idempotente por fila (Idempotency-Key derivada de
 * la carga y el número de fila), así que un reproceso tras una caída no duplica garantías.
 */
@Component
public class ProcesadorCarga {

    private static final Logger log = LoggerFactory.getLogger(ProcesadorCarga.class);

    private final GarantiaService garantias;
    private final JdbcTemplate jdbc;
    private final AuditoriaService auditoria;
    private final OutboxService outbox;
    private final TransactionTemplate tx;
    private final int maxMinutos;

    public ProcesadorCarga(GarantiaService garantias, JdbcTemplate jdbc, AuditoriaService auditoria, OutboxService outbox,
                           PlatformTransactionManager transacciones,
                           @Value("${g360.carga.max-minutos-proceso:30}") int maxMinutos) {
        this.garantias = garantias;
        this.jdbc = jdbc;
        this.auditoria = auditoria;
        this.outbox = outbox;
        this.tx = new TransactionTemplate(transacciones);
        this.maxMinutos = maxMinutos;
    }

    public void procesar(UUID id) {
        Map<String, Object> c = jdbc.queryForMap("select * from carga_masiva where id = ?", id);
        if (!"EN_PROCESO".equals(c.get("estado"))) {
            return;
        }
        long numeroCarga = ((Number) c.get("numero")).longValue();
        String motivo = "Carga masiva #" + numeroCarga + " creada por " + c.get("creado_por") + ", aprobada por " + c.get("aprobado_por");
        if (!Boolean.TRUE.equals(c.get("incluir_actualizaciones"))) {
            jdbc.update("""
                    update carga_masiva_fila set estado = 'OMITIDA', errores = '["Actualización no confirmada al enviar la carga"]'::jsonb
                    where carga_id = ? and estado = 'VALIDA' and accion = 'ACTUALIZAR'""", id);
        }
        List<Map<String, Object>> filas = jdbc.queryForList(
                "select numero, accion, datos from carga_masiva_fila where carga_id = ? and estado = 'VALIDA' order by numero", id);
        long limite = System.currentTimeMillis() + maxMinutos * 60_000L;
        log.info("Procesando carga masiva #{}: {} filas", numeroCarga, filas.size());
        for (Map<String, Object> f : filas) {
            int numero = ((Number) f.get("numero")).intValue();
            if (System.currentTimeMillis() > limite) {
                fallida(id, numero, List.of("Se agotó el tiempo máximo de procesamiento (" + maxMinutos
                        + " min). Vuelve a cargar esta fila."));
                continue;
            }
            JsonNode datos = CargaMasivaService.leer(f.get("datos").toString());
            try {
                String codigo = "CREAR".equals(f.get("accion"))
                        ? crear(id, numero, datos)
                        : actualizar(datos, motivo);
                jdbc.update("update carga_masiva_fila set estado = 'PROCESADA', garantia_codigo = ?, procesada_en = now() where carga_id = ? and numero = ?",
                        codigo, id, numero);
                jdbc.update("update carga_masiva set filas_procesadas = filas_procesadas + 1 where id = ?", id);
            } catch (Errores.NegocioException e) {
                List<String> errores = new ArrayList<>();
                errores.add(e.getMessage());
                errores.addAll(e.detalles());
                fallida(id, numero, errores);
            } catch (RuntimeException e) {
                log.error("Falló la fila {} de la carga #{}", numero, numeroCarga, e);
                fallida(id, numero, List.of("Error inesperado al procesar la fila. Referencia para soporte: " + Contexto.correlationId()));
            }
        }
        tx.executeWithoutResult(s -> {
            Map<String, Object> fin = jdbc.queryForMap("select filas_procesadas, filas_fallidas from carga_masiva where id = ?", id);
            int procesadas = ((Number) fin.get("filas_procesadas")).intValue();
            int fallidas = ((Number) fin.get("filas_fallidas")).intValue();
            String estado = fallidas > 0 ? "PROCESADA_CON_FALLOS" : "PROCESADA";
            jdbc.update("update carga_masiva set estado = ?, procesada_en = now() where id = ?", estado, id);
            auditoria.registrar("PROCESAR_CARGA_MASIVA", "CargaMasiva", String.valueOf(numeroCarga), null,
                    Map.of("estado", estado, "procesadas", procesadas, "fallidas", fallidas), null, "CARGA_MASIVA");
            outbox.publicar("CargaMasivaProcesada", "CargaMasiva", String.valueOf(numeroCarga),
                    Map.of("carga", numeroCarga, "tipo", c.get("tipo_codigo"), "estado", estado, "procesadas", procesadas,
                            "fallidas", fallidas));
        });
        log.info("Carga masiva #{} terminada", numeroCarga);
    }

    private String crear(UUID carga, int numero, JsonNode datos) {
        RegistroGarantia r = Json.leer(datos.get("registro"), RegistroGarantia.class);
        return garantias.registrar(r, "carga-" + carga + "-" + numero, "CARGA_MASIVA").codigo;
    }

    private String actualizar(JsonNode datos, String motivo) {
        JsonNode a = datos.get("actualizacion");
        String codigo = a.get("garantia").asText();
        JsonNode atributos = a.get("atributos");
        BigDecimal gravamenes = a.hasNonNull("gravamenesPrevios") ? a.get("gravamenesPrevios").decimalValue() : null;
        if ((atributos != null && !atributos.isEmpty()) || gravamenes != null) {
            garantias.actualizar(codigo, atributos, gravamenes, motivo);
        }
        if (a.hasNonNull("valoracion")) {
            garantias.valorar(codigo, Json.leer(a.get("valoracion"), ValoracionSolicitud.class));
        }
        return codigo;
    }

    private void fallida(UUID carga, int numero, List<String> errores) {
        jdbc.update("update carga_masiva_fila set estado = 'FALLIDA', errores = ?::jsonb, procesada_en = now() where carga_id = ? and numero = ?",
                Json.canonico(errores), carga, numero);
        jdbc.update("update carga_masiva set filas_fallidas = filas_fallidas + 1 where id = ?", carga);
    }

    /** Retoma las cargas que quedaron en proceso si el servicio se reinició a mitad de camino. */
    @EventListener(ApplicationReadyEvent.class)
    public void reanudar() {
        List<Map<String, Object>> pendientes = jdbc.queryForList("select id, aprobado_por from carga_masiva where estado = 'EN_PROCESO'");
        if (pendientes.isEmpty()) {
            return;
        }
        var ejecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "g360-carga-reanudar");
            t.setDaemon(true);
            return t;
        });
        for (Map<String, Object> p : pendientes) {
            ejecutor.submit(() -> {
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                        p.get("aprobado_por"), "n/a", List.of(new SimpleGrantedAuthority("ROLE_" + Roles.OPERACIONES_DIRECTOR))));
                try {
                    Contexto.conCorrelation(UUID.randomUUID().toString(), () -> procesar((UUID) p.get("id")));
                } finally {
                    SecurityContextHolder.clearContext();
                }
            });
        }
        ejecutor.shutdown();
    }
}
