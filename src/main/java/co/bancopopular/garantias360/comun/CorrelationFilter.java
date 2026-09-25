package co.bancopopular.garantias360.comun;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/** Propaga o genera el Correlation ID de cada petición (RF-0903). */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationFilter extends OncePerRequestFilter {

    private static final Pattern VALIDO = Pattern.compile("^[A-Za-z0-9._:-]{1,100}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String recibido = request.getHeader(Contexto.CORRELATION_HEADER);
        String id = recibido != null && VALIDO.matcher(recibido).matches() ? recibido : UUID.randomUUID().toString();
        MDC.put(Contexto.CORRELATION_MDC, id);
        response.setHeader(Contexto.CORRELATION_HEADER, id);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(Contexto.CORRELATION_MDC);
        }
    }
}
