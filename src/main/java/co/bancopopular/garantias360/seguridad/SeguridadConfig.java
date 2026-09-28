package co.bancopopular.garantias360.seguridad;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Seguridad (RF-2401, RF-2402). Producción: OIDC/OAuth 2.0 con Entra ID (JWT, app roles en el claim
 * "roles"). Perfiles local/demo: identidad por cabeceras X-Usuario / X-Roles para poder demostrar
 * maker–checker sin un tenant de Entra. Ese filtro NO existe fuera de esos perfiles.
 * <p>
 * En ambos casos, si el usuario está registrado en la administración de usuarios (M24), sus roles
 * efectivos son los de sus perfiles activos y un usuario inactivo no entra. Si no está registrado,
 * se usan los roles del token (o de la cabecera en local/demo).
 */
@Configuration
@EnableMethodSecurity
public class SeguridadConfig {

    // /error: sin él, cualquier error no manejado se reenvía allí y termina como un 403 sin detalle.
    private static final String[] PUBLICO = {"/actuator/health/**", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", "/error"};

    @Bean
    @Profile("!local & !demo")
    SecurityFilterChain produccion(HttpSecurity http, UsuarioService usuarios) throws Exception {
        base(http);
        http.oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(new RolesEntraId(usuarios))));
        return http.build();
    }

    @Bean
    @Profile("local | demo")
    SecurityFilterChain desarrollo(HttpSecurity http, UsuarioService usuarios) throws Exception {
        base(http);
        http.addFilterBefore(new IdentidadPorCabecera(usuarios), AnonymousAuthenticationFilter.class);
        return http.build();
    }

    private void base(HttpSecurity http) throws Exception {
        http.csrf(c -> c.disable())
                .cors(c -> {
                })
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers(PUBLICO).permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().denyAll());
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(@Value("${g360.cors.origenes:http://localhost:[*]}") String origenes) {
        CorsConfiguration c = new CorsConfiguration();
        // Admite patrones (p. ej. http://localhost:[*] en desarrollo); en ambientes integrados G360_CORS fija el origen exacto.
        c.setAllowedOriginPatterns(Arrays.asList(origenes.split(",")));
        c.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        c.setAllowedHeaders(List.of("*"));
        c.setExposedHeaders(List.of("X-Correlation-ID"));
        UrlBasedCorsConfigurationSource fuente = new UrlBasedCorsConfigurationSource();
        fuente.registerCorsConfiguration("/api/**", c);
        return fuente;
    }

    /** Roles efectivos: los de sus perfiles si el usuario está registrado; si no, los recibidos. */
    static Collection<String> efectivos(UsuarioService usuarios, String usuario, Collection<String> recibidos) {
        UsuarioService.Resolucion r = usuarios.resolver(usuario);
        if (!r.registrado()) {
            return recibidos;
        }
        if (!r.activo()) {
            throw new DisabledException("El usuario " + usuario + " está inactivo");
        }
        return r.roles();
    }

    private static List<SimpleGrantedAuthority> autoridades(Collection<String> roles) {
        return roles.stream().map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList();
    }

    /** App roles de Entra ID → ROLE_*. */
    static class RolesEntraId implements Converter<Jwt, AbstractAuthenticationToken> {
        private final UsuarioService usuarios;

        RolesEntraId(UsuarioService usuarios) {
            this.usuarios = usuarios;
        }

        @Override
        public AbstractAuthenticationToken convert(Jwt jwt) {
            Collection<String> roles = jwt.getClaimAsStringList("roles");
            String nombre = jwt.hasClaim("preferred_username") ? jwt.getClaimAsString("preferred_username") : jwt.getSubject();
            return new JwtAuthenticationToken(jwt, autoridades(efectivos(usuarios, nombre, roles == null ? List.of() : roles)), nombre);
        }
    }

    /** Solo local/demo. */
    static class IdentidadPorCabecera extends OncePerRequestFilter {
        private static final Pattern SEGURO = Pattern.compile("^[A-Za-z0-9._@-]{1,80}$");
        private final UsuarioService usuarios;

        IdentidadPorCabecera(UsuarioService usuarios) {
            this.usuarios = usuarios;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            String usuario = request.getHeader("X-Usuario");
            String roles = request.getHeader("X-Roles");
            if (usuario == null || !SEGURO.matcher(usuario).matches()) {
                usuario = "demo.consultor";
                roles = Roles.CONSULTOR;
            }
            List<String> recibidos = Arrays.stream(roles == null ? new String[0] : roles.split(","))
                    .map(String::trim)
                    .filter(r -> SEGURO.matcher(r).matches())
                    .toList();
            Collection<String> efectivos;
            try {
                efectivos = efectivos(usuarios, usuario, recibidos);
            } catch (DisabledException e) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType("application/problem+json;charset=UTF-8");
                response.getWriter().write("{\"status\":403,\"codigo\":\"USUARIO_INACTIVO\",\"detail\":\"El usuario está inactivo\",\"detalles\":[]}");
                return;
            }
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(usuario, "n/a", autoridades(efectivos)));
            chain.doFilter(request, response);
        }
    }
}
