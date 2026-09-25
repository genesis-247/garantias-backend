package co.bancopopular.garantias360.comun;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI garantias360() {
        return new OpenAPI().info(new Info()
                .title("Garantías 360 — API")
                .version("v1")
                .description("Maestro de garantías de Banco Popular: registro, ciclo de vida, cobertura explicable, "
                        + "reglas con maker–checker, auditoría y eventos. Errores en formato RFC 9457 con Correlation ID."));
    }
}
