package co.bancopopular.garantias360.carga;

import co.bancopopular.garantias360.auditoria.AuditoriaService;
import co.bancopopular.garantias360.carga.ArchivoCarga.Columna;
import co.bancopopular.garantias360.carga.ArchivoCarga.FilaLeida;
import co.bancopopular.garantias360.comun.Contexto;
import co.bancopopular.garantias360.comun.Errores;
import co.bancopopular.garantias360.comun.Json;
import co.bancopopular.garantias360.comun.TareasPosteriores;
import co.bancopopular.garantias360.configuracion.CampoDefinicion;
import co.bancopopular.garantias360.configuracion.TipoGarantiaService;
import co.bancopopular.garantias360.configuracion.TipoGarantiaService.TipoConVersion;
import co.bancopopular.garantias360.garantia.Garantia;
import co.bancopopular.garantias360.garantia.GarantiaDtos.*;
import co.bancopopular.garantias360.garantia.Macroestado;
import co.bancopopular.garantias360.garantia.Repositorios;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.Validator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Carga masiva con asistente de 4 pasos (RF-1703): (1) plantilla por tipo, (2) carga y validación
 * de estructura y de cada fila, (3) revisión del reporte de errores con confirmación aparte de las
 * filas que actualizan garantías existentes, y (4) aprobación maker–checker por un Director de
 * Operaciones distinto de quien la creó o la envió. El archivo no se conserva: se guarda su SHA-256
 * y el contenido normalizado de cada fila, con historial auditable.
 */
@Service
public class CargaMasivaService {

    private static final ZoneId BOGOTA = ZoneId.of("America/Bogota");
    private static final Pattern CODIGO = Pattern.compile("^[A-Z][A-Z0-9_]{1,49}$");
    private static final Pattern REFERENCIA = Pattern.compile("^[A-Za-z0-9._/-]{1,80}$");
    private static final DateTimeFormatter DMA = DateTimeFormatter.ofPattern("d/M/uuuu").withResolverStyle(ResolverStyle.STRICT);
    public static final String APLICATIVO_POR_DEFECTO = "CARGA_MASIVA";

    private final TipoGarantiaService tipos;
    private final Repositorios.Garantias garantias;
    private final AuditoriaService auditoria;
    private final JdbcTemplate jdbc;
    private final Validator validador;
    private final TareasPosteriores posteriores;
    private final ProcesadorCarga procesador;
    private final int maxBytes;
    private final int maxFilas;
    private final int maxSegundos;

    public CargaMasivaService(TipoGarantiaService tipos, Repositorios.Garantias garantias, AuditoriaService auditoria,
                              JdbcTemplate jdbc, Validator validador, TareasPosteriores posteriores,
                              ProcesadorCarga procesador,
                              @Value("${g360.carga.max-bytes:5242880}") int maxBytes,
                              @Value("${g360.carga.max-filas:5000}") int maxFilas,
                              @Value("${g360.carga.max-segundos-validacion:120}") int maxSegundos) {
        this.tipos = tipos;
        this.garantias = garantias;
        this.auditoria = auditoria;
        this.jdbc = jdbc;
        this.validador = validador;
        this.posteriores = posteriores;
        this.procesador = procesador;
        this.maxBytes = maxBytes;
        this.maxFilas = maxFilas;
        this.maxSegundos = maxSegundos;
    }

    record FilaValidada(int numero, String accion, String estado, ObjectNode datos, List<String> errores, String garantia) {
    }

    // ------------------------------------------------------------------ paso 1: plantilla

    public byte[] plantilla(String tipo) {
        return ArchivoCarga.plantilla(tipos.vigente(tipo));
    }

    public Map<String, Object> limites() {
        return Map.of("maxBytes", maxBytes, "maxFilas", maxFilas, "maxSegundosValidacion", maxSegundos,
                "formatos", List.of(".xlsx", ".csv"));
    }

    // ------------------------------------------------------------------ paso 2: carga y validación

    @Transactional
    public Map<String, Object> crear(String tipoCodigo, String nombreArchivo, byte[] contenido) {
        if (contenido == null || contenido.length == 0) {
            throw Errores.invalido("ARCHIVO_VACIO", "El archivo está vacío", List.of());
        }
        if (contenido.length > maxBytes) {
            throw Errores.invalido("ARCHIVO_GRANDE", "El archivo supera el tamaño máximo de " + (maxBytes / 1024 / 1024) + " MB", List.of());
        }
        TipoConVersion tipo = tipos.vigente(tipoCodigo);
        List<Columna> columnas = ArchivoCarga.columnas(tipo);
        ArchivoCarga.Lectura lectura = ArchivoCarga.leer(nombreArchivo, contenido, columnas, maxFilas);
        if (!lectura.erroresEstructura().isEmpty()) {
            throw Errores.invalido("ESTRUCTURA_INVALIDA", "El archivo no tiene la estructura de la plantilla de "
                    + tipo.tipo().nombre + " (versión " + tipo.version().numero + ")", lectura.erroresEstructura());
        }
        if (lectura.filas().isEmpty()) {
            throw Errores.invalido("SIN_FILAS", "El archivo no tiene filas de datos", List.of());
        }
        long limite = System.nanoTime() + maxSegundos * 1_000_000_000L;
        Set<String> referencias = new HashSet<>();
        Map<String, Integer> llaves = new HashMap<>();
        List<FilaValidada> filas = new ArrayList<>();
        for (FilaLeida f : lectura.filas()) {
            if (System.nanoTime() > limite) {
                throw Errores.invalido("TIEMPO_AGOTADO", "La validación superó el tiempo máximo de " + maxSegundos
                        + " s; divide el archivo en partes más pequeñas", List.of());
            }
            filas.add(validar(tipo, columnas, f, referencias, llaves));
        }
        UUID id = UUID.randomUUID();
        int nuevas = (int) filas.stream().filter(f -> "VALIDA".equals(f.estado()) && "CREAR".equals(f.accion())).count();
        int actualizaciones = (int) filas.stream().filter(f -> "VALIDA".equals(f.estado()) && "ACTUALIZAR".equals(f.accion())).count();
        int errores = (int) filas.stream().filter(f -> "ERROR".equals(f.estado())).count();
        String nombre = nombreArchivo == null ? "archivo" : nombreArchivo.replaceAll("[^A-Za-z0-9._ -]", "_");
        jdbc.update("""
                        insert into carga_masiva (id, tipo_codigo, tipo_version_id, archivo_nombre, archivo_hash, archivo_bytes, estado,
                                                  total_filas, filas_nuevas, filas_actualizacion, filas_error, creado_por, correlation_id)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                id, tipo.tipo().codigo, tipo.version().id, nombre, Json.sha256Bytes(contenido), contenido.length,
                errores > 0 ? "CON_ERRORES" : "VALIDADA", filas.size(), nuevas, actualizaciones, errores, Contexto.usuario(),
                Contexto.correlationId());
        jdbc.batchUpdate("""
                        insert into carga_masiva_fila (id, carga_id, numero, accion, estado, datos, errores, garantia_codigo)
                        values (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)""",
                filas.stream().map(f -> new Object[]{UUID.randomUUID(), id, f.numero(), f.accion(), f.estado(),
                        Json.canonico(f.datos()), Json.canonico(f.errores()), f.garantia()}).toList());
        Map<String, Object> carga = carga(id);
        auditoria.registrar("CREAR_CARGA_MASIVA", "CargaMasiva", String.valueOf(carga.get("numero")), null,
                Map.of("tipo", tipo.tipo().codigo, "versionTipo", tipo.version().numero, "archivo", nombre,
                        "hash", carga.get("archivoHash"), "filas", filas.size(), "nuevas", nuevas,
                        "actualizaciones", actualizaciones, "errores", errores), null);
        return detalle(id, null, 500);
    }

    private FilaValidada validar(TipoConVersion tipo, List<Columna> columnas, FilaLeida f, Set<String> referencias,
                                 Map<String, Integer> llaves) {
        List<String> errores = new ArrayList<>(f.errores());
        Map<String, String> v = f.valores();
        ObjectNode datos = Json.CANONICO.createObjectNode();
        datos.set("original", Json.arbol(v));
        String referencia = valor(v, "referencia");
        String aplicativo = Objects.requireNonNullElse(valor(v, "aplicativo"), APLICATIVO_POR_DEFECTO).toUpperCase(Locale.ROOT);
        if (referencia == null) {
            errores.add("'Referencia de la solicitud' es obligatoria");
        } else if (!REFERENCIA.matcher(referencia).matches()) {
            errores.add("'Referencia de la solicitud': solo letras, números y . _ / - (máx. 80)");
        } else if (!referencias.add(aplicativo + "|" + referencia)) {
            errores.add("La referencia " + referencia + " está repetida en el archivo");
        }
        if (!CODIGO.matcher(aplicativo).matches()) {
            errores.add("'Aplicativo de origen' debe ser un código en MAYÚSCULAS");
        }

        ObjectNode atributos = Json.CANONICO.createObjectNode();
        for (Columna c : columnas) {
            if (c.campo() == null) {
                continue;
            }
            String texto = valor(v, c.codigo());
            if (texto != null) {
                JsonNode convertido = convertir(c.campo(), texto, errores);
                if (convertido != null) {
                    atributos.set(c.codigo(), convertido);
                }
            }
        }
        BigDecimal gravamenes = numero(v, "gravamenesPrevios", "Gravámenes de mayor prelación", errores);
        if (gravamenes != null && gravamenes.signum() < 0) {
            errores.add("'Gravámenes de mayor prelación' no puede ser negativo");
        }
        ValoracionSolicitud valoracion = valoracion(v, errores);

        // ¿Actualiza una garantía existente? Por referencia del aplicativo o por la llave natural del tipo.
        Garantia existente = referencia == null ? null
                : garantias.findByAplicativoOrigenAndReferenciaExterna(aplicativo, referencia).orElse(null);
        String detectadaPor = existente == null ? null : "referencia";
        for (CampoDefinicion c : tipo.campos()) {
            if (!c.llave() || !atributos.hasNonNull(c.codigo())) {
                continue;
            }
            String llave = c.codigo() + "=" + atributos.get(c.codigo()).asText();
            Integer previa = llaves.putIfAbsent(llave, f.numero());
            if (previa != null) {
                errores.add("'" + c.etiqueta() + "' = " + atributos.get(c.codigo()).asText() + " se repite en la fila " + previa);
            }
            if (existente == null) {
                existente = garantias.porLlaveNatural(tipo.tipo().codigo, c.codigo(), atributos.get(c.codigo()).asText()).orElse(null);
                detectadaPor = existente == null ? null : "llave natural (" + c.etiqueta() + ")";
            }
        }

        if (existente != null) {
            return validarActualizacion(tipo, f, existente, detectadaPor, v, atributos, gravamenes, valoracion, datos, errores);
        }

        for (Columna c : columnas) {
            if (c.requeridaAlCrear() && c.campo() == null && valor(v, c.codigo()) == null && !"referencia".equals(c.codigo())) {
                errores.add("'" + c.etiqueta() + "' es obligatoria para crear la garantía");
            }
        }
        String tipoDocumento = mayus(valor(v, "clienteTipoDocumento"));
        if (tipoDocumento != null && !ArchivoCarga.TIPOS_DOCUMENTO.contains(tipoDocumento)) {
            errores.add("'Tipo de documento del cliente' no es válido: " + tipoDocumento);
        }
        String documento = valor(v, "clienteNumeroDocumento");
        if (documento != null && !documento.matches("^[0-9A-Za-z-]{3,20}$")) {
            errores.add("'Número de documento del cliente' solo admite dígitos, letras y guion, sin puntos");
        }
        String producto = mayus(valor(v, "producto"));
        String segmento = mayus(valor(v, "segmento"));
        for (String[] c : new String[][]{{"Producto", producto}, {"Segmento", segmento}}) {
            if (c[1] != null && !CODIGO.matcher(c[1]).matches()) {
                errores.add("'" + c[0] + "' debe ser un código en MAYÚSCULAS_CON_GUIONES");
            }
        }
        String moneda = Objects.requireNonNullElse(mayus(valor(v, "moneda")), "COP");
        if (!List.of("COP", "USD", "EUR").contains(moneda)) {
            errores.add("'Moneda' no soportada: " + moneda);
        }
        List<VinculoSolicitud> vinculos = new ArrayList<>();
        String obligacion = valor(v, "numeroObligacion");
        if (obligacion != null) {
            String tipoVinculo = Objects.requireNonNullElse(mayus(valor(v, "tipoVinculo")), "CERRADA");
            if (!List.of("ABIERTA", "CERRADA").contains(tipoVinculo)) {
                errores.add("'Tipo de vínculo' debe ser ABIERTA o CERRADA");
            }
            BigDecimal prioridad = numero(v, "prioridadVinculo", "Prioridad del vínculo", errores);
            BigDecimal tope = numero(v, "topeVinculo", "Tope del vínculo", errores);
            vinculos.add(new VinculoSolicitud(obligacion, tipoVinculo, tope, null,
                    prioridad == null ? null : prioridad.intValue(), producto, segmento, null));
        }
        errores.addAll(tipos.validarAtributos(tipo.campos(), atributos, Macroestado.REGISTRO));
        Cliente cliente = new Cliente(tipoDocumento, documento, valor(v, "clienteNombre"));
        RegistroGarantia registro = new RegistroGarantia(tipo.tipo().codigo, new Origen(aplicativo, referencia), cliente,
                producto, segmento, moneda, gravamenes, atributos,
                documento == null ? List.of() : List.of(new ParticipanteSolicitud("PROPIETARIO", tipoDocumento, documento,
                        valor(v, "clienteNombre"), new BigDecimal("100"))),
                vinculos, valoracion);
        if (errores.isEmpty()) {
            validador.validate(registro).forEach(x -> errores.add("'" + x.getPropertyPath() + "': " + x.getMessage()));
        }
        datos.set("registro", Json.arbol(registro));
        return new FilaValidada(f.numero(), "CREAR", errores.isEmpty() ? "VALIDA" : "ERROR", datos, errores, null);
    }

    private FilaValidada validarActualizacion(TipoConVersion tipo, FilaLeida f, Garantia g, String detectadaPor,
                                              Map<String, String> v, ObjectNode atributos, BigDecimal gravamenes,
                                              ValoracionSolicitud valoracion, ObjectNode datos, List<String> errores) {
        if (!g.tipoCodigo.equals(tipo.tipo().codigo)) {
            errores.add("La referencia corresponde a " + g.codigo + ", que es de tipo " + g.tipoCodigo + ", no " + tipo.tipo().codigo);
        }
        if (g.macroestado == Macroestado.CIERRE || g.macroestado == Macroestado.ANULADA) {
            errores.add(g.codigo + " está en " + g.macroestado + " y no se puede actualizar");
        }
        String documento = valor(v, "clienteNumeroDocumento");
        if (documento != null && !documento.equals(g.clienteDocumento)) {
            errores.add("El documento del cliente (" + documento + ") no coincide con el de " + g.codigo + " (" + g.clienteDocumento + ")");
        }
        if (errores.isEmpty()) {
            var version = tipos.porVersion(g.tipoVersionId);
            ObjectNode combinados = g.atributos == null ? Json.CANONICO.createObjectNode() : ((ObjectNode) g.atributos).deepCopy();
            combinados.setAll(atributos);
            errores.addAll(tipos.validarAtributos(version.campos(), combinados, g.macroestado));
        }
        ObjectNode act = Json.CANONICO.createObjectNode();
        act.put("garantia", g.codigo);
        act.put("detectadaPor", detectadaPor);
        act.set("atributos", atributos);
        if (gravamenes != null) {
            act.put("gravamenesPrevios", gravamenes);
        }
        if (valoracion != null) {
            act.set("valoracion", Json.arbol(valoracion));
        }
        datos.set("actualizacion", act);
        boolean sinCambios = atributos.isEmpty() && gravamenes == null && valoracion == null;
        if (sinCambios && errores.isEmpty()) {
            return new FilaValidada(f.numero(), "NINGUNA", "OMITIDA", datos,
                    List.of("La fila no trae datos para actualizar " + g.codigo), g.codigo);
        }
        return new FilaValidada(f.numero(), "ACTUALIZAR", errores.isEmpty() ? "VALIDA" : "ERROR", datos, errores, g.codigo);
    }

    private ValoracionSolicitud valoracion(Map<String, String> v, List<String> errores) {
        BigDecimal valorComercial = numero(v, "valorComercial", "Valor comercial", errores);
        if (valorComercial == null) {
            return null;
        }
        if (valorComercial.signum() < 0) {
            errores.add("'Valor comercial' no puede ser negativo");
        }
        LocalDate fecha = fecha(valor(v, "fechaValoracion"), "Fecha de la valoración", errores);
        if (fecha == null) {
            errores.add("'Fecha de la valoración' es obligatoria cuando se informa el valor comercial");
        } else if (fecha.isAfter(LocalDate.now(BOGOTA))) {
            errores.add("'Fecha de la valoración' no puede ser futura");
        }
        String tipo = Objects.requireNonNullElse(mayus(valor(v, "tipoValoracion")), "AVALUO_COMERCIAL");
        if (!ArchivoCarga.TIPOS_VALORACION.contains(tipo)) {
            errores.add("'Tipo de valoración' no es válido: " + tipo);
        }
        return new ValoracionSolicitud(tipo, fecha, valorComercial, null, null, valor(v, "perito"), null, null, null, null,
                "Carga masiva");
    }

    // ------------------------------------------------------------------ conversión de celdas

    private static String valor(Map<String, String> v, String codigo) {
        String s = v.get(codigo);
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String mayus(String s) {
        return s == null ? null : s.toUpperCase(Locale.ROOT);
    }

    static BigDecimal numero(String texto) {
        String t = texto.replace(" ", "").replace("$", "");
        if (t.contains(".") && t.contains(",")) {
            throw new NumberFormatException("separadores mixtos");
        }
        return new BigDecimal(t.replace(',', '.'));
    }

    private static BigDecimal numero(Map<String, String> v, String codigo, String etiqueta, List<String> errores) {
        String t = valor(v, codigo);
        if (t == null) {
            return null;
        }
        try {
            return numero(t);
        } catch (NumberFormatException e) {
            errores.add("'" + etiqueta + "' debe ser numérico sin separador de miles (valor: " + t + ")");
            return null;
        }
    }

    private static LocalDate fecha(String t, String etiqueta, List<String> errores) {
        if (t == null) {
            return null;
        }
        try {
            return t.contains("/") ? LocalDate.parse(t, DMA) : LocalDate.parse(t.length() > 10 ? t.substring(0, 10) : t);
        } catch (DateTimeParseException e) {
            errores.add("'" + etiqueta + "' debe ser una fecha AAAA-MM-DD (valor: " + t + ")");
            return null;
        }
    }

    private static JsonNode convertir(CampoDefinicion c, String texto, List<String> errores) {
        var f = Json.CANONICO.getNodeFactory();
        switch (c.tipo()) {
            case NUMERO, MONEDA -> {
                try {
                    return f.numberNode(numero(texto));
                } catch (NumberFormatException e) {
                    errores.add("'" + c.etiqueta() + "' debe ser numérico sin separador de miles (valor: " + texto + ")");
                    return null;
                }
            }
            case FECHA -> {
                LocalDate d = fecha(texto, c.etiqueta(), errores);
                return d == null ? null : f.textNode(d.toString());
            }
            case BOOLEANO -> {
                String t = texto.toUpperCase(Locale.ROOT);
                if (Set.of("SI", "SÍ", "S", "TRUE", "VERDADERO", "1", "X").contains(t)) {
                    return f.booleanNode(true);
                }
                if (Set.of("NO", "N", "FALSE", "FALSO", "0").contains(t)) {
                    return f.booleanNode(false);
                }
                errores.add("'" + c.etiqueta() + "' debe ser SI o NO (valor: " + texto + ")");
                return null;
            }
            case LISTA -> {
                return f.textNode(opcion(c, texto));
            }
            case LISTA_MULTIPLE -> {
                ArrayNode a = f.arrayNode();
                Arrays.stream(texto.split("[|;,]")).map(String::trim).filter(s -> !s.isEmpty()).forEach(s -> a.add(opcion(c, s)));
                return a;
            }
            default -> {
                return f.textNode(texto);
            }
        }
    }

    /** Acepta el identificador o la etiqueta de la opción, sin distinguir mayúsculas. */
    private static String opcion(CampoDefinicion c, String texto) {
        return c.opciones().stream()
                .filter(o -> o.id().equalsIgnoreCase(texto) || o.etiqueta().equalsIgnoreCase(texto))
                .map(CampoDefinicion.Opcion::id).findFirst().orElse(texto);
    }

    // ------------------------------------------------------------------ pasos 3 y 4: envío y aprobación

    @Transactional
    public Map<String, Object> enviar(UUID id, boolean incluirActualizaciones, boolean excluirErrores) {
        Map<String, Object> c = bloquear(id, "VALIDADA", "CON_ERRORES");
        int errores = n(c, "filas_error");
        int nuevas = n(c, "filas_nuevas");
        int actualizaciones = n(c, "filas_actualizacion");
        if (errores > 0 && !excluirErrores) {
            throw Errores.invalido("FILAS_CON_ERROR", "La carga tiene " + errores + " filas con error: corrígelas y vuelve a "
                    + "cargar el archivo, o confirma que se excluyen", List.of());
        }
        if (actualizaciones > 0 && !incluirActualizaciones && nuevas == 0) {
            throw Errores.invalido("SIN_FILAS_PROCESABLES", "Todas las filas válidas actualizan garantías existentes: "
                    + "confirma las actualizaciones para enviarla", List.of());
        }
        if (nuevas + (incluirActualizaciones ? actualizaciones : 0) == 0) {
            throw Errores.invalido("SIN_FILAS_PROCESABLES", "La carga no tiene filas válidas para procesar", List.of());
        }
        jdbc.update("""
                        update carga_masiva set estado = 'EN_APROBACION', incluir_actualizaciones = ?, enviado_por = ?, enviada_en = now()
                        where id = ?""", incluirActualizaciones, Contexto.usuario(), id);
        auditoria.registrar("ENVIAR_CARGA_MASIVA", "CargaMasiva", String.valueOf(c.get("numero")), null,
                Map.of("nuevas", nuevas, "actualizaciones", incluirActualizaciones ? actualizaciones : 0,
                        "excluidasPorError", errores, "actualizacionesConfirmadas", incluirActualizaciones), null);
        return carga(id);
    }

    @Transactional
    public Map<String, Object> aprobar(UUID id) {
        Map<String, Object> c = bloquear(id, "EN_APROBACION");
        exigirOtroUsuario(c);
        jdbc.update("update carga_masiva set estado = 'EN_PROCESO', aprobado_por = ?, decidida_en = now() where id = ?",
                Contexto.usuario(), id);
        auditoria.registrar("APROBAR_CARGA_MASIVA", "CargaMasiva", String.valueOf(c.get("numero")), null,
                Map.of("creadoPor", c.get("creado_por"), "enviadoPor", c.get("enviado_por")), null);
        posteriores.enSegundoPlano(() -> procesador.procesar(id));
        return carga(id);
    }

    @Transactional
    public Map<String, Object> rechazar(UUID id, String motivo) {
        if (motivo == null || motivo.isBlank()) {
            throw Errores.invalido("MOTIVO_REQUERIDO", "El rechazo exige un motivo", List.of());
        }
        Map<String, Object> c = bloquear(id, "EN_APROBACION");
        exigirOtroUsuario(c);
        jdbc.update("update carga_masiva set estado = 'RECHAZADA', aprobado_por = ?, decidida_en = now(), motivo = ? where id = ?",
                Contexto.usuario(), motivo, id);
        auditoria.registrar("RECHAZAR_CARGA_MASIVA", "CargaMasiva", String.valueOf(c.get("numero")), null, null, motivo);
        return carga(id);
    }

    @Transactional
    public Map<String, Object> cancelar(UUID id, String motivo) {
        Map<String, Object> c = bloquear(id, "VALIDADA", "CON_ERRORES", "EN_APROBACION");
        if (!Contexto.usuario().equals(c.get("creado_por"))) {
            throw Errores.prohibido("SOLO_CREADOR", "Solo quien creó la carga puede cancelarla; el aprobador la rechaza");
        }
        jdbc.update("update carga_masiva set estado = 'CANCELADA', motivo = ?, decidida_en = now() where id = ?", motivo, id);
        auditoria.registrar("CANCELAR_CARGA_MASIVA", "CargaMasiva", String.valueOf(c.get("numero")), null, null, motivo);
        return carga(id);
    }

    private static void exigirOtroUsuario(Map<String, Object> c) {
        String yo = Contexto.usuario();
        if (yo.equals(c.get("creado_por")) || yo.equals(c.get("enviado_por"))) {
            throw Errores.prohibido("MAKER_CHECKER", "Quien creó o envió la carga (" + c.get("creado_por")
                    + ") no puede aprobarla ni rechazarla");
        }
    }

    private Map<String, Object> bloquear(UUID id, String... estados) {
        List<Map<String, Object>> r = jdbc.queryForList("select * from carga_masiva where id = ? for update", id);
        if (r.isEmpty()) {
            throw Errores.noEncontrado("La carga masiva " + id);
        }
        Map<String, Object> c = r.getFirst();
        if (!Arrays.asList(estados).contains((String) c.get("estado"))) {
            throw Errores.conflicto("ESTADO_CARGA", "La carga está en " + c.get("estado") + "; la operación exige "
                    + String.join(" o ", estados));
        }
        return c;
    }

    private static int n(Map<String, Object> m, String k) {
        return ((Number) m.get(k)).intValue();
    }

    // ------------------------------------------------------------------ consultas

    private static final String VISTA = """
            select c.id, c.numero, c.tipo_codigo as "tipo", t.nombre as "tipoNombre", v.numero as "versionTipo",
                   c.archivo_nombre as "archivoNombre", c.archivo_hash as "archivoHash", c.archivo_bytes as "archivoBytes",
                   c.estado, c.total_filas as "totalFilas", c.filas_nuevas as "filasNuevas",
                   c.filas_actualizacion as "filasActualizacion", c.filas_error as "filasError",
                   c.filas_procesadas as "filasProcesadas", c.filas_fallidas as "filasFallidas",
                   c.incluir_actualizaciones as "incluirActualizaciones", c.creado_por as "creadoPor",
                   c.enviado_por as "enviadoPor", c.aprobado_por as "aprobadoPor", c.motivo, c.created_at as "creadaEn",
                   c.enviada_en as "enviadaEn", c.decidida_en as "decididaEn", c.procesada_en as "procesadaEn",
                   c.correlation_id as "correlationId"
            from carga_masiva c join tipo_garantia t on t.codigo = c.tipo_codigo
                 join tipo_garantia_version v on v.id = c.tipo_version_id""";

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listar(String estado, int limite) {
        List<Object> args = new ArrayList<>();
        String sql = VISTA;
        if (estado != null && !estado.isBlank()) {
            sql += " where c.estado = ?";
            args.add(estado);
        }
        args.add(Math.min(Math.max(limite, 1), 200));
        return jdbc.queryForList(sql + " order by c.created_at desc limit ?", args.toArray());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> carga(UUID id) {
        List<Map<String, Object>> r = jdbc.queryForList(VISTA + " where c.id = ?", id);
        if (r.isEmpty()) {
            throw Errores.noEncontrado("La carga masiva " + id);
        }
        return r.getFirst();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detalle(UUID id, String estadoFila, int limite) {
        Map<String, Object> d = new LinkedHashMap<>(carga(id));
        List<Object> args = new ArrayList<>(List.of(id));
        String sql = "select numero, accion, estado, datos, errores, garantia_codigo as \"garantia\" from carga_masiva_fila where carga_id = ?";
        if (estadoFila != null && !estadoFila.isBlank()) {
            sql += " and estado = ?";
            args.add(estadoFila);
        }
        args.add(Math.min(Math.max(limite, 1), 5000));
        d.put("filas", jdbc.query(sql + " order by numero limit ?", (rs, i) -> {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("numero", rs.getInt("numero"));
            f.put("accion", rs.getString("accion"));
            f.put("estado", rs.getString("estado"));
            JsonNode datos = leer(rs.getString("datos"));
            f.put("original", datos.get("original"));
            f.put("detectadaPor", datos.path("actualizacion").path("detectadaPor").asText(null));
            f.put("errores", leer(rs.getString("errores")));
            f.put("garantia", rs.getString("garantia"));
            return f;
        }, args.toArray()));
        return d;
    }

    /** Reporte por fila en CSV (;), con celdas neutralizadas contra inyección de fórmulas. */
    @Transactional(readOnly = true)
    public byte[] reporte(UUID id, boolean soloErrores) {
        Map<String, Object> c = carga(id);
        StringBuilder sb = new StringBuilder("﻿");
        sb.append("Carga masiva #").append(c.get("numero")).append(" · ").append(c.get("tipoNombre"))
                .append(" · archivo ").append(celda(String.valueOf(c.get("archivoNombre")))).append('\n');
        sb.append("fila;referencia;accion;estado;garantia;errores\n");
        jdbc.query("select numero, accion, estado, datos, errores, garantia_codigo from carga_masiva_fila where carga_id = ?"
                        + (soloErrores ? " and estado in ('ERROR', 'FALLIDA')" : "") + " order by numero",
                rs -> {
                    JsonNode datos = leer(rs.getString("datos"));
                    List<String> errs = new ArrayList<>();
                    leer(rs.getString("errores")).forEach(e -> errs.add(e.asText()));
                    sb.append(rs.getInt("numero")).append(';')
                            .append(celda(datos.path("original").path("referencia").asText(""))).append(';')
                            .append(rs.getString("accion")).append(';')
                            .append(rs.getString("estado")).append(';')
                            .append(celda(Objects.requireNonNullElse(rs.getString("garantia_codigo"), ""))).append(';')
                            .append(celda(String.join(" | ", errs))).append('\n');
                }, id);
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String celda(String v) {
        String t = v.replace("\"", "\"\"");
        if (ArchivoCarga.inyeccion(t)) {
            t = "'" + t;
        }
        return "\"" + t + "\"";
    }

    static JsonNode leer(String json) {
        try {
            return Json.CANONICO.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
