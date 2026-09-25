package co.bancopopular.garantias360.garantia;

import co.bancopopular.garantias360.configuracion.TipoGarantia;
import co.bancopopular.garantias360.configuracion.TipoGarantiaRepositorio;
import co.bancopopular.garantias360.obligacion.Obligacion;
import co.bancopopular.garantias360.obligacion.ObligacionRepositorio;
import co.bancopopular.garantias360.reglas.ResolutorReglas;
import co.bancopopular.garantias360.reglas.motor.ReglaException;
import co.bancopopular.garantias360.reglas.motor.TipoRegla;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * Evalúa la idoneidad de una garantía con la regla IDONEIDAD activa (N-01, anexo B ejemplo 3 y 4).
 * La regla recibe hechos, no decide sobre datos que no conoce: sin regla activa la garantía queda
 * "NO_EVALUADA" y no cuenta como idónea.
 */
@Service
public class IdoneidadService {

    private final TipoGarantiaRepositorio tipos;
    private final Repositorios.Vinculos vinculos;
    private final ObligacionRepositorio obligaciones;

    public IdoneidadService(TipoGarantiaRepositorio tipos, Repositorios.Vinculos vinculos,
                            ObligacionRepositorio obligaciones) {
        this.tipos = tipos;
        this.vinculos = vinculos;
        this.obligaciones = obligaciones;
    }

    public record Resultado(String idoneidad, String motivo, String regla) {
    }

    public Resultado evaluar(Garantia g, ResolutorReglas.Instantanea reglas) {
        TipoGarantia tipo = tipos.findByCodigo(g.tipoCodigo).orElse(null);
        Map<String, Object> hechos = hechos(g, tipo);
        try {
            Optional<ResolutorReglas.Resolucion> r = reglas.intentar(TipoRegla.IDONEIDAD, hechos);
            return r.map(x -> new Resultado(x.valor(), x.motivo(), x.regla()))
                    .orElse(new Resultado("NO_EVALUADA", "No hay una regla de idoneidad activa", null));
        } catch (ReglaException e) {
            return new Resultado("NO_EVALUADA", e.getMessage(), null);
        }
    }

    /** Aplica el resultado a la entidad; devuelve true si cambió. */
    public boolean aplicar(Garantia g, ResolutorReglas.Instantanea reglas) {
        Resultado r = evaluar(g, reglas);
        boolean cambio = !Objects.equals(g.idoneidad, r.idoneidad()) || !Objects.equals(g.idoneidadMotivo, r.motivo());
        g.idoneidad = r.idoneidad();
        g.idoneidadMotivo = r.motivo();
        g.idoneidadRegla = r.regla();
        return cambio;
    }

    Map<String, Object> hechos(Garantia g, TipoGarantia tipo) {
        LocalDate hoy = LocalDate.now(ZoneId.of("America/Bogota"));
        boolean requiereAvaluo = tipo != null && tipo.requiereAvaluo;
        boolean valoracionVigente = !requiereAvaluo
                || (g.fechaUltimaValoracion != null && (g.fechaProximaValoracion == null || !g.fechaProximaValoracion.isBefore(hoy)));
        boolean polizaVigente = tipo == null || !tipo.requierePoliza
                || (g.atributos != null && g.atributos.path("polizaVigente").asBoolean(false));
        String destino = vinculos.findByGarantiaIdAndVigenteTrue(g.id).stream()
                .map(v -> obligaciones.findById(v.obligacionId).map(o -> o.destino).orElse(null))
                .filter(Objects::nonNull)
                .findFirst()
                .orElse("NO_INFORMADO");
        Map<String, Object> h = new HashMap<>();
        h.put("tipo", g.tipoCodigo);
        h.put("clase", tipo == null ? "OTRA" : tipo.clase);
        h.put("estadoJuridico", g.estadoJuridico);
        h.put("condicionamientosAbiertos", g.condicionamientosAbiertos);
        h.put("perfeccionada", g.perfeccionada);
        h.put("valoracionVigente", valoracionVigente);
        h.put("polizaRequeridaVigente", polizaVigente);
        h.put("destinoCredito", destino);
        return h;
    }

    List<Obligacion> obligacionesDe(Garantia g) {
        List<UUID> ids = vinculos.findByGarantiaIdAndVigenteTrue(g.id).stream().map(v -> v.obligacionId).toList();
        return obligaciones.findByIdIn(ids);
    }
}
