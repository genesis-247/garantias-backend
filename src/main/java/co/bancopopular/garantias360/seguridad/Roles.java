package co.bancopopular.garantias360.seguridad;

/**
 * Roles de la sección 4 de la especificación (área × nivel). Llegan como app roles de Entra ID
 * (claim "roles") en producción, o por cabecera en el perfil local/demo.
 */
public final class Roles {

    public static final String OPERACIONES_GESTOR = "OPERACIONES_GESTOR";
    public static final String OPERACIONES_DIRECTOR = "OPERACIONES_DIRECTOR";
    public static final String JURIDICA_GESTOR = "JURIDICA_GESTOR";
    public static final String JURIDICA_DIRECTOR = "JURIDICA_DIRECTOR";
    public static final String RIESGOS_GESTOR = "RIESGOS_GESTOR";
    public static final String APROBADOR_REGLAS = "APROBADOR_REGLAS";
    public static final String CUMPLIMIENTO = "CUMPLIMIENTO";
    public static final String COMERCIAL = "COMERCIAL";
    public static final String AUDITOR = "AUDITOR";
    public static final String ADMIN_FUNCIONAL = "ADMIN_FUNCIONAL";
    public static final String ADMIN_SEGURIDAD = "ADMIN_SEGURIDAD";
    public static final String CONSULTOR = "CONSULTOR";
    public static final String SISTEMA = "SISTEMA";

    /** Expresiones para @PreAuthorize. */
    public static final String LECTURA = "isAuthenticated()";
    public static final String OPERA_GARANTIAS =
            "hasAnyRole('OPERACIONES_GESTOR','OPERACIONES_DIRECTOR','JURIDICA_GESTOR','JURIDICA_DIRECTOR','SISTEMA')";
    public static final String REGISTRA_GARANTIAS = "hasAnyRole('OPERACIONES_GESTOR','OPERACIONES_DIRECTOR','SISTEMA')";
    public static final String EDITA_REGLAS = "hasAnyRole('RIESGOS_GESTOR','ADMIN_FUNCIONAL')";
    public static final String APRUEBA_REGLAS = "hasRole('APROBADOR_REGLAS')";
    public static final String CALCULA_COBERTURA = "hasAnyRole('RIESGOS_GESTOR','OPERACIONES_DIRECTOR','SISTEMA')";
    public static final String INTEGRACION = "hasRole('SISTEMA')";
    public static final String AUDITA = "hasAnyRole('AUDITOR','CUMPLIMIENTO','ADMIN_FUNCIONAL')";
    public static final String CONFIGURA_TIPOS = "hasRole('ADMIN_FUNCIONAL')";
    public static final String ADMINISTRA_SEGURIDAD = "hasRole('ADMIN_SEGURIDAD')";
    public static final String CONSTITUYE = "hasAnyRole('OPERACIONES_GESTOR','OPERACIONES_DIRECTOR','SISTEMA')";
    public static final String CARGA_MASIVA = "hasAnyRole('OPERACIONES_GESTOR','OPERACIONES_DIRECTOR')";
    public static final String APRUEBA_CARGA = "hasRole('OPERACIONES_DIRECTOR')";

    /** Catálogo para la administración de perfiles (M24): rol → descripción. */
    public static final java.util.Map<String, String> CATALOGO = catalogo();

    /** Roles que escriben: incompatibles con AUDITOR (el auditor no escribe). */
    public static final java.util.Set<String> ESCRITURA = java.util.Set.of(OPERACIONES_GESTOR, OPERACIONES_DIRECTOR,
            JURIDICA_GESTOR, JURIDICA_DIRECTOR, RIESGOS_GESTOR, APROBADOR_REGLAS, ADMIN_FUNCIONAL, ADMIN_SEGURIDAD, SISTEMA);

    /** Roles que operan garantías: incompatibles con la administración (sección 4). */
    public static final java.util.Set<String> OPERACION = java.util.Set.of(OPERACIONES_GESTOR, OPERACIONES_DIRECTOR,
            JURIDICA_GESTOR, JURIDICA_DIRECTOR);

    private static java.util.Map<String, String> catalogo() {
        var m = new java.util.LinkedHashMap<String, String>();
        m.put(OPERACIONES_GESTOR, "Operaciones — Gestor: registra y opera garantías, constitución, valoraciones y cargas masivas");
        m.put(OPERACIONES_DIRECTOR, "Operaciones — Director: aprueba liberaciones, cambios de valor sobre el umbral y cargas masivas");
        m.put(JURIDICA_GESTOR, "Jurídica — Gestor: estudio jurídico, hallazgos y condicionamientos");
        m.put(JURIDICA_DIRECTOR, "Jurídica — Director: concepto jurídico final e inicio de la ejecución");
        m.put(RIESGOS_GESTOR, "Riesgos — Gestor: simula cobertura y propone reglas");
        m.put(APROBADOR_REGLAS, "Riesgos — Director: aprueba reglas (maker–checker)");
        m.put(CUMPLIMIENTO, "Cumplimiento: controles SFC y brechas");
        m.put(COMERCIAL, "Comercial: consulta de sus clientes");
        m.put(CONSULTOR, "Consultor: lectura");
        m.put(AUDITOR, "Auditor: lectura total, sin escritura");
        m.put(ADMIN_FUNCIONAL, "Administrador funcional: tipos de garantía, catálogos y parámetros");
        m.put(ADMIN_SEGURIDAD, "Administrador de seguridad: usuarios y perfiles");
        m.put(SISTEMA, "Cliente técnico: aplicativos de producto, Appian, Flexcube y procesos batch");
        return java.util.Collections.unmodifiableMap(m);
    }

    private Roles() {
    }
}
