package co.bancopopular.garantias360.api;

import co.bancopopular.garantias360.seguridad.Roles;
import co.bancopopular.garantias360.seguridad.UsuarioService;
import co.bancopopular.garantias360.seguridad.UsuarioService.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/seguridad")
@Tag(name = "Seguridad y administración", description = "Usuarios, perfiles y catálogo de roles (M24)")
public class SeguridadController {

    private static final String CONSULTA = "hasAnyRole('ADMIN_SEGURIDAD','AUDITOR')";

    private final UsuarioService usuarios;

    public SeguridadController(UsuarioService usuarios) {
        this.usuarios = usuarios;
    }

    @GetMapping("/roles")
    @PreAuthorize(Roles.LECTURA)
    @Operation(summary = "Catálogo de roles (área × nivel, sección 4)")
    public Map<String, String> roles() {
        return Roles.CATALOGO;
    }

    @GetMapping("/perfiles")
    @PreAuthorize(CONSULTA)
    public List<Perfil> perfiles() {
        return usuarios.perfiles();
    }

    @PostMapping("/perfiles")
    @PreAuthorize(Roles.ADMINISTRA_SEGURIDAD)
    @ResponseStatus(HttpStatus.CREATED)
    public Perfil crearPerfil(@Valid @RequestBody PerfilSolicitud s) {
        return usuarios.guardarPerfil(s, true);
    }

    @PutMapping("/perfiles/{codigo}")
    @PreAuthorize(Roles.ADMINISTRA_SEGURIDAD)
    public Perfil editarPerfil(@PathVariable String codigo, @RequestBody PerfilSolicitud s) {
        return usuarios.guardarPerfil(new PerfilSolicitud(codigo, s.nombre(), s.descripcion(), s.roles(), s.activo()), false);
    }

    @GetMapping("/usuarios")
    @PreAuthorize(CONSULTA)
    public List<Usuario> usuarios() {
        return usuarios.usuarios();
    }

    @PostMapping("/usuarios")
    @PreAuthorize(Roles.ADMINISTRA_SEGURIDAD)
    @ResponseStatus(HttpStatus.CREATED)
    public Usuario crearUsuario(@Valid @RequestBody UsuarioSolicitud s) {
        return usuarios.guardarUsuario(s, true);
    }

    @PutMapping("/usuarios/{usuario}")
    @PreAuthorize(Roles.ADMINISTRA_SEGURIDAD)
    public Usuario editarUsuario(@PathVariable String usuario, @RequestBody UsuarioSolicitud s) {
        return usuarios.guardarUsuario(new UsuarioSolicitud(usuario, s.nombre(), s.cargo(), s.correo(), s.area(),
                s.perfiles(), s.activo()), false);
    }
}
