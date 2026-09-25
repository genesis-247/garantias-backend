package co.bancopopular.garantias360.cobertura;

import co.bancopopular.garantias360.cobertura.motor.ModeloCobertura.ResultadoCobertura;
import co.bancopopular.garantias360.cobertura.motor.ModeloCobertura.ResultadoObligacion;
import co.bancopopular.garantias360.garantia.Garantia;
import co.bancopopular.garantias360.garantia.IdoneidadService;
import co.bancopopular.garantias360.garantia.Repositorios;
import co.bancopopular.garantias360.reglas.Regla;
import co.bancopopular.garantias360.reglas.ReglaService;
import co.bancopopular.garantias360.reglas.ReglaVersion;
import co.bancopopular.garantias360.reglas.ResolutorReglas;
import co.bancopopular.garantias360.reglas.motor.TipoRegla;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

/**
 * Simulación de una versión de regla sobre el portafolio real sin afectar producción (RF-1404,
 * anexo B, B.4): compara la cobertura vigente con la que resultaría y los cambios de idoneidad.
 */
@Service
public class SimulacionService {

    private final ReglaService reglas;
    private final ResolutorReglas resolutor;
    private final CoberturaService cobertura;
    private final CoberturaRepositorios.Vigentes vigentes;
    private final Repositorios.Garantias garantias;
    private final IdoneidadService idoneidad;

    public SimulacionService(ReglaService reglas, ResolutorReglas resolutor, CoberturaService cobertura,
                             CoberturaRepositorios.Vigentes vigentes, Repositorios.Garantias garantias,
                             IdoneidadService idoneidad) {
        this.reglas = reglas;
        this.resolutor = resolutor;
        this.cobertura = cobertura;
        this.vigentes = vigentes;
        this.garantias = garantias;
        this.idoneidad = idoneidad;
    }

    public record CambioObligacion(String obligacion, BigDecimal exposicion, BigDecimal ratioActual, BigDecimal ratioSimulado,
                                   BigDecimal asignadoActual, BigDecimal asignadoSimulado, boolean cumpleActual,
                                   boolean cumpleSimulado) {
    }

    public record CambioIdoneidad(String garantia, String actual, String simulada, String motivo) {
    }

    @Transactional(readOnly = true)
    public Map<String, Object> simular(String codigo, int version) {
        Regla r = reglas.regla(codigo);
        ReglaVersion candidata = reglas.version(codigo, version);
        ResolutorReglas.Instantanea base = resolutor.instantanea();
        ResolutorReglas.Instantanea sim = resolutor.instantanea(candidata);

        List<CambioIdoneidad> cambiosIdoneidad = new ArrayList<>();
        if (r.tipo == TipoRegla.IDONEIDAD) {
            for (Garantia g : garantias.findAll()) {
                var actual = idoneidad.evaluar(g, base);
                var simulada = idoneidad.evaluar(g, sim);
                if (!actual.idoneidad().equals(simulada.idoneidad())) {
                    cambiosIdoneidad.add(new CambioIdoneidad(g.codigo, actual.idoneidad(), simulada.idoneidad(), simulada.motivo()));
                }
            }
        }

        Map<UUID, CoberturaVigente> hoy = new HashMap<>();
        vigentes.findAll().forEach(v -> hoy.put(v.obligacionId, v));
        List<CambioObligacion> cambios = new ArrayList<>();
        BigDecimal asignadoActual = BigDecimal.ZERO;
        BigDecimal asignadoSimulado = BigDecimal.ZERO;
        BigDecimal exposicion = BigDecimal.ZERO;
        int bajoObjetivoActual = 0;
        int bajoObjetivoSimulado = 0;
        for (ResultadoCobertura rc : cobertura.simular(sim)) {
            for (ResultadoObligacion o : rc.obligaciones()) {
                if (o.exposicion().signum() <= 0) {
                    continue;
                }
                CoberturaVigente v = hoy.get(UUID.fromString(o.id()));
                BigDecimal aAct = v == null ? BigDecimal.ZERO : v.asignado;
                boolean cumpleAct = v != null && v.brecha.signum() == 0;
                boolean cumpleSim = o.brecha().signum() == 0;
                exposicion = exposicion.add(o.exposicion());
                asignadoActual = asignadoActual.add(aAct.min(o.exposicion()));
                asignadoSimulado = asignadoSimulado.add(o.asignado().min(o.exposicion()));
                bajoObjetivoActual += cumpleAct ? 0 : 1;
                bajoObjetivoSimulado += cumpleSim ? 0 : 1;
                if (aAct.compareTo(o.asignado()) != 0 || cumpleAct != cumpleSim) {
                    cambios.add(new CambioObligacion(o.numero(), o.exposicion(), v == null ? null : v.ratio, o.ratio(),
                            aAct, o.asignado(), cumpleAct, cumpleSim));
                }
            }
        }
        cambios.sort(Comparator.comparing((CambioObligacion c) -> c.asignadoSimulado().subtract(c.asignadoActual()).abs()).reversed());
        Map<String, Object> resultado = new LinkedHashMap<>();
        resultado.put("regla", codigo);
        resultado.put("version", version);
        resultado.put("tipo", r.tipo);
        resultado.put("exposicionTotal", exposicion);
        resultado.put("coberturaActual", ratio(asignadoActual, exposicion));
        resultado.put("coberturaSimulada", ratio(asignadoSimulado, exposicion));
        resultado.put("obligacionesBajoObjetivoActual", bajoObjetivoActual);
        resultado.put("obligacionesBajoObjetivoSimulado", bajoObjetivoSimulado);
        resultado.put("obligacionesConCambio", cambios.size());
        resultado.put("cambios", cambios.stream().limit(50).toList());
        resultado.put("cambiosIdoneidad", cambiosIdoneidad);
        resultado.put("nota", "Simulación sin efectos: no se guardó ningún cálculo ni se cambió ninguna garantía.");
        return resultado;
    }

    private static BigDecimal ratio(BigDecimal a, BigDecimal b) {
        return b.signum() == 0 ? BigDecimal.ZERO : a.divide(b, 6, java.math.RoundingMode.HALF_EVEN);
    }
}
