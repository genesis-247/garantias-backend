package co.bancopopular.garantias360.seguridad;

import co.bancopopular.garantias360.auditoria.AuditoriaService;
import co.bancopopular.garantias360.comun.Contexto;
import co.bancopopular.garantias360.comun.Errores;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

/**
 * Usuarios y perfiles (M24, RF-2402, RF-2405). Un perfil agrupa roles del catálogo de la sección 4;
 * los roles efectivos de un usuario registrado son los de sus perfiles activos. Toda modificación
 * queda en la auditoría y respeta la segregación de funciones:
 * <ul>
 *   <li>el Auditor no escribe (AUDITOR es incompatible con cualquier rol de escritura);</li>
 *   <li>el Administrador funcional no opera garantías;</li>
 *   <li>nadie modifica su propio usuario.</li>
 * </ul>
 */
@Service
public class UsuarioService {

    private final JdbcTemplate jdbc;
    private final AuditoriaService auditoria;

    public UsuarioService(JdbcTemplate jdbc, AuditoriaService auditoria) {
        this.jdbc = jdbc;
        this.auditoria = auditoria;
    }

    public record Perfil(String codigo, String nombre, String descripcion, List<String> roles, boolean activo,
                         boolean sistema, long usuarios, String actualizadoPor, Instant updatedAt) {
    }

    public record Usuario(String usuario, String nombre, String cargo, String correo, String area, List<String> perfiles,
                          boolean activo, Set<String> roles, String actualizadoPor, Instant updatedAt) {
    }

    public record PerfilSolicitud(@NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]{2,49}$") String codigo,
                                  @NotBlank String nombre, String descripcion, List<String> roles, Boolean activo) {
    }

    public record UsuarioSolicitud(@NotBlank @Pattern(regexp = "^[A-Za-z0-9._@-]{3,80}$") String usuario,
                                   @NotBlank String nombre, String cargo, String correo, String area,
                                   List<String> perfiles, Boolean activo) {
    }

    /** Roles efectivos: vacío si el usuario no está registrado (se usan los del token o la cabecera). */
    public record Resolucion(boolean registrado, boolean activo, Set<String> roles) {
    }

    // ------------------------------------------------------------------ resolución de roles

    public Resolucion resolver(String usuario) {
        List<Resolucion> r = jdbc.query("""
                        select u.activo,
                               coalesce((select array_agg(distinct rol) from perfil p, unnest(p.roles) rol
                                         where p.codigo = any(u.perfiles) and p.activo), '{}') as roles
                        from usuario u where u.usuario = ?""",
                (rs, i) -> new Resolucion(true, rs.getBoolean("activo"), new TreeSet<>(texto(rs.getArray("roles")))), usuario);
        return r.isEmpty() ? new Resolucion(false, true, Set.of()) : r.getFirst();
    }

    // ------------------------------------------------------------------ perfiles

    @Transactional(readOnly = true)
    public List<Perfil> perfiles() {
        return jdbc.query("""
                select p.*, (select count(*) from usuario u where p.codigo = any(u.perfiles)) as usuarios
                from perfil p order by p.sistema desc, p.nombre""", (rs, i) -> perfil(rs));
    }

    @Transactional
    public Perfil guardarPerfil(PerfilSolicitud s, boolean nuevo) {
        List<String> roles = normalizarRoles(s.roles());
        validarRoles(roles, "El perfil " + s.codigo());
        Optional<Perfil> actual = perfil(s.codigo());
        if (nuevo && actual.isPresent()) {
            throw Errores.conflicto("PERFIL_EXISTE", "Ya existe el perfil " + s.codigo());
        }
        if (!nuevo && actual.isEmpty()) {
            throw Errores.noEncontrado("El perfil " + s.codigo());
        }
        boolean activo = s.activo() == null || s.activo();
        String usuario = Contexto.usuario();
        if (nuevo) {
            jdbc.update("insert into perfil (codigo, nombre, descripcion, roles, activo, creado_por, actualizado_por) values (?, ?, ?, ?, ?, ?, ?)",
                    s.codigo(), s.nombre(), s.descripcion(), roles.toArray(String[]::new), activo, usuario, usuario);
        } else {
            // Cambiar roles de un perfil en uso cambia los permisos de todos sus usuarios: se valida cada uno.
            if (!actual.get().roles().equals(roles)) {
                jdbc.queryForList("select usuario from usuario where ? = any(perfiles)", String.class, s.codigo())
                        .forEach(u -> validarCombinacion(u, reemplazar(u, s.codigo(), roles)));
            }
            jdbc.update("""
                            update perfil set nombre = ?, descripcion = ?, roles = ?, activo = ?, actualizado_por = ?,
                                              updated_at = now(), version = version + 1
                            where codigo = ?""",
                    s.nombre(), s.descripcion(), roles.toArray(String[]::new), activo, usuario, s.codigo());
        }
        Perfil despues = perfil(s.codigo()).orElseThrow();
        auditoria.registrar(nuevo ? "CREAR_PERFIL" : "EDITAR_PERFIL", "Perfil", s.codigo(),
                actual.map(UsuarioService::mapa).orElse(null), mapa(despues), null);
        return despues;
    }

    private Optional<Perfil> perfil(String codigo) {
        return jdbc.query("""
                select p.*, (select count(*) from usuario u where p.codigo = any(u.perfiles)) as usuarios
                from perfil p where p.codigo = ?""", (rs, i) -> perfil(rs), codigo).stream().findFirst();
    }

    // ------------------------------------------------------------------ usuarios

    @Transactional(readOnly = true)
    public List<Usuario> usuarios() {
        return jdbc.query("select * from usuario order by activo desc, nombre", (rs, i) -> usuario(rs));
    }

    @Transactional(readOnly = true)
    public Optional<Usuario> usuario(String usuario) {
        return jdbc.query("select * from usuario where usuario = ?", (rs, i) -> usuario(rs), usuario).stream().findFirst();
    }

    @Transactional
    public Usuario guardarUsuario(UsuarioSolicitud s, boolean nuevo) {
        if (s.usuario().equalsIgnoreCase(Contexto.usuario())) {
            throw Errores.prohibido("AUTOGESTION", "Nadie puede modificar sus propios perfiles o su estado (segregación de funciones)");
        }
        List<String> perfiles = new ArrayList<>(new TreeSet<>(Objects.requireNonNullElse(s.perfiles(), List.of())));
        Set<String> existentes = new HashSet<>(jdbc.queryForList("select codigo from perfil", String.class));
        List<String> desconocidos = perfiles.stream().filter(p -> !existentes.contains(p)).toList();
        if (!desconocidos.isEmpty()) {
            throw Errores.invalido("PERFIL_DESCONOCIDO", "Hay perfiles que no existen", desconocidos);
        }
        validarCombinacion(s.usuario(), rolesDe(perfiles));
        Optional<Usuario> actual = usuario(s.usuario());
        if (nuevo && actual.isPresent()) {
            throw Errores.conflicto("USUARIO_EXISTE", "Ya existe el usuario " + s.usuario());
        }
        if (!nuevo && actual.isEmpty()) {
            throw Errores.noEncontrado("El usuario " + s.usuario());
        }
        boolean activo = s.activo() == null || s.activo();
        String quien = Contexto.usuario();
        if (nuevo) {
            jdbc.update("""
                            insert into usuario (usuario, nombre, cargo, correo, area, perfiles, activo, creado_por, actualizado_por)
                            values (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    s.usuario(), s.nombre(), s.cargo(), s.correo(), s.area(), perfiles.toArray(String[]::new), activo, quien, quien);
        } else {
            jdbc.update("""
                            update usuario set nombre = ?, cargo = ?, correo = ?, area = ?, perfiles = ?, activo = ?,
                                               actualizado_por = ?, updated_at = now(), version = version + 1
                            where usuario = ?""",
                    s.nombre(), s.cargo(), s.correo(), s.area(), perfiles.toArray(String[]::new), activo, quien, s.usuario());
        }
        Usuario despues = usuario(s.usuario()).orElseThrow();
        String accion = nuevo ? "CREAR_USUARIO" : actual.get().activo() != despues.activo()
                ? (despues.activo() ? "ACTIVAR_USUARIO" : "INACTIVAR_USUARIO") : "EDITAR_USUARIO";
        auditoria.registrar(accion, "Usuario", s.usuario(), actual.map(UsuarioService::mapa).orElse(null), mapa(despues), null);
        return despues;
    }

    /** Alta idempotente para la carga inicial de ambientes de demostración (sin auditoría de usuario humano). */
    @Transactional
    public void asegurar(String usuario, String nombre, String cargo, String area, List<String> perfiles) {
        jdbc.update("""
                insert into usuario (usuario, nombre, cargo, area, perfiles, creado_por) values (?, ?, ?, ?, ?, 'sistema')
                on conflict (usuario) do nothing""", usuario, nombre, cargo, area, perfiles.toArray(String[]::new));
    }

    // ------------------------------------------------------------------ segregación de funciones

    private void validarRoles(List<String> roles, String quien) {
        List<String> desconocidos = roles.stream().filter(r -> !Roles.CATALOGO.containsKey(r)).toList();
        if (!desconocidos.isEmpty()) {
            throw Errores.invalido("ROL_DESCONOCIDO", "Hay roles que no existen en el catálogo", desconocidos);
        }
        if (roles.isEmpty()) {
            throw Errores.invalido("SIN_ROLES", quien + " debe tener al menos un rol", List.of());
        }
        List<String> conflictos = conflictos(new HashSet<>(roles));
        if (!conflictos.isEmpty()) {
            throw Errores.invalido("SEGREGACION_FUNCIONES", quien + " combina roles incompatibles", conflictos);
        }
    }

    private void validarCombinacion(String usuario, Set<String> roles) {
        List<String> conflictos = conflictos(roles);
        if (!conflictos.isEmpty()) {
            throw Errores.invalido("SEGREGACION_FUNCIONES", "El usuario " + usuario + " quedaría con roles incompatibles", conflictos);
        }
    }

    static List<String> conflictos(Set<String> roles) {
        List<String> c = new ArrayList<>();
        if (roles.contains(Roles.AUDITOR)) {
            roles.stream().filter(Roles.ESCRITURA::contains).sorted()
                    .forEach(r -> c.add("AUDITOR y " + r + ": el Auditor no escribe"));
        }
        for (String admin : List.of(Roles.ADMIN_FUNCIONAL, Roles.ADMIN_SEGURIDAD)) {
            if (roles.contains(admin)) {
                roles.stream().filter(Roles.OPERACION::contains).sorted()
                        .forEach(r -> c.add(admin + " y " + r + ": la administración no opera garantías"));
            }
        }
        if (roles.contains(Roles.RIESGOS_GESTOR) && roles.contains(Roles.APROBADOR_REGLAS)) {
            c.add("RIESGOS_GESTOR y APROBADOR_REGLAS: quien propone reglas no las aprueba");
        }
        return c;
    }

    private Set<String> rolesDe(List<String> perfiles) {
        if (perfiles.isEmpty()) {
            return Set.of();
        }
        return new TreeSet<>(jdbc.queryForList("select distinct unnest(roles) from perfil where codigo = any(?) and activo",
                String.class, (Object) perfiles.toArray(String[]::new)));
    }

    /** Roles que tendría el usuario si el perfil indicado pasara a tener los roles dados. */
    private Set<String> reemplazar(String usuario, String perfil, List<String> roles) {
        List<String> perfiles = usuario(usuario).map(Usuario::perfiles).orElse(List.of());
        Set<String> r = new TreeSet<>(rolesDe(perfiles.stream().filter(p -> !p.equals(perfil)).toList()));
        r.addAll(roles);
        return r;
    }

    private static List<String> normalizarRoles(List<String> roles) {
        return new ArrayList<>(new TreeSet<>(Objects.requireNonNullElse(roles, List.of())));
    }

    // ------------------------------------------------------------------ mapeo

    private Perfil perfil(ResultSet rs) throws SQLException {
        return new Perfil(rs.getString("codigo"), rs.getString("nombre"), rs.getString("descripcion"),
                texto(rs.getArray("roles")), rs.getBoolean("activo"), rs.getBoolean("sistema"), rs.getLong("usuarios"),
                rs.getString("actualizado_por"), rs.getTimestamp("updated_at").toInstant());
    }

    private Usuario usuario(ResultSet rs) throws SQLException {
        List<String> perfiles = texto(rs.getArray("perfiles"));
        return new Usuario(rs.getString("usuario"), rs.getString("nombre"), rs.getString("cargo"), rs.getString("correo"),
                rs.getString("area"), perfiles, rs.getBoolean("activo"), rolesDe(perfiles), rs.getString("actualizado_por"),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static List<String> texto(Array a) throws SQLException {
        return a == null ? List.of() : Arrays.asList((String[]) a.getArray());
    }

    private static Map<String, Object> mapa(Perfil p) {
        return Map.of("codigo", p.codigo(), "nombre", p.nombre(), "roles", p.roles(), "activo", p.activo());
    }

    private static Map<String, Object> mapa(Usuario u) {
        return Map.of("usuario", u.usuario(), "nombre", u.nombre(), "perfiles", u.perfiles(), "activo", u.activo(),
                "roles", u.roles());
    }
}
