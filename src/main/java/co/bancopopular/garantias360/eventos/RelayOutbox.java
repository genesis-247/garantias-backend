package co.bancopopular.garantias360.eventos;

import co.bancopopular.garantias360.comun.Contexto;
import co.bancopopular.garantias360.comun.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * Publica los eventos pendientes del outbox con reintentos y backoff (RF-2101). Tras el número
 * máximo de intentos el evento queda en ERROR (equivalente a DLQ) y se reprocesa desde M09.
 * "for update skip locked" permite varias réplicas sin publicar dos veces el mismo evento.
 */
@Component
public class RelayOutbox {

    private static final Logger log = LoggerFactory.getLogger(RelayOutbox.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final PublicadorEventos publicador;
    private final int maxIntentos;

    public RelayOutbox(JdbcTemplate jdbc, TransactionTemplate tx, PublicadorEventos publicador,
                       @Value("${g360.outbox.max-intentos:5}") int maxIntentos) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.publicador = publicador;
        this.maxIntentos = maxIntentos;
    }

    @Scheduled(fixedDelayString = "${g360.outbox.intervalo-ms:2000}")
    public void publicarPendientes() {
        Integer procesados;
        do {
            procesados = tx.execute(s -> publicarLote());
        } while (procesados != null && procesados > 0);
    }

    private int publicarLote() {
        List<PublicadorEventos.EventoSaliente> lote = jdbc.query("""
                        select * from evento_outbox
                        where estado = 'PENDIENTE'
                          and (intentos = 0 or creado_en < now() - make_interval(secs => power(2, intentos)))
                        order by creado_en
                        limit 100
                        for update skip locked""",
                (rs, i) -> new PublicadorEventos.EventoSaliente(rs.getString("id"), rs.getString("tipo"),
                        rs.getInt("version"), rs.getString("agregado"), rs.getString("agregado_id"),
                        rs.getString("correlation_id"), rs.getString("causation_id"),
                        rs.getTimestamp("creado_en").toInstant(), leer(rs.getString("payload"))));
        for (var evento : lote) {
            Contexto.conCorrelation(evento.correlationId() == null ? evento.id() : evento.correlationId(), () -> {
                try {
                    publicador.publicar(evento);
                    jdbc.update("update evento_outbox set estado = 'PUBLICADO', publicado_en = ?, intentos = intentos + 1, error = null where id = ?::uuid",
                            Timestamp.from(Instant.now()), evento.id());
                } catch (Exception e) {
                    log.warn("Fallo publicando evento {}: {}", evento.id(), e.getMessage());
                    jdbc.update("""
                                    update evento_outbox
                                    set intentos = intentos + 1, error = ?,
                                        estado = case when intentos + 1 >= ? then 'ERROR' else 'PENDIENTE' end
                                    where id = ?::uuid""",
                            truncar(e.getMessage()), maxIntentos, evento.id());
                }
            });
        }
        return lote.size();
    }

    /** Reprocesamiento controlado desde la DLQ (RF-0904). */
    public int reprocesar(String id) {
        return jdbc.update("update evento_outbox set estado = 'PENDIENTE', intentos = 0, error = null where id = ?::uuid and estado = 'ERROR'", id);
    }

    private static Object leer(String json) {
        try {
            return Json.CANONICO.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String truncar(String texto) {
        return texto == null ? "error desconocido" : texto.substring(0, Math.min(texto.length(), 1000));
    }
}
