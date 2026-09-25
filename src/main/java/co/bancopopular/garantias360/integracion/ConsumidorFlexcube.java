package co.bancopopular.garantias360.integracion;

import co.bancopopular.garantias360.comun.Contexto;
import co.bancopopular.garantias360.comun.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Consumidor Kafka de eventos de obligación de Flexcube (RF-2103). Los reintentos y la DLQ los
 * gestiona el DefaultErrorHandler de Spring Kafka (g360.kafka.*); la idempotencia, el inbox.
 */
@Component
@ConditionalOnProperty(name = "g360.kafka.habilitado", havingValue = "true")
public class ConsumidorFlexcube {

    private static final Logger log = LoggerFactory.getLogger(ConsumidorFlexcube.class);

    private final FlexcubeService servicio;

    public ConsumidorFlexcube(FlexcubeService servicio) {
        this.servicio = servicio;
    }

    @KafkaListener(topics = "${g360.kafka.topico-flexcube:bp.flexcube.obligaciones.v1}", groupId = "garantias360")
    public void consumir(String mensaje, @Header(name = "correlation_id", required = false) String correlation,
                         @Header(KafkaHeaders.RECEIVED_KEY) String clave) throws Exception {
        var evento = Json.CANONICO.readValue(mensaje, FlexcubeService.EventoObligacion.class);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("flexcube", "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_SISTEMA"))));
        try {
            Contexto.conCorrelation(correlation == null ? UUID.randomUUID().toString() : correlation, () -> {
                var r = servicio.procesar(evento);
                log.info("Evento Flexcube {} ({}) clave={}: {}", evento.eventoId(), evento.tipo(), clave, r.estado());
            });
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
