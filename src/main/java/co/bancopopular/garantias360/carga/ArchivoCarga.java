package co.bancopopular.garantias360.carga;

import co.bancopopular.garantias360.comun.Errores;
import co.bancopopular.garantias360.configuracion.CampoDefinicion;
import co.bancopopular.garantias360.configuracion.CampoDefinicion.TipoCampo;
import co.bancopopular.garantias360.configuracion.TipoGarantiaService.TipoConVersion;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Plantilla y lectura de archivos de carga masiva (RF-1703, RF-1609). La plantilla se genera desde
 * la versión publicada del tipo: columnas transversales + campos personalizados. Se leen .xlsx y
 * .csv (separador ; o ,). Las fórmulas se neutralizan: nunca se evalúan y la celda se rechaza.
 */
public final class ArchivoCarga {

    private ArchivoCarga() {
    }

    /** Columna de la plantilla. {@code campo} es null en las columnas transversales. */
    public record Columna(String codigo, String etiqueta, boolean requeridaAlCrear, String formato, String ayuda,
                          CampoDefinicion campo) {
    }

    /** Fila leída: número de fila en el archivo (1 = primera fila) y valores por código de columna. */
    public record FilaLeida(int numero, Map<String, String> valores, List<String> errores) {
    }

    public record Lectura(List<FilaLeida> filas, List<String> erroresEstructura) {
    }

    public static final List<String> TIPOS_DOCUMENTO = List.of("CC", "CE", "NIT", "PAS", "TI", "PEP");
    public static final List<String> TIPOS_VALORACION = List.of("AVALUO_COMERCIAL", "AVALUO_CATASTRAL", "GUIA_FASECOLDA",
            "INDICE", "SALDO_CERTIFICADO", "VALOR_CONTRATO", "VALOR_DERECHOS_FIDUCIARIOS");

    public static List<Columna> columnas(TipoConVersion tipo) {
        List<Columna> c = new ArrayList<>();
        c.add(new Columna("referencia", "Referencia de la solicitud", true, "Texto",
                "Única por aplicativo. Si ya existe una garantía con esta referencia, la fila la actualiza.", null));
        c.add(new Columna("aplicativo", "Aplicativo de origen", false, "Texto", "Por defecto CARGA_MASIVA", null));
        c.add(new Columna("clienteTipoDocumento", "Tipo de documento del cliente", true, String.join(" | ", TIPOS_DOCUMENTO), null, null));
        c.add(new Columna("clienteNumeroDocumento", "Número de documento del cliente", true, "Texto (sin puntos)", null, null));
        c.add(new Columna("clienteNombre", "Nombre o razón social del cliente", true, "Texto", null, null));
        c.add(new Columna("producto", "Producto", true, "Código", "HIPOTECARIO_VIVIENDA, VEHICULO, LIBRANZA, TARJETA_CREDITO, CAPITAL_TRABAJO…", null));
        c.add(new Columna("segmento", "Segmento", true, "Código", "PERSONAS, BANCA_EMPRESAS…", null));
        c.add(new Columna("moneda", "Moneda", false, "COP | USD | EUR", "Por defecto COP", null));
        c.add(new Columna("gravamenesPrevios", "Gravámenes de mayor prelación", false, "Número", "Valor comprometido con otros acreedores", null));
        c.add(new Columna("numeroObligacion", "Número de obligación (Flexcube)", false, "Texto", "Obligación que respalda al crear", null));
        c.add(new Columna("tipoVinculo", "Tipo de vínculo", false, "ABIERTA | CERRADA", "Por defecto CERRADA", null));
        c.add(new Columna("prioridadVinculo", "Prioridad del vínculo", false, "Entero", "Menor = se usa primero. Por defecto 100", null));
        c.add(new Columna("topeVinculo", "Tope del vínculo", false, "Número", "Solo para garantías abiertas con cupo", null));
        c.add(new Columna("valorComercial", "Valor comercial", false, "Número", "Si se informa, registra una valoración", null));
        c.add(new Columna("fechaValoracion", "Fecha de la valoración", false, "AAAA-MM-DD", "Obligatoria si hay valor comercial", null));
        c.add(new Columna("tipoValoracion", "Tipo de valoración", false, String.join(" | ", TIPOS_VALORACION), "Por defecto AVALUO_COMERCIAL", null));
        c.add(new Columna("perito", "Perito o proveedor", false, "Texto", null, null));
        tipo.campos().stream().sorted(Comparator.comparingInt(CampoDefinicion::orden)).forEach(f -> c.add(new Columna(
                f.codigo(), f.etiqueta(), f.obligatorio() && f.obligatorioDesde() == null, formato(f),
                ayuda(f), f)));
        return c;
    }

    private static String formato(CampoDefinicion f) {
        return switch (f.tipo()) {
            case NUMERO, MONEDA -> "Número" + (f.minimo() != null ? " ≥ " + f.minimo().toPlainString() : "")
                    + (f.maximo() != null ? " ≤ " + f.maximo().toPlainString() : "");
            case FECHA -> "AAAA-MM-DD";
            case BOOLEANO -> "SI | NO";
            case LISTA -> activas(f);
            case LISTA_MULTIPLE -> "Varias, separadas por | : " + activas(f);
            case TEXTO -> "Texto (máx. 200)";
            case TEXTO_LARGO -> "Texto";
        };
    }

    private static String activas(CampoDefinicion f) {
        return f.opciones().stream().filter(CampoDefinicion.Opcion::activo).map(CampoDefinicion.Opcion::id)
                .collect(Collectors.joining(" | "));
    }

    private static String ayuda(CampoDefinicion f) {
        List<String> partes = new ArrayList<>();
        if (f.ayuda() != null) {
            partes.add(f.ayuda());
        }
        if (f.obligatorio() && f.obligatorioDesde() != null) {
            partes.add("Obligatorio desde " + f.obligatorioDesde());
        }
        if (f.llave()) {
            partes.add("Llave natural: detecta duplicados");
        }
        return partes.isEmpty() ? null : String.join(". ", partes);
    }

    // ------------------------------------------------------------------ plantilla .xlsx

    public static byte[] plantilla(TipoConVersion tipo) {
        List<Columna> columnas = columnas(tipo);
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet hoja = wb.createSheet("Garantias");
            DataFormat formato = wb.createDataFormat();
            Font negrita = wb.createFont();
            negrita.setBold(true);
            negrita.setColor(IndexedColors.WHITE.getIndex());
            CellStyle titulo = wb.createCellStyle();
            titulo.setFont(negrita);
            titulo.setFillForegroundColor(IndexedColors.DARK_GREEN.getIndex());
            titulo.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            titulo.setWrapText(true);
            titulo.setVerticalAlignment(VerticalAlignment.CENTER);
            Font gris = wb.createFont();
            gris.setItalic(true);
            gris.setColor(IndexedColors.GREY_50_PERCENT.getIndex());
            CellStyle codigo = wb.createCellStyle();
            codigo.setFont(gris);
            codigo.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            codigo.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            CellStyle texto = wb.createCellStyle();
            texto.setDataFormat(formato.getFormat("@"));

            Row r0 = hoja.createRow(0);
            Row r1 = hoja.createRow(1);
            r0.setHeightInPoints(34);
            DataValidationHelper dv = hoja.getDataValidationHelper();
            for (int i = 0; i < columnas.size(); i++) {
                Columna c = columnas.get(i);
                Cell a = r0.createCell(i);
                a.setCellValue(c.etiqueta() + (c.requeridaAlCrear() ? " *" : ""));
                a.setCellStyle(titulo);
                Cell b = r1.createCell(i);
                b.setCellValue(c.codigo());
                b.setCellStyle(codigo);
                hoja.setColumnWidth(i, Math.min(60, Math.max(16, c.etiqueta().length() + 4)) * 256);
                boolean numerica = c.campo() != null ? c.campo().tipo() == TipoCampo.NUMERO || c.campo().tipo() == TipoCampo.MONEDA
                        : Set.of("gravamenesPrevios", "prioridadVinculo", "topeVinculo", "valorComercial").contains(c.codigo());
                if (!numerica) {
                    hoja.setDefaultColumnStyle(i, texto);
                }
                String[] opciones = opciones(c);
                if (opciones != null) {
                    DataValidation v = dv.createValidation(dv.createExplicitListConstraint(opciones),
                            new CellRangeAddressList(2, 5001, i, i));
                    v.setShowErrorBox(true);
                    v.setSuppressDropDownArrow(true);
                    hoja.addValidationData(v);
                }
            }
            hoja.createFreezePane(0, 2);

            Sheet ins = wb.createSheet("Instrucciones");
            String[] notas = {
                    "Plantilla de carga masiva — " + tipo.tipo().nombre + " (" + tipo.tipo().codigo + "), versión " + tipo.version().numero,
                    "1. Diligencia una garantía por fila desde la fila 3 de la hoja Garantias. No modifiques las filas 1 y 2.",
                    "2. Las columnas con * son obligatorias al crear. Para actualizar basta la referencia y los datos que cambian.",
                    "3. Si la referencia (o la llave natural del tipo) ya existe, la fila ACTUALIZA esa garantía: se confirma aparte.",
                    "4. No se admiten fórmulas: escribe valores. Fechas en formato AAAA-MM-DD. Números sin separador de miles.",
                    "5. La carga la aprueba un Director de Operaciones distinto de quien la crea (maker–checker).",
                    "Datos personales: usa este archivo solo por canales autorizados del Banco (Ley 1581 de 2012)."};
            for (int i = 0; i < notas.length; i++) {
                ins.createRow(i).createCell(0).setCellValue(notas[i]);
            }
            Row h = ins.createRow(notas.length + 1);
            String[] enc = {"Código", "Columna", "Obligatoria al crear", "Formato u opciones", "Ayuda"};
            for (int i = 0; i < enc.length; i++) {
                Cell cell = h.createCell(i);
                cell.setCellValue(enc[i]);
                cell.setCellStyle(titulo);
            }
            for (int i = 0; i < columnas.size(); i++) {
                Columna c = columnas.get(i);
                Row r = ins.createRow(notas.length + 2 + i);
                r.createCell(0).setCellValue(c.codigo());
                r.createCell(1).setCellValue(c.etiqueta());
                r.createCell(2).setCellValue(c.requeridaAlCrear() ? "Sí" : "No");
                r.createCell(3).setCellValue(c.formato());
                r.createCell(4).setCellValue(c.ayuda() == null ? "" : c.ayuda());
            }
            ins.setColumnWidth(0, 28 * 256);
            ins.setColumnWidth(1, 40 * 256);
            ins.setColumnWidth(2, 20 * 256);
            ins.setColumnWidth(3, 60 * 256);
            ins.setColumnWidth(4, 80 * 256);
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo generar la plantilla", e);
        }
    }

    private static String[] opciones(Columna c) {
        if (c.campo() == null) {
            return switch (c.codigo()) {
                case "clienteTipoDocumento" -> TIPOS_DOCUMENTO.toArray(String[]::new);
                case "tipoVinculo" -> new String[]{"ABIERTA", "CERRADA"};
                case "tipoValoracion" -> TIPOS_VALORACION.toArray(String[]::new);
                case "moneda" -> new String[]{"COP", "USD", "EUR"};
                default -> null;
            };
        }
        if (c.campo().tipo() == TipoCampo.BOOLEANO) {
            return new String[]{"SI", "NO"};
        }
        if (c.campo().tipo() == TipoCampo.LISTA) {
            String[] ops = c.campo().opciones().stream().filter(CampoDefinicion.Opcion::activo)
                    .map(CampoDefinicion.Opcion::id).toArray(String[]::new);
            // Excel limita la lista explícita a 255 caracteres.
            return String.join(",", ops).length() <= 250 ? ops : null;
        }
        return null;
    }

    // ------------------------------------------------------------------ lectura

    public static Lectura leer(String nombre, byte[] contenido, List<Columna> columnas, int maxFilas) {
        String n = nombre == null ? "" : nombre.toLowerCase(Locale.ROOT);
        boolean zip = contenido.length > 4 && contenido[0] == 'P' && contenido[1] == 'K';
        if (n.endsWith(".xlsx")) {
            if (!zip) {
                throw Errores.invalido("ARCHIVO_INVALIDO", "El archivo no es un Excel .xlsx válido", List.of());
            }
            return leerExcel(contenido, columnas, maxFilas);
        }
        if (n.endsWith(".csv") || n.endsWith(".txt")) {
            if (zip) {
                throw Errores.invalido("ARCHIVO_INVALIDO", "El archivo tiene extensión .csv pero su contenido es un libro de Excel", List.of());
            }
            return leerCsv(contenido, columnas, maxFilas);
        }
        throw Errores.invalido("FORMATO_NO_SOPORTADO", "Formato no soportado: usa la plantilla .xlsx o un .csv", List.of());
    }

    private static Lectura leerExcel(byte[] contenido, List<Columna> columnas, int maxFilas) {
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(contenido))) {
            Sheet hoja = wb.getSheet("Garantias") != null ? wb.getSheet("Garantias") : wb.getSheetAt(0);
            List<List<String>> crudas = new ArrayList<>();
            List<List<String>> formulas = new ArrayList<>();
            int ultima = hoja.getLastRowNum();
            if (ultima > maxFilas + 2) {
                throw Errores.invalido("DEMASIADAS_FILAS", "El archivo supera el máximo de " + maxFilas + " filas", List.of());
            }
            int ancho = 0;
            for (int i = 0; i <= Math.min(1, ultima); i++) {
                Row r = hoja.getRow(i);
                ancho = Math.max(ancho, r == null ? 0 : r.getLastCellNum());
            }
            for (int i = 0; i <= ultima; i++) {
                Row r = hoja.getRow(i);
                List<String> valores = new ArrayList<>();
                List<String> conFormula = new ArrayList<>();
                for (int j = 0; j < ancho; j++) {
                    Cell c = r == null ? null : r.getCell(j);
                    if (c != null && c.getCellType() == CellType.FORMULA) {
                        conFormula.add(String.valueOf(j));
                        valores.add("");
                    } else {
                        valores.add(texto(c));
                    }
                }
                crudas.add(valores);
                formulas.add(conFormula);
            }
            return estructurar(crudas, formulas, columnas);
        } catch (Errores.NegocioException e) {
            throw e;
        } catch (Exception e) {
            throw Errores.invalido("ARCHIVO_INVALIDO", "No se pudo leer el archivo de Excel", List.of(String.valueOf(e.getMessage())));
        }
    }

    private static String texto(Cell c) {
        if (c == null) {
            return "";
        }
        return switch (c.getCellType()) {
            case STRING -> c.getStringCellValue().trim();
            case BOOLEAN -> c.getBooleanCellValue() ? "SI" : "NO";
            case NUMERIC -> DateUtil.isCellDateFormatted(c)
                    ? c.getLocalDateTimeCellValue().toLocalDate().toString()
                    : BigDecimal.valueOf(c.getNumericCellValue()).stripTrailingZeros().toPlainString();
            default -> "";
        };
    }

    private static Lectura leerCsv(byte[] contenido, List<Columna> columnas, int maxFilas) {
        String texto = decodificar(contenido);
        if (texto.startsWith("﻿")) {
            texto = texto.substring(1);
        }
        String primera = texto.lines().findFirst().orElse("");
        char sep = primera.chars().filter(ch -> ch == ';').count() >= primera.chars().filter(ch -> ch == ',').count() ? ';' : ',';
        List<List<String>> crudas = csv(texto, sep);
        if (crudas.size() > maxFilas + 2) {
            throw Errores.invalido("DEMASIADAS_FILAS", "El archivo supera el máximo de " + maxFilas + " filas", List.of());
        }
        List<List<String>> formulas = new ArrayList<>();
        for (List<String> fila : crudas) {
            List<String> f = new ArrayList<>();
            for (int j = 0; j < fila.size(); j++) {
                if (inyeccion(fila.get(j))) {
                    f.add(String.valueOf(j));
                    fila.set(j, "");
                }
            }
            formulas.add(f);
        }
        return estructurar(crudas, formulas, columnas);
    }

    /** Contenido que una hoja de cálculo interpretaría como fórmula (CSV injection). */
    static boolean inyeccion(String v) {
        if (v == null || v.isEmpty()) {
            return false;
        }
        char c = v.charAt(0);
        if (c == '=' || c == '+' || c == '@' || c == '\t' || c == '\r') {
            return true;
        }
        return c == '-' && !v.matches("-\\d+([.,]\\d+)?");
    }

    private static String decodificar(byte[] contenido) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(contenido)).toString();
        } catch (CharacterCodingException e) {
            return new String(contenido, Charset.forName("windows-1252"));
        }
    }

    private static List<List<String>> csv(String texto, char sep) {
        List<List<String>> filas = new ArrayList<>();
        List<String> fila = new ArrayList<>();
        StringBuilder celda = new StringBuilder();
        boolean comillas = false;
        for (int i = 0; i < texto.length(); i++) {
            char c = texto.charAt(i);
            if (comillas) {
                if (c == '"' && i + 1 < texto.length() && texto.charAt(i + 1) == '"') {
                    celda.append('"');
                    i++;
                } else if (c == '"') {
                    comillas = false;
                } else {
                    celda.append(c);
                }
            } else if (c == '"' && celda.isEmpty()) {
                comillas = true;
            } else if (c == sep) {
                fila.add(celda.toString().trim());
                celda.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < texto.length() && texto.charAt(i + 1) == '\n') {
                    i++;
                }
                fila.add(celda.toString().trim());
                celda.setLength(0);
                filas.add(fila);
                fila = new ArrayList<>();
            } else {
                celda.append(c);
            }
        }
        if (!celda.isEmpty() || !fila.isEmpty()) {
            fila.add(celda.toString().trim());
            filas.add(fila);
        }
        return filas;
    }

    /**
     * Ubica la fila de códigos (fila 1 o 2), valida la estructura y arma las filas de datos. Las
     * filas completamente vacías se ignoran.
     */
    private static Lectura estructurar(List<List<String>> crudas, List<List<String>> formulas, List<Columna> columnas) {
        Set<String> conocidas = columnas.stream().map(Columna::codigo).collect(Collectors.toSet());
        int filaCodigos = -1;
        for (int i = 0; i < Math.min(2, crudas.size()); i++) {
            List<String> noVacias = crudas.get(i).stream().filter(s -> !s.isBlank()).toList();
            if (!noVacias.isEmpty() && noVacias.stream().filter(conocidas::contains).count() * 2 > noVacias.size()) {
                filaCodigos = i;
                break;
            }
        }
        if (filaCodigos < 0) {
            return new Lectura(List.of(), List.of("No se encontró la fila de códigos de columna. Usa la plantilla del tipo sin modificar las filas 1 y 2."));
        }
        List<String> encabezado = crudas.get(filaCodigos);
        List<String> errores = new ArrayList<>();
        Set<String> vistas = new HashSet<>();
        for (String c : encabezado) {
            if (c.isBlank()) {
                continue;
            }
            if (!conocidas.contains(c)) {
                errores.add("La columna '" + c + "' no pertenece a la plantilla de este tipo o de esta versión");
            } else if (!vistas.add(c)) {
                errores.add("La columna '" + c + "' está repetida");
            }
        }
        columnas.stream().filter(Columna::requeridaAlCrear).map(Columna::codigo)
                .filter(c -> !"referencia".equals(c) && !vistas.contains(c))
                .forEach(c -> errores.add("Falta la columna obligatoria '" + c + "'"));
        if (!vistas.contains("referencia")) {
            errores.add("Falta la columna obligatoria 'referencia'");
        }
        List<FilaLeida> filas = new ArrayList<>();
        if (!errores.isEmpty()) {
            return new Lectura(filas, errores);
        }
        for (int i = filaCodigos + 1; i < crudas.size(); i++) {
            List<String> valores = crudas.get(i);
            if (valores.stream().allMatch(String::isBlank) && formulas.get(i).isEmpty()) {
                continue;
            }
            Map<String, String> m = new LinkedHashMap<>();
            for (int j = 0; j < encabezado.size(); j++) {
                if (!encabezado.get(j).isBlank()) {
                    m.put(encabezado.get(j), j < valores.size() ? valores.get(j) : "");
                }
            }
            List<String> errs = new ArrayList<>();
            for (String j : formulas.get(i)) {
                int k = Integer.parseInt(j);
                String col = k < encabezado.size() ? encabezado.get(k) : "columna " + (k + 1);
                errs.add("'" + col + "': contiene una fórmula o un valor que empieza por =, +, - o @; escribe el valor sin fórmula");
            }
            filas.add(new FilaLeida(i + 1, m, errs));
        }
        return new Lectura(filas, List.of());
    }
}
