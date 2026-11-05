package ledger.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import ledger.persistence.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

@Component
public class OutboxPublisher {
    private static final Logger log= LoggerFactory.getLogger(OutboxPublisher.class);
    private final OutboxRepository outbox;
    private final RabbitTemplate rabbit;
    private final ObjectMapper json;
    private final String exchange;
    private final String routing;
    private final String instanceId;
    public OutboxPublisher(OutboxRepository outbox,RabbitTemplate rabbit,ObjectMapper json,
                           @Value("${ledger.outbox.exchange}") String exchange,
                           @Value("${ledger.outbox.routing-key}") String routing,
                           @Value("${ledger.instance-id:local}") String instanceId) {
        this.outbox=outbox;this.rabbit=rabbit;this.json=json;this.exchange=exchange;this.routing=routing;this.instanceId=instanceId;
    }

    @Scheduled(fixedDelayString="${ledger.outbox.poll-ms:1000}")
    public void publishPending() {
        for(OutboxRepository.OutboxEvent event:outbox.findPending(20)) publish(event);
    }

    private void publish(OutboxRepository.OutboxEvent event) {
        String id=event.eventId();
        try {
            MessageProperties properties=new MessageProperties();
            properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
            properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            properties.setMessageId(id);
            Message message=new Message(event.payload().getBytes(StandardCharsets.UTF_8),properties);
            CorrelationData correlation=new CorrelationData(id);
            rabbit.send(exchange,routing,message,correlation);
            CorrelationData.Confirm confirm=correlation.getFuture().get(10, TimeUnit.SECONDS);
            if(!confirm.ack()) throw new IllegalStateException("Broker negatively confirmed outbox event");
            if(correlation.getReturned()!=null) throw new IllegalStateException("Outbox event was unroutable");
            outbox.markPublished(id);
            log.info("ledger_outbox_published instanceId={} eventId={} correlationId={}",
                    instanceId,id,extractRequestId(event.payload()));
        } catch(Exception ex) {
            outbox.recordFailure(id,safeError(ex));
            log.error("ledger_outbox_publish_failed instanceId={} eventId={} reason={}",instanceId,id,safeError(ex));
        }
    }
    private String safeError(Exception ex) {
        String text=ex.getClass().getSimpleName();
        return text.length()>200?text.substring(0,200):text;
    }
    private String extractRequestId(String payload) {
        try { var tree=json.readTree(payload); var node=tree.get("requestId"); return node==null?"-":node.asText(); }
        catch(Exception ex){return "-";}
    }
}
