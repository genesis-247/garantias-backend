package co.bancopopular.garantias360.eventos;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/** Core transaccional (M09): eventos recibidos y publicados, linaje y línea de tiempo por Correlation ID. */
@Service
public class EventosConsulta {

    private final JdbcTemplate jdbc;

    public EventosConsulta(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> resumen() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("publicados", jdbc.queryForList("select estado, count(*) as total from evento_outbox group by estado"));
        r.put("recibidos", jdbc.queryForList("select estado, count(*) as total from evento_inbox group by estado"));
        r.put("porTipo", jdbc.queryForList("select tipo, count(*) as total from evento_outbox group by tipo order by 2 desc"));
        return r;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> eventos(String direccion, String tipo, String estado, String texto, int limite) {
        String sql = """
                select * from (
                  select 'PUBLICADO' as direccion, id::text as id, tipo, version, agregado as entidad, agregado_id as entidad_id,
                         correlation_id, causation_id, 'GARANTIAS_360' as origen, estado, intentos, error, creado_en as fecha,
                         publicado_en as procesado_en, payload
                  from evento_outbox
                  union all
                  select 'RECIBIDO', evento_id, tipo, 1, 'Obligacion', payload ->> 'numeroObligacion', correlation_id, null, origen,
                         estado, 1, error, recibido_en, procesado_en, payload
                  from evento_inbox
                ) e where (?::text is null or direccion = ?) and (?::text is null or tipo = ?) and (?::text is null or estado = ?)
                  and (?::text is null or entidad_id ilike '%' || ? || '%' or correlation_id = ? or id = ?)
                order by fecha desc limit ?""";
        return jdbc.queryForList(sql, direccion, direccion, tipo, tipo, estado, estado, texto, texto, texto, texto,
                Math.min(Math.max(limite, 1), 500));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> linaje(String eventoId) {
        return jdbc.queryForList("""
                select l.entidad, l.entidad_id, l.accion, l.creado_en from linaje_evento l join evento_inbox i on i.id = l.inbox_id
                where i.evento_id = ? order by l.id""", eventoId);
    }

    /** Todo lo ocurrido con un Correlation ID: auditoría, eventos, cálculos (RF-0903). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> lineaTiempo(String correlationId) {
        return jdbc.queryForList("""
                select * from (
                  select ocurrido_en as fecha, 'AUDITORIA' as fuente, accion as tipo, entidad || ' ' || entidad_id as detalle, usuario as actor
                  from auditoria where correlation_id = ?
                  union all
                  select creado_en, 'EVENTO_PUBLICADO', tipo, agregado || ' ' || agregado_id || ' (' || estado || ')', 'Garantías 360'
                  from evento_outbox where correlation_id = ?
                  union all
                  select recibido_en, 'EVENTO_RECIBIDO', tipo, coalesce(payload ->> 'numeroObligacion', '') || ' (' || estado || ')', origen
                  from evento_inbox where correlation_id = ?
                  union all
                  select fecha_corte, 'CALCULO_COBERTURA', estado, disparador || ' · ' || cardinality(obligaciones) || ' obligaciones', creado_por
                  from calculo_cobertura where correlation_id = ?
                ) t order by fecha""", correlationId, correlationId, correlationId, correlationId);
    }
}
