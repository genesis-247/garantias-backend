package co.bancopopular.garantias360.demo;

import co.bancopopular.garantias360.configuracion.CampoDefinicion;
import co.bancopopular.garantias360.configuracion.CampoDefinicion.Opcion;
import co.bancopopular.garantias360.configuracion.CampoDefinicion.TipoCampo;
import co.bancopopular.garantias360.configuracion.DefinicionTipo.ItemChecklist;
import co.bancopopular.garantias360.configuracion.DefinicionTipo.PlantillaActividad;
import co.bancopopular.garantias360.configuracion.TipoGarantiaService.NuevoTipo;
import co.bancopopular.garantias360.reglas.ReglaService.NuevaRegla;
import co.bancopopular.garantias360.reglas.motor.TablaDecision;
import co.bancopopular.garantias360.reglas.motor.TablaDecision.CasoPrueba;
import co.bancopopular.garantias360.reglas.motor.TablaDecision.Fila;
import co.bancopopular.garantias360.reglas.motor.TablaDecision.Salida;
import co.bancopopular.garantias360.reglas.motor.TipoRegla;

import java.math.BigDecimal;
import java.util.*;

/**
 * Catálogo inicial de tipos (RF-1608) y reglas ILUSTRATIVAS del anexo B. Los valores numéricos no
 * son políticas del Banco: los define Riesgo de Crédito (P-09) antes de producción.
 */
public final class CatalogoDemo {

    private CatalogoDemo() {
    }

    // ------------------------------------------------------------------ campos

    private static CampoDefinicion texto(String codigo, String etiqueta, boolean obligatorio, String desde, boolean llave,
                                         String grupo, int orden, String patron, String ayuda) {
        return new CampoDefinicion(codigo, etiqueta, TipoCampo.TEXTO, obligatorio, desde, llave, grupo, orden, ayuda,
                null, null, patron, null);
    }

    private static CampoDefinicion numero(String codigo, String etiqueta, TipoCampo tipo, boolean obligatorio, String grupo,
                                          int orden, String min, String max) {
        return new CampoDefinicion(codigo, etiqueta, tipo, obligatorio, null, false, grupo, orden, null,
                min == null ? null : new BigDecimal(min), max == null ? null : new BigDecimal(max), null, null);
    }

    private static CampoDefinicion fecha(String codigo, String etiqueta, boolean obligatorio, String grupo, int orden) {
        return new CampoDefinicion(codigo, etiqueta, TipoCampo.FECHA, obligatorio, null, false, grupo, orden, null, null, null, null, null);
    }

    private static CampoDefinicion booleano(String codigo, String etiqueta, boolean obligatorio, String grupo, int orden, String ayuda) {
        return new CampoDefinicion(codigo, etiqueta, TipoCampo.BOOLEANO, obligatorio, null, false, grupo, orden, ayuda, null, null, null, null);
    }

    private static CampoDefinicion lista(String codigo, String etiqueta, boolean obligatorio, String grupo, int orden, String... opciones) {
        List<Opcion> ops = Arrays.stream(opciones).map(o -> new Opcion(o, etiquetaOpcion(o), true)).toList();
        return new CampoDefinicion(codigo, etiqueta, TipoCampo.LISTA, obligatorio, null, false, grupo, orden, null, null, null, null, ops);
    }

    private static String etiquetaOpcion(String id) {
        String t = id.replace('_', ' ').toLowerCase();
        return Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }

    private static final CampoDefinicion POLIZA = booleano("polizaVigente", "Póliza vigente", false, "Seguros", 90,
            "Incendio y terremoto (inmuebles) o todo riesgo (vehículos), con el Banco como beneficiario oneroso");
    private static final CampoDefinicion FOLIO_RGM = texto("folioRgm", "Folio electrónico RGM", true, "PERFECCIONAMIENTO",
            false, "Registro", 80, null, "Número de inscripción en el Registro de Garantías Mobiliarias (Confecámaras)");

    // ------------------------------------------------------------------ checklist jurídico y plantilla de constitución

    private static ItemChecklist item(String codigo, String descripcion, boolean obligatorio) {
        return new ItemChecklist(codigo, descripcion, obligatorio, null);
    }

    private static PlantillaActividad act(int orden, String codigo, String nombre, boolean obligatoria, boolean evidencia,
                                          int dias, String rol, String... campos) {
        return new PlantillaActividad(codigo, nombre, null, orden, obligatoria, evidencia, dias, rol, List.of(campos));
    }

    private static final String OPS = "OPERACIONES_GESTOR";
    private static final String JUR = "JURIDICA_GESTOR";
    private static final ItemChecklist FACULTADES = item("FACULTADES", "Capacidad y facultades de quien constituye la garantía", true);
    private static final PlantillaActividad FIRMA = act(10, "FIRMA_DOCUMENTOS", "Firma de documentos de la garantía", true, true, 5, OPS);

    private static final Map<String, List<ItemChecklist>> CHECKLIST = Map.ofEntries(
            Map.entry("REAL_INMUEBLE", List.of(
                    item("TITULOS_10_ANIOS", "Estudio de títulos a 10 años o más", true),
                    item("CERTIFICADO_TRADICION", "Certificado de tradición con vigencia no mayor a 30 días", true),
                    item("LIMITACIONES_DOMINIO", "Sin limitaciones al dominio, embargos ni demandas inscritas", true),
                    item("VIVIENDA_FAMILIAR", "Afectación a vivienda familiar o patrimonio de familia", true),
                    FACULTADES)),
            Map.entry("VEHICULO", List.of(
                    item("HISTORIAL_RUNT", "Historial del vehículo en el RUNT", true),
                    item("PRENDAS_PREVIAS", "Sin prendas ni garantías mobiliarias previas en el RGM", true),
                    item("COMPARENDOS", "Comparendos e impuestos vehiculares al día", false), FACULTADES)),
            Map.entry("MOBILIARIA", List.of(
                    item("CONTRATO_FUENTE", "Contrato o título fuente de los bienes o derechos", true),
                    item("CESIBILIDAD", "Cesibilidad o gravabilidad de los bienes o derechos", true),
                    item("GARANTIAS_PREVIAS_RGM", "Consulta de garantías previas en el RGM", true), FACULTADES)),
            Map.entry("CESANTIAS", List.of(
                    item("AUTORIZACION_ESCRITA", "Autorización escrita del trabajador (CST art. 256)", true),
                    item("DESTINO_VIVIENDA", "Destino del crédito: vivienda (Ley 50/1990 art. 104)", true),
                    item("SIN_PIGNORACION_FNA", "Sin pignoración vigente de las cesantías con el FNA", true))),
            Map.entry("GENERAL", List.of(FACULTADES)));

    private static final Map<String, List<PlantillaActividad>> PLANTILLAS = Map.ofEntries(
            Map.entry("HIPOTECA", List.of(FIRMA,
                    act(20, "ESCRITURA_PUBLICA", "Otorgamiento de la escritura pública de hipoteca", true, true, 15, OPS, "numeroEscritura", "notaria"),
                    act(30, "PAGO_DERECHOS_REGISTRO", "Pago de derechos de registro y boleta fiscal", true, true, 20, OPS),
                    act(40, "REGISTRO_ORIP", "Radicación y registro en la ORIP", true, true, 30, OPS),
                    act(50, "POLIZA_INCENDIO_TERREMOTO", "Constitución o endoso de la póliza de incendio y terremoto", true, true, 30, OPS, "polizaVigente"),
                    act(60, "CERTIFICADO_TRADICION", "Certificado de tradición con la hipoteca anotada", true, true, 35, JUR))),
            Map.entry("VEHICULO", List.of(FIRMA,
                    act(20, "INSCRIPCION_RGM", "Inscripción en el RGM (formulario de registro inicial)", true, true, 10, OPS, "folioRgm"),
                    act(30, "ANOTACION_RUNT", "Anotación de la garantía en el organismo de tránsito (RUNT)", true, true, 20, OPS),
                    act(40, "POLIZA_TODO_RIESGO", "Póliza todo riesgo con el Banco como beneficiario", true, true, 20, OPS, "polizaVigente"))),
            Map.entry("MOBILIARIA_OTROS", List.of(FIRMA,
                    act(20, "INSCRIPCION_RGM", "Inscripción en el RGM (formulario de registro inicial)", true, true, 10, OPS, "folioRgm"),
                    act(30, "INSCRIPCION_CAMARA", "Inscripción en la Cámara de Comercio", false, true, 20, OPS))),
            Map.entry("DERECHOS_COBRO", List.of(FIRMA,
                    act(20, "INSCRIPCION_RGM", "Inscripción en el RGM (formulario de registro inicial)", true, true, 10, OPS, "folioRgm"),
                    act(30, "NOTIFICACION_DEUDOR_CEDIDO", "Notificación al deudor cedido", true, true, 15, JUR, "notificacionDeudorCedido"))),
            Map.entry("DEPOSITO_CDT", List.of(FIRMA,
                    act(20, "PIGNORACION_TITULO", "Marcación de la pignoración del CDT", true, true, 3, OPS))),
            Map.entry("DEPOSITO_AHORRO", List.of(FIRMA,
                    act(20, "BLOQUEO_CUENTA", "Bloqueo de la cuenta en garantía", true, true, 3, OPS))),
            Map.entry("FNG", List.of(
                    act(10, "CERTIFICADO_FNG", "Confirmación o expedición del certificado FNG", true, true, 5, OPS))),
            Map.entry("FAG", List.of(
                    act(10, "CERTIFICADO_FAG", "Confirmación o expedición del certificado FAG", true, true, 5, OPS))),
            Map.entry("CESANTIAS_FNA", List.of(
                    act(10, "AUTORIZACION_TRABAJADOR", "Autorización escrita del trabajador", true, true, 3, OPS, "autorizacionEscrita"),
                    act(20, "RADICACION_FNA", "Envío al FNA de la copia de la libranza o del pagaré", true, true, 10, OPS, "radicacionFna"),
                    act(30, "CONFIRMACION_FNA", "Confirmación de la pignoración por el FNA", true, true, 25, OPS, "confirmacionFna"))),
            Map.entry("FIDUCIA", List.of(
                    act(10, "CONTRATO_FIDUCIA", "Constitución del contrato de fiducia en garantía", true, true, 15, JUR),
                    act(20, "CERTIFICADO_GARANTIA", "Expedición del certificado de garantía fiduciaria", true, true, 25, OPS, "certificadoGarantia"))),
            Map.entry("PIGNORACION_RENTAS", List.of(FIRMA,
                    act(20, "NOTIFICACION_PAGADOR", "Notificación a la entidad pagadora de las rentas", true, true, 10, OPS))),
            Map.entry("PAGARE", List.of(
                    act(10, "FIRMA_PAGARE", "Firma del pagaré y carta de instrucciones", true, true, 3, OPS),
                    act(20, "DESMATERIALIZACION", "Desmaterialización y custodia en Deceval", false, true, 10, OPS))),
            Map.entry("AVAL", List.of(FIRMA)));

    private static String checklistDe(NuevoTipo t) {
        return switch (t.clase()) {
            case "REAL_INMUEBLE", "VEHICULO" -> t.clase();
            case "MOBILIARIA" -> "MOBILIARIA";
            default -> "CESANTIAS_FNA".equals(t.codigo()) ? "CESANTIAS" : "GENERAL";
        };
    }

    private static String plantillaDe(NuevoTipo t) {
        return t.codigo().startsWith("HIPOTECA") ? "HIPOTECA" : t.codigo();
    }

    /** Catálogo inicial con su checklist jurídico y su plantilla de constitución (RF-1606, RF-1608). */
    public static List<NuevoTipo> tipos() {
        return tiposBase().stream()
                .map(t -> t.con(CHECKLIST.get(checklistDe(t)), PLANTILLAS.getOrDefault(plantillaDe(t), List.of(FIRMA))))
                .toList();
    }

    private static List<NuevoTipo> tiposBase() {
        List<CampoDefinicion> inmueble = List.of(
                texto("matriculaInmobiliaria", "Matrícula inmobiliaria", true, null, true, "Inmueble", 1, "^\\d{2,3}[A-Z]?-\\d{1,8}$", "Formato 050-1234567 o 50C-1234567"),
                texto("direccion", "Dirección", true, null, false, "Inmueble", 2, null, null),
                texto("municipio", "Municipio", true, null, false, "Inmueble", 3, null, "Código o nombre DIVIPOLA"),
                texto("numeroPredialNacional", "Número predial nacional", false, null, false, "Inmueble", 4, "^\\d{30}$", "30 dígitos (catastro)"),
                numero("avaluoCatastral", "Avalúo catastral vigente", TipoCampo.MONEDA, false, "Inmueble", 5, "0", null),
                lista("gradoHipoteca", "Grado de la hipoteca", true, "Registro", 6, "PRIMER_GRADO", "SEGUNDO_GRADO"),
                texto("numeroEscritura", "Número de escritura", true, "PERFECCIONAMIENTO", false, "Registro", 7, null, null),
                texto("notaria", "Notaría", true, "PERFECCIONAMIENTO", false, "Registro", 8, null, null),
                POLIZA);
        List<NuevoTipo> t = new ArrayList<>();
        t.add(new NuevoTipo("HIPOTECA_VIVIENDA", "Hipoteca de vivienda", "REAL_INMUEBLE", true, true, "ORIP", true, inmueble));
        t.add(new NuevoTipo("HIPOTECA_NO_VIVIENDA", "Hipoteca no vivienda", "REAL_INMUEBLE", true, true, "ORIP", true, inmueble));
        t.add(new NuevoTipo("VEHICULO", "Garantía mobiliaria sobre vehículo", "VEHICULO", true, true, "RGM", true, List.of(
                texto("placa", "Placa", true, null, true, "Vehículo", 1, "^[A-Z]{3}\\d{2}[A-Z0-9]$", "Ej. ABC123"),
                texto("marca", "Marca", true, null, false, "Vehículo", 2, null, null),
                texto("linea", "Línea", false, null, false, "Vehículo", 3, null, null),
                numero("modelo", "Modelo (año)", TipoCampo.NUMERO, true, "Vehículo", 4, "1990", "2027"),
                texto("codigoFasecolda", "Código Fasecolda", true, null, false, "Vehículo", 5, "^\\d{8}$", "8 dígitos de la Guía de Valores"),
                lista("servicio", "Servicio", true, "Vehículo", 6, "PARTICULAR", "PUBLICO", "CARGA"),
                FOLIO_RGM, POLIZA)));
        t.add(new NuevoTipo("MOBILIARIA_OTROS", "Garantía mobiliaria sobre otros bienes", "MOBILIARIA", true, false, "RGM", true, List.of(
                texto("descripcionBienes", "Descripción de los bienes", true, null, false, "Bienes", 1, null, "Inventarios, maquinaria, equipos"),
                texto("ubicacion", "Ubicación", true, null, false, "Bienes", 2, null, null), FOLIO_RGM)));
        t.add(new NuevoTipo("DERECHOS_COBRO", "Derechos económicos y de cobro", "MOBILIARIA", true, false, "RGM", true, List.of(
                texto("contrato", "Contrato fuente", true, null, true, "Derechos", 1, null, null),
                texto("deudorCedido", "Deudor cedido", true, null, false, "Derechos", 2, null, null),
                numero("valorContrato", "Valor del contrato", TipoCampo.MONEDA, true, "Derechos", 3, "0", null),
                fecha("fechaTerminacion", "Terminación del contrato", true, "Derechos", 4),
                booleano("notificacionDeudorCedido", "Notificación al deudor cedido", false, "Registro", 5, "Requisito para la oponibilidad de la cesión"),
                FOLIO_RGM)));
        t.add(new NuevoTipo("DEPOSITO_CDT", "Depósito en garantía — CDT", "DEPOSITO", false, false, "NINGUNO", false, List.of(
                texto("numeroTitulo", "Número del título", true, null, true, "Depósito", 1, null, null),
                fecha("fechaVencimiento", "Vencimiento del CDT", true, "Depósito", 2))));
        t.add(new NuevoTipo("DEPOSITO_AHORRO", "Depósito en garantía — cuenta de ahorros", "DEPOSITO", false, false, "NINGUNO", false, List.of(
                texto("numeroCuenta", "Número de cuenta", true, null, true, "Depósito", 1, null, null))));
        t.add(new NuevoTipo("FNG", "Garantía del Fondo Nacional de Garantías", "FONDO_GARANTIAS", false, false, "FNG", false, List.of(
                texto("numeroCertificado", "Número de certificado", true, null, true, "Certificado", 1, null, null),
                numero("porcentajeCobertura", "Porcentaje de cobertura", TipoCampo.NUMERO, true, "Certificado", 2, "0", "100"),
                fecha("fechaVencimientoCertificado", "Vencimiento del certificado", true, "Certificado", 3),
                texto("programa", "Programa FNG", false, null, false, "Certificado", 4, null, null))));
        t.add(new NuevoTipo("FAG", "Garantía del FAG (Finagro)", "FONDO_GARANTIAS", false, false, "FAG", false, List.of(
                texto("numeroCertificado", "Número de certificado", true, null, true, "Certificado", 1, null, null),
                numero("porcentajeCobertura", "Porcentaje de cobertura", TipoCampo.NUMERO, true, "Certificado", 2, "0", "100"),
                fecha("fechaVencimientoCertificado", "Vencimiento del certificado", true, "Certificado", 3))));
        t.add(new NuevoTipo("CESANTIAS_FNA", "Pignoración de cesantías FNA", "DEPOSITO", false, false, "FNA", false, List.of(
                texto("numeroAfiliado", "Identificación del afiliado FNA", true, null, true, "Afiliado", 1, null, null),
                lista("regimen", "Régimen de cesantías", true, "Afiliado", 2, "PRIVADO", "PUBLICO"),
                texto("empleador", "Empleador", true, null, false, "Afiliado", 3, null, null),
                booleano("autorizacionEscrita", "Autorización escrita del trabajador", true, "Requisitos", 4, "CST art. 256; Ley 50/1990 art. 104"),
                texto("radicacionFna", "Radicación ante el FNA", true, "CONSTITUCION", false, "Registro", 5, null, "Copia de libranza o pagaré enviada al FNA"),
                texto("confirmacionFna", "Confirmación de pignoración del FNA", true, "PERFECCIONAMIENTO", false, "Registro", 6, null, null))));
        t.add(new NuevoTipo("FIDUCIA", "Fiducia en garantía", "FIDUCIARIA", true, false, "NINGUNO", true, List.of(
                texto("fideicomiso", "Fideicomiso", true, null, true, "Fiducia", 1, null, null),
                texto("fiduciaria", "Sociedad fiduciaria", true, null, false, "Fiducia", 2, null, null),
                texto("certificadoGarantia", "Certificado de garantía", true, "PERFECCIONAMIENTO", false, "Fiducia", 3, null, null))));
        t.add(new NuevoTipo("PIGNORACION_RENTAS", "Pignoración de rentas", "DERECHO_ECONOMICO", false, false, "NINGUNO", true, List.of(
                texto("fuenteRentas", "Fuente de las rentas", true, null, false, "Rentas", 1, null, null))));
        t.add(new NuevoTipo("PAGARE", "Pagaré", "PERSONAL", false, false, "NINGUNO", true, List.of(
                texto("numeroPagare", "Número de pagaré", true, null, true, "Pagaré", 1, null, null),
                booleano("desmaterializado", "Desmaterializado (Deceval)", false, "Pagaré", 2, null))));
        t.add(new NuevoTipo("AVAL", "Aval / codeudor", "PERSONAL", false, false, "NINGUNO", true, List.of(
                texto("avalista", "Avalista o codeudor", true, null, false, "Aval", 1, null, null))));
        return t;
    }

    // ------------------------------------------------------------------ reglas (anexo B, ilustrativas)

    private static Fila f(String valor, String motivo, String... condiciones) {
        Map<String, String> c = new LinkedHashMap<>();
        for (int i = 0; i < condiciones.length; i += 2) {
            c.put(condiciones[i], condiciones[i + 1]);
        }
        return new Fila(c, new Salida(valor, motivo));
    }

    private static CasoPrueba caso(String nombre, String esperado, Object... entradas) {
        Map<String, Object> e = new LinkedHashMap<>();
        for (int i = 0; i < entradas.length; i += 2) {
            e.put((String) entradas[i], entradas[i + 1]);
        }
        return new CasoPrueba(nombre, e, esperado);
    }

    public static List<NuevaRegla> reglas() {
        List<NuevaRegla> r = new ArrayList<>();
        r.add(new NuevaRegla("HC-TIPO", "Haircut por tipo de garantía",
                "Descuento por riesgo de realización, liquidez, moneda y vigencia de la valoración (anexo B, ejemplos 1 y 2). Valores ilustrativos.",
                TipoRegla.HAIRCUT, new TablaDecision(List.of(
                        f("0", "Depósito en pesos", "tipo", "in (DEPOSITO_CDT, DEPOSITO_AHORRO)", "moneda", "COP"),
                        f("0.10", "Depósito en moneda extranjera", "tipo", "in (DEPOSITO_CDT, DEPOSITO_AHORRO)"),
                        f("0", "El valor ya es el porcentaje o saldo certificado", "tipo", "in (FNG, FAG, CESANTIAS_FNA)"),
                        f("1", "Garantía personal: no aporta valor de realización", "tipo", "in (PAGARE, AVAL)"),
                        f("0.45", "Hipoteca con valoración vencida", "tipo", "in (HIPOTECA_VIVIENDA, HIPOTECA_NO_VIVIENDA)", "valoracionVencida", "true"),
                        f("0.55", "Valoración vencida", "valoracionVencida", "true"),
                        f("0.30", null, "tipo", "HIPOTECA_VIVIENDA"),
                        f("0.40", null, "tipo", "HIPOTECA_NO_VIVIENDA"),
                        f("0.30", null, "tipo", "VEHICULO"),
                        f("0.35", null, "tipo", "FIDUCIA"),
                        f("0.40", null, "tipo", "PIGNORACION_RENTAS"),
                        f("0.50", null, "tipo", "in (DERECHOS_COBRO, MOBILIARIA_OTROS)")),
                        new Salida("0.50", "Sin haircut específico")),
                List.of(caso("CDT en pesos", "0", "tipo", "DEPOSITO_CDT", "moneda", "COP", "valoracionVencida", false),
                        caso("Hipoteca vivienda vigente", "0.30", "tipo", "HIPOTECA_VIVIENDA", "moneda", "COP", "valoracionVencida", false),
                        caso("Hipoteca con avalúo vencido", "0.45", "tipo", "HIPOTECA_VIVIENDA", "moneda", "COP", "valoracionVencida", true),
                        caso("Pagaré", "1", "tipo", "PAGARE", "moneda", "COP", "valoracionVencida", false))));
        r.add(new NuevaRegla("COB-OBJ", "Cobertura objetivo por producto",
                "Cobertura mínima exigida por política (anexo B, ejemplo 6). Valores ilustrativos.",
                TipoRegla.COBERTURA_OBJETIVO, new TablaDecision(List.of(
                        f("0", "Libranza: respaldada por la pagaduría, sin exigencia de garantía real", "producto", "LIBRANZA"),
                        f("1.00", "Banca Empresas", "segmento", "BANCA_EMPRESAS"),
                        f("1.00", null, "producto", "in (HIPOTECARIO_VIVIENDA, VEHICULO, TARJETA_CREDITO, LIBRE_INVERSION)")),
                        new Salida("1.00", "Objetivo general")),
                List.of(caso("Capital de trabajo", "1.00", "producto", "CAPITAL_TRABAJO", "segmento", "BANCA_EMPRESAS"),
                        caso("Libranza", "0", "producto", "LIBRANZA", "segmento", "PERSONAS"),
                        caso("Hipotecario", "1", "producto", "HIPOTECARIO_VIVIENDA", "segmento", "PERSONAS"))));
        r.add(new NuevaRegla("DIST-GAR", "Método de distribución de garantías compartidas",
                "Cómo se reparte una garantía que respalda varias obligaciones (anexo A, A.2).",
                TipoRegla.METODO_DISTRIBUCION, new TablaDecision(List.of(
                        f("PRORRATA", "Garantías inmobiliarias empresariales se reparten por necesidad", "tipo", "HIPOTECA_NO_VIVIENDA", "segmento", "BANCA_EMPRESAS")),
                        new Salida("SECUENCIAL", "Por prioridad de la obligación")),
                List.of(caso("Hipoteca empresarial", "PRORRATA", "tipo", "HIPOTECA_NO_VIVIENDA", "segmento", "BANCA_EMPRESAS"),
                        caso("Vehículo", "SECUENCIAL", "tipo", "VEHICULO", "segmento", "PERSONAS"))));
        r.add(new NuevaRegla("EXC-GAR", "Asignación de excedente",
                "Si una garantía exclusiva puede reflejar cobertura superior al objetivo (anexo A, fase 4).",
                TipoRegla.ASIGNAR_EXCEDENTE, new TablaDecision(List.of(
                        f("true", "Depósitos: el excedente sigue respaldando la obligación", "tipo", "in (DEPOSITO_CDT, DEPOSITO_AHORRO, CESANTIAS_FNA)")),
                        new Salida("false", null)),
                List.of(caso("CDT", "true", "tipo", "DEPOSITO_CDT", "producto", "TARJETA_CREDITO"),
                        caso("Hipoteca", "false", "tipo", "HIPOTECA_VIVIENDA", "producto", "HIPOTECARIO_VIVIENDA"))));
        r.add(new NuevaRegla("PRIO-GAR", "Prioridad de uso de garantías",
                "Orden de uso: primero las más líquidas (anexo A, A.2).",
                TipoRegla.PRIORIDAD_GARANTIA, new TablaDecision(List.of(
                        f("10", null, "clase", "DEPOSITO"),
                        f("20", null, "clase", "FONDO_GARANTIAS"),
                        f("30", null, "clase", "REAL_INMUEBLE"),
                        f("35", null, "clase", "FIDUCIARIA"),
                        f("40", null, "clase", "VEHICULO"),
                        f("90", null, "clase", "PERSONAL")),
                        new Salida("50", null)),
                List.of(caso("CDT", "10", "tipo", "DEPOSITO_CDT", "clase", "DEPOSITO"))));
        r.add(new NuevaRegla("PER-VAL", "Periodicidad de valoración",
                "Meses entre valoraciones (anexo B, ejemplo 7). Vehículos: Fasecolda anual (D-06).",
                TipoRegla.PERIODICIDAD_VALORACION, new TablaDecision(List.of(
                        f("12", "Fasecolda anual", "tipo", "VEHICULO"),
                        f("6", null, "tipo", "DERECHOS_COBRO"),
                        f("36", "Ilustrativo: política de Riesgos pendiente (P-09)", "clase", "REAL_INMUEBLE")),
                        new Salida("12", null)),
                List.of(caso("Vehículo", "12", "tipo", "VEHICULO", "clase", "VEHICULO", "segmento", "PERSONAS"))));
        r.add(new NuevaRegla("IDON-GEN", "Idoneidad de la garantía",
                "Criterios del art. 2.1.2.1.3 del Decreto 2555/2010 y pignoración de cesantías solo para vivienda (anexo B, ejemplos 3 y 4).",
                TipoRegla.IDONEIDAD, new TablaDecision(List.of(
                        f("NO_IDONEA", "Garantía personal sin valor de realización", "tipo", "in (PAGARE, AVAL)"),
                        f("NO_IDONEA", "La pignoración de cesantías solo procede para crédito de vivienda (CST art. 256; Ley 50/1990 art. 104)",
                                "tipo", "CESANTIAS_FNA", "destinoCredito", "!= VIVIENDA"),
                        f("NO_IDONEA", "Concepto jurídico desfavorable", "estadoJuridico", "RECHAZADA"),
                        f("NO_IDONEA", "Sin concepto jurídico favorable", "estadoJuridico", "in (SIN_ESTUDIO, PENDIENTE, EN_ESTUDIO, CON_OBSERVACIONES)"),
                        f("CONDICIONADA", "Condicionamientos jurídicos pendientes", "condicionamientosAbiertos", "> 0"),
                        f("NO_IDONEA", "Garantía no perfeccionada (sin oponibilidad)", "perfeccionada", "false"),
                        f("NO_IDONEA", "Valoración vencida", "valoracionVigente", "false"),
                        f("NO_IDONEA", "Póliza obligatoria no vigente (Ley 546/1999)", "polizaRequeridaVigente", "false")),
                        new Salida("IDONEA", "Cumple los criterios del art. 2.1.2.1.3 del Decreto 2555/2010")),
                List.of(caso("Pagaré", "NO_IDONEA", "tipo", "PAGARE", "estadoJuridico", "APROBADA", "condicionamientosAbiertos", 0,
                                "perfeccionada", true, "valoracionVigente", true, "polizaRequeridaVigente", true, "destinoCredito", "LIBRE_INVERSION"),
                        caso("Cesantías para libranza", "NO_IDONEA", "tipo", "CESANTIAS_FNA", "estadoJuridico", "APROBADA",
                                "condicionamientosAbiertos", 0, "perfeccionada", true, "valoracionVigente", true,
                                "polizaRequeridaVigente", true, "destinoCredito", "LIBRE_INVERSION"),
                        caso("Cesantías para vivienda", "IDONEA", "tipo", "CESANTIAS_FNA", "estadoJuridico", "APROBADA",
                                "condicionamientosAbiertos", 0, "perfeccionada", true, "valoracionVigente", true,
                                "polizaRequeridaVigente", true, "destinoCredito", "VIVIENDA"),
                        caso("Hipoteca condicionada", "CONDICIONADA", "tipo", "HIPOTECA_VIVIENDA", "estadoJuridico", "CONDICIONADA",
                                "condicionamientosAbiertos", 2, "perfeccionada", true, "valoracionVigente", true,
                                "polizaRequeridaVigente", true, "destinoCredito", "VIVIENDA"))));
        return r;
    }
}
