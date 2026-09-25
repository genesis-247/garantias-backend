package co.bancopopular.garantias360.comun;

import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Acceso al usuario y al Correlation ID de la operación en curso. */
public final class Contexto {

    public static final String CORRELATION_HEADER = "X-Correlation-ID";
    public static final String CORRELATION_MDC = "correlationId";
    public static final String SISTEMA = "sistema";

    private Contexto() {
    }

    public static String usuario() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null || !auth.isAuthenticated() ? SISTEMA : auth.getName();
    }

    public static Set<String> roles() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return Set.of();
        }
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .map(a -> a.startsWith("ROLE_") ? a.substring(5) : a)
                .collect(Collectors.toSet());
    }

    public static String correlationId() {
        String id = MDC.get(CORRELATION_MDC);
        if (id == null) {
            id = UUID.randomUUID().toString();
            MDC.put(CORRELATION_MDC, id);
        }
        return id;
    }

    /** Ejecuta una tarea de fondo con su propio Correlation ID. */
    public static void conCorrelation(String id, Runnable tarea) {
        String anterior = MDC.get(CORRELATION_MDC);
        MDC.put(CORRELATION_MDC, id);
        try {
            tarea.run();
        } finally {
            if (anterior == null) {
                MDC.remove(CORRELATION_MDC);
            } else {
                MDC.put(CORRELATION_MDC, anterior);
            }
        }
    }
}
