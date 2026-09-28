package co.bancopopular.garantias360.comun;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.CoreErrorCode;
import org.flywaydb.core.api.output.ValidateOutput;
import org.flywaydb.core.api.output.ValidateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.util.Map;

/**
 * Migraciones con una única excepción controlada: en el incremento 2 se corrigió un comentario de
 * V1 (sin cambios de esquema), lo que cambia su checksum. Solo si la base tiene exactamente el
 * checksum anterior conocido de esa versión se realinea el historial; cualquier otra diferencia
 * sigue deteniendo el arranque, como exige Flyway.
 */
@Configuration
@Profile("!test")
public class FlywayConfig {

    private static final Logger log = LoggerFactory.getLogger(FlywayConfig.class);

    /** Versión → checksum anterior aceptado (cambios solo de comentarios). */
    private static final Map<String, Integer> CHECKSUMS_ANTERIORES = Map.of("1", 1636046046);

    @Bean
    FlywayMigrationStrategy migrarConCorreccionDeComentarios() {
        return flyway -> {
            ValidateResult r = flyway.validateWithResult();
            if (!r.validationSuccessful && soloComentariosConocidos(flyway, r)) {
                log.warn("Realineando el checksum de migraciones con cambios solo de comentarios: {}",
                        r.invalidMigrations.stream().filter(FlywayConfig::esChecksum).map(m -> m.version).toList());
                flyway.repair();
            }
            flyway.migrate();
        };
    }

    private static boolean esChecksum(ValidateOutput m) {
        return m.errorDetails != null && m.errorDetails.errorCode == CoreErrorCode.CHECKSUM_MISMATCH;
    }

    private static boolean soloComentariosConocidos(Flyway flyway, ValidateResult r) {
        // Las migraciones pendientes también se informan como inválidas; aquí solo importan los checksums.
        var checksums = r.invalidMigrations.stream().filter(FlywayConfig::esChecksum).toList();
        if (checksums.isEmpty()) {
            return false;
        }
        for (ValidateOutput m : checksums) {
            Integer anterior = CHECKSUMS_ANTERIORES.get(m.version);
            if (anterior == null) {
                return false;
            }
            boolean coincide = java.util.Arrays.stream(flyway.info().applied())
                    .anyMatch(i -> i.getVersion() != null && m.version.equals(i.getVersion().getVersion())
                            && anterior.equals(i.getChecksum()));
            if (!coincide) {
                return false;
            }
        }
        return true;
    }
}
