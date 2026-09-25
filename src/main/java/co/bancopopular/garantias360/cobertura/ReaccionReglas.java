package co.bancopopular.garantias360.cobertura;

import co.bancopopular.garantias360.auditoria.AuditoriaService;
import co.bancopopular.garantias360.comun.TareasPosteriores;
import co.bancopopular.garantias360.garantia.Garantia;
import co.bancopopular.garantias360.garantia.IdoneidadService;
import co.bancopopular.garantias360.garantia.Repositorios;
import co.bancopopular.garantias360.reglas.ReglaService;
import co.bancopopular.garantias360.reglas.ResolutorReglas;
import co.bancopopular.garantias360.reglas.motor.TipoRegla;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.Objects;

/** Al activar una regla, se reevalúa la idoneidad (si aplica) y se recalcula el portafolio (RF-0805). */
@Component
public class ReaccionReglas {

    private final TareasPosteriores posteriores;
    private final CoberturaService cobertura;
    private final Repositorios.Garantias garantias;
    private final IdoneidadService idoneidad;
    private final ResolutorReglas resolutor;
    private final AuditoriaService auditoria;
    private final TransactionTemplate tx;

    public ReaccionReglas(TareasPosteriores posteriores, CoberturaService cobertura, Repositorios.Garantias garantias,
                          IdoneidadService idoneidad, ResolutorReglas resolutor, AuditoriaService auditoria,
                          TransactionTemplate tx) {
        this.posteriores = posteriores;
        this.cobertura = cobertura;
        this.garantias = garantias;
        this.idoneidad = idoneidad;
        this.resolutor = resolutor;
        this.auditoria = auditoria;
        this.tx = tx;
    }

    @EventListener
    public void alActivar(ReglaService.ReglaActivada evento) {
        posteriores.despuesDelCommit(() -> {
            if (evento.tipo() == TipoRegla.IDONEIDAD) {
                reevaluarIdoneidad(evento);
            }
            cobertura.recalcularPortafolio("REGLA_ACTIVADA " + evento.codigo() + " v" + evento.version());
        });
    }

    public void reevaluarIdoneidad(ReglaService.ReglaActivada evento) {
        ResolutorReglas.Instantanea reglas = resolutor.instantanea();
        tx.executeWithoutResult(s -> {
            for (Garantia g : garantias.findAll()) {
                String antes = g.idoneidad;
                if (idoneidad.aplicar(g, reglas) && !Objects.equals(antes, g.idoneidad)) {
                    auditoria.registrar("REEVALUAR_IDONEIDAD", "Garantia", g.codigo, Map.of("idoneidad", antes),
                            Map.of("idoneidad", g.idoneidad, "regla", String.valueOf(g.idoneidadRegla)),
                            "Activación de " + evento.codigo() + " v" + evento.version(), "MOTOR");
                }
            }
        });
    }
}
