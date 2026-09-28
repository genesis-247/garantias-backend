package co.bancopopular.garantias360.api;

import co.bancopopular.garantias360.seguridad.UsuarioService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Selector de identidad del encabezado, solo en local/demo (identidad por cabeceras). Lista los
 * usuarios activos con sus roles efectivos para que la administración de perfiles se refleje al
 * instante. En producción no existe: la identidad viene de Entra ID.
 */
@RestController
@RequestMapping("/api/v1/sesion")
@Profile("local | demo")
@Tag(name = "Consultas y gobierno")
public class SesionDemoController {

    private final UsuarioService usuarios;

    public SesionDemoController(UsuarioService usuarios) {
        this.usuarios = usuarios;
    }

    @GetMapping("/usuarios-demo")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Usuarios activos para el selector de perfil (solo local/demo)")
    public List<Map<String, Object>> usuariosDemo() {
        return usuarios.usuarios().stream().filter(UsuarioService.Usuario::activo)
                .map(u -> Map.<String, Object>of("usuario", u.usuario(), "nombre", u.nombre(),
                        "cargo", u.cargo() == null ? "" : u.cargo(), "roles", u.roles()))
                .toList();
    }
}
