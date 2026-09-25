package co.bancopopular.garantias360.eventos;

import co.bancopopular.garantias360.comun.Contexto;
import co.bancopopular.garantias360.comun.Json;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Transactional outbox (RF-2101): el evento se guarda en la misma transacción que el cambio de
 * negocio y un relay lo publica después. Nunca se publica un evento de un cambio revertido.
 */
@Service
public class OutboxService {

    public static final int VERSION = 1;

    private final JdbcTemplate jdbc;

    public OutboxService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID publicar(String tipo, String agregado, String agregadoId, Object payload) {
        return publicar(tipo, agregado, agregadoId, payload, null);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID publicar(String tipo, String agregado, String agregadoId, Object payload, String causationId) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                        insert into evento_outbox (id, tipo, version, agregado, agregado_id, correlation_id, causation_id,
                                                   payload, estado)
                        values (?, ?, ?, ?, ?, ?, ?, ?::jsonb, 'PENDIENTE')""",
                id, tipo, VERSION, agregado, agregadoId, Contexto.correlationId(), causationId, Json.canonico(payload));
        return id;
    }
}
