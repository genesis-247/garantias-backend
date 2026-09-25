package co.bancopopular.garantias360.auditoria;

import co.bancopopular.garantias360.comun.Contexto;
import co.bancopopular.garantias360.comun.Json;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Auditoría inmutable encadenada por hash (RF-1501, RF-1502). Cada registro incluye el SHA-256 del
 * anterior; la fila única de auditoria_cabeza se bloquea al insertar para serializar la cadena, y un
 * trigger en la base de datos impide UPDATE y DELETE.
 */
@Service
public class AuditoriaService {

    private final JdbcTemplate jdbc;

    public AuditoriaService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Registro(long id, Instant ocurridoEn, String usuario, String accion, String entidad, String entidadId,
                           JsonNode antes, JsonNode despues, String motivo, String origen, String correlationId,
                           String hashAnterior, String hash) {
    }

    public record Verificacion(boolean integra, long registrosVerificados, Long primerRegistroAlterado, String detalle) {
    }

    /** Se une a la transacción del cambio auditado: si el cambio se revierte, su auditoría también. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void registrar(String accion, String entidad, String entidadId, Object antes, Object despues, String motivo) {
        registrar(accion, entidad, entidadId, antes, despues, motivo, "API");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void registrar(String accion, String entidad, String entidadId, Object antes, Object despues, String motivo,
                          String origen) {
        String anterior = jdbc.queryForObject("select hash from auditoria_cabeza where id = 1 for update", String.class);
        Instant ahora = Instant.now().truncatedTo(ChronoUnit.MICROS);
        String usuario = Contexto.usuario();
        String correlation = Contexto.correlationId();
        String antesJson = antes == null ? null : Json.canonico(antes);
        String despuesJson = despues == null ? null : Json.canonico(despues);
        String hash = calcularHash(anterior, ahora, usuario, accion, entidad, entidadId, antesJson, despuesJson, motivo,
                origen, correlation);
        jdbc.update("""
                        insert into auditoria (ocurrido_en, usuario, accion, entidad, entidad_id, antes, despues, motivo,
                                               origen, correlation_id, hash_anterior, hash)
                        values (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?)""",
                Timestamp.from(ahora), usuario, accion, entidad, entidadId, antesJson, despuesJson, motivo, origen,
                correlation, anterior, hash);
        jdbc.update("update auditoria_cabeza set hash = ? where id = 1", hash);
    }

    @Transactional(readOnly = true)
    public List<Registro> consultar(String entidad, String entidadId, String correlationId, String usuario, int limite) {
        StringBuilder sql = new StringBuilder("select * from auditoria where 1 = 1");
        List<Object> args = new ArrayList<>();
        if (entidad != null) {
            sql.append(" and entidad = ?");
            args.add(entidad);
        }
        if (entidadId != null) {
            sql.append(" and entidad_id = ?");
            args.add(entidadId);
        }
        if (correlationId != null) {
            sql.append(" and correlation_id = ?");
            args.add(correlationId);
        }
        if (usuario != null) {
            sql.append(" and usuario = ?");
            args.add(usuario);
        }
        sql.append(" order by id desc limit ?");
        args.add(Math.min(Math.max(limite, 1), 500));
        return jdbc.query(sql.toString(), (rs, i) -> new Registro(rs.getLong("id"),
                rs.getTimestamp("ocurrido_en").toInstant(), rs.getString("usuario"), rs.getString("accion"),
                rs.getString("entidad"), rs.getString("entidad_id"), leer(rs.getString("antes")),
                leer(rs.getString("despues")), rs.getString("motivo"), rs.getString("origen"),
                rs.getString("correlation_id"), rs.getString("hash_anterior"), rs.getString("hash")), args.toArray());
    }

    /** Recalcula toda la cadena y la compara con lo almacenado (RF-1905). */
    @Transactional(readOnly = true)
    public Verificacion verificar() {
        final String[] esperadoAnterior = {"GENESIS"};
        final long[] contados = {0};
        final Long[] alterado = {null};
        final String[] detalle = {null};
        jdbc.query("select * from auditoria order by id", rs -> {
            if (alterado[0] != null) {
                return;
            }
            contados[0]++;
            long id = rs.getLong("id");
            String anterior = rs.getString("hash_anterior");
            String recalculado = calcularHash(anterior, rs.getTimestamp("ocurrido_en").toInstant(), rs.getString("usuario"),
                    rs.getString("accion"), rs.getString("entidad"), rs.getString("entidad_id"),
                    normalizar(rs.getString("antes")), normalizar(rs.getString("despues")), rs.getString("motivo"),
                    rs.getString("origen"), rs.getString("correlation_id"));
            if (!anterior.equals(esperadoAnterior[0])) {
                alterado[0] = id;
                detalle[0] = "El enlace con el registro anterior está roto";
            } else if (!recalculado.equals(rs.getString("hash"))) {
                alterado[0] = id;
                detalle[0] = "El contenido no coincide con su hash";
            }
            esperadoAnterior[0] = rs.getString("hash");
        });
        String cabeza = jdbc.queryForObject("select hash from auditoria_cabeza where id = 1", String.class);
        if (alterado[0] == null && !Objects.equals(cabeza, esperadoAnterior[0])) {
            detalle[0] = "La cabeza de la cadena no coincide con el último registro";
            return new Verificacion(false, contados[0], null, detalle[0]);
        }
        return new Verificacion(alterado[0] == null, contados[0], alterado[0],
                alterado[0] == null ? "Cadena íntegra" : detalle[0]);
    }

    private static String calcularHash(String anterior, Instant ocurridoEn, String usuario, String accion, String entidad,
                                       String entidadId, String antes, String despues, String motivo, String origen,
                                       String correlation) {
        Map<String, Object> contenido = new TreeMap<>();
        contenido.put("anterior", anterior);
        contenido.put("ocurridoEn", ocurridoEn.truncatedTo(ChronoUnit.MICROS).toString());
        contenido.put("usuario", usuario);
        contenido.put("accion", accion);
        contenido.put("entidad", entidad);
        contenido.put("entidadId", entidadId);
        contenido.put("antes", antes);
        contenido.put("despues", despues);
        contenido.put("motivo", motivo);
        contenido.put("origen", origen);
        contenido.put("correlationId", correlation);
        return Json.sha256(Json.canonico(contenido));
    }

    /** jsonb reordena claves y espacios: se normaliza a JSON canónico antes de verificar. */
    private static String normalizar(String json) {
        if (json == null) {
            return null;
        }
        return Json.canonico(leer(json));
    }

    private static JsonNode leer(String json) {
        if (json == null) {
            return null;
        }
        try {
            return Json.CANONICO.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
