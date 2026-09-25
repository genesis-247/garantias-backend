package co.bancopopular.garantias360.garantia;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** Macroestados fijos del ciclo de vida (M03). Cada tipo mapea sus estados configurables a estos. */
public enum Macroestado {
    SOLICITUD, REGISTRO, ESTUDIO_JURIDICO, CONSTITUCION, PERFECCIONAMIENTO, ACTIVA, MONITOREO, ACTUALIZACION,
    EJECUCION, LIBERACION, CIERRE, ANULADA;

    private static final Map<Macroestado, Set<Macroestado>> TRANSICIONES = Map.ofEntries(
            Map.entry(SOLICITUD, EnumSet.of(REGISTRO, ANULADA)),
            Map.entry(REGISTRO, EnumSet.of(ESTUDIO_JURIDICO, ANULADA)),
            Map.entry(ESTUDIO_JURIDICO, EnumSet.of(CONSTITUCION, ANULADA)),
            Map.entry(CONSTITUCION, EnumSet.of(PERFECCIONAMIENTO, ANULADA)),
            Map.entry(PERFECCIONAMIENTO, EnumSet.of(ACTIVA, ANULADA)),
            Map.entry(ACTIVA, EnumSet.of(MONITOREO, LIBERACION, EJECUCION)),
            Map.entry(MONITOREO, EnumSet.of(ACTUALIZACION, EJECUCION, LIBERACION)),
            Map.entry(ACTUALIZACION, EnumSet.of(MONITOREO)),
            Map.entry(EJECUCION, EnumSet.of(MONITOREO, CIERRE)),
            Map.entry(LIBERACION, EnumSet.of(CIERRE, MONITOREO)),
            Map.entry(CIERRE, EnumSet.noneOf(Macroestado.class)),
            Map.entry(ANULADA, EnumSet.noneOf(Macroestado.class)));

    public Set<Macroestado> siguientes() {
        return TRANSICIONES.get(this);
    }

    public boolean puedePasarA(Macroestado destino) {
        return siguientes().contains(destino);
    }

    /** Estados en los que la garantía respalda obligaciones y entra al cálculo de cobertura. */
    public boolean respalda() {
        return this == ACTIVA || this == MONITOREO || this == ACTUALIZACION || this == EJECUCION
                || this == PERFECCIONAMIENTO || this == CONSTITUCION;
    }
}
