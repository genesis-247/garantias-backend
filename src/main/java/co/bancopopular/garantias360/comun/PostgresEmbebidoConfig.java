package co.bancopopular.garantias360.comun;

import com.zaxxer.hikari.HikariDataSource;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * PostgreSQL 16 real, embebido en el proceso del backend, para desarrollo local sin Docker ni
 * instalación. Los datos persisten en {@code g360.db.embebida-directorio}. Nunca se activa en
 * ambientes integrados: allí se usa G360_DB_URL contra Azure Database for PostgreSQL.
 */
@Configuration
@ConditionalOnProperty(name = "g360.db.embebida", havingValue = "true")
public class PostgresEmbebidoConfig {

    private static final Logger log = LoggerFactory.getLogger(PostgresEmbebidoConfig.class);
    private static final String BASE = "garantias360";

    @Bean(destroyMethod = "close")
    EmbeddedPostgres postgresEmbebido(@Value("${g360.db.embebida-directorio:${user.home}/.garantias360/postgres}") String directorio,
                                      @Value("${g360.db.embebida-puerto:54329}") int puerto) throws IOException {
        File datos = new File(directorio);
        datos.mkdirs();
        log.info("Iniciando PostgreSQL embebido en el puerto {} con datos en {}", puerto, datos.getAbsolutePath());
        return EmbeddedPostgres.builder()
                .setDataDirectory(datos)
                .setCleanDataDirectory(false)
                .setPort(puerto)
                .start();
    }

    @Bean
    DataSource dataSource(EmbeddedPostgres pg) throws SQLException {
        try (Connection c = pg.getPostgresDatabase().getConnection();
             var existe = c.prepareStatement("select 1 from pg_database where datname = ?")) {
            existe.setString(1, BASE);
            if (!existe.executeQuery().next()) {
                c.createStatement().execute("create database " + BASE);
            }
        }
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(pg.getJdbcUrl("postgres", BASE));
        ds.setUsername("postgres");
        ds.setMaximumPoolSize(10);
        return ds;
    }
}
