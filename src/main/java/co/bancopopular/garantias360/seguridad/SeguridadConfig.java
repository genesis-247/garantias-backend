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
 */
@Configuration
@EnableMethodSecurity
public class SeguridadConfig {

    private static final String[] PUBLICO = {"/actuator/health/**", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html"};

    @Bean
    @Profile("!local & !demo")
    SecurityFilterChain produccion(HttpSecurity http) throws Exception {
        base(http);
        http.oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(new RolesEntraId())));
        return http.build();
    }

    @Bean
    @Profile("local | demo")
    SecurityFilterChain desarrollo(HttpSecurity http) throws Exception {
        base(http);
        http.addFilterBefore(new IdentidadPorCabecera(), AnonymousAuthenticationFilter.class);
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
    CorsConfigurationSource corsConfigurationSource(@Value("${g360.cors.origenes:http://localhost:3000}") String origenes) {
        CorsConfiguration c = new CorsConfiguration();
        c.setAllowedOrigins(Arrays.asList(origenes.split(",")));
        c.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        c.setAllowedHeaders(List.of("*"));
        c.setExposedHeaders(List.of("X-Correlation-ID"));
        UrlBasedCorsConfigurationSource fuente = new UrlBasedCorsConfigurationSource();
        fuente.registerCorsConfiguration("/api/**", c);
        return fuente;
    }

    /** App roles de Entra ID → ROLE_*. */
    static class RolesEntraId implements Converter<Jwt, AbstractAuthenticationToken> {
        @Override
        public AbstractAuthenticationToken convert(Jwt jwt) {
            Collection<String> roles = jwt.getClaimAsStringList("roles");
            var autoridades = (roles == null ? List.<String>of() : roles).stream()
                    .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                    .toList();
            String nombre = jwt.hasClaim("preferred_username") ? jwt.getClaimAsString("preferred_username") : jwt.getSubject();
            return new JwtAuthenticationToken(jwt, autoridades, nombre);
        }
    }

    /** Solo local/demo. */
    static class IdentidadPorCabecera extends OncePerRequestFilter {
        private static final Pattern SEGURO = Pattern.compile("^[A-Za-z0-9._@-]{1,80}$");

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            String usuario = request.getHeader("X-Usuario");
            String roles = request.getHeader("X-Roles");
            if (usuario == null || !SEGURO.matcher(usuario).matches()) {
                usuario = "demo.consultor";
                roles = Roles.CONSULTOR;
            }
            var autoridades = Arrays.stream(roles == null ? new String[0] : roles.split(","))
                    .map(String::trim)
                    .filter(r -> SEGURO.matcher(r).matches())
                    .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                    .toList();
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(usuario, "n/a", autoridades));
            chain.doFilter(request, response);
        }
    }
}
