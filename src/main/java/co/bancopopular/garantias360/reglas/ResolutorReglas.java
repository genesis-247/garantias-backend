package co.bancopopular.garantias360.reglas;

import co.bancopopular.garantias360.comun.Json;
import co.bancopopular.garantias360.reglas.motor.EvaluadorTabla;
import co.bancopopular.garantias360.reglas.motor.ReglaException;
import co.bancopopular.garantias360.reglas.motor.TablaDecision;
import co.bancopopular.garantias360.reglas.motor.TipoRegla;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Entrega una instantánea consistente de las reglas activas: un cálculo usa siempre el mismo
 * conjunto de versiones de principio a fin, y la simulación puede sustituir una versión candidata.
 */
@Component
public class ResolutorReglas {

    private final ReglaRepositorios.Reglas reglas;
    private final ReglaRepositorios.Versiones versiones;
    private final EvaluadorTabla evaluador = new EvaluadorTabla();

    public ResolutorReglas(ReglaRepositorios.Reglas reglas, ReglaRepositorios.Versiones versiones) {
        this.reglas = reglas;
        this.versiones = versiones;
    }

    public record ReglaVigente(String codigo, int version, String hash, TipoRegla tipo, TablaDecision tabla) {
        public String referencia() {
            return codigo + " v" + version;
        }
    }

    public record Resolucion(String valor, String motivo, String regla, Integer fila) {
    }

    @Transactional(readOnly = true)
    public Instantanea instantanea() {
        return instantanea(null);
    }

    /** @param candidata versión que reemplaza a la activa de su tipo (simulación); null = solo activas. */
    @Transactional(readOnly = true)
    public Instantanea instantanea(ReglaVersion candidata) {
        Map<UUID, Regla> porId = new HashMap<>();
        reglas.findAll().forEach(r -> porId.put(r.id, r));
        Map<TipoRegla, ReglaVigente> activas = new EnumMap<>(TipoRegla.class);
        for (ReglaVersion v : versiones.findByEstado(ReglaVersion.EstadoRegla.ACTIVA)) {
            Regla r = porId.get(v.reglaId);
            activas.put(r.tipo, vigente(r, v));
        }
        if (candidata != null) {
            Regla r = porId.get(candidata.reglaId);
            activas.put(r.tipo, vigente(r, candidata));
        }
        return new Instantanea(Collections.unmodifiableMap(activas), evaluador);
    }

    private static ReglaVigente vigente(Regla r, ReglaVersion v) {
        return new ReglaVigente(r.codigo, v.numero, v.hash, r.tipo, Json.leer(v.definicion, TablaDecision.class));
    }

    public static final class Instantanea {
        private final Map<TipoRegla, ReglaVigente> activas;
        private final EvaluadorTabla evaluador;

        Instantanea(Map<TipoRegla, ReglaVigente> activas, EvaluadorTabla evaluador) {
            this.activas = activas;
            this.evaluador = evaluador;
        }

        public Resolucion resolver(TipoRegla tipo, Map<String, Object> contexto) {
            ReglaVigente regla = activas.get(tipo);
            if (regla == null) {
                throw new ReglaException("No hay una regla activa de tipo " + tipo);
            }
            try {
                EvaluadorTabla.Evaluacion e = evaluador.evaluar(tipo, regla.tabla(), contexto);
                return new Resolucion(e.valor(), e.motivo(), regla.referencia(), e.fila());
            } catch (ReglaException ex) {
                throw new ReglaException("Regla " + regla.referencia() + ": " + ex.getMessage());
            }
        }

        public Optional<Resolucion> intentar(TipoRegla tipo, Map<String, Object> contexto) {
            if (!activas.containsKey(tipo)) {
                return Optional.empty();
            }
            return Optional.of(resolver(tipo, contexto));
        }

        public Map<String, Object> referencias() {
            Map<String, Object> refs = new TreeMap<>();
            activas.forEach((t, r) -> refs.put(t.name(), Map.of("regla", r.codigo(), "version", r.version(),
                    "hash", Objects.requireNonNullElse(r.hash(), ""))));
            return refs;
        }
    }
}
