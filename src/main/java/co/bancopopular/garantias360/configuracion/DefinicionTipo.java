package co.bancopopular.garantias360.configuracion;

import java.util.List;

/**
 * Contenido versionado de un tipo de garantía (M16): comportamiento (RF-1602), campos (RF-1603,
 * RF-1604), checklist jurídico y plantilla de actividades de constitución (RF-1606).
 */
public final class DefinicionTipo {

    private DefinicionTipo() {
    }

    /** Registros públicos admitidos (RF-1602). */
    public static final List<String> REGISTROS = List.of("NINGUNO", "ORIP", "RGM", "RUNT", "FNA", "FNG", "FAG", "CAMARA_COMERCIO");

    public record Comportamiento(String nombre, String descripcion, String clase, boolean requiereAvaluo,
                                 boolean requierePoliza, String registroPublico, boolean admiteMultiples) {
    }

    /** Ítem del checklist jurídico del tipo (M05, RF-0501). */
    public record ItemChecklist(String codigo, String descripcion, boolean obligatorio, String ayuda) {
    }

    /**
     * Actividad de la plantilla de constitución y perfeccionamiento (M06, RF-0601 a RF-0603).
     *
     * @param campos         campos del tipo que se capturan al completar la actividad (escritura, folio…)
     * @param diasPlazo      días hábiles aproximados desde la generación del plan hasta la fecha límite
     * @param rolResponsable rol que normalmente ejecuta la actividad (informativo; la tarea vive en Appian)
     */
    public record PlantillaActividad(String codigo, String nombre, String descripcion, int orden, boolean obligatoria,
                                     boolean requiereEvidencia, Integer diasPlazo, String rolResponsable, List<String> campos) {
    }

    /** Lo que se edita en un borrador. */
    public record Edicion(Comportamiento comportamiento, List<CampoDefinicion> campos, List<ItemChecklist> checklistJuridico,
                          List<PlantillaActividad> actividades) {
    }
}
