package co.bancopopular.garantias360.eventos;

import co.bancopopular.garantias360.comun.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.support.MessageBuilder;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Publica un evento del outbox con formato CloudEvents (RF-2104). Kafka en ambientes integrados;
 * registro en log cuando g360.kafka.habilitado=false (desarrollo sin broker).
 */
public interface PublicadorEventos {

    void publicar(EventoSaliente evento) throws Exception;

    record EventoSaliente(String id, String tipo, int version, String agregado, String agregadoId, String correlationId,
                          String causationId, Instant creadoEn, Object data) {

        Map<String, Object> cloudEvent() {
            Map<String, Object> ce = new LinkedHashMap<>();
            ce.put("specversion", "1.0");
            ce.put("id", id);
            ce.put("type", "co.bancopopular.garantias." + tipo + ".v" + version);
            ce.put("source", "/garantias360/" + agregado);
            ce.put("subject", agregadoId);
            ce.put("time", creadoEn.toString());
            ce.put("datacontenttype", "application/json");
            ce.put("correlationid", correlationId);
            ce.put("causationid", causationId);
            ce.put("data", data);
            return ce;
        }

        String topico() {
            return switch (agregado) {
                case "CalculoCobertura" -> "bp.garantias.cobertura.v1";
                case "Regla" -> "bp.garantias.reglas.v1";
                case "Alerta" -> "bp.garantias.alertas.v1";
                default -> "bp.garantias.garantia.v1";
            };
        }
    }

    @Configuration
    class Configuracion {

        private static final Logger log = LoggerFactory.getLogger(PublicadorEventos.class);

        @Bean
        @ConditionalOnProperty(name = "g360.kafka.habilitado", havingValue = "true")
        PublicadorEventos kafka(KafkaTemplate<String, String> kafka,
                                @Value("${g360.kafka.timeout-ms:5000}") long timeoutMs) {
            return evento -> kafka.send(MessageBuilder.withPayload(Json.canonico(evento.cloudEvent()))
                            .setHeader(KafkaHeaders.TOPIC, evento.topico())
                            .setHeader(KafkaHeaders.KEY, evento.agregadoId())
                            .setHeader("ce_id", evento.id())
                            .setHeader("ce_type", evento.tipo())
                            .setHeader("correlation_id", evento.correlationId())
                            .build())
                    .get(timeoutMs, TimeUnit.MILLISECONDS);
        }

        @Bean
        @ConditionalOnProperty(name = "g360.kafka.habilitado", havingValue = "false", matchIfMissing = true)
        PublicadorEventos registro() {
            return evento -> log.info("Evento publicado (sin broker) topico={} tipo={} id={} correlationId={}",
                    evento.topico(), evento.tipo(), evento.id(), evento.correlationId());
        }
    }
}
