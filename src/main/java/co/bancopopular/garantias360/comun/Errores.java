package co.bancopopular.garantias360.comun;

import co.bancopopular.garantias360.reglas.motor.ReglaException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.Map;

/** Errores de la API en formato RFC 9457 (Problem Details), en español y con Correlation ID. */
@RestControllerAdvice
public class Errores {

    /** Error de negocio con código estable para los consumidores. */
    public static class NegocioException extends RuntimeException {
        private final HttpStatus estado;
        private final String codigo;
        private final List<String> detalles;

        public NegocioException(HttpStatus estado, String codigo, String mensaje, List<String> detalles) {
            super(mensaje);
            this.estado = estado;
            this.codigo = codigo;
            this.detalles = detalles;
        }

        public NegocioException(HttpStatus estado, String codigo, String mensaje) {
            this(estado, codigo, mensaje, List.of());
        }

        public List<String> detalles() {
            return detalles;
        }

        @Override
        public String toString() {
            return "NegocioException[" + codigo + "]: " + getMessage() + (detalles.isEmpty() ? "" : " " + detalles);
        }
    }

    public static NegocioException noEncontrado(String que) {
        return new NegocioException(HttpStatus.NOT_FOUND, "NO_ENCONTRADO", que + " no existe");
    }

    public static NegocioException conflicto(String codigo, String mensaje) {
        return new NegocioException(HttpStatus.CONFLICT, codigo, mensaje);
    }

    public static NegocioException invalido(String codigo, String mensaje, List<String> detalles) {
        return new NegocioException(HttpStatus.UNPROCESSABLE_ENTITY, codigo, mensaje, detalles);
    }

    public static NegocioException prohibido(String codigo, String mensaje) {
        return new NegocioException(HttpStatus.FORBIDDEN, codigo, mensaje);
    }

    @ExceptionHandler(NegocioException.class)
    ProblemDetail negocio(NegocioException e) {
        return problema(e.estado, e.codigo, e.getMessage(), e.detalles);
    }

    @ExceptionHandler(ReglaException.class)
    ProblemDetail regla(ReglaException e) {
        return problema(HttpStatus.UNPROCESSABLE_ENTITY, "REGLA_INVALIDA", e.getMessage(), List.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail validacion(MethodArgumentNotValidException e) {
        List<String> detalles = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .toList();
        return problema(HttpStatus.UNPROCESSABLE_ENTITY, "DATOS_INVALIDOS", "La petición tiene datos inválidos", detalles);
    }

    @ExceptionHandler({AccessDeniedException.class, AuthorizationDeniedException.class})
    ProblemDetail acceso(RuntimeException e) {
        return problema(HttpStatus.FORBIDDEN, "SIN_PERMISO", "Tu rol no permite esta operación", List.of());
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ProblemDetail concurrencia(ObjectOptimisticLockingFailureException e) {
        return problema(HttpStatus.CONFLICT, "MODIFICADO_POR_OTRO",
                "El registro fue modificado por otra operación. Recarga e intenta de nuevo.", List.of());
    }

    private ProblemDetail problema(HttpStatus estado, String codigo, String mensaje, List<String> detalles) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(estado, mensaje);
        p.setTitle(estado.getReasonPhrase());
        p.setProperties(Map.of(
                "codigo", codigo,
                "detalles", detalles == null ? List.of() : detalles,
                "correlationId", Contexto.correlationId()));
        return p;
    }
}
