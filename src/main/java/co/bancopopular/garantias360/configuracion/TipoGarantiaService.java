package co.bancopopular.garantias360.configuracion;

import co.bancopopular.garantias360.auditoria.AuditoriaService;
import co.bancopopular.garantias360.comun.Contexto;
import co.bancopopular.garantias360.comun.Errores;
import co.bancopopular.garantias360.comun.Json;
import co.bancopopular.garantias360.configuracion.CampoDefinicion.TipoCampo;
import co.bancopopular.garantias360.garantia.Macroestado;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Tipos de garantía, sus versiones y la validación de los campos personalizados (M16). */
@Service
public class TipoGarantiaService {

    private static final Set<String> RESERVADOS = Set.of("id", "codigo", "tipo", "estado", "macroestado", "cliente",
            "valorComercial", "valorAdmisible", "valorNeto", "moneda", "producto", "segmento");
    private static final Pattern CODIGO = Pattern.compile("^[a-z][A-Za-z0-9]{1,49}$");

    private final TipoGarantiaRepositorio tipos;
    private final TipoGarantiaRepositorio.Versiones versiones;
    private final AuditoriaService auditoria;

    public TipoGarantiaService(TipoGarantiaRepositorio tipos, TipoGarantiaRepositorio.Versiones versiones,
                               AuditoriaService auditoria) {
        this.tipos = tipos;
        this.versiones = versiones;
        this.auditoria = auditoria;
    }

    public record TipoConVersion(TipoGarantia tipo, TipoGarantiaVersion version, List<CampoDefinicion> campos) {
    }

    public record NuevoTipo(String codigo, String nombre, String clase, boolean requiereAvaluo, boolean requierePoliza,
                            String registroPublico, boolean admiteMultiples, List<CampoDefinicion> campos) {
    }

    @Transactional(readOnly = true)
    public List<TipoConVersion> listar() {
        return tipos.findAllByOrderByNombre().stream()
                .map(t -> versiones.publicada(t.id).map(v -> new TipoConVersion(t, v, campos(v))).orElse(null))
                .filter(Objects::nonNull)
                .toList();
    }

    @Transactional(readOnly = true)
    public TipoConVersion vigente(String codigo) {
        TipoGarantia t = tipos.findByCodigo(codigo)
                .orElseThrow(() -> Errores.noEncontrado("El tipo de garantía " + codigo));
        if (!t.activo) {
            throw Errores.conflicto("TIPO_INACTIVO", "El tipo de garantía " + codigo + " está inactivo");
        }
        TipoGarantiaVersion v = versiones.publicada(t.id)
                .orElseThrow(() -> Errores.conflicto("TIPO_SIN_VERSION", "El tipo " + codigo + " no tiene una versión publicada"));
        return new TipoConVersion(t, v, campos(v));
    }

    @Transactional(readOnly = true)
    public TipoConVersion porVersion(UUID versionId) {
        TipoGarantiaVersion v = versiones.findById(versionId).orElseThrow(() -> Errores.noEncontrado("La versión de tipo"));
        TipoGarantia t = tipos.findById(v.tipoId).orElseThrow();
        return new TipoConVersion(t, v, campos(v));
    }

    /**
     * Crea el tipo con su versión 1 publicada. En el incremento 1 la publicación es directa y
     * auditada; el maker–checker de configuración (RF-1607) llega con la UI de M16.
     */
    @Transactional
    public TipoConVersion crear(NuevoTipo nuevo) {
        if (tipos.findByCodigo(nuevo.codigo()).isPresent()) {
            throw Errores.conflicto("TIPO_EXISTE", "Ya existe el tipo " + nuevo.codigo());
        }
        List<String> errores = validarDefinicion(nuevo.campos());
        if (!errores.isEmpty()) {
            throw Errores.invalido("CAMPOS_INVALIDOS", "La definición de campos tiene errores", errores);
        }
        TipoGarantia t = new TipoGarantia();
        t.id = UUID.randomUUID();
        t.codigo = nuevo.codigo();
        t.nombre = nuevo.nombre();
        t.clase = nuevo.clase();
        t.requiereAvaluo = nuevo.requiereAvaluo();
        t.requierePoliza = nuevo.requierePoliza();
        t.registroPublico = Objects.requireNonNullElse(nuevo.registroPublico(), "NINGUNO");
        t.admiteMultiples = nuevo.admiteMultiples();
        tipos.save(t);
        TipoGarantiaVersion v = new TipoGarantiaVersion();
        v.id = UUID.randomUUID();
        v.tipoId = t.id;
        v.numero = 1;
        v.campos = Json.arbol(nuevo.campos());
        v.estado = "PUBLICADA";
        v.creadoPor = Contexto.usuario();
        versiones.save(v);
        auditoria.registrar("CREAR_TIPO_GARANTIA", "TipoGarantia", t.codigo, null,
                Map.of("tipo", t.codigo, "version", 1, "campos", v.campos), null);
        return new TipoConVersion(t, v, nuevo.campos());
    }

    public List<CampoDefinicion> campos(TipoGarantiaVersion v) {
        return v.campos == null ? List.of() : Arrays.asList(Json.leer(v.campos, CampoDefinicion[].class));
    }

    /** Errores de la definición de campos (RF-1603). */
    public List<String> validarDefinicion(List<CampoDefinicion> campos) {
        List<String> errores = new ArrayList<>();
        Set<String> vistos = new HashSet<>();
        for (CampoDefinicion c : Objects.requireNonNullElse(campos, List.<CampoDefinicion>of())) {
            String p = "Campo '" + c.codigo() + "': ";
            if (c.codigo() == null || !CODIGO.matcher(c.codigo()).matches()) {
                errores.add(p + "el código debe ser camelCase alfanumérico");
            } else if (RESERVADOS.contains(c.codigo())) {
                errores.add(p + "el código está reservado para un campo transversal");
            } else if (!vistos.add(c.codigo())) {
                errores.add(p + "código duplicado");
            }
            if (c.tipo() == null) {
                errores.add(p + "falta el tipo de dato");
            }
            if ((c.tipo() == TipoCampo.LISTA || c.tipo() == TipoCampo.LISTA_MULTIPLE)
                    && (c.opciones() == null || c.opciones().isEmpty())) {
                errores.add(p + "una lista necesita opciones");
            }
            if (c.obligatorioDesde() != null) {
                try {
                    Macroestado.valueOf(c.obligatorioDesde());
                } catch (IllegalArgumentException e) {
                    errores.add(p + "obligatorioDesde no es un macroestado válido");
                }
            }
            if (c.patron() != null) {
                try {
                    Pattern.compile(c.patron());
                } catch (PatternSyntaxException e) {
                    errores.add(p + "patrón inválido");
                }
            }
        }
        return errores;
    }

    /**
     * Valida los atributos de una garantía contra los campos de su versión para el macroestado
     * dado: tipos, rangos, patrones, opciones activas y obligatoriedad por estado (RF-1604).
     */
    public List<String> validarAtributos(List<CampoDefinicion> campos, JsonNode atributos, Macroestado estado) {
        List<String> errores = new ArrayList<>();
        Map<String, CampoDefinicion> porCodigo = new HashMap<>();
        campos.forEach(c -> porCodigo.put(c.codigo(), c));
        if (atributos != null) {
            atributos.fieldNames().forEachRemaining(n -> {
                if (!porCodigo.containsKey(n)) {
                    errores.add("'" + n + "' no es un campo del tipo de garantía");
                }
            });
        }
        for (CampoDefinicion c : campos) {
            JsonNode valor = atributos == null ? null : atributos.get(c.codigo());
            boolean vacio = valor == null || valor.isNull() || (valor.isTextual() && valor.asText().isBlank());
            if (vacio) {
                if (c.obligatorio() && exigibleEn(c, estado)) {
                    errores.add("'" + c.etiqueta() + "' es obligatorio" + (c.obligatorioDesde() == null ? ""
                            : " desde " + c.obligatorioDesde()));
                }
                continue;
            }
            validarValor(c, valor, errores);
        }
        return errores;
    }

    private static boolean exigibleEn(CampoDefinicion c, Macroestado estado) {
        if (c.obligatorioDesde() == null) {
            return true;
        }
        return estado.ordinal() >= Macroestado.valueOf(c.obligatorioDesde()).ordinal() && estado != Macroestado.ANULADA;
    }

    private static void validarValor(CampoDefinicion c, JsonNode valor, List<String> errores) {
        String e = "'" + c.etiqueta() + "': ";
        switch (c.tipo()) {
            case NUMERO, MONEDA -> {
                BigDecimal n;
                try {
                    n = valor.isNumber() ? valor.decimalValue() : new BigDecimal(valor.asText());
                } catch (NumberFormatException ex) {
                    errores.add(e + "debe ser numérico");
                    return;
                }
                if (c.minimo() != null && n.compareTo(c.minimo()) < 0) {
                    errores.add(e + "debe ser mayor o igual a " + c.minimo());
                }
                if (c.maximo() != null && n.compareTo(c.maximo()) > 0) {
                    errores.add(e + "debe ser menor o igual a " + c.maximo());
                }
            }
            case FECHA -> {
                try {
                    LocalDate.parse(valor.asText());
                } catch (DateTimeParseException ex) {
                    errores.add(e + "debe ser una fecha AAAA-MM-DD");
                }
            }
            case BOOLEANO -> {
                if (!valor.isBoolean()) {
                    errores.add(e + "debe ser verdadero o falso");
                }
            }
            case LISTA -> validarOpcion(c, valor.asText(), errores);
            case LISTA_MULTIPLE -> {
                if (!valor.isArray()) {
                    errores.add(e + "debe ser una lista");
                } else {
                    valor.forEach(v -> validarOpcion(c, v.asText(), errores));
                }
            }
            case TEXTO, TEXTO_LARGO -> {
                if (c.patron() != null && !Pattern.matches(c.patron(), valor.asText())) {
                    errores.add(e + "no tiene el formato esperado");
                }
                if (c.tipo() == TipoCampo.TEXTO && valor.asText().length() > 200) {
                    errores.add(e + "supera 200 caracteres");
                }
            }
        }
    }

    private static void validarOpcion(CampoDefinicion c, String id, List<String> errores) {
        boolean valida = c.opciones().stream().anyMatch(o -> o.id().equals(id) && o.activo());
        if (!valida) {
            errores.add("'" + c.etiqueta() + "': la opción '" + id + "' no existe o está inactiva");
        }
    }
}
