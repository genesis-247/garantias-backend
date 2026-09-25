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

    private Roles() {
    }
}
