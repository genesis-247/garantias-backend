package co.bancopopular.garantias360.comun;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamWriteFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * JSON canónico (claves ordenadas, decimales sin notación científica) y SHA-256 sobre él.
 * Es la base de la integridad: mismas entradas → mismo hash (RF-0804, RF-1502).
 */
public final class Json {

    public static final ObjectMapper CANONICO = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            // Decimales exactos: 1.00 no se convierte en 1. Sin esto un cálculo no es reproducible bit a bit.
            .nodeFactory(JsonNodeFactory.withExactBigDecimals(true))
            .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
            .build();

    private Json() {
    }

    public static String canonico(Object valor) {
        try {
            JsonNode arbol = CANONICO.valueToTree(valor);
            return CANONICO.writeValueAsString(CANONICO.treeToValue(arbol, Object.class));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo serializar a JSON canónico", e);
        }
    }

    public static JsonNode arbol(Object valor) {
        return CANONICO.valueToTree(valor);
    }

    public static <T> T leer(JsonNode nodo, Class<T> tipo) {
        try {
            return CANONICO.treeToValue(nodo, tipo);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON inválido para " + tipo.getSimpleName(), e);
        }
    }

    public static String sha256(String texto) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(texto.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sha256Bytes(byte[] contenido) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(contenido));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String hash(Object valor) {
        return sha256(canonico(valor));
    }
}
