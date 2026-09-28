package co.bancopopular.garantias360.demo;

import co.bancopopular.garantias360.seguridad.UsuarioService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Usuarios SINTÉTICOS de demostración, uno por perfil (sección 4). Solo en el perfil "demo" y solo
 * si no hay usuarios registrados. Sus roles efectivos salen de sus perfiles, así que lo que cambie la
 * administración de seguridad (M24) se refleja de inmediato en el selector del encabezado.
 */
@Component
@Profile("demo")
@Order(0)
public class UsuariosDemo implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(UsuariosDemo.class);

    private final UsuarioService usuarios;
    private final JdbcTemplate jdbc;

    public UsuariosDemo(UsuarioService usuarios, JdbcTemplate jdbc) {
        this.usuarios = usuarios;
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        Long n = jdbc.queryForObject("select count(*) from usuario", Long.class);
        if (n != null && n > 0) {
            return;
        }
        usuarios.asegurar("operaciones.pedro", "Pedro Ramírez", "Gestor de operaciones", "Operaciones", List.of("OPERACIONES_GESTOR"));
        usuarios.asegurar("operaciones.directora", "Marcela Duarte", "Directora de operaciones", "Operaciones", List.of("OPERACIONES_DIRECTOR"));
        usuarios.asegurar("juridica.laura", "Laura Montoya", "Abogada de garantías", "Jurídica", List.of("JURIDICA_GESTOR"));
        usuarios.asegurar("juridica.directora", "Catalina Rueda", "Directora jurídica", "Jurídica", List.of("JURIDICA_DIRECTOR"));
        usuarios.asegurar("riesgos.ana", "Ana Beltrán", "Analista de riesgo de crédito", "Riesgos", List.of("RIESGOS_GESTOR"));
        usuarios.asegurar("aprobador.juan", "Juan Camilo Rey", "Director de riesgos (aprobador de reglas)", "Riesgos", List.of("APROBADOR_REGLAS"));
        usuarios.asegurar("cumplimiento.jorge", "Jorge Iván Salas", "Oficial de cumplimiento", "Cumplimiento", List.of("CUMPLIMIENTO"));
        usuarios.asegurar("auditoria.sofia", "Sofía Herrera", "Auditora interna", "Auditoría", List.of("AUDITOR"));
        usuarios.asegurar("admin.funcional", "Diego Parra", "Administrador funcional", "Tecnología", List.of("ADMIN_FUNCIONAL", "INTEGRACION"));
        usuarios.asegurar("admin.aprobadora", "Natalia Guzmán", "Administradora funcional (aprobadora)", "Tecnología", List.of("ADMIN_FUNCIONAL"));
        usuarios.asegurar("seguridad.andres", "Andrés Castaño", "Administrador de seguridad", "Seguridad de la información", List.of("ADMIN_SEGURIDAD"));
        usuarios.asegurar("consulta.comercial", "Valentina Ortiz", "Gerente comercial", "Comercial", List.of("CONSULTOR"));
        log.info("Usuarios de demostración creados");
    }
}
