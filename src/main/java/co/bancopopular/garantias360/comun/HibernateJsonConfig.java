package co.bancopopular.garantias360.comun;

import org.hibernate.cfg.AvailableSettings;
import org.hibernate.type.format.jackson.JacksonJsonFormatMapper;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Las columnas jsonb se leen y escriben con el mapeador canónico (decimales exactos, RF-0804). */
@Configuration
public class HibernateJsonConfig {

    @Bean
    HibernatePropertiesCustomizer mapeadorJsonCanonico() {
        return props -> props.put(AvailableSettings.JSON_FORMAT_MAPPER, new JacksonJsonFormatMapper(Json.CANONICO));
    }
}
